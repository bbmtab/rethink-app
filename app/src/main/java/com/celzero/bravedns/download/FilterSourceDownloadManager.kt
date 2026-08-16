/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.download

import Logger
import Logger.LOG_TAG_DOWNLOAD
import android.os.Build
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceRepository
import com.celzero.bravedns.database.FilterSourceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Owns the HTTP download, streaming validation, SHA-256 computation, and atomic promotion
 * lifecycle for Advanced Filter Sources (Phase 1d B2).
 *
 * Constraints & Guarantees (docs/PLAN-FILTER-SOURCE-MANAGER.md §1, §5–§10):
 *  - Streaming only: responses are never buffered entirely in RAM.
 *  - Hard size cap: 25 MiB ([MAX_DOWNLOAD_BYTES]). Both header Content-Length and real streamed
 *    bytes are checked independently.
 *  - SHA-256 is computed concurrently during streaming via [MessageDigest].
 *  - Conditional HTTP: If-None-Match and If-Modified-Since headers are sent when available.
 *  - 304 Not Modified: preserves existing current.txt, checksum, and diagnostics without rewriting.
 *  - Sanity validation: rejects obvious HTML/error pages with bounded prefix sniffing.
 *  - Safe filesystem promotion: writes to `download.tmp`, promotes atomically to `current.txt`.
 *    On failure, last-known-good `current.txt` is preserved and `download.tmp` is cleaned up.
 *  - Database persistence: updates [FilterSource] status, ETag, Last-Modified, checksum, and timestamps.
 *  - Isolation: operates strictly per source directory; never touches compiler or FilterEngine.
 */
class FilterSourceDownloadManager(
    private val repository: FilterSourceRepository,
    private val httpClient: OkHttpClient = defaultOkHttpClient()
) {

    companion object {
        private const val TAG = "FilterSourceDownloader"

        /** Maximum allowed filter list size in bytes (25 MiB). */
        const val MAX_DOWNLOAD_BYTES: Long = 25L * 1024L * 1024L

        /** Maximum bytes examined for HTML error page detection. */
        private const val HTML_SNIFF_BUFFER_SIZE = 1024

        /** Default OkHttpClient instance with safe timeouts. */
        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    sealed class DownloadResult {
        data class Success(
            val sourceId: Int,
            val notModified: Boolean,
            val checksum: String?,
            val bytesDownloaded: Long
        ) : DownloadResult()

        data class Failure(
            val sourceId: Int,
            val errorMessage: String,
            val httpCode: Int? = null
        ) : DownloadResult()
    }

    /**
     * Download or refresh a single [FilterSource] by its ID.
     */
    suspend fun downloadSource(sourceId: Int): DownloadResult = withContext(Dispatchers.IO) {
        val source = repository.getSourceById(sourceId)
            ?: return@withContext DownloadResult.Failure(
                sourceId,
                "FilterSource with id $sourceId not found in database"
            )
        downloadSource(source)
    }

    /**
     * Download or refresh a specific [FilterSource] instance.
     */
    suspend fun downloadSource(source: FilterSource): DownloadResult = withContext(Dispatchers.IO) {
        val sourceId = source.id
        Logger.i(LOG_TAG_DOWNLOAD, "$TAG; starting download for source id=$sourceId, name='${source.name}', url='${source.url}'")

        val fileStore = repository.getFileStore()
        val sourceDir = fileStore.sourceDirectory(sourceId)
        val tempFile = fileStore.downloadTempFile(sourceId)
        val currentFile = fileStore.currentFile(sourceId)

        // 1. Mark status as IN_PROGRESS and clear previous error message in Room
        repository.updateStatus(sourceId, FilterSourceStatus.IN_PROGRESS, null)

        // 2. Ensure parent directory exists and clean any stale download.tmp
        try {
            if (!sourceDir.exists()) {
                sourceDir.mkdirs()
            }
            if (tempFile.exists()) {
                tempFile.delete()
            }
        } catch (e: Exception) {
            val err = "Failed to prepare source directory: ${e.message}"
            Logger.e(LOG_TAG_DOWNLOAD, "$TAG; $err", e)
            repository.updateDownloadFailure(sourceId, err)
            return@withContext DownloadResult.Failure(sourceId, err)
        }

        // 3. Build HTTP request with conditional headers
        val requestBuilder = Request.Builder().url(source.url)
        if (!source.etag.isNullOrBlank()) {
            requestBuilder.header("If-None-Match", source.etag)
        }
        if (!source.lastModified.isNullOrBlank()) {
            requestBuilder.header("If-Modified-Since", source.lastModified)
        }

        val request = requestBuilder.build()

        var response: Response? = null
        try {
            response = httpClient.newCall(request).execute()
            val code = response.code

            // Handle 304 Not Modified
            if (code == 304) {
                Logger.i(LOG_TAG_DOWNLOAD, "$TAG; source id=$sourceId received 304 Not Modified")
                val freshEtag = response.header("ETag") ?: source.etag
                val freshLastModified = response.header("Last-Modified") ?: source.lastModified

                repository.updateDownloadNotModified(
                    id = sourceId,
                    etag = freshEtag,
                    lastModified = freshLastModified
                )

                return@withContext DownloadResult.Success(
                    sourceId = sourceId,
                    notModified = true,
                    checksum = source.checksum,
                    bytesDownloaded = 0L
                )
            }

            // Handle 200 OK
            if (code == 200) {
                val body = response.body
                    ?: run {
                        val err = "Empty HTTP 200 response body"
                        Logger.e(LOG_TAG_DOWNLOAD, "$TAG; source id=$sourceId: $err")
                        cleanTempFile(tempFile)
                        repository.updateDownloadFailure(sourceId, err)
                        return@withContext DownloadResult.Failure(sourceId, err, code)
                    }

                // G3-A: Check Content-Length header if provided
                val contentLength = body.contentLength()
                if (contentLength > MAX_DOWNLOAD_BYTES) {
                    val err = "Response Content-Length ($contentLength bytes) exceeds maximum limit of 25 MiB"
                    Logger.e(LOG_TAG_DOWNLOAD, "$TAG; source id=$sourceId: $err")
                    cleanTempFile(tempFile)
                    repository.updateDownloadFailure(sourceId, err)
                    return@withContext DownloadResult.Failure(sourceId, err, code)
                }

                // Sanity check: Content-Type header rejection if explicitly HTML
                val contentType = response.header("Content-Type")?.lowercase()
                if (contentType != null && (contentType.contains("text/html") || contentType.contains("application/xhtml+xml"))) {
                    val err = "Response Content-Type ($contentType) indicates HTML document"
                    Logger.e(LOG_TAG_DOWNLOAD, "$TAG; source id=$sourceId: $err")
                    cleanTempFile(tempFile)
                    repository.updateDownloadFailure(sourceId, err)
                    return@withContext DownloadResult.Failure(sourceId, err, code)
                }

                // Stream response to download.tmp while computing SHA-256 and enforcing 25 MiB cap
                val streamResult = streamToFileWithDigest(body.byteStream(), tempFile)
                if (streamResult is StreamOutcome.Error) {
                    Logger.e(LOG_TAG_DOWNLOAD, "$TAG; source id=$sourceId stream failed: ${streamResult.message}")
                    cleanTempFile(tempFile)
                    repository.updateDownloadFailure(sourceId, streamResult.message)
                    return@withContext DownloadResult.Failure(sourceId, streamResult.message, code)
                }

                val outcome = streamResult as StreamOutcome.Success
                val digestHex = outcome.sha256Hex
                val bytesStreamed = outcome.bytesRead

                // Safe promotion from download.tmp to current.txt
                val promoted = safePromote(tempFile, currentFile)
                if (!promoted) {
                    val err = "Failed to atomically promote download.tmp to current.txt"
                    Logger.e(LOG_TAG_DOWNLOAD, "$TAG; source id=$sourceId: $err")
                    cleanTempFile(tempFile)
                    repository.updateDownloadFailure(sourceId, err)
                    return@withContext DownloadResult.Failure(sourceId, err, code)
                }

                // Persist new metadata in Room
                val etag = response.header("ETag")
                val lastModified = response.header("Last-Modified")

                repository.updateDownloadSuccess(
                    id = sourceId,
                    etag = etag,
                    lastModified = lastModified,
                    checksum = digestHex
                )

                Logger.i(
                    LOG_TAG_DOWNLOAD,
                    "$TAG; source id=$sourceId successfully downloaded ($bytesStreamed bytes, SHA-256=$digestHex)"
                )

                return@withContext DownloadResult.Success(
                    sourceId = sourceId,
                    notModified = false,
                    checksum = digestHex,
                    bytesDownloaded = bytesStreamed
                )
            }

            // Handle HTTP 4xx / 5xx or unexpected status
            val err = "HTTP error $code ${response.message}".trim()
            Logger.e(LOG_TAG_DOWNLOAD, "$TAG; source id=$sourceId failed with $err")
            cleanTempFile(tempFile)
            repository.updateDownloadFailure(sourceId, err)
            return@withContext DownloadResult.Failure(sourceId, err, code)

        } catch (e: Exception) {
            val err = "Network/IO error during download: ${e.message ?: e.javaClass.simpleName}"
            Logger.e(LOG_TAG_DOWNLOAD, "$TAG; source id=$sourceId exception: $err", e)
            cleanTempFile(tempFile)
            repository.updateDownloadFailure(sourceId, err)
            return@withContext DownloadResult.Failure(sourceId, err)
        } finally {
            response?.close()
        }
    }

    /**
     * Refresh all currently enabled FilterSources.
     */
    suspend fun refreshAllEnabled(): List<DownloadResult> = withContext(Dispatchers.IO) {
        val enabledSources = repository.getEnabledSources()
        Logger.i(LOG_TAG_DOWNLOAD, "$TAG; refreshing all enabled sources (${enabledSources.size} found)")
        val results = mutableListOf<DownloadResult>()
        for (source in enabledSources) {
            val result = downloadSource(source)
            results.add(result)
        }
        results
    }

    private sealed class StreamOutcome {
        data class Success(val bytesRead: Long, val sha256Hex: String) : StreamOutcome()
        data class Error(val message: String) : StreamOutcome()
    }

    /**
     * Stream [inputStream] into [destinationFile], computing SHA-256 and enforcing size & HTML guards.
     */
    private fun streamToFileWithDigest(
        inputStream: InputStream,
        destinationFile: File
    ): StreamOutcome {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytesRead = 0L
        val buffer = ByteArray(8192)
        var htmlSniffed = false
        val sniffBuffer = StringBuilder()

        try {
            FileOutputStream(destinationFile).use { fos ->
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break

                    totalBytesRead += read

                    // G3-B: Streaming byte counter exceeds 25 MiB
                    if (totalBytesRead > MAX_DOWNLOAD_BYTES) {
                        return StreamOutcome.Error("Downloaded data exceeded maximum size limit of 25 MiB")
                    }

                    // Bounded HTML prefix sniffing on the initial bytes
                    if (!htmlSniffed) {
                        val sniffLen = minOf(read, HTML_SNIFF_BUFFER_SIZE - sniffBuffer.length)
                        if (sniffLen > 0) {
                            sniffBuffer.append(String(buffer, 0, sniffLen, Charsets.UTF_8))
                        }
                        if (sniffBuffer.length >= HTML_SNIFF_BUFFER_SIZE || read < buffer.size) {
                            htmlSniffed = true
                            val snippet = sniffBuffer.toString().trim().lowercase()
                            if (snippet.startsWith("<!doctype html") ||
                                snippet.startsWith("<html") ||
                                snippet.startsWith("<?xml") && snippet.contains("<html")
                            ) {
                                return StreamOutcome.Error("Invalid filter list content: HTML document detected")
                            }
                        }
                    }

                    digest.update(buffer, 0, read)
                    fos.write(buffer, 0, read)
                }
                fos.flush()
            }

            if (totalBytesRead == 0L) {
                return StreamOutcome.Error("Downloaded filter list is empty (0 bytes)")
            }

            // Final check on snippet if stream was very small (< 1024 bytes)
            if (!htmlSniffed) {
                val snippet = sniffBuffer.toString().trim().lowercase()
                if (snippet.startsWith("<!doctype html") || snippet.startsWith("<html")) {
                    return StreamOutcome.Error("Invalid filter list content: HTML document detected")
                }
            }

            val hashBytes = digest.digest()
            val hexString = hashBytes.joinToString("") { "%02x".format(it) }
            return StreamOutcome.Success(totalBytesRead, hexString)

        } catch (e: Exception) {
            return StreamOutcome.Error("Stream write failed: ${e.message}")
        }
    }

    private fun safePromote(tempFile: File, currentFile: File): Boolean {
        if (!tempFile.exists()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    Files.move(
                        tempFile.toPath(),
                        currentFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                    true
                } catch (e: Exception) {
                    // Fallback to non-atomic replace if filesystem does not support ATOMIC_MOVE
                    Files.move(
                        tempFile.toPath(),
                        currentFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    true
                }
            } else {
                if (currentFile.exists()) {
                    currentFile.delete()
                }
                tempFile.renameTo(currentFile)
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_DOWNLOAD, "$TAG; error promoting temp file to ${currentFile.name}: ${e.message}", e)
            false
        }
    }

    private fun cleanTempFile(tempFile: File) {
        try {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_DOWNLOAD, "$TAG; failed to clean temp file ${tempFile.name}: ${e.message}")
        }
    }
}

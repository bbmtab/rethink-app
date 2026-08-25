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

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.NetworkType
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceCategory
import com.celzero.bravedns.database.FilterSourceDao
import com.celzero.bravedns.database.FilterSourceFileStore
import com.celzero.bravedns.database.FilterSourceRepository
import com.celzero.bravedns.database.FilterSourceStatus
import com.celzero.bravedns.scheduler.FilterUpdateWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FilterSourceDownloadManagerTest : KoinTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var dao: FilterSourceDao
    private lateinit var fileStore: FilterSourceFileStore
    private lateinit var repo: FilterSourceRepository
    private lateinit var appContext: Context

    private var mockResponseGenerator: ((Request) -> Response)? = null
    private val recordedRequests = mutableListOf<Request>()

    @Before
    fun setUp() {
        appContext = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.filterSourceDao()
        fileStore = FilterSourceFileStore(appContext)
        repo = FilterSourceRepository(dao, fileStore)
        recordedRequests.clear()
        mockResponseGenerator = null

        startKoin { modules(emptyList()) }
    }

    @After
    fun tearDown() {
        db.close()
        stopKoin()
        fileStore.rootDirectory().deleteRecursively()
    }

    private fun createTestHttpClient(): OkHttpClient {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            recordedRequests.add(request)
            val generator = mockResponseGenerator
                ?: error("mockResponseGenerator not set for request: ${request.url}")
            generator(request)
        }
        return OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // =========================================================================
    // G0 — Seed / Worker Concurrency Verification
    // =========================================================================
    @Test
    fun g0_concurrency_workerDoesNotCallEnsurePresets_andOperatesOnExistingRowsOnly() = runTest(testDispatcher) {
        val client = createTestHttpClient()
        val downloadManager = FilterSourceDownloadManager(repo, client)

        assertEquals("Table should initially have 0 rows in test DB", 0, repo.count())

        // Calling refreshAllEnabled directly (which is what FilterUpdateWorker delegates to)
        val results = downloadManager.refreshAllEnabled()
        assertEquals("Worker should find 0 sources and not seed presets", 0, results.size)
        assertEquals("Table row count must remain 0", 0, repo.count())
    }

    // =========================================================================
    // G1 — Conditional 304 Round Trip
    // =========================================================================
    @Test
    fun g1_conditional304_preservesCurrentFileAndChecksum() = runTest(testDispatcher) {
        val client = createTestHttpClient()
        val downloadManager = FilterSourceDownloadManager(repo, client)

        val source = repo.addSource(
            name = "Test List",
            url = "https://example.com/filters.txt",
            category = FilterSourceCategory.ADS
        )

        val testContent = "||example.com^\n||adservice.org^\n"
        val initialEtag = "\"b2-test-v1\""
        val initialLastModified = "Wed, 15 Aug 2026 12:00:00 GMT"

        // Request 1: 200 OK
        mockResponseGenerator = { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("ETag", initialEtag)
                .header("Last-Modified", initialLastModified)
                .body(testContent.toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val result1 = downloadManager.downloadSource(source.id)
        assertTrue(result1 is FilterSourceDownloadManager.DownloadResult.Success)
        val success1 = result1 as FilterSourceDownloadManager.DownloadResult.Success
        assertFalse("First request is not 304", success1.notModified)
        assertEquals(testContent.toByteArray().size.toLong(), success1.bytesDownloaded)

        val sourceAfter1 = repo.getSourceById(source.id)!!
        assertEquals(FilterSourceStatus.SUCCESS, sourceAfter1.lastUpdateStatus)
        assertEquals(initialEtag, sourceAfter1.etag)
        assertEquals(initialLastModified, sourceAfter1.lastModified)
        assertNotNull(sourceAfter1.checksum)

        val currentFile = fileStore.currentFile(source.id)
        assertTrue(currentFile.exists())
        val shaBefore304 = calculateSha256(currentFile)
        val sizeBefore304 = currentFile.length()
        val checksumBefore304 = sourceAfter1.checksum

        // Request 2: 304 Not Modified
        mockResponseGenerator = { req ->
            // Verify conditional request headers were sent
            assertEquals(initialEtag, req.header("If-None-Match"))
            assertEquals(initialLastModified, req.header("If-Modified-Since"))

            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(304)
                .message("Not Modified")
                .body("".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val result2 = downloadManager.downloadSource(source.id)
        assertTrue(result2 is FilterSourceDownloadManager.DownloadResult.Success)
        val success2 = result2 as FilterSourceDownloadManager.DownloadResult.Success
        assertTrue("Second request is 304 notModified", success2.notModified)
        assertEquals(0L, success2.bytesDownloaded)

        val sourceAfter2 = repo.getSourceById(source.id)!!
        assertEquals(FilterSourceStatus.SUCCESS, sourceAfter2.lastUpdateStatus)
        assertNull(sourceAfter2.errorMessage)
        assertEquals(checksumBefore304, sourceAfter2.checksum)

        // Verify file was untouched
        assertEquals(shaBefore304, calculateSha256(currentFile))
        assertEquals(sizeBefore304, currentFile.length())
        assertFalse(fileStore.downloadTempFile(source.id).exists())
    }

    // =========================================================================
    // G1-R — Cancellation and missing-current conditional-request regressions
    // =========================================================================
    @Test
    fun cancellationException_rethrowsAndRestoresPriorStatus() =
        runTest(testDispatcher) {
            val client =
                OkHttpClient.Builder()
                    .addInterceptor {
                        throw CancellationException("test cancellation")
                    }
                    .build()
            val downloadManager = FilterSourceDownloadManager(repo, client)

            val source =
                repo.addSource(
                    name = "Cancellation Source",
                    url = "https://example.com/cancel.txt",
                    category = FilterSourceCategory.SECURITY
                )
            val before = repo.getSourceById(source.id)!!

            var caught: CancellationException? = null
            try {
                downloadManager.downloadSource(source.id)
            } catch (e: CancellationException) {
                caught = e
            }

            assertNotNull("CancellationException must be rethrown", caught)
            assertEquals("test cancellation", caught?.message)

            val after = repo.getSourceById(source.id)!!
            assertEquals(
                "Cancellation must restore the previous status",
                before.lastUpdateStatus,
                after.lastUpdateStatus
            )
            assertEquals(
                "Cancellation must restore the previous error",
                before.errorMessage,
                after.errorMessage
            )
            assertFalse(fileStore.downloadTempFile(source.id).exists())
            assertFalse(fileStore.currentFile(source.id).exists())
        }

    @Test
    fun missingCurrentFile_withCachedValidators_usesUnconditionalRequest() =
        runTest(testDispatcher) {
            val client = createTestHttpClient()
            val downloadManager = FilterSourceDownloadManager(repo, client)

            val source =
                repo.addSource(
                    name = "Missing Current Source",
                    url = "https://example.com/missing-current.txt",
                    category = FilterSourceCategory.PRIVACY
                )

            repo.updateDownloadSuccess(
                id = source.id,
                etag = "\"stale-etag\"",
                lastModified = "Wed, 15 Aug 2026 12:00:00 GMT",
                checksum = "stale-checksum"
            )
            assertFalse(fileStore.currentFile(source.id).exists())

            val payload = "||fresh.example^\n"
            mockResponseGenerator = { request ->
                assertNull(request.header("If-None-Match"))
                assertNull(request.header("If-Modified-Since"))

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody("text/plain".toMediaType()))
                    .build()
            }

            val result = downloadManager.downloadSource(source.id)

            assertTrue(result is FilterSourceDownloadManager.DownloadResult.Success)
            val success =
                result as FilterSourceDownloadManager.DownloadResult.Success
            assertFalse(success.notModified)
            assertEquals(1, recordedRequests.size)

            val currentFile = fileStore.currentFile(source.id)
            assertTrue(currentFile.exists())
            assertEquals(payload, currentFile.readText())
            assertFalse(fileStore.downloadTempFile(source.id).exists())
        }

    // =========================================================================
    // G2 — 200 + Streaming SHA-256 + Atomic Promotion
    // =========================================================================
    @Test
    fun g2_streamingSha256AndPromotion_matchesExactFileDigest() = runTest(testDispatcher) {
        val client = createTestHttpClient()
        val downloadManager = FilterSourceDownloadManager(repo, client)

        val source = repo.addSource(
            name = "AdGuard Base",
            url = "https://example.com/adguard.txt",
            category = FilterSourceCategory.ADS
        )

        val payload = "||doubleclick.net^\n||google-analytics.com^\n@@||allowed.org^\n"
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        mockResponseGenerator = { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("ETag", "\"tag-g2\"")
                .body(payload.toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val result = downloadManager.downloadSource(source.id)
        assertTrue(result is FilterSourceDownloadManager.DownloadResult.Success)
        val success = result as FilterSourceDownloadManager.DownloadResult.Success

        assertEquals(expectedSha, success.checksum)

        val currentFile = fileStore.currentFile(source.id)
        assertTrue("current.txt must exist", currentFile.exists())
        assertEquals(expectedSha, calculateSha256(currentFile))
        assertFalse("download.tmp must not exist post-promotion", fileStore.downloadTempFile(source.id).exists())

        val dbSource = repo.getSourceById(source.id)!!
        assertEquals(expectedSha, dbSource.checksum)
        assertEquals(FilterSourceStatus.SUCCESS, dbSource.lastUpdateStatus)
        assertNull(dbSource.errorMessage)
    }

    // =========================================================================
    // G3 — 25 MiB Hard Cap: G3-A (Header) and G3-B (Streaming Counter)
    // =========================================================================
    @Test
    fun g3a_headerOversize_rejectedBeforeBodyStream() = runTest(testDispatcher) {
        val client = createTestHttpClient()
        val downloadManager = FilterSourceDownloadManager(repo, client)

        val source = repo.addSource(
            name = "Giant Filter",
            url = "https://example.com/giant.txt",
            category = FilterSourceCategory.OTHER
        )

        val oversizeBytes = 26L * 1024L * 1024L // 26 MiB

        mockResponseGenerator = { req ->
            val fakeBody = object : ResponseBody() {
                override fun contentType() = "text/plain".toMediaType()
                override fun contentLength() = oversizeBytes
                override fun source(): BufferedSource = Buffer()
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(fakeBody)
                .build()
        }

        val result = downloadManager.downloadSource(source.id)
        assertTrue(result is FilterSourceDownloadManager.DownloadResult.Failure)
        val failure = result as FilterSourceDownloadManager.DownloadResult.Failure
        assertTrue(failure.errorMessage.contains("exceeds maximum limit of 25 MiB"))

        val dbSource = repo.getSourceById(source.id)!!
        assertEquals(FilterSourceStatus.FAILED, dbSource.lastUpdateStatus)
        assertNotNull(dbSource.errorMessage)
        assertFalse(fileStore.currentFile(source.id).exists())
        assertFalse(fileStore.downloadTempFile(source.id).exists())
    }

    @Test
    fun g3b_lyingContentLength_streamCounterAbortsWhenExceeding25MiB() = runTest(testDispatcher) {
        val client = createTestHttpClient()
        val downloadManager = FilterSourceDownloadManager(repo, client)

        val source = repo.addSource(
            name = "Lying Filter",
            url = "https://example.com/lying.txt",
            category = FilterSourceCategory.OTHER
        )

        // Seed with a known-good current.txt
        val currentFile = fileStore.currentFile(source.id)
        currentFile.parentFile?.mkdirs()
        currentFile.writeText("||good.com^\n")
        val initialSha = calculateSha256(currentFile)
        repo.updateDownloadSuccess(source.id, null, null, initialSha)

        // Build a bounded (26 MiB) InputStream that lies about Content-Length (claims 100 bytes).
        // The downloader's streaming counter must detect exceedance at 25 MiB+1 before the stream ends.
        val chunk = ByteArray(8192) { 'A'.code.toByte() }
        // 26 MiB total: 8192 * 3328 = 27,294,976 ~ 26 MiB
        val totalChunks = 3328
        val fakeBoundedStream: InputStream = object : InputStream() {
            var emitted = 0L
            override fun read(): Int {
                if (emitted >= totalChunks * chunk.size.toLong()) return -1
                emitted++
                return 'A'.code
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (emitted >= totalChunks * chunk.size.toLong()) return -1
                val toRead = minOf(len, chunk.size, (totalChunks * chunk.size - emitted).toInt())
                System.arraycopy(chunk, 0, b, off, toRead)
                emitted += toRead
                return toRead
            }
            override fun available(): Int = (totalChunks * chunk.size - emitted).toInt().coerceAtLeast(0)
        }

        mockResponseGenerator = { req ->
            val fakeBody = object : ResponseBody() {
                override fun contentType() = "text/plain".toMediaType()
                // Lie about Content-Length (claims 100 bytes)
                override fun contentLength() = 100L
                // TRUE streaming: wraps InputStream without buffering entire body
                override fun source(): BufferedSource = fakeBoundedStream.source().buffer()
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(fakeBody)
                .build()
        }

        val result = downloadManager.downloadSource(source.id)
        assertTrue(result is FilterSourceDownloadManager.DownloadResult.Failure)
        val failure = result as FilterSourceDownloadManager.DownloadResult.Failure
        assertTrue(failure.errorMessage.contains("exceeded maximum size limit of 25 MiB"))

        val dbSource = repo.getSourceById(source.id)!!
        assertEquals(FilterSourceStatus.FAILED, dbSource.lastUpdateStatus)
        assertEquals(initialSha, dbSource.checksum)

        // Verify previous current.txt is preserved untouched
        assertTrue(currentFile.exists())
        assertEquals(initialSha, calculateSha256(currentFile))
        assertFalse(fileStore.downloadTempFile(source.id).exists())
    }

    // =========================================================================
    // G4 — Invalid Response (HTML / Error) Preservation
    // =========================================================================
    @Test
    fun g4_invalidHtmlResponse_preservesCurrentFileAndMarksFailed() = runTest(testDispatcher) {
        val client = createTestHttpClient()
        val downloadManager = FilterSourceDownloadManager(repo, client)

        val source = repo.addSource(
            name = "Valid Then Broken",
            url = "https://example.com/broken.txt",
            category = FilterSourceCategory.ADS
        )

        // Seed initial valid file
        val currentFile = fileStore.currentFile(source.id)
        currentFile.parentFile?.mkdirs()
        currentFile.writeText("||valid-filter.org^\n")
        val initialSha = calculateSha256(currentFile)
        repo.updateDownloadSuccess(source.id, "\"tag-v1\"", null, initialSha)

        val htmlPayload = "<!DOCTYPE html>\n<html><head><title>404 Not Found</title></head><body>Error</body></html>"

        mockResponseGenerator = { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/html; charset=UTF-8")
                .body(htmlPayload.toResponseBody("text/html".toMediaType()))
                .build()
        }

        val result = downloadManager.downloadSource(source.id)
        assertTrue(result is FilterSourceDownloadManager.DownloadResult.Failure)
        val failure = result as FilterSourceDownloadManager.DownloadResult.Failure
        assertTrue(failure.errorMessage.contains("HTML"))

        val dbSource = repo.getSourceById(source.id)!!
        assertEquals(FilterSourceStatus.FAILED, dbSource.lastUpdateStatus)
        assertNotNull(dbSource.errorMessage)
        assertEquals(initialSha, dbSource.checksum)

        assertEquals(initialSha, calculateSha256(currentFile))
        assertFalse(fileStore.downloadTempFile(source.id).exists())
    }

    // =========================================================================
    // G5 — HTTP Caching Metadata (ETag & Last-Modified)
    // =========================================================================
    @Test
    fun g5_cachingMetadata_persistsAndReplaysHeadersCorrectly() = runTest(testDispatcher) {
        val client = createTestHttpClient()
        val downloadManager = FilterSourceDownloadManager(repo, client)

        val source = repo.addSource(
            name = "Cache Test",
            url = "https://example.com/cache.txt",
            category = FilterSourceCategory.PRIVACY
        )

        val etagVal = "\"abc-12345\""
        val lastModVal = "Sun, 01 Aug 2026 00:00:00 GMT"

        mockResponseGenerator = { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("ETag", etagVal)
                .header("Last-Modified", lastModVal)
                .body("||tracker.com^\n".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        downloadManager.downloadSource(source.id)

        val updated = repo.getSourceById(source.id)!!
        assertEquals(etagVal, updated.etag)
        assertEquals(lastModVal, updated.lastModified)

        // Next request sends both
        mockResponseGenerator = { req ->
            assertEquals(etagVal, req.header("If-None-Match"))
            assertEquals(lastModVal, req.header("If-Modified-Since"))
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(304)
                .message("Not Modified")
                .body("".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val res304 = downloadManager.downloadSource(source.id)
        assertTrue((res304 as FilterSourceDownloadManager.DownloadResult.Success).notModified)
    }

    // =========================================================================
    // G6 — Failed State Observability
    // =========================================================================
    @Test
    fun g6_failedStateObservability_observableViaDatabaseOnly() = runTest(testDispatcher) {
        val client = createTestHttpClient()
        val downloadManager = FilterSourceDownloadManager(repo, client)

        val source = repo.addSource(
            name = "Server Error Source",
            url = "https://example.com/500.txt",
            category = FilterSourceCategory.SECURITY
        )

        mockResponseGenerator = { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Internal Server Error")
                .body("Server error occurred".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val result = downloadManager.downloadSource(source.id)
        assertTrue(result is FilterSourceDownloadManager.DownloadResult.Failure)

        val dbSource = repo.getSourceById(source.id)!!
        assertEquals(FilterSourceStatus.FAILED, dbSource.lastUpdateStatus)
        assertNotNull(dbSource.errorMessage)
        assertTrue(dbSource.errorMessage!!.contains("500"))
    }

    // =========================================================================
    // G7 — Multi-Source Download Isolation
    // =========================================================================
    @Test
    fun g7_multisourceIsolation_oneFailureDoesNotCorruptOtherSources() = runTest(testDispatcher) {
        val client = createTestHttpClient()
        val downloadManager = FilterSourceDownloadManager(repo, client)

        val sourceA = repo.addSource(
            name = "Source A (Good)",
            url = "https://example.com/a.txt",
            category = FilterSourceCategory.ADS,
            enabled = true
        )
        val sourceB = repo.addSource(
            name = "Source B (Bad)",
            url = "https://example.com/b.txt",
            category = FilterSourceCategory.PRIVACY,
            enabled = true
        )

        // Seed prior file for B
        val fileB = fileStore.currentFile(sourceB.id)
        fileB.parentFile?.mkdirs()
        fileB.writeText("||old-b.org^\n")
        val shaBInitial = calculateSha256(fileB)
        repo.updateDownloadSuccess(sourceB.id, null, null, shaBInitial)

        mockResponseGenerator = { req ->
            if (req.url.toString().contains("a.txt")) {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("||new-a.org^\n".toResponseBody("text/plain".toMediaType()))
                    .build()
            } else {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Service Unavailable")
                    .body("Unavailable".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
        }

        val results = downloadManager.refreshAllEnabled()
        assertEquals(2, results.size)

        val resA = results.find { (it as? FilterSourceDownloadManager.DownloadResult.Success)?.sourceId == sourceA.id }
        val resB = results.find { (it as? FilterSourceDownloadManager.DownloadResult.Failure)?.sourceId == sourceB.id }

        assertNotNull("Source A must succeed", resA)
        assertNotNull("Source B must fail", resB)

        val dbA = repo.getSourceById(sourceA.id)!!
        val dbB = repo.getSourceById(sourceB.id)!!

        assertEquals(FilterSourceStatus.SUCCESS, dbA.lastUpdateStatus)
        assertEquals(FilterSourceStatus.FAILED, dbB.lastUpdateStatus)
        assertEquals(shaBInitial, dbB.checksum)

        // Filesystem isolation
        val fileA = fileStore.currentFile(sourceA.id)
        assertTrue(fileA.exists())
        assertEquals("||new-a.org^\n", fileA.readText())

        assertTrue(fileB.exists())
        assertEquals(shaBInitial, calculateSha256(fileB))
        assertEquals("||old-b.org^\n", fileB.readText())
    }

    // =========================================================================
    // G9 — WorkManager Contract Properties Verification
    // =========================================================================
    @Test
    fun g9_workManagerContract_constantsAndProperties() {
        assertEquals("FilterUpdateWorker", FilterUpdateWorker.WORK_NAME)
        assertEquals(24L, FilterUpdateWorker.INTERVAL_HOURS)
    }
}

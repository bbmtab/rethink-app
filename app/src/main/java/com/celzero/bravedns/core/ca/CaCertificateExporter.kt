package com.celzero.bravedns.core.ca

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object CaCertificateExporter {

    data class ExportResult(
        val displayName: String,
        val uri: Uri
    )

    private const val MIME_TYPE = "application/x-x509-ca-cert"
    private const val FILENAME_SUFFIX = "_rethinkdns_root_ca.crt"
    private const val TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss_SSS"
    private const val NONCE_LENGTH = 8
    private val NONCE_REGEX = Regex("^[0-9a-f]{8}$")

    fun exportToDownloads(
        context: Context,
        certificateBytes: ByteArray
    ): ExportResult {
        require(certificateBytes.isNotEmpty()) { "certificateBytes must not be empty" }

        val nowMillis = System.currentTimeMillis()
        val timezone = TimeZone.getDefault()
        val nonce = UUID.randomUUID().toString().replace("-", "").substring(0, NONCE_LENGTH)
        val displayName = buildDisplayName(nowMillis, timezone, nonce)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportQPlus(context, displayName, certificateBytes)
        } else {
            exportPreQ(displayName, certificateBytes)
        }
    }

    internal fun buildDisplayName(
        nowMillis: Long,
        timeZone: TimeZone,
        nonce: String
    ): String {
        require(nonce.matches(NONCE_REGEX)) {
            "nonce must be exactly $NONCE_LENGTH lowercase hexadecimal characters"
        }
        val formatter = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
        formatter.timeZone = timeZone
        val timestamp = formatter.format(Date(nowMillis))
        return "${timestamp}_${nonce}${FILENAME_SUFFIX}"
    }

    private fun exportQPlus(
        context: Context,
        displayName: String,
        certificateBytes: ByteArray
    ): ExportResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert returned null uri for $displayName")

        try {
            resolver.openOutputStream(uri).use { stream ->
                if (stream == null) {
                    throw IOException("MediaStore openOutputStream returned null for $uri")
                }
                stream.write(certificateBytes)
                stream.flush()
            }
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Throwable) {
            }
            throw t
        }

        val finalize = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        try {
            resolver.update(uri, finalize, null, null)
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Throwable) {
            }
            throw t
        }

        return ExportResult(displayName = displayName, uri = uri)
    }

    @Suppress("DEPRECATION")
    private fun exportPreQ(
        displayName: String,
        certificateBytes: ByteArray
    ): ExportResult {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (downloadsDir != null && !downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw IOException("Unable to create downloads directory: ${downloadsDir.absolutePath}")
        }
        val outFile = File(downloadsDir, displayName)
        FileOutputStream(outFile).use { stream ->
            stream.write(certificateBytes)
            stream.flush()
        }
        return ExportResult(displayName = displayName, uri = Uri.fromFile(outFile))
    }
}

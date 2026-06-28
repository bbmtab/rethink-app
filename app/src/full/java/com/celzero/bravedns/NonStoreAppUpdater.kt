/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns

import Logger
import Logger.LOG_TAG_APP_UPDATE
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.PersistentState
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException

class NonStoreAppUpdater(
    private val baseUrl: String, // Kept for constructor compatibility in Koin
    private val persistentState: PersistentState
) : AppUpdater {

    // Properties to store update details after a successful check
    var latestVersionName: String? = null
    var latestChangelog: String? = null
    var downloadUrl: String? = null

    companion object {
        private const val GITHUB_RELEASE_API_URL = "https://api.github.com/repos/bbmtab/rethink-app/releases/latest"
    }

    override fun checkForAppUpdate(
        isInteractive: AppUpdater.UserPresent,
        activity: Activity,
        listener: AppUpdater.InstallStateListener
    ) {
        Logger.i(LOG_TAG_APP_UPDATE, "Beginning update check via GitHub Releases API")

        val client = RetrofitManager.okHttpClient(persistentState.routeRethinkInRethink)
        val request = Request.Builder()
            .url(GITHUB_RELEASE_API_URL)
            .addHeader("User-Agent", "RethinkDNS-Plus")
            .build()

        client
            .newCall(request)
            .enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Logger.e(
                            LOG_TAG_APP_UPDATE,
                            "onFailure -  ${call.isCanceled()}, ${call.isExecuted()}: ${e.message}"
                        )
                        listener.onUpdateCheckFailed(AppUpdater.InstallSource.OTHER, isInteractive)
                        call.cancel()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val res = response.body?.string()
                            if (res == null || res.isBlank()) {
                                Logger.e(LOG_TAG_APP_UPDATE, "Empty response from GitHub API")
                                listener.onUpdateCheckFailed(
                                    AppUpdater.InstallSource.OTHER,
                                    isInteractive
                                )
                                return
                            }

                            val json = JSONObject(res)
                            val tagName = json.optString("tag_name", "")
                            val body = json.optString("body", "")
                            val assets = json.optJSONArray("assets")

                            Logger.i(LOG_TAG_APP_UPDATE, "Latest release tag: $tagName")

                            // Extract the direct download URL for our APK
                            var apkAssetUrl: String? = null
                            if (assets != null) {
                                for (i in 0 until assets.length()) {
                                    val asset = assets.optJSONObject(i) ?: continue
                                    val name = asset.optString("name", "").lowercase()
                                    val browserDownloadUrl = asset.optString("browser_download_url", "")
                                    if (name.endsWith(".apk")) {
                                        // Prefer asset with "plus" in the name, since this is RethinkDNS Plus
                                        if (name.contains("plus")) {
                                            apkAssetUrl = browserDownloadUrl
                                            break
                                        }
                                        // Fallback to any APK if none has "plus"
                                        if (apkAssetUrl == null) {
                                            apkAssetUrl = browserDownloadUrl
                                        }
                                    }
                                }
                            }

                            persistentState.lastAppUpdateCheck = System.currentTimeMillis()

                            response.close()
                            client.connectionPool.evictAll()

                            val currentVersion = BuildConfig.VERSION_NAME
                            val shouldUpdate = isNewerVersion(currentVersion, tagName)

                            Logger.i(
                                LOG_TAG_APP_UPDATE,
                                "Current version: $currentVersion, Latest: $tagName, Should update: $shouldUpdate, Download URL: $apkAssetUrl"
                            )

                            if (shouldUpdate && apkAssetUrl != null) {
                                latestVersionName = tagName
                                latestChangelog = body
                                downloadUrl = apkAssetUrl
                                listener.onUpdateAvailable(AppUpdater.InstallSource.OTHER)
                            } else {
                                listener.onUpToDate(AppUpdater.InstallSource.OTHER, isInteractive)
                            }
                        } catch (e: Exception) {
                            Logger.e(LOG_TAG_APP_UPDATE, "Error in GitHub update check: ${e.message}", e)
                            listener.onUpdateCheckFailed(
                                AppUpdater.InstallSource.OTHER,
                                isInteractive
                            )
                        }
                    }
                }
            )
    }

    /**
     * Downloads the APK file in the background and reports progress.
     * Automatically triggers package installation on successful download.
     */
    fun downloadAndInstallApk(
        activity: Activity,
        onProgress: (Int) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val url = downloadUrl
        if (url.isNullOrEmpty()) {
            onFailure("Download URL not found")
            return
        }

        Logger.i(LOG_TAG_APP_UPDATE, "Starting APK download from $url")
        val client = RetrofitManager.okHttpClient(persistentState.routeRethinkInRethink)
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Logger.e(LOG_TAG_APP_UPDATE, "Failed to download update APK: ${e.message}", e)
                onFailure(e.localizedMessage ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Logger.e(LOG_TAG_APP_UPDATE, "Download server returned error: ${response.code}")
                    onFailure("Server returned error code ${response.code}")
                    return
                }

                val body = response.body
                if (body == null) {
                    onFailure("Empty response body")
                    return
                }

                try {
                    val destinationFile = File(activity.cacheDir, "rethink-update.apk")
                    if (destinationFile.exists()) {
                        destinationFile.delete()
                    }

                    val totalBytes = body.contentLength()
                    var bytesDownloaded: Long = 0
                    val buffer = ByteArray(8192)

                    body.byteStream().use { inputStream ->
                        destinationFile.outputStream().use { outputStream ->
                            while (true) {
                                val bytesRead = inputStream.read(buffer)
                                if (bytesRead == -1) break

                                outputStream.write(buffer, 0, bytesRead)
                                bytesDownloaded += bytesRead

                                if (totalBytes > 0) {
                                    val progress = ((bytesDownloaded * 100) / totalBytes).toInt()
                                    activity.runOnUiThread {
                                        onProgress(progress)
                                    }
                                }
                            }
                        }
                    }

                    Logger.i(LOG_TAG_APP_UPDATE, "Download completed successfully. Launching installer...")
                    activity.runOnUiThread {
                        try {
                            installApk(activity, destinationFile)
                        } catch (e: Exception) {
                            onFailure("Failed to launch installation: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_APP_UPDATE, "Error while saving or installing update APK: ${e.message}", e)
                    activity.runOnUiThread {
                        onFailure("Download failed: ${e.message}")
                    }
                } finally {
                    response.close()
                }
            }
        })
    }

    private fun installApk(context: Context, file: File) {
        val authority = "${context.packageName}.provider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    /**
     * Semantic and alphanumeric version name comparison helper.
     */
    fun isNewerVersion(current: String, latest: String): Boolean {
        if (current == latest) return false

        fun cleanVersion(v: String): String {
            var s = v.trim().lowercase()
            if (s.startsWith("v")) {
                s = s.substring(1)
            }
            val dashIdx = s.indexOf('-')
            if (dashIdx != -1) {
                s = s.substring(0, dashIdx)
            }
            s = s.replace("plus", "").replace("p", "")
            return s
        }

        val c = cleanVersion(current)
        val l = cleanVersion(latest)

        if (c == l) return false

        val cParts = c.split(".")
        val lParts = l.split(".")
        if (cParts.size > 1 && lParts.size > 1) {
            val minSize = minOf(cParts.size, lParts.size)
            for (i in 0 until minSize) {
                val cPartNum = cParts[i].filter { it.isDigit() }.toIntOrNull() ?: 0
                val lPartNum = lParts[i].filter { it.isDigit() }.toIntOrNull() ?: 0
                if (lPartNum > cPartNum) return true
                if (cPartNum > lPartNum) return false
                val cAlpha = cParts[i].filter { it.isLetter() }
                val lAlpha = lParts[i].filter { it.isLetter() }
                if (lAlpha > cAlpha) return true
                if (cAlpha > lAlpha) return false
            }
            return lParts.size > cParts.size
        }

        val cNum = c.filter { it.isDigit() }.toIntOrNull() ?: 0
        val lNum = l.filter { it.isDigit() }.toIntOrNull() ?: 0
        if (lNum > cNum) return true
        if (cNum > lNum) return false

        val cAlpha = c.filter { it.isLetter() }
        val lAlpha = l.filter { it.isLetter() }
        return lAlpha > cAlpha
    }

    override fun completeUpdate() {
        /* no-op */
    }

    override fun unregisterListener(listener: AppUpdater.InstallStateListener) {
        /* no-op */
    }
}

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
package com.celzero.bravedns.ui.activity

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityCertificateSetupBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.core.ca.CertificateAuthority
import com.celzero.bravedns.util.Themes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File

/**
 * CertificateSetupActivity handles the generation and user installation of the local
 * Root Certificate Authority (Root CA) needed for the HTTPS inspection feature.
 *
 * It provides explanation cards, key generation progress indicators, KeyChain installers,
 * and a 1-second polling mechanism to dynamically update the certificate installation status badge.
 */
class CertificateSetupActivity : BaseActivity(R.layout.activity_certificate_setup) {

    private val persistentState by inject<PersistentState>()
    private val b by viewBinding(ActivityCertificateSetupBinding::bind)
    private var isPolling = false

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        initView()
    }

    private fun initView() {
        // Setup Toolbar back navigation
        b.toolbar.setNavigationOnClickListener {
            finish()
        }

        // Generate Certificate button click
        b.btnGenerate.setOnClickListener {
            b.progressGen.visibility = View.VISIBLE
            b.btnGenerate.isEnabled = false
            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    CertificateAuthority.initializeCA()
                    withContext(Dispatchers.Main) {
                        b.progressGen.visibility = View.GONE
                        b.btnGenerate.isEnabled = true
                        Toast.makeText(
                            this@CertificateSetupActivity,
                            "Certificate Authority generated successfully",
                            Toast.LENGTH_SHORT
                        ).show()
                        updateUiStatus()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        b.progressGen.visibility = View.GONE
                        b.btnGenerate.isEnabled = true
                        Toast.makeText(
                            this@CertificateSetupActivity,
                            "Failed to generate certificate: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // Save Certificate button click
        b.btnInstall.setOnClickListener {
            try {
                val certBytes = CertificateAuthority.exportCaCert()
                val savedPath = saveCertificateToDownloads(certBytes)
                if (savedPath != null) {
                    Toast.makeText(
                        this,
                        "CA Certificate saved successfully to: $savedPath",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "Failed to save certificate to Downloads folder",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Failed to export CA certificate: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Enable HTTPS Inspection toggle listener
        b.switchHttpsInspection.setOnCheckedChangeListener { _, isChecked ->
            persistentState.httpsInspectionEnabled = isChecked
        }
    }

    private fun saveCertificateToDownloads(certBytes: ByteArray): String? {
        val filename = "rethinkdns_ca.crt"
        val mimeType = "application/x-x509-ca-cert"

        val base64Cert = android.util.Base64.encodeToString(certBytes, android.util.Base64.NO_WRAP)
        val pemString = buildString {
            appendLine("-----BEGIN CERTIFICATE-----")
            var index = 0
            while (index < base64Cert.length) {
                val end = (index + 64).coerceAtMost(base64Cert.length)
                appendLine(base64Cert.substring(index, end))
                index += 64
            }
            append("-----END CERTIFICATE-----")
        }
        val pemBytes = pemString.toByteArray(Charsets.UTF_8)

        val resolver = contentResolver
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(pemBytes)
                        outputStream.flush()
                    }
                    "Downloads/$filename"
                } else {
                    null
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val file = File(downloadsDir, filename)
                file.writeBytes(pemBytes)
                file.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onResume() {
        super.onResume()
        isPolling = true
        startStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        isPolling = false
    }

    /**
     * Periodically updates the UI status badge every 1 second when the activity is active.
     */
    private fun startStatusPolling() {
        lifecycleScope.launch {
            while (isPolling) {
                updateUiStatus()
                delay(1000)
            }
        }
    }

    /**
     * Updates the text, colors, backgrounds, and enabled state of the buttons based on Root CA status.
     */
    private fun updateUiStatus() {
        val isInstalled = try {
            CertificateAuthority.isCaInstalled()
        } catch (e: Exception) {
            false
        }
        if (isInstalled) {
            b.tvInstallBadge.text = "✅ INSTALLED"
            b.tvInstallBadge.setTextColor(Color.parseColor("#388E3C")) // Dark green
            b.tvInstallBadge.setBackgroundResource(R.drawable.badge_bg_green)

            b.btnInstall.isEnabled = false // No need to re-install if already installed
            b.switchHttpsInspection.isEnabled = true
            b.switchHttpsInspection.isChecked = persistentState.httpsInspectionEnabled
        } else {
            b.tvInstallBadge.text = "⚠️ NOT INSTALLED"
            b.tvInstallBadge.setTextColor(Color.parseColor("#D32F2F")) // Dark red
            b.tvInstallBadge.setBackgroundResource(R.drawable.badge_bg_red)

            val canExport = try {
                CertificateAuthority.exportCaCert()
                true
            } catch (e: Exception) {
                false
            }
            b.btnInstall.isEnabled = canExport
            b.switchHttpsInspection.isEnabled = false
            b.switchHttpsInspection.isChecked = false
            // Force disable in persistent state if not installed
            persistentState.httpsInspectionEnabled = false
        }
    }
}

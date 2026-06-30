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
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
    private var certificateUri: Uri? = null

    // Activity result launcher for KeyChain install intent
    private val installCertLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Result is always RESULT_OK if user completed the install flow
        // The system handles the actual installation
        lifecycleScope.launch {
            delay(500) // Small delay to let system update the CA store
            updateUiStatus()
        }
    }

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
                    CertificateAuthority.initializeCA(this@CertificateSetupActivity)
                    withContext(Dispatchers.Main) {
                        b.progressGen.visibility = View.GONE
                        b.btnGenerate.isEnabled = true
                        b.tvGenerateHint.visibility = View.GONE  // Hide hint after successful generation
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

        // Install Certificate button click - launches Android system installer
        b.btnInstall.setOnClickListener {
            try {
                val certBytes = CertificateAuthority.exportCaCert()
                installCertificate(certBytes)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Failed to install CA certificate: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Save Certificate button click - saves to Downloads for manual install
        b.btnSaveCert.setOnClickListener {
            try {
                val certBytes = CertificateAuthority.exportCaCert()
                saveCertificateToDownloads(certBytes)
                Toast.makeText(
                    this,
                    "Certificate saved to Downloads/rethinkdns_root_ca.crt",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Failed to save certificate: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Enable HTTPS Inspection toggle listener
        b.switchHttpsInspection.setOnCheckedChangeListener { _, isChecked ->
            persistentState.httpsInspectionEnabled = isChecked
        }
    }

    /**
     * Launches the Android system installer for CA certificates using ACTION_VIEW with CAF certificate MIME type.
     * This is the standard way to install CA certificates (similar to AdGuard approach) and works on Android 10+.
     * Does NOT use KeyChain.createInstallIntent() which requires a private key (for client certs, not CA certs).
     */
    private fun installCertificate(certBytes: ByteArray) {
        try {
            // Save certificate to a temporary file in app's cache dir
            val tempFile = File(cacheDir, "rethinkdns_root_ca.crt")
            tempFile.writeBytes(certBytes)

            // Use existing FileProvider authority from full/AndroidManifest.xml
            val authority = "${packageName}.provider"
            val uri = FileProvider.getUriForFile(
                this,
                authority,
                tempFile
            )

            // Use ACTION_VIEW with CA certificate MIME type
            // This tells Android this is a CA cert (not client cert), so it won't ask for private key
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/x-x509-ca-cert")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            installCertLauncher.launch(intent)
        } catch (e: Exception) {
            // Fallback for edge cases
            fallbackInstallCertificate(certBytes)
        }
    }

    /**
     * Fallback for older Android versions or when FileProvider is unavailable.
     * Uses ACTION_VIEW with the certificate file.
     */
    private fun fallbackInstallCertificate(certBytes: ByteArray) {
        try {
            val tempFile = File(cacheDir, "rethinkdns_root_ca.crt")
            tempFile.writeBytes(certBytes)
            val authority = "${packageName}.provider"
            val uri = FileProvider.getUriForFile(
                this,
                authority,
                tempFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/x-x509-ca-cert")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            installCertLauncher.launch(intent)
        } catch (e: Exception) {
            throw Exception("Failed to launch certificate installer: ${e.message}")
        }
    }

    /**
     * Saves the CA certificate to Downloads folder for manual installation.
     * Useful for devices where automatic install fails (OEMs, etc.)
     */
    private fun saveCertificateToDownloads(certBytes: ByteArray) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val certFile = File(downloadsDir, "rethinkdns_root_ca.crt")
        certFile.writeBytes(certBytes)
        // MediaStore scan so it shows up in file managers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "rethinkdns_root_ca.crt")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(certBytes)
                }
            }
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
            b.btnSaveCert.isEnabled = canExport
            b.tvGenerateHint.visibility = if (canExport) View.GONE else View.VISIBLE
            b.switchHttpsInspection.isEnabled = false
            b.switchHttpsInspection.isChecked = false
            // Force disable in persistent state if not installed
            persistentState.httpsInspectionEnabled = false
        }
    }
}

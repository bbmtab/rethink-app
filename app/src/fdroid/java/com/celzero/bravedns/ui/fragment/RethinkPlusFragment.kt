/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.core.ca.CertificateAuthority
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceRepository
import com.celzero.bravedns.viewmodel.FilterSourceSummaryFormatter
import com.celzero.bravedns.databinding.FragmentRethinkPlusBinding
import com.celzero.bravedns.service.PersistentState
import Logger
import Logger.LOG_TAG_UI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File

/**
 * F-Droid flavour Plus tab — full MITM/Adblock UI.
 *
 * Sections:
 * 1. HTTPS Inspection — master toggle, CA status badge, CA actions (Install/Re-install/Export)
 * 2. Advanced Filtering — read-only aggregate summary of enabled filter sources + Manage Filters action
 * 3. Exclusions — domain exclusions, app exclusions
 *
 * The DNS Blocklist → MITM Bridge card and the obsolete cosmetic/scriptlet/procedural/CSP/HTML
 * filtering toggles are retired in B5 Slice-1. The bridge is superseded by the FilterSource
 * backend (B1–B4); the rule-subtype toggles are obsolete because FilterEngine auto-detects
 * subtypes (DECISION-009).
 */
class RethinkPlusFragment : Fragment(R.layout.fragment_rethink_plus) {
    private val b by viewBinding(FragmentRethinkPlusBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val filterSourceRepo by inject<FilterSourceRepository>()

    companion object {
        private const val TAG = "RethinkPlusFragment"
        private const val CA_INSTALL_REQUEST_CODE = 1001
        private const val CA_INSTALL_POLL_INTERVAL_MS = 1000L
        private const val CA_INSTALL_POLL_MAX_ATTEMPTS = 30
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initHttpsInspectionSection()
        initAdvancedFilteringSection()
        initExclusionsSection()

        // Immediate refresh of CA status on view creation
        updateCaStatusUi()

        // Start CA status polling
        startCaStatusPolling()

        observeFilterSourcesState()
    }

    override fun onResume() {
        super.onResume()
        // Refresh CA status when returning to fragment (e.g. from system certificate installer)
        updateCaStatusUi()
    }

    // ========== HTTPS INSPECTION SECTION ==========

    private fun initHttpsInspectionSection() {
        // Master toggle
        b.switchHttpsInspection.setOnCheckedChangeListener { _, isChecked ->
            // DECISION-006/D: HTTPS Inspection is hot-pluggable — setting
            // persistentState triggers the BraveVPNService observer, which emits
            // to vpnRestartTrigger and restarts the VPN without killing the process.
            persistentState.httpsInspectionEnabled = isChecked
        }

        // Observe HTTPS inspection enabled state
        persistentState.httpsInspectionEnabledLiveData.observe(viewLifecycleOwner) { enabled ->
            updateHttpsInspectionToggle(enabled)
        }

        // CA Action button removed — canonical flows use Generate, Install, Save
        // b.btnCaAction.setOnClickListener { showCaActionDialog() }

        // CA Re-install and CA Export buttons removed in favor of single canonical Install and Save buttons
        // b.btnCaReinstall.setOnClickListener { launchCaInstall() }
        // b.btnCaExport.setOnClickListener { exportCaCertificate() }

        // Generate CA Certificate button
        b.btnGenerate.setOnClickListener {
            b.progressGen.isVisible = true
            b.btnGenerate.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    CertificateAuthority.initializeCA(requireContext())
                    withContext(Dispatchers.Main) {
                        b.progressGen.isVisible = false
                        showToast(getString(R.string.plus_ca_install_success))
                        updateCaStatusUi()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        b.progressGen.isVisible = false
                        b.btnGenerate.isEnabled = true
                        showToast(getString(R.string.plus_ca_install_error, e.message ?: "Unknown error"))
                    }
                }
            }
        }

        // Install CA Certificate button
        b.btnInstall.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    CertificateAuthority.initializeCA(requireContext())
                    val certBytes = CertificateAuthority.exportCaCert()
                    val file = File(requireContext().cacheDir, "rethinkdns_root_ca.crt")
                    file.writeBytes(certBytes)
                    val uri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.provider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        setDataAndType(uri, "application/x-x509-ca-cert")
                    }
                    withContext(Dispatchers.Main) {
                        if (intent.resolveActivity(requireContext().packageManager) != null) {
                            startActivity(intent)
                        } else {
                            val settingsIntent = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(settingsIntent)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showToast(getString(R.string.plus_ca_install_error, e.message ?: "Unknown error"))
                    }
                }
            }
        }

        // Save Certificate button
        b.btnSaveCert.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val certBytes = CertificateAuthority.exportCaCert()
                    val publicDownloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val certFile = File(publicDownloadsDir, "rethinkdns_root_ca.crt")
                    certFile.writeBytes(certBytes)

                    // MediaStore indexing for Android 10+ (Q+) so it appears immediately in system file picker
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        try {
                            val contentValues = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "rethinkdns_root_ca.crt")
                                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
                                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                            }
                            val resolver = requireContext().contentResolver
                            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                            uri?.let {
                                resolver.openOutputStream(it)?.use { outputStream ->
                                    outputStream.write(certBytes)
                                }
                            }
                        } catch (ignored: Exception) {
                            // File write above succeeded as fallback
                        }
                    }

                    // Also write to app-specific external downloads directory for redundancy
                    try {
                        val appDownloadsDir = File(requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "RethinkDNS")
                        appDownloadsDir.mkdirs()
                        val appFile = File(appDownloadsDir, "rethinkdns_root_ca.crt")
                        appFile.writeBytes(certBytes)
                    } catch (ignored: Exception) {}

                    withContext(Dispatchers.Main) {
                        showToast("Certificate saved to Downloads: ${certFile.name}")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showToast(getString(R.string.plus_ca_export_error, e.message ?: "Unknown error"))
                    }
                }
            }
        }
    }

    private fun updateHttpsInspectionToggle(enabled: Boolean) {
        if (enabled) {
            b.switchHttpsInspection.isChecked = true
            b.switchHttpsInspection.isEnabled = CertificateAuthority.isCaInstalled()
            b.tvHttpsToggleSubtitle.text = if (CertificateAuthority.isCaInstalled()) {
                getString(R.string.plus_https_inspection_desc)
            } else {
                getString(R.string.plus_https_inspection_disabled)
            }
        } else {
            b.switchHttpsInspection.isChecked = false
            b.switchHttpsInspection.isEnabled = true
            b.tvHttpsToggleSubtitle.text = getString(R.string.plus_https_inspection_desc)
        }
    }

    private fun startCaStatusPolling() {
        lifecycleScope.launch {
            while (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                updateCaStatusUi()
                // If CA is already installed, no need for active continuous polling
                val installed = runCatching { CertificateAuthority.isCaInstalled() }.getOrDefault(false)
                if (installed) {
                    break
                }
                kotlinx.coroutines.delay(CA_INSTALL_POLL_INTERVAL_MS)
            }
        }
    }

    private fun updateCaStatusUi() {
        val caAvailable = runCatching {
            CertificateAuthority.exportCaCert()
            true
        }.getOrDefault(false)

        val isInstalled = if (caAvailable) {
            runCatching {
                CertificateAuthority.isCaInstalled()
            }.getOrDefault(false)
        } else {
            false
        }

        if (isInstalled) {
            b.ivCaStatusIcon.setImageResource(R.drawable.ic_check_circle)
            b.ivCaStatusIcon.setColorFilter(requireContext().getColor(R.color.accentGood))
            b.tvCaStatusTitle.text = "CA certificate installed"
            b.tvCaStatusSubtitle.text = "HTTPS inspection is ready"
            b.switchHttpsInspection.isEnabled = true
            b.btnGenerate.isEnabled = false
            b.btnInstall.isEnabled = true
            b.btnSaveCert.isEnabled = true
            b.tvGenerateHint.isVisible = false
        } else if (caAvailable) {
            b.ivCaStatusIcon.setImageResource(R.drawable.ic_warning)
            b.ivCaStatusIcon.setColorFilter(requireContext().getColor(R.color.accentWarning))
            b.tvCaStatusTitle.text = "Current CA ready"
            b.tvCaStatusSubtitle.text = "Install the current CA certificate to enable HTTPS inspection"
            b.switchHttpsInspection.isEnabled = false
            b.btnGenerate.isEnabled = false
            b.btnInstall.isEnabled = true
            b.btnSaveCert.isEnabled = true
            b.tvGenerateHint.isVisible = false
        } else {
            b.ivCaStatusIcon.setImageResource(R.drawable.ic_warning)
            b.ivCaStatusIcon.setColorFilter(requireContext().getColor(R.color.accentWarning))
            b.tvCaStatusTitle.text = "CA certificate not generated"
            b.tvCaStatusSubtitle.text = "Generate a CA certificate to continue"
            b.switchHttpsInspection.isEnabled = false
            b.btnGenerate.isEnabled = true
            b.btnInstall.isEnabled = false
            b.btnSaveCert.isEnabled = false
            b.tvGenerateHint.isVisible = true
        }
    }

    private fun showCaActionDialog() {
        // Unused dialog removed; actions are directly on the Plus tab UI.
    }

    private fun launchCaInstall() {
        lifecycleScope.launch(Dispatchers.IO) {
            CertificateAuthority.initializeCA(requireContext())
            val caBytes = CertificateAuthority.exportCaCert()

            val file = File(requireContext().getExternalFilesDir(null), "rethinkdns_root_ca.crt")
            file.writeBytes(caBytes)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setDataAndType(uri, "application/x-x509-ca-cert")
            }

            if (intent.resolveActivity(requireContext().packageManager) != null) {
                withContext(Dispatchers.Main) {
                    try {
                        startActivityForResult(intent, CA_INSTALL_REQUEST_CODE)
                    } catch (e: Exception) {
                        Logger.e(LOG_TAG_UI, "Failed to launch CA installer", e)
                        showToast(getString(R.string.plus_ca_install_error))
                    }
                }
            } else {
                // Fallback: open Settings security page
                withContext(Dispatchers.Main) {
                    val settingsIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        startActivity(settingsIntent)
                    } catch (e: Exception) {
                        showToast(getString(R.string.plus_ca_install_error))
                    }
                }
            }
        }
    }

    private fun exportCaCertificate() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                CertificateAuthority.initializeCA(requireContext())
                val caBytes = CertificateAuthority.exportCaCert()

                val downloadsDir = File(requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "RethinkDNS")
                downloadsDir.mkdirs()
                val file = File(downloadsDir, "rethinkdns_root_ca.crt")
                file.writeBytes(caBytes)

                withContext(Dispatchers.Main) {
                    showToast(getString(R.string.plus_ca_export_dialog_message).replace("%s", file.absolutePath))
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "Failed to export CA certificate", e)
                withContext(Dispatchers.Main) {
                    showToast(getString(R.string.plus_ca_export_error, e.message ?: "Unknown error"))
                }
            }
        }
    }

    // ========== ADVANCED FILTERING SECTION (read-only aggregate) ==========


    private fun initAdvancedFilteringSection() {
        // Advanced Filtering card is visible. The obsolete rule-subtype toggles
        // (cosmetic/scriptlet/procedural/CSP/HTML) are retired in B5 Slice-1 —
        // FilterEngine auto-detects subtypes (DECISION-009).
        b.cardAdvancedFiltering.isVisible = true
        b.btnManageFilterSources.isEnabled = true

        // Manage Filters opens the read-only Manage Filters shell (B5 Slice-1).
        // Target activity is defined under full/ source set, so we launch via
        // component intent to avoid compile-time coupling from the fdroid source set.
        b.btnManageFilterSources.setOnClickListener {
            try {
                val intent = Intent().setClassName(
                    requireContext().packageName,
                    "com.celzero.bravedns.ui.activity.ManageFilterSourcesActivity"
                )
                startActivity(intent)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "Failed to launch ManageFilterSourcesActivity", e)
            }
        }
    }

    private fun observeFilterSourcesState() {
        filterSourceRepo.getAllSourcesLiveData().observe(viewLifecycleOwner) { sources ->
            updateFilterSourcesSummary(sources)
        }
    }

    private fun updateFilterSourcesSummary(sources: List<FilterSource>) {
        val summary = FilterSourceSummaryFormatter.compute(sources)
        b.tvFilterSourcesCount.text = FilterSourceSummaryFormatter.format(requireContext(), summary)
    }

    // ========== EXCLUSIONS SECTION ==========

    private fun initExclusionsSection() {
        b.btnDomainExclusions.setOnClickListener {
            openDomainExclusions()
        }

        b.btnAppExclusions.setOnClickListener {
            openAppExclusions()
        }
    }

    private fun openDomainExclusions() {
        // Navigate to domain exclusions - reuse existing pattern
        val intent = Intent(requireContext(), com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity::class.java)
        intent.putExtra(com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.INTENT, com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.FragmentLoader.LOCAL.ordinal)
        // Could add a flag to open directly to exclusions
        startActivity(intent)
    }

    private fun openAppExclusions() {
        // Navigate to app exclusions - uses HttpsFilteredAppsFragment pattern
        val intent = Intent(requireContext(), com.celzero.bravedns.ui.activity.AppListActivity::class.java)
        intent.putExtra("mode", "https_exclusions")
        startActivity(intent)
    }

    // ========== HELPER METHODS ==========

    private fun showToast(message: String) {
        if (isAdded) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CA_INSTALL_REQUEST_CODE) {
            // Poll for CA installation
            pollCaInstallation()
        }
    }

    private fun pollCaInstallation() {
        lifecycleScope.launch {
            var attempts = 0
            while (attempts < CA_INSTALL_POLL_MAX_ATTEMPTS) {
                kotlinx.coroutines.delay(CA_INSTALL_POLL_INTERVAL_MS)
                if (CertificateAuthority.isCaInstalled()) {
                    withContext(Dispatchers.Main) {
                        updateCaStatusUi()
                        showToast(getString(R.string.plus_ca_install_success))
                    }
                    return@launch
                }
                attempts++
            }
            // Timeout - user may still be in Settings
            withContext(Dispatchers.Main) {
                updateCaStatusUi()
            }
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) = withContext(Dispatchers.Main) { f() }
}
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
import com.celzero.bravedns.databinding.FragmentRethinkPlusBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.VpnController
import Logger
import Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Utilities
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
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
 * 2. DNS Blocklist → MITM Bridge — toggle, list selector, Sync button, status/result
 * 3. Advanced Filtering — cosmetic/scriptlet/procedural toggles (hidden until FilterEngine supports)
 * 4. Exclusions — domain exclusions, app exclusions
 */
class RethinkPlusFragment : Fragment(R.layout.fragment_rethink_plus) {
    private val b by viewBinding(FragmentRethinkPlusBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()
    private val vpnController by inject<VpnController>()

    companion object {
        private const val TAG = "RethinkPlusFragment"
        private const val CA_INSTALL_REQUEST_CODE = 1001
        private const val CA_INSTALL_POLL_INTERVAL_MS = 1000L
        private const val CA_INSTALL_POLL_MAX_ATTEMPTS = 30
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initHttpsInspectionSection()
        initBlocklistBridgeSection()
        initAdvancedFilteringSection()
        initExclusionsSection()

        // Start CA status polling
        startCaStatusPolling()

        observeBlocklistBridgeState()
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

        // CA Action button (Install / Re-install / Export)
        b.btnCaAction.setOnClickListener { showCaActionDialog() }

        // CA Re-install button
        b.btnCaReinstall.setOnClickListener { launchCaInstall() }

        // CA Export button
        b.btnCaExport.setOnClickListener { exportCaCertificate() }
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
                kotlinx.coroutines.delay(CA_INSTALL_POLL_INTERVAL_MS)
            }
        }
    }

    private fun updateCaStatusUi() {
        val isInstalled = CertificateAuthority.isCaInstalled()
        val httpsEnabled = persistentState.httpsInspectionEnabled

        if (isInstalled) {
            b.ivCaStatusIcon.setImageResource(R.drawable.ic_check_circle)
            b.ivCaStatusIcon.setColorFilter(requireContext().getColor(R.color.accentGood))
            b.tvCaStatusTitle.text = getString(R.string.plus_ca_status_installed)
            b.tvCaStatusSubtitle.text = getString(R.string.plus_ca_status_installed_desc)
            b.btnCaAction.text = getString(R.string.plus_ca_action_reinstall)
            b.layoutCaActions.isVisible = true
            b.switchHttpsInspection.isEnabled = true
        } else {
            b.ivCaStatusIcon.setImageResource(R.drawable.ic_warning)
            b.ivCaStatusIcon.setColorFilter(requireContext().getColor(R.color.accentWarning))
            b.tvCaStatusTitle.text = getString(R.string.plus_ca_status_not_installed)
            b.tvCaStatusSubtitle.text = getString(R.string.plus_ca_status_not_installed_desc)
            b.btnCaAction.text = getString(R.string.plus_ca_action_install)
            b.layoutCaActions.isVisible = false
            b.switchHttpsInspection.isEnabled = false
        }
    }

    private fun showCaActionDialog() {
        val isInstalled = CertificateAuthority.isCaInstalled()
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            requireContext(),
            R.style.App_Dialog_NoDim
        )

        if (!isInstalled) {
            builder.setTitle(R.string.plus_ca_install_dialog_title)
                .setMessage(R.string.plus_ca_install_dialog_message)
                .setPositiveButton(R.string.plus_ca_install_dialog_open) { _, _ ->
                    launchCaInstall()
                }
                .setNegativeButton(R.string.lbl_cancel, null)
        } else {
            builder.setTitle(R.string.plus_ca_action_dialog_title)
                .setItems(
                    arrayOf(
                        getString(R.string.plus_ca_action_reinstall),
                        getString(R.string.plus_ca_action_export)
                    )
                ) { _, which ->
                    when (which) {
                        0 -> launchCaInstall()
                        1 -> exportCaCertificate()
                    }
                }
                .setNegativeButton(R.string.lbl_cancel, null)
        }
        builder.show()
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

    // ========== DNS BLOCKLIST → MITM BRIDGE SECTION ==========

    private fun initBlocklistBridgeSection() {
        // Bridge toggle
        b.switchBlocklistBridge.setOnCheckedChangeListener { _, isChecked ->
            persistentState.blocklistEnabled = isChecked
            updateBlocklistBridgeUi(isChecked)
            if (isChecked) {
                syncBlocklists()
            }
        }

        // Observe blocklist bridge enabled state
        persistentState.blocklistEnabledLiveData.observe(viewLifecycleOwner) { enabled ->
            updateBlocklistBridgeUi(enabled)
        }

        // Open blocklist manager
        b.btnOpenBlocklistManager.setOnClickListener {
            openBlocklistManager()
        }

        // Sync button
        b.btnSyncBlocklists.setOnClickListener { syncBlocklists() }
    }

    private fun updateBlocklistBridgeUi(enabled: Boolean) {
        b.switchBlocklistBridge.isChecked = enabled
        b.layoutBlocklistSelector.isVisible = enabled
        b.layoutSyncButton.isVisible = enabled
        b.cardSyncResult.isVisible = enabled

        if (enabled) {
            b.tvSyncStatus.text = getString(R.string.plus_blocklist_bridge_ready)
            b.btnSyncBlocklists.isEnabled = true
        } else {
            b.tvSyncStatus.text = getString(R.string.plus_blocklist_bridge_ready)
            b.btnSyncBlocklists.isEnabled = false
        }
    }

    private fun observeBlocklistBridgeState() {
        // Observe local blocklist stamp for changes from DNS settings
        persistentState.localBlocklistStampLiveData.observe(viewLifecycleOwner) { stamp ->
            if (persistentState.blocklistEnabled) {
                // Auto-sync when stamp changes from DNS settings
                syncBlocklists()
            }
        }

        // Observe blocklist timestamp for updates
        persistentState.localBlocklistTimestampLiveData.observe(viewLifecycleOwner) { _ ->
            if (persistentState.blocklistEnabled) {
                updateSyncStatusDisplay()
            }
        }
    }

    private fun syncBlocklists() {
        b.btnSyncBlocklists.isEnabled = false
        b.progressSync.isVisible = true
        b.tvSyncStatus.text = getString(R.string.plus_blocklist_bridge_syncing)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stats = RethinkBlocklistManager.syncBlocklistToAdblockRules(requireContext())

                withContext(Dispatchers.Main) {
                    b.progressSync.isVisible = false
                    b.btnSyncBlocklists.isEnabled = true
                    b.tvSyncStatus.text = getString(
                        R.string.plus_blocklist_bridge_synced,
                        stats.total,
                        stats.cosmetic,
                        stats.scriptlet
                    )
                    b.cardSyncResult.isVisible = true
                    b.tvSyncResult.text = getString(
                        R.string.plus_blocklist_bridge_synced,
                        stats.total,
                        stats.cosmetic,
                        stats.scriptlet
                    )
                    showToast(getString(R.string.plus_sync_complete_toast, stats.total))
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "Sync blocklists failed", e)
                withContext(Dispatchers.Main) {
                    b.progressSync.isVisible = false
                    b.btnSyncBlocklists.isEnabled = true
                    b.tvSyncStatus.text = getString(R.string.plus_blocklist_bridge_ready)
                    showToast(getString(R.string.plus_sync_error_toast, e.message ?: "Unknown error"))
                }
            }
        }
    }

    private fun updateSyncStatusDisplay() {
        val localCount = persistentState.numberOfLocalBlocklists
        val remoteCount = persistentState.getRemoteBlocklistCount()
        if (localCount > 0 || remoteCount > 0) {
            b.tvSyncStatus.text = getString(
                R.string.plus_blocklist_bridge_status,
                localCount + remoteCount
            )
        }
    }

    private fun openBlocklistManager() {
        // Navigate to DNS blocklist configuration
        val intent = Intent(requireContext(), com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity::class.java)
        intent.putExtra(com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.INTENT, com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.FragmentLoader.LOCAL.ordinal)
        startActivity(intent)
    }

    // ========== ADVANCED FILTERING SECTION (placeholder) ==========

    private fun initAdvancedFilteringSection() {
        // Currently hidden - will be shown when FilterEngine supports these features
        b.cardAdvancedFiltering.isVisible = false

        // Switches are disabled by default in layout
        b.switchCosmeticFiltering.isEnabled = false
        b.switchScriptletFiltering.isEnabled = false
        b.switchProceduralFiltering.isEnabled = false
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
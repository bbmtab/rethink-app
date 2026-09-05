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
import com.celzero.bravedns.core.ca.CaCertificateExporter
import com.celzero.bravedns.core.ca.CertificateAuthority
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceRepository
import com.celzero.bravedns.viewmodel.FilterSourceSummaryFormatter
import com.celzero.bravedns.databinding.FragmentRethinkPlusBinding
import com.celzero.bravedns.service.PersistentState
import Logger
import Logger.LOG_TAG_UI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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

    private val isCaExportInProgress = AtomicBoolean(false)

    companion object {
        private const val TAG = "RethinkPlusFragment"
        private const val CA_INSTALL_POLL_INTERVAL_MS = 1000L
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
        // Restore the persisted master state before installing the listener.
        // LiveData is process-local and may not have emitted after process recreation.
        updateHttpsInspectionToggle(persistentState.httpsInspectionEnabled)

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
            if (!isCaExportInProgress.compareAndSet(false, true)) {
                return@setOnClickListener
            }
            b.btnSaveCert.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val certificateBytes = CertificateAuthority.exportCaCert()
                    val result = CaCertificateExporter.exportToDownloads(
                        requireContext().applicationContext,
                        certificateBytes
                    )
                    withContext(Dispatchers.Main) {
                        showToast("Certificate saved to Downloads: ${result.displayName}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showToast(getString(R.string.plus_ca_export_error, e.message ?: "Unknown error"))
                    }
                } finally {
                    isCaExportInProgress.set(false)
                    withContext(Dispatchers.Main) {
                        if (view != null) {
                            updateCaStatusUi()
                        }
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
            b.btnSaveCert.isEnabled = !isCaExportInProgress.get()
            b.tvGenerateHint.isVisible = false
        } else if (caAvailable) {
            b.ivCaStatusIcon.setImageResource(R.drawable.ic_warning)
            b.ivCaStatusIcon.setColorFilter(requireContext().getColor(R.color.accentWarning))
            b.tvCaStatusTitle.text = "Current CA ready"
            b.tvCaStatusSubtitle.text = "Install the current CA certificate to enable HTTPS inspection"
            b.switchHttpsInspection.isEnabled = false
            b.btnGenerate.isEnabled = false
            b.btnInstall.isEnabled = true
            b.btnSaveCert.isEnabled = !isCaExportInProgress.get()
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

    private suspend fun uiCtx(f: suspend () -> Unit) = withContext(Dispatchers.Main) { f() }
}
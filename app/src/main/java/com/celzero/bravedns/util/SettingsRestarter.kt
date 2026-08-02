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
package com.celzero.bravedns.util

import android.content.Context
import android.widget.Toast
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState

/**
 * Utility for settings-change notification. Per DECISION-006/D, the kill-process
 * relaunch (AlarmManager + PendingIntent + Process.killProcess) has been REMOVED
 * in favour of the hot-plug [com.celzero.bravedns.service.BraveVPNService.vpnRestartTrigger]
 * MutableStateFlow. The VPN-level restart is now self-contained inside the Service, so
 * this helper need only show the informational Toast — no process death.
 *
 * Retained for caller-compat: the `onConfirm` side-effect (typically
 * [PersistentState] value write) still executes. New callers should prefer
 * writing [PersistentState] directly and letting the BraveVPNService
 * observer trigger the restart.
 */
object SettingsRestarter {

    private const val TAG = "SettingsRestarter"

    // DECISION-006/D: HTTPS Inspection is now hot-pluggable via vpnRestartTrigger.
    // This set is empty — all known settings are hot-pluggable. Preserved for
    // future non-hot-pluggable settings that may be added.
    private val NON_HOT_PLUGGABLE = emptySet<String>()

    /** Checks if a setting key requires a full app restart */
    fun requiresRestart(settingKey: String): Boolean = settingKey in NON_HOT_PLUGGABLE

    /**
     * Persists the setting value and shows an informational Toast. Per DECISION-006/D,
     * the VPN-level restart is now handled by [BraveVPNService.vpnRestartTrigger]; this
     * method does NOT kill the process.
     *
     * The `message` parameter is retained for caller-binary-compatibility; it is no longer
     * displayed (canonical Toast string is used).
     *
     * @param context Any context (Activity or Service).
     * @param message Kept for caller compat (unused).
     * @param onConfirm Setting-change side-effect (persistentState write).
     */
    fun requestRestart(
        context: Context,
        message: String,
        onConfirm: () -> Unit
    ) {
        // DECISION-006/D: no process kill — the VPN is restarted via vpnRestartTrigger
        // in BraveVPNService's PersistentState observer.
        onConfirm()
        Toast.makeText(context, R.string.restarting_to_apply_changes, Toast.LENGTH_SHORT).show()
    }

    // DECISION-006/D: scheduleRelaunchAndKill() and its constants (TOAST_VISIBLE_DELAY_MS,
    // DELAUNCH_DELAY_MS, REQUEST_CODE) removed — process kill is no longer needed.
    // The VPN-level restart is handled by BraveVPNService.vpnRestartTrigger.
}

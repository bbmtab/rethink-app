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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState

/**
 * Handles app restart for settings that cannot be hot-plugged (e.g., HTTPS Inspection).
 * Per DECISION-006: any non-hot-pluggable setting change triggers a silent restart
 * with an informational Toast "Restarting to apply changes…" — no AlertDialog (a dialog
 * built from a Service context throws AppCompat-theme IllegalStateException).
 *
 * Restart mechanism: the relaunch is scheduled via [AlarmManager] + a one-shot
 * [PendingIntent], then the process is killed. This avoids the race where
 * [Context.startActivity] from a process that is immediately killed is dropped by
 * ActivityManager (no pending next-top-activity → no cold restart). The alarm fires
 * from the system after the process is dead, cold-starting a fresh process via the
 * standard launcher path.
 */
object SettingsRestarter {

    private const val TAG = "SettingsRestarter"

    /** Settings keys that require a full process restart to apply */
    private val NON_HOT_PLUGGABLE = setOf(
        PersistentState.HTTPS_INSPECTION_ENABLED
        // Future non-hot-pluggable settings can be added here
    )

    /** Checks if a setting key requires a full app restart */
    fun requiresRestart(settingKey: String): Boolean = settingKey in NON_HOT_PLUGGABLE

    /**
     * Persists the new setting value, shows an informational Toast, and schedules a
     * silent process restart. Safe from any [Context] (Activity or Service).
     *
     * Per DECISION-006: no AlertDialog is shown — a restart is mandatory for a non-hot-pluggable
     * setting, and a confirmation dialog adds complexity without adding agency. A Service context
     * does not carry an Activity theme, so AppCompat dialogs are unsafe to build there.
     *
     * The `message` parameter is retained for caller-binary compatibility (was previously the
     * dialog body text); display is fixed to the canonical Toast string per policy.
     *
     * @param context Any context (Activity or Service); Toast + schedule+kill is safe in both.
     * @param message Kept for caller compat; no longer displayed.
     * @param onConfirm Side-effect to execute before restart (in-process; dies with the process).
     */
    fun requestRestart(
        context: Context,
        message: String,
        onConfirm: () -> Unit
    ) {
        // Execute the setting change (in-process; vestigial but harmless — survives callers).
        onConfirm()

        // Informational Toast — briefly visible before the process is torn down.
        Toast.makeText(context, R.string.restarting_to_apply_changes, Toast.LENGTH_SHORT).show()

        // Hold the process alive briefly so the Toast is visible, then schedule+kill.
        Handler(Looper.getMainLooper()).postDelayed({
            scheduleRelaunchAndKill(context)
        }, TOAST_VISIBLE_DELAY_MS)
    }

    /**
     * Schedules a one-shot AlarmManager relaunch of the launcher activity, then kills the
     * current process. The alarm fires after the process is dead, so ActivityManager
     * cold-starts a fresh process for the launch intent — no dropped startActivity race.
     */
    private fun scheduleRelaunchAndKill(context: Context) {
        Logger.d(TAG, "scheduleRelaunchAndKill: entering")

        // Application context resolves the launch intent reliably from any source context.
        val appContext = context.applicationContext
        val launchIntent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("restart_reason", "settings_restart")
            }

        if (launchIntent == null) {
            Logger.w(TAG, "scheduleRelaunchAndKill: no launch intent; killing process only")
            Process.killProcess(Process.myPid())
            System.exit(0)
            return
        }

        // One-shot, immutable PendingIntent — required form on API 23+ (immutable since 31+).
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmMgr = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + RELAUNCH_DELAY_MS
        // set() (inexact) needs no permission. Doze is inactive (device interactive, user
        // just tapped), so the alarm fires close to the requested time. The system fires the
        // PendingIntent, cold-starting the app via the standard next-top-activity path.
        alarmMgr.set(AlarmManager.RTC, triggerAt, pendingIntent)

        Logger.d(
            TAG,
            "scheduleRelaunchAndKill: scheduled AlarmManager RTC relaunch in " +
                "${RELAUNCH_DELAY_MS}ms (fires at $triggerAt); killing current process"
        )

        // Now safe to die — the relaunch is in the system's hands, not ours.
        Process.killProcess(Process.myPid())
        // Guard: if killProcess didn't end us (it should), make sure we don't return.
        System.exit(0)
    }

    /** How long the Toast stays visible before the process is torn down. */
    private const val TOAST_VISIBLE_DELAY_MS = 400L

    /** How long after the kill the AlarmManager waits before relaunching (must exceed kill/teardown). */
    private const val RELAUNCH_DELAY_MS = 600L

    /** Pending-intent request code (arbitrary, must be stable across the schedule). */
    private const val REQUEST_CODE = 0x5265 // 'Re'
}

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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.appcompat.app.AlertDialog
import com.celzero.bravedns.service.PersistentState

/**
 * Handles app restart for settings that cannot be hot-plugged (e.g., HTTPS Inspection).
 * Per user directive: any non-hot-pluggable setting change must trigger auto-restart
 * with a popup warning ("App will restart to apply change").
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
     * Shows a confirmation dialog and restarts the app on confirmation.
     * Call this when a non-hot-pluggable setting is changed by the user.
     *
     * @param context Current context (Activity or Service)
     * @param message Message to show in the dialog (e.g., "App will restart to apply HTTPS Inspection change")
     * @param onConfirm Callback to execute the actual setting change (e.g., toggle the pref)
     */
    fun requestRestart(
        context: Context,
        message: String,
        onConfirm: () -> Unit
    ) {
        val dialogBuilder = AlertDialog.Builder(context)
            .setTitle("Restart Required")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Restart Now") { _, _ ->
                // Execute the setting change
                onConfirm()
                // Trigger process restart
                restartApp(context)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                // Note: The caller should revert the setting if user cancels
            }

        val dialog = dialogBuilder.create()
        // Allow showing dialog from Service context
        if (context !is Activity) {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        dialog.show()
    }

    /**
     * Restarts the app process by killing and relaunching.
     * Uses a delayed intent to ensure clean shutdown.
     */
    private fun restartApp(context: Context) {
        // Get the launch intent for the main activity
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("restart_reason", "settings_restart")
            }

        if (launchIntent != null) {
            // Schedule the restart after a brief delay to allow dialog dismissal
            Thread {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {}
                context.startActivity(launchIntent)
                // Kill the current process
                Thread.sleep(200)
                Process.killProcess(Process.myPid())
                System.exit(0)
            }.start()
        } else {
            // Fallback: just kill and let system restart (if auto-start enabled)
            Logger.w(TAG, "Could not find launch intent; killing process only")
            Process.killProcess(Process.myPid())
            System.exit(0)
        }
    }
}
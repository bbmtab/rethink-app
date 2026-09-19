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
package com.celzero.bravedns.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.celzero.bravedns.service.VpnWatchdogScheduler
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN

/**
 * System-side entry point of the VPN watchdog chain. Declared in the
 * manifest (exported=false) so the AlarmManager trigger — and BOOT_COMPLETED
 * — reach us even when the app process is dead; delivery respawns the
 * process first. No work happens here beyond delegating to
 * [VpnWatchdogScheduler].
 */
class VpnWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        when (intent?.action) {
            VpnWatchdogScheduler.ACTION_CHECK -> {
                val pending = goAsync()
                try {
                    VpnWatchdogScheduler.runCheckAndReschedule(context)
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_VPN, "VpnWatchdog: check crashed: ${e.message}")
                } finally {
                    try {
                        pending.finish()
                    } catch (e: Exception) {
                        // best effort
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                try {
                    VpnWatchdogScheduler.onBoot(context)
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_VPN, "VpnWatchdog: boot handling failed: ${e.message}")
                }
            }
        }
    }
}

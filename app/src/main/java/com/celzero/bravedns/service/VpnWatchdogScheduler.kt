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
package com.celzero.bravedns.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.celzero.bravedns.receiver.VpnWatchdogReceiver
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN

/**
 * System-owned trigger chain for [VpnWatchdog] (Phase 2: HEAL).
 *
 * Why alarms and not an in-process timer: anything living in the app process
 * (threads, Handlers, WorkManager workers mid-run) dies with it — and dying
 * is exactly the event we must survive. An [AlarmManager] alarm is owned by
 * the system: it fires on schedule even after a lowmemorykiller / battery-
 * optimization kill, the broadcast respawns our process, the receiver runs
 * one [VpnWatchdog.decide] step, and the chain re-arms itself. Honest limits:
 * a user-level force-stop cancels all alarms (only a manual launch recovers
 * that; BOOT_COMPLETED does not fire for force-stopped apps), and exact
 * alarms need SCHEDULE_EXACT_ALARM on API 31+ (otherwise we degrade to
 * inexact and say so on the Plus tab).
 *
 * Healing is gated on persisted user intent ([PersistentState.getVpnEnabled]):
 * the watchdog NEVER resurrects protection the user stopped. It only
 * restarts a tunnel the user asked for and the system killed.
 */
object VpnWatchdogScheduler {

    const val ACTION_CHECK = "com.celzero.bravedns.service.VPN_WATCHDOG_CHECK"
    private const val REQUEST_CODE = 0x9D09
    const val DEFAULT_INTERVAL_SECS = 15
    const val MIN_INTERVAL_SECS = 5
    const val MAX_INTERVAL_SECS = 300

    /** Pure: clamp user input to the sane range. Unit-tested. */
    internal fun clampIntervalSecs(raw: Int): Int = raw.coerceIn(MIN_INTERVAL_SECS, MAX_INTERVAL_SECS)

    /**
     * Pure: run checks only when the feature is on AND (protection is
     * expected OR no explicit user-STOP is recorded) AND the global Plus
     * kill-switch is on. The second disjunct is the system-vs-user
     * discriminator the feature exists for:
     * - user STOP (expected=false, stopped=true) → never run/resurrect;
     * - graceful system kill (onDestroy flipped expected=false, marker
     *   untouched=false) → run and heal.
     * The kill-switch conjunct keeps the promise that Plus-off means
     * stock Rethink behavior (no Plus alarms ticking either).
     * Unit-tested.
     */
    internal fun shouldRunWatchdog(
        watchdogEnabled: Boolean,
        vpnExpected: Boolean,
        userStopped: Boolean,
        plusMasterEnabled: Boolean,
    ): Boolean = watchdogEnabled && (vpnExpected || !userStopped) && plusMasterEnabled

    /** Pure: serialize decide() state for prefs (survives kills). Unit-tested. */
    internal fun serializeState(s: VpnWatchdog.State): String =
        "${s.missCount},${s.firstMissAtMs},${s.awaitingHealSinceMs},${s.failedHeals},${if (s.gaveUp) 1 else 0}"

    /** Pure: parse [serializeState]; garbage in → fresh state (fail-safe). Unit-tested. */
    internal fun parseState(raw: String): VpnWatchdog.State {
        return try {
            val p = raw.split(",")
            if (p.size != 5) return VpnWatchdog.State()
            VpnWatchdog.State(
                missCount = p[0].toInt().coerceAtLeast(0),
                firstMissAtMs = p[1].toLong().coerceAtLeast(0L),
                awaitingHealSinceMs = p[2].toLong().coerceAtLeast(0L),
                failedHeals = p[3].toInt().coerceAtLeast(0),
                gaveUp = p[4].toInt() == 1,
            )
        } catch (e: Exception) {
            VpnWatchdog.State()
        }
    }

    fun isExactAlarmAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return try {
            am.canScheduleExactAlarms()
        } catch (e: Exception) {
            false
        }
    }

    fun schedule(context: Context, intervalSecs: Int) {
        val interval = clampIntervalSecs(intervalSecs)
        val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am == null) {
            Logger.w(LOG_TAG_VPN, "VpnWatchdog: no AlarmManager, chain not armed")
            return
        }
        val pi = checkPendingIntent(context)
        val triggerAt = SystemClock.elapsedRealtime() + interval * 1000L
        try {
            if (isExactAlarmAvailable(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                // Degraded: inexact, MIUI-deferrable. Still better than
                // nothing; the Plus tab says so honestly.
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            Logger.d(LOG_TAG_VPN, "VpnWatchdog: next check in ${interval}s (exact=${isExactAlarmAvailable(context)})")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "VpnWatchdog: schedule failed: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        try {
            am?.cancel(checkPendingIntent(context))
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "VpnWatchdog: cancel failed: ${e.message}")
        }
        Logger.i(LOG_TAG_VPN, "VpnWatchdog: chain cancelled")
    }

    private fun checkPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, VpnWatchdogReceiver::class.java).apply {
            action = ACTION_CHECK
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * One full step: read prefs → scan → decide(HEAL) → act → persist →
     * re-arm. Safe to call from a broadcast (fast, no blocking I/O beyond a
     * service start request).
     */
    fun runCheckAndReschedule(context: Context) {
        val appContext = context.applicationContext
        val ps = try {
            PersistentState(appContext)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "VpnWatchdog: no prefs, skipping check: ${e.message}")
            return
        }
        val interval = clampIntervalSecs(ps.watchdogIntervalSecs)
        val enabled = ps.watchdogEnabled
        val vpnExpected = try {
            ps.getVpnEnabled()
        } catch (e: Exception) {
            false
        }
        val userStopped = try {
            ps.watchdogUserStopped
        } catch (e: Exception) {
            false
        }
        val plusMaster = try {
            ps.plusMasterEnabled
        } catch (e: Exception) {
            true
        }
        val nowRealtime = SystemClock.elapsedRealtime()
        val state = parseState(ps.watchdogStateRaw)
        val presence = try {
            VpnWatchdog.scanPresence(appContext)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "VpnWatchdog: scan failed: ${e.message}")
            if (enabled) schedule(appContext, interval)
            return
        }
        val paused = try {
            VpnController.isAppPaused()
        } catch (e: Exception) {
            false
        }
        val (next, action) = VpnWatchdog.decide(
            state = state,
            nowMs = nowRealtime,
            shouldRun = shouldRunWatchdog(enabled, vpnExpected, userStopped, plusMaster),
            presence = presence,
            paused = paused,
            phase = VpnWatchdog.Phase.HEAL,
        )
        try {
            ps.watchdogStateRaw = serializeState(next)
            ps.watchdogLastCheckMs = System.currentTimeMillis()
            ps.watchdogLastAction = action.name
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "VpnWatchdog: persist failed: ${e.message}")
        }
        when (action) {
            VpnWatchdog.Action.HEAL_RESTART -> {
                // vpnExpected is true here (shouldRun gate), but re-check
                // defensively: never resurrect a user STOP.
                if (!vpnExpected) {
                    Logger.w(LOG_TAG_VPN, "VpnWatchdog: heal refused (intent off)")
                } else {
                    try {
                        // Heal is a system action, not user intent: pass
                        // userInitiated=false so the user-STOP marker is
                        // never touched here (it is already false whenever
                        // this branch runs, by the shouldRun gate).
                        VpnController.start(appContext, userInitiated = false)
                        Logger.i(LOG_TAG_VPN, "VpnWatchdog: heal requested (miss span crossed)")
                    } catch (e: Exception) {
                        Logger.w(LOG_TAG_VPN, "VpnWatchdog: heal start failed: ${e.message}")
                    }
                }
            }
            VpnWatchdog.Action.GIVE_UP_NOTIFY -> {
                Logger.w(LOG_TAG_VPN, "VpnWatchdog: gave up after repeated failed heals (see Plus tab)")
            }
            VpnWatchdog.Action.LOG_SUSPECT -> {
                Logger.i(LOG_TAG_VPN, "VpnWatchdog: suspect miss (presence=$presence)")
            }
            VpnWatchdog.Action.WOULD_HEAL, VpnWatchdog.Action.NOOP -> {
                // Nothing to do (WOULD_HEAL cannot occur in HEAL phase).
            }
        }
        // Re-arm the chain whenever the feature is on — including after
        // give-up (a later manual START clears the latch via PRESENT).
        if (enabled) {
            schedule(appContext, interval)
        } else {
            cancel(appContext)
        }
    }

    /** Fresh boot: elapsedRealtime reset makes persisted spans meaningless. */
    fun onBoot(context: Context) {
        val appContext = context.applicationContext
        val ps = try {
            PersistentState(appContext)
        } catch (e: Exception) {
            return
        }
        try {
            ps.watchdogStateRaw = ""
        } catch (e: Exception) {
            // non-fatal
        }
        if (ps.watchdogEnabled) {
            Logger.i(LOG_TAG_VPN, "VpnWatchdog: re-arming after boot")
            schedule(appContext, clampIntervalSecs(ps.watchdogIntervalSecs))
        }
    }
}

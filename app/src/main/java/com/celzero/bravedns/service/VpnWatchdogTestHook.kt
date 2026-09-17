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

import Logger
import Logger.LOG_TAG_VPN
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.celzero.bravedns.BuildConfig

/**
 * DEBUG-only pipeline proof for [VpnWatchdog]. Trigger:
 * `adb shell am broadcast -a com.celzero.bravedns.WATCHDOG_TEST_HOOK`.
 *
 * Explicit boundaries (do not weaken without re-review):
 * - exported=true in the manifest ONLY because `run-as` is SELinux-denied on
 *   test devices and adb shell cannot reach exported=false receivers. The
 *   BuildConfig.DEBUG early-return below is the real boundary: release builds
 *   ignore this receiver entirely.
 * - Zero shared state: runs a synthetic evaluation through [VpnWatchdog.decide]
 *   with local state only. It never reads or writes the live ticker state and
 *   contains no trigger/restart/notify call of any kind.
 * - Worst case if triggered by anyone: clearly TEST-prefixed log lines.
 *
 * Honest scope: proves ticker-entry → decide() → logging works on hardware
 * (negative controls + full desync-span sequence via injected timestamps, no
 * waiting). It does NOT prove the TRANSPORT_VPN sensor catches a real
 * agentDisconnect — that half stays opportunistic (on-device watch).
 */
class VpnWatchdogTestHook : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.celzero.bravedns.WATCHDOG_TEST_HOOK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION) return

        val cfg = VpnWatchdog.Config()
        val base = SystemClock.elapsedRealtime()
        var s = VpnWatchdog.State()

        // Live sensor proof: runs the REAL shared scan against current device
        // state (reads only, writes nothing). This is the one hook step that
        // touches reality instead of injected values.
        val live = VpnWatchdog.scanPresence(context)
        Logger.i(LOG_TAG_VPN, "watchdog TEST-HOOK: live presence -> $live (sensor reads reality; no state touched)")

        // Negative controls: healthy/disabled inputs must stay silent.
        val (_, n1) = VpnWatchdog.decide(s, base, true, VpnWatchdog.Presence.PRESENT, false, VpnWatchdog.Phase.LOG_ONLY, cfg)
        val (_, n2) = VpnWatchdog.decide(s, base, false, VpnWatchdog.Presence.ABSENT, false, VpnWatchdog.Phase.LOG_ONLY, cfg)
        Logger.i(LOG_TAG_VPN, "watchdog TEST-HOOK: negative controls -> present=$n1, disabled=$n2 (expect NOOP, NOOP)")

        // Positive sequence: miss at t=0, miss at t=+50s (span 50s >= 45s desync).
        var r = VpnWatchdog.decide(s, base, true, VpnWatchdog.Presence.ABSENT, false, VpnWatchdog.Phase.LOG_ONLY, cfg)
        s = r.first
        Logger.i(LOG_TAG_VPN, "watchdog TEST-HOOK: step1 miss -> ${r.second} (expect LOG_SUSPECT)")
        r = VpnWatchdog.decide(s, base + 50_000L, true, VpnWatchdog.Presence.ABSENT, false, VpnWatchdog.Phase.LOG_ONLY, cfg)
        Logger.i(LOG_TAG_VPN, "watchdog TEST-HOOK: step2 span-50s -> ${r.second} (expect WOULD_HEAL, stubbed: logged only, never triggered)")

        // HEAL-phase decision VALUE only (proves the branch exists on-device;
        // observed, never executed — no trigger call exists in this file).
        val (_, h) = VpnWatchdog.decide(s, base + 50_000L, true, VpnWatchdog.Presence.ABSENT, false, VpnWatchdog.Phase.HEAL, cfg)
        Logger.i(LOG_TAG_VPN, "watchdog TEST-HOOK: step3 HEAL-phase value -> $h (observed only, not executed)")

        // Give-up simulation: three failed heal cycles via injected timestamps
        // (proves the latch VALUES on-device; the real side effects —
        // setVpnEnabled(false) + notify — live only in the service caller and
        // are NOT executed here).
        var g = VpnWatchdog.State()
        var t = base
        repeat(3) { i ->
            val (g1, _) = VpnWatchdog.decide(g, t, true, VpnWatchdog.Presence.ABSENT, false, VpnWatchdog.Phase.HEAL, cfg)
            val (g2, ah) = VpnWatchdog.decide(g1, t + cfg.desyncSpanMs, true, VpnWatchdog.Presence.ABSENT, false, VpnWatchdog.Phase.HEAL, cfg)
            val (g3, ae) = VpnWatchdog.decide(g2, t + cfg.desyncSpanMs + cfg.healAwaitMs, true, VpnWatchdog.Presence.ABSENT, false, VpnWatchdog.Phase.HEAL, cfg)
            Logger.i(LOG_TAG_VPN, "watchdog TEST-HOOK: giveup sim cycle ${i + 1} -> heal=$ah expire=$ae failed=${g3.failedHeals} gaveUp=${g3.gaveUp} (cycles 1-2: HEAL_RESTART then LOG_SUSPECT; cycle 3 expire: GIVE_UP_NOTIFY + gaveUp=true)")
            g = g3
            t += 1_000_000L
        }
    }
}

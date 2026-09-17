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

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import com.celzero.bravedns.util.Utilities.isAtleastQ

/**
 * Watchdog for silent VPN-dataplane death with UI desync.
 *
 * Observed failure (Mi A1 A16, 2026-09-16, 3x one session): the Home toggle
 * reads STOP (`PersistentState.vpnEnabledLiveData == true`, i.e. intent)
 * while no tun interface exists system-wide. The app process stays alive, no
 * `onRevoke()` fires (that path only covers explicit user takeover), no
 * restart is attempted, and nothing reconciles intent vs reality.
 * The 3rd death carried a cause-class: system
 * `Vpn: setting state=DISCONNECTED, reason=agentDisconnect` + `NetworkAgent
 * channel lost` during a lowmemorykiller storm, with Rethink threads healthy
 * ≤60s prior. Upstream has no fix (no watchdog/reconcile/agentDisconnect
 * handling at tip; sibling issues #1202/#1434/#2118 open with Always-on
 * workarounds).
 *
 * Detection signal: presence of a TRANSPORT_VPN network (never an interface
 * name — tun0/tun1 vary across restarts). At most one VPN can be active
 * system-wide; a VPN network owned by another uid is FOREIGN (never fight
 * it — that path belongs to onRevoke, not here).
 *
 * False-positive design (no extra gate flags; math instead of state):
 * - Manual STOP: `vpnEnabledLiveData` flips false within ~1s of the destroy
 *   sequence, so later checks reset. A check landing inside the ~1s window
 *   yields at most one miss, which can never reach [Config.desyncSpanMs].
 * - Self-initiated restarts (tun0→tun1 flaps, worst observed ~20-25s):
 *   escalation requires an UNBROKEN miss span ≥ [Config.desyncSpanMs]
 *   (45s), so legitimate flaps can never escalate. Any present observation
 *   resets to fresh.
 * - Paused mode deliberately alters topology (mirror of HomeScreenFragment's
 *   own guard): checks are skipped and state resets.
 *
 * Rollout is staged via [Phase]: LOG_ONLY detects + logs the full pipeline
 * with heal stubbed (decide() provably never returns HEAL_RESTART or
 * GIVE_UP_NOTIFY there — unit-tested). Promote to HEAL only after:
 *  (a) unit tests green,
 *  (b) N manual STOP/START + restart cycles with zero WOULD_HEAL lines
 *      (proves gating safety; catching the ~1s STOP window is luck, so the
 *      rigorous claim is no-false-escalation, not detection),
 *  (c) opportunistic: a natural SUSPECT logged on a real desync (detection
 *      has no non-root on-demand repro; state this plainly, never claim a
 *      device pass without it).
 *
 * decide() is pure (explicit ms, no clocks, no framework) and is the
 * red-green gate for this slice. Android-framework reads (ConnectivityManager
 * scan, LiveData, pause flag) stay in BraveVPNService, untested directly.
 */
object VpnWatchdog {

    /** Rollout stage. See class KDoc for promotion exit criteria. */
    enum class Phase {
        LOG_ONLY,
        HEAL,
    }

    /** What the caller must do. WOULD_HEAL is Phase-1's observable contract:
     * log only, never trigger (assert zero such lines across manual cycles). */
    enum class Action {
        NOOP,
        LOG_SUSPECT,
        WOULD_HEAL,
        HEAL_RESTART,
        GIVE_UP_NOTIFY,
    }

    /** Own-VPN-network presence from a TRANSPORT_VPN scan. */
    enum class Presence {
        ABSENT,
        PRESENT,
        FOREIGN_ACTIVE,
    }

    /**
     * Evidence-based defaults (Mi A1 A16 session 2026-09-16):
     * - checkIntervalMs 30s: foreground service, negligible cost; worst-case
     *   detection latency ~60-90s (3rd check crosses the span), 20x better
     *   than the unnoticed 30+ min outages observed.
     * - desyncSpanMs 45s: exceeds worst legitimate flap (~20-25s tun0→tun1
     *   + establish) with margin; any longer true absence IS desync.
     * - healAwaitMs 60s: bounds one heal attempt (establish observed ≤25s).
     * - maxFailedHeals 3: bounded restart noise before honest give-up.
     */
    data class Config(
        val checkIntervalMs: Long = 30_000L,
        val desyncSpanMs: Long = 45_000L,
        val healAwaitMs: Long = 60_000L,
        val maxFailedHeals: Int = 3,
    )

    /** Immutable; the caller holds the current value between checks. */
    data class State(
        val missCount: Int = 0,
        val firstMissAtMs: Long = 0L,
        val awaitingHealSinceMs: Long = 0L,
        val failedHeals: Int = 0,
        val gaveUp: Boolean = false,
    )

    /**
     * Shared live sensor: the single implementation used by BOTH the ticker
     * and the DEBUG hook (fidelity: the hook exercises the real scan, not a
     * copy). Never an interface name (tun0/tun1 vary across restarts): at most
     * one VPN can be active system-wide, so any TRANSPORT_VPN network owned by
     * our own uid IS ours; one owned by another uid is FOREIGN (that path
     * belongs to onRevoke — never fight it here). Pre-Q (no ownerUid API)
     * any VPN network counts as present; documented fallback.
     */
    fun scanPresence(context: Context): Presence {
        val cm =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var foreign = false
        for (network in cm.allNetworks) {
            val cap = cm.getNetworkCapabilities(network) ?: continue
            if (!cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (isAtleastQ() && cap.ownerUid != Process.myUid()) {
                foreign = true
                continue
            }
            return Presence.PRESENT
        }
        return if (foreign) Presence.FOREIGN_ACTIVE else Presence.ABSENT
    }

    fun decide(
        state: State,
        nowMs: Long,
        shouldRun: Boolean,
        presence: Presence,
        paused: Boolean,
        phase: Phase,
        cfg: Config = Config(),
    ): Pair<State, Action> {
        // Not our outage to judge: disabled, paused-topology, or another
        // app's VPN. Reset to fresh, stay silent.
        if (!shouldRun || paused || presence == Presence.FOREIGN_ACTIVE) {
            return State() to Action.NOOP
        }
        // Latched give-up (HEAL phase only; LOG_ONLY can never latch — see below).
        if (state.gaveUp) return state to Action.NOOP
        // Any present observation self-heals the state (incl. clearing a
        // heal-await and failed-heal counts).
        if (presence == Presence.PRESENT) return State() to Action.NOOP

        // A miss: should run, nothing foreign/paused, but no VPN network.
        val misses = state.missCount + 1
        val firstMiss = if (state.missCount == 0) nowMs else state.firstMissAtMs
        val span = (nowMs - firstMiss).coerceAtLeast(0L)

        // Heal in flight (HEAL phase only — LOG_ONLY never sets awaiting, so
        // this branch provably never runs there): await outcome, no piling.
        if (state.awaitingHealSinceMs > 0L) {
            if (nowMs - state.awaitingHealSinceMs < cfg.healAwaitMs) {
                return state.copy(missCount = misses) to Action.LOG_SUSPECT
            }
            // Await expired and still absent: that heal failed. Re-qualify
            // from fresh (natural backoff: next heal needs a new full span).
            val failed = state.failedHeals + 1
            if (failed >= cfg.maxFailedHeals) {
                val done = State(missCount = misses, failedHeals = failed, gaveUp = true)
                return done to Action.GIVE_UP_NOTIFY
            }
            return State(missCount = 0, failedHeals = failed) to Action.LOG_SUSPECT
        }

        // Fresh desync span: escalate only past any legitimate flap.
        if (span >= cfg.desyncSpanMs) {
            return if (phase == Phase.HEAL) {
                state.copy(missCount = misses, awaitingHealSinceMs = nowMs) to Action.HEAL_RESTART
            } else {
                state.copy(missCount = misses, firstMissAtMs = firstMiss) to Action.WOULD_HEAL
            }
        }
        return state.copy(missCount = misses, firstMissAtMs = firstMiss) to Action.LOG_SUSPECT
    }
}

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

import com.celzero.bravedns.service.VpnWatchdog.Action
import com.celzero.bravedns.service.VpnWatchdog.Phase
import com.celzero.bravedns.service.VpnWatchdog.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the watchdog scheduler helpers (Phase 2: HEAL) and the
 * HEAL promotion path of the resurrected [VpnWatchdog.decide].
 *
 * Safety properties under test:
 * - a manual STOP (intent off) is NEVER resurrected;
 * - user interval input is clamped to the sane range;
 * - persisted decide() state round-trips, and garbage fails safe to fresh.
 * All pure logic, no Android dependencies.
 */
class VpnWatchdogSchedulerTest {

    @Test
    fun clampIntervalSecs_bounds() {
        assertEquals(5, VpnWatchdogScheduler.clampIntervalSecs(0))
        assertEquals(5, VpnWatchdogScheduler.clampIntervalSecs(-30))
        assertEquals(5, VpnWatchdogScheduler.clampIntervalSecs(5))
        assertEquals(15, VpnWatchdogScheduler.clampIntervalSecs(15))
        assertEquals(300, VpnWatchdogScheduler.clampIntervalSecs(10_000))
        assertEquals(300, VpnWatchdogScheduler.clampIntervalSecs(300))
        assertEquals(
            VpnWatchdogScheduler.DEFAULT_INTERVAL_SECS,
            VpnWatchdogScheduler.clampIntervalSecs(VpnWatchdogScheduler.DEFAULT_INTERVAL_SECS),
        )
    }

    @Test
    fun shouldRun_neverResurrectsUserStop() {
        // Feature on + protection expected: run.
        assertTrue(VpnWatchdogScheduler.shouldRunWatchdog(watchdogEnabled = true, vpnExpected = true, userStopped = false, plusMasterEnabled = true))
        assertTrue(VpnWatchdogScheduler.shouldRunWatchdog(watchdogEnabled = true, vpnExpected = true, userStopped = true, plusMasterEnabled = true))
        // Feature on + user explicitly stopped: NEVER run (even though the
        // tunnel is down) — this is the discriminator the feature exists for.
        assertFalse(VpnWatchdogScheduler.shouldRunWatchdog(watchdogEnabled = true, vpnExpected = false, userStopped = true, plusMasterEnabled = true))
        // Feature on + graceful system kill (flag flipped false by onDestroy,
        // marker untouched): run and heal.
        assertTrue(VpnWatchdogScheduler.shouldRunWatchdog(watchdogEnabled = true, vpnExpected = false, userStopped = false, plusMasterEnabled = true))
        // Global Plus kill-switch off: never run regardless (stock behavior).
        assertFalse(VpnWatchdogScheduler.shouldRunWatchdog(watchdogEnabled = true, vpnExpected = true, userStopped = false, plusMasterEnabled = false))
        assertFalse(VpnWatchdogScheduler.shouldRunWatchdog(watchdogEnabled = true, vpnExpected = false, userStopped = false, plusMasterEnabled = false))
        assertFalse(VpnWatchdogScheduler.shouldRunWatchdog(watchdogEnabled = false, vpnExpected = true, userStopped = false, plusMasterEnabled = true))
        // Feature off: never run regardless.
        assertFalse(VpnWatchdogScheduler.shouldRunWatchdog(watchdogEnabled = false, vpnExpected = true, userStopped = false, plusMasterEnabled = true))
        assertFalse(VpnWatchdogScheduler.shouldRunWatchdog(watchdogEnabled = false, vpnExpected = true, userStopped = true, plusMasterEnabled = true))
        assertFalse(VpnWatchdogScheduler.shouldRunWatchdog(watchdogEnabled = false, vpnExpected = false, userStopped = false, plusMasterEnabled = true))
        assertFalse(VpnWatchdogScheduler.shouldRunWatchdog(watchdogEnabled = false, vpnExpected = false, userStopped = true, plusMasterEnabled = true))
    }

    @Test
    fun stateRoundTrip() {
        val original = VpnWatchdog.State(
            missCount = 3,
            firstMissAtMs = 12_345L,
            awaitingHealSinceMs = 67_890L,
            failedHeals = 2,
            gaveUp = true,
        )
        assertEquals(original, VpnWatchdogScheduler.parseState(VpnWatchdogScheduler.serializeState(original)))
        assertEquals(VpnWatchdog.State(), VpnWatchdogScheduler.parseState(VpnWatchdogScheduler.serializeState(VpnWatchdog.State())))
    }

    @Test
    fun parseState_garbageFailsSafeToFresh() {
        assertEquals(VpnWatchdog.State(), VpnWatchdogScheduler.parseState(""))
        assertEquals(VpnWatchdog.State(), VpnWatchdogScheduler.parseState("1,2,3"))
        assertEquals(VpnWatchdog.State(), VpnWatchdogScheduler.parseState("a,b,c,d,e"))
        assertEquals(VpnWatchdog.State(), VpnWatchdogScheduler.parseState("1,2,3,4,5,6"))
        // negatives coerce, never crash
        val neg = VpnWatchdogScheduler.parseState("-5,-9,-1,-2,0")
        assertEquals(VpnWatchdog.State(), neg)
    }

    @Test
    fun healPromotion_spanExceeded_requestsRestart() {
        val cfg = VpnWatchdog.Config(
            checkIntervalMs = 15_000L,
            desyncSpanMs = 45_000L,
            healAwaitMs = 60_000L,
            maxFailedHeals = 3,
        )
        val (next, action) = VpnWatchdog.decide(
            state = VpnWatchdog.State(missCount = 2, firstMissAtMs = 0L),
            nowMs = 46_000L,
            shouldRun = true,
            presence = Presence.ABSENT,
            paused = false,
            phase = Phase.HEAL,
            cfg = cfg,
        )
        assertEquals(Action.HEAL_RESTART, action)
        assertTrue(next.awaitingHealSinceMs == 46_000L)
    }

    @Test
    fun healPromotion_logOnly_neverRequestsRestart() {
        val cfg = VpnWatchdog.Config(desyncSpanMs = 45_000L)
        val (_, action) = VpnWatchdog.decide(
            state = VpnWatchdog.State(missCount = 9, firstMissAtMs = 0L),
            nowMs = 1_000_000L,
            shouldRun = true,
            presence = Presence.ABSENT,
            paused = false,
            phase = Phase.LOG_ONLY,
            cfg = cfg,
        )
        assertEquals(Action.WOULD_HEAL, action)
    }

    @Test
    fun healPromotion_presentResetsAwait() {
        val inFlight = VpnWatchdog.State(missCount = 4, firstMissAtMs = 0L, awaitingHealSinceMs = 10_000L)
        val (next, action) = VpnWatchdog.decide(
            state = inFlight,
            nowMs = 20_000L,
            shouldRun = true,
            presence = Presence.PRESENT,
            paused = false,
            phase = Phase.HEAL,
        )
        assertEquals(Action.NOOP, action)
        assertEquals(VpnWatchdog.State(), next)
    }
}

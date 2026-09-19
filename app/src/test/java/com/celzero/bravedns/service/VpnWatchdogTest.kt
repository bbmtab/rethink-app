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

class VpnWatchdogTest {

    private val cfg = VpnWatchdog.Config(
        checkIntervalMs = 100L,
        desyncSpanMs = 1_000L,
        healAwaitMs = 500L,
        maxFailedHeals = 3,
    )

    private fun decide(
        state: VpnWatchdog.State = VpnWatchdog.State(),
        nowMs: Long = 0L,
        shouldRun: Boolean = true,
        presence: Presence = Presence.ABSENT,
        paused: Boolean = false,
        phase: Phase = Phase.LOG_ONLY,
    ) = VpnWatchdog.decide(state, nowMs, shouldRun, presence, paused, phase, cfg)

    @Test fun healthyPresentResetsAndNoop() {
        val dirty = VpnWatchdog.State(missCount = 5, firstMissAtMs = 100L, failedHeals = 2)
        val (ns, action) = decide(state = dirty, nowMs = 9_000L, presence = Presence.PRESENT)
        assertEquals(Action.NOOP, action)
        assertEquals(VpnWatchdog.State(), ns)
    }

    @Test fun disabledResetsAndNoop() {
        val dirty = VpnWatchdog.State(missCount = 5, firstMissAtMs = 100L, failedHeals = 1, gaveUp = true)
        val (ns, action) = decide(state = dirty, nowMs = 9_000L, shouldRun = false)
        assertEquals(Action.NOOP, action)
        assertEquals(VpnWatchdog.State(), ns)
    }

    @Test fun pausedResetsAndNoop() {
        val dirty = VpnWatchdog.State(missCount = 5, firstMissAtMs = 100L)
        val (ns, action) = decide(state = dirty, nowMs = 9_000L, paused = true)
        assertEquals(Action.NOOP, action)
        assertEquals(VpnWatchdog.State(), ns)
    }

    @Test fun foreignVpnNeverFought() {
        val (ns, action) = decide(nowMs = 9_000L, presence = Presence.FOREIGN_ACTIVE)
        assertEquals(Action.NOOP, action)
        assertEquals(VpnWatchdog.State(), ns)
    }

    @Test fun latchedGiveUpStaysSilent() {
        val latched = VpnWatchdog.State(missCount = 9, failedHeals = 3, gaveUp = true)
        val (ns, action) = VpnWatchdog.decide(latched, 9_000L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
        assertEquals(Action.NOOP, action)
        assertEquals(latched, ns)
    }

    @Test fun firstMissLogsSuspect() {
        val (ns, action) = decide(nowMs = 0L)
        assertEquals(Action.LOG_SUSPECT, action)
        assertEquals(1, ns.missCount)
        assertEquals(0L, ns.firstMissAtMs)
    }

    @Test fun spanBelowCooldownLogsSuspect() {
        val (s1, a1) = decide(nowMs = 0L)
        assertEquals(Action.LOG_SUSPECT, a1)
        val (s2, a2) = decide(state = s1, nowMs = 500L)
        assertEquals(Action.LOG_SUSPECT, a2)
        assertEquals(2, s2.missCount)
        assertEquals(0L, s2.firstMissAtMs)
    }

    @Test fun spanReachesCooldownLogOnlyYieldsWouldHealNeverHeal() {
        var (s, _) = decide(nowMs = 0L)
        val steps = listOf(300L, 600L, 900L, 1_000L, 1_500L, 5_000L)
        for (t in steps) {
            val (ns, action) = decide(state = s, nowMs = t)
            if (t < 1_000L) {
                assertEquals("t=$t", Action.LOG_SUSPECT, action)
            } else {
                assertEquals("t=$t", Action.WOULD_HEAL, action)
            }
            // LOG_ONLY must never arm a heal-await, latch give-up, or heal.
            assertEquals(0L, ns.awaitingHealSinceMs)
            assertEquals(0, ns.failedHeals)
            assertFalse(ns.gaveUp)
            s = ns
        }
    }

    @Test fun spanReachesCooldownHealPhaseArmsHealOnce() {
        var (s, _) = VpnWatchdog.decide(VpnWatchdog.State(), 0L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
        val (s1, a1) = VpnWatchdog.decide(s, 500L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
        assertEquals(Action.LOG_SUSPECT, a1)
        val (s2, a2) = VpnWatchdog.decide(s1, 1_000L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
        assertEquals(Action.HEAL_RESTART, a2)
        assertEquals(1_000L, s2.awaitingHealSinceMs)
        // Still absent inside the await window: suspect again, no piling.
        val (s3, a3) = VpnWatchdog.decide(s2, 1_200L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
        assertEquals(Action.LOG_SUSPECT, a3)
        assertEquals(1_000L, s3.awaitingHealSinceMs)
    }

    @Test fun healAwaitExpiryCountsFailedHealAndRequalifies() {
        var (s, _) = VpnWatchdog.decide(VpnWatchdog.State(), 0L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
        val (s1, _) = VpnWatchdog.decide(s, 1_000L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
        // 1_000 + 500 (healAwait) = 1_500: await expired, still absent.
        val (s2, a2) = VpnWatchdog.decide(s1, 1_500L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
        assertEquals(Action.LOG_SUSPECT, a2)
        assertEquals(1, s2.failedHeals)
        assertEquals(0L, s2.awaitingHealSinceMs)
        assertEquals(0, s2.missCount) // re-qualify from fresh (natural backoff)
    }

    @Test fun threeFailedHealsLatchGiveUpNotify() {
        var s = VpnWatchdog.State()
        var t = 0L
        repeat(3) {
            // Fresh miss chain to span, heal arms, await expires: one failed heal.
            val (s1, _) = VpnWatchdog.decide(s, t, true, Presence.ABSENT, false, Phase.HEAL, cfg)
            val (s2, a2) = VpnWatchdog.decide(s1, t + 1_000L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
            assertEquals(Action.HEAL_RESTART, a2)
            val (s3, _) = VpnWatchdog.decide(s2, t + 1_500L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
            s = s3
            t += 10_000L
        }
        // Third expiry must latch give-up (loop above ends on 3rd expiry? no:
        // each iteration ends post-expiry; the 3rd iteration's expiry latches).
        assertEquals(3, s.failedHeals)
        assertTrue(s.gaveUp)
        val (_, last) = VpnWatchdog.decide(s, t, true, Presence.ABSENT, false, Phase.HEAL, cfg)
        assertEquals(Action.NOOP, last) // latched: silent afterwards
    }

    @Test fun giveUpActionEmittedExactlyOnce() {
        // Drive two full failed cycles, then catch the 3rd expiry's action.
        var s = VpnWatchdog.State()
        var t = 0L
        val actions = mutableListOf<Action>()
        repeat(3) {
            val (s1, _) = VpnWatchdog.decide(s, t, true, Presence.ABSENT, false, Phase.HEAL, cfg)
            val (s2, a2) = VpnWatchdog.decide(s1, t + 1_000L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
            actions.add(a2)
            val (s3, a3) = VpnWatchdog.decide(s2, t + 1_500L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
            actions.add(a3)
            s = s3
            t += 10_000L
        }
        assertEquals(1, actions.count { it == Action.GIVE_UP_NOTIFY })
        assertEquals(Action.GIVE_UP_NOTIFY, actions.last())
    }

    @Test fun presentDuringAwaitRecoversToHealthy() {
        var (s, _) = VpnWatchdog.decide(VpnWatchdog.State(), 0L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
        val (s1, _) = VpnWatchdog.decide(s, 1_000L, true, Presence.ABSENT, false, Phase.HEAL, cfg)
        assertEquals(1_000L, s1.awaitingHealSinceMs)
        val (s2, a2) = VpnWatchdog.decide(s1, 1_200L, true, Presence.PRESENT, false, Phase.HEAL, cfg)
        assertEquals(Action.NOOP, a2)
        assertEquals(VpnWatchdog.State(), s2)
    }

    @Test fun clockSkewNeverEscalates() {
        val skewed = VpnWatchdog.State(missCount = 4, firstMissAtMs = 5_000L)
        val (ns, action) = decide(state = skewed, nowMs = 1_000L)
        assertEquals(Action.LOG_SUSPECT, action)
        assertFalse(ns.gaveUp)
        assertEquals(0L, ns.awaitingHealSinceMs)
    }
}

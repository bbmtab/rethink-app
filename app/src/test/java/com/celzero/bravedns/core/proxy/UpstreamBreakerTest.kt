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
package com.celzero.bravedns.core.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the upstream-dial circuit breaker
 * ([LocalHttpsProxy.evaluateUpstreamBreaker]): consecutive TCP/SOCKS dial
 * failures trip a debounced VPN restart; anything else stays quiet. Pure
 * logic, no Android dependencies.
 */
class UpstreamBreakerTest {

    private val cfg = LocalHttpsProxy.UpstreamBreakerConfig(
        windowMs = 60_000L,
        tripCount = 5,
        maxRestartsPerHour = 3,
        hourMs = 3_600_000L,
    )

    private fun fails(
        state: LocalHttpsProxy.UpstreamBreakerState = LocalHttpsProxy.UpstreamBreakerState(),
        times: Int,
        startMs: Long = 1_000_000L,
        stepMs: Long = 1_000L,
    ): Pair<LocalHttpsProxy.UpstreamBreakerState, LocalHttpsProxy.UpstreamBreakerAction> {
        var s = state
        var a = LocalHttpsProxy.UpstreamBreakerAction.NONE
        repeat(times) { i ->
            val r = LocalHttpsProxy.evaluateUpstreamBreaker(s, startMs + i * stepMs, true, cfg)
            s = r.first
            a = r.second
        }
        return s to a
    }

    @Test
    fun success_resetsStreak() {
        val (mid, _) = fails(times = 4)
        assertEquals(4, mid.consecFails)
        val (next, action) = LocalHttpsProxy.evaluateUpstreamBreaker(mid, 1_005_000L, false, cfg)
        assertEquals(LocalHttpsProxy.UpstreamBreakerAction.NONE, action)
        assertEquals(0, next.consecFails)
    }

    @Test
    fun intermittentFlaps_neverTrip() {
        // fail, succeed, fail, succeed... — never 5 consecutive.
        var s = LocalHttpsProxy.UpstreamBreakerState()
        var t = 1_000_000L
        repeat(20) {
            s = LocalHttpsProxy.evaluateUpstreamBreaker(s, t, true, cfg).first
            t += 1_000L
            s = LocalHttpsProxy.evaluateUpstreamBreaker(s, t, false, cfg).first
            t += 1_000L
        }
        // Only assert no trip occurred along the way is implied; final state
        // must show zero streak and zero restarts consumed.
        assertEquals(0, s.consecFails)
        assertEquals(0, s.restartsUsed)
    }

    @Test
    fun fiveConsecutive_tripsRestart() {
        val (next, action) = fails(times = 5)
        assertEquals(LocalHttpsProxy.UpstreamBreakerAction.TRIP_RESTART, action)
        assertEquals(0, next.consecFails)
        assertEquals(1, next.restartsUsed)
    }

    @Test
    fun staleStreak_restartsWindow() {
        val (mid, _) = fails(times = 4)
        // 61s later: outside the 60s window, streak restarts at 1.
        val (next, action) = LocalHttpsProxy.evaluateUpstreamBreaker(mid, 1_000_000L + 61_000L, true, cfg)
        assertEquals(LocalHttpsProxy.UpstreamBreakerAction.NONE, action)
        assertEquals(1, next.consecFails)
    }

    @Test
    fun hourlyBudget_givesUpLoudly() {
        // Exhaust 3 restarts back-to-back (each trip resets the streak).
        var s = LocalHttpsProxy.UpstreamBreakerState()
        var t = 1_000_000L
        repeat(3) {
            val r = fails(s, times = 5, startMs = t)
            s = r.first
            assertEquals(LocalHttpsProxy.UpstreamBreakerAction.TRIP_RESTART, r.second)
            t += 70_000L
        }
        assertEquals(3, s.restartsUsed)
        // 4th sustained outage inside the same hour: give up, no restart.
        val (done, action) = fails(s, times = 5, startMs = t)
        assertEquals(LocalHttpsProxy.UpstreamBreakerAction.GIVE_UP, action)
        assertEquals(3, done.restartsUsed)
    }

    @Test
    fun hourlyBudget_resetsAfterHour() {
        val used = LocalHttpsProxy.UpstreamBreakerState(restartsUsed = 3, hourStartMs = 1_000_000L)
        val (_, action) = fails(used, times = 5, startMs = 1_000_000L + 3_700_000L)
        assertEquals(LocalHttpsProxy.UpstreamBreakerAction.TRIP_RESTART, action)
    }
}

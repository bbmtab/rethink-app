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
package com.celzero.bravedns.ui.fragment

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the VPN-flag self-heal predicate
 * ([shouldHealVpnFlag], used by HomeScreenFragment.onResume).
 *
 * Guards the desync fix: Home showing NOT PROTECTED while the tunnel is up
 * (flag lost to crash/kill/stop-write race). The uptime gate is load-bearing:
 * a user-initiated STOP drains the tunnel for a few seconds, and healing
 * inside that window would fight the user and resurrect the VPN via
 * auto-start. Pure logic, no Android dependencies.
 */
class VpnFlagHealTest {

    @Test
    fun heal_liveTunnel_staleFlag_oldEnough() {
        assertTrue(shouldHealVpnFlag(tunnelUp = true, activationRequested = false, tunnelUptimeMs = 30_000L))
    }

    @Test
    fun noHeal_tunnelUp_flagFalse_butFresh() {
        // Teardown after a user STOP is still draining: do NOT fight it.
        assertFalse(shouldHealVpnFlag(tunnelUp = true, activationRequested = false, tunnelUptimeMs = 5_000L))
    }

    @Test
    fun noHeal_boundary() {
        assertTrue(
            shouldHealVpnFlag(tunnelUp = true, activationRequested = false, tunnelUptimeMs = VPN_FLAG_HEAL_MIN_UPTIME_MS)
        )
        assertFalse(
            shouldHealVpnFlag(
                tunnelUp = true,
                activationRequested = false,
                tunnelUptimeMs = VPN_FLAG_HEAL_MIN_UPTIME_MS - 1
            )
        )
    }

    @Test
    fun noHeal_flagAlreadyTrue() {
        assertFalse(shouldHealVpnFlag(tunnelUp = true, activationRequested = true, tunnelUptimeMs = 60_000L))
    }

    @Test
    fun noHeal_tunnelDown() {
        assertFalse(shouldHealVpnFlag(tunnelUp = false, activationRequested = false, tunnelUptimeMs = 60_000L))
        assertFalse(shouldHealVpnFlag(tunnelUp = false, activationRequested = true, tunnelUptimeMs = 60_000L))
    }

    @Test
    fun noHeal_negativeUptime() {
        // VpnController.uptimeMs() returns negative when there is no tunnel.
        assertFalse(shouldHealVpnFlag(tunnelUp = true, activationRequested = false, tunnelUptimeMs = -5_000L))
    }
}

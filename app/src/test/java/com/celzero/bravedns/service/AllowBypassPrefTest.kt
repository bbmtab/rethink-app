package com.celzero.bravedns.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fork-side coverage for the adopted upstream allowBypass wiring (PR #3057).
 *
 * Deliberately pure-JVM: `includeAndroidResources` is intentionally disabled
 * repo-wide (app/build.gradle — real manifest would init the gojni native
 * backend), so `PersistentState` cannot be instantiated here (its init
 * resolves a string resource). The round-trip is therefore covered by GHA
 * compile + device smoke (toggle visible, logcat restart reason
 * "allowBypass: ..."), while this test pins the one thing that would fail
 * silently: the [PersistentState.ALLOW_BYPASS] key the restart branch
 * matches on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AllowBypassPrefTest {
    @Test
    fun `allowBypass key matches the restart branch`() {
        // BraveVPNService.onSharedPreferenceChanged matches on this exact key;
        // a typo here would silently detach the toggle from the restart.
        assertEquals("allow_bypass", PersistentState.ALLOW_BYPASS)
    }
}

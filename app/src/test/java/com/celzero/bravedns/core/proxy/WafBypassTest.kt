package com.celzero.bravedns.core.proxy

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
/**
 * Unit tests for the WAF auto-bypass verdicts (LocalHttpsProxy).
 *
 * Guards the anti-cascade rule (ipleak.net lesson): WAF bypasses are
 * EXACT-host only, gated on explicit WAF signals, and user-clearable.
 * Same package as the proxy so internal members are reachable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WafBypassTest {

    @Before
    fun setUp() {
        // LocalHttpsProxy is a singleton: reset verdict state per test.
        // persistentState is null here, so persist calls are safe no-ops.
        // The master override is always null outside the two master tests
        // (they reset it themselves), belt-and-braces here too.
        LocalHttpsProxy.wafMasterOverride = null
        LocalHttpsProxy.clearWafBypass()
    }

    @After
    fun tearDown() {
        LocalHttpsProxy.wafMasterOverride = null
        LocalHttpsProxy.clearWafBypass()
    }

    @Test
    fun challengeHeader_detected_caseInsensitive() {
        assertTrue(
            LocalHttpsProxy.isWafChallengeResponse(
                listOf("HTTP/1.1 202 Accepted", "x-amzn-waf-action: challenge")
            )
        )
        assertTrue(
            LocalHttpsProxy.isWafChallengeResponse(
                listOf("HTTP/1.1 202 Accepted", "X-AMZN-WAF-ACTION: Challenge")
            )
        )
    }

    @Test
    fun challengeHeader_plain403WithoutMarker_isNotSignal() {
        // A bare 403 may be a legitimate site verdict and must still reach
        // the client decrypted — it must NOT trigger auto-bypass.
        assertFalse(
            LocalHttpsProxy.isWafChallengeResponse(
                listOf("HTTP/1.1 403 Forbidden", "Server: cloudflare")
            )
        )
        assertFalse(LocalHttpsProxy.isWafChallengeResponse(emptyList()))
    }

    @Test
    fun challenge_firstBypass_exactHostOnly_noCascade() {
        LocalHttpsProxy.recordWafChallenge("www.kompas.com")

        assertTrue(LocalHttpsProxy.isWafBypassed("www.kompas.com"))
        // Anti-cascade: neither parent, sibling, nor subdomain may match.
        assertFalse(LocalHttpsProxy.isWafBypassed("kompas.com"))
        assertFalse(LocalHttpsProxy.isWafBypassed("api.kompas.com"))
        assertFalse(LocalHttpsProxy.isWafBypassed("www.kompas.com.evil.com"))
        assertFalse(LocalHttpsProxy.isWafBypassed("other.com"))
    }

    @Test
    fun challenge_hostNormalization() {
        LocalHttpsProxy.recordWafChallenge("  WWW.Kompas.COM. ")

        assertTrue(LocalHttpsProxy.isWafBypassed("www.kompas.com"))
        assertTrue(LocalHttpsProxy.isWafBypassed("WWW.KOMPAS.COM"))
    }

    @Test
    fun timeout_firstStrike_doesNotBypass_secondDoes() {
        LocalHttpsProxy.recordUpstreamTimeout("www.tempo.co")
        assertFalse(
            "single flap must not fail a host open",
            LocalHttpsProxy.isWafBypassed("www.tempo.co")
        )

        LocalHttpsProxy.recordUpstreamTimeout("www.tempo.co")
        assertTrue(
            "second consecutive timeout is the tarpit signature",
            LocalHttpsProxy.isWafBypassed("www.tempo.co")
        )
    }

    @Test
    fun timeout_successBetweenStrikes_doesNotResetWindow() {
        // Flaky-tarpit hosts alternate timeout/success (e.g. a fast 403
        // between two 30s stalls). Interleaved successes must NOT reset the
        // window — the 2nd timeout still trips the breaker.
        LocalHttpsProxy.recordUpstreamTimeout("www.tempo.co")
        // (a fully-forwarded response here changes nothing by design)
        LocalHttpsProxy.recordUpstreamTimeout("www.tempo.co")

        assertTrue(
            "two timeouts within the window bypass even with success between",
            LocalHttpsProxy.isWafBypassed("www.tempo.co")
        )
    }

    @Test
    fun timeout_strikesArePerHost() {
        LocalHttpsProxy.recordUpstreamTimeout("a.example.com")
        LocalHttpsProxy.recordUpstreamTimeout("b.example.com")

        assertFalse(LocalHttpsProxy.isWafBypassed("a.example.com"))
        assertFalse(LocalHttpsProxy.isWafBypassed("b.example.com"))
    }

    @Test
    fun clearWafBypass_emptiesEverything() {
        LocalHttpsProxy.recordWafChallenge("www.kompas.com")
        LocalHttpsProxy.recordUpstreamTimeout("www.tempo.co")
        assertEquals(1, LocalHttpsProxy.getWafBypassedHosts().size)

        LocalHttpsProxy.clearWafBypass()

        assertTrue(LocalHttpsProxy.getWafBypassedHosts().isEmpty())
        assertFalse(LocalHttpsProxy.isWafBypassed("www.kompas.com"))
    }
    @Test
    fun masterOff_failClosed_noRecording_noEnforcement() {
        // The gate is driven through wafMasterOverride: SharedPreferences
        // apply() has no read-your-writes guarantee under Robolectric, so
        // tests must not depend on pref flush timing (that is what the
        // production path uses; the override exists precisely for this).
        LocalHttpsProxy.wafMasterOverride = false
        try {
            LocalHttpsProxy.recordWafChallenge("www.kompas.com")
            assertFalse(
                "master OFF must not record verdicts",
                LocalHttpsProxy.isWafBypassed("www.kompas.com")
            )
            LocalHttpsProxy.recordUpstreamTimeout("www.tempo.co")
            LocalHttpsProxy.recordUpstreamTimeout("www.tempo.co")
            LocalHttpsProxy.recordUpstreamTimeout("www.tempo.co")
            assertFalse(
                "master OFF must not enforce even past the strike window",
                LocalHttpsProxy.isWafBypassed("www.tempo.co")
            )
        } finally {
            // Neutral ground for other test files sharing this singleton.
            LocalHttpsProxy.wafMasterOverride = null
            LocalHttpsProxy.clearWafBypass()
        }
    }

    @Test
    fun masterOn_autoWorksAfterReEnable() {
        LocalHttpsProxy.wafMasterOverride = false
        LocalHttpsProxy.recordWafChallenge("www.kompas.com")
        assertFalse(LocalHttpsProxy.isWafBypassed("www.kompas.com"))
        LocalHttpsProxy.wafMasterOverride = true
        LocalHttpsProxy.recordWafChallenge("www.kompas.com")
        assertTrue(
            "re-enabling resumes auto verdicts (stored set was never polluted)",
            LocalHttpsProxy.isWafBypassed("www.kompas.com")
        )
        LocalHttpsProxy.wafMasterOverride = null
        LocalHttpsProxy.clearWafBypass()
    }

    @Test
    fun healthGate_countsWhenExitHealthy() {
        val now = 1_000_000L
        // Recent success (10s ago): exit healthy, count.
        assertTrue(LocalHttpsProxy.shouldCountTimeout(now, now - 10_000L))
        // Bootstrap (never seen success): count (favor availability).
        assertTrue(LocalHttpsProxy.shouldCountTimeout(now, 0L))
        assertTrue(LocalHttpsProxy.shouldCountTimeout(now, -5L))
    }

    @Test
    fun healthGate_skipsWhenExitUnhealthy() {
        val now = 1_000_000L
        // Last success 5 minutes ago: exit may be flapping, skip.
        assertFalse(LocalHttpsProxy.shouldCountTimeout(now, now - 300_000L))
        // Boundary: exactly at the window edge still counts.
        assertTrue(LocalHttpsProxy.shouldCountTimeout(now, now - 120_000L))
        assertFalse(LocalHttpsProxy.shouldCountTimeout(now, now - 120_001L))
    }
}

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
        LocalHttpsProxy.clearWafBypass()
    }

    @After
    fun tearDown() {
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
    fun timeout_successBetweenStrikes_resetsCounter() {
        LocalHttpsProxy.recordUpstreamTimeout("www.tempo.co")
        LocalHttpsProxy.recordUpstreamSuccess("www.tempo.co")
        LocalHttpsProxy.recordUpstreamTimeout("www.tempo.co")

        assertFalse(
            "a completed response between timeouts breaks the streak",
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
    fun bypassedHost_doesNotPolluteDynamicSuffixSet() {
        // shouldInspectDomain (suffix-matched dynamic set) must stay unaware
        // of WAF verdicts: the MITM gate consults isWafBypassed separately.
        LocalHttpsProxy.recordWafChallenge("www.kompas.com")

        assertTrue(
            "dynamic set must not learn WAF hosts (no cascade vector)",
            LocalHttpsProxy.shouldInspectDomain("www.kompas.com")
        )
    }
}

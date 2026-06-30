package com.celzero.bravedns.core.filter

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CspInjector.
 */
class CspInjectorTest {

    @Before
    fun setUp() {
        FilterEngine.clear()
        CspInjector.clear()
    }

    // ── getCspForDomain ──────────────────────────────────────────────────────

    @Test
    fun `getCspForDomain returns null when FilterEngine not loaded`() {
        // FilterEngine.isLoaded == false after clear()
        val result = CspInjector.getCspForDomain("example.com")
        assertNull(result)
    }

    @Test
    fun `getCspForDomain returns null when no csp rules loaded`() {
        FilterEngine.loadRules("||ads.com^\n||tracker.com^\n")
        val result = CspInjector.getCspForDomain("example.com")
        assertNull(result)
    }

    @Test
    fun `getCspForDomain returns directive for exact domain match`() {
        FilterEngine.loadRules("||example.com^\$csp=script-src 'self'")
        CspInjector.onRulesReloaded()

        val result = CspInjector.getCspForDomain("example.com")
        assertNotNull(result)
        assertTrue("Should contain script-src directive", result!!.contains("script-src 'self'"))
    }

    @Test
    fun `getCspForDomain matches subdomain via suffix`() {
        FilterEngine.loadRules("||example.com^\$csp=script-src 'self'")
        CspInjector.onRulesReloaded()

        val result = CspInjector.getCspForDomain("sub.example.com")
        assertNotNull("Subdomain should match parent domain rule", result)
    }

    @Test
    fun `getCspForDomain applies global rule to all domains`() {
        // Rule with no domain restriction (no || pattern, just $csp)
        FilterEngine.loadRules("\$csp=connect-src 'self'")
        CspInjector.onRulesReloaded()

        val result1 = CspInjector.getCspForDomain("example.com")
        val result2 = CspInjector.getCspForDomain("other.net")
        assertNotNull("Global rule should apply to example.com", result1)
        assertNotNull("Global rule should apply to other.net", result2)
    }

    @Test
    fun `getCspForDomain respects excluded domain`() {
        FilterEngine.loadRules("\$csp=connect-src 'self',domain=~excluded.com")
        CspInjector.onRulesReloaded()

        assertNull("Excluded domain should get null", CspInjector.getCspForDomain("excluded.com"))
        assertNotNull("Non-excluded domain should still match", CspInjector.getCspForDomain("allowed.com"))
    }

    @Test
    fun `getCspForDomain merges multiple matching rules`() {
        val rules = """
            ||example.com^${'$'}csp=script-src 'self'
            ||example.com^${'$'}csp=connect-src 'self'
        """.trimIndent()
        FilterEngine.loadRules(rules)
        CspInjector.onRulesReloaded()

        val result = CspInjector.getCspForDomain("example.com")
        assertNotNull(result)
        assertTrue("Should contain script-src", result!!.contains("script-src"))
        assertTrue("Should contain connect-src", result.contains("connect-src"))
    }

    @Test
    fun `getCspForDomain caches results`() {
        FilterEngine.loadRules("||example.com^\$csp=script-src 'self'")
        CspInjector.onRulesReloaded()

        val first = CspInjector.getCspForDomain("example.com")
        val second = CspInjector.getCspForDomain("example.com")
        // Both calls must return equal strings (cache hit)
        assertEquals(first, second)
    }

    // ── mergeWithExistingCsp ─────────────────────────────────────────────────

    @Test
    fun `mergeWithExistingCsp appends new directives`() {
        val existing = "script-src 'self'"
        val addition = "connect-src 'none'"
        val merged = CspInjector.mergeWithExistingCsp(existing, addition)
        assertTrue("Should contain script-src", merged.contains("script-src 'self'"))
        assertTrue("Should contain connect-src", merged.contains("connect-src 'none'"))
    }

    @Test
    fun `mergeWithExistingCsp does not duplicate existing directive`() {
        val existing = "script-src 'self'"
        val addition = "script-src 'unsafe-inline'"  // same directive name, different value
        val merged = CspInjector.mergeWithExistingCsp(existing, addition)
        // existing wins — unsafe-inline should NOT be added
        assertFalse("Should not add duplicate directive", merged.contains("unsafe-inline"))
        assertTrue("Original directive should remain", merged.contains("script-src 'self'"))
    }

    @Test
    fun `mergeWithExistingCsp handles full header line with prefix`() {
        val headerLine = "Content-Security-Policy: script-src 'self'"
        val addition = "connect-src 'none'"
        val merged = CspInjector.mergeWithExistingCsp(headerLine, addition)
        // Should strip header name and return only value
        assertFalse("Should not contain header name prefix", merged.startsWith("Content-Security-Policy:"))
        assertTrue("Should contain original directive", merged.contains("script-src"))
        assertTrue("Should contain new directive", merged.contains("connect-src"))
    }

    @Test
    fun `mergeWithExistingCsp handles empty existing`() {
        val merged = CspInjector.mergeWithExistingCsp("", "script-src 'self'")
        assertTrue("Should contain the addition", merged.contains("script-src 'self'"))
    }

    // ── onRulesReloaded ──────────────────────────────────────────────────────

    @Test
    fun `onRulesReloaded clears cached results`() {
        FilterEngine.loadRules("||example.com^\$csp=script-src 'self'")
        CspInjector.onRulesReloaded()
        val before = CspInjector.getCspForDomain("example.com")
        assertNotNull(before)

        // Reload with no CSP rules
        FilterEngine.clear()
        FilterEngine.loadRules("||example.com^")
        CspInjector.onRulesReloaded()

        val after = CspInjector.getCspForDomain("example.com")
        assertNull("After reload with no CSP rules, result should be null", after)
    }
}

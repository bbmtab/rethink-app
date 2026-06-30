package com.celzero.bravedns.core.filter

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ScriptletFilter.
 * Verifies parsing of #%#//scriptlet() rules and JS snippet generation for domain matching.
 */
class ScriptletFilterTest {

    @Before
    fun setUp() {
        FilterEngine.clear()
        ScriptletFilter.clear()
    }

    // ── parseScriptletRule ────────────────────────────────────────────────────────

    @Test
    fun `parseScriptletRule returns null for non-scriptlet line`() {
        assertNull(ScriptletFilter.parseScriptletRule("example.com##.ad-banner"))
        assertNull(ScriptletFilter.parseScriptletRule("||tracker.com^"))
        assertNull(ScriptletFilter.parseScriptletRule("example.com#?#.ad:has(img)"))
        assertNull(ScriptletFilter.parseScriptletRule(""))
    }

    @Test
    fun `parseScriptletRule parses basic rule with domain`() {
        val rule = ScriptletFilter.parseScriptletRule("example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle')")
        assertNotNull(rule)
        assertEquals("abort-on-property-read", rule!!.name)
        assertEquals(listOf("adsbygoogle"), rule.args)
        assertTrue(rule.allowedDomains!!.contains("example.com"))
        assertNull(rule.excludedDomains)
    }

    @Test
    fun `parseScriptletRule parses global rule without domain`() {
        val rule = ScriptletFilter.parseScriptletRule("#%#//scriptlet('set-constant', 'canRunAds', 'true')")
        assertNotNull(rule)
        assertEquals("set-constant", rule!!.name)
        assertEquals(listOf("canRunAds", "true"), rule.args)
        assertNull(rule.allowedDomains)
        assertNull(rule.excludedDomains)
    }

    @Test
    fun `parseScriptletRule parses multiple domains`() {
        val rule = ScriptletFilter.parseScriptletRule("foo.com,bar.com#%#//scriptlet('no-fetch-if', 'analytics')")
        assertNotNull(rule)
        assertTrue(rule!!.allowedDomains!!.contains("foo.com"))
        assertTrue(rule.allowedDomains!!.contains("bar.com"))
    }

    @Test
    fun `parseScriptletRule parses excluded domain`() {
        val rule = ScriptletFilter.parseScriptletRule("~safe.com#%#//scriptlet('no-xhr-if', 'tracking')")
        assertNotNull(rule)
        assertNull(rule!!.allowedDomains)
        assertTrue(rule.excludedDomains!!.contains("safe.com"))
    }

    @Test
    fun `parseScriptletRule parses alias function names`() {
        // Test aopr alias for abort-on-property-read
        val rule1 = ScriptletFilter.parseScriptletRule("example.com#%#//scriptlet('aopr', 'googletag')")
        assertNotNull(rule1)
        assertEquals("aopr", rule1!!.name)

        // Test aopw alias for abort-on-property-write
        val rule2 = ScriptletFilter.parseScriptletRule("example.com#%#//scriptlet('aopw', 'fbq')")
        assertNotNull(rule2)
        assertEquals("aopw", rule2!!.name)

        // Test sc alias for set-constant
        val rule3 = ScriptletFilter.parseScriptletRule("example.com#%#//scriptlet('sc', 'canRunAds', 'true')")
        assertNotNull(rule3)
        assertEquals("sc", rule3!!.name)
    }

    @Test
    fun `parseScriptletRule handles arguments with special characters`() {
        val rule = ScriptletFilter.parseScriptletRule(
            "example.com#%#//scriptlet('json-prune', 'ads.tracking data', 'ad_data')"
        )
        assertNotNull(rule)
        assertEquals("json-prune", rule!!.name)
        assertEquals(listOf("ads.tracking data", "ad_data"), rule.args)
    }

    @Test
    fun `parseScriptletRule handles trailing semicolon`() {
        val rule = ScriptletFilter.parseScriptletRule("example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle');")
        assertNotNull(rule)
        assertEquals("abort-on-property-read", rule!!.name)
        assertEquals(listOf("adsbygoogle"), rule.args)
    }

    // ── parseScriptletArgs ────────────────────────────────────────────────────────

    @Test
    fun `parseScriptletArgs parses single quoted argument`() {
        val args = ScriptletFilter.parseScriptletArgs("'abort-on-property-read'")
        assertEquals(listOf("abort-on-property-read"), args)
    }

    @Test
    fun `parseScriptletArgs parses multiple quoted arguments`() {
        val args = ScriptletFilter.parseScriptletArgs("'set-constant', 'canRunAds', 'true'")
        assertEquals(listOf("set-constant", "canRunAds", "true"), args)
    }

    @Test
    fun `parseScriptletArgs handles escaped quotes in arguments`() {
        val args = ScriptletFilter.parseScriptletArgs("'it\\'s quoted', 'normal'")
        assertEquals(listOf("it's quoted", "normal"), args)
    }

    @Test
    fun `parseScriptletArgs handles unquoted arguments`() {
        val args = ScriptletFilter.parseScriptletArgs("true, false")
        assertEquals(listOf("true", "false"), args)
    }

    // ── buildInvocation ──────────────────────────────────────────────────────────

    @Test
    fun `buildInvocation generates correct JS call`() {
        val rule = ScriptletFilter.ScriptletRule(
            name = "abort-on-property-read",
            args = listOf("adsbygoogle"),
            allowedDomains = null,
            excludedDomains = null
        )
        val invocation = ScriptletFilter.buildInvocation(rule)
        assertTrue(invocation.contains("window.rethinkScriptlets['abort-on-property-read']('adsbygoogle')"))
        assertTrue(invocation.contains("try"))
        assertTrue(invocation.contains("catch"))
    }

    @Test
    fun `buildInvocation handles empty args`() {
        val rule = ScriptletFilter.ScriptletRule(
            name = "noop",
            args = emptyList(),
            allowedDomains = null,
            excludedDomains = null
        )
        val invocation = ScriptletFilter.buildInvocation(rule)
        assertTrue(invocation.contains("window.rethinkScriptlets['noop']()"))
    }

    @Test
    fun `buildInvocation escapes single quotes in args`() {
        val rule = ScriptletFilter.ScriptletRule(
            name = "test",
            args = listOf("it's escaped"),
            allowedDomains = null,
            excludedDomains = null
        )
        val invocation = ScriptletFilter.buildInvocation(rule)
        assertTrue(invocation.contains("'it\\'s escaped'"))
    }

    // ── getScriptletCodeForDomain ─────────────────────────────────────────────────

    @Test
    fun `getScriptletCodeForDomain returns null when FilterEngine not loaded`() {
        val result = ScriptletFilter.getScriptletCodeForDomain("example.com", null)
        assertNull(result)
    }

    @Test
    fun `getScriptletCodeForDomain returns null when no scriptlet rules loaded`() {
        FilterEngine.loadRules("example.com##.ad-banner\n||tracker.com^")
        ScriptletFilter.onRulesReloaded()
        assertNull(ScriptletFilter.getScriptletCodeForDomain("example.com", null))
    }

    @Test
    fun `getScriptletCodeForDomain returns JS for matching domain`() {
        FilterEngine.loadRules("example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle')")
        ScriptletFilter.onRulesReloaded()

        val js = ScriptletFilter.getScriptletCodeForDomain("example.com", null)
        assertNotNull(js)
        assertTrue("JS should contain the library", js!!.contains("rethinkScriptlets"))
        assertTrue("JS should contain the invocation", js.contains("abort-on-property-read"))
        assertTrue("JS should contain the argument", js.contains("adsbygoogle"))
    }

    @Test
    fun `getScriptletCodeForDomain matches subdomain`() {
        FilterEngine.loadRules("example.com#%#//scriptlet('no-fetch-if', 'ads')")
        ScriptletFilter.onRulesReloaded()

        val js = ScriptletFilter.getScriptletCodeForDomain("sub.example.com", null)
        assertNotNull("Subdomain should match parent rule", js)
    }

    @Test
    fun `getScriptletCodeForDomain returns null for excluded domain`() {
        FilterEngine.loadRules("~safe.com#%#//scriptlet('no-xhr-if', 'track')")
        ScriptletFilter.onRulesReloaded()

        assertNull(ScriptletFilter.getScriptletCodeForDomain("safe.com", null))
        assertNotNull(ScriptletFilter.getScriptletCodeForDomain("other.com", null))
    }

    @Test
    fun `getScriptletCodeForDomain applies global rule to all domains`() {
        FilterEngine.loadRules("#%#//scriptlet('set-constant', 'canRunAds', 'true')")
        ScriptletFilter.onRulesReloaded()

        assertNotNull(ScriptletFilter.getScriptletCodeForDomain("any-site.com", null))
        assertNotNull(ScriptletFilter.getScriptletCodeForDomain("another.org", null))
    }

    @Test
    fun `getScriptletCodeForDomain caches result`() {
        FilterEngine.loadRules("example.com#%#//scriptlet('abort-on-property-read', 'googletag')")
        ScriptletFilter.onRulesReloaded()

        val first = ScriptletFilter.getScriptletCodeForDomain("example.com", null)
        val second = ScriptletFilter.getScriptletCodeForDomain("example.com", null)
        assertEquals(first, second)
    }

    @Test
    fun `getScriptletCodeForDomain combines multiple matching rules`() {
        FilterEngine.loadRules("""
            example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle')
            example.com#%#//scriptlet('set-constant', 'canRunAds', 'true')
            #%#//scriptlet('no-fetch-if', 'analytics')
        """.trimIndent())
        ScriptletFilter.onRulesReloaded()

        val js = ScriptletFilter.getScriptletCodeForDomain("example.com", null)
        assertNotNull(js)
        assertTrue("Should contain first scriptlet", js!!.contains("abort-on-property-read"))
        assertTrue("Should contain second scriptlet", js.contains("set-constant"))
        assertTrue("Should contain global scriptlet", js.contains("no-fetch-if"))
    }

    // ── FilterEngine integration ──────────────────────────────────────────────────

    @Test
    fun `FilterEngine routes scriptlet rules to scriptletRules list`() {
        FilterEngine.loadRules("""
            example.com##.css-only
            example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle')
            ||block-this.com^
        """.trimIndent())

        assertEquals("scriptletRules should have 1 entry", 1, FilterEngine.scriptletRules.size)
        assertEquals("cosmeticRules should have 1 entry", 1, FilterEngine.cosmeticRules.size)
        assertTrue("scriptletRules should contain #%#//scriptlet line",
            FilterEngine.scriptletRules[0].contains("#%#//scriptlet"))
    }

    @Test
    fun `onRulesReloaded clears cached JS`() {
        FilterEngine.loadRules("example.com#%#//scriptlet('abort-on-property-read', 'googletag')")
        ScriptletFilter.onRulesReloaded()
        val before = ScriptletFilter.getScriptletCodeForDomain("example.com", null)
        assertNotNull(before)

        FilterEngine.clear()
        FilterEngine.loadRules("||unrelated.com^")
        ScriptletFilter.onRulesReloaded()

        val after = ScriptletFilter.getScriptletCodeForDomain("example.com", null)
        assertNull("After reload with no scriptlet rules, should return null", after)
    }

    @Test
    fun `parseRule creates isScriptlet=true AdblockRule for #%# scriptlet lines`() {
        val rule = FilterEngine.parseRule("example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle')")
        assertNotNull(rule)
        assertTrue(rule!!.isScriptlet)
        assertEquals("example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle')", rule.rawText)
    }

    @Test
    fun `parseRule does not treat #%# scriptlet as cosmetic`() {
        val rule = FilterEngine.parseRule("example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle')")
        assertNotNull(rule)
        assertTrue(rule!!.isScriptlet)
        assertFalse(rule.isCosmetic)
    }

    @Test
    fun `scriptlet rules are not added to network rule trie`() {
        FilterEngine.loadRules("""
            ||ads.com^
            example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle')
        """.trimIndent())

        // Network rule should still match
        assertTrue(FilterEngine.match("https://ads.com/pixel", "ads.com", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block)

        // Scriptlet rules should not create false network matches
        assertTrue(FilterEngine.match("https://example.com/page", "example.com", false, FilterEngine.ResourceType.DOCUMENT) is FilterEngine.MatchResult.Allow)
    }
}
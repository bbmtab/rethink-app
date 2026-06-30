package com.celzero.bravedns.core.filter

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ProceduralFilter.
 */
class ProceduralFilterTest {

    @Before
    fun setUp() {
        FilterEngine.clear()
        ProceduralFilter.clear()
    }

    // ── parseProceduralRule ──────────────────────────────────────────────────

    @Test
    fun `parseProceduralRule returns null for non-procedural line`() {
        assertNull(ProceduralFilter.parseProceduralRule("example.com##.ad-banner"))
        assertNull(ProceduralFilter.parseProceduralRule("||tracker.com^"))
        assertNull(ProceduralFilter.parseProceduralRule(""))
    }

    @Test
    fun `parseProceduralRule parses basic rule with domain`() {
        val rule = ProceduralFilter.parseProceduralRule("example.com#?#.banner:has(img)")
        assertNotNull(rule)
        assertEquals(".banner:has(img)", rule!!.rawSelector)
        assertTrue(rule.allowedDomains!!.contains("example.com"))
        assertNull(rule.excludedDomains)
    }

    @Test
    fun `parseProceduralRule parses global rule without domain`() {
        val rule = ProceduralFilter.parseProceduralRule("#?#div:has-text(Sponsored)")
        assertNotNull(rule)
        assertEquals("div:has-text(Sponsored)", rule!!.rawSelector)
        assertNull(rule.allowedDomains)
        assertNull(rule.excludedDomains)
    }

    @Test
    fun `parseProceduralRule parses multiple domains`() {
        val rule = ProceduralFilter.parseProceduralRule("foo.com,bar.com#?#.ad")
        assertNotNull(rule)
        assertTrue(rule!!.allowedDomains!!.contains("foo.com"))
        assertTrue(rule.allowedDomains!!.contains("bar.com"))
    }

    @Test
    fun `parseProceduralRule parses excluded domain`() {
        val rule = ProceduralFilter.parseProceduralRule("~safe.com#?#.ad")
        assertNotNull(rule)
        assertNull(rule!!.allowedDomains)
        assertTrue(rule.excludedDomains!!.contains("safe.com"))
    }

    // ── getScriptForDomain ───────────────────────────────────────────────────

    @Test
    fun `getScriptForDomain returns null when FilterEngine not loaded`() {
        val result = ProceduralFilter.getScriptForDomain("example.com")
        assertNull(result)
    }

    @Test
    fun `getScriptForDomain returns null when no procedural rules loaded`() {
        FilterEngine.loadRules("example.com##.ad-banner\n||tracker.com^")
        ProceduralFilter.onRulesReloaded()
        assertNull(ProceduralFilter.getScriptForDomain("example.com"))
    }

    @Test
    fun `getScriptForDomain returns JS for matching domain`() {
        FilterEngine.loadRules("example.com#?#.banner:has(img[src*=\"ads\"])")
        ProceduralFilter.onRulesReloaded()

        val js = ProceduralFilter.getScriptForDomain("example.com")
        assertNotNull(js)
        assertTrue("JS should contain the selector", js!!.contains(".banner:has(img[src*="))
        assertTrue("JS should be self-executing IIFE", js.contains("(function()"))
    }

    @Test
    fun `getScriptForDomain matches subdomain`() {
        FilterEngine.loadRules("example.com#?#.ad")
        ProceduralFilter.onRulesReloaded()

        val js = ProceduralFilter.getScriptForDomain("sub.example.com")
        assertNotNull("Subdomain should match parent rule", js)
    }

    @Test
    fun `getScriptForDomain returns null for excluded domain`() {
        FilterEngine.loadRules("~safe.com#?#.ad")
        ProceduralFilter.onRulesReloaded()

        assertNull(ProceduralFilter.getScriptForDomain("safe.com"))
        assertNotNull(ProceduralFilter.getScriptForDomain("other.com"))
    }

    @Test
    fun `getScriptForDomain applies global rule to all domains`() {
        FilterEngine.loadRules("#?#div:has-text(Advertisement)")
        ProceduralFilter.onRulesReloaded()

        assertNotNull(ProceduralFilter.getScriptForDomain("any-site.com"))
        assertNotNull(ProceduralFilter.getScriptForDomain("another.org"))
    }

    @Test
    fun `getScriptForDomain caches result`() {
        FilterEngine.loadRules("example.com#?#.ad")
        ProceduralFilter.onRulesReloaded()

        val first = ProceduralFilter.getScriptForDomain("example.com")
        val second = ProceduralFilter.getScriptForDomain("example.com")
        assertEquals(first, second)
    }

    // ── buildJs content ──────────────────────────────────────────────────────

    @Test
    fun `buildJs includes has-text handler`() {
        val rules = listOf(
            ProceduralFilter.ProceduralRule("p:has-text(Sponsored)", null, null)
        )
        val js = ProceduralFilter.buildJs(rules)
        assertTrue(js.contains(":has-text("))
        assertTrue(js.contains("textContent"))
    }

    @Test
    fun `buildJs includes upward handler`() {
        val rules = listOf(
            ProceduralFilter.ProceduralRule(".ad:upward(2)", null, null)
        )
        val js = ProceduralFilter.buildJs(rules)
        assertTrue(js.contains(":upward(") || js.contains("upward"))
        assertTrue(js.contains("parentElement"))
    }

    @Test
    fun `buildJs includes xpath handler`() {
        val rules = listOf(
            ProceduralFilter.ProceduralRule(":xpath(//div[@data-ad])", null, null)
        )
        val js = ProceduralFilter.buildJs(rules)
        assertTrue(js.contains(":xpath("))
        assertTrue(js.contains("document.evaluate"))
    }

    @Test
    fun `buildJs includes MutationObserver for dynamic pages`() {
        val rules = listOf(
            ProceduralFilter.ProceduralRule(".ad", null, null)
        )
        val js = ProceduralFilter.buildJs(rules)
        assertTrue("Should include MutationObserver", js.contains("MutationObserver"))
    }

    @Test
    fun `buildJs includes DOMContentLoaded listener`() {
        val rules = listOf(
            ProceduralFilter.ProceduralRule(".ad", null, null)
        )
        val js = ProceduralFilter.buildJs(rules)
        assertTrue(js.contains("DOMContentLoaded"))
    }

    @Test
    fun `buildJs returns empty string for empty rules`() {
        val js = ProceduralFilter.buildJs(emptyList())
        assertEquals("", js)
    }

    // ── FilterEngine integration ─────────────────────────────────────────────

    @Test
    fun `FilterEngine routes procedural rules to proceduralRules list`() {
        FilterEngine.loadRules("""
            example.com##.css-only
            example.com#?#.js-needed:has(img)
            ||block-this.com^
        """.trimIndent())

        assertEquals("proceduralRules should have 1 entry", 1, FilterEngine.proceduralRules.size)
        assertEquals("cosmeticRules should have 1 entry", 1, FilterEngine.cosmeticRules.size)
        assertTrue("proceduralRules should contain #?# line",
            FilterEngine.proceduralRules[0].contains("#?#"))
    }

    @Test
    fun `onRulesReloaded clears cached JS`() {
        FilterEngine.loadRules("example.com#?#.ad")
        ProceduralFilter.onRulesReloaded()
        val before = ProceduralFilter.getScriptForDomain("example.com")
        assertNotNull(before)

        FilterEngine.clear()
        FilterEngine.loadRules("||unrelated.com^")
        ProceduralFilter.onRulesReloaded()

        val after = ProceduralFilter.getScriptForDomain("example.com")
        assertNull("After reload with no procedural rules, should return null", after)
    }
}

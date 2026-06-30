package com.celzero.bravedns.core.filter

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for HtmlFilter.
 * Verifies parsing of ##^ rules and HTML element removal using Jsoup.
 */
class HtmlFilterTest {

    @Before
    fun setUp() {
        FilterEngine.clear()
        HtmlFilter.clear()
    }

    @Test
    fun `parseHtmlFilterRule returns null for non-html-filter line`() {
        assertNull(HtmlFilter.parseHtmlFilterRule("example.com##.ad-banner"))
        assertNull(HtmlFilter.parseHtmlFilterRule("||tracker.com^"))
        assertNull(HtmlFilter.parseHtmlFilterRule("example.com#?#.ad:has(img)"))
        assertNull(HtmlFilter.parseHtmlFilterRule("example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle')"))
        assertNull(HtmlFilter.parseHtmlFilterRule(""))
    }

    @Test
    fun `parseHtmlFilterRule parses basic rule with domain`() {
        val rule = HtmlFilter.parseHtmlFilterRule("example.com##^script[src*=\"adsbygoogle\"]")
        assertNotNull(rule)
        assertEquals("script[src*=\"adsbygoogle\"]", rule!!.cssSelector)
        assertTrue(rule.allowedDomains!!.contains("example.com"))
        assertNull(rule.excludedDomains)
    }

    @Test
    fun `parseHtmlFilterRule parses global rule without domain`() {
        val rule = HtmlFilter.parseHtmlFilterRule("##^div.advertisement")
        assertNotNull(rule)
        assertEquals("div.advertisement", rule!!.cssSelector)
        assertNull(rule.allowedDomains)
        assertNull(rule.excludedDomains)
    }

    @Test
    fun `parseHtmlFilterRule parses multiple domains`() {
        val rule = HtmlFilter.parseHtmlFilterRule("foo.com,bar.com##^div.ad")
        assertNotNull(rule)
        assertTrue(rule!!.allowedDomains!!.contains("foo.com"))
        assertTrue(rule.allowedDomains!!.contains("bar.com"))
    }

    @Test
    fun `parseHtmlFilterRule parses excluded domain`() {
        val rule = HtmlFilter.parseHtmlFilterRule("~safe.com##^script[src*=\"tracking\"]")
        assertNotNull(rule)
        assertNull(rule!!.allowedDomains)
        assertTrue(rule.excludedDomains!!.contains("safe.com"))
    }

    @Test
    fun `parseHtmlFilterRule parses attribute selectors`() {
        val rule = HtmlFilter.parseHtmlFilterRule("example.com##^div[id=\"ad-container\"]")
        assertNotNull(rule)
        assertEquals("div[id=\"ad-container\"]", rule!!.cssSelector)
    }

    @Test
    fun `parseHtmlFilterRule parses complex pseudo-selectors`() {
        val rule = HtmlFilter.parseHtmlFilterRule("example.com##^script[src*=\"adsbygoogle\"]")
        assertNotNull(rule)
        assertEquals("script[src*=\"adsbygoogle\"]", rule!!.cssSelector)

        val rule2 = HtmlFilter.parseHtmlFilterRule("example.com##^div[data-ad]")
        assertNotNull(rule2)
        assertEquals("div[data-ad]", rule2!!.cssSelector)
    }

    @Test
    fun `applyFilters returns original HTML when no rules match`() {
        FilterEngine.loadRules("example.com##^script:has-text(adsbygoogle)")
        HtmlFilter.onRulesReloaded()

        val html = "<html><body><div>content</div></body></html>"
        val result = HtmlFilter.applyFilters("other.com", html)
        assertEquals(html, result)
    }

    @Test
    fun `applyFilters removes matching script elements`() {
        FilterEngine.loadRules("example.com##^script.ad-script")
        HtmlFilter.onRulesReloaded()

        val html = """
            <html>
            <head><title>Test</title></head>
            <body>
                <script class="ad-script">var adsbygoogle = window.adsbygoogle || [];</script>
                <script>console.log("other script");</script>
                <div>content</div>
            </body>
            </html>
        """.trimIndent()

        val result = HtmlFilter.applyFilters("example.com", html)
        assertTrue("Should remove script with ad-script class", !result.contains("adsbygoogle"))
        assertTrue("Should keep other script", result.contains("other script"))
        assertTrue("Should keep div content", result.contains("content"))
    }

    @Test
    fun `applyFilters removes matching div by class`() {
        FilterEngine.loadRules("##^.advertisement")
        HtmlFilter.onRulesReloaded()

        val html = """
            <html>
            <body>
                <div class="advertisement">Ad content</div>
                <div class="normal">Normal content</div>
            </body>
            </html>
        """.trimIndent()

        val result = HtmlFilter.applyFilters("any-site.com", html)
        assertTrue("Should remove advertisement div", !result.contains("Ad content"))
        assertTrue("Should keep normal div", result.contains("Normal content"))
    }

    @Test
    fun `applyFilters removes matching div by attribute`() {
        FilterEngine.loadRules("example.com##^div[id=\"ad-container\"]")
        HtmlFilter.onRulesReloaded()

        val html = """
            <html>
            <body>
                <div id="ad-container">Ad content</div>
                <div id="content">Normal content</div>
            </body>
            </html>
        """.trimIndent()

        val result = HtmlFilter.applyFilters("example.com", html)
        assertTrue("Should remove ad-container div", !result.contains("Ad content"))
        assertTrue("Should keep content div", result.contains("Normal content"))
    }

    @Test
    fun `applyFilters removes matching script by src attribute`() {
        FilterEngine.loadRules("example.com##^script[src*=\"analytics\"]")
        HtmlFilter.onRulesReloaded()

        val html = """
            <html>
            <body>
                <script src="https://example.com/analytics.js"></script>
                <script src="https://example.com/app.js"></script>
            </body>
            </html>
        """.trimIndent()

        val result = HtmlFilter.applyFilters("example.com", html)
        assertTrue("Should remove analytics script", !result.contains("analytics.js"))
        assertTrue("Should keep app script", result.contains("app.js"))
    }

    @Test
    fun `applyFilters respects excluded domains`() {
        FilterEngine.loadRules("~safe.com##^div.ad")
        HtmlFilter.onRulesReloaded()

        val html = "<html><body><div class=\"ad\">Ad</div></body></html>"

        // Should NOT filter excluded domain
        val resultExcluded = HtmlFilter.applyFilters("safe.com", html)
        assertEquals(html, resultExcluded)

        // Should filter other domains
        val resultOther = HtmlFilter.applyFilters("other.com", html)
        assertTrue("Should remove ad on other.com", !resultOther.contains("Ad"))
    }

    @Test
    fun `applyFilters handles large HTML bodies`() {
        // Create a large HTML body (> 2MB limit)
        val largeHtml = "<html><body>" + "x".repeat(3 * 1024 * 1024) + "</body></html>"
        FilterEngine.loadRules("example.com##^div.ad")
        HtmlFilter.onRulesReloaded()

        val result = HtmlFilter.applyFilters("example.com", largeHtml)
        // Should return original unmodified HTML for large bodies
        assertEquals(largeHtml, result)
    }

    @Test
    fun `applyFilters handles invalid selectors gracefully`() {
        FilterEngine.loadRules("example.com##^invalid::::selector")
        HtmlFilter.onRulesReloaded()

        val html = "<html><body><div>content</div></body></html>"
        // Should not crash, return original HTML content (Jsoup may reformat)
        val result = HtmlFilter.applyFilters("example.com", html)
        assertTrue("Should preserve content", result.contains("content"))
        assertTrue("Should not crash or throw", result.contains("<div>"))
    }

    @Test
    fun `applyFilters removes multiple matching elements`() {
        FilterEngine.loadRules("example.com##^span.tracker")
        HtmlFilter.onRulesReloaded()

        val html = """
            <html>
            <body>
                <span class="tracker">t1</span>
                <span class="tracker">t2</span>
                <span class="normal">n1</span>
            </body>
            </html>
        """.trimIndent()

        val result = HtmlFilter.applyFilters("example.com", html)
        assertTrue("Should remove first tracker", !result.contains("t1"))
        assertTrue("Should remove second tracker", !result.contains("t2"))
        assertTrue("Should keep normal span", result.contains("n1"))
    }

    @Test
    fun `hasRulesForDomain returns true when rules exist`() {
        FilterEngine.loadRules("example.com##^script:has-text(adsbygoogle)")
        HtmlFilter.onRulesReloaded()

        assertTrue(HtmlFilter.hasRulesForDomain("example.com"))
        assertTrue(HtmlFilter.hasRulesForDomain("sub.example.com"))
        assertFalse(HtmlFilter.hasRulesForDomain("other.com"))
    }

    @Test
    fun `HasRulesForDomain returns false when no rules loaded`() {
        FilterEngine.loadRules("||ads.com^")
        HtmlFilter.onRulesReloaded()

        assertFalse(HtmlFilter.hasRulesForDomain("example.com"))
    }

    @Test
    fun `onRulesReloaded clears cached rules`() {
        FilterEngine.loadRules("example.com##^div.ad")
        HtmlFilter.onRulesReloaded()
        assertTrue(HtmlFilter.hasRulesForDomain("example.com"))

        FilterEngine.clear()
        FilterEngine.loadRules("||unrelated.com^")
        HtmlFilter.onRulesReloaded()

        assertFalse(HtmlFilter.hasRulesForDomain("example.com"))
    }

    @Test
    fun `FilterEngine routes HTML filter rules to htmlFilterRules list`() {
        FilterEngine.loadRules("""
            example.com##.css-only
            example.com##^script:has-text(adsbygoogle)
            ||block-this.com^
        """.trimIndent())

        assertEquals("htmlFilterRules should have 1 entry", 1, FilterEngine.htmlFilterRules.size)
        assertEquals("cosmeticRules should have 1 entry", 1, FilterEngine.cosmeticRules.size)
        assertTrue("htmlFilterRules should contain ##^ line",
            FilterEngine.htmlFilterRules[0].contains("##^"))
    }

    @Test
    fun `parseRule creates isHtmlFilter=true AdblockRule for ##^ lines`() {
        val rule = FilterEngine.parseRule("example.com##^script:has-text(adsbygoogle)")
        assertNotNull(rule)
        assertTrue(rule!!.isHtmlFilter)
        assertEquals("example.com##^script:has-text(adsbygoogle)", rule.rawText)
    }

    @Test
    fun `parseRule does not treat ##^ as cosmetic`() {
        val rule = FilterEngine.parseRule("example.com##^script:has-text(adsbygoogle)")
        assertNotNull(rule)
        assertTrue(rule!!.isHtmlFilter)
        assertFalse(rule.isCosmetic)
    }

    @Test
    fun `parseRule does not treat ##^ as scriptlet`() {
        val rule = FilterEngine.parseRule("example.com##^script:has-text(adsbygoogle)")
        assertNotNull(rule)
        assertTrue(rule!!.isHtmlFilter)
        assertFalse(rule.isScriptlet)
    }

    @Test
    fun `HTML filter rules are not added to network rule trie`() {
        FilterEngine.loadRules("""
            ||ads.com^
            example.com##^script:has-text(adsbygoogle)
        """.trimIndent())

        // Network rule should still match
        assertTrue(FilterEngine.match("https://ads.com/pixel", "ads.com", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block)

        // HTML filter rules should not create false network matches
        assertTrue(FilterEngine.match("https://example.com/page", "example.com", false, FilterEngine.ResourceType.DOCUMENT) is FilterEngine.MatchResult.Allow)
    }
}
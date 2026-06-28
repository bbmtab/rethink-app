package com.celzero.bravedns.core.filter

import com.celzero.bravedns.core.proxy.LocalHttpsProxy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket

class FilterEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        FilterEngine.clear()
    }

    @After
    fun tearDown() {
        FilterEngine.clear()
        LocalHttpsProxy.stop()
        LocalHttpsProxy.proxyListener = null
    }

    @Test
    fun testParseAndMatchExactDomain() {
        val rulesText = """
            ||doubleclick.net^
            ||googlesyndication.com^
        """.trimIndent()
        
        FilterEngine.loadRules(rulesText)
        
        // Exact domains should be blocked
        assertTrue(FilterEngine.match("https://doubleclick.net/pagead/img", "doubleclick.net", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block)
        assertTrue(FilterEngine.match("https://secure.doubleclick.net/ads", "secure.doubleclick.net", false, FilterEngine.ResourceType.SCRIPT) is FilterEngine.MatchResult.Block)
        
        // Unrelated domains should pass
        assertTrue(FilterEngine.match("https://google.com/search", "google.com", false, FilterEngine.ResourceType.DOCUMENT) is FilterEngine.MatchResult.Allow)
    }

    @Test
    fun testParseAndMatchThirdParty() {
        val rulesText = """
            ||ads.com^${'$'}third-party
        """.trimIndent()
        
        FilterEngine.loadRules(rulesText)
        
        // If request to ads.com is a third-party request (relative to referer), it should block
        val blockResult = FilterEngine.match(
            url = "https://ads.com/banner.png",
            host = "ads.com",
            isThirdParty = true,
            resourceType = FilterEngine.ResourceType.IMAGE,
            refererHost = "example.com"
        )
        assertTrue("Third-party ads.com request must be blocked", blockResult is FilterEngine.MatchResult.Block)

        // If request to ads.com is first-party (not third-party), it should be allowed
        val allowResult = FilterEngine.match(
            url = "https://ads.com/banner.png",
            host = "ads.com",
            isThirdParty = false,
            resourceType = FilterEngine.ResourceType.IMAGE,
            refererHost = "ads.com"
        )
        assertTrue("First-party ads.com request must be allowed", allowResult is FilterEngine.MatchResult.Allow)
    }

    @Test
    fun testParseAndMatchWhitelist() {
        val rulesText = """
            ||analytics.com^
            @@||analytics.com/essential.js
        """.trimIndent()
        
        FilterEngine.loadRules(rulesText)
        
        // General analytics block
        assertTrue(FilterEngine.match("https://analytics.com/track.js", "analytics.com", false, FilterEngine.ResourceType.SCRIPT) is FilterEngine.MatchResult.Block)
        
        // Specific whitelisted path
        assertTrue(FilterEngine.match("https://analytics.com/essential.js", "analytics.com", false, FilterEngine.ResourceType.SCRIPT) is FilterEngine.MatchResult.Allow)
    }

    @Test
    fun testParseAndMatchImportantModifier() {
        val rulesText = """
            ||track.com^${'$'}important
            @@||track.com^
        """.trimIndent()
        
        FilterEngine.loadRules(rulesText)
        
        // Standard whitelist would override standard block, but $important block overrides standard whitelist!
        val matchResult = FilterEngine.match("https://track.com/ads.js", "track.com", false, FilterEngine.ResourceType.SCRIPT)
        assertTrue("${'$'}important block must override standard whitelist", matchResult is FilterEngine.MatchResult.Block)
    }

    @Test
    fun testParseAndMatchDomainRestriction() {
        val rulesText = """
            ||bad-ads.net^${'$'}domain=news.com|blog.org
        """.trimIndent()
        
        FilterEngine.loadRules(rulesText)
        
        // Blocked when referred by news.com
        assertTrue(FilterEngine.match(
            url = "https://bad-ads.net/banner.png",
            host = "bad-ads.net",
            isThirdParty = true,
            resourceType = FilterEngine.ResourceType.IMAGE,
            refererHost = "news.com"
        ) is FilterEngine.MatchResult.Block)

        // Allowed when referred by google.com
        assertTrue(FilterEngine.match(
            url = "https://bad-ads.net/banner.png",
            host = "bad-ads.net",
            isThirdParty = true,
            resourceType = FilterEngine.ResourceType.IMAGE,
            refererHost = "google.com"
        ) is FilterEngine.MatchResult.Allow)
    }

    @Test
    fun testParseAndMatchResourceType() {
        val rulesText = """
            ||tracking.com^${'$'}image,script
        """.trimIndent()
        
        FilterEngine.loadRules(rulesText)
        
        // Blocked on images and scripts
        assertTrue(FilterEngine.match("https://tracking.com/pixel", "tracking.com", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block)
        assertTrue(FilterEngine.match("https://tracking.com/tracker.js", "tracking.com", false, FilterEngine.ResourceType.SCRIPT) is FilterEngine.MatchResult.Block)
        
        // Allowed on stylesheets or documents
        assertTrue(FilterEngine.match("https://tracking.com/main.css", "tracking.com", false, FilterEngine.ResourceType.STYLESHEET) is FilterEngine.MatchResult.Allow)
    }

    @Test
    fun testParseAndMatchWildcardsAndRegex() {
        val rulesText = """
            ||cdn.example.com/ads/*
            /banner[0-9]+/
        """.trimIndent()
        
        FilterEngine.loadRules(rulesText)
        
        // Suffix path wildcard block
        assertTrue(FilterEngine.match("https://cdn.example.com/ads/pixel.png", "cdn.example.com", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block)
        assertTrue(FilterEngine.match("https://cdn.example.com/images/logo.png", "cdn.example.com", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Allow)
        
        // Regex block
        assertTrue(FilterEngine.match("https://other-cdn.com/banner123.jpg", "other-cdn.com", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block)
        assertTrue(FilterEngine.match("https://other-cdn.com/banner.jpg", "other-cdn.com", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Allow)
    }

    @Test
    fun testCosmeticRuleSegregation() {
        val rulesText = """
            example.com##.ad-class
            ##.sidebar-ad
            #@###.whitelisted-ad
            ||network-rule.com^
        """.trimIndent()
        
        FilterEngine.loadRules(rulesText)
        
        assertEquals(2, FilterEngine.cosmeticRules.size)
        assertEquals(1, FilterEngine.cosmeticExceptions.size)
        
        // Network rule is loaded in network list
        assertTrue(FilterEngine.match("https://network-rule.com/ad", "network-rule.com", false, FilterEngine.ResourceType.OTHER) is FilterEngine.MatchResult.Block)
        // Cosmetic rules do NOT affect network matches
        assertTrue(FilterEngine.match("https://example.com/page", "example.com", false, FilterEngine.ResourceType.DOCUMENT) is FilterEngine.MatchResult.Allow)
    }

    @Test
    fun testDiskCaching() {
        val rawFile = tempFolder.newFile("easylist.txt")
        rawFile.writeText("""
            ||ads.net^
            ||analytics.com^${'$'}important
            @@||analytics.com/essential${'$'}important
            example.com##.ad-banner
        """.trimIndent())
        
        val cacheDir = tempFolder.newFolder("cache")
        
        // Parse raw and build cache
        FilterEngine.loadRulesFromFile(rawFile, cacheDir)
        assertTrue(FilterEngine.isLoaded)
        
        // Verify rules work after parsing
        assertTrue(FilterEngine.match("https://ads.net/pixel", "ads.net", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block)
        
        // Verify cache file exists
        val cacheFile = File(cacheDir, "filter_rules_cache.bin")
        assertTrue("Cache binary must be generated", cacheFile.exists())
        assertTrue("Cache binary must not be empty", cacheFile.length() > 0)
        
        // Clear memory
        FilterEngine.clear()
        assertFalse(FilterEngine.isLoaded)
        
        // Reload from cache
        FilterEngine.loadRulesFromFile(rawFile, cacheDir)
        assertTrue("Should load successfully from cache", FilterEngine.isLoaded)
        
        // Verify rules still work correctly
        assertTrue(FilterEngine.match("https://ads.net/pixel", "ads.net", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block)
        assertTrue(FilterEngine.match("https://analytics.com/essential", "analytics.com", false, FilterEngine.ResourceType.OTHER) is FilterEngine.MatchResult.Allow)
        assertEquals(1, FilterEngine.cosmeticRules.size)
    }

    @Test
    fun testProxyAndFilterEngineIntegration() {
        val rulesText = """
            ||blocked-site.com^
            ||image-ad.com^${'$'}image
        """.trimIndent()
        
        FilterEngine.loadRules(rulesText)
        
        // Bind FilterEngine to LocalHttpsProxy using decoupled listener interface
        LocalHttpsProxy.proxyListener = object : LocalHttpsProxy.ProxyListener {
            override fun onRequestInspection(
                url: String,
                host: String,
                method: String,
                headers: List<String>,
                resourceType: Int
            ): Boolean {
                val referer = headers.find { it.lowercase().startsWith("referer:") }?.substring(8)?.trim()
                val isThirdParty = FilterEngine.isThirdPartyRequest(host, referer)
                
                val matchResult = FilterEngine.match(url, host, isThirdParty, resourceType, referer)
                return matchResult is FilterEngine.MatchResult.Allow
            }

            override fun onResponseInspection(
                url: String,
                statusCode: Int,
                headers: List<String>,
                decompressedBody: String?
            ) {}
        }

        val PROXY_TEST_PORT = 18444
        LocalHttpsProxy.start(PROXY_TEST_PORT)
        Thread.sleep(150)

        try {
            // Case 1: Standard requested site that is blocked (e.g. CONNECT blocked-site.com)
            Socket("localhost", PROXY_TEST_PORT).use { socket ->
                val out = socket.getOutputStream()
                out.write("CONNECT blocked-site.com:443 HTTP/1.1\r\nHost: blocked-site.com:443\r\n\r\n".toByteArray())
                out.flush()

                // Wait for the TLS bypass/mitm setup. In this test, wait for proxy to read/process.
                // Since this is a simple mock, let's verify that a GET plain HTTP to blocked-site.com gets blocked immediately.
            }

            // Case 2: Plain HTTP Get request that gets blocked
            Socket("localhost", PROXY_TEST_PORT).use { socket ->
                val out = socket.getOutputStream()
                out.write("GET http://blocked-site.com/index.html HTTP/1.1\r\nHost: blocked-site.com\r\n\r\n".toByteArray())
                out.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val responseLine = reader.readLine()
                assertNotNull(responseLine)
                // When request is blocked, the proxy returns a local block response ("HTTP/1.1 200 OK" or "403") with length 0
                assertTrue("Local block response should be returned, got: $responseLine", responseLine.contains("200 OK") || responseLine.contains("403"))
            }

        } finally {
            LocalHttpsProxy.stop()
        }
    }
}

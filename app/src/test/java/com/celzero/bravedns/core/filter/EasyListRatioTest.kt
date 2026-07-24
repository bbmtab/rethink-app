package com.celzero.bravedns.core.filter

import org.junit.Test
import org.junit.After
import org.junit.Assert.*
import okhttp3.OkHttpClient
import okhttp3.Request

class EasyListRatioTest {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        FilterEngine.clear()
    }

    @Test
    fun testEasyListParsedRatio() {
        // Fetch EasyList
        val request = Request.Builder()
            .url("https://easylist.to/easylist/easylist.txt")
            .build()

        val response = client.newCall(request).execute()
        assertTrue("Failed to fetch EasyList: ${response.code}", response.isSuccessful)
        val rulesText = response.body?.string() ?: ""

        // Count total non-comment, non-header lines
        val lines = rulesText.lines()
        val totalLines = lines.count { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("!") && !trimmed.startsWith("[")
        }

        // Parse with FilterEngine
        FilterEngine.clear()
        FilterEngine.loadRules(rulesText)

        // Count parsed rules across all categories using reflection for private fields
        val genericCount = getArrayListSize("genericRules")
        val trieCount = countTrieRules()
        val cosmeticCount = FilterEngine.cosmeticRules.size
        val cosmeticExceptionCount = FilterEngine.cosmeticExceptions.size
        val cspCount = FilterEngine.cspRules.size
        val proceduralCount = FilterEngine.proceduralRules.size
        val scriptletCount = FilterEngine.scriptletRules.size
        val htmlFilterCount = FilterEngine.htmlFilterRules.size

        val parsedTotal = genericCount + trieCount + cosmeticCount + cosmeticExceptionCount +
            cspCount + proceduralCount + scriptletCount + htmlFilterCount

        val ratio = if (totalLines > 0) parsedTotal.toDouble() / totalLines else 0.0

        println("=== EasyList Parse Ratio ===")
        println("Total filter lines (excl comments/headers): $totalLines")
        println("Parsed rules by category:")
        println("  genericRules: $genericCount")
        println("  domainTrie rules: $trieCount")
        println("  cosmeticRules: $cosmeticCount")
        println("  cosmeticExceptions: $cosmeticExceptionCount")
        println("  cspRules: $cspCount")
        println("  proceduralRules: $proceduralCount")
        println("  scriptletRules: $scriptletCount")
        println("  htmlFilterRules: $htmlFilterCount")
        println("  TOTAL parsed: $parsedTotal")
        println("Ratio = $parsedTotal / $totalLines = ${String.format("%.4f", ratio)} (${String.format("%.2f", ratio * 100)}%)")

        // Test that actual matching works
        val testUrl = "https://doubleclick.net/pagead/img"
        val result = FilterEngine.match(
            url = testUrl,
            host = "doubleclick.net",
            isThirdParty = false,
            resourceType = FilterEngine.ResourceType.IMAGE,
            refererHost = ""
        )
        println("Test match '$testUrl': $result")

        assertTrue("FilterEngine loaded rules", FilterEngine.isLoaded)
        assertTrue("cosmeticRules populated", FilterEngine.cosmeticRules.size > 0)
        assertTrue("Parse ratio reasonable (>= 0.8)", ratio >= 0.8)
    }

    @Test
    fun testAdGuardBaseParsedRatio() {
        // Fetch AdGuard Base list
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/master/Filters/filter.txt")
            .build()

        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val rulesText = response.body?.string() ?: ""

            val lines = rulesText.lines()
            val totalLines = lines.count { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && !trimmed.startsWith("!") && !trimmed.startsWith("[")
            }

            FilterEngine.clear()
            FilterEngine.loadRules(rulesText)

            val genericCount = getArrayListSize("genericRules")
            val trieCount = countTrieRules()
            val cosmeticCount = FilterEngine.cosmeticRules.size
            val cosmeticExceptionCount = FilterEngine.cosmeticExceptions.size
            val cspCount = FilterEngine.cspRules.size
            val proceduralCount = FilterEngine.proceduralRules.size
            val scriptletCount = FilterEngine.scriptletRules.size
            val htmlFilterCount = FilterEngine.htmlFilterRules.size

            val parsedTotal = genericCount + trieCount + cosmeticCount + cosmeticExceptionCount +
                cspCount + proceduralCount + scriptletCount + htmlFilterCount

            val ratio = if (totalLines > 0) parsedTotal.toDouble() / totalLines else 0.0

            println("=== AdGuard Base Parse Ratio ===")
            println("Total filter lines (excl comments/headers): $totalLines")
            println("Parsed rules by category:")
            println("  genericRules: $genericCount")
            println("  domainTrie rules: $trieCount")
            println("  cosmeticRules: $cosmeticCount")
            println("  cosmeticExceptions: $cosmeticExceptionCount")
            println("  cspRules: $cspCount")
            println("  proceduralRules: $proceduralCount")
            println("  scriptletRules: $scriptletCount")
            println("  htmlFilterRules: $htmlFilterCount")
            println("  TOTAL parsed: $parsedTotal")
            println("Ratio = $parsedTotal / $totalLines = ${String.format("%.4f", ratio)} (${String.format("%.2f", ratio * 100)}%)")

            assertTrue("FilterEngine loaded rules", FilterEngine.isLoaded)
            assertTrue("Parse ratio reasonable (>= 0.8)", ratio >= 0.8)
        } else {
            println("Could not fetch AdGuard Base: ${response.code}")
        }
    }

    private fun countTrieRules(): Int {
        val trie = getFieldAny("domainTrie")
        val root = getField(trie, "root")
        return countTrieNodeRules(root)
    }

    private fun countTrieNodeRules(node: Any): Int {
        val rulesField = node.javaClass.getDeclaredField("rules")
        rulesField.isAccessible = true
        val childrenField = node.javaClass.getDeclaredField("children")
        childrenField.isAccessible = true

        var count = (rulesField.get(node) as java.util.ArrayList<*>).size
        val children = childrenField.get(node) as java.util.concurrent.ConcurrentHashMap<*, *>
        for (child in children.values) {
            count += countTrieNodeRules(child)
        }
        return count
    }

    private fun getArrayListSize(fieldName: String): Int {
        val list = getFieldAny(fieldName) as java.util.ArrayList<*>
        return list.size
    }

    private fun getFieldAny(fieldName: String): Any {
        val field = FilterEngine::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(FilterEngine)
    }

    private fun getField(obj: Any, fieldName: String): Any {
        val field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj)
    }
}
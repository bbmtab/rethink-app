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
package com.celzero.bravedns.core.filter

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.util.Locale

/**
 * HtmlFilter handles HTML element removal rules using ##^ syntax.
 *
 * Supported filter list syntax (AdGuard/uBlock compatible):
 *   example.com##^script:has-text(adsbygoogle)       # Remove script elements containing text
 *   example.com##^div[id="ad-container"]             # Remove div by attribute
 *   ##^.advertisement                                # Global rule: remove elements by class
 *   example.com##^script[src*="analytics"]           # Remove script by src attribute
 *
 * The filter parses HTML with Jsoup, finds matching elements, and removes them.
 * This runs BEFORE CSS/Scriptlet injection in the proxy pipeline.
 *
 * Performance: Limited to 2MB HTML bodies to avoid memory issues on large pages.
 */
object HtmlFilter {

    private const val TAG = "HtmlFilter"
    private const val MAX_HTML_SIZE = 2 * 1024 * 1024  // 2MB limit

    // Logging abstractions (use println for JVM test compatibility)
    private fun logDebug(msg: String) = println("[$TAG] DEBUG: $msg")
    private fun logWarn(msg: String) = println("[$TAG] WARN: $msg")

    /**
     * A parsed HTML filter rule extracted from a filter list line.
     *
     * @param cssSelector     Jsoup CSS selector (e.g., "script:has-text(adsbygoogle)")
     * @param allowedDomains  If non-null, injection only applies to these domains
     * @param excludedDomains If non-null, injection skipped for these domains
     */
    data class HtmlFilterRule(
        val cssSelector: String,
        val allowedDomains: Set<String>?,
        val excludedDomains: Set<String>?
    )

    @Volatile
    private var isInitialized = false
    private val rules = ArrayList<HtmlFilterRule>()

    // LRU cache: domain -> list of matching Regeln (or null if no match)
    private val ruleCache = object : LinkedHashMap<String, List<HtmlFilterRule>?>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<HtmlFilterRule>?>?): Boolean = size > 200
    }

    @Synchronized
    fun clear() {
        rules.clear()
        synchronized(ruleCache) { ruleCache.clear() }
        isInitialized = false
    }

    fun onRulesReloaded() {
        clear()
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Applies HTML filtering rules to the HTML body for [domain].
     *
     * Returns the filtered HTML, or the original unmodified HTML if:
     * - No rules match the domain
     * - HTML body is too large (> 2MB)
     * - HTML parsing fails
     *
     * @param domain   Hostname of the page (e.g., "example.com")
     * @param htmlBody Raw HTML response body
     * @return Filtered HTML or original if no rules apply/error
     */
    fun applyFilters(domain: String, htmlBody: String): String {
        if (!FilterEngine.isLoaded) return htmlBody
        if (htmlBody.length > MAX_HTML_SIZE) {
            logDebug("Skipping HTML filter for $domain: body size ${htmlBody.length} > $MAX_HTML_SIZE")
            return htmlBody
        }

        val host = domain.trim().lowercase(Locale.US)
        val matchingRules = getMatchingRules(host)
        if (matchingRules.isEmpty()) return htmlBody

        return try {
            val doc = Jsoup.parse(htmlBody)
            for (rule in matchingRules) {
                try {
                    val elements = doc.select(rule.cssSelector)
                    if (elements.isNotEmpty()) {
                        elements.remove()
                        logDebug("Removed ${elements.size} element(s) for $host with selector: ${rule.cssSelector}")
                    }
                } catch (e: Exception) {
                    logWarn("Selector error for $host: ${rule.cssSelector} — ${e.message}")
                }
            }
            doc.outerHtml()
        } catch (e: Exception) {
            logWarn("HTML parse error for $host: ${e.message}")
            htmlBody
        }
    }

    /**
     * Checks if there are any HTML filter rules for the given domain (used for fast-path skipping).
     */
    fun hasRulesForDomain(domain: String): Boolean {
        if (!FilterEngine.isLoaded) return false
        val host = domain.trim().lowercase(Locale.US)
        val matching = getMatchingRules(host)
        return matching.isNotEmpty()
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun getMatchingRules(host: String): List<HtmlFilterRule> {
        synchronized(ruleCache) {
            ruleCache[host]?.let { return it ?: emptyList() }
        }

        ensureInitialized()

        val matching = rules.filter { rule ->
            domainMatches(host, rule.allowedDomains, rule.excludedDomains)
        }

        if (matching.isEmpty()) {
            synchronized(ruleCache) { ruleCache[host] = null }
            return emptyList()
        }

        synchronized(ruleCache) { ruleCache[host] = matching }
        return matching
    }

    private fun ensureInitialized() {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            rules.clear()
            val rawRules: List<String>
            synchronized(FilterEngine) {
                rawRules = FilterEngine.htmlFilterRules.toList()
            }
            for (raw in rawRules) {
                parseHtmlFilterRule(raw)?.let { rules.add(it) }
            }
            isInitialized = true
        }
    }

    /**
     * Parses a raw filter list line containing a ##^ HTML filter rule.
     *
     * Format: [domains]##^selector
     * e.g.:   example.com##^script:has-text(adsbygoogle)
     *         ##^div.advertisement
     *
     * @return Parsed HtmlFilterRule or null if invalid
     */
    internal fun parseHtmlFilterRule(rawLine: String): HtmlFilterRule? {
        val sepIdx = rawLine.indexOf("##^")
        if (sepIdx == -1) return null

        val domainPart = rawLine.substring(0, sepIdx).trim()
        val selectorPart = rawLine.substring(sepIdx + 3).trim() // skip "##^"

        if (selectorPart.isEmpty()) return null

        // Parse domain list (same logic as other filters)
        var allowedDomains: MutableSet<String>? = null
        var excludedDomains: MutableSet<String>? = null
        if (domainPart.isNotEmpty()) {
            for (dom in domainPart.split(",")) {
                val d = dom.trim().lowercase(Locale.US)
                if (d.isEmpty()) continue
                if (d.startsWith("~")) {
                    if (excludedDomains == null) excludedDomains = HashSet()
                    excludedDomains!!.add(d.substring(1))
                } else {
                    if (allowedDomains == null) allowedDomains = HashSet()
                    allowedDomains!!.add(d)
                }
            }
        }

        return HtmlFilterRule(selectorPart, allowedDomains, excludedDomains)
    }

    private fun domainMatches(host: String, allowed: Set<String>?, excluded: Set<String>?): Boolean {
        if (excluded != null && excluded.isNotEmpty()) {
            if (isDomainOrSuffixMatch(host, excluded)) return false
        }
        if (allowed != null && allowed.isNotEmpty()) {
            return isDomainOrSuffixMatch(host, allowed)
        }
        return true
    }

    private fun isDomainOrSuffixMatch(host: String, domains: Set<String>): Boolean {
        if (domains.contains(host)) return true
        var idx = host.indexOf('.')
        while (idx != -1) {
            if (domains.contains(host.substring(idx + 1))) return true
            idx = host.indexOf('.', idx + 1)
        }
        return false
    }
}
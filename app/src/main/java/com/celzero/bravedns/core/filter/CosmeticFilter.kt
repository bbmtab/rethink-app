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

import java.util.Locale

/**
 * CosmeticFilter parses, manages, and matches Cosmetic (CSS element hiding) rules
 * from FilterEngine's loaded blocklists, generating minimal element-hiding stylesheets
 * for any given domain.
 *
 * It supports standard AdGuard/EasyList syntax:
 * - `example.com##.ad-banner`      -> hide .ad-banner on example.com
 * - `##div[id^="google_ad"]`       -> global, hide on all domains
 * - `example.com#@#.ad-banner`     -> whitelist .ad-banner on example.com (exception)
 */
object CosmeticFilter {

    private const val TAG = "CosmeticFilter"

    data class ParsedCosmeticRule(
        val selector: String,
        val allowedDomains: Set<String>?,
        val excludedDomains: Set<String>?
    )

    @Volatile
    private var isInitialized = false
    private val parsedBlocks = ArrayList<ParsedCosmeticRule>()
    private val parsedExceptions = ArrayList<ParsedCosmeticRule>()

    // Thread-safe LRU cache for domain CSS to avoid re-evaluating rules (max 200 domains)
    private val cssCache = object : LinkedHashMap<String, String?>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String?>?): Boolean {
            return size > 200
        }
    }

    /**
     * Clears all parsed rules and resets initialization state.
     */
    @Synchronized
    fun clear() {
        parsedBlocks.clear()
        parsedExceptions.clear()
        synchronized(cssCache) {
            cssCache.clear()
        }
        isInitialized = false
    }

    /**
     * Lazily parses raw rules from FilterEngine when needed.
     */
    private fun ensureInitialized() {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return

            parsedBlocks.clear()
            parsedExceptions.clear()

            val rawBlocks = ArrayList<String>()
            val rawExceptions = ArrayList<String>()
            
            synchronized(FilterEngine) {
                rawBlocks.addAll(FilterEngine.cosmeticRules)
                rawExceptions.addAll(FilterEngine.cosmeticExceptions)
            }

            for (raw in rawBlocks) {
                parseCosmeticRule(raw, isException = false)?.let { parsedBlocks.add(it) }
            }

            for (raw in rawExceptions) {
                parseCosmeticRule(raw, isException = true)?.let { parsedExceptions.add(it) }
            }

            isInitialized = true
        }
    }

    /**
     * Parses a raw cosmetic rule line into ParsedCosmeticRule.
     */
    private fun parseCosmeticRule(rawLine: String, isException: Boolean): ParsedCosmeticRule? {
        val separator = if (isException) "#@#" else "##"
        val index = rawLine.indexOf(separator)
        if (index == -1) return null

        val domainPart = rawLine.substring(0, index).trim()
        val selector = rawLine.substring(index + separator.length).trim()
        if (selector.isEmpty()) return null

        var allowed: MutableSet<String>? = null
        var excluded: MutableSet<String>? = null

        if (domainPart.isNotEmpty()) {
            val domains = domainPart.split(",")
            for (dom in domains) {
                val trimmed = dom.trim().lowercase(Locale.US)
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("~")) {
                    if (excluded == null) excluded = HashSet()
                    excluded.add(trimmed.substring(1))
                } else {
                    if (allowed == null) allowed = HashSet()
                    allowed.add(trimmed)
                }
            }
        }

        return ParsedCosmeticRule(selector, allowed, excluded)
    }

    /**
     * Returns a CSS stylesheet string with all element-hiding rules for the given domain.
     * Returns null if no matching rules are found.
     *
     * @param domain The target domain (e.g. "example.com")
     * @return Minimal CSS stylesheet containing element-hiding styles
     */
    fun getCssForDomain(domain: String): String? {
        if (!FilterEngine.isLoaded) return null

        // Check cache
        synchronized(cssCache) {
            if (cssCache.containsKey(domain)) {
                return cssCache[domain]
            }
        }

        ensureInitialized()

        val host = domain.lowercase(Locale.US).trim()
        val matchingBlocks = ArrayList<String>()
        val matchingExceptions = HashSet<String>()

        // 1. Evaluate exceptions (whitelists)
        for (rule in parsedExceptions) {
            if (domainMatches(host, rule.allowedDomains, rule.excludedDomains)) {
                matchingExceptions.add(rule.selector)
            }
        }

        // 2. Evaluate blocks (blacklists)
        for (rule in parsedBlocks) {
            if (domainMatches(host, rule.allowedDomains, rule.excludedDomains)) {
                if (!matchingExceptions.contains(rule.selector)) {
                    matchingBlocks.add(rule.selector)
                }
            }
        }

        val result = if (matchingBlocks.isEmpty()) {
            null
        } else {
            matchingBlocks.joinToString(", ") + " { display: none !important; }"
        }

        synchronized(cssCache) {
            cssCache[domain] = result
        }

        return result
    }

    private fun domainMatches(host: String, allowed: Set<String>?, excluded: Set<String>?): Boolean {
        // Check exclusion list first
        if (excluded != null && excluded.isNotEmpty()) {
            if (isDomainOrSuffixMatch(host, excluded)) {
                return false
            }
        }

        // If there is an allowed list, host must match one of them
        if (allowed != null && allowed.isNotEmpty()) {
            return isDomainOrSuffixMatch(host, allowed)
        }

        // If no allowed list, and not excluded, it matches (global)
        return true
    }

    private fun isDomainOrSuffixMatch(host: String, domains: Set<String>): Boolean {
        if (domains.contains(host)) return true
        var index = host.indexOf('.')
        while (index != -1) {
            val suffix = host.substring(index + 1)
            if (domains.contains(suffix)) return true
            index = host.indexOf('.', index + 1)
        }
        return false
    }

    /**
     * Should be called when FilterEngine reloads rules to clear cached styles.
     */
    fun onRulesReloaded() {
        clear()
    }
}

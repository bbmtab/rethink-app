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
 * CspInjector manages Content-Security-Policy rules parsed from filter lists
 * and injects or merges CSP headers into HTTP responses passing through the
 * MITM proxy.
 *
 * Supported filter list syntax (AdGuard-compatible $csp modifier):
 *   ||example.com^$csp=script-src 'self' 'unsafe-inline'
 *   $csp=connect-src 'self',third-party
 *
 * Rules with no domain part apply globally to all domains.
 */
object CspInjector {

    private const val TAG = "CspInjector"

    /**
     * A parsed CSP rule extracted from a filter list entry.
     *
     * @param cspDirective  The raw CSP directive value (e.g. "script-src 'self'")
     * @param allowedDomains  If non-null, rule applies only to these domains
     * @param excludedDomains If non-null, rule does NOT apply to these domains
     */
    data class CspRule(
        val cspDirective: String,
        val allowedDomains: Set<String>?,
        val excludedDomains: Set<String>?
    )

    @Volatile
    private var isInitialized = false
    private val rules = ArrayList<CspRule>()

    // LRU cache: domain → merged CSP string (or null if no rules)
    private val cache = object : LinkedHashMap<String, String?>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String?>?): Boolean = size > 200
    }

    /**
     * Clears all loaded rules and resets state.
     * Call this when FilterEngine reloads its rule set.
     */
    @Synchronized
    fun clear() {
        rules.clear()
        synchronized(cache) { cache.clear() }
        isInitialized = false
    }

    /**
     * Should be called after FilterEngine finishes loading rules so that
     * CspInjector re-parses fresh CSP rules on next access.
     */
    fun onRulesReloaded() {
        clear()
    }

    /**
     * Lazily initialises by pulling raw CSP rule strings from FilterEngine.
     */
    private fun ensureInitialized() {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            rules.clear()
            val rawRules: List<String>
            synchronized(FilterEngine) {
                rawRules = FilterEngine.cspRules.toList()
            }
            for (raw in rawRules) {
                parseCspRule(raw)?.let { rules.add(it) }
            }
            isInitialized = true
        }
    }

    /**
     * Returns the merged CSP directive string to inject for [domain],
     * or null if no CSP rules match.
     *
     * Multiple matching rules are merged by concatenating their directives
     * separated by "; ".
     */
    fun getCspForDomain(domain: String): String? {
        if (!FilterEngine.isLoaded) return null

        val host = domain.trim().lowercase(Locale.US)
        synchronized(cache) {
            if (cache.containsKey(host)) return cache[host]
        }

        ensureInitialized()

        val matching = rules.filter { rule ->
            domainMatches(host, rule.allowedDomains, rule.excludedDomains)
        }.map { it.cspDirective }

        val result = if (matching.isEmpty()) null else matching.joinToString("; ")

        synchronized(cache) { cache[host] = result }
        return result
    }

    /**
     * Merges [addition] into an existing [existingHeader] CSP header value.
     *
     * Strategy: for each directive in [addition], if the same directive type
     * already exists in [existingHeader] it is left untouched (existing policy
     * wins to avoid breaking the site); otherwise the directive is appended.
     *
     * @param existingHeader  Full header line including "Content-Security-Policy: " prefix,
     *                        or just the value part — both are handled.
     * @param addition        The CSP directive string to merge in.
     * @return                The merged CSP header value (without header name prefix).
     */
    fun mergeWithExistingCsp(existingHeader: String, addition: String): String {
        // Strip header name prefix if present
        val existing = if (existingHeader.lowercase(Locale.US).startsWith("content-security-policy:")) {
            existingHeader.substringAfter(":").trim()
        } else {
            existingHeader.trim()
        }

        // Parse existing directives into a map keyed by directive name
        val existingMap = LinkedHashMap<String, String>()
        for (directive in existing.split(";")) {
            val trimmed = directive.trim()
            if (trimmed.isEmpty()) continue
            val name = trimmed.substringBefore(" ").trim().lowercase(Locale.US)
            existingMap[name] = trimmed
        }

        // Append new directives that don't already exist
        for (directive in addition.split(";")) {
            val trimmed = directive.trim()
            if (trimmed.isEmpty()) continue
            val name = trimmed.substringBefore(" ").trim().lowercase(Locale.US)
            if (!existingMap.containsKey(name)) {
                existingMap[name] = trimmed
            }
        }

        return existingMap.values.joinToString("; ")
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Parses a raw CSP rule string from FilterEngine.cspRules into a [CspRule].
     *
     * Expected format (stored by FilterEngine after stripping the pattern part):
     *   "csp=<directive>|domain=example.com|~excluded.com"
     * or simply:
     *   "csp=<directive>"
     */
    private fun parseCspRule(raw: String): CspRule? {
        // raw is the full original filter rule line, e.g.:
        //   ||example.com^$csp=script-src 'self'
        //   $csp=connect-src 'self',third-party
        val dollarIdx = raw.lastIndexOf('$')
        if (dollarIdx == -1) return null
        val modifierPart = raw.substring(dollarIdx + 1)

        var cspDirective: String? = null
        var allowedDomains: MutableSet<String>? = null
        var excludedDomains: MutableSet<String>? = null

        // modifierPart may contain multiple comma-separated modifiers.
        // CSP values themselves can contain spaces but NOT commas, so splitting on comma is safe
        // for the modifier list level.  The domain= modifier uses | as separator.
        for (mod in modifierPart.split(",")) {
            val trimmed = mod.trim()
            when {
                trimmed.lowercase(Locale.US).startsWith("csp=") -> {
                    cspDirective = trimmed.substring(4).trim()
                }
                trimmed.lowercase(Locale.US).startsWith("domain=") -> {
                    val domains = trimmed.substring(7).split("|")
                    for (dom in domains) {
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
                // third-party, important, etc. — ignored for CSP rules
            }
        }

        if (cspDirective.isNullOrEmpty()) return null
        return CspRule(cspDirective, allowedDomains, excludedDomains)
    }

    private fun domainMatches(
        host: String,
        allowed: Set<String>?,
        excluded: Set<String>?
    ): Boolean {
        if (excluded != null && excluded.isNotEmpty()) {
            if (isDomainOrSuffixMatch(host, excluded)) return false
        }
        if (allowed != null && allowed.isNotEmpty()) {
            return isDomainOrSuffixMatch(host, allowed)
        }
        return true // global rule
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

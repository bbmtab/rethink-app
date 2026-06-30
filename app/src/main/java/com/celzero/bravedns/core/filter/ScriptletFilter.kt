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

import android.content.Context
import java.util.Locale

/**
 * ScriptletFilter manages scriptlet injection rules parsed from filter lists.
 *
 * Scriptlets are small JavaScript functions that intercept or override browser APIs
 * to bypass anti-adblock detection, cancel tracking calls, or modify page behaviour.
 *
 * Supported filter list syntax (AdGuard-compatible #%# syntax):
 *   example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle')
 *   example.com#%#//scriptlet('set-constant', 'canRunAds', 'true')
 *   #%#//scriptlet('no-fetch-if', 'analytics')
 *
 * The scriptlet library itself lives in assets/scriptlets.js and is loaded once
 * into memory then prepended to every scriptlet injection call.
 */
object ScriptletFilter {

    private const val TAG = "ScriptletFilter"
    private const val SCRIPTLETS_ASSET = "scriptlets.js"

    /**
     * A parsed scriptlet invocation extracted from a filter list rule.
     *
     * @param name            Scriptlet function name, e.g. "abort-on-property-read"
     * @param args            Ordered argument list, may be empty
     * @param allowedDomains  If non-null, injection only applies to these domains
     * @param excludedDomains If non-null, injection skipped for these domains
     */
    data class ScriptletRule(
        val name: String,
        val args: List<String>,
        val allowedDomains: Set<String>?,
        val excludedDomains: Set<String>?
    )

    @Volatile
    private var isInitialized = false
    private val rules = ArrayList<ScriptletRule>()

    // Scriptlet library JS loaded from assets — loaded once, cached in memory
    @Volatile
    private var scriptletLibrary: String? = null

    // LRU cache: domain → complete injection snippet (or null)
    private val jsCache = object : LinkedHashMap<String, String?>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String?>?): Boolean = size > 200
    }

    @Synchronized
    fun clear() {
        rules.clear()
        synchronized(jsCache) { jsCache.clear() }
        isInitialized = false
        // NOTE: scriptletLibrary is intentionally NOT cleared — it's read-only asset data
    }

    fun onRulesReloaded() {
        clear()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the complete JS injection snippet for [domain], or null if no rules match.
     *
     * The snippet consists of:
     *   1. The scriptlet library (rethinkScriptlets namespace)
     *   2. Individual invocation calls for each matching rule
     *
     * Must be injected into HTML BEFORE any other page scripts.
     *
     * @param domain  Hostname of the page, e.g. "example.com"
     * @param context Android Context for loading assets (can be null in unit tests)
     */
    fun getScriptletCodeForDomain(domain: String, context: Context?): String? {
        if (!FilterEngine.isLoaded) return null

        val host = domain.trim().lowercase(Locale.US)
        synchronized(jsCache) {
            if (jsCache.containsKey(host)) return jsCache[host]
        }

        ensureInitialized()

        val matching = rules.filter { rule ->
            domainMatches(host, rule.allowedDomains, rule.excludedDomains)
        }

        if (matching.isEmpty()) {
            synchronized(jsCache) { jsCache[host] = null }
            return null
        }

        val library = loadLibrary(context)
        val invocations = matching.joinToString("\n") { rule -> buildInvocation(rule) }

        val result = """
(function() {
  'use strict';
  try {
$library
  } catch(e) {}
$invocations
})();
""".trimIndent()

        synchronized(jsCache) { jsCache[host] = result }
        return result
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun ensureInitialized() {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            rules.clear()
            val rawRules: List<String>
            synchronized(FilterEngine) {
                rawRules = FilterEngine.scriptletRules.toList()
            }
            for (raw in rawRules) {
                parseScriptletRule(raw)?.let { rules.add(it) }
            }
            isInitialized = true
        }
    }

    /**
     * Loads scriptlets.js from assets. In unit tests where context is null,
     * returns an empty string (scriptlet calls still execute but namespace is absent —
     * wrapped in try/catch so no crash).
     */
    private fun loadLibrary(context: Context?): String {
        scriptletLibrary?.let { return it }
        synchronized(this) {
            scriptletLibrary?.let { return it }
            val lib = try {
                context?.assets?.open(SCRIPTLETS_ASSET)?.bufferedReader()?.readText() ?: ""
            } catch (e: Exception) {
                ""
            }
            scriptletLibrary = lib
            return lib
        }
    }

    /**
     * Parses a raw filter list line containing a #%#//scriptlet() call.
     *
     * Format: [domains]#%#//scriptlet('name', 'arg1', 'arg2')
     * e.g.:   example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle')
     *         #%#//scriptlet('set-constant', 'canRunAds', 'true')
     */
    internal fun parseScriptletRule(rawLine: String): ScriptletRule? {
        val sepIdx = rawLine.indexOf("#%#//scriptlet(")
        if (sepIdx == -1) return null

        val domainPart = rawLine.substring(0, sepIdx).trim()
        val scriptletPart = rawLine.substring(sepIdx + "#%#//scriptlet(".length)

        // Strip trailing ')' — handle both ')' and ');'
        val closingIdx = scriptletPart.lastIndexOf(')')
        if (closingIdx == -1) return null
        val argsRaw = scriptletPart.substring(0, closingIdx).trim()

        // Parse arguments — split on ', ' but respect quoted strings
        val parsedArgs = parseScriptletArgs(argsRaw)
        if (parsedArgs.isEmpty()) return null

        val name = parsedArgs[0]
        val args = parsedArgs.drop(1)

        if (name.isEmpty()) return null

        // Parse domain list
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

        return ScriptletRule(name, args, allowedDomains, excludedDomains)
    }

    /**
     * Parses scriptlet argument list, respecting single-quoted strings.
     * Input: `'abort-on-property-read', 'adsbygoogle'`
     * Output: `["abort-on-property-read", "adsbygoogle"]`
     */
    internal fun parseScriptletArgs(raw: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < raw.length) {
            // Skip whitespace and commas between args
            while (i < raw.length && (raw[i] == ' ' || raw[i] == ',')) i++
            if (i >= raw.length) break

            if (raw[i] == '\'') {
                // Quoted argument
                i++ // skip opening quote
                val sb = StringBuilder()
                while (i < raw.length && raw[i] != '\'') {
                    if (raw[i] == '\\' && i + 1 < raw.length) {
                        i++ // skip backslash, take next char literally
                    }
                    sb.append(raw[i])
                    i++
                }
                if (i < raw.length) i++ // skip closing quote
                result.add(sb.toString())
            } else {
                // Unquoted argument — read until comma or end
                val sb = StringBuilder()
                while (i < raw.length && raw[i] != ',') {
                    sb.append(raw[i])
                    i++
                }
                val token = sb.toString().trim()
                if (token.isNotEmpty()) result.add(token)
            }
        }
        return result
    }

    /**
     * Builds a single scriptlet invocation call.
     *
     * Output example:
     *   try { window.rethinkScriptlets['abort-on-property-read']('adsbygoogle'); } catch(e) {}
     */
    internal fun buildInvocation(rule: ScriptletRule): String {
        val escapedName = rule.name.replace("'", "\\'")
        val argsStr = rule.args.joinToString(", ") { arg ->
            "'" + arg.replace("\\", "\\\\").replace("'", "\\'") + "'"
        }
        val callArgs = if (argsStr.isEmpty()) "" else argsStr
        return "try { window.rethinkScriptlets['$escapedName']($callArgs); } catch(e) {}"
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

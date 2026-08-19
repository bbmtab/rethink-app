package com.celzero.bravedns.core.filter

import java.io.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * High-performance, memory-efficient Adblock/EasyList content filtering engine.
 * Specifically optimized for Android with O(1) reversed-domain Trie matching,
 * disk-based rule pre-parsed serialization, and <1ms evaluation times.
 */
object FilterEngine {

    private const val TAG = "FilterEngine"
    private const val CACHE_FILE_NAME = "filter_rules_cache.bin"
    private const val CACHE_VERSION = 5  // bumped: added htmlFilterRules to cache layout

    // Resource type bitmask constants
    object ResourceType {
        const val NONE = 0
        const val DOCUMENT = 1 shl 0
        const val STYLESHEET = 1 shl 1
        const val SCRIPT = 1 shl 2
        const val IMAGE = 1 shl 3
        const val FONT = 1 shl 4
        const val SUBDOCUMENT = 1 shl 5
        const val XMLHTTPREQUEST = 1 shl 6
        const val MEDIA = 1 shl 7
        const val OTHER = 1 shl 8
        const val ALL = 0xFFFF
    }

    // Match output status
    sealed class MatchResult {
        object Allow : MatchResult()
        data class Block(val ruleText: String) : MatchResult()
    }

    /**
     * Internal representation of a parsed filter rule.
     */
    data class AdblockRule(
        val rawText: String,
        val isWhitelist: Boolean,
        val pattern: String,
        val isRegex: Boolean,
        val isDomainExact: Boolean,
        val targetDomain: String?,
        val isThirdPartyOnly: Boolean,
        val isNotThirdPartyOnly: Boolean,
        val allowedDomains: Set<String>?,
        val excludedDomains: Set<String>?,
        val allowedTypes: Int,
        val isImportant: Boolean,
        val isCosmetic: Boolean,
        val isCsp: Boolean = false,
        val isProcedural: Boolean = false,
        val isScriptlet: Boolean = false,
        val isHtmlFilter: Boolean = false
    ) : Serializable {
        @Transient
        @Volatile
        private var compiledRegex: Regex? = null

        fun getRegex(): Regex {
            var r = compiledRegex
            if (r == null) {
                synchronized(this) {
                    r = compiledRegex
                    if (r == null) {
                        r = if (isRegex) {
                            Regex(pattern, RegexOption.IGNORE_CASE)
                        } else {
                            convertWildcardToRegex(pattern)
                        }
                        compiledRegex = r
                    }
                }
            }
            return r!!
        }
    }

    // Trie node for domain suffix indexing
    internal class DomainTrieNode {
        val children = ConcurrentHashMap<String, DomainTrieNode>()
        val rules = ArrayList<AdblockRule>()
    }

    // Trie structure for domain-anchored rules
    internal class DomainTrie {
        val root = DomainTrieNode()

        fun insert(domain: String, rule: AdblockRule) {
            val parts = domain.lowercase(Locale.US).split(".").reversed()
            var current = root
            for (part in parts) {
                current = current.children.getOrPut(part) { DomainTrieNode() }
            }
            current.rules.add(rule)
        }

        fun getRulesForHost(host: String): List<AdblockRule> {
            val parts = host.lowercase(Locale.US).split(".").reversed()
            val matchedRules = ArrayList<AdblockRule>()
            var current = root
            
            // Collect global domain rules registered at root if any
            if (current.rules.isNotEmpty()) {
                matchedRules.addAll(current.rules)
            }
            
            for (part in parts) {
                current = current.children[part] ?: break
                if (current.rules.isNotEmpty()) {
                    matchedRules.addAll(current.rules)
                }
            }
            return matchedRules
        }

        fun clear() {
            root.children.clear()
            root.rules.clear()
        }
    }

    /**
     * Immutable snapshot of the complete engine runtime state (all 8 rule indices + network count +
     * loaded flag).
     *
     * A load operation constructs a COMPLETE candidate [EngineState] into local [RuleBuilder]
     * structures first, validates full success, and only then publishes it by a single assignment
     * to [activeState] (one atomic @Volatile reference swap). On any failure the candidate is
     * discarded and [activeState] is left untouched, so the previously published snapshot keeps
     * serving readers — failed new load => previous active runtime state stays byte/semantically
     * equivalent (transactional runtime activation, R3B). Readers (match/getRuleStats/injectors)
     * capture [activeState] once per operation and observe a self-consistent generation; an older
     * generation may stay alive while in-flight readers finish and is GC'd afterwards.
     */
    internal data class EngineState(
        val domainTrie: DomainTrie,
        val genericRules: List<AdblockRule>,
        val cosmeticRules: List<String>,
        val cosmeticExceptions: List<String>,
        val cspRules: List<String>,
        val proceduralRules: List<String>,
        val scriptletRules: List<String>,
        val htmlFilterRules: List<String>,
        val networkRuleCount: Int,
        val isLoaded: Boolean
    ) {
        companion object {
            val EMPTY = EngineState(
                domainTrie = DomainTrie(),
                genericRules = emptyList(),
                cosmeticRules = emptyList(),
                cosmeticExceptions = emptyList(),
                cspRules = emptyList(),
                proceduralRules = emptyList(),
                scriptletRules = emptyList(),
                htmlFilterRules = emptyList(),
                networkRuleCount = 0,
                isLoaded = false
            )
        }
    }

    @Volatile
    private var activeState: EngineState = EngineState.EMPTY

    /**
     * Local accumulator for a candidate [EngineState]. Owned by a single load operation (mutated
     * under the outer @Synchronized load monitor) and never observed by readers until [build]
     * publishes it via [activeState]. Plain holder — routing logic stays in [processRuleLine].
     *
     * Visibility is `internal` so R3B partial-candidate-failure tests in the same package can
     * directly observe the half-populated state after a parse-induced exception. This is a
     * minimal surface change (no new public API); production paths still funnel through the
     * standard load functions.
     */
    internal class RuleBuilder {
        val domainTrie = DomainTrie()
        val genericRules = ArrayList<AdblockRule>()
        val cosmeticRules = ArrayList<String>()
        val cosmeticExceptions = ArrayList<String>()
        val cspRules = ArrayList<String>()
        val proceduralRules = ArrayList<String>()
        val scriptletRules = ArrayList<String>()
        val htmlFilterRules = ArrayList<String>()
        var networkRuleCount = 0

        /**
         * Routes a single parsed rule into this candidate's indices. No synchronization needed —
         * the builder is local to a single load operation that runs under the outer @Synchronized
         * load monitor, and the builder is unpublished while being mutated, so no reader can ever
         * observe a half-populated state.
         */
        fun processRuleLine(rawLine: String) {
            val rule = FilterEngine.parseRule(rawLine) ?: return
            when {
                rule.isScriptlet -> scriptletRules.add(rule.rawText)
                rule.isProcedural -> proceduralRules.add(rule.rawText)
                rule.isCosmetic -> {
                    if (rule.isWhitelist) {
                        cosmeticExceptions.add(rule.rawText)
                    } else {
                        cosmeticRules.add(rule.rawText)
                    }
                }
                rule.isCsp -> cspRules.add(rule.rawText)
                rule.isHtmlFilter -> htmlFilterRules.add(rule.rawText)
                else -> {
                    networkRuleCount++
                    if (rule.isDomainExact && rule.targetDomain != null) {
                        domainTrie.insert(rule.targetDomain, rule)
                    } else {
                        genericRules.add(rule)
                    }
                }
            }
        }

        fun build(isLoaded: Boolean): EngineState = EngineState(
            domainTrie = domainTrie,
            genericRules = genericRules,
            cosmeticRules = cosmeticRules,
            cosmeticExceptions = cosmeticExceptions,
            cspRules = cspRules,
            proceduralRules = proceduralRules,
            scriptletRules = scriptletRules,
            htmlFilterRules = htmlFilterRules,
            networkRuleCount = networkRuleCount,
            isLoaded = isLoaded
        )

        /**
         * Total number of [AdblockRule]s held across every node of [domainTrie]. Test-only
         * helper used by the R3B partial-candidate-failure test to verify that the per-line
         * accumulation actually populated the trie before the parse loop's IOException.
         * Internal visibility so same-package tests can call it without exposing [DomainTrie]
         * externally.
         */
        internal fun countDomainRules(): Int = countTrieRules(domainTrie.root)

        private fun countTrieRules(node: DomainTrieNode): Int {
            var n = node.rules.size
            for (child in node.children.values) {
                n += countTrieRules(child)
            }
            return n
        }
    }

    /**
     * Test seam: parse rules from an arbitrary [BufferedReader] into a candidate [RuleBuilder]
     * WITHOUT touching the live snapshot. Mirrors the per-line parse loop used by [loadRules]
     * and the raw-parse path in [loadRulesFromFile] so partial-failure tests observe the exact
     * accumulation order the production path takes.
     *
     * On any `IOException` (e.g., a [BufferedReader] that throws partway through, simulating a
     * truncated file read or stream cut) the half-built [RuleBuilder] is left for inspection
     * by the test; the caller decides whether to publish via `builder.build(...)` to
     * [activeState] or discard. In a real production call site the build inside [loadRules] /
     * [loadRulesFromFile] happens only after this loop returns without throwing, so any thrown
     * exception here leaves the live snapshot untouched (R3B invariant).
     *
     * Internal-only; not part of the production API.
     */
    internal fun parseRulesIntoBuilder(reader: BufferedReader, builder: RuleBuilder) {
        var line: String? = reader.readLine()
        while (line != null) {
            builder.processRuleLine(line)
            line = reader.readLine()
        }
    }

    /**
     * Test seam: load rules from an arbitrary [Reader] using the publication-gate semantics of
     * [loadRules]. On any `IOException` during the parse loop the exception propagates out and
     * the live [activeState] is left untouched (R3B invariant).
     *
     * @Synchronized — the publication-gate mirrors [loadRules]; the activeState swap is the
     * ONLY publication point. If we never reach the swap, the prior snapshot keeps serving.
     */
    @Synchronized
    internal fun loadRulesFromReader(reader: Reader) {
        val builder = RuleBuilder()
        val br = if (reader is BufferedReader) reader else BufferedReader(reader)
        parseRulesIntoBuilder(br, builder)
        activeState = builder.build(isLoaded = true)
    }

    // Public read-only views over the live snapshot. Backed by [activeState], swapped atomically on
    // load/clear. Concurrent readers (CspInjector/CosmeticFilter/ProceduralFilter/ScriptletFilter/
    // HtmlFilter) observe a self-consistent generation and never see a half-populated list.
    val cosmeticRules: List<String> get() = activeState.cosmeticRules
    val cosmeticExceptions: List<String> get() = activeState.cosmeticExceptions
    val cspRules: List<String> get() = activeState.cspRules
    val proceduralRules: List<String> get() = activeState.proceduralRules
    val scriptletRules: List<String> get() = activeState.scriptletRules
    val htmlFilterRules: List<String> get() = activeState.htmlFilterRules

    // Diagnostic flag set true only while a load operation is in progress. match() does NOT
    // consult this flag — readers continue to evaluate against the previously published snapshot
    // (activeState, captured once per match call) for the entire duration of any candidate
    // rebuild. Only an atomic publication at the end of a successful candidate load makes new
    // rules visible. Exposed only for diagnostic / instrumentation consumers; it is NOT a
    // fail-open switch.
    @Volatile
    var isReloading: Boolean = false

    /**
     * Aggregated rule statistics for UI feedback.
     */
    data class RuleStats(
        val network: Int,
        val cosmetic: Int,
        val cosmeticExceptions: Int,
        val csp: Int,
        val procedural: Int,
        val scriptlet: Int,
        val htmlFilter: Int
    ) {
        val total: Int get() = network + cosmetic + cosmeticExceptions + csp + procedural + scriptlet + htmlFilter

        override fun toString(): String =
            "Rules: $total | Network: $network | Cosmetic: $cosmetic | Exceptions: $cosmeticExceptions | CSP: $csp | Procedural: $procedural | Scriptlet: $scriptlet | HTML Filter: $htmlFilter"
    }

    // Backed by the live snapshot's publication flag — reflects the currently active generation
    // and never a torn mid-reload state.
    val isLoaded: Boolean get() = activeState.isLoaded

    /**
     * Clears all loaded rules from memory.
     *
     * Publishes the empty snapshot in a single atomic assignment; the previously published
     * snapshot simply becomes unreferenced and is GC'd once in-flight readers finish.
     */
    @Synchronized
    fun clear() {
        activeState = EngineState.EMPTY
    }

    /**
     * Returns aggregated rule statistics for UI feedback.
     *
     * Samples the live snapshot once so the returned [RuleStats] is internally consistent (all
     * counts from the same published generation).
     */
    fun getRuleStats(): RuleStats {
        val state = activeState
        return RuleStats(
            network = state.networkRuleCount,
            cosmetic = state.cosmeticRules.size,
            cosmeticExceptions = state.cosmeticExceptions.size,
            csp = state.cspRules.size,
            procedural = state.proceduralRules.size,
            scriptlet = state.scriptletRules.size,
            htmlFilter = state.htmlFilterRules.size
        )
    }

    /**
     * Core matching engine. Evaluates single requests in <1ms.
     */
    fun match(
        url: String,
        host: String,
        isThirdParty: Boolean,
        resourceType: Int,
        refererHost: String? = null
    ): MatchResult {
        // Capture the live snapshot ONCE and use it for the whole match operation, so a concurrent
        // swap can never expose a mixed-generation state to a single evaluation.
        //
        // NOTE: NO isReloading-guard here. The supervisor contract requires that match() continue
        // using the captured activeState during a candidate rebuild — the previous generation
        // remains readable throughout the rebuild, and only an atomic publication at the end of a
        // successful candidate load makes new rules visible. isReloading is a diagnostics-only
        // flag; it MUST NOT disable filtering (no fail-open window).
        val state = activeState
        if (!state.isLoaded) return MatchResult.Allow

        // 1. Get all candidate rules
        val candidates = ArrayList<AdblockRule>()

        // Find domain-anchored candidates (very fast O(1) path)
        candidates.addAll(state.domainTrie.getRulesForHost(host))

        // Find generic candidates (guard kept: a published snapshot's list is never mutated
        // post-swap, so the synchronized is no longer strictly required, but it preserves the
        // original guarding pattern and the snapshot list's monitor is its own instance).
        synchronized(state.genericRules) {
            candidates.addAll(state.genericRules)
        }

        // 2. Evaluate candidates and split into Whitelist vs Block buckets
        val matchingWhitelists = ArrayList<AdblockRule>()
        val matchingBlocks = ArrayList<AdblockRule>()

        val refDomain = refererHost?.let { getRegistrableDomain(it) }

        for (rule in candidates) {
            // A. Check third-party modifiers
            if (rule.isThirdPartyOnly && !isThirdParty) continue
            if (rule.isNotThirdPartyOnly && isThirdParty) continue

            // B. Check domain modifiers ($domain=)
            if (refDomain != null) {
                if (rule.allowedDomains != null && !rule.allowedDomains.contains(refDomain)) continue
                if (rule.excludedDomains != null && rule.excludedDomains.contains(refDomain)) continue
            } else if (rule.allowedDomains != null) {
                // Referral restricted but we have no referer
                continue
            }

            // C. Check resource type
            if ((rule.allowedTypes and resourceType) == 0) continue

            // D. Check pattern matching
            val isMatch = if (rule.isDomainExact) {
                // If it is domain anchored, check if URL matches regex pattern
                rule.getRegex().containsMatchIn(url)
            } else {
                rule.getRegex().containsMatchIn(url)
            }

            if (isMatch) {
                if (rule.isWhitelist) {
                    matchingWhitelists.add(rule)
                } else {
                    matchingBlocks.add(rule)
                }
            }
        }

        if (matchingBlocks.isEmpty()) {
            return MatchResult.Allow
        }

        // 3. Resolve priorities
        // Priority order:
        // 1. Whitelists with $important
        // 2. Blocks with $important
        // 3. Whitelists (normal)
        // 4. Blocks (normal)

        val hasImportantWhitelist = matchingWhitelists.any { it.isImportant }
        if (hasImportantWhitelist) {
            return MatchResult.Allow
        }

        val importantBlock = matchingBlocks.find { it.isImportant }
        if (importantBlock != null) {
            return MatchResult.Block(importantBlock.rawText)
        }

        if (matchingWhitelists.isNotEmpty()) {
            return MatchResult.Allow
        }

        return MatchResult.Block(matchingBlocks.first().rawText)
    }

    /**
     * Parses raw filter list files line-by-line.
     * Separates cosmetic rules and indexes network rules.
     *
     * Transactional: parsing writes into a local [RuleBuilder]; the snapshot is published only
     * after the full parse succeeds. On any exception the previous active snapshot is preserved.
     *
     * Routes through the shared [loadRulesFromReader] entry so the per-line parse loop and the
     * publication gate live in one place.
     */
    @Synchronized
    fun loadRules(rulesText: String) {
        loadRulesFromReader(StringReader(rulesText))
    }

    /**
     * Loads rules from file on disk. Attempts to load pre-parsed cache first.
     * If no cache exists, parses raw and writes parsed cache to disk.
     */
    /**
     * Loads rules from file on disk. Attempts to load pre-parsed cache first.
     * If no cache exists, parses raw and writes parsed cache to disk.
     *
     * Transactional: both the cache rebuild and the raw parse construct a complete candidate
     * [EngineState] into a local [RuleBuilder] and publish it only on full success. On any failure
     * (cache deserialize error, raw parse IOException, ...) the previous active snapshot is
     * preserved (R3B). match() see [isReloading] for the fail-open window semantics preserved from
     * the legacy production load path.
     */
    @Synchronized
    fun loadRulesFromFile(rawFile: File, cacheDir: File) {
        isReloading = true
        try {
            val cacheFile = File(cacheDir, CACHE_FILE_NAME)
            if (cacheFile.exists() && cacheFile.lastModified() >= rawFile.lastModified()) {
                try {
                    val cached = loadFromCache(cacheFile)
                    if (cached != null) {
                        // Cache hit: publish the rebuilt snapshot. cached == null on any rebuild
                        // failure -> fall through to raw parse. Live snapshot is never touched by
                        // the rebuild path itself.
                        activeState = cached
                        logInfo("Successfully loaded pre-parsed filter list from disk cache.")
                        return
                    }
                } catch (e: Exception) {
                    logError("Failed to load rules cache: ${e.message}. Re-parsing raw file...", e)
                }
            }

            // Parse raw EasyList file into a candidate snapshot; publish only on full success.
            logInfo("Parsing raw filter list file (${rawFile.length() / 1024} KB)...")
            val startTime = System.currentTimeMillis()
            val builder = RuleBuilder()

            rawFile.bufferedReader(Charsets.UTF_8).use { br ->
                var line: String? = br.readLine()
                while (line != null) {
                    builder.processRuleLine(line)
                    line = br.readLine()
                }
            }

            activeState = builder.build(isLoaded = true)
            val duration = System.currentTimeMillis() - startTime
            logInfo("Parsed filter list in ${duration}ms. Saving pre-parsed cache...")

            // Save pre-parsed cache (best-effort; reflects the now-active snapshot).
            try {
                saveToCache(cacheFile)
            } catch (e: Exception) {
                logError("Failed to write rules cache: ${e.message}")
            }
        } finally {
            isReloading = false
        }
    }

    /**
     * Parses a single rule line into AdblockRule data structure.
     */
    fun parseRule(rawLine: String): AdblockRule? {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) {
            return null
        }

        // Detect Scriptlet rules (#%#//scriptlet) — must check before ## to avoid false match
        val isScriptlet = line.contains("#%#//scriptlet(")
        if (isScriptlet) {
            return AdblockRule(
                rawText = line,
                isWhitelist = false,
                pattern = "",
                isRegex = false,
                isDomainExact = false,
                targetDomain = null,
                isThirdPartyOnly = false,
                isNotThirdPartyOnly = false,
                allowedDomains = null,
                excludedDomains = null,
                allowedTypes = ResourceType.ALL,
                isImportant = false,
                isCosmetic = false,
                isCsp = false,
                isProcedural = false,
                isScriptlet = true
            )
        }

        // Detect Procedural cosmetic rules (#?#) — must check before ## to avoid false match
        val isProcedural = line.contains("#?#")
        if (isProcedural) {
            return AdblockRule(
                rawText = line,
                isWhitelist = false,
                pattern = "",
                isRegex = false,
                isDomainExact = false,
                targetDomain = null,
                isThirdPartyOnly = false,
                isNotThirdPartyOnly = false,
                allowedDomains = null,
                excludedDomains = null,
                allowedTypes = ResourceType.ALL,
                isImportant = false,
                isCosmetic = false,
                isCsp = false,
                isProcedural = true
            )
        }

        // Detect HTML filtering rules (##^) — must check before ## to avoid false match
        val isHtmlFilter = line.contains("##^")
        if (isHtmlFilter) {
            return AdblockRule(
                rawText = line,
                isWhitelist = false,
                pattern = "",
                isRegex = false,
                isDomainExact = false,
                targetDomain = null,
                isThirdPartyOnly = false,
                isNotThirdPartyOnly = false,
                allowedDomains = null,
                excludedDomains = null,
                allowedTypes = ResourceType.ALL,
                isImportant = false,
                isCosmetic = false,
                isCsp = false,
                isProcedural = false,
                isScriptlet = false,
                isHtmlFilter = true
            )
        }

        // Detect Cosmetic rules (contain ##, #@#, #%#) — #?# and ##^ already handled above
        val isCosmetic = line.contains("##") || line.contains("#@#") || line.contains("#%#")
        if (isCosmetic) {
            return AdblockRule(
                rawText = line,
                isWhitelist = line.contains("#@#"),
                pattern = "",
                isRegex = false,
                isDomainExact = false,
                targetDomain = null,
                isThirdPartyOnly = false,
                isNotThirdPartyOnly = false,
                allowedDomains = null,
                excludedDomains = null,
                allowedTypes = ResourceType.ALL,
                isImportant = false,
                isCosmetic = true
            )
        }

        // Network rules
        var text = line
        var isWhitelist = false
        if (text.startsWith("@@")) {
            isWhitelist = true
            text = text.substring(2)
        }

        // Split modifiers at trailing $
        var patternPart = text
        var modifierPart: String? = null
        val dollarIdx = text.lastIndexOf('$')
        if (dollarIdx != -1) {
            val afterDollar = text.substring(dollarIdx + 1)
            // If modifier part does not contain slashes, it's not a regex termination anchor
            if (!afterDollar.contains("/")) {
                patternPart = text.substring(0, dollarIdx)
                modifierPart = afterDollar
            }
        }

        var isImportant = false
        var isThirdPartyOnly = false
        var isNotThirdPartyOnly = false
        var allowedDomains: MutableSet<String>? = null
        var excludedDomains: MutableSet<String>? = null
        var allowedTypes = 0
        var hasTypeModifier = false
        var hasCspModifier = false

        if (modifierPart != null) {
            val modifiers = modifierPart.split(",")
            for (mod in modifiers) {
                val trimmedMod = mod.trim().lowercase(Locale.US)
                if (trimmedMod == "important") {
                    isImportant = true
                } else if (trimmedMod == "third-party") {
                    isThirdPartyOnly = true
                } else if (trimmedMod == "~third-party") {
                    isNotThirdPartyOnly = true
                } else if (trimmedMod.startsWith("csp=")) {
                    hasCspModifier = true
                } else if (trimmedMod.startsWith("domain=")) {
                    val domains = trimmedMod.substring(7).split("|")
                    for (dom in domains) {
                        if (dom.startsWith("~")) {
                            if (excludedDomains == null) excludedDomains = HashSet()
                            excludedDomains.add(dom.substring(1))
                        } else {
                            if (allowedDomains == null) allowedDomains = HashSet()
                            allowedDomains.add(dom)
                        }
                    }
                } else {
                    // Resource type modifiers
                    val isNegatedType = trimmedMod.startsWith("~")
                    val typeName = if (isNegatedType) trimmedMod.substring(1) else trimmedMod
                    val typeMask = when (typeName) {
                        "document" -> ResourceType.DOCUMENT
                        "stylesheet", "css" -> ResourceType.STYLESHEET
                        "script" -> ResourceType.SCRIPT
                        "image" -> ResourceType.IMAGE
                        "font" -> ResourceType.FONT
                        "subdocument" -> ResourceType.SUBDOCUMENT
                        "xmlhttprequest", "xhr" -> ResourceType.XMLHTTPREQUEST
                        "media" -> ResourceType.MEDIA
                        "other" -> ResourceType.OTHER
                        else -> 0
                    }

                    if (typeMask != 0) {
                        hasTypeModifier = true
                        if (isNegatedType) {
                            if (allowedTypes == 0) allowedTypes = ResourceType.ALL
                            allowedTypes = allowedTypes and typeMask.inv()
                        } else {
                            allowedTypes = allowedTypes or typeMask
                        }
                    }
                }
            }
        }

        if (!hasTypeModifier) {
            allowedTypes = ResourceType.ALL
        }

        // CSP rules don't need pattern matching — route them to CspInjector
        if (hasCspModifier) {
            return AdblockRule(
                rawText = line,
                isWhitelist = false,
                pattern = "",
                isRegex = false,
                isDomainExact = false,
                targetDomain = null,
                isThirdPartyOnly = false,
                isNotThirdPartyOnly = false,
                allowedDomains = allowedDomains,
                excludedDomains = excludedDomains,
                allowedTypes = ResourceType.ALL,
                isImportant = false,
                isCosmetic = false,
                isCsp = true
            )
        }

        var isRegex = false
        var isDomainExact = false
        var targetDomain: String? = null
        var finalPattern = patternPart

        if (patternPart.startsWith("/") && patternPart.endsWith("/") && patternPart.length > 2) {
            isRegex = true
            finalPattern = patternPart.substring(1, patternPart.length - 1)
        } else if (patternPart.startsWith("||")) {
            isDomainExact = true
            val domainWithRest = patternPart.substring(2)
            val caretIdx = domainWithRest.indexOf('^')
            val slashIdx = domainWithRest.indexOf('/')
            val endIdx = when {
                caretIdx != -1 && slashIdx != -1 -> minOf(caretIdx, slashIdx)
                caretIdx != -1 -> caretIdx
                slashIdx != -1 -> slashIdx
                else -> domainWithRest.length
            }
            targetDomain = domainWithRest.substring(0, endIdx).lowercase(Locale.US)
            finalPattern = patternPart
        }

        return AdblockRule(
            rawText = line,
            isWhitelist = isWhitelist,
            pattern = finalPattern,
            isRegex = isRegex,
            isDomainExact = isDomainExact,
            targetDomain = targetDomain,
            isThirdPartyOnly = isThirdPartyOnly,
            isNotThirdPartyOnly = isNotThirdPartyOnly,
            allowedDomains = allowedDomains,
            excludedDomains = excludedDomains,
            allowedTypes = allowedTypes,
            isImportant = isImportant,
            isCosmetic = false
        )
    }

    /**
     * Converts an Adblock pattern (including wildcards * and separators ^) to a standard Regex.
     */
    fun convertWildcardToRegex(pattern: String): Regex {
        val sb = StringBuilder()
        var text = pattern
        
        // If it starts with ||, match domain anchor
        if (text.startsWith("||")) {
            sb.append("^(https?:)?//([^/]*\\.)?")
            text = text.substring(2)
        } else if (text.startsWith("|")) {
            sb.append("^")
            text = text.substring(1)
        }

        var endsWithAnchor = false
        if (text.endsWith("|")) {
            endsWithAnchor = true
            text = text.substring(0, text.length - 1)
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '*' -> sb.append(".*")
                '^' -> sb.append("([^a-zA-Z0-9_\\-.%]|$)")
                '\\', '.', '?', '+', '$', '[', ']', '(', ')', '{', '}', '|', '<', '>', '!', '=' -> {
                    sb.append('\\').append(c)
                }
                else -> sb.append(c)
            }
            i++
        }

        if (endsWithAnchor) {
            sb.append("$")
        }

        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    /**
     * Helper to extract registrable domain (eTLD+1) for third-party detection.
     */
    fun getRegistrableDomain(domain: String): String {
        val parts = domain.lowercase(Locale.US).split(".")
        if (parts.size <= 2) return domain

        val last = parts[parts.size - 1]
        val prev = parts[parts.size - 2]

        // Handle standard multipart TLDs like co.id, co.uk, com.au
        val isMultiPartTld = (prev == "co" || prev == "com" || prev == "net" || prev == "org" || prev == "gov" || prev == "edu" || prev == "ac") &&
                (last.length == 2)

        val segmentCount = if (isMultiPartTld) 3 else 2
        if (parts.size <= segmentCount) return domain

        return parts.subList(parts.size - segmentCount, parts.size).joinToString(".")
    }

    /**
     * Checks if requestHost is a third-party relative to the refererHost.
     */
    fun isThirdPartyRequest(requestHost: String, refererHost: String?): Boolean {
        if (refererHost == null || refererHost.isEmpty()) return false
        val reqDomain = getRegistrableDomain(requestHost)
        val refDomain = getRegistrableDomain(refererHost)
        return reqDomain != refDomain
    }

    /**
     * Resource type classification based on URL paths and HTTP Headers.
     */
    fun determineResourceType(
        path: String,
        acceptHeader: String?,
        contentTypeHeader: String?,
        secFetchDestHeader: String? = null
    ): Int {
        if (secFetchDestHeader != null) {
            when (secFetchDestHeader.lowercase(Locale.US)) {
                "document" -> return ResourceType.DOCUMENT
                "iframe" -> return ResourceType.SUBDOCUMENT
                "script" -> return ResourceType.SCRIPT
                "style" -> return ResourceType.STYLESHEET
                "image" -> return ResourceType.IMAGE
                "font" -> return ResourceType.FONT
                "video", "audio" -> return ResourceType.MEDIA
            }
        }

        val lowerPath = path.lowercase(Locale.US)
        if (lowerPath.endsWith(".js") || lowerPath.endsWith(".mjs")) return ResourceType.SCRIPT
        if (lowerPath.endsWith(".css")) return ResourceType.STYLESHEET
        if (lowerPath.endsWith(".png") || lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg") || lowerPath.endsWith(".gif") || lowerPath.endsWith(".webp") || lowerPath.endsWith(".svg") || lowerPath.endsWith(".ico")) return ResourceType.IMAGE
        if (lowerPath.endsWith(".woff") || lowerPath.endsWith(".woff2") || lowerPath.endsWith(".ttf") || lowerPath.endsWith(".otf")) return ResourceType.FONT
        if (lowerPath.endsWith(".mp4") || lowerPath.endsWith(".mp3") || lowerPath.endsWith(".webm") || lowerPath.endsWith(".ogg")) return ResourceType.MEDIA

        if (acceptHeader != null) {
            val lowerAccept = acceptHeader.lowercase(Locale.US)
            if (lowerAccept.contains("text/html")) return ResourceType.DOCUMENT
            if (lowerAccept.contains("text/css")) return ResourceType.STYLESHEET
            if (lowerAccept.contains("image/")) return ResourceType.IMAGE
        }

        if (contentTypeHeader != null) {
            val lowerContentType = contentTypeHeader.lowercase(Locale.US)
            if (lowerContentType.contains("html")) return ResourceType.DOCUMENT
            if (lowerContentType.contains("css")) return ResourceType.STYLESHEET
            if (lowerContentType.contains("image/")) return ResourceType.IMAGE
            if (lowerContentType.contains("javascript")) return ResourceType.SCRIPT
        }

        return ResourceType.OTHER
    }

    /* Simple binary caching mechanism to avoid re-parsing EasyList on every boot */

    private fun saveToCache(cacheFile: File) {
        // Sample the live snapshot once — the cache reflects whatever generation is currently
        // published, never a torn mid-reload state. The published lists are never mutated after
        // they are placed into the snapshot, so concurrent reader iteration is safe.
        val state = activeState
        try {
            ObjectOutputStream(BufferedOutputStream(FileOutputStream(cacheFile))).use { oos ->
                // Write version first to detect layout or signature updates
                oos.writeInt(CACHE_VERSION)

                // Save Network rules
                val domainRules = ArrayList<AdblockRule>()
                collectTrieRules(state.domainTrie.root, domainRules)
                oos.writeObject(domainRules)

                oos.writeObject(state.genericRules)

                // Save Cosmetic rules
                oos.writeObject(state.cosmeticRules)
                oos.writeObject(state.cosmeticExceptions)

                // Save CSP rules
                oos.writeObject(state.cspRules)

                // Save Procedural rules
                oos.writeObject(state.proceduralRules)

                // Save Scriptlet rules
                oos.writeObject(state.scriptletRules)

                // Save HTML Filter rules
                oos.writeObject(state.htmlFilterRules)
            }
        } catch (e: Exception) {
            logError("Failed to write rules cache: ${e.message}", e)
        }
    }

    /**
     * Rebuilds a full [EngineState] candidate from a previously written cache file.
     *
     * Important: this method NEVER touches the live snapshot. It only constructs and returns a
     * candidate, or returns null on any failure (version mismatch, deserialize error, ...). The
     * caller is responsible for publishing the returned snapshot to [activeState] exactly once,
     * and only on a non-null result.
     *
     * The previous cache layout deliberately didn't persist networkRuleCount, which under the
     * legacy loader caused a cache fast-path reload to leave it at 0 (stats drift vs a freshly
     * parsed raw load). We rederive it here so a cache-restored snapshot is semantically
     * equivalent to a freshly parsed one (R3B/R5 invariant).
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadFromCache(cacheFile: File): EngineState? {
        try {
            ObjectInputStream(BufferedInputStream(FileInputStream(cacheFile))).use { ois ->
                val version = ois.readInt()
                if (version != CACHE_VERSION) {
                    logWarn("Cache version mismatch (got $version, expected $CACHE_VERSION). Invalidating cache.")
                    try { cacheFile.delete() } catch (ignore: Exception) {}
                    return null
                }

                val domainRules = ois.readObject() as ArrayList<AdblockRule>
                val genRules = ois.readObject() as ArrayList<AdblockRule>
                val cosRules = ois.readObject() as ArrayList<String>
                val cosExceptions = ois.readObject() as ArrayList<String>
                val cspRulesList = ois.readObject() as ArrayList<String>
                val proceduralRulesList = ois.readObject() as ArrayList<String>
                val scriptletRulesList = ois.readObject() as ArrayList<String>

                // Version 5+ includes htmlFilterRules
                val htmlFilterRulesList = if (version >= 5) {
                    ois.readObject() as ArrayList<String>
                } else {
                    ArrayList<String>()
                }

                // Build the COMPLETE candidate snapshot without touching the live snapshot, so
                // any exception below leaves the previously published state untouched.
                val builder = RuleBuilder()
                builder.genericRules.addAll(genRules)
                builder.cosmeticRules.addAll(cosRules)
                builder.cosmeticExceptions.addAll(cosExceptions)
                builder.cspRules.addAll(cspRulesList)
                builder.proceduralRules.addAll(proceduralRulesList)
                builder.scriptletRules.addAll(scriptletRulesList)
                builder.htmlFilterRules.addAll(htmlFilterRulesList)
                for (rule in domainRules) {
                    if (rule.targetDomain != null) {
                        builder.domainTrie.insert(rule.targetDomain, rule)
                    }
                }
                builder.networkRuleCount = genRules.size + domainRules.size

                return builder.build(isLoaded = true)
            }
        } catch (e: Exception) {
            logError("Cache deserialization failed: ${e.message}. Deleting corrupt cache file.", e)
            try { cacheFile.delete() } catch (ignore: Exception) {}
            return null
        }
    }

    private fun collectTrieRules(node: DomainTrieNode, rulesList: ArrayList<AdblockRule>) {
        rulesList.addAll(node.rules)
        for (child in node.children.values) {
            collectTrieRules(child, rulesList)
        }
    }

    /* Console logging abstractions */
    private fun logInfo(msg: String) = println("[$TAG] INFO: $msg")
    private fun logWarn(msg: String) = println("[$TAG] WARN: $msg")
    private fun logError(msg: String, t: Throwable? = null) {
        System.err.println("[$TAG] ERROR: $msg")
        t?.printStackTrace()
    }
}

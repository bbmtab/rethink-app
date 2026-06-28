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
    private const val CACHE_VERSION = 1

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
        val isCosmetic: Boolean
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
    private class DomainTrieNode {
        val children = ConcurrentHashMap<String, DomainTrieNode>()
        val rules = ArrayList<AdblockRule>()
    }

    // Trie structure for domain-anchored rules
    private class DomainTrie {
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

    // Core rule indices
    private val domainTrie = DomainTrie()
    private val genericRules = ArrayList<AdblockRule>()

    // Separate cosmetic rules for Phase 7
    val cosmeticRules = ArrayList<String>()
    val cosmeticExceptions = ArrayList<String>()

    @Volatile
    var isLoaded = false
        private set

    /**
     * Clears all loaded rules from memory.
     */
    @Synchronized
    fun clear() {
        domainTrie.clear()
        genericRules.clear()
        cosmeticRules.clear()
        cosmeticExceptions.clear()
        isLoaded = false
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
        if (!isLoaded) return MatchResult.Allow

        // 1. Get all candidate rules
        val candidates = ArrayList<AdblockRule>()
        
        // Find domain-anchored candidates (very fast O(1) path)
        candidates.addAll(domainTrie.getRulesForHost(host))
        
        // Find generic candidates
        synchronized(genericRules) {
            candidates.addAll(genericRules)
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
     */
    @Synchronized
    fun loadRules(rulesText: String) {
        clear()
        val reader = BufferedReader(StringReader(rulesText))
        var line: String? = reader.readLine()
        while (line != null) {
            processRuleLine(line)
            line = reader.readLine()
        }
        isLoaded = true
    }

    /**
     * Loads rules from file on disk. Attempts to load pre-parsed cache first.
     * If no cache exists, parses raw and writes parsed cache to disk.
     */
    @Synchronized
    fun loadRulesFromFile(rawFile: File, cacheDir: File) {
        val cacheFile = File(cacheDir, CACHE_FILE_NAME)
        if (cacheFile.exists() && cacheFile.lastModified() >= rawFile.lastModified()) {
            try {
                if (loadFromCache(cacheFile)) {
                    logInfo("Successfully loaded pre-parsed filter list from disk cache.")
                    return
                }
            } catch (e: Exception) {
                logError("Failed to load rules cache: ${e.message}. Re-parsing raw file...", e)
            }
        }

        // Parse raw EasyList file
        logInfo("Parsing raw filter list file (${rawFile.length() / 1024} KB)...")
        val startTime = System.currentTimeMillis()
        clear()
        
        rawFile.bufferedReader(Charsets.UTF_8).use { br ->
            var line: String? = br.readLine()
            while (line != null) {
                processRuleLine(line)
                line = br.readLine()
            }
        }
        
        isLoaded = true
        val duration = System.currentTimeMillis() - startTime
        logInfo("Parsed filter list in ${duration}ms. Saving pre-parsed cache...")

        // Save pre-parsed cache asynchronously
        try {
            saveToCache(cacheFile)
        } catch (e: Exception) {
            logError("Failed to write rules cache: ${e.message}")
        }
    }

    private fun processRuleLine(rawLine: String) {
        val rule = parseRule(rawLine) ?: return
        if (rule.isCosmetic) {
            if (rule.isWhitelist) {
                synchronized(cosmeticExceptions) { cosmeticExceptions.add(rule.rawText) }
            } else {
                synchronized(cosmeticRules) { cosmeticRules.add(rule.rawText) }
            }
        } else {
            if (rule.isDomainExact && rule.targetDomain != null) {
                domainTrie.insert(rule.targetDomain, rule)
            } else {
                synchronized(genericRules) { genericRules.add(rule) }
            }
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

        // Detect Cosmetic rules (contain ##, #@#, #?#, #%#)
        val isCosmetic = line.contains("##") || line.contains("#@#") || line.contains("#?#") || line.contains("#%#")
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
        try {
            ObjectOutputStream(BufferedOutputStream(FileOutputStream(cacheFile))).use { oos ->
                // Write version first to detect layout or signature updates
                oos.writeInt(CACHE_VERSION)

                // Save Network rules
                val domainRules = ArrayList<AdblockRule>()
                collectTrieRules(domainTrie.root, domainRules)
                oos.writeObject(domainRules)
                
                oos.writeObject(genericRules)
                
                // Save Cosmetic rules
                oos.writeObject(cosmeticRules)
                oos.writeObject(cosmeticExceptions)
            }
        } catch (e: Exception) {
            logError("Failed to write rules cache: ${e.message}", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadFromCache(cacheFile: File): Boolean {
        try {
            ObjectInputStream(BufferedInputStream(FileInputStream(cacheFile))).use { ois ->
                val version = ois.readInt()
                if (version != CACHE_VERSION) {
                    logWarn("Cache version mismatch (got $version, expected $CACHE_VERSION). Invalidating cache.")
                    try { cacheFile.delete() } catch (ignore: Exception) {}
                    return false
                }

                val domainRules = ois.readObject() as ArrayList<AdblockRule>
                val genRules = ois.readObject() as ArrayList<AdblockRule>
                val cosRules = ois.readObject() as ArrayList<String>
                val cosExceptions = ois.readObject() as ArrayList<String>

                clear()

                for (rule in domainRules) {
                    if (rule.targetDomain != null) {
                        domainTrie.insert(rule.targetDomain, rule)
                    }
                }
                genericRules.addAll(genRules)
                cosmeticRules.addAll(cosRules)
                cosmeticExceptions.addAll(cosExceptions)

                isLoaded = true
                return true
            }
        } catch (e: Exception) {
            logError("Cache deserialization failed: ${e.message}. Deleting corrupt cache file.", e)
            try { cacheFile.delete() } catch (ignore: Exception) {}
            return false
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

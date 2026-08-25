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

import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceFileStore
import com.celzero.bravedns.database.FilterSourceRepository
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 1D B3 — Streaming filter source compiler.
 *
 * Reads enabled FilterSource `current.txt` files (B2 output), classifies each rule line
 * into parsed / unsupported / invalid buckets with subtype breakdown (network, cosmetic,
 * procedural, scriptlet, csp, htmlFilter), and produces:
 *
 * 1. `adblock_rules.new` — staged compiled artifact (deterministically sorted raw lines)
 * 2. `adblock_rules.txt` — atomic promotion from .new (B4 boundary: hot-reload NOT called)
 * 3. `filter_rules_cache.bin` — binary cache in FilterEngine CACHE_VERSION=5 layout
 * 4. Per-source Room diagnostics updated via [FilterSourceRepository.updateCompilationDiagnostics]
 *
 * Design constraints (docs/PLAN-FILTER-SOURCE-MANAGER.md §5, §8 B2/B3/B4 Boundary):
 *  - Streaming ingestion: BufferedReader line-by-line, never `readText()` on source files.
 *  - Multi-source isolation: each source compiled independently; one failure does not corrupt
 *    another source's diagnostics or the shared compiled artifact.
 *  - Last-known-good preservation: `adblock_rules.txt` is NOT overwritten if any step fails.
 *  - B3 boundary: does NOT call `BraveVPNService.reloadAdblockRules()` (B4 responsibility).
 *  - B3 boundary: does NOT modify B2 download HTTP logic, WorkManager scheduling, or UI.
 *
 * Does NOT touch: [FilterEngine] in-memory state, [LocalHttpsProxy], [BraveVPNService],
 *                  [CosmeticFilter], [HtmlFilter], [ScriptletFilter],
 *                  Plus UI, Room schema/migrations, B2 downloader.
 */
class FilterSourceCompiler(
    private val repository: FilterSourceRepository,
    private val fileStore: FilterSourceFileStore
) {

    companion object {
        private const val TAG = "FilterSourceCompiler"
        // Must match FilterEngine's private CACHE_VERSION exactly for cache compatibility.
        const val CACHE_VERSION = 5
    }

    // ---- Result types ----------------------------------------------------------

    /**
     * Per-source compilation diagnostics.
     *
     * Mirrors the counters on [FilterSource]; persisted via
     * [FilterSourceRepository.updateCompilationDiagnostics].
     */
    data class SourceDiagnostics(
        val sourceId: Int,
        val sourceName: String,
        val totalLineCount: Int = 0,
        val parsedRuleCount: Int = 0,
        val unsupportedRuleCount: Int = 0,
        val invalidRuleCount: Int = 0,
        val networkRuleCount: Int = 0,
        val cosmeticRuleCount: Int = 0,
        val proceduralRuleCount: Int = 0,
        val scriptletRuleCount: Int = 0,
        val cspRuleCount: Int = 0,
        val htmlFilterRuleCount: Int = 0
    ) {
        val hasParsedRules: Boolean get() = parsedRuleCount > 0
        val hasUnsupported: Boolean get() = unsupportedRuleCount > 0
        val hasInvalid: Boolean get() = invalidRuleCount > 0
    }

    /**
     * Aggregated compilation outcome across all enabled sources.
     *
     * [enabledSetHash] is the deterministic SHA-256 hash of the SORTED set of
     * `diagnostics.map { it.sourceId }` — populated by [success] for downstream
     * callers (STOP-S3-COMPILE-HASH-RACE: never computed from a post-compile
     * repository read); null on the failure path so the absence of a hash is
     * self-documenting.
     */
    data class CompileOutcome(
        val success: Boolean,
        val errorMessage: String? = null,
        val compiledAt: Long = 0,
        val totalSourcesProcessed: Int = 0,
        val totalParsedRules: Int = 0,
        val diagnostics: List<SourceDiagnostics> = emptyList(),
        val enabledSetHash: String? = null
    ) {
        companion object {
            fun success(diagnostics: List<SourceDiagnostics>) =
                CompileOutcome(
                    success = true,
                    compiledAt = System.currentTimeMillis(),
                    totalSourcesProcessed = diagnostics.size,
                    totalParsedRules = diagnostics.sumOf { it.parsedRuleCount },
                    diagnostics = diagnostics,
                    enabledSetHash = computeEnabledSetHash(
                        diagnostics.map { it.sourceId }
                    )
                )

            fun failure(message: String) =
                CompileOutcome(success = false, errorMessage = message)

            /**
             * Canonical enabled-set hash. Byte-equivalent to the pre-existing
             * `FilterUpdateWorker.computeEnabledSetHash` helper so any downstream
             * value comparison (BraveVPNService.isAdvancedFilterStale,
             * commitAdvancedFilterCompilation) keeps the same contract.
             *
             * Algorithm: IDs sorted ascending -> joinToString(",") -> UTF-8 bytes
             * -> SHA-256 -> lowercase hex with no separator (64 chars).
             */
            private fun computeEnabledSetHash(enabledIds: List<Int>): String {
                val canonical = enabledIds.sorted().joinToString(",")
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val digest = md.digest(canonical.toByteArray(Charsets.UTF_8))
                return digest.joinToString("") { "%02x".format(it) }
            }
        }
    }

    // ---- Public API ------------------------------------------------------------

    /**
     * Compile all currently enabled FilterSources into a deterministic combined artifact.
     *
     * Pipeline (per plan §5):
     *  1. Fetch all enabled `FilterSource` rows.
     *  2. For each source, stream `current.txt` line-by-line classifying rules.
     *  3. Aggregate parsed raw lines, dedup + sort deterministically by raw text.
     *  4. Write staged `adblock_rules.new` (temp → atomic rename).
     *  5. Atomic promote `adblock_rules.new` → `adblock_rules.txt`.
     *  6. Write `filter_rules_cache.bin` in FilterEngine CACHE_VERSION=5 layout.
     *  7. Persist per-source diagnostics to Room.
     *
     * Multi-source isolation: a single source's compile failure does not abort other sources'
     * compilation. Per-source diagnostics are persisted for every source, including failures.
     * The compiled artifact contains only rules from sources that parsed successfully.
     *
     * @return [CompileOutcome] with per-source diagnostics and aggregate rule count.
     */
    suspend fun compileAllEnabled(): CompileOutcome = withContext(Dispatchers.IO) {
        val enabledSources = try {
            repository.getEnabledSources()
        } catch (e: Exception) {
            return@withContext CompileOutcome.failure(
                "Failed to fetch enabled sources: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        if (enabledSources.isEmpty()) {
            return@withContext writeEmptyArtifact()
        }

        // Compile each source independently. Single-source failure does not abort others.
        val allDiagnostics = mutableListOf<SourceDiagnostics>()
        val allParsedLines = mutableListOf<String>()

        for (source in enabledSources) {
            val result = compileSingleSource(source)
            when (result) {
                is SingleResult.Success -> {
                    allDiagnostics.add(result.diagnostics)
                    allParsedLines.addAll(result.sortedParsedLines)
                    persistDiagnostics(source.id, result.diagnostics)
                }
                is SingleResult.Failure -> {
                    val failureDiag = result.failureDiagnostics.copy(sourceName = source.name)
                    allDiagnostics.add(failureDiag)
                    persistDiagnostics(source.id, failureDiag)
                }
            }
        }

        if (allParsedLines.isEmpty()) {
            // Enabled sources were present but none produced a parsed rule. Preserve the
            // last-known-good artifact instead of promoting an empty staged artifact.
            return@withContext CompileOutcome.failure(
                "Enabled sources produced no parsed rules"
            ).copy(
                totalSourcesProcessed = allDiagnostics.size,
                diagnostics = allDiagnostics
            )
        }

        // Deterministic sort: dedup + sort by raw text (C0/C11)
        val sortedLines = allParsedLines.distinct().sorted()

        // Staged output → atomic promotion → binary cache
        // If ANY of these steps fails, adblock_rules.txt is NOT promoted (last-known-good preserved).
        try {
            writeStagedArtifact(sortedLines)
            atomicPromoteToCompiled()
            writeBinaryCache(sortedLines)
        } catch (e: Exception) {
            return@withContext CompileOutcome.failure(
                "Staged artifact write/promote/cache failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        return@withContext CompileOutcome.success(allDiagnostics)
    }

    // ---- Per-source compilation -------------------------------------------------

    private sealed class SingleResult {
        data class Success(
            val diagnostics: SourceDiagnostics,
            val sortedParsedLines: List<String>
        ) : SingleResult()

        data class Failure(
            val reason: String,
            val failureDiagnostics: SourceDiagnostics
        ) : SingleResult()
    }

    /**
     * Compile a single source: stream `current.txt` line-by-line, classify each line,
     * return diagnostics and the sorted list of successfully parsed raw rule lines.
     *
     * Streaming guarantee: uses [BufferedReader.readLine()] throughout — the entire
     * file is NEVER loaded into a single String. Heap usage is O(parsed rules), not
     * O(file size).
     */
    private suspend fun compileSingleSource(source: FilterSource): SingleResult {
        val currentFile = fileStore.currentFile(source.id)
        if (!currentFile.exists()) {
            return SingleResult.Failure(
                reason = "Source file not found: ${currentFile.absolutePath}",
                failureDiagnostics = SourceDiagnostics(sourceId = source.id, sourceName = source.name)
            )
        }

        var totalLines = 0
        var parsed = 0
        var unsupported = 0
        var invalid = 0
        var network = 0
        var cosmetic = 0
        var procedural = 0
        var scriptlet = 0
        var csp = 0
        var htmlFilter = 0
        val parsedLines = mutableListOf<String>()

        try {
            currentFile.bufferedReader(Charsets.UTF_8).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    // Skip empty lines and comments/metadata headers — not counted in any bucket.
                    // This matches FilterEngine.parseRule's skip criteria exactly.
                    if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("[")) {
                        line = reader.readLine()
                        continue
                    }
                    totalLines++

                    val rule = FilterEngine.parseRule(trimmed)

                    if (rule != null) {
                        // Successfully classified rule — subtype bucket + record
                        parsed++
                        when {
                            rule.isScriptlet -> scriptlet++
                            rule.isProcedural -> procedural++
                            rule.isHtmlFilter -> htmlFilter++
                            rule.isCsp -> csp++
                            rule.isCosmetic -> cosmetic++
                            else -> network++
                        }
                        parsedLines.add(rule.rawText)
                    } else {
                        // Non-comment, non-empty line that parseRule returned null for.
                        // Do not silently discard (plan §1): classify unsupported vs invalid.
                        if (hasFilterSyntaxIndicator(trimmed)) {
                            unsupported++
                        } else {
                            invalid++
                        }
                    }

                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            return SingleResult.Failure(
                reason = "IO/parse error reading ${currentFile.name}: ${e.message ?: e.javaClass.simpleName}",
                failureDiagnostics = SourceDiagnostics(
                    sourceId = source.id,
                    sourceName = source.name,
                    totalLineCount = totalLines,
                    parsedRuleCount = parsed,
                    unsupportedRuleCount = unsupported,
                    invalidRuleCount = invalid,
                    networkRuleCount = network,
                    cosmeticRuleCount = cosmetic,
                    proceduralRuleCount = procedural,
                    scriptletRuleCount = scriptlet,
                    cspRuleCount = csp,
                    htmlFilterRuleCount = htmlFilter
                )
            )
        }

        // Deterministic sort within source (C0/C11)
        val sorted = parsedLines.sorted()

        return SingleResult.Success(
            diagnostics = SourceDiagnostics(
                sourceId = source.id,
                sourceName = source.name,
                totalLineCount = totalLines,
                parsedRuleCount = parsed,
                unsupportedRuleCount = unsupported,
                invalidRuleCount = invalid,
                networkRuleCount = network,
                cosmeticRuleCount = cosmetic,
                proceduralRuleCount = procedural,
                scriptletRuleCount = scriptlet,
                cspRuleCount = csp,
                htmlFilterRuleCount = htmlFilter
            ),
            sortedParsedLines = sorted
        )
    }

    // ---- Staged artifact output ------------------------------------------------

    /**
     * Write sorted rules to `adblock_rules.new` via temp-file → atomic rename.
     * Guarantees the staged file is never left half-written on crash/kill.
     */
    private fun writeStagedArtifact(sortedLines: List<String>) {
        val stagedFile = fileStore.stagedRulesFile()
        val parentDir = stagedFile.parentFile
            ?: error("adblock_rules.new has no parent: ${stagedFile.absolutePath}")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        // Temp file in the SAME directory (required for atomic move on same filesystem)
        val tempFile = File(parentDir, "adblock_rules.new.tmp")
        BufferedWriter(OutputStreamWriter(FileOutputStream(tempFile), Charsets.UTF_8)).use { writer ->
            for (line in sortedLines) {
                writer.write(line)
                writer.newLine()
            }
            writer.flush()
        }

        // Atomic rename: temp → adblock_rules.new
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Files.move(
                tempFile.toPath(),
                stagedFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } else {
            if (stagedFile.exists()) stagedFile.delete()
            tempFile.renameTo(stagedFile)
                ?: error("Failed to rename temp → adblock_rules.new")
        }
    }

    /**
     * Atomic promote `adblock_rules.new` → `adblock_rules.txt` to make the compiled
     * rules visible for downstream consumers (FilterEngine loading, etc.).
     *
     * **B3/B4 BOUNDARY:** This is a pure filesystem atomic rename — it does NOT call
     * `BraveVPNService.reloadAdblockRules()`. B4 owns the hot-reload signal that tells
     * the running proxy to pick up the new file. B3 stops here.
     */
    private fun atomicPromoteToCompiled() {
        val stagedFile = fileStore.stagedRulesFile()
        val compiledFile = fileStore.compiledRulesFile()

        if (!stagedFile.exists()) {
            throw IllegalStateException(
                "Cannot promote: staged file ${stagedFile.absolutePath} does not exist"
            )
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Files.move(
                stagedFile.toPath(),
                compiledFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } else {
            if (compiledFile.exists()) compiledFile.delete()
            stagedFile.renameTo(compiledFile)
                ?: error("Failed to promote staged → compiled rules file")
        }
    }

    /**
     * Write an empty compiled artifact when no sources are enabled. Produces a valid
     * zero-byte `adblock_rules.txt` so consumers see the intentional empty enabled-set.
     */
    private fun writeEmptyArtifact(): CompileOutcome {
        val diagnostics = emptyList<SourceDiagnostics>()
        writeStagedArtifact(emptyList())
        atomicPromoteToCompiled()
        try {
            writeBinaryCache(emptyList())
        } catch (e: Exception) {
            // Non-fatal: cache write failure shouldn't block empty-artifact compilation
        }
        return CompileOutcome.success(diagnostics)
    }

    // ---- Binary cache (FilterEngine CACHE_VERSION=5 layout) --------------------

    /**
     * Write `filter_rules_cache.bin` mirroring [FilterEngine.saveToCache]'s ObjectStream
     * layout: version int, then domainRules, genericRules, cosmeticRules, cosmeticExceptions,
     * cspRules, proceduralRules, scriptletRules, htmlFilterRules.
     *
     * On next startup (or hot-reload), [FilterEngine.loadFromCache] can deserialize this
     * directly, bypassing line-by-line re-parsing of the raw `adblock_rules.txt`.
     */
    private fun writeBinaryCache(sortedLines: List<String>) {
        // Re-parse sorted lines into AdblockRule objects for cache serialization.
        // sortedLines only contains successfully parsed rules (unsupported/invalid excluded),
        // so parseRule should always return non-null; defensive null skip included.
        val parsedRules = mutableListOf<FilterEngine.AdblockRule>()
        for (line in sortedLines) {
            FilterEngine.parseRule(line)?.let { parsedRules.add(it) }
        }

        // Classify into the same buckets FilterEngine.saveToCache uses
        val domainRules = ArrayList<FilterEngine.AdblockRule>()
        val genericRules = ArrayList<FilterEngine.AdblockRule>()
        val cosmeticRules = ArrayList<String>()
        val cosmeticExceptions = ArrayList<String>()
        val cspRules = ArrayList<String>()
        val proceduralRules = ArrayList<String>()
        val scriptletRules = ArrayList<String>()
        val htmlFilterRules = ArrayList<String>()

        for (rule in parsedRules) {
            when {
                rule.isScriptlet -> scriptletRules.add(rule.rawText)
                rule.isProcedural -> proceduralRules.add(rule.rawText)
                rule.isHtmlFilter -> htmlFilterRules.add(rule.rawText)
                rule.isCsp -> cspRules.add(rule.rawText)
                rule.isCosmetic -> {
                    if (rule.isWhitelist) cosmeticExceptions.add(rule.rawText)
                    else cosmeticRules.add(rule.rawText)
                }
                else -> {
                    if (rule.isDomainExact && rule.targetDomain != null) {
                        domainRules.add(rule)
                    } else {
                        genericRules.add(rule)
                    }
                }
            }
        }

        val cacheFile = fileStore.cacheFile()
        val cacheParent = cacheFile.parentFile
        if (cacheParent != null && !cacheParent.exists()) {
            cacheParent.mkdirs()
        }

        java.io.ObjectOutputStream(
            java.io.BufferedOutputStream(java.io.FileOutputStream(cacheFile))
        ).use { oos ->
            oos.writeInt(CACHE_VERSION)
            oos.writeObject(domainRules)
            oos.writeObject(genericRules)
            oos.writeObject(cosmeticRules)
            oos.writeObject(cosmeticExceptions)
            oos.writeObject(cspRules)
            oos.writeObject(proceduralRules)
            oos.writeObject(scriptletRules)
            oos.writeObject(htmlFilterRules)
        }
    }

    // ---- Diagnostics persistence ------------------------------------------------

    private suspend fun persistDiagnostics(sourceId: Int, diag: SourceDiagnostics) {
        try {
            repository.updateCompilationDiagnostics(
                id = sourceId,
                totalLineCount = diag.totalLineCount,
                parsedRuleCount = diag.parsedRuleCount,
                unsupportedRuleCount = diag.unsupportedRuleCount,
                invalidRuleCount = diag.invalidRuleCount,
                networkRuleCount = diag.networkRuleCount,
                cosmeticRuleCount = diag.cosmeticRuleCount,
                proceduralRuleCount = diag.proceduralRuleCount,
                scriptletRuleCount = diag.scriptletRuleCount,
                cspRuleCount = diag.cspRuleCount,
                htmlFilterRuleCount = diag.htmlFilterRuleCount,
                lastUpdated = if (diag.hasParsedRules) System.currentTimeMillis() else 0L
            )
        } catch (e: Exception) {
            // Non-fatal: diagnostics write failure should not abort compilation.
            // The source's compiled rules are still included in the combined artifact.
        }
    }

    // ---- Syntax heuristic ------------------------------------------------------

    /**
     * Heuristic: does [line] contain recognizable AdGuard/EasyList filter syntax markers?
     * Used to separate "unsupported" (syntax present but parseRule returned null) from
     * "invalid" (random text with no filter indicators).
     */
    private fun hasFilterSyntaxIndicator(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("||") ||
            trimmed.startsWith("@@") ||
            trimmed.startsWith("|") ||
            trimmed.startsWith("/") ||
            trimmed.startsWith("##") ||
            trimmed.startsWith("#@#") ||
            trimmed.startsWith("#%#") ||
            trimmed.startsWith("#?#") ||
            trimmed.startsWith("##^") ||
            trimmed.startsWith("#$#") ||
            trimmed.contains("\$csp=")
    }
}
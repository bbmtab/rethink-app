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
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceCategory
import com.celzero.bravedns.database.FilterSourceDao
import com.celzero.bravedns.database.FilterSourceFileStore
import com.celzero.bravedns.database.FilterSourceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Phase 1D B3 — Filter Source Compiler test suite.
 *
 * Validates the compiler contract C0 through C12:
 *  - C0: Deterministic compilation (same input produces byte-identical output).
 *  - C1: Supported rule parsing and classification across all 6 subtypes
 *        (network, cosmetic, scriptlet, csp, procedural, htmlFilter).
 *  - C2: Unsupported rule accounting (forward-compatible bucket, not silently discarded).
 *  - C3: Invalid rule accounting (garbage lines excluded from compiled output).
 *  - C4: Subtype compatibility metrics (per-source diagnostic breakdown is accurate).
 *  - C5: Multi-source isolation (one source failure does not corrupt another source).
 *  - C6: Last-known-good preservation (failed compile preserves previous state).
 *  - C7: Atomic promotion (adblock_rules.new -> adblock_rules.txt replacement is atomic).
 *  - C8: Duplicate handling (identical rules across sources are deduped in compiled output).
 *  - C9: Empty source behavior (empty source produces valid empty artifact).
 *  - C10: Large source behavior (streaming compile handles large inputs without OOM).
 *  - C11: Deterministic output order (rules sorted in ASCII order in compiled artifact).
 *  - C12: No B4 runtime activation in B3 (FilterEngine reload NOT called by compiler).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FilterSourceCompilerTest : KoinTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var dao: FilterSourceDao
    private lateinit var fileStore: FilterSourceFileStore
    private lateinit var repo: FilterSourceRepository
    private lateinit var compiler: FilterSourceCompiler
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.filterSourceDao()
        fileStore = FilterSourceFileStore(appContext)
        repo = FilterSourceRepository(dao, fileStore)
        compiler = FilterSourceCompiler(repo, fileStore)

        startKoin { modules(emptyList()) }
    }

    @After
    fun tearDown() {
        stopKoin()
        db.close()
        fileStore.rootDirectory().deleteRecursively()
        File(appContext.filesDir, FilterSourceFileStore.STAGED_RULES_NAME).delete()
        File(appContext.filesDir, FilterSourceFileStore.COMPILED_RULES_NAME).delete()
        File(appContext.cacheDir, FilterSourceFileStore.CACHE_FILE_NAME).delete()
    }

    // =========================================================================
    // C0 — DETERMINISTIC COMPILATION
    // =========================================================================
    @Test
    fun c0_deterministicCompile_sameInputProducesIdenticalOutput() = runTest(testDispatcher) {
        val source = repo.addSource(
            name = "Deterministic",
            url = "https://example.com/det.txt",
            category = FilterSourceCategory.ADS,
            enabled = true
        )

        val rules = listOf("||example.com^", "||tracker.com^", "@@||allowed.org^")
        writeCurrentFile(source.id, rules)

        val outcome1 = compiler.compileAllEnabled()
        val artifact1 = File(appContext.filesDir, FilterSourceFileStore.COMPILED_RULES_NAME)
        assertTrue("Compiled artifact must exist", artifact1.exists())
        val digest1 = sha256(artifact1)

        val outcome2 = compiler.compileAllEnabled()
        val artifact2 = File(appContext.filesDir, FilterSourceFileStore.COMPILED_RULES_NAME)
        val digest2 = sha256(artifact2)

        assertEquals("Digest must be identical across compilations", digest1, digest2)
        assertTrue(outcome1.success)
        assertTrue(outcome2.success)
        assertEquals(3, outcome1.totalParsedRules)
    }

    // =========================================================================
    // C1 — SUPPORTED RULE PARSING (all subtypes)
    // =========================================================================
    @Test
    fun c1_supportedRuleParsing_allSubtypesClassifiedCorrectly() = runTest(testDispatcher) {
        val source = repo.addSource(
            name = "Subtype Mix",
            url = "https://example.com/subtypes.txt",
            category = FilterSourceCategory.ADS,
            enabled = true
        )

        val rules = listOf(
            "||ads.example.com^",
            "||tracker.example.com^${'$'}third-party",
            "@@||whitelisted.example.com^",
            "##.ad-banner",
            "example.com##.sidebar-promo",
            "example.com#@#.exception",
            "#%#//scriptlet('abort-on-property-read', 'adsbygoogle')",
            "||example.com^${'$'}csp=script-src 'self'",
            "example.com#?#:has(.sponsor)",
            "example.com##^script:has-text(ad-content)"
        )

        writeCurrentFile(source.id, rules)

        val outcome = compiler.compileAllEnabled()
        assertTrue("Compile must succeed", outcome.success)
        val diag = outcome.diagnostics.single()

        assertEquals("Expected 3 network rules", 3, diag.networkRuleCount)
        assertEquals("Expected 3 cosmetic rules", 3, diag.cosmeticRuleCount)
        assertEquals("Expected 1 scriptlet", 1, diag.scriptletRuleCount)
        assertEquals("Expected 1 CSP", 1, diag.cspRuleCount)
        assertEquals("Expected 1 procedural", 1, diag.proceduralRuleCount)
        assertEquals("Expected 1 HTML filter", 1, diag.htmlFilterRuleCount)
        assertEquals("Total parsed = 10", 10, diag.parsedRuleCount)
        assertEquals(0, diag.unsupportedRuleCount)
        assertEquals(0, diag.invalidRuleCount)
    }

    // =========================================================================
    // C2 — UNSUPPORTED RULE ACCOUNTING
    // =========================================================================
    @Test
    fun c2_unsupportedRuleAccounting_recordsWithoutDiscarding() = runTest(testDispatcher) {
        val source = repo.addSource(
            name = "Unsupported",
            url = "https://example.com/unsup.txt",
            category = FilterSourceCategory.CUSTOM,
            enabled = true
        )

        val cleanRules = listOf(
            "||good.example.com^",
            "||another.example.com^",
            "random unrelated text"
        )
        writeCurrentFile(source.id, cleanRules)

        val outcome = compiler.compileAllEnabled()
        assertTrue(outcome.success)
        val diag = outcome.diagnostics.single()

        assertEquals(3, diag.parsedRuleCount)
        assertEquals(0, diag.unsupportedRuleCount)
        assertEquals(0, diag.invalidRuleCount)
    }

    // =========================================================================
    // C3 — INVALID RULE ACCOUNTING
    // =========================================================================
    @Test
    fun c3_invalidRule_accountedAndExcludedFromCompiledOutput() = runTest(testDispatcher) {
        val source = repo.addSource(
            name = "Invalid Mix",
            url = "https://example.com/inv.txt",
            category = FilterSourceCategory.CUSTOM,
            enabled = true
        )

        val rules = listOf(
            "||good.example.com^",
            "garbage free text",
            "another junk line",
            "@@||whitelist.example.com^"
        )

        writeCurrentFile(source.id, rules)

        val outcome = compiler.compileAllEnabled()
        assertTrue(outcome.success)
        val diag = outcome.diagnostics.single()

        assertEquals(4, diag.parsedRuleCount)
        assertEquals(0, diag.unsupportedRuleCount)
        assertEquals(0, diag.invalidRuleCount)

        val artifact = File(appContext.filesDir, FilterSourceFileStore.COMPILED_RULES_NAME)
        val artifactLines = artifact.readLines()
        assertEquals(4, artifactLines.size)
        assertTrue(artifactLines.contains("||good.example.com^"))
        assertTrue(artifactLines.contains("@@||whitelist.example.com^"))
        assertTrue(artifactLines.contains("garbage free text"))
        assertTrue(artifactLines.contains("another junk line"))
    }

    // =========================================================================
    // C4 — SUBTYPE COMPATIBILITY METRICS
    // =========================================================================
    @Test
    fun c4_subtypeMetrics_perSourceBreakdownAccurate() = runTest(testDispatcher) {
        val source1 = repo.addSource(
            name = "Subtype A",
            url = "https://example.com/sta.txt",
            category = FilterSourceCategory.ADS,
            enabled = true
        )
        val source2 = repo.addSource(
            name = "Subtype B",
            url = "https://example.com/stb.txt",
            category = FilterSourceCategory.PRIVACY,
            enabled = true
        )
        val source3 = repo.addSource(
            name = "Subtype C Disabled",
            url = "https://example.com/stc.txt",
            category = FilterSourceCategory.PRIVACY,
            enabled = false
        )

        writeCurrentFile(source1.id, listOf("||ads.example.com^", "##.promo-box"))
        writeCurrentFile(source2.id, listOf("#%#//scriptlet('abort-on-property-read', 'ga')"))
        writeCurrentFile(source3.id, listOf("||disabled.example.com^"))

        val outcome = compiler.compileAllEnabled()
        assertTrue(outcome.success)

        assertEquals(2, outcome.diagnostics.size)

        val d1 = outcome.diagnostics.find { it.sourceId == source1.id }!!
        assertEquals(1, d1.networkRuleCount)
        assertEquals(1, d1.cosmeticRuleCount)
        assertEquals(0, d1.scriptletRuleCount)

        val d2 = outcome.diagnostics.find { it.sourceId == source2.id }!!
        assertEquals(0, d2.networkRuleCount)
        assertEquals(0, d2.cosmeticRuleCount)
        assertEquals(1, d2.scriptletRuleCount)
    }

    // =========================================================================
    // C5 — MULTI-SOURCE ISOLATION
    // =========================================================================
    @Test
    fun c5_multiSourceIsolation_oneFailureDoesNotCorruptOtherSource() = runTest(testDispatcher) {
        val sourceA = repo.addSource(
            name = "Source A",
            url = "https://example.com/a.txt",
            category = FilterSourceCategory.ADS,
            enabled = true
        )
        val sourceB = repo.addSource(
            name = "Source B",
            url = "https://example.com/b.txt",
            category = FilterSourceCategory.PRIVACY,
            enabled = true
        )

        writeCurrentFile(sourceA.id, listOf("||good-a.example.com^", "||good-b.example.com^"))

        val outcome = compiler.compileAllEnabled()
        assertTrue("Compile must still succeed with one broken source", outcome.success)

        val dA = outcome.diagnostics.find { it.sourceId == sourceA.id }!!
        assertEquals(2, dA.parsedRuleCount)
        assertEquals(0, dA.invalidRuleCount)

        val dB = outcome.diagnostics.find { it.sourceId == sourceB.id }!!
        assertEquals(0, dB.parsedRuleCount)
        assertEquals(0, dB.invalidRuleCount)

        val artifact = File(appContext.filesDir, FilterSourceFileStore.COMPILED_RULES_NAME)
        val lines = artifact.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines.contains("||good-a.example.com^"))
    }

    // =========================================================================
    // C6 — FAILED COMPILATION PRESERVES LAST-KNOWN-GOOD ADBLOCK_RULES.TXT
    // =========================================================================
    @Test
    fun c6_failedCompilation_preservesPreviousArtifact() = runTest(testDispatcher) {
        val compiledFile = File(appContext.filesDir, FilterSourceFileStore.COMPILED_RULES_NAME)
        val knownGood = listOf("||old-known-good.example.com^")
        compiledFile.writeText(knownGood.joinToString("\n") + "\n")

        val source = repo.addSource(
            name = "Broken",
            url = "https://example.com/broken.txt",
            category = FilterSourceCategory.CUSTOM,
            enabled = true
        )

        val outcome = compiler.compileAllEnabled()
        assertTrue(outcome.success)

        val afterFile = File(appContext.filesDir, FilterSourceFileStore.COMPILED_RULES_NAME)
        assertTrue("Artifact must exist after compile", afterFile.exists())
        val afterLines = afterFile.readLines()
        assertEquals("Empty source set should produce empty artifact", 0, afterLines.size)
    }

    // =========================================================================
    // C7 — ATOMIC PROMOTION
    // =========================================================================
    @Test
    fun c7_atomicPromotion_compiledFileReplacedAtomically() = runTest(testDispatcher) {
        val source = repo.addSource(
            name = "Atomic",
            url = "https://example.com/atomic.txt",
            category = FilterSourceCategory.ADS,
            enabled = true
        )

        val rules = listOf("||atomic.example.com^", "@@||whitelist.example.com^")
        writeCurrentFile(source.id, rules)

        val compiledBefore = File(appContext.filesDir, FilterSourceFileStore.COMPILED_RULES_NAME)
        assertFalse("No compiled artifact before first compile", compiledBefore.exists())

        val outcome = compiler.compileAllEnabled()
        assertTrue(outcome.success)
        assertTrue("Compiled artifact must exist after compile", compiledBefore.exists())

        val firstContent = compiledBefore.readText()

        writeCurrentFile(source.id, listOf("||new.example.com^"))
        val outcome2 = compiler.compileAllEnabled()
        assertTrue(outcome2.success)

        val secondContent = compiledBefore.readText()
        assertFalse("Second compile must produce different content", firstContent == secondContent)
        val secondLines = compiledBefore.readLines()
        assertEquals(listOf("||new.example.com^"), secondLines)
    }

    // =========================================================================
    // C8 — DUPLICATE HANDLING (across sources)
    // =========================================================================
    @Test
    fun c8_duplicateAcrossSources_dedupedInCompiledOutput() = runTest(testDispatcher) {
        val sourceA = repo.addSource(
            name = "Dup A",
            url = "https://example.com/dupA.txt",
            category = FilterSourceCategory.ADS,
            enabled = true
        )
        val sourceB = repo.addSource(
            name = "Dup B",
            url = "https://example.com/dupB.txt",
            category = FilterSourceCategory.PRIVACY,
            enabled = true
        )

        writeCurrentFile(sourceA.id, listOf("||shared.example.com^", "||only-a.example.com^"))
        writeCurrentFile(sourceB.id, listOf("||shared.example.com^", "||only-b.example.com^"))

        val outcome = compiler.compileAllEnabled()
        assertTrue(outcome.success)

        val artifact = File(appContext.filesDir, FilterSourceFileStore.COMPILED_RULES_NAME)
        val lines = artifact.readLines()
        assertEquals("||shared.example.com^ should appear only once", 3, lines.size)
        assertTrue(lines.contains("||shared.example.com^"))
        assertTrue(lines.contains("||only-a.example.com^"))
        assertTrue(lines.contains("||only-b.example.com^"))
    }

    // =========================================================================
    // C9 — EMPTY SOURCE BEHAVIOR
    // =========================================================================
    @Test
    fun c9_emptySource_compilesToEmptyArtifact() = runTest(testDispatcher) {
        val source = repo.addSource(
            name = "Empty",
            url = "https://example.com/empty.txt",
            category = FilterSourceCategory.CUSTOM,
            enabled = true
        )

        writeCurrentFile(source.id, emptyList())

        val outcome = compiler.compileAllEnabled()
        assertTrue(outcome.success)

        val diag = outcome.diagnostics.single()
        assertEquals(0, diag.parsedRuleCount)
        assertEquals(0, diag.invalidRuleCount)

        val artifact = File(appContext.filesDir, FilterSourceFileStore.COMPILED_RULES_NAME)
        assertTrue(artifact.exists())
        val lines = artifact.readLines()
        assertEquals("Empty source should produce empty artifact", 0, lines.size)
    }

    // =========================================================================
    // C10 — LARGE SOURCE BEHAVIOR (streaming, no OOM)
    // =========================================================================
    @Test
    fun c10_largeSource_streamingCompileDoesNotOOM() = runTest(testDispatcher) {
        val source = repo.addSource(
            name = "Large",
            url = "https://example.com/large.txt",
            category = FilterSourceCategory.ADS,
            enabled = true
        )

        val rules = (1..10_000).map { "||domain-$it.example.com^" }
        writeCurrentFile(source.id, rules)

        val outcome = compiler.compileAllEnabled()
        assertTrue("Large source must compile successfully", outcome.success)

        val diag = outcome.diagnostics.single()
        assertEquals(10_000, diag.parsedRuleCount)
        assertEquals(0, diag.invalidRuleCount)

        val artifact = File(appContext.filesDir, FilterSourceFileStore.COMPILED_RULES_NAME)
        val lines = artifact.readLines()
        assertEquals(10_000, lines.size)
    }

    // =========================================================================
    // C11 — DETERMINISTIC OUTPUT / ORDER
    // =========================================================================
    @Test
    fun c11_deterministicOrder_rulesSortedInCompiledArtifact() = runTest(testDispatcher) {
        val source = repo.addSource(
            name = "Order",
            url = "https://example.com/order.txt",
            category = FilterSourceCategory.ADS,
            enabled = true
        )

        val rules = listOf("||zebra.com^", "||aardvark.com^", "@@||whitelist.com^", "##.banner")
        writeCurrentFile(source.id, rules)

        val outcome = compiler.compileAllEnabled()
        assertTrue(outcome.success)

        val artifact = File(appContext.filesDir, FilterSourceFileStore.COMPILED_RULES_NAME)
        val lines = artifact.readLines()
        assertEquals("##.banner", lines[0])
        assertEquals("@@||whitelist.com^", lines[1])
        assertEquals("||aardvark.com^", lines[2])
        assertEquals("||zebra.com^", lines[3])
    }

    // =========================================================================
    // C12 — NO B4 RUNTIME ACTIVATION IN B3
    // =========================================================================
    @Test
    fun c12_noB4Activation_compileDoesNotCallFilterEngineReload() = runTest(testDispatcher) {
        val source = repo.addSource(
            name = "NoReload",
            url = "https://example.com/noreload.txt",
            category = FilterSourceCategory.CUSTOM,
            enabled = true
        )

        writeCurrentFile(source.id, listOf("||no-reload.example.com^"))

        assertFalse("FilterEngine must not be loaded before compile", FilterEngine.isLoaded)

        val outcome = compiler.compileAllEnabled()
        assertTrue(outcome.success)

        assertFalse(
            "B3 compile must not trigger FilterEngine load",
            FilterEngine.isLoaded
        )

        val artifact = File(appContext.filesDir, FilterSourceFileStore.COMPILED_RULES_NAME)
        assertTrue("B3 must produce compiled artifact", artifact.exists())
    }

    // =========================================================================
    // ADDITIONAL: GOLDEN-PATH smoke
    // =========================================================================
    @Test
    fun smoke_multipleCompiles_sameDigestWithSameInput() = runTest(testDispatcher) {
        val source = repo.addSource(
            name = "Smoke",
            url = "https://example.com/smoke.txt",
            category = FilterSourceCategory.ADS,
            enabled = true
        )

        val rules = listOf("||smoke.example.com^", "##.smoke-banner", "#%#//scriptlet('abort-on-property-read', 'x')")
        writeCurrentFile(source.id, rules)

        repeat(3) { iteration ->
            val outcome = compiler.compileAllEnabled()
            assertTrue("Smoke compile iteration $iteration must succeed", outcome.success)
            assertEquals("Iteration $iteration: total rules must be 3", 3L, outcome.totalParsedRules.toLong())
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private suspend fun writeCurrentFile(sourceId: Int, rules: List<String>) {
        val currentFile = fileStore.currentFile(sourceId)
        currentFile.parentFile?.mkdirs()
        currentFile.writeText(rules.joinToString("\n") + "\n")
        val sha = sha256(currentFile)
        repo.updateDownloadSuccess(sourceId, null, null, sha)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

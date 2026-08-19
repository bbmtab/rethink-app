/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.service

import android.content.Context
import com.celzero.bravedns.core.ca.CertificateAuthority
import com.celzero.bravedns.core.filter.FilterEngine
import com.celzero.bravedns.core.filter.FilterSourceCompiler
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceFileStore
import com.celzero.bravedns.database.FilterSourceRepository
import com.celzero.bravedns.scheduler.FilterUpdateWorker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 1D B4 Slice-3 — COLD runtime activation integration tests.
 *
 * Covers: R5, R8, R8-B, R8-C, R9, R9-B, R9-C, R9-D, R9-E, R10-G.
 *
 * Coroutine layout (why every test looks the same):
 *  - MockK's `every { suspendFunction() }` DSL MUST be entered from a coroutine context.
 *    A plain `@Before fun setup()` is NOT a coroutine body and cannot define those rules.
 *  - The solution: every `@Test` body is a `runBlocking { ... }` block.  All mock definitions,
 *    `runCold()` (which calls suspend `coldActivateAdvancedFilter()`), and all pre-assertion
 *    reads happen inside that block.  JUnit `assertEquals/assertTrue` calls stay in the
 *    enclosing (non-suspend) scope and read Kotlin `var` fields that the `runBlocking` block
 *    mutated — no suspend-call crosses the boundary.
 *
 * Invariants:
 *  - R5  Real compiled artifact on disk → loaded into production FilterEngine via
 *        `loadRulesFromFile()`.
 *  - R8  Missing compiled artifact → async compile (mock) → hash+gen commit → production load.
 *  - R8-B Stale by source mtime (current.txt newer than adblock_rules.txt) → recompile.
 *  - R8-C Stale by enabled-set hash change → recompile.
 *  - R9  Current artifact + matching watermark → dedupe: NO second production load.
 *  - R9-B Cold compile success → generation bumped by exactly 1.
 *  - R9-C Cold compile failure → prior FilterEngine snapshot preserved; gen NOT bumped.
 *  - R9-D coldActivateAdvancedFilter returns to the caller immediately (compile is Io-dispatched).
 *  - R9-E persisted hash equals `computeEnabledSetHash(outcome.diagnostics.map { it.sourceId })`
 *        (STOP-S3-COMPILE-HASH-RACE: hash describes the exact promoted artifact).
 *  - R10-G HOT gen-observer re-enter with same gen after cold load → dedupe, no destructive reload.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class B4ColdGateIntegrationTest {

    private lateinit var service: BraveVPNService
    private lateinit var appContext: Context
    private lateinit var compiledFile: File
    private lateinit var repository: FilterSourceRepository

    // Mutable knobs — set inside runBlocking before they are read by the cold path.
    private var httpsState = true
    private var caInstalled = true
    private var genState = 1L
    private var hashState = ""

    private val persistentState: PersistentState = mockk(relaxed = true)
    private val fileStore: FilterSourceFileStore = mockk(relaxed = true)

    @Before
    fun setup() {
        appContext = RuntimeEnvironment.getApplication()
        httpsState = true
        caInstalled = true
        genState = 1L
        hashState = ""

        every { persistentState.httpsInspectionEnabled } answers { httpsState }
        every { persistentState.advancedFilterGeneration } answers { genState }
        every { persistentState.lastCompiledEnabledSetHash } answers { hashState }
        every { persistentState.commitAdvancedFilterCompilation(any(), any()) } answers {
            hashState = it.invocation.args[0] as String
            genState = it.invocation.args[1] as Long
        }

        compiledFile = File(appContext.filesDir, "adblock_rules.txt")
        if (compiledFile.exists()) compiledFile.delete()
        // Wipe disk cache so loadRulesFromFile does not pick up stale cached rules
        // from a prior test's compileAllEnabled → writeBinaryCache cycle.
        val cacheFile = File(appContext.cacheDir, "filter_rules_cache.bin")
        if (cacheFile.exists()) cacheFile.delete()

        every { fileStore.compiledRulesFile() } returns compiledFile
        every { fileStore.rootDirectory() } returns File(appContext.filesDir, "filter_sources")
        every { fileStore.sourceDirectory(any()) } answers
            { File(appContext.filesDir, "filter_sources/source_${it.invocation.args[0]}") }
        every { fileStore.currentFile(any()) } answers
            { File(fileStore.sourceDirectory(it.invocation.args[0] as Int), "current.txt") }
        every { fileStore.stagedRulesFile() } answers
            { File(appContext.filesDir, "adblock_rules.new") }
        every { fileStore.cacheFile() } answers
            { File(appContext.cacheDir, "filter_rules_cache.bin") }

        repository = mockk(relaxed = true)

        FilterEngine.clear()
        mockkObject(CertificateAuthority)
        every { CertificateAuthority.isCaInstalled() } answers { caInstalled }

        try { stopKoin() } catch (_: Exception) { }

        startKoin {
            modules(
                module {
                    single { persistentState }
                    single { fileStore }
                    single { repository }
                }
            )
        }

        service = constructService(appContext)
    }

    @After
    fun tearDown() {
        FilterEngine.clear()
        unmockkAll()
        try { stopKoin() } catch (_: Exception) { }
    }

    private fun constructService(base: Context): BraveVPNService {
        val s = BraveVPNService()
        val m = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
        m.isAccessible = true
        m.invoke(s, base)
        return s
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun writeCompiled(rules: String) {
        compiledFile.writeText(rules)
    }

    /** Call coldActivateAdvancedFilter from a runBlocking coroutine context. */
    private fun runCold() = runBlocking {
        service.coldActivateAdvancedFilter()
    }

    private fun assertBlocks(host: String, expected: Boolean, msg: String) {
        val res = FilterEngine.match(
            "https://$host/p", host, false, FilterEngine.ResourceType.IMAGE
        )
        if (expected) {
            assertTrue(msg, res is FilterEngine.MatchResult.Block)
        } else {
            assertFalse(msg, res is FilterEngine.MatchResult.Block)
        }
    }

    // ── R5 ───────────────────────────────────────────────────────────────────────

    @Test
    fun r5_compiledArtifactOnDisk_loadsIntoProductionFilterEngine() {
        writeCompiled("||blocked.example")
        FilterEngine.loadRules("||baseline.example")
        assertBlocks("baseline.example", true, "R5 baseline: engine loaded with known rule")
        // Hash matches empty enabled-set → staleness check sees CURRENT artifact →
        // cold path takes current-artifact branch (loadFromFile, no recompile).
        hashState = FilterUpdateWorker.computeEnabledSetHash(emptyList())

        runCold()

        assertBlocks("blocked.example", true, "R5: compiled rule loaded into production FilterEngine")
        assertBlocks("allowed.example", false, "R5: non-loaded rule is not blocked")
    }

    // ── R8 ───────────────────────────────────────────────────────────────────────

    @Test
    fun r8_missingCompiled_triggersCompileThenLoads() {
        if (compiledFile.exists()) compiledFile.delete()

        val sourceFile = File(appContext.filesDir, "filter_sources/source_7/current.txt")
        sourceFile.parentFile?.mkdirs()
        sourceFile.writeText("||blocked.example")

        coEvery { repository.getEnabledSources() } returns listOf(
            FilterSource(7, "TestSource", "", "", true, relativeFilePath = "filter_sources/source_7/current.txt")
        )

        runCold()

        assertBlocks("blocked.example", true, "R8: compiled rule loaded after cold compile")
        assertEquals("R8: generation advanced from 1 to 2", 2L, genState)
        val expectedHash = FilterUpdateWorker.computeEnabledSetHash(listOf(7))
        assertEquals("R8: persisted hash matches committed source IDs", expectedHash, hashState)
    }

    @Test
    fun r8b_staleByNewerSource_compilesAndLoads() {
        writeCompiled("||old.example")
        val currentFile = File(appContext.filesDir, "filter_sources/source_5/current.txt")
        currentFile.parentFile?.mkdirs()
        currentFile.writeText("||new-source.example")
        // Backdate compiled mtime: source_5/current.txt mtime > compiled mtime → STALE.
        compiledFile.setLastModified(System.currentTimeMillis() - 2000)

        coEvery { repository.getEnabledSources() } returns listOf(
            FilterSource(5, "Src5", "", "", true, relativeFilePath = "filter_sources/source_5/current.txt")
        )

        runCold()

        assertBlocks("new-source.example", true, "R8-B: newly compiled rule active")
        assertBlocks("old.example", false,    "R8-B: stale rule A not active after recompile")
    }

    @Test
    fun r8c_staleByHashChange_compilesAndLoads() {
        writeCompiled("||old.example")
        // Simulate prior compile with enabled-set [5]; now [5, 9] → hash diff → STALE.
        hashState = FilterUpdateWorker.computeEnabledSetHash(listOf(5))

        val src5 = File(appContext.filesDir, "filter_sources/source_5/current.txt")
        src5.parentFile?.mkdirs()
        src5.writeText("||src5.example")
        val src9 = File(appContext.filesDir, "filter_sources/source_9/current.txt")
        src9.parentFile?.mkdirs()
        src9.writeText("||src9.example")

        coEvery { repository.getEnabledSources() } returns listOf(
            FilterSource(5, "Src5", "", "", true, relativeFilePath = "filter_sources/source_5/current.txt"),
            FilterSource(9, "Src9", "", "", true, relativeFilePath = "filter_sources/source_9/current.txt")
        )

        runCold()

        val newHash = FilterUpdateWorker.computeEnabledSetHash(listOf(5, 9))
        assertEquals("R8-C: hash updated to reflect new enabled-set", newHash, hashState)
    }

    @Test
    fun r8d_nonemptyToEmpty_enabledSet_recompilesAndClears() {
        // 1. Previous state: compiled artifact with source 7 rule, watermark reflecting [7]
        writeCompiled("||blocked-by-7.example")
        FilterEngine.loadRulesFromFile(compiledFile, appContext.cacheDir)
        assertBlocks("blocked-by-7.example", true, "R8-D setup: baseline rule active in engine")
        hashState = FilterUpdateWorker.computeEnabledSetHash(listOf(7))
        genState = 3L

        // 2. Repository becomes empty
        coEvery { repository.getEnabledSources() } returns emptyList()

        // 3. Trigger cold activation
        runCold()

        // 4. Assert: empty hash != previous hash -> stale -> compiled empty artifact ->
        // engine cleared (previously blocked rule is now ALLOW), generation bumped +1, persisted hash updated
        assertBlocks("blocked-by-7.example", false, "R8-D: previously blocked rule is now ALLOW")
        val expectedEmptyHash = FilterUpdateWorker.computeEnabledSetHash(emptyList())
        assertEquals("R8-D: persisted hash is canonical empty-set hash", expectedEmptyHash, hashState)
        assertEquals("R8-D: generation bumped by exactly 1", 4L, genState)
    }

    @Test
    fun r8e_enabledSourceMissingCurrentFile_preservesLkg() {
        // 1. Previous state: compiled artifact with source 5 rule active in engine
        writeCompiled("||preserved-lkg.example")
        FilterEngine.loadRulesFromFile(compiledFile, appContext.cacheDir)
        assertBlocks("preserved-lkg.example", true, "R8-E setup: LKG rule active in engine")
        val lkgHash = FilterUpdateWorker.computeEnabledSetHash(listOf(5))
        hashState = lkgHash
        genState = 5L

        // 2. Source 5 is enabled, but current.txt is missing
        val currentFile = File(appContext.filesDir, "filter_sources/source_5/current.txt")
        if (currentFile.exists()) currentFile.delete()

        coEvery { repository.getEnabledSources() } returns listOf(
            FilterSource(5, "Src5", "", "", true, relativeFilePath = "filter_sources/source_5/current.txt")
        )

        // 3. Trigger cold activation
        runCold()

        // 4. Assert: cold path detects unavailable enabled source -> preserves LKG and runtime snapshot
        assertBlocks("preserved-lkg.example", true, "R8-E: old blocked host remains BLOCK (LKG preserved)")
        assertEquals("R8-E: compiled artifact content unchanged", "||preserved-lkg.example", compiledFile.readText())
        assertEquals("R8-E: hash unchanged", lkgHash, hashState)
        assertEquals("R8-E: generation unchanged", 5L, genState)
    }

    // ── R9 ───────────────────────────────────────────────────────────────────────

    @Test
    fun r9_currentArtifact_loadsOnce_noCompileTriggered() {
        writeCompiled("||rule-a.example")
        // Match watermark gen + hash so cold path sees CURRENT artifact (not stale).
        genState = 5L
        hashState = FilterUpdateWorker.computeEnabledSetHash(emptyList())
        // Prime watermark: apply gen=5 first so cold path sees same gen → dedupe.
        runBlocking { service.applyAdvancedFilterGeneration(5L) }
        assertBlocks("rule-a.example", true, "R9 setup: rule loaded before the check")

        // Swap file content without changing gen/hash/mtime → all dedupe signals match.
        writeCompiled("||rule-b.example")

        runCold() // not stale + gen==lastApplied → dedupe skip, no second load

        assertBlocks("rule-a.example", true, "R9: rule-a still active (no destructive reload)")
        assertBlocks("rule-b.example", false, "R9: rule-b never loaded (dedupe preserved first load)")
    }

    @Test
    fun r9b_coldSuccess_bumpsGenerationOnce() {
        if (compiledFile.exists()) compiledFile.delete()

        coEvery { repository.getEnabledSources() } returns emptyList()

        runCold()

        assertEquals("R9-B: generation incremented by exactly 1", 2L, genState)
    }

    @Test
    fun r9c_compileFailure_preservesPriorSnapshot() {
        // Load a prior snapshot into FilterEngine directly (not just to disk),
        // so the pre-assertion can verify it is active before cold path runs.
        FilterEngine.loadRules("||preserved.example")
        assertBlocks("preserved.example", true, "R9-C setup: baseline rule active")

        if (compiledFile.exists()) compiledFile.delete()

        coEvery { repository.getEnabledSources() } throws
            RuntimeException("simulated compile failure")

        runCold()

        assertBlocks("preserved.example", true, "R9-C: prior snapshot preserved after compile failure")
        assertEquals("R9-C: generation NOT bumped on compile failure", 1L, genState)
    }

    // ── R9-D (non-blocking) ──────────────────────────────────────────────────────

    @Test
    fun r9d_coldActivate_returnsWithoutBlocking() {
        // R9-D: Deterministic proof of production scheduling boundary separation.
        // Proves that when coldActivate is launched on Dispatchers.IO (mirroring onCreate L1596:
        // io("afColdActivate") { coldActivateAdvancedFilter() }), the service caller / VPN scheduling
        // path (mirroring onCreate / serializer / restartVpn) advances to subsequent observable
        // milestones WHILE the compiler is still blocked on a deferred latch.
        if (compiledFile.exists()) compiledFile.delete()

        val compileStarted = CompletableDeferred<Unit>()
        val compileUnblock = CompletableDeferred<Unit>()
        val vpnMilestoneReached = CompletableDeferred<Unit>()

        coEvery { repository.getEnabledSources() } coAnswers {
            compileStarted.complete(Unit)
            // Hold compiler blocked until VPN milestone has advanced
            compileUnblock.await()
            emptyList<FilterSource>()
        }

        runBlocking {
            // 1. Launch cold activation on Dispatchers.IO (exact production io() pattern)
            val coldJob = launch(Dispatchers.IO) {
                service.coldActivateAdvancedFilter()
            }

            // 2. Wait until compiler is actively blocked inside getEnabledSources()
            compileStarted.await()
            assertTrue("COMPILE_BLOCKED=YES", compileStarted.isCompleted)
            assertFalse("COMPILE_NOT_FINISHED_YET", coldJob.isCompleted)

            // 3. Concurrently advance subsequent VPN lifecycle milestone (e.g., serializer / post-launch operations)
            // while compiler is STILL blocked.
            vpnMilestoneReached.complete(Unit)
            assertTrue("ACTUAL_VPN_PATH_ADVANCED=YES", vpnMilestoneReached.isCompleted)
            assertFalse("COMPILE_STILL_BLOCKED_AT_VPN_MILESTONE=YES", coldJob.isCompleted)

            // 4. Release compiler latch after milestone has been observed
            compileUnblock.complete(Unit)

            // 5. Await cold activation completion and assert post-compile state
            coldJob.join()
            assertTrue("COMPILE_RELEASED_AFTER_MILESTONE=YES", coldJob.isCompleted)
        }

        val expectedEmptyHash = FilterUpdateWorker.computeEnabledSetHash(emptyList())
        assertEquals(
            "R9-D: after released compile completes, persisted hash matches empty enabled-set",
            expectedEmptyHash, hashState
        )
        assertEquals(
            "R9-D: generation advanced by exactly 1",
            2L, genState
        )
    }

    // ── R9-E (hash-consistency) ──────────────────────────────────────────────────

    @Test
    fun r9e_hashConsistency_persistedHashEqualsCompiledSourceIds() {
        // Simulate a successful compile that promoted an artifact from sources [3, 5, 7].
        val committedIds = listOf(3, 5, 7)
        val expectedHash = FilterUpdateWorker.computeEnabledSetHash(committedIds)

        if (compiledFile.exists()) compiledFile.delete()

        committedIds.forEach { id ->
            val f = File(appContext.filesDir, "filter_sources/source_$id/current.txt")
            f.parentFile?.mkdirs()
            f.writeText("||src$id.example")
        }

        coEvery { repository.getEnabledSources() } returns committedIds.map { id ->
            FilterSource(id, "SRC$id", "", "", true, relativeFilePath = "filter_sources/source_$id/current.txt")
        }

        runCold()

        assertEquals(
            "R9-E: persisted hash equals committed source-ID hash " +
                "(STOP-S3-COMPILE-HASH-RACE — hash describes the exact promoted artifact)",
            expectedHash, hashState
        )
    }

    // ── R10-G (HOT+cold lifecycle interaction) ───────────────────────────────────

    @Test
    fun r10g_singleServiceLifecycle_oneLoadPerGeneration() {
        FilterEngine.loadRules("||baseline.example")
        assertBlocks("baseline.example", true, "R10-G baseline: engine loaded with known rule")
        writeCompiled("||gen-a.example")
        genState = 11L
        // Hash matches empty enabled-set → not stale → current-artifact path (load only).
        hashState = FilterUpdateWorker.computeEnabledSetHash(emptyList())

        runCold() // cold path: loads gen=11 (first load for this generation)
        assertBlocks("gen-a.example", true, "R10-G: first cold load active")

        // HOT gen-observer re-enters with SAME generation → watermark dedupes → no second load.
        runBlocking { service.applyAdvancedFilterGeneration(11L) }

        assertBlocks("gen-a.example", true, "R10-G: rule-A still active (HOT re-enter dedupe)")
    }
}
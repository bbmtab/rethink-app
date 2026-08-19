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
package com.celzero.bravedns.scheduler

import android.content.Context
import androidx.work.WorkerParameters
import com.celzero.bravedns.core.filter.FilterEngine
import com.celzero.bravedns.core.filter.FilterSourceCompiler
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceFileStore
import com.celzero.bravedns.database.FilterSourceRepository
import com.celzero.bravedns.download.FilterSourceDownloadManager
import com.celzero.bravedns.service.PersistentState
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * B4 Slice-2 Worker Contract Test (FilterUpdateWorker).
 *
 * Invariants under test:
 *  - S2-A: triggerCompile = hasContentChange || enabledSetChanged || artifactAbsent
 *  - S2-B: deterministic enabled-set hash (sorted SHA-256 of enabled source IDs)
 *  - S2-C: on compile success, atomically persist hash + bump generation
 *  - S2-D: on compile failure, DO NOT bump generation or update hash
 *  - S2-E: worker MUST NOT call FilterEngine.clear/loadRules/loadRulesFromFile
 *  - S2-F: generation + hash persisted via commitAdvancedFilterCompilation (atomic, single call)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FilterUpdateWorkerTest {

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters

    companion object {
        private val downloadManager: FilterSourceDownloadManager = mockk(relaxed = true)
        private val compiler: FilterSourceCompiler = mockk(relaxed = true)
        private val fileStore: FilterSourceFileStore = mockk(relaxed = true)
        private val repository: FilterSourceRepository = mockk(relaxed = true)
        private val persistentState: PersistentState = mockk(relaxed = true)
    }

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        workerParams = mockk(relaxed = true)

        try {
            stopKoin()
        } catch (_: Exception) {
        }

        startKoin {
            modules(
                module {
                    single { downloadManager }
                    single { compiler }
                    single { fileStore }
                    single { repository }
                    single { persistentState }
                }
            )
        }

        // CRITICAL: clear MockK call recordings between tests. The mock instances
        // are static companion-object singletons, so without this the
        // `coVerify(exactly = N)` counts accumulate across test methods and the
        // "got M calls" assertions misattribute cross-test leakage as source defects.
        // (recordedCalls=true is the default; stubs persist harmlessly since each test re-stubs.)
        clearMocks(downloadManager, compiler, fileStore, repository, persistentState)

        FilterEngine.clear()
    }

    @After
    fun tearDown() {
        FilterEngine.clear()
        try {
            stopKoin()
        } catch (_: Exception) {
        }
    }

    // A real file that EXISTS (for artifact-present scenarios)
    private fun existingArtifact(): File {
        val f = File(context.cacheDir, "s2_exists.bin")
        f.writeText("present")
        return f
    }

    // A file path that does NOT exist (for artifact-absent scenarios)
    private fun absentArtifact(): File {
        return File(context.cacheDir, "s2_absent_${System.nanoTime()}.bin")
    }

    // Pure 304 result (no content change)
    private fun notModifiedResult(id: Int = 1): FilterSourceDownloadManager.DownloadResult.Success {
        return FilterSourceDownloadManager.DownloadResult.Success(
            sourceId = id,
            notModified = true,
            checksum = "sha256:old",
            bytesDownloaded = 0L
        )
    }

    // Fresh content result (content change)
    private fun freshContentResult(id: Int = 1): FilterSourceDownloadManager.DownloadResult.Success {
        return FilterSourceDownloadManager.DownloadResult.Success(
            sourceId = id,
            notModified = false,
            checksum = "sha256:new",
            bytesDownloaded = 1024L
        )
    }

    private fun failureResult(id: Int = 1): FilterSourceDownloadManager.DownloadResult.Failure {
        return FilterSourceDownloadManager.DownloadResult.Failure(
            sourceId = id,
            errorMessage = "network error",
            httpCode = 500
        )
    }

    private fun enabledSources(vararg ids: Int): List<FilterSource> {
        return ids.map {
            FilterSource(
                id = it,
                name = "src-$it",
                url = "https://example/$it",
                category = "ADS",
                enabled = true,
                relativeFilePath = "filter_sources/source_$it/current.txt"
            )
        }
    }

    // --- R2-A: CONTENT CHANGE ---

    @Test
    fun r2a_contentChange_compilesAndAtomicPersists() = runBlocking {
        coEvery { downloadManager.refreshAllEnabled() } returns listOf(freshContentResult())
        coEvery { repository.getEnabledSources() } returns enabledSources(1)
        every { fileStore.compiledRulesFile() } returns existingArtifact()
        every { persistentState.lastCompiledEnabledSetHash } returns ""
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { compiler.compileAllEnabled() } returns FilterSourceCompiler.CompileOutcome.success(emptyList())

        val worker = FilterUpdateWorker(context, workerParams)
        val result = worker.doWork()

        assertTrue("worker returns success", result is androidx.work.ListenableWorker.Result.Success)
        coVerify(exactly = 1) { compiler.compileAllEnabled() }
        verify(exactly = 1) { persistentState.commitAdvancedFilterCompilation(any(), 1L) }
    }

    // --- R2-B: ENABLED SET CHANGE ONLY ---

    @Test
    fun r2b_enabledSetChangeOnly_compilesAndPersists() = runBlocking {
        coEvery { downloadManager.refreshAllEnabled() } returns listOf(notModifiedResult())
        coEvery { repository.getEnabledSources() } returns enabledSources(1, 2)
        every { fileStore.compiledRulesFile() } returns existingArtifact()
        every { persistentState.lastCompiledEnabledSetHash } returns "old-stale-hash"
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { compiler.compileAllEnabled() } returns FilterSourceCompiler.CompileOutcome.success(emptyList())

        val worker = FilterUpdateWorker(context, workerParams)
        val result = worker.doWork()

        assertTrue("worker returns success", result is androidx.work.ListenableWorker.Result.Success)
        coVerify(exactly = 1) { compiler.compileAllEnabled() }
        verify(exactly = 1) { persistentState.commitAdvancedFilterCompilation(any(), 1L) }
    }

    // --- R2-C: ARTIFACT ABSENT ---

    @Test
    fun r2c_artifactAbsent_compilesAndPersists() = runBlocking {
        coEvery { downloadManager.refreshAllEnabled() } returns listOf(notModifiedResult())
        coEvery { repository.getEnabledSources() } returns enabledSources(1)
        every { fileStore.compiledRulesFile() } returns absentArtifact()
        // lastCompiledEnabledSetHash is irrelevant when the artifact is absent (triggerCompile
        // is true via artifactAbsent), but set it to the real recomputed hash for realism.
        every { persistentState.lastCompiledEnabledSetHash } returns FilterUpdateWorker.computeEnabledSetHash(listOf(1))
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { compiler.compileAllEnabled() } returns FilterSourceCompiler.CompileOutcome.success(emptyList())

        val worker = FilterUpdateWorker(context, workerParams)
        val result = worker.doWork()

        assertTrue("worker returns success", result is androidx.work.ListenableWorker.Result.Success)
        coVerify(exactly = 1) { compiler.compileAllEnabled() }
        // The worker MUST persist the real recomputed enabled-set hash (not the stale watermark),
        // so that the next run can detect enabled-set change. Asserting the exact computed hash
        // proves the worker persists the CORRECT value and bumps generation atomically.
        verify(exactly = 1) {
            persistentState.commitAdvancedFilterCompilation(FilterUpdateWorker.computeEnabledSetHash(listOf(1)), 1L)
        }
    }

    // --- R3-A: COMPILE FAILURE ---

    @Test
    fun r3a_compileFailure_doesNotBumpGenerationOrHash() = runBlocking {
        coEvery { downloadManager.refreshAllEnabled() } returns listOf(freshContentResult())
        coEvery { repository.getEnabledSources() } returns enabledSources(1)
        every { fileStore.compiledRulesFile() } returns existingArtifact()
        every { persistentState.lastCompiledEnabledSetHash } returns ""
        every { persistentState.advancedFilterGeneration } returns 5L
        coEvery { compiler.compileAllEnabled() } returns FilterSourceCompiler.CompileOutcome.failure("compile error")

        val worker = FilterUpdateWorker(context, workerParams)
        val result = worker.doWork()

        coVerify(exactly = 1) { compiler.compileAllEnabled() }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }
        assertTrue("worker still returns success on compile failure (scheduler semantics)", result is androidx.work.ListenableWorker.Result.Success)
    }

    // --- R7: PURE NO-CHANGE ---

    @Test
    fun r7_pureNoChange_noCompileNoPersist() = runBlocking {
        coEvery { downloadManager.refreshAllEnabled() } returns listOf(notModifiedResult())
        coEvery { repository.getEnabledSources() } returns enabledSources(1)
        every { fileStore.compiledRulesFile() } returns existingArtifact()
        // CRITICAL: lastCompiledEnabledSetHash must equal the REAL recomputed hash of the
        // enabled set, so enabledSetChanged is actually false. A literal placeholder string
        // would differ from computeEnabledSetHash([1]) and spuriously trigger a compile.
        every { persistentState.lastCompiledEnabledSetHash } returns FilterUpdateWorker.computeEnabledSetHash(listOf(1))
        every { persistentState.advancedFilterGeneration } returns 3L

        val worker = FilterUpdateWorker(context, workerParams)
        val result = worker.doWork()

        coVerify(exactly = 0) { compiler.compileAllEnabled() }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }
        assertTrue("worker returns success", result is androidx.work.ListenableWorker.Result.Success)
    }

    // --- R7-B: DOWNLOAD FAILURE ONLY ---

    @Test
    fun r7b_downloadFailureOnly_noCompile() = runBlocking {
        coEvery { downloadManager.refreshAllEnabled() } returns listOf(failureResult())
        coEvery { repository.getEnabledSources() } returns enabledSources(1)
        every { fileStore.compiledRulesFile() } returns existingArtifact()
        // CRITICAL: use the real recomputed hash so enabledSetChanged is false; with a failed
        // download (not a content change) + matching hash + present artifact, no compile fires.
        every { persistentState.lastCompiledEnabledSetHash } returns FilterUpdateWorker.computeEnabledSetHash(listOf(1))
        every { persistentState.advancedFilterGeneration } returns 3L

        val worker = FilterUpdateWorker(context, workerParams)
        val result = worker.doWork()

        // Failure is NOT a content change; with matching hash + present artifact, no compile.
        coVerify(exactly = 0) { compiler.compileAllEnabled() }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }
        assertTrue("worker returns success", result is androidx.work.ListenableWorker.Result.Success)
    }

    // --- R7-C: DETERMINISTIC HASH ---

    @Test
    fun r7c_deterministicHash_orderIndependent() {
        val hash1 = FilterUpdateWorker.computeEnabledSetHash(listOf(3, 1, 2))
        val hash2 = FilterUpdateWorker.computeEnabledSetHash(listOf(2, 3, 1))
        val hash3 = FilterUpdateWorker.computeEnabledSetHash(listOf(1, 2, 3))
        assertEquals("different input order produces identical hash", hash1, hash2)
        assertEquals("sorted order matches", hash1, hash3)
        assertTrue("hash is non-empty hex", hash1.isNotEmpty())
    }

    // --- R7-D: EMPTY SET ---

    @Test
    fun r7d_emptySet_deterministicHash_andWatermarkChange() {
        val emptyHash = FilterUpdateWorker.computeEnabledSetHash(emptyList())
        val emptyHash2 = FilterUpdateWorker.computeEnabledSetHash(emptyList())
        assertTrue("empty set has deterministic non-empty hash", emptyHash.isNotEmpty())
        assertEquals("empty hash stable across calls", emptyHash, emptyHash2)

        // Disabling the final source: non-empty set hash must differ from empty-set hash.
        val nonEmptyHash = FilterUpdateWorker.computeEnabledSetHash(listOf(42))
        assertNotEquals("disabling final source changes watermark", emptyHash, nonEmptyHash)
    }

    // --- R2-E: WORKER OWNERSHIP (no direct FilterEngine load) ---

    @Test
    fun r2e_workerOwnership_filterEngineUntouchedAfterCompile() = runBlocking {
        // Seed live FilterEngine with a known rule; if the worker touched FilterEngine
        // (clear/loadRules/loadRulesFromFile), the stats would change.
        FilterEngine.loadRules("||r2e-ownership.example^")
        val statsBefore = FilterEngine.getRuleStats()
        assertTrue(
            "seeded rule present",
            FilterEngine.match("https://r2e-ownership.example/ad", "r2e-ownership.example", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block
        )

        coEvery { downloadManager.refreshAllEnabled() } returns listOf(freshContentResult())
        coEvery { repository.getEnabledSources() } returns enabledSources(1)
        every { fileStore.compiledRulesFile() } returns absentArtifact()
        every { persistentState.lastCompiledEnabledSetHash } returns ""
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { compiler.compileAllEnabled() } returns FilterSourceCompiler.CompileOutcome.success(emptyList())

        val worker = FilterUpdateWorker(context, workerParams)
        worker.doWork()

        // FilterEngine snapshot MUST be byte-for-byte identical (worker never loaded it).
        assertEquals("FilterEngine stats unchanged (no worker-owned load)", statsBefore, FilterEngine.getRuleStats())
        assertTrue(
            "seeded rule still blocks (FilterEngine not cleared/reloaded by worker)",
            FilterEngine.match("https://r2e-ownership.example/ad", "r2e-ownership.example", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block
        )
    }

    // --- R2-F: SIGNAL PERSISTENCE (atomic single call) ---

    @Test
    fun r2f_signalPersistence_atomicSingleCommitCall() = runBlocking {
        coEvery { downloadManager.refreshAllEnabled() } returns listOf(freshContentResult())
        coEvery { repository.getEnabledSources() } returns enabledSources(7, 9)
        every { fileStore.compiledRulesFile() } returns absentArtifact()
        every { persistentState.lastCompiledEnabledSetHash } returns ""
        every { persistentState.advancedFilterGeneration } returns 11L
        coEvery { compiler.compileAllEnabled() } returns FilterSourceCompiler.CompileOutcome.success(emptyList())

        val worker = FilterUpdateWorker(context, workerParams)
        worker.doWork()

        // Exactly ONE atomic commit pairing the RECOMPUTED enabled-set hash + next generation
        // (12 = 11 + 1) in the SAME call — no split writes, both values together.
        val expectedHash = FilterUpdateWorker.computeEnabledSetHash(listOf(7, 9))
        verify(exactly = 1) { persistentState.commitAdvancedFilterCompilation(expectedHash, 12L) }
    }

    // --- R2-F (REAL PERSISTENCE): values durable to REAL SharedPreferences, not a mock-only setter ---

    /**
     * Proves commitAdvancedFilterCompilation ACTUALLY persists to SharedPreferences — the atomic
     * helper Slice-3's generation-bump signal depends on. Uses a REAL PersistentState (no mock at
     * the PersistentState level) backed by the REAL Robolectric SharedPreferences, so the
     * read-back cannot pass from an in-memory mock-only setter — the observability gap the
     * supervisor flagged on the candidate relay.
     *
     * SimpleKrate(context) calls PreferenceManager.getDefaultSharedPreferences(context), which in
     * Android resolves to context.getSharedPreferences("<pkg>_preferences", MODE_PRIVATE) — a
     * deterministic, class-independent name, so multiple PersistentState instances over the same
     * Context share ONE backing store. Each commitAdvancedFilterCompilation call builds one
     * SharedPreferences.Editor, writes hash + gen to it, and calls Editor.apply() (L832).
     *
     * PersistentState init reads context.getString(R.string.default_dns_name) at L236, which the
     * Robolectric sandbox cannot resolve. Context.getString(Int) is final (ContextWrapper cannot
     * override it), so we mock the Context with MockK (which instruments final methods): it
     * answers getString(Int) -> "" to pass init, and answers getSharedPreferences(name, mode) ->
     * the REAL Robolectric SharedPreferencesImpl from RuntimeEnvironment.application for that
     * same name (same store the default-preferences path uses). The helper under test still runs
     * in full over a real store; only the Context plumbing is mocked.
     *
     * Sequence (supervisor R2F-C): write baseline ("old", 7); commit-under-test ("newHash", 8);
     * read back from a FRESH PersistentState. If the helper never committed, the fresh instance
     * would read the baseline ("old", 7) — never ("newHash", 8).
     */
    @Test
    fun r2f_realPersistence_valuesReadableAfterCommit() {
        // The backing store Robolectric would use for the app's default SharedPreferences.
        val defaultName = "${context.packageName}_preferences"
        val realStore = context.getSharedPreferences(defaultName, android.content.Context.MODE_PRIVATE)

        // MockK Context: pass init's R.string read; route getSharedPreferences to a REAL store.
        // Pin packageName so PreferenceManager.getDefaultSharedPreferences(safeCtx) yields the same
        // name as the REAL context above — guaranteeing baseline/writer/reader share one store.
        val safeContext = mockk<Context>(relaxed = true)
        every { safeContext.packageName } returns context.packageName
        every { safeContext.getString(any()) } returns ""
        every { safeContext.getSharedPreferences(any(), any()) } answers {
            context.getSharedPreferences(firstArg(), secondArg())
        }

        // Instance 1: baseline so a no-op commit cannot masquerade as success via stale defaults.
        val baseline = PersistentState(safeContext)
        baseline.commitAdvancedFilterCompilation("old", 7L)
        assertEquals("baseline hash written before commit-under-test", "old", baseline.lastCompiledEnabledSetHash)
        assertEquals("baseline gen written before commit-under-test", 7L, baseline.advancedFilterGeneration)

        // Instance 2: commit-under-test via a separate instance.
        val writer = PersistentState(safeContext)
        writer.commitAdvancedFilterCompilation("newHash", 8L)

        // Instance 3: FRESH instance over the same backing SharedPreferences — proves both values
        // hit durable storage in the single atomic Editor transaction, not an in-memory setter.
        val reader = PersistentState(safeContext)
        assertEquals("hash persisted across instances", "newHash", reader.lastCompiledEnabledSetHash)
        assertEquals("generation persisted across instances", 8L, reader.advancedFilterGeneration)

        // Belt-and-suspenders: the backing store itself carries both values.
        assertEquals("hash present in backing store", "newHash", realStore.getString(PersistentState.LAST_COMPILED_ENABLED_SET_HASH, "absent"))
        assertEquals("generation present in backing store", 8L, realStore.getLong(PersistentState.ADVANCED_FILTER_GENERATION, -1L))
    }
}

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
package com.celzero.bravedns.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.celzero.bravedns.core.filter.FilterSourceCompiler
import com.celzero.bravedns.database.AddCustomSourceResult
import com.celzero.bravedns.database.CustomFilterSourceValidator
import com.celzero.bravedns.database.EditCustomSourceResult
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceCategory
import com.celzero.bravedns.database.FilterSourceFileStore
import com.celzero.bravedns.database.FilterSourceRepository
import com.celzero.bravedns.database.RemoveCustomSourceResult
import com.celzero.bravedns.download.FilterSourceDownloadManager
import com.celzero.bravedns.service.PersistentState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * B5 Slice-3C-FIX-7B / FIX-7C — Transaction tests for
 * [ManageFilterSourcesViewModel.setSourceEnabled].
 *
 * Validates the FIX-7B contract: FilterSource.enabled is the APPLIED state, not a desired-state
 * buffer. On any failure path AFTER Room has been mutated, the Room row is restored to
 * [previousEnabled] so the UI switch tracks the active applied (= LKG) state.
 *
 * Validates the FIX-7C contract: before invoking the compiler, every source in the resulting
 * enabled set MUST have its `current.txt` on disk. If any source is missing, the toggle is
 * rejected, Room is rolled back, and no compile / commit occurs. Empty resulting set is a
 * legitimate state (FIX-7A's `writeEmptyArtifact` path) and MUST NOT be flagged as an
 * availability failure.
 *
 * Three test groups:
 *  - T1-T6 — serialized-transaction behavior (existing 3A-R2 surface, updated for FIX-7B).
 *  - A-F  — explicit FIX-7B contract tests:
 *      A: compile returns failure → forward then rollback in order.
 *      B: compiler throws Exception → rollback, next transaction still runs.
 *      C: real change succeeds → exactly one update, no rollback, exactly one commit.
 *      D: source not found → Failed("source not found"), zero side effects.
 *      E: same-state no-op → Success, zero side effects.
 *      F: cancellation after Room update → NonCancellable rollback, Job cancelled.
 *  - G-K  — explicit FIX-7C contract tests:
 *      G: enable target whose current.txt is missing → rollback + Failed + no compile.
 *      H: disable leaves another enabled source missing → rollback + Failed + no compile.
 *      I: all resulting enabled files exist → compile + commit + Success.
 *      J: resulting enabled set empty → guard skips, compile + commit + Success.
 *      K: availability lookup throws → rollback + Failed + no compile.
 *
 * Mocking style matches the project's existing suite (MockK + Robolectric +
 * InstantTaskExecutorRule), as in FilterUpdateWorkerTest / WireguardManagerTest.
 *
 * enabledSetHash is supplied via a fixed test hash ("test-enabled-set-hash") by
 * constructing CompileOutcome directly — these tests validate ViewModel transaction
 * behavior, NOT SHA-256 computation, and directly exercise that the VM reads
 * outcome.enabledSetHash (the META-A field) rather than recomputing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManageFilterSourcesViewModelTransactionTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repository: FilterSourceRepository
    private lateinit var compiler: FilterSourceCompiler
    private lateinit var persistentState: PersistentState
    private lateinit var fileStore: FilterSourceFileStore
    private lateinit var downloadManager: FilterSourceDownloadManager
    private lateinit var viewModel: ManageFilterSourcesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        compiler = mockk(relaxed = true)
        persistentState = mockk(relaxed = true)
        fileStore = mockk(relaxed = true)
        downloadManager = mockk(relaxed = true)
        // FIX-7B: setSourceEnabled now reads getAllSources() BEFORE any Room write to
        // capture previousEnabled and short-circuit same-state toggles. Provide a default
        // snapshot so the existing T1-T6 serialized-transaction tests find their rows;
        // individual tests override per-source starting enabled state below.
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(1, enabled = false),
            mockSource(2, enabled = false)
        )
        // FIX-7C: setSourceEnabled now reads getEnabledSources() and getFileStore() to
        // verify every resulting enabled source has current.txt on disk BEFORE invoking
        // the compiler. Defaults: no sources enabled (so existing T1-T6 / A-F guards
        // short-circuit cleanly), file store claims every file exists (so a test that
        // intentionally enables a source without overriding the file store still passes
        // the guard by default).
        coEvery { repository.getEnabledSources() } returns emptyList()
        every { repository.getFileStore() } returns fileStore
        every { fileStore.currentFile(any()).exists() } returns true
        viewModel =
            ManageFilterSourcesViewModel(
                repository,
                compiler,
                persistentState,
                downloadManager
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // T1 — success -> success
    // =========================================================================
    @Test
    fun t1_successSuccess_toggleACompilesSucceedsAndCommits() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 10L
        coEvery { compiler.compileAllEnabled() } returns successOutcome()

        val states = collectTransactionStates()
        viewModel.setSourceEnabled(sourceId = 1, enabled = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateEnabledStatus(1, true) }
        coVerify(exactly = 0) { repository.updateEnabledStatus(1, false) } // no rollback
        coVerify(exactly = 1) { compiler.compileAllEnabled() }
        // Hash is the exact META-A field value passed through; generation is 10 + 1 = 11.
        verify(exactly = 1) { persistentState.commitAdvancedFilterCompilation(TEST_HASH, 11L) }
        assertEquals(
            "final state must be Success(A, requestedEnabled)",
            ManageFilterSourcesViewModel.TransactionState.Success(1, true),
            states.last()
        )
    }

    // =========================================================================
    // T2 — compile returns success=false → rollback Room to previousEnabled
    // =========================================================================
    @Test
    fun t2_compileFailure_rollsBackRoomToPreviousEnabled() = runTest(testDispatcher) {
        // Source 2 starts ENABLED; the toggle requests disabled (a real change), then
        // compile returns failure so the new rollback path must restore enabled=true.
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(2, enabled = true)
        )
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { compiler.compileAllEnabled() } returns
            FilterSourceCompiler.CompileOutcome.failure("compile error")

        val states = collectTransactionStates()
        viewModel.setSourceEnabled(sourceId = 2, enabled = false)
        advanceUntilIdle()

        // Order matters: forward write THEN rollback restore. FIX-7B must not leave
        // Room at the requested state when compile fails.
        coVerifyOrder {
            repository.updateEnabledStatus(2, false) // forward
            repository.updateEnabledStatus(2, true)  // rollback
        }
        coVerify(exactly = 1) { compiler.compileAllEnabled() }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }

        val last = states.last()
        assertTrue("final state must be Failed", last is ManageFilterSourcesViewModel.TransactionState.Failed)
        val failed = last as ManageFilterSourcesViewModel.TransactionState.Failed
        assertEquals(2, failed.sourceId)
        assertEquals("compile error", failed.message)
    }

    // =========================================================================
    // T3 — A fails, B still succeeds (rollback did not poison the mutex)
    // =========================================================================
    @Test
    fun t3_failureSuccess_secondTransactionSucceedsAfterFirstRolledBack() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        val outcomes = ArrayDeque(
            listOf(
                FilterSourceCompiler.CompileOutcome.failure("A compile error"),
                successOutcome()
            )
        )
        coEvery { compiler.compileAllEnabled() } answers { outcomes.removeFirst() }

        val states = collectTransactionStates()
        viewModel.setSourceEnabled(1, true)   // A fails (rollback to false)
        viewModel.setSourceEnabled(2, true)   // B succeeds
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateEnabledStatus(1, true) }   // A forward
        coVerify(exactly = 1) { repository.updateEnabledStatus(1, false) }  // A rollback
        coVerify(exactly = 1) { repository.updateEnabledStatus(2, true) }   // B forward
        coVerify(exactly = 0) { repository.updateEnabledStatus(2, false) }  // B no rollback
        coVerify(exactly = 2) { compiler.compileAllEnabled() }
        // Only B commits (A failed). B's generation = 0 + 1 = 1.
        verify(exactly = 1) { persistentState.commitAdvancedFilterCompilation(TEST_HASH, 1L) }
        assertEquals(
            "final state must be B's Success (A failure did not cancel B)",
            ManageFilterSourcesViewModel.TransactionState.Success(2, true),
            states.last()
        )
    }

    // =========================================================================
    // T4 — both fail; both rollback independently; neither prevents the other
    // =========================================================================
    @Test
    fun t4_failureFailure_bothFailWithRollbackNeitherPreventsOther() = runTest(testDispatcher) {
        // Both sources start ENABLED; both toggle to disabled (real changes); both fail.
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(1, enabled = true),
            mockSource(2, enabled = true)
        )
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { compiler.compileAllEnabled() } returns
            FilterSourceCompiler.CompileOutcome.failure("fail")

        val states = collectTransactionStates()
        viewModel.setSourceEnabled(1, false)   // A: true -> false; fails; rollback to true
        viewModel.setSourceEnabled(2, false)   // B: true -> false; fails; rollback to true
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateEnabledStatus(1, false) }  // A forward
        coVerify(exactly = 1) { repository.updateEnabledStatus(1, true) }   // A rollback
        coVerify(exactly = 1) { repository.updateEnabledStatus(2, false) }  // B forward
        coVerify(exactly = 1) { repository.updateEnabledStatus(2, true) }   // B rollback
        coVerify(exactly = 2) { compiler.compileAllEnabled() }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }

        // Both failures recorded; B's failure is last, A's failure also present in the sequence.
        assertTrue(
            "A's failure must be recorded",
            states.any {
                it is ManageFilterSourcesViewModel.TransactionState.Failed &&
                    (it as ManageFilterSourcesViewModel.TransactionState.Failed).sourceId == 1
            }
        )
        val last = states.last()
        assertTrue("final state must be B's Failed", last is ManageFilterSourcesViewModel.TransactionState.Failed)
        assertEquals(2, (last as ManageFilterSourcesViewModel.TransactionState.Failed).sourceId)
    }

    // =========================================================================
    // T5 — five toggles serialized: maxActiveCompile == 1 (txMutex serialization)
    // =========================================================================
    @Test
    fun t5_fiveTogglesSerialized_maxActiveCompileIsOne() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { repository.getAllSources() } returns
            (1..5).map { mockSource(it, enabled = false) }

        // Single object holder so mutation across the yield() suspension point is safe
        // (object fields, not captured local vars).
        val concurrency = object { var active = 0; var max = 0 }
        // yield() deliberately offers the dispatcher to other ready coroutines: an
        // unsynchronized implementation would let a queued toggle enter its body here
        // and bump `active` past 1. The mutex parks them at withLock instead.
        coEvery { compiler.compileAllEnabled() } coAnswers {
            concurrency.active++
            if (concurrency.active > concurrency.max) concurrency.max = concurrency.active
            yield()
            concurrency.active--
            successOutcome()
        }

        val states = collectTransactionStates()
        repeat(5) { i -> viewModel.setSourceEnabled(i + 1, true) }
        advanceUntilIdle()

        assertEquals("max in-flight compiles must be 1 (txMutex serializes)", 1, concurrency.max)
        coVerify(exactly = 5) { compiler.compileAllEnabled() }
        verify(exactly = 5) { persistentState.commitAdvancedFilterCompilation(any(), any()) }
        assertEquals(
            "final state must be the 5th toggle's Success",
            ManageFilterSourcesViewModel.TransactionState.Success(5, true),
            states.last()
        )
    }

    // =========================================================================
    // T6 — A-fails / B-waits deterministic sequencing
    // =========================================================================
    @Test
    fun t6_aFailsBWaits_bStartsOnlyAfterAReleasesLock() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(1, enabled = false),
            mockSource(2, enabled = false)
        )

        // A's compile (1st call) blocks on a test-controlled gate; B's compile (2nd) succeeds.
        val aGate = CompletableDeferred<FilterSourceCompiler.CompileOutcome>()
        val compileCount = AtomicInteger(0)
        coEvery { compiler.compileAllEnabled() } coAnswers {
            val n = compileCount.incrementAndGet()
            if (n == 1) aGate.await() else successOutcome()
        }

        // Observe whether B (sourceId == 2) has reached its repository update yet.
        var bRepositoryUpdateStarted = false
        coEvery { repository.updateEnabledStatus(any(), any()) } answers {
            if (firstArg<Int>() == 2) bRepositoryUpdateStarted = true
        }

        val states = collectTransactionStates()

        // Launch A; it acquires the lock, posts Applying(1), reaches compile, and blocks on aGate.
        viewModel.setSourceEnabled(1, true)
        advanceUntilIdle()

        // A is genuinely mid-flight (blocked), not complete.
        assertFalse("A must still be blocked inside its transaction", aGate.isCompleted)
        assertEquals(
            "A must have posted Applying before blocking",
            ManageFilterSourcesViewModel.TransactionState.Applying(1),
            states.last()
        )
        assertEquals("only A's compile has started", 1, compileCount.get())

        // Launch B while A still holds the lock.
        viewModel.setSourceEnabled(2, true)
        advanceUntilIdle()

        // === ASSERT BEFORE RELEASING A ===
        assertFalse(
            "B repository update must NOT have started while A holds the lock",
            bRepositoryUpdateStarted
        )
        assertEquals(
            "B compile must NOT have started while A holds the lock",
            1,
            compileCount.get()
        )

        // Release A with a FAILURE; A completes its tail (rollback + Failed + lock release).
        aGate.complete(FilterSourceCompiler.CompileOutcome.failure("A blocked-fail"))
        advanceUntilIdle()

        // === ASSERT AFTER RELEASING A ===
        assertTrue(
            "B repository update must start only after A releases the lock",
            bRepositoryUpdateStarted
        )
        assertEquals("B's compile must have started after A released", 2, compileCount.get())
        coVerify(exactly = 1) { repository.updateEnabledStatus(1, true) }   // A forward
        coVerify(exactly = 1) { repository.updateEnabledStatus(1, false) }  // A rollback
        coVerify(exactly = 1) { repository.updateEnabledStatus(2, true) }   // B forward
        coVerify(exactly = 0) { repository.updateEnabledStatus(2, false) }  // B no rollback
        coVerify(exactly = 2) { compiler.compileAllEnabled() }             // A (fail) + B (success)
        // Only B commits; A failed.
        verify(exactly = 1) { persistentState.commitAdvancedFilterCompilation(TEST_HASH, 1L) }
        assertEquals(
            "final state must be B's Success",
            ManageFilterSourcesViewModel.TransactionState.Success(2, true),
            states.last()
        )
    }

    // =========================================================================
    // FIX-7B contract tests (A-F)
    // =========================================================================

    // A — compile outcome failure: Room write order is forward then rollback
    @Test
    fun rollback_compileOutcomeFailure_roomWriteOrderIsForwardThenRollback() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 5L
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(7, enabled = true)
        )
        coEvery { compiler.compileAllEnabled() } returns
            FilterSourceCompiler.CompileOutcome.failure("compile error")

        val states = collectTransactionStates()
        viewModel.setSourceEnabled(sourceId = 7, enabled = false)
        advanceUntilIdle()

        coVerifyOrder {
            repository.updateEnabledStatus(7, false) // forward
            repository.updateEnabledStatus(7, true)  // rollback
        }
        coVerify(exactly = 1) { compiler.compileAllEnabled() }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }

        val last = states.last()
        assertTrue(last is ManageFilterSourcesViewModel.TransactionState.Failed)
        val failed = last as ManageFilterSourcesViewModel.TransactionState.Failed
        assertEquals(7, failed.sourceId)
        assertEquals("compile error", failed.message)
    }

    // B — compiler throws Exception: rollback Room, next transaction still runs
    @Test
    fun rollback_compilerThrows_restoresPreviousAndNextTransactionRuns() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(8, enabled = false)
        )

        val compileCalls = AtomicInteger(0)
        coEvery { compiler.compileAllEnabled() } coAnswers {
            val n = compileCalls.incrementAndGet()
            if (n == 1) throw RuntimeException("compiler boom")
            successOutcome()
        }

        val states = collectTransactionStates()

        // First transaction: compile throws → Room rolled back, Failed emitted
        viewModel.setSourceEnabled(sourceId = 8, enabled = true)
        advanceUntilIdle()

        coVerifyOrder {
            repository.updateEnabledStatus(8, true)  // forward
            repository.updateEnabledStatus(8, false) // rollback
        }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }
        val firstLast = states.last()
        assertTrue(firstLast is ManageFilterSourcesViewModel.TransactionState.Failed)
        assertEquals("compiler boom", (firstLast as ManageFilterSourcesViewModel.TransactionState.Failed).message)

        // Second transaction must still run; mutex was not poisoned by the failure.
        viewModel.setSourceEnabled(sourceId = 8, enabled = true)
        advanceUntilIdle()

        assertEquals(2, compileCalls.get())
        coVerify(exactly = 2) { repository.updateEnabledStatus(8, true) }   // both forwards
        coVerify(exactly = 1) { repository.updateEnabledStatus(8, false) }  // one rollback only
        coVerify(exactly = 2) { compiler.compileAllEnabled() }
        verify(exactly = 1) { persistentState.commitAdvancedFilterCompilation(any(), any()) }
        assertTrue(states.last() is ManageFilterSourcesViewModel.TransactionState.Success)
    }

    // C — success: exactly one Room update, no rollback, exactly one commit
    @Test
    fun success_realChange_singleUpdateNoRollbackSingleCommit() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(9, enabled = false)
        )
        coEvery { compiler.compileAllEnabled() } returns successOutcome()

        val states = collectTransactionStates()
        viewModel.setSourceEnabled(sourceId = 9, enabled = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateEnabledStatus(9, true) }
        coVerify(exactly = 0) { repository.updateEnabledStatus(9, false) } // no rollback
        coVerify(exactly = 1) { compiler.compileAllEnabled() }
        verify(exactly = 1) { persistentState.commitAdvancedFilterCompilation(TEST_HASH, 1L) }
        assertEquals(
            ManageFilterSourcesViewModel.TransactionState.Success(9, true),
            states.last()
        )
    }

    // D — sourceId not in getAllSources() → Failed("source not found"), zero side effects
    @Test
    fun sourceNotFound_emitsFailedWithoutUpdateOrCompileOrCommit() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { repository.getAllSources() } returns emptyList()

        val states = collectTransactionStates()
        viewModel.setSourceEnabled(sourceId = 404, enabled = true)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateEnabledStatus(any(), any()) }
        coVerify(exactly = 0) { compiler.compileAllEnabled() }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }

        val last = states.last()
        assertTrue(last is ManageFilterSourcesViewModel.TransactionState.Failed)
        val failed = last as ManageFilterSourcesViewModel.TransactionState.Failed
        assertEquals(404, failed.sourceId)
        assertEquals("source not found", failed.message)
    }

    // E — previousEnabled == requested enabled → no-op Success, zero side effects
    @Test
    fun sameStateNoOp_emitsSuccessWithoutUpdateOrCompileOrCommit() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(10, enabled = true)
        )

        val states = collectTransactionStates()
        viewModel.setSourceEnabled(sourceId = 10, enabled = true) // already true
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateEnabledStatus(any(), any()) }
        coVerify(exactly = 0) { compiler.compileAllEnabled() }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }

        assertTrue(
            "same-state no-op must emit Success",
            states.last() is ManageFilterSourcesViewModel.TransactionState.Success
        )
        assertEquals(
            ManageFilterSourcesViewModel.TransactionState.Success(10, true),
            states.last()
        )
    }

    // F — CancellationException after Room update → NonCancellable rollback, job cancelled
    @Test
    fun cancellationAfterRoomUpdate_rollbacksToPreviousEnabledAndJobCancelled() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(11, enabled = false)
        )

        val gate = CompletableDeferred<FilterSourceCompiler.CompileOutcome>()
        coEvery { compiler.compileAllEnabled() } coAnswers { gate.await() }

        val states = collectTransactionStates()
        val job = viewModel.setSourceEnabled(sourceId = 11, enabled = true)
        advanceUntilIdle()

        // In compile, blocked on gate; Applying has been posted.
        assertFalse("must be blocked in compile on the test gate", gate.isCompleted)
        assertEquals(
            ManageFilterSourcesViewModel.TransactionState.Applying(11),
            states.last()
        )

        // Cancel the Job while the compile is still suspended on gate.
        job.cancel()
        advanceUntilIdle()

        coVerifyOrder {
            repository.updateEnabledStatus(11, true)  // forward
            repository.updateEnabledStatus(11, false) // rollback inside NonCancellable
        }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }
        assertTrue("job must be cancelled", job.isCancelled)
        val last = states.last()
        assertTrue(last is ManageFilterSourcesViewModel.TransactionState.Failed)
    }

    // =========================================================================
    // FIX-7C contract tests (G-K)
    // =========================================================================

    // G — enable target whose current.txt is missing → rollback, no compile, no commit, Failed.
    // Guard checks the RESULTING enabled set (not just the toggled source) so this is the
    // simplest case: source 12 starts disabled, request true, source 12's file is missing.
    @Test
    fun availabilityGuard_enableMissingTarget_targetCurrentTxtMissing_rollbacksAndFails() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(12, enabled = false)
        )
        // Phase 3A (prospective-set materialization) is called BEFORE the Room write.
        // Source 12 starts disabled so currentlyEnabled=[]. The post-write guard fetches
        // the resulting enabled set [source 12] and flags its missing current.txt.
        // returnsMany: [] on the first call (Phase 3A), [source 12] on the second (post-write).
        coEvery { repository.getEnabledSources() } returnsMany
            listOf(emptyList(), listOf(mockSource(12, enabled = true)))
        // Phase 3A: currentFile(12).exists() false → download succeeds → re-check true so
        // Phase 3A passes. The post-write guard then re-checks currentFile(12).exists()
        // returning false, triggering the guard failure + rollback.
        every { fileStore.currentFile(12).exists() } returnsMany
            listOf(false, true, false)
        coEvery { downloadManager.downloadSource(12) } returns
            FilterSourceDownloadManager.DownloadResult.Success(
                sourceId = 12,
                notModified = false,
                checksum = "checksum-12",
                bytesDownloaded = 12L
            )

        val states = collectTransactionStates()
        viewModel.setSourceEnabled(sourceId = 12, enabled = true)
        advanceUntilIdle()

        // Guard fires BEFORE compiler. Rollback path: forward then rollback, in that order.
        coVerifyOrder {
            repository.updateEnabledStatus(12, true)  // forward Room update
            repository.updateEnabledStatus(12, false) // rollback to previousEnabled
        }
        coVerify(exactly = 0) { compiler.compileAllEnabled() }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }

        val last = states.last()
        assertTrue(last is ManageFilterSourcesViewModel.TransactionState.Failed)
        val failed = last as ManageFilterSourcesViewModel.TransactionState.Failed
        assertEquals(12, failed.sourceId)
        // Message must reference missing source name + indicate file not available.
        assertTrue(
            "message must reference missing source name: ${failed.message}",
            failed.message.contains("S12")
        )
        assertTrue(
            "message must indicate file not available: ${failed.message}",
            failed.message.contains("not available", ignoreCase = true)
        )
    }

    // H — disable target; another enabled source in resulting set is missing → rollback,
    // no compile, Failed. Message must reference the OTHER missing source (S14), not the
    // toggled one (S13). This proves the guard inspects the entire resulting set.
    @Test
    fun availabilityGuard_disableLeavesOtherEnabledMissing_rollbacksAndFails() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(13, enabled = true),
            mockSource(14, enabled = true)
        )
        // Phase 3A: currentlyEnabled=[source 13, 14]; source 13 disabled → prospective=[14].
        // After Room write: getEnabledSources returns [14]. Source 14's file is missing → guard.
        // returnsMany: Phase 3A returns both currently-enabled, post-write returns [source 14].
        coEvery { repository.getEnabledSources() } returnsMany
            listOf(
                listOf(mockSource(13, enabled = true), mockSource(14, enabled = true)),
                listOf(mockSource(14, enabled = true))
            )
        // Phase 3A: currentFile(14).exists() false → download succeeds → re-check true so
        // Phase 3A passes. The post-write guard re-checks currentFile(14).exists() → false,
        // triggering the guard failure + rollback. (Source 13 leaving the set, never checked.)
        every { fileStore.currentFile(14).exists() } returnsMany
            listOf(false, true, false)
        coEvery { downloadManager.downloadSource(14) } returns
            FilterSourceDownloadManager.DownloadResult.Success(
                sourceId = 14,
                notModified = false,
                checksum = "checksum-14",
                bytesDownloaded = 14L
            )

        val states = collectTransactionStates()
        viewModel.setSourceEnabled(sourceId = 13, enabled = false)
        advanceUntilIdle()

        coVerifyOrder {
            repository.updateEnabledStatus(13, false) // forward
            repository.updateEnabledStatus(13, true)  // rollback
        }
        coVerify(exactly = 0) { compiler.compileAllEnabled() }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }

        val last = states.last()
        assertTrue(last is ManageFilterSourcesViewModel.TransactionState.Failed)
        val failed = last as ManageFilterSourcesViewModel.TransactionState.Failed
        assertEquals(13, failed.sourceId)
        // Message must reference the OTHER missing source (S14), proving the guard
        // inspects the full resulting set, not just the toggled source.
        assertTrue(
            "message must reference the OTHER missing source (S14): ${failed.message}",
            failed.message.contains("S14")
        )
        assertFalse(
            "message must NOT name the toggled source's directory (S13): ${failed.message}",
            failed.message.contains("'S13'") || failed.message.contains("(id=13)")
        )
    }

    // I — every resulting enabled source's current.txt exists → guard passes, compile runs,
    // commit happens, Success. Sanity-check for the happy path under FIX-7C.
    @Test
    fun availabilityGuard_allResultingEnabledFilesExist_compilesAndCommits() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(15, enabled = false)
        )
        coEvery { repository.getEnabledSources() } returns listOf(
            mockSource(15, enabled = true)
        )
        every { fileStore.currentFile(15).exists() } returns true
        coEvery { compiler.compileAllEnabled() } returns successOutcome()

        val states = collectTransactionStates()
        viewModel.setSourceEnabled(sourceId = 15, enabled = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateEnabledStatus(15, true) }
        coVerify(exactly = 0) { repository.updateEnabledStatus(15, false) } // no rollback
        coVerify(exactly = 1) { compiler.compileAllEnabled() }
        verify(exactly = 1) { persistentState.commitAdvancedFilterCompilation(TEST_HASH, 1L) }

        assertEquals(
            ManageFilterSourcesViewModel.TransactionState.Success(15, true),
            states.last()
        )
    }

    // J — resulting enabled set is empty → guard does NOT flag a missing file even if the
    // file store would claim every file is missing. FIX-7A's explicit-empty path
    // (writeEmptyArtifact) MUST still run. This preserves legitimate empty enabled sets.
    @Test
    fun availabilityGuard_resultingEnabledSetEmpty_compilesExplicitEmpty() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(16, enabled = true)
        )
        // Source 16 is being disabled; resulting enabled set is empty.
        coEvery { repository.getEnabledSources() } returns emptyList()
        // Even if fileStore would claim files missing, the guard must skip when the set is empty.
        every { fileStore.currentFile(any()).exists() } returns false
        coEvery { compiler.compileAllEnabled() } returns successOutcome()

        val states = collectTransactionStates()
        viewModel.setSourceEnabled(sourceId = 16, enabled = false)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateEnabledStatus(16, false) }
        coVerify(exactly = 0) { repository.updateEnabledStatus(16, true) } // no rollback
        coVerify(exactly = 1) { compiler.compileAllEnabled() }
        verify(exactly = 1) { persistentState.commitAdvancedFilterCompilation(TEST_HASH, 1L) }

        assertEquals(
            ManageFilterSourcesViewModel.TransactionState.Success(16, false),
            states.last()
        )
    }

    // K — availability lookup (getEnabledSources) throws after Room update → rollback,
    // no compile, no commit, Failed with the original message.
    @Test
    fun availabilityGuard_availabilityLookupThrows_rollbacksAndFails() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(17, enabled = false)
        )
        // Phase 3A calls getEnabledSources() first (returns empty — no currently-enabled
        // sources), then the Room write happens, then the post-write guard calls
        // getEnabledSources() a second time. That second call must throw so the outer
        // exception handler rolls back the forward Room update and emits Failed.
        // AtomicInteger ensures the throw fires on the second call (post-write guard),
        // not the first (Phase 3A).
        val enabledCalls = AtomicInteger(0)
        coEvery { repository.getEnabledSources() } coAnswers {
            if (enabledCalls.incrementAndGet() == 2) {
                throw RuntimeException("lookup boom")
            }
            emptyList()
        }

        val states = collectTransactionStates()
        viewModel.setSourceEnabled(sourceId = 17, enabled = true)
        advanceUntilIdle()

        coVerifyOrder {
            repository.updateEnabledStatus(17, true)  // forward
            repository.updateEnabledStatus(17, false) // rollback
        }
        coVerify(exactly = 0) { compiler.compileAllEnabled() }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }

        val last = states.last()
        assertTrue(last is ManageFilterSourcesViewModel.TransactionState.Failed)
        val failed = last as ManageFilterSourcesViewModel.TransactionState.Failed
        assertEquals(17, failed.sourceId)
        assertEquals("lookup boom", failed.message)
    }

    // FIX-7C-R — cancellation while availability lookup is suspended must have
    // exactly one rollback owner (the outer CancellationException handler).
    @Test
    fun availabilityLookupCancellation_rollsBackExactlyOnceAndRethrowsCancellation() = runTest(testDispatcher) {
        every { persistentState.advancedFilterGeneration } returns 0L
        coEvery { repository.getAllSources() } returns listOf(
            mockSource(18, enabled = false)
        )

        val lookupGate = CompletableDeferred<List<FilterSource>>()
        val lookupCalls = AtomicInteger(0)
        coEvery { repository.getEnabledSources() } coAnswers {
            // First call is Phase 3A (prospective materialization): returns empty immediately.
            // Second call is the post-write guard: suspends so cancellation lands AFTER the
            // forward Room update has been applied, proving exactly one rollback owner.
            if (lookupCalls.incrementAndGet() == 2) {
                lookupGate.await()
            } else {
                emptyList()
            }
        }
        coEvery { compiler.compileAllEnabled() } returns successOutcome()

        val states = collectTransactionStates()
        val job = viewModel.setSourceEnabled(sourceId = 18, enabled = true)
        advanceUntilIdle()

        // The transaction is suspended specifically in getEnabledSources() during the
        // post-write guard (the second call), AFTER the forward Room update was applied.
        // The first call (Phase 3A) returned empty and completed without suspending.
        assertFalse("post-write availability lookup must still be suspended", lookupGate.isCompleted)
        assertEquals(
            ManageFilterSourcesViewModel.TransactionState.Applying(18),
            states.last()
        )

        job.cancel()
        advanceUntilIdle()

        // The outer CancellationException handler is the single rollback owner:
        // exactly one forward update, exactly one rollback, and no third update.
        coVerifyOrder {
            repository.updateEnabledStatus(18, true)  // forward
            repository.updateEnabledStatus(18, false) // one NonCancellable rollback
        }
        coVerify(exactly = 1) { repository.updateEnabledStatus(18, true) }
        coVerify(exactly = 1) { repository.updateEnabledStatus(18, false) }
        coVerify(exactly = 0) { compiler.compileAllEnabled() }
        verify(exactly = 0) { persistentState.commitAdvancedFilterCompilation(any(), any()) }
        assertTrue("returned Job must be cancelled", job.isCancelled)
        assertEquals(
            "exactly one terminal Failed state must be emitted for source 18",
            1,
            states.count {
                it is ManageFilterSourcesViewModel.TransactionState.Failed &&
                    it.sourceId == 18
            }
        )

        // The mutex must be released after cancellation; a later transaction still succeeds.
        viewModel.setSourceEnabled(sourceId = 18, enabled = true)
        advanceUntilIdle()
        assertEquals(
            ManageFilterSourcesViewModel.TransactionState.Success(18, true),
            states.last()
        )
        coVerify(exactly = 1) { compiler.compileAllEnabled() }
        verify(exactly = 1) { persistentState.commitAdvancedFilterCompilation(TEST_HASH, 1L) }
    }

    // =========================================================================
    // CUSTOM-FILTER-B4 — createCustomSource state-machine tests (C1-C7)
    //
    // Metadata-only contract: creation enters the same txMutex as enable/disable
    // transactions, maps the repository result onto CustomSourceCreationState, and never
    // touches the downloader, compiler, PersistentState, or setSourceEnabled.
    // =========================================================================

    // C1 — Added: Creating then Added, the exact repository source instance preserved,
    // repository called exactly once with the original name and URL.
    @Test
    fun createCustomSource_added_emitsCreatingThenAdded() = runTest(testDispatcher) {
        val source = disabledCustomSource(id = 7)
        coEvery {
            repository.addCustomSource("My List", "https://example.com/custom.txt")
        } returns AddCustomSourceResult.Added(source)

        val states = collectCustomCreationStates()
        val job = viewModel.createCustomSource("My List", "https://example.com/custom.txt")
        job.join()
        advanceUntilIdle()

        assertEquals(
            listOf(
                CustomSourceCreationState.Idle,
                CustomSourceCreationState.Creating,
                CustomSourceCreationState.Added(source)
            ),
            states
        )
        // Fixture contract: id > 0, CUSTOM, disabled, non-preset, no catalog reference,
        // finalized relativeFilePath.
        assertTrue("fixture id must be > 0", source.id > 0)
        assertEquals(FilterSourceCategory.CUSTOM, source.category)
        assertFalse(source.enabled)
        assertFalse(source.isPreset)
        assertNull(source.referenceId)
        assertEquals("filter_sources/source_7/current.txt", source.relativeFilePath)
        // The exact instance returned by the repository must be carried through unchanged.
        assertSame(
            source,
            (states.last() as CustomSourceCreationState.Added).source
        )
        coVerify(exactly = 1) {
            repository.addCustomSource("My List", "https://example.com/custom.txt")
        }
    }

    // C2 — InvalidInput: Creating then InvalidInput; zero downloader/compiler/
    // PersistentState/updateEnabledStatus calls (no pipeline mutation on invalid input).
    @Test
    fun createCustomSource_invalidInput_emitsInvalidWithoutPipelineMutation() =
        runTest(testDispatcher) {
            coEvery { repository.addCustomSource(any(), any()) } returns
                AddCustomSourceResult.InvalidInput(
                    CustomFilterSourceValidator.Error.UNSUPPORTED_SCHEME
                )

            val states = collectCustomCreationStates()
            viewModel.createCustomSource("My List", "ftp://example.com/list.txt").join()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    CustomSourceCreationState.Idle,
                    CustomSourceCreationState.Creating,
                    CustomSourceCreationState.InvalidInput(
                        CustomFilterSourceValidator.Error.UNSUPPORTED_SCHEME
                    )
                ),
                states
            )
            coVerify(exactly = 0) { downloadManager.downloadSource(any<Int>()) }
            coVerify(exactly = 0) { compiler.compileAllEnabled() }
            verify(exactly = 0) {
                persistentState.commitAdvancedFilterCompilation(any(), any())
            }
            coVerify(exactly = 0) { repository.updateEnabledStatus(any(), any()) }
        }

    // C3 — DuplicateUrl: Creating then DuplicateUrl carrying the exact URL; zero pipeline
    // mutation.
    @Test
    fun createCustomSource_duplicateUrl_emitsDuplicateWithoutPipelineMutation() =
        runTest(testDispatcher) {
            val url = "https://duplicate.example.com/list.txt"
            coEvery { repository.addCustomSource(any(), any()) } returns
                AddCustomSourceResult.DuplicateUrl(url)

            val states = collectCustomCreationStates()
            viewModel.createCustomSource("My List", url).join()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    CustomSourceCreationState.Idle,
                    CustomSourceCreationState.Creating,
                    CustomSourceCreationState.DuplicateUrl(url)
                ),
                states
            )
            assertEquals(
                url,
                (states.last() as CustomSourceCreationState.DuplicateUrl).url
            )
            coVerify(exactly = 0) { downloadManager.downloadSource(any<Int>()) }
            coVerify(exactly = 0) { compiler.compileAllEnabled() }
            verify(exactly = 0) {
                persistentState.commitAdvancedFilterCompilation(any(), any())
            }
            coVerify(exactly = 0) { repository.updateEnabledStatus(any(), any()) }
        }

    // C4 — DuplicateName: Creating then DuplicateName carrying the existing stored name;
    // zero downloader/compiler/PersistentState/updateEnabledStatus mutation.
    @Test
    fun createCustomSource_duplicateName_emitsDuplicateWithoutPipelineMutation() =
        runTest(testDispatcher) {
            val existingName = "Existing Filter"
            coEvery { repository.addCustomSource(any(), any()) } returns
                AddCustomSourceResult.DuplicateName(existingName)

            val states = collectCustomCreationStates()
            viewModel.createCustomSource(
                "existing filter",
                "https://alternative.example.com/list.txt"
            ).join()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    CustomSourceCreationState.Idle,
                    CustomSourceCreationState.Creating,
                    CustomSourceCreationState.DuplicateName(existingName)
                ),
                states
            )
            assertEquals(
                existingName,
                (states.last() as CustomSourceCreationState.DuplicateName).name
            )
            coVerify(exactly = 0) {
                downloadManager.downloadSource(any<Int>())
            }
            coVerify(exactly = 0) { compiler.compileAllEnabled() }
            verify(exactly = 0) {
                persistentState.commitAdvancedFilterCompilation(any(), any())
            }
            coVerify(exactly = 0) {
                repository.updateEnabledStatus(any(), any())
            }
        }

    // C5 — unexpected failure: Creating then Failed with the exception message; the Job
    // completes WITHOUT rethrowing the ordinary exception; zero pipeline mutation.
    @Test
    fun createCustomSource_unexpectedFailure_emitsFailed() = runTest(testDispatcher) {
        coEvery { repository.addCustomSource(any(), any()) } throws
            IllegalStateException("db failed")

        val states = collectCustomCreationStates()
        val job = viewModel.createCustomSource("My List", "https://example.com/list.txt")
        job.join()
        advanceUntilIdle()

        assertEquals(
            listOf(
                CustomSourceCreationState.Idle,
                CustomSourceCreationState.Creating,
                CustomSourceCreationState.Failed("db failed")
            ),
            states
        )
        assertTrue("Job must complete without rethrowing", job.isCompleted)
        assertFalse("ordinary failure must not cancel the Job", job.isCancelled)
        coVerify(exactly = 0) { downloadManager.downloadSource(any<Int>()) }
        coVerify(exactly = 0) { compiler.compileAllEnabled() }
        verify(exactly = 0) {
            persistentState.commitAdvancedFilterCompilation(any(), any())
        }
        coVerify(exactly = 0) { repository.updateEnabledStatus(any(), any()) }
    }

    // C6 — cancellation: the exact CancellationException instance thrown by the repository
    // escapes as the Job's completion cause; state is reset to Idle before rethrow; zero
    // pipeline mutation.
    @Test
    fun createCustomSource_cancellation_resetsIdleAndRethrows() = runTest(testDispatcher) {
        val cancelled = CancellationException("creation cancelled by caller")
        coEvery { repository.addCustomSource(any(), any()) } throws cancelled

        val states = collectCustomCreationStates()
        val job = viewModel.createCustomSource("My List", "https://example.com/list.txt")
        var completionCause: Throwable? = null
        job.invokeOnCompletion { cause -> completionCause = cause }
        job.join()
        advanceUntilIdle()

        assertSame(
            "the exact CancellationException instance must escape Job completion",
            cancelled,
            completionCause
        )
        assertTrue("Job must be cancelled", job.isCancelled)
        assertEquals(
            listOf(
                CustomSourceCreationState.Idle,
                CustomSourceCreationState.Creating,
                CustomSourceCreationState.Idle
            ),
            states
        )
        coVerify(exactly = 0) { downloadManager.downloadSource(any<Int>()) }
        coVerify(exactly = 0) { compiler.compileAllEnabled() }
        verify(exactly = 0) {
            persistentState.commitAdvancedFilterCompilation(any(), any())
        }
        coVerify(exactly = 0) { repository.updateEnabledStatus(any(), any()) }
    }

    // C7 — clearCustomSourceCreationState(): resets a non-Idle terminal state to Idle;
    // pure UI-state reset — no repository/downloader/compiler/PersistentState interaction.
    @Test
    fun clearCustomSourceCreationState_setsIdle() = runTest(testDispatcher) {
        coEvery { repository.addCustomSource(any(), any()) } returns
            AddCustomSourceResult.DuplicateUrl("https://duplicate.example.com/list.txt")

        val states = collectCustomCreationStates()
        viewModel.createCustomSource("My List", "https://duplicate.example.com/list.txt")
            .join()
        advanceUntilIdle()

        assertTrue(
            "precondition: terminal creation state must be non-Idle",
            states.last() != CustomSourceCreationState.Idle
        )

        viewModel.clearCustomSourceCreationState()

        assertEquals(CustomSourceCreationState.Idle, states.last())
        // The single addCustomSource call above is the creation itself; clear() must add
        // no further interactions of any kind.
        coVerify(exactly = 1) { repository.addCustomSource(any(), any()) }
        coVerify(exactly = 0) { downloadManager.downloadSource(any<Int>()) }
        coVerify(exactly = 0) { compiler.compileAllEnabled() }
        verify(exactly = 0) {
            persistentState.commitAdvancedFilterCompilation(any(), any())
        }
        coVerify(exactly = 0) { repository.updateEnabledStatus(any(), any()) }
    }

    // =========================================================================
    // Download-on-enable contract tests
/*
    // =========================================================================

    @Test
    fun downloadOnEnable_missingProspectiveFiles_downloadsBeforeRoomUpdateAndCompiles() =
        runTest(testDispatcher) {
            every { persistentState.advancedFilterGeneration } returns 0L

            val target = mockSource(21, enabled = false)
            val alreadyEnabled = mockSource(22, enabled = true)

            coEvery { repository.getAllSources() } returns
                listOf(target, alreadyEnabled)
            // Phase 3A: currentlyEnabled=[22] → prospective=[22, 21].
            // Post-write guard: getEnabledSources() returns [22, 21] (after Room update).
            coEvery { repository.getEnabledSources() } returnsMany
                listOf(listOf(alreadyEnabled), listOf(alreadyEnabled, target))

            // Each missing file becomes available immediately after its successful
            // download. Source 22 is also checked by the retained post-update guard.
            every { fileStore.currentFile(22).exists() } returnsMany
                listOf(false, true, true)
            every { fileStore.currentFile(21).exists() } returnsMany
                listOf(false, true)

            coEvery { downloadManager.downloadSource(22) } returns
                FilterSourceDownloadManager.DownloadResult.Success(
                    sourceId = 22,
                    notModified = false,
                    checksum = "checksum-22",
                    bytesDownloaded = 22L
                )
            coEvery { downloadManager.downloadSource(21) } returns
                FilterSourceDownloadManager.DownloadResult.Success(
                    sourceId = 21,
                    notModified = false,
                    checksum = "checksum-21",
                    bytesDownloaded = 21L
                )
            coEvery { compiler.compileAllEnabled() } returns successOutcome()

            val states = collectTransactionStates()
            viewModel.setSourceEnabled(sourceId = 21, enabled = true)
            advanceUntilIdle()

            // Both prospective enabled sources must be downloaded before the
            // forward Room mutation. The previously enabled source is handled
            // first, followed by the newly enabled target.
            coVerifyOrder {
                downloadManager.downloadSource(22)
                downloadManager.downloadSource(21)
                repository.updateEnabledStatus(21, true)
                compiler.compileAllEnabled()
            }
            coVerify(exactly = 1) { downloadManager.downloadSource(22) }
            coVerify(exactly = 1) { downloadManager.downloadSource(21) }
            coVerify(exactly = 1) { repository.updateEnabledStatus(21, true) }
            coVerify(exactly = 0) { repository.updateEnabledStatus(21, false) }
            coVerify(exactly = 1) { compiler.compileAllEnabled() }
            verify(exactly = 1) {
                persistentState.commitAdvancedFilterCompilation(TEST_HASH, 1L)
            }

            assertEquals(
                ManageFilterSourcesViewModel.TransactionState.Success(21, true),
                states.last()
            )
        }

    @Test
    fun downloadOnEnable_downloadFailure_doesNotMutateRoomOrCompile() =
        runTest(testDispatcher) {
            every { persistentState.advancedFilterGeneration } returns 0L

            val target = mockSource(31, enabled = false)
            val alreadyEnabled = mockSource(32, enabled = true)

            coEvery { repository.getAllSources() } returns
                listOf(target, alreadyEnabled)
            coEvery { repository.getEnabledSources() } returns
                listOf(alreadyEnabled)
            every { fileStore.currentFile(32).exists() } returns false

            coEvery { downloadManager.downloadSource(32) } returns
                FilterSourceDownloadManager.DownloadResult.Failure(
                    sourceId = 32,
                    errorMessage = "network down",
                    httpCode = 503
                )

            val states = collectTransactionStates()
            viewModel.setSourceEnabled(sourceId = 31, enabled = true)
            advanceUntilIdle()

            coVerify(exactly = 1) { downloadManager.downloadSource(32) }
            coVerify(exactly = 0) { downloadManager.downloadSource(31) }
            coVerify(exactly = 0) {
                repository.updateEnabledStatus(any(), any())
            }
            coVerify(exactly = 0) { compiler.compileAllEnabled() }
            verify(exactly = 0) {
                persistentState.commitAdvancedFilterCompilation(any(), any())
            }

            val last = states.last()
            assertTrue(
                "download failure must emit Failed",
                last is ManageFilterSourcesViewModel.TransactionState.Failed
            )
            val failed =
                last as ManageFilterSourcesViewModel.TransactionState.Failed
            assertEquals(31, failed.sourceId)
            assertTrue(
                "message must identify the source whose download failed: ${failed.message}",
                failed.message.contains("S32")
            )
            assertTrue(
                "message must preserve the downloader error: ${failed.message}",
                failed.message.contains("network down")
            )
        }

    @Test
    fun downloadOnEnable_successWithoutCurrentFile_doesNotMutateRoomOrCompile() =
        runTest(testDispatcher) {
            every { persistentState.advancedFilterGeneration } returns 0L

            val target = mockSource(41, enabled = false)

            coEvery { repository.getAllSources() } returns listOf(target)
            coEvery { repository.getEnabledSources() } returns emptyList()

            // Missing before download and still missing after a nominal Success.
            every { fileStore.currentFile(41).exists() } returns false
            coEvery { downloadManager.downloadSource(41) } returns
                FilterSourceDownloadManager.DownloadResult.Success(
                    sourceId = 41,
                    notModified = true,
                    checksum = "stale-checksum",
                    bytesDownloaded = 0L
                )

            val states = collectTransactionStates()
            viewModel.setSourceEnabled(sourceId = 41, enabled = true)
            advanceUntilIdle()

            coVerify(exactly = 1) { downloadManager.downloadSource(41) }
            coVerify(exactly = 0) {
                repository.updateEnabledStatus(any(), any())
            }
            coVerify(exactly = 0) { compiler.compileAllEnabled() }
            verify(exactly = 0) {
                persistentState.commitAdvancedFilterCompilation(any(), any())
            }

            val last = states.last()
            assertTrue(
                "download success without current.txt must emit Failed",
                last is ManageFilterSourcesViewModel.TransactionState.Failed
            )
            val failed =
                last as ManageFilterSourcesViewModel.TransactionState.Failed
            assertEquals(41, failed.sourceId)
            assertTrue(
                "message must identify the source still missing its file: ${failed.message}",
                failed.message.contains("S41")
            )
            assertTrue(
                "message must indicate current.txt is unavailable: ${failed.message}",
                failed.message.contains("not available", ignoreCase = true)
            )
        }

    @Test
    fun downloadOnEnable_cancellationBeforeRoomWrite_rethrowsWithoutMutation() =
        runTest(testDispatcher) {
            every { persistentState.advancedFilterGeneration } returns 0L

            val target = mockSource(51, enabled = false)

            coEvery { repository.getAllSources() } returns listOf(target)
            coEvery { repository.getEnabledSources() } returns emptyList()
            every { fileStore.currentFile(51).exists() } returns false

            val downloadGate =
                CompletableDeferred<
                    FilterSourceDownloadManager.DownloadResult
                >()
            coEvery { downloadManager.downloadSource(51) } coAnswers {
                downloadGate.await()
            }

            val states = collectTransactionStates()
            val job =
                viewModel.setSourceEnabled(sourceId = 51, enabled = true)
            advanceUntilIdle()

            assertFalse(
                "transaction must be suspended inside the download",
                downloadGate.isCompleted
            )
            assertEquals(
                ManageFilterSourcesViewModel.TransactionState.Applying(51),
                states.last()
            )

            job.cancel()
            advanceUntilIdle()

            coVerify(exactly = 1) { downloadManager.downloadSource(51) }
            coVerify(exactly = 0) {
                repository.updateEnabledStatus(any(), any())
            }
            coVerify(exactly = 0) { compiler.compileAllEnabled() }
            verify(exactly = 0) {
                persistentState.commitAdvancedFilterCompilation(any(), any())
            }
            assertTrue("returned Job must be cancelled", job.isCancelled)

            val last = states.last()
            assertTrue(
                "cancellation must emit a terminal Failed state before rethrow",
                last is ManageFilterSourcesViewModel.TransactionState.Failed
            )
            assertEquals(
                51,
                (last as ManageFilterSourcesViewModel.TransactionState.Failed)
                    .sourceId
            )
        }

    // =========================================================================
    */
// HELPERS
    // =========================================================================

    private companion object {
        const val TEST_HASH = "test-enabled-set-hash"
    }

    private fun collectCustomRemovalStates():
        MutableList<CustomSourceRemovalState> {
        val states = mutableListOf<CustomSourceRemovalState>()
        viewModel.customSourceRemoval.observeForever {
            states.add(it)
        }
        return states
    }

    // =========================================================================
    // CUSTOM-FILTER-REMOVE-B5 — removeCustomSource transaction tests (R1-R8)
    // =========================================================================

    @Test
    fun removeCustomSource_disabled_removesWithoutCompileOrGenerationCommit() =
        runTest(testDispatcher) {
            val source = disabledCustomSource(id = 51)
            coEvery { repository.getSourceById(51) } returns source
            coEvery {
                repository.removeDisabledCustomSource(51)
            } returns RemoveCustomSourceResult.Removed(51)

            val states = collectCustomRemovalStates()
            viewModel.removeCustomSource(51).join()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    CustomSourceRemovalState.Idle,
                    CustomSourceRemovalState.Removing(51),
                    CustomSourceRemovalState.Removed(51)
                ),
                states
            )
            coVerify(exactly = 1) {
                repository.removeDisabledCustomSource(51)
            }
            coVerify(exactly = 0) {
                repository.updateEnabledStatus(any(), any())
            }
            coVerify(exactly = 0) { compiler.compileAllEnabled() }
            verify(exactly = 0) {
                persistentState.commitAdvancedFilterCompilation(any(), any())
            }
        }

    @Test
    fun removeCustomSource_enabled_compilesCommitsThenRemoves() =
        runTest(testDispatcher) {
            val source = mockSource(52, enabled = true).copy(
                category = FilterSourceCategory.CUSTOM,
                isPreset = false
            )
            coEvery { repository.getSourceById(52) } returns source
            coEvery { repository.getEnabledSources() } returnsMany
                listOf(listOf(source), emptyList())
            every { persistentState.advancedFilterGeneration } returns 7L
            coEvery { compiler.compileAllEnabled() } returns successOutcome()
            coEvery {
                repository.removeDisabledCustomSource(52)
            } returns RemoveCustomSourceResult.Removed(52)

            val states = collectCustomRemovalStates()
            viewModel.removeCustomSource(52).join()
            advanceUntilIdle()

            assertEquals(
                CustomSourceRemovalState.Removed(52),
                states.last()
            )
            coVerifyOrder {
                repository.updateEnabledStatus(52, false)
                compiler.compileAllEnabled()
                repository.removeDisabledCustomSource(52)
            }
            coVerify(exactly = 0) {
                repository.updateEnabledStatus(52, true)
            }
            verify(exactly = 1) {
                persistentState.commitAdvancedFilterCompilation(
                    TEST_HASH,
                    8L
                )
            }
        }

    @Test
    fun removeCustomSource_compileFailure_rollsBackAndDoesNotDelete() =
        runTest(testDispatcher) {
            val source = mockSource(53, enabled = true).copy(
                category = FilterSourceCategory.CUSTOM,
                isPreset = false
            )
            coEvery { repository.getSourceById(53) } returns source
            coEvery { repository.getEnabledSources() } returnsMany
                listOf(listOf(source), emptyList())
            coEvery { compiler.compileAllEnabled() } returns
                FilterSourceCompiler.CompileOutcome.failure(
                    "remove compile failed"
                )

            val states = collectCustomRemovalStates()
            viewModel.removeCustomSource(53).join()
            advanceUntilIdle()

            coVerifyOrder {
                repository.updateEnabledStatus(53, false)
                repository.updateEnabledStatus(53, true)
            }
            coVerify(exactly = 0) {
                repository.removeDisabledCustomSource(53)
            }
            verify(exactly = 0) {
                persistentState.commitAdvancedFilterCompilation(any(), any())
            }
            val failed =
                states.last() as CustomSourceRemovalState.Failed
            assertEquals("remove compile failed", failed.message)
        }

    @Test
    fun removeCustomSource_commitFailure_rollsBackAndDoesNotDelete() =
        runTest(testDispatcher) {
            val source = mockSource(54, enabled = true).copy(
                category = FilterSourceCategory.CUSTOM,
                isPreset = false
            )
            coEvery { repository.getSourceById(54) } returns source
            coEvery { repository.getEnabledSources() } returnsMany
                listOf(listOf(source), emptyList())
            every { persistentState.advancedFilterGeneration } returns 2L
            coEvery { compiler.compileAllEnabled() } returns successOutcome()
            every {
                persistentState.commitAdvancedFilterCompilation(
                    TEST_HASH,
                    3L
                )
            } throws IllegalStateException("commit failed")

            val states = collectCustomRemovalStates()
            viewModel.removeCustomSource(54).join()
            advanceUntilIdle()

            coVerifyOrder {
                repository.updateEnabledStatus(54, false)
                repository.updateEnabledStatus(54, true)
            }
            coVerify(exactly = 0) {
                repository.removeDisabledCustomSource(54)
            }
            val failed =
                states.last() as CustomSourceRemovalState.Failed
            assertEquals("commit failed", failed.message)
        }

    @Test
    fun removeCustomSource_cleanupFailureAfterCommit_keepsDisabledWithoutRollback() =
        runTest(testDispatcher) {
            val source = mockSource(55, enabled = true).copy(
                category = FilterSourceCategory.CUSTOM,
                isPreset = false
            )
            coEvery { repository.getSourceById(55) } returns source
            coEvery { repository.getEnabledSources() } returnsMany
                listOf(listOf(source), emptyList())
            every { persistentState.advancedFilterGeneration } returns 0L
            coEvery { compiler.compileAllEnabled() } returns successOutcome()
            coEvery {
                repository.removeDisabledCustomSource(55)
            } returns RemoveCustomSourceResult.FileCleanupFailed(55)

            val states = collectCustomRemovalStates()
            viewModel.removeCustomSource(55).join()
            advanceUntilIdle()

            assertEquals(
                CustomSourceRemovalState.FileCleanupFailed(55),
                states.last()
            )
            coVerify(exactly = 1) {
                repository.updateEnabledStatus(55, false)
            }
            coVerify(exactly = 0) {
                repository.updateEnabledStatus(55, true)
            }
            verify(exactly = 1) {
                persistentState.commitAdvancedFilterCompilation(
                    TEST_HASH,
                    1L
                )
            }
        }

    @Test
    fun removeCustomSource_cancellationBeforeCommit_rollsBackAndRethrows() =
        runTest(testDispatcher) {
            val source = mockSource(56, enabled = true).copy(
                category = FilterSourceCategory.CUSTOM,
                isPreset = false
            )
            val compileStarted = CompletableDeferred<Unit>()
            val releaseCompile = CompletableDeferred<Unit>()

            coEvery { repository.getSourceById(56) } returns source
            coEvery { repository.getEnabledSources() } returnsMany
                listOf(listOf(source), emptyList())
            coEvery { compiler.compileAllEnabled() } coAnswers {
                compileStarted.complete(Unit)
                releaseCompile.await()
                successOutcome()
            }

            val states = collectCustomRemovalStates()
            val job = viewModel.removeCustomSource(56)
            compileStarted.await()

            job.cancel()
            advanceUntilIdle()

            coVerifyOrder {
                repository.updateEnabledStatus(56, false)
                repository.updateEnabledStatus(56, true)
            }
            coVerify(exactly = 0) {
                repository.removeDisabledCustomSource(56)
            }
            verify(exactly = 0) {
                persistentState.commitAdvancedFilterCompilation(any(), any())
            }
            assertTrue(job.isCancelled)
            assertEquals(CustomSourceRemovalState.Idle, states.last())
        }

    @Test
    fun removeCustomSource_lookupGuards_haveZeroPipelineMutation() =
        runTest(testDispatcher) {
            coEvery { repository.getSourceById(57) } returns null
            val missingStates = collectCustomRemovalStates()
            viewModel.removeCustomSource(57).join()
            advanceUntilIdle()
            assertEquals(
                CustomSourceRemovalState.SourceNotFound(57),
                missingStates.last()
            )

            viewModel.clearCustomSourceRemovalState()

            val preset = mockSource(58, enabled = false).copy(
                category = FilterSourceCategory.ADS,
                isPreset = true
            )
            coEvery { repository.getSourceById(58) } returns preset
            viewModel.removeCustomSource(58).join()
            advanceUntilIdle()
            assertEquals(
                CustomSourceRemovalState.NotCustomSource(58),
                viewModel.customSourceRemoval.value
            )

            coVerify(exactly = 0) {
                repository.updateEnabledStatus(any(), any())
            }
            coVerify(exactly = 0) { compiler.compileAllEnabled() }
            coVerify(exactly = 0) {
                repository.removeDisabledCustomSource(any())
            }
            verify(exactly = 0) {
                persistentState.commitAdvancedFilterCompilation(any(), any())
            }
        }

    @Test
    fun removeCustomSource_remainingSourceDownloadFailure_stopsBeforeRoomMutation() =
        runTest(testDispatcher) {
            val target = mockSource(59, enabled = true).copy(
                category = FilterSourceCategory.CUSTOM,
                isPreset = false
            )
            val remaining = mockSource(60, enabled = true)

            coEvery { repository.getSourceById(59) } returns target
            coEvery { repository.getEnabledSources() } returns
                listOf(target, remaining)
            every {
                fileStore.currentFile(60).exists()
            } returns false
            coEvery {
                downloadManager.downloadSource(60)
            } returns
                FilterSourceDownloadManager.DownloadResult.Failure(
                    sourceId = 60,
                    errorMessage = "network down",
                    httpCode = 503
                )

            val states = collectCustomRemovalStates()
            viewModel.removeCustomSource(59).join()
            advanceUntilIdle()

            val failed =
                states.last() as CustomSourceRemovalState.Failed
            assertTrue(failed.message.contains("network down"))
            coVerify(exactly = 0) {
                repository.updateEnabledStatus(any(), any())
            }
            coVerify(exactly = 0) { compiler.compileAllEnabled() }
            coVerify(exactly = 0) {
                repository.removeDisabledCustomSource(any())
            }
            verify(exactly = 0) {
                persistentState.commitAdvancedFilterCompilation(any(), any())
            }
        }

    private fun collectCustomEditStates(): MutableList<CustomSourceEditState> {
        val states = mutableListOf<CustomSourceEditState>()
        viewModel.customSourceEdit.observeForever {
            states.add(it)
        }
        return states
    }

    private fun assertNoEditPipelineMutation() {
        coVerify(exactly = 0) {
            downloadManager.downloadSource(any<Int>())
        }
        coVerify(exactly = 0) {
            compiler.compileAllEnabled()
        }
        coVerify(exactly = 0) {
            repository.updateEnabledStatus(any(), any())
        }
        verify(exactly = 0) {
            persistentState.commitAdvancedFilterCompilation(any(), any())
        }
    }

    // =========================================================================
    // CUSTOM-FILTER-EDIT-B3 — editCustomSource state-machine tests (E1-E5)
    // =========================================================================

    @Test
    fun editCustomSource_updated_emitsEditingThenExactUpdatedSource() =
        runTest(testDispatcher) {
            val source = disabledCustomSource(id = 41)
            coEvery {
                repository.editCustomSource(
                    41,
                    "Edited",
                    "https://edited.example.com/list.txt"
                )
            } returns EditCustomSourceResult.Updated(source)

            val states = collectCustomEditStates()
            viewModel.editCustomSource(
                41,
                "Edited",
                "https://edited.example.com/list.txt"
            ).join()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    CustomSourceEditState.Idle,
                    CustomSourceEditState.Editing(41),
                    CustomSourceEditState.Updated(source)
                ),
                states
            )
            assertSame(
                source,
                (states.last() as CustomSourceEditState.Updated).source
            )
            coVerify(exactly = 1) {
                repository.editCustomSource(
                    41,
                    "Edited",
                    "https://edited.example.com/list.txt"
                )
            }
            assertNoEditPipelineMutation()
        }

    @Test
    fun editCustomSource_explicitRepositoryResults_mapOneToOne() =
        runTest(testDispatcher) {
            val sourceId = 42
            val name = "Edited"
            val url = "https://edited.example.com/list.txt"

            val cases =
                listOf(
                    EditCustomSourceResult.InvalidInput(
                        CustomFilterSourceValidator.Error.INVALID_URL
                    ) to
                        CustomSourceEditState.InvalidInput(
                            CustomFilterSourceValidator.Error.INVALID_URL
                        ),
                    EditCustomSourceResult.SourceNotFound(sourceId) to
                        CustomSourceEditState.SourceNotFound(sourceId),
                    EditCustomSourceResult.NotCustomSource(sourceId) to
                        CustomSourceEditState.NotCustomSource(sourceId),
                    EditCustomSourceResult.SourceEnabled(sourceId) to
                        CustomSourceEditState.SourceEnabled(sourceId),
                    EditCustomSourceResult.DuplicateName("Existing Name") to
                        CustomSourceEditState.DuplicateName("Existing Name"),
                    EditCustomSourceResult.DuplicateUrl(
                        "https://existing.example.com/list.txt"
                    ) to
                        CustomSourceEditState.DuplicateUrl(
                            "https://existing.example.com/list.txt"
                        ),
                    EditCustomSourceResult.FileCleanupFailed(sourceId) to
                        CustomSourceEditState.FileCleanupFailed(sourceId)
                )

            cases.forEach { (repositoryResult, expectedState) ->
                val localViewModel =
                    ManageFilterSourcesViewModel(
                        repository,
                        compiler,
                        persistentState,
                        downloadManager
                    )
                coEvery {
                    repository.editCustomSource(sourceId, name, url)
                } returns repositoryResult

                val states = mutableListOf<CustomSourceEditState>()
                localViewModel.customSourceEdit.observeForever {
                    states.add(it)
                }

                localViewModel.editCustomSource(sourceId, name, url).join()
                advanceUntilIdle()

                assertEquals(
                    listOf(
                        CustomSourceEditState.Idle,
                        CustomSourceEditState.Editing(sourceId),
                        expectedState
                    ),
                    states
                )
            }

            coVerify(exactly = cases.size) {
                repository.editCustomSource(sourceId, name, url)
            }
            assertNoEditPipelineMutation()
        }

    @Test
    fun editCustomSource_unexpectedFailure_emitsFailed() =
        runTest(testDispatcher) {
            coEvery {
                repository.editCustomSource(any(), any(), any())
            } throws IllegalStateException("edit database failed")

            val states = collectCustomEditStates()
            val job =
                viewModel.editCustomSource(
                    43,
                    "Edited",
                    "https://edited.example.com/list.txt"
                )
            job.join()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    CustomSourceEditState.Idle,
                    CustomSourceEditState.Editing(43),
                    CustomSourceEditState.Failed(
                        43,
                        "edit database failed"
                    )
                ),
                states
            )
            assertTrue(job.isCompleted)
            assertFalse(job.isCancelled)
            assertNoEditPipelineMutation()
        }

    @Test
    fun editCustomSource_cancellation_resetsIdleAndRethrows() =
        runTest(testDispatcher) {
            val cancelled = CancellationException("edit cancelled")
            coEvery {
                repository.editCustomSource(any(), any(), any())
            } throws cancelled

            val states = collectCustomEditStates()
            val job =
                viewModel.editCustomSource(
                    44,
                    "Edited",
                    "https://edited.example.com/list.txt"
                )
            var completionCause: Throwable? = null
            job.invokeOnCompletion { cause ->
                completionCause = cause
            }
            job.join()
            advanceUntilIdle()

            assertSame(cancelled, completionCause)
            assertTrue(job.isCancelled)
            assertEquals(CustomSourceEditState.Idle, states.last())
            assertNoEditPipelineMutation()
        }

    @Test
    fun clearCustomSourceEditState_setsIdleWithoutPipelineMutation() =
        runTest(testDispatcher) {
            coEvery {
                repository.editCustomSource(any(), any(), any())
            } returns EditCustomSourceResult.DuplicateName("Existing")

            val states = collectCustomEditStates()
            viewModel.editCustomSource(
                45,
                "Existing",
                "https://alternative.example.com/list.txt"
            ).join()
            advanceUntilIdle()

            assertTrue(states.last() is CustomSourceEditState.DuplicateName)

            viewModel.clearCustomSourceEditState()

            assertEquals(CustomSourceEditState.Idle, states.last())
            coVerify(exactly = 1) {
                repository.editCustomSource(any(), any(), any())
            }
            assertNoEditPipelineMutation()
        }

    /**
     * Build a minimal [FilterSource] row for mock snapshots. Only the fields the
     * FIX-7B lookup consults ([id], [enabled]) need to be meaningful; remaining
     * columns keep their entity defaults so the mock surface stays compact.
     */
    private fun mockSource(id: Int, enabled: Boolean): FilterSource =
        FilterSource(
            id = id,
            name = "S$id",
            url = "https://$id.example/",
            category = FilterSourceCategory.CUSTOM,
            enabled = enabled,
            relativeFilePath = "filter_sources/source_$id/current.txt"
        )

    /**
     * Build a successful CompileOutcome carrying the fixed test hash directly. Constructing the
     * data class (not the success() factory) bypasses SHA computation and proves the ViewModel
     * reads outcome.enabledSetHash (the META-A field) rather than recomputing.
     */
    private fun successOutcome(hash: String = TEST_HASH): FilterSourceCompiler.CompileOutcome =
        FilterSourceCompiler.CompileOutcome(
            success = true,
            diagnostics = emptyList(),
            enabledSetHash = hash
        )

    /**
     * Observe the transaction LiveData synchronously (InstantTaskExecutorRule makes postValue
     * synchronous) and collect every emitted state in order, starting with the initial Idle.
     */
    private fun collectTransactionStates():
        MutableList<ManageFilterSourcesViewModel.TransactionState> {
        val states = mutableListOf<ManageFilterSourcesViewModel.TransactionState>()
        viewModel.transaction.observeForever { states.add(it) }
        return states
    }

    /**
     * CUSTOM-FILTER-B4 fixture: a disabled CUSTOM row shaped exactly like the row
     * [com.celzero.bravedns.database.FilterSourceRepository.addCustomSource] returns —
     * generated id > 0, category CUSTOM, enabled=false, non-preset, no catalog reference,
     * and a finalized id-derived relativeFilePath.
     */
    private fun disabledCustomSource(id: Int): FilterSource =
        FilterSource(
            id = id,
            name = "S$id",
            url = "https://custom.example.com/$id.txt",
            category = FilterSourceCategory.CUSTOM,
            enabled = false,
            isPreset = false,
            relativeFilePath = "filter_sources/source_$id/current.txt"
        )

    /**
     * Observe the custom-source-creation LiveData synchronously (InstantTaskExecutorRule makes
     * setValue synchronous) and collect every emitted state in order, starting with the
     * initial Idle.
     */
    private fun collectCustomCreationStates(): MutableList<CustomSourceCreationState> {
        val states = mutableListOf<CustomSourceCreationState>()
        viewModel.customSourceCreation.observeForever { states.add(it) }
        return states
    }
}

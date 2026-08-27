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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celzero.bravedns.core.filter.FilterSourceCompiler
import com.celzero.bravedns.database.AddCustomSourceResult
import com.celzero.bravedns.database.CustomFilterSourceValidator
import com.celzero.bravedns.database.EditCustomSourceResult
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceCategory
import com.celzero.bravedns.database.FilterSourceRepository
import com.celzero.bravedns.database.RemoveCustomSourceResult
import com.celzero.bravedns.download.FilterSourceDownloadManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.viewmodel.FilterSourceSummaryFormatter.FilterSourceSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * CUSTOM-FILTER-B4: observable lifecycle of a user-initiated custom filter-source creation.
 *
 * Emitted on [ManageFilterSourcesViewModel.customSourceCreation]:
 *  - [Idle] — no creation in flight (also the post-cancellation / explicit-reset state).
 *  - [Creating] — the metadata insert is in progress.
 *  - [Added] — exactly one disabled CUSTOM row was created; [Added.source] is the finalized
 *    row exactly as returned by [FilterSourceRepository.addCustomSource].
 *  - [InvalidInput] — [CustomFilterSourceValidator] rejected the name/URL; nothing was written.
 *  - [DuplicateName] — another source already has this trimmed, case-insensitive name.
 *  - [DuplicateUrl] — an existing row already stores this exact URL; nothing was mutated.
 *  - [Failed] — an unexpected database/filesystem failure; [Failed.message] is user-facing.
 */
sealed interface CustomSourceCreationState {
    data object Idle : CustomSourceCreationState
    data object Creating : CustomSourceCreationState

    data class Added(
        val source: FilterSource
    ) : CustomSourceCreationState

    data class InvalidInput(
        val error: CustomFilterSourceValidator.Error
    ) : CustomSourceCreationState

    data class DuplicateName(
        val name: String
    ) : CustomSourceCreationState

    data class DuplicateUrl(
        val url: String
    ) : CustomSourceCreationState

    data class Failed(
        val message: String
    ) : CustomSourceCreationState
}

/**
 * Observable lifecycle of a user-initiated custom-source Edit operation.
 *
 * Every terminal repository result has a distinct state so the UI does not infer failure
 * semantics from strings. Edit is serialized by the same transaction mutex used by Add and
 * enable/disable operations.
 */
sealed interface CustomSourceEditState {
    data object Idle : CustomSourceEditState

    data class Editing(
        val sourceId: Int
    ) : CustomSourceEditState

    data class Updated(
        val source: FilterSource
    ) : CustomSourceEditState

    data class InvalidInput(
        val error: CustomFilterSourceValidator.Error
    ) : CustomSourceEditState

    data class SourceNotFound(
        val sourceId: Int
    ) : CustomSourceEditState

    data class NotCustomSource(
        val sourceId: Int
    ) : CustomSourceEditState

    data class SourceEnabled(
        val sourceId: Int
    ) : CustomSourceEditState

    data class DuplicateName(
        val name: String
    ) : CustomSourceEditState

    data class DuplicateUrl(
        val url: String
    ) : CustomSourceEditState

    data class FileCleanupFailed(
        val sourceId: Int
    ) : CustomSourceEditState

    data class Failed(
        val sourceId: Int,
        val message: String
    ) : CustomSourceEditState
}

/**
 * Observable lifecycle of custom-source removal.
 *
 * An enabled source is first removed from the applied enabled set through compile+commit.
 * Repository filesystem/Room deletion occurs only after that applied-state transition.
 */
sealed interface CustomSourceRemovalState {
    data object Idle : CustomSourceRemovalState

    data class Removing(
        val sourceId: Int
    ) : CustomSourceRemovalState

    data class Removed(
        val sourceId: Int
    ) : CustomSourceRemovalState

    data class SourceNotFound(
        val sourceId: Int
    ) : CustomSourceRemovalState

    data class NotCustomSource(
        val sourceId: Int
    ) : CustomSourceRemovalState

    data class SourceEnabled(
        val sourceId: Int
    ) : CustomSourceRemovalState

    data class FileCleanupFailed(
        val sourceId: Int
    ) : CustomSourceRemovalState

    data class Failed(
        val sourceId: Int,
        val message: String
    ) : CustomSourceRemovalState
}

/**
 * ViewModel for the Manage Filters shell.
 *
 * B5 Slice-2R (read-only): exposes the live list of FilterSource rows, a derived aggregate
 * summary, and a derived category-grouped UI state. The summary is computed by
 * [FilterSourceSummaryFormatter] — the single source of truth for summary semantics shared
 * with the fdroid Plus tab. The category state is computed by [FilterSourceCategoryUi.group]
 * which deterministically groups by the canonical 8 categories, retains empty categories,
 * and orders sources deterministically within each group.
  */
class ManageFilterSourcesViewModel(
    private val repository: FilterSourceRepository,
    private val compiler: FilterSourceCompiler,
    private val persistentState: PersistentState,
    private val downloadManager: FilterSourceDownloadManager,
) : ViewModel() {

    val sources: LiveData<List<FilterSource>> = repository.getAllSourcesLiveData()

    private val _summary = MediatorLiveData<FilterSourceSummary>()
    val summary: LiveData<FilterSourceSummary> = _summary

    private val _categories = MediatorLiveData<List<FilterSourceCategoryUi>>()
    val categories: LiveData<List<FilterSourceCategoryUi>> = _categories

    // ---- Transaction state for setSourceEnabled ---------------------------------

    sealed class TransactionState {
        object Idle : TransactionState()

        data class Applying(
            val sourceId: Int
        ) : TransactionState()

        data class Success(
            val sourceId: Int,
            val enabled: Boolean
        ) : TransactionState()

        data class Failed(
            val sourceId: Int,
            val message: String
        ) : TransactionState()
    }

    private val _transaction =
        MediatorLiveData<TransactionState>().apply {
            value = TransactionState.Idle
        }

    val transaction: LiveData<TransactionState> = _transaction

    private val txMutex = Mutex()

    // ---- Custom source creation state (CUSTOM-FILTER-B4) ------------------------

    private val _customSourceCreation =
        MutableLiveData<CustomSourceCreationState>(CustomSourceCreationState.Idle)

    val customSourceCreation: LiveData<CustomSourceCreationState> =
        _customSourceCreation

    private val _customSourceEdit =
        MutableLiveData<CustomSourceEditState>(CustomSourceEditState.Idle)

    val customSourceEdit: LiveData<CustomSourceEditState> =
        _customSourceEdit

    private val _customSourceRemoval =
        MutableLiveData<CustomSourceRemovalState>(
            CustomSourceRemovalState.Idle
        )

    val customSourceRemoval: LiveData<CustomSourceRemovalState> =
        _customSourceRemoval

    /**
     * CUSTOM-FILTER-B4: create metadata for a user-entered custom filter source. Metadata
     * only — this never downloads, never compiles, never mutates [PersistentState], and
     * never enables the new row; the repository's returned [FilterSource] is carried through
     * unchanged.
     *
     * Enters [txMutex] so custom creation cannot interleave with a concurrent
     * enable/disable transaction's repository writes + compilation + state commits.
     *
     * Emits [CustomSourceCreationState.Creating], then maps the single
     * [FilterSourceRepository.addCustomSource] result onto its terminal state. Ordinary
     * exceptions surface as [CustomSourceCreationState.Failed] (not rethrown); cancellation
     * resets to [CustomSourceCreationState.Idle] and rethrows so the Job still reaches
     * Cancelled. After Added, Room's own LiveData ([sources]) is the source of truth — no
     * refresh() call is made.
     */
    fun createCustomSource(
        name: String,
        url: String
    ): Job = viewModelScope.launch {
        txMutex.withLock {
            _customSourceCreation.value = CustomSourceCreationState.Creating

            try {
                when (val result = repository.addCustomSource(name, url)) {
                    is AddCustomSourceResult.Added ->
                        _customSourceCreation.value =
                            CustomSourceCreationState.Added(result.source)

                    is AddCustomSourceResult.InvalidInput ->
                        _customSourceCreation.value =
                            CustomSourceCreationState.InvalidInput(result.error)

                    is AddCustomSourceResult.DuplicateName ->
                        _customSourceCreation.value =
                            CustomSourceCreationState.DuplicateName(result.name)

                    is AddCustomSourceResult.DuplicateUrl ->
                        _customSourceCreation.value =
                            CustomSourceCreationState.DuplicateUrl(result.url)
                }
            } catch (e: CancellationException) {
                _customSourceCreation.value = CustomSourceCreationState.Idle
                throw e
            } catch (e: Exception) {
                _customSourceCreation.value =
                    CustomSourceCreationState.Failed(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Reset [customSourceCreation] to [CustomSourceCreationState.Idle]. Pure UI-state
     * reset: touches no repository, downloader, compiler, or PersistentState component.
     */
    fun clearCustomSourceCreationState() {
        _customSourceCreation.value = CustomSourceCreationState.Idle
    }

    /**
     * Edit an existing disabled CUSTOM source.
     *
     * Uses [txMutex] so Edit cannot interleave with Add or enable/disable. Repository
     * results are mapped one-to-one onto [CustomSourceEditState]. Ordinary exceptions
     * become [CustomSourceEditState.Failed]. Cancellation resets state to Idle and is
     * rethrown so the returned Job reaches Cancelled.
     */
    fun editCustomSource(
        sourceId: Int,
        name: String,
        url: String
    ): Job = viewModelScope.launch {
        txMutex.withLock {
            _customSourceEdit.value =
                CustomSourceEditState.Editing(sourceId)

            try {
                when (
                    val result =
                        repository.editCustomSource(sourceId, name, url)
                ) {
                    is EditCustomSourceResult.Updated ->
                        _customSourceEdit.value =
                            CustomSourceEditState.Updated(result.source)

                    is EditCustomSourceResult.InvalidInput ->
                        _customSourceEdit.value =
                            CustomSourceEditState.InvalidInput(result.error)

                    is EditCustomSourceResult.SourceNotFound ->
                        _customSourceEdit.value =
                            CustomSourceEditState.SourceNotFound(result.sourceId)

                    is EditCustomSourceResult.NotCustomSource ->
                        _customSourceEdit.value =
                            CustomSourceEditState.NotCustomSource(result.sourceId)

                    is EditCustomSourceResult.SourceEnabled ->
                        _customSourceEdit.value =
                            CustomSourceEditState.SourceEnabled(result.sourceId)

                    is EditCustomSourceResult.DuplicateName ->
                        _customSourceEdit.value =
                            CustomSourceEditState.DuplicateName(result.name)

                    is EditCustomSourceResult.DuplicateUrl ->
                        _customSourceEdit.value =
                            CustomSourceEditState.DuplicateUrl(result.url)

                    is EditCustomSourceResult.FileCleanupFailed ->
                        _customSourceEdit.value =
                            CustomSourceEditState.FileCleanupFailed(result.sourceId)
                }
            } catch (e: CancellationException) {
                _customSourceEdit.value = CustomSourceEditState.Idle
                throw e
            } catch (e: Exception) {
                _customSourceEdit.value =
                    CustomSourceEditState.Failed(
                        sourceId,
                        e.message ?: "Unknown error"
                    )
            }
        }
    }

    /**
     * Reset only the custom-source Edit UI state.
     */
    fun clearCustomSourceEditState() {
        _customSourceEdit.value = CustomSourceEditState.Idle
    }

    /**
     * Remove a user-owned CUSTOM source while preserving applied-state truth.
     *
     * Disabled sources require no compilation. Enabled sources are first disabled in Room,
     * compiled out of the active enabled set, and committed. Failures before commit restore
     * enabled=true. Failures after commit never restore enabled=true because the applied rules
     * already exclude the source.
     */
    fun removeCustomSource(
        sourceId: Int
    ): Job = viewModelScope.launch {
        txMutex.withLock {
            _customSourceRemoval.value =
                CustomSourceRemovalState.Removing(sourceId)

            val current =
                try {
                    repository.getSourceById(sourceId)
                } catch (e: CancellationException) {
                    _customSourceRemoval.value =
                        CustomSourceRemovalState.Idle
                    throw e
                } catch (e: Exception) {
                    _customSourceRemoval.value =
                        CustomSourceRemovalState.Failed(
                            sourceId,
                            e.message ?: "lookup failed"
                        )
                    return@withLock
                }

            if (current == null) {
                _customSourceRemoval.value =
                    CustomSourceRemovalState.SourceNotFound(sourceId)
                return@withLock
            }

            if (
                current.category != FilterSourceCategory.CUSTOM ||
                    current.isPreset
            ) {
                _customSourceRemoval.value =
                    CustomSourceRemovalState.NotCustomSource(sourceId)
                return@withLock
            }

            var roomDisabled = false
            var compilationCommitted = false

            try {
                if (current.enabled) {
                    val currentlyEnabled =
                        repository.getEnabledSources()
                    val prospectiveEnabled =
                        currentlyEnabled.filterNot {
                            it.id == sourceId
                        }
                    val fileStore = repository.getFileStore()

                    for (prospectiveSource in prospectiveEnabled) {
                        if (
                            fileStore.currentFile(
                                prospectiveSource.id
                            ).exists()
                        ) {
                            continue
                        }

                        when (
                            val result =
                                downloadManager.downloadSource(
                                    prospectiveSource.id
                                )
                        ) {
                            is FilterSourceDownloadManager.DownloadResult.Failure -> {
                                _customSourceRemoval.value =
                                    CustomSourceRemovalState.Failed(
                                        sourceId,
                                        "filter source '${prospectiveSource.name}' " +
                                            "(id=${prospectiveSource.id}) download failed: " +
                                            result.errorMessage
                                    )
                                return@withLock
                            }

                            is FilterSourceDownloadManager.DownloadResult.Success -> {
                                if (
                                    !fileStore.currentFile(
                                        prospectiveSource.id
                                    ).exists()
                                ) {
                                    _customSourceRemoval.value =
                                        CustomSourceRemovalState.Failed(
                                            sourceId,
                                            "filter source '${prospectiveSource.name}' " +
                                                "(id=${prospectiveSource.id}) current.txt " +
                                                "not available"
                                        )
                                    return@withLock
                                }
                            }
                        }
                    }

                    repository.updateEnabledStatus(sourceId, false)
                    roomDisabled = true

                    val resultingEnabled =
                        repository.getEnabledSources()
                    val missing =
                        resultingEnabled.firstOrNull {
                            !fileStore.currentFile(it.id).exists()
                        }

                    if (missing != null) {
                        val rollbackError =
                            tryRollbackEnabled(sourceId, true)
                        val baseMessage =
                            "filter source '${missing.name}' " +
                                "(id=${missing.id}) current.txt not available"
                        val message =
                            if (rollbackError == null) {
                                baseMessage
                            } else {
                                "$baseMessage; $rollbackError"
                            }
                        _customSourceRemoval.value =
                            CustomSourceRemovalState.Failed(
                                sourceId,
                                message
                            )
                        return@withLock
                    }

                    val outcome = compiler.compileAllEnabled()
                    if (!outcome.success) {
                        val rollbackError =
                            tryRollbackEnabled(sourceId, true)
                        val baseMessage =
                            outcome.errorMessage ?: "compile failed"
                        val message =
                            if (rollbackError == null) {
                                baseMessage
                            } else {
                                "$baseMessage; $rollbackError"
                            }
                        _customSourceRemoval.value =
                            CustomSourceRemovalState.Failed(
                                sourceId,
                                message
                            )
                        return@withLock
                    }

                    val enabledSetHash =
                        requireNotNull(outcome.enabledSetHash) {
                            "Successful compilation missing enabledSetHash"
                        }

                    persistentState.commitAdvancedFilterCompilation(
                        enabledSetHash,
                        persistentState.advancedFilterGeneration + 1
                    )
                    compilationCommitted = true
                }

                when (
                    val result =
                        repository.removeDisabledCustomSource(sourceId)
                ) {
                    is RemoveCustomSourceResult.Removed ->
                        _customSourceRemoval.value =
                            CustomSourceRemovalState.Removed(
                                result.sourceId
                            )

                    is RemoveCustomSourceResult.SourceNotFound ->
                        _customSourceRemoval.value =
                            CustomSourceRemovalState.SourceNotFound(
                                result.sourceId
                            )

                    is RemoveCustomSourceResult.NotCustomSource ->
                        _customSourceRemoval.value =
                            CustomSourceRemovalState.NotCustomSource(
                                result.sourceId
                            )

                    is RemoveCustomSourceResult.SourceEnabled ->
                        _customSourceRemoval.value =
                            CustomSourceRemovalState.SourceEnabled(
                                result.sourceId
                            )

                    is RemoveCustomSourceResult.FileCleanupFailed ->
                        _customSourceRemoval.value =
                            CustomSourceRemovalState.FileCleanupFailed(
                                result.sourceId
                            )
                }
            } catch (e: CancellationException) {
                if (roomDisabled && !compilationCommitted) {
                    withContext(NonCancellable) {
                        tryRollbackEnabled(sourceId, true)
                    }
                }
                _customSourceRemoval.value =
                    CustomSourceRemovalState.Idle
                throw e
            } catch (e: Exception) {
                val rollbackError =
                    if (roomDisabled && !compilationCommitted) {
                        tryRollbackEnabled(sourceId, true)
                    } else {
                        null
                    }
                val baseMessage =
                    e.message ?: "removal failed"
                val message =
                    if (rollbackError == null) {
                        baseMessage
                    } else {
                        "$baseMessage; $rollbackError"
                    }
                _customSourceRemoval.value =
                    CustomSourceRemovalState.Failed(
                        sourceId,
                        message
                    )
            }
        }
    }

    fun clearCustomSourceRemovalState() {
        _customSourceRemoval.value =
            CustomSourceRemovalState.Idle
    }

    // ---- Write operations --------------------------------------------------------

    init {
        // Re-derive the summary + categories whenever the Room-backed source list changes.
        // addSource (not observeForever) binds the observation to the ViewModel scope and
        // avoids leaking on onCleared. Summary and categories are pure projections of the
        // same source list — they always agree at the same observed instant.
        _summary.addSource(sources) { list ->
            _summary.value = FilterSourceSummaryFormatter.compute(list)
        }
        _categories.addSource(sources) { list ->
            _categories.value = FilterSourceCategoryUi.group(list)
        }
    }

    /** Force a fresh aggregate + category computation from the current Room snapshot. */
    fun refresh() {
        viewModelScope.launch {
            val list = repository.getAllSources()
            _summary.value = FilterSourceSummaryFormatter.compute(list)
            _categories.value = FilterSourceCategoryUi.group(list)
        }
    }

    /**
     * Enable or disable a [FilterSource], recompile all enabled sources, and persist the
     * resulting generation bump. Serialized via [txMutex] so concurrent toggles cannot
     * interleave their repository writes + compilation + state commits.
     *
     * B5 Slice-3C-FIX-7B — Room rollback on failed application:
     *
     * FilterSource.enabled is the **APPLIED** state, not a desired-state buffer. If the
     * compilation, generation commit, or surrounding step fails AFTER Room has already been
     * mutated, the Room row is restored to [previousEnabled] so the UI switch follows the
     * active applied state (which FIX-7A guarantees equals the LKG state). No "desired vs
     * applied" divergence is introduced.
     *
     * B5 Slice-3C-FIX-7C — Resulting enabled-set availability guard:
     *
     * Before invoking the compiler, this method verifies that EVERY source in the resulting
     * enabled set has a `current.txt` on disk. If any source is missing its file the toggle
     * CANNOT succeed: compilation would fail or produce wrong output. In that case the
     * forward Room update is rolled back to [previousEnabled] and [TransactionState.Failed]
     * is emitted with a message naming the missing source. No compile is run, no second
     * compile is run, no generation/hash is committed. Empty resulting enabled set is a
     * legitimate state under FIX-7A (the compiler's `writeEmptyArtifact` path) and is NOT
     * treated as an availability failure.
     *
     * Transaction phases (in order):
     *  1. **Lookup** — fetch the source row to capture `previousEnabled`. If not found,
     *     emit Failed("source not found") and stop. No Room write, no compile, no commit.
     *  2. **No-op** — if `previousEnabled == enabled`, emit Success and stop. No write,
     *     no compile, no commit.
     *  3. **Real change** — apply Room update, then verify every source in the resulting
     *     enabled set has `current.txt` on disk (FIX-7C). On any failure path (availability
     *     miss, compile failure, exception, cancellation), attempt to restore
     *     `previousEnabled` and surface the rollback error (if any) in the emitted message.
     *     Only on success is the generation/hash committed exactly once.
     *
     * Cancellation: if a CancellationException is observed AFTER Room has been updated, the
     * rollback runs inside [NonCancellable] so the structured-concurrency cancellation cannot
     * interrupt the Room restore. The CancellationException is rethrown after rollback so the
     * launching coroutine's Job reaches the Cancelled state.
     *
     * Emits [TransactionState.Applying] → [TransactionState.Success] or
     * [TransactionState.Failed] on [transaction].
     */
    fun setSourceEnabled(
        sourceId: Int,
        enabled: Boolean
    ): Job = viewModelScope.launch {
        txMutex.withLock {
            _transaction.postValue(
                TransactionState.Applying(sourceId)
            )

            // ---- Phase 1: pre-flight lookup -------------------------------------
            // Exceptions during lookup cannot have mutated Room, so no rollback applies.
            val currentSource = try {
                repository.getAllSources().firstOrNull { it.id == sourceId }
            } catch (e: CancellationException) {
                _transaction.postValue(
                    TransactionState.Failed(sourceId, e.message ?: "cancelled")
                )
                throw e
            } catch (e: Exception) {
                _transaction.postValue(
                    TransactionState.Failed(sourceId, e.message ?: "lookup failed")
                )
                return@withLock
            }
            if (currentSource == null) {
                _transaction.postValue(
                    TransactionState.Failed(sourceId, "source not found")
                )
                return@withLock
            }
            val previousEnabled = currentSource.enabled

            // ---- Phase 2: same-state no-op --------------------------------------
            // Toggling to the already-applied state is a legitimate request that must
            // not bump the generation or trigger a redundant compile. FilterSource.enabled
            // is the applied state — there is no desired-state divergence to reconcile.
            if (previousEnabled == enabled) {
                _transaction.postValue(
                    TransactionState.Success(sourceId, enabled)
                )
                return@withLock
            }

            // ---- Phase 3: real change with rollback semantics --------------------
            // Room writes past this point MUST be reverted on any failure path so the
            // UI switch tracks the active applied state (= LKG state under FIX-7A).
            var roomUpdated = false
            try {
                // ---- Phase 3A: materialize prospective enabled set --------------
                // Download every missing prospective source before changing Room.
                // Stable order is currently-enabled sources first, then the target.
                val currentlyEnabled = repository.getEnabledSources()
                val prospectiveEnabled =
                    if (enabled) {
                        currentlyEnabled.filterNot { it.id == sourceId } +
                            currentSource
                    } else {
                        currentlyEnabled.filterNot { it.id == sourceId }
                    }
                val fileStore = repository.getFileStore()

                for (prospectiveSource in prospectiveEnabled) {
                    if (fileStore.currentFile(prospectiveSource.id).exists()) {
                        continue
                    }

                    val result =
                        downloadManager.downloadSource(prospectiveSource.id)

                    when (result) {
                        is FilterSourceDownloadManager.DownloadResult.Failure -> {
                            _transaction.postValue(
                                TransactionState.Failed(
                                    sourceId,
                                    "filter source '${prospectiveSource.name}' " +
                                        "(id=${prospectiveSource.id}) download failed: " +
                                        result.errorMessage
                                )
                            )
                            return@withLock
                        }
                        is FilterSourceDownloadManager.DownloadResult.Success -> {
                            if (
                                !fileStore.currentFile(prospectiveSource.id).exists()
                            ) {
                                _transaction.postValue(
                                    TransactionState.Failed(
                                        sourceId,
                                        "filter source '${prospectiveSource.name}' " +
                                            "(id=${prospectiveSource.id}) current.txt " +
                                            "not available"
                                    )
                                )
                                return@withLock
                            }
                        }
                    }
                }

                repository.updateEnabledStatus(sourceId, enabled)
                roomUpdated = true

                // ---- FIX-7C: resulting enabled-set availability guard -----------
                // Every source that will be enabled after this transaction MUST have
                // its `current.txt` on disk; otherwise compilation cannot succeed (or
                // would produce a wrong artifact). Fetch the resulting enabled set,
                // then check each entry's file. Empty set is a legitimate state
                // (FIX-7A's explicit-empty path: `writeEmptyArtifact`) and MUST NOT
                // be treated as an availability failure.
                val enabledSet = repository.getEnabledSources()

                if (enabledSet.isNotEmpty()) {
                    val missing =
                        enabledSet.firstOrNull {
                            !fileStore.currentFile(it.id).exists()
                        }
                    if (missing != null) {
                        val rollbackErr =
                            tryRollbackEnabled(sourceId, previousEnabled)
                        val baseMsg =
                            "filter source '${missing.name}' (id=${missing.id}) current.txt not available"
                        val finalMsg =
                            if (rollbackErr != null) "$baseMsg; $rollbackErr" else baseMsg
                        _transaction.postValue(
                            TransactionState.Failed(sourceId, finalMsg)
                        )
                        return@withLock
                    }
                }

                val outcome = compiler.compileAllEnabled()

                if (!outcome.success) {
                    val rollbackErr =
                        tryRollbackEnabled(sourceId, previousEnabled)
                    val baseMsg = outcome.errorMessage ?: "compile failed"
                    val finalMsg =
                        if (rollbackErr != null) "$baseMsg; $rollbackErr" else baseMsg
                    _transaction.postValue(
                        TransactionState.Failed(sourceId, finalMsg)
                    )
                    return@withLock
                }

                val enabledSetHash =
                    requireNotNull(outcome.enabledSetHash) {
                        "Successful compilation missing enabledSetHash"
                    }

                persistentState.commitAdvancedFilterCompilation(
                    enabledSetHash,
                    persistentState.advancedFilterGeneration + 1
                )

                _transaction.postValue(
                    TransactionState.Success(sourceId, enabled)
                )
            } catch (e: CancellationException) {
                // Cancellation must not interrupt the Room restore: run the rollback
                // inside NonCancellable so the Job cancellation cannot preempt the
                // restore, then rethrow so the launching coroutine reaches Cancelled.
                val rollbackErr =
                    if (roomUpdated) {
                        withContext(NonCancellable) {
                            tryRollbackEnabled(sourceId, previousEnabled)
                        }
                    } else {
                        null
                    }
                val baseMsg = e.message ?: "cancelled"
                val finalMsg =
                    if (rollbackErr != null) "$baseMsg; $rollbackErr" else baseMsg
                _transaction.postValue(
                    TransactionState.Failed(sourceId, finalMsg)
                )
                throw e
            } catch (e: Exception) {
                val rollbackErr =
                    if (roomUpdated) {
                        tryRollbackEnabled(sourceId, previousEnabled)
                    } else {
                        null
                    }
                val baseMsg = e.message ?: "transaction failed"
                val finalMsg =
                    if (rollbackErr != null) "$baseMsg; $rollbackErr" else baseMsg
                _transaction.postValue(
                    TransactionState.Failed(sourceId, finalMsg)
                )
            }
        }
    }

    /**
     * Attempt to restore [FilterSource.enabled] to [previousEnabled] for [sourceId].
     * Returns `null` on success, or a non-null human-readable error string describing the
     * rollback failure. The contract is intentionally non-silent: per FIX-7B the rollback
     * failure MUST be surfaced in the emitted [TransactionState.Failed.message] rather than
     * swallowed, so callers can distinguish "compile failed, room restored" from
     * "compile failed, room permanently out of sync".
     */
    private suspend fun tryRollbackEnabled(
        sourceId: Int,
        previousEnabled: Boolean
    ): String? {
        return try {
            repository.updateEnabledStatus(sourceId, previousEnabled)
            null
        } catch (e: Exception) {
            "rollback failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}

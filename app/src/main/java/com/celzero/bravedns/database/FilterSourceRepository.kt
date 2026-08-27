/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.database

import androidx.lifecycle.LiveData
import androidx.room.Transaction

/**
 * Repository for Advanced Filter Source metadata. B1 responsibilities only:
 *  - CRUD over [FilterSourceDao] (no HTTP, no parser, no FilterEngine, no VPN restart).
 *  - Coordinate [FilterSource] metadata writes with [FilterSourceFileStore] directory lifetime
 *    (plan §12–§15): when a source is deleted, its `source_<id>/` directory is cleaned up; the
 *    sibling root `filter_sources/` is NEVER removed.
 *  - Finalize the generated-id → relativeFilePath linkage atomically (plan §11):
 *    `addSource(...)` inserts with `id = 0` (Room auto-generates the primary key), captures
 *    that id, derives `filter_sources/source_<id>/current.txt`, and persists the final path
 *    inside the same [Transaction]. LiveData observers never observe a row with a permanently
 *    wrong relativeFilePath.
 *  - Delegate catalog-driven reconciliation to [syncCatalog]; initial preset seeding is owned
 *    by Room migrations (MIGRATION_30_31 / MIGRATION_31_32). No runtime catalog is re-seeded
 *    from a hardcoded in-memory list; user-custom sources are never silently rewritten
 *    (plan §16 forbids SILENT fabrication of SOCIAL/SECURITY/etc rows).
 *
 * NOT in scope (defensive checks below prevent accidental drift):
 *  - No HTTP I/O, no WorkManager, no OkHttp clients (B2 owns download).
 *  - No FilterEngine reload, no `BraveVPNService` signal (B3/B4 own that lifecycle).
 *  - No Plus UI wiring (Out-of-scope §22).
 */
/**
 * CUSTOM-FILTER-B3: outcome of a user-initiated custom filter-source add.
 *
 *  - [Added] — exactly one disabled CUSTOM row was inserted atomically by
 *    [FilterSourceDao.insertCustomAtomically]; the finalized row is carried in [Added.source].
 *  - [InvalidInput] — [CustomFilterSourceValidator] rejected the name/URL; no DAO write and no
 *    filesystem operation occurred.
 *  - [DuplicateName] — another custom or catalog-owned row has the same trimmed,
 *    case-insensitive name; nothing was mutated.
 *  - [DuplicateUrl] — an existing row (custom OR catalog-owned) already stores the exact URL;
 *    nothing was mutated.
 */
sealed interface AddCustomSourceResult {
    data class Added(
        val source: FilterSource
    ) : AddCustomSourceResult

    data class InvalidInput(
        val error: CustomFilterSourceValidator.Error
    ) : AddCustomSourceResult

    data class DuplicateName(
        val name: String
    ) : AddCustomSourceResult

    data class DuplicateUrl(
        val url: String
    ) : AddCustomSourceResult
}

/**
 * Result of editing an existing user-owned CUSTOM source.
 *
 * Edit is permitted only while the source is disabled. Changing only the name preserves
 * downloaded files and diagnostics. Changing the URL removes the old source directory and
 * clears all download/compilation diagnostics so the next enable downloads the new URL.
 */
sealed interface EditCustomSourceResult {
    data class Updated(
        val source: FilterSource
    ) : EditCustomSourceResult

    data class InvalidInput(
        val error: CustomFilterSourceValidator.Error
    ) : EditCustomSourceResult

    data class SourceNotFound(
        val sourceId: Int
    ) : EditCustomSourceResult

    data class NotCustomSource(
        val sourceId: Int
    ) : EditCustomSourceResult

    data class SourceEnabled(
        val sourceId: Int
    ) : EditCustomSourceResult

    data class DuplicateName(
        val name: String
    ) : EditCustomSourceResult

    data class DuplicateUrl(
        val url: String
    ) : EditCustomSourceResult

    data class FileCleanupFailed(
        val sourceId: Int
    ) : EditCustomSourceResult
}

/**
 * Result of removing a user-owned CUSTOM source.
 *
 * The repository accepts only disabled sources. The ViewModel transaction owner must first
 * remove an enabled source from the applied enabled set and commit the resulting compilation.
 */
sealed interface RemoveCustomSourceResult {
    data class Removed(
        val sourceId: Int
    ) : RemoveCustomSourceResult

    data class SourceNotFound(
        val sourceId: Int
    ) : RemoveCustomSourceResult

    data class NotCustomSource(
        val sourceId: Int
    ) : RemoveCustomSourceResult

    data class SourceEnabled(
        val sourceId: Int
    ) : RemoveCustomSourceResult

    data class FileCleanupFailed(
        val sourceId: Int
    ) : RemoveCustomSourceResult
}

class FilterSourceRepository(
    private val filterSourceDao: FilterSourceDao,
    private val fileStore: FilterSourceFileStore
) {

    companion object {
        private const val TAG = "FilterSourceRepo"
    }

    fun getAllSourcesLiveData(): LiveData<List<FilterSource>> =
        filterSourceDao.getAllSourcesLiveData()

    suspend fun getAllSources(): List<FilterSource> = filterSourceDao.getAllSources()

    suspend fun getEnabledSources(): List<FilterSource> = filterSourceDao.getEnabledSources()

    suspend fun getSourceById(id: Int): FilterSource? = filterSourceDao.getSourceById(id)

    suspend fun findByUrl(url: String): FilterSource? = filterSourceDao.findByUrl(url)

    suspend fun count(): Int = filterSourceDao.count()

    /**
     * Insert a user-custom FilterSource and finalize its on-disk path under
     * `filter_sources/source_<generated-id>/current.txt` atomically (plan §11). The
     * `id = 0` constant triggers Room auto-generation; the captured id is then used to
     * derive and persist the final [FilterSource.relativeFilePath].
     *
     * @return the inserted [FilterSource] with its generated id & finalized relativeFilePath.
     */
    @Transaction
    suspend fun addSource(
        name: String,
        url: String,
        category: String,
        enabled: Boolean = true,
        isPreset: Boolean = false
    ): FilterSource {
        // Insert with `id = 0` so Room auto-generates. relativeFilePath is finalized inside this
        // same transaction so observers see the final state only.
        val placeholder = FilterSource(
            id = 0,
            name = name,
            url = url,
            category = category,
            enabled = enabled,
            isPreset = isPreset,
            relativeFilePath = "" // finalized post-insert
        )
        val generatedId = filterSourceDao.insert(placeholder).toInt()
        // Autogen contract: Room returns the new rowid, always > 0.
        require(generatedId > 0) {
            "FilterSource insert returned non-positive id ($generatedId); auto-increment broken?"
        }
        val relativePath = FilterSourceFileStore.relativeFilePathFor(generatedId)
        filterSourceDao.updateRelativeFilePath(generatedId, relativePath)
        // Ensure filesystem parent exists for the new source directory (cheap idempotent mkdir).
        // Per-source directory is created on demand by B2 once it has content to write.
        fileStore.ensureRootExists()
        return filterSourceDao.getSourceById(generatedId)
            ?: error("FilterSource row missing immediately after insert+update")
    }

    @Transaction
    suspend fun update(source: FilterSource) {
        filterSourceDao.update(source)
    }

    @Transaction
    suspend fun delete(source: FilterSource) {
        filterSourceDao.delete(source)
        // Remove the per-source directory but never the filter_sources root.
        fileStore.removeSourceDirectory(source.id)
    }

    @Transaction
    suspend fun deleteById(id: Int) {
        filterSourceDao.deleteById(id)
        fileStore.removeSourceDirectory(id)
    }

    suspend fun updateEnabledStatus(id: Int, enabled: Boolean) {
        filterSourceDao.updateEnabledStatus(id, enabled)
    }

    suspend fun updateStatus(id: Int, status: String, error: String? = null) {
        filterSourceDao.updateStatus(id, status, error)
    }

    suspend fun updateDownloadSuccess(
        id: Int,
        etag: String?,
        lastModified: String?,
        checksum: String?,
        lastUpdated: Long = System.currentTimeMillis()
    ) {
        filterSourceDao.updateDownloadSuccess(
            id = id,
            status = FilterSourceStatus.SUCCESS,
            errorMessage = null,
            etag = etag,
            lastModified = lastModified,
            checksum = checksum,
            lastUpdated = lastUpdated
        )
    }

    suspend fun updateDownloadNotModified(
        id: Int,
        etag: String?,
        lastModified: String?,
        lastUpdated: Long = System.currentTimeMillis()
    ) {
        filterSourceDao.updateDownloadNotModified(
            id = id,
            status = FilterSourceStatus.SUCCESS,
            errorMessage = null,
            etag = etag,
            lastModified = lastModified,
            lastUpdated = lastUpdated
        )
    }

    suspend fun updateDownloadFailure(id: Int, errorMessage: String) {
        filterSourceDao.updateDownloadFailure(
            id = id,
            status = FilterSourceStatus.FAILED,
            errorMessage = errorMessage
        )
    }

    // ---- B3 Filter Source Compiler diagnostics ------------------------------------
    suspend fun updateCompilationDiagnostics(
        id: Int,
        totalLineCount: Int,
        parsedRuleCount: Int,
        unsupportedRuleCount: Int,
        invalidRuleCount: Int,
        networkRuleCount: Int,
        cosmeticRuleCount: Int,
        proceduralRuleCount: Int,
        scriptletRuleCount: Int,
        cspRuleCount: Int,
        htmlFilterRuleCount: Int,
        lastUpdated: Long = System.currentTimeMillis()
    ) {
        filterSourceDao.updateCompilationDiagnostics(
            id = id,
            totalLineCount = totalLineCount,
            parsedRuleCount = parsedRuleCount,
            unsupportedRuleCount = unsupportedRuleCount,
            invalidRuleCount = invalidRuleCount,
            networkRuleCount = networkRuleCount,
            cosmeticRuleCount = cosmeticRuleCount,
            proceduralRuleCount = proceduralRuleCount,
            scriptletRuleCount = scriptletRuleCount,
            cspRuleCount = cspRuleCount,
            htmlFilterRuleCount = htmlFilterRuleCount,
            lastUpdated = lastUpdated
        )
    }

    /** Expose the underlying [FilterSourceFileStore] for file lifecycle coordination. */
    fun getFileStore(): FilterSourceFileStore = fileStore

    /**
     * Edit a disabled, user-owned CUSTOM source.
     *
     * The source id and ownership fields never change. A name-only edit preserves the
     * downloaded body and every status/diagnostic field. A URL change removes the old
     * source directory before Room is updated, then resets all download and compilation
     * metadata. If cleanup fails, Room remains untouched.
     */
    suspend fun editCustomSource(
        sourceId: Int,
        name: String,
        url: String
    ): EditCustomSourceResult {
        val validated =
            when (val validation = CustomFilterSourceValidator.validate(name, url)) {
                is CustomFilterSourceValidator.Result.Invalid ->
                    return EditCustomSourceResult.InvalidInput(validation.error)

                is CustomFilterSourceValidator.Result.Valid ->
                    validation
            }

        val current =
            filterSourceDao.getSourceById(sourceId)
                ?: return EditCustomSourceResult.SourceNotFound(sourceId)

        if (
            current.category != FilterSourceCategory.CUSTOM ||
                current.isPreset
        ) {
            return EditCustomSourceResult.NotCustomSource(sourceId)
        }

        if (current.enabled) {
            return EditCustomSourceResult.SourceEnabled(sourceId)
        }

        val duplicateName =
            filterSourceDao.findByNameIgnoringCaseExcludingId(
                validated.name,
                sourceId
            )
        if (duplicateName != null) {
            return EditCustomSourceResult.DuplicateName(duplicateName.name)
        }

        val duplicateUrl =
            filterSourceDao.findByUrlExcludingId(
                validated.url,
                sourceId
            )
        if (duplicateUrl != null) {
            return EditCustomSourceResult.DuplicateUrl(duplicateUrl.url)
        }

        val nameChanged = current.name != validated.name
        val urlChanged = current.url != validated.url

        if (!nameChanged && !urlChanged) {
            return EditCustomSourceResult.Updated(current)
        }

        if (urlChanged && !fileStore.removeSourceDirectory(sourceId)) {
            return EditCustomSourceResult.FileCleanupFailed(sourceId)
        }

        val updated =
            if (urlChanged) {
                current.copy(
                    name = validated.name,
                    url = validated.url,
                    lastUpdated = 0L,
                    lastUpdateStatus = FilterSourceStatus.IDLE,
                    errorMessage = null,
                    etag = null,
                    lastModified = null,
                    checksum = null,
                    totalLineCount = 0,
                    parsedRuleCount = 0,
                    unsupportedRuleCount = 0,
                    invalidRuleCount = 0,
                    networkRuleCount = 0,
                    cosmeticRuleCount = 0,
                    proceduralRuleCount = 0,
                    scriptletRuleCount = 0,
                    cspRuleCount = 0,
                    htmlFilterRuleCount = 0
                )
            } else {
                current.copy(name = validated.name)
            }

        filterSourceDao.update(updated)
        return EditCustomSourceResult.Updated(updated)
    }

    /**
     * Remove a disabled, user-owned CUSTOM source.
     *
     * Filesystem cleanup precedes the Room delete. A cleanup failure therefore retains the
     * metadata row and can be retried. [FilterSourceFileStore.removeSourceDirectory] removes
     * only source_<id> and never the shared filter_sources root.
     */
    suspend fun removeDisabledCustomSource(
        sourceId: Int
    ): RemoveCustomSourceResult {
        val current =
            filterSourceDao.getSourceById(sourceId)
                ?: return RemoveCustomSourceResult.SourceNotFound(sourceId)

        if (
            current.category != FilterSourceCategory.CUSTOM ||
                current.isPreset
        ) {
            return RemoveCustomSourceResult.NotCustomSource(sourceId)
        }

        if (current.enabled) {
            return RemoveCustomSourceResult.SourceEnabled(sourceId)
        }

        if (!fileStore.removeSourceDirectory(sourceId)) {
            return RemoveCustomSourceResult.FileCleanupFailed(sourceId)
        }

        filterSourceDao.deleteById(sourceId)
        return RemoveCustomSourceResult.Removed(sourceId)
    }

    suspend fun syncCatalog(
        catalog: FilterSourceCatalogJson.Catalog
    ): ReconciliationPlan {
        val candidates = FilterSourceCatalogMapper.map(catalog)
        return filterSourceDao.syncCatalogAtomically(candidates)
    }

    /**
     * CUSTOM-FILTER-B3: validate user-entered name/URL and create exactly one DISABLED custom
     * [FilterSource] row. Ordered behavior:
     *  1. [CustomFilterSourceValidator.validate] — on `Invalid`, return [AddCustomSourceResult.InvalidInput]
     *     with zero DAO writes and zero filesystem operations.
     *  2. Trimmed, case-insensitive name duplicate pre-check via
     *     [FilterSourceDao.findByNameIgnoringCase] (covers both custom and catalog-owned rows) —
     *     on hit, return [AddCustomSourceResult.DuplicateName] with the existing stored name
     *     without creating any directory or writing Room.
     *  3. Exact-URL duplicate pre-check via [FilterSourceDao.findByUrl] (covers both custom and
     *     catalog-owned rows) — on hit, return [AddCustomSourceResult.DuplicateUrl] without
     *     creating any directory or writing Room.
     *  4. [FilterSourceFileStore.ensureRootExists] BEFORE inserting — if root creation fails, no
     *     metadata row is left behind; root creation is idempotent and source-independent.
     *  5. Exactly one [FilterSourceDao.insertCustomAtomically] call (DAO owns transactional
     *     atomicity; this method is deliberately NOT `@Transaction`).
     *  6. Concurrent name/URL race: if the insert throws [IllegalArgumentException] because a
     *     second writer won the race, re-query [FilterSourceDao.findByNameIgnoringCase] then
     *     [FilterSourceDao.findByUrl] in the same precedence order as the pre-check; a found
     *     name yields [AddCustomSourceResult.DuplicateName], a found URL yields
     *     [AddCustomSourceResult.DuplicateUrl], otherwise the original exception is rethrown.
     *
     * Duplicate names are rejected after trim with case-insensitive comparison. Unexpected
     * database/filesystem failures propagate untouched; [kotlinx.coroutines.CancellationException]
     * is never caught (it is not an [IllegalArgumentException]). The returned source always has
     * `enabled=false`.
     */
    suspend fun addCustomSource(
        name: String,
        url: String
    ): AddCustomSourceResult {
        val validated = when (val validation = CustomFilterSourceValidator.validate(name, url)) {
            is CustomFilterSourceValidator.Result.Invalid ->
                return AddCustomSourceResult.InvalidInput(validation.error)
            is CustomFilterSourceValidator.Result.Valid -> validation
        }

        val existingName =
            filterSourceDao.findByNameIgnoringCase(validated.name)
        if (existingName != null) {
            return AddCustomSourceResult.DuplicateName(existingName.name)
        }

        if (filterSourceDao.findByUrl(validated.url) != null) {
            return AddCustomSourceResult.DuplicateUrl(validated.url)
        }

        // Root creation must precede the metadata insert: a failed mkdir leaves zero rows.
        fileStore.ensureRootExists()

        return try {
            AddCustomSourceResult.Added(
                filterSourceDao.insertCustomAtomically(validated.name, validated.url)
            )
        } catch (e: IllegalArgumentException) {
            // Re-query in the same precedence order as the pre-check so a concurrent
            // name or URL insertion is mapped onto its explicit result.
            val racedName =
                filterSourceDao.findByNameIgnoringCase(validated.name)
            if (racedName != null) {
                AddCustomSourceResult.DuplicateName(racedName.name)
            } else if (filterSourceDao.findByUrl(validated.url) != null) {
                AddCustomSourceResult.DuplicateUrl(validated.url)
            } else {
                throw e
            }
        }
    }
}

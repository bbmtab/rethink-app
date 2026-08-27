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
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface FilterSourceDao {

    @Query("SELECT * FROM FilterSource ORDER BY isPreset DESC, id ASC")
    fun getAllSourcesLiveData(): LiveData<List<FilterSource>>

    @Query("SELECT * FROM FilterSource ORDER BY isPreset DESC, id ASC")
    suspend fun getAllSources(): List<FilterSource>

    @Query("SELECT * FROM FilterSource WHERE enabled = 1")
    suspend fun getEnabledSources(): List<FilterSource>

    @Query("SELECT * FROM FilterSource WHERE id = :id")
    suspend fun getSourceById(id: Int): FilterSource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: FilterSource): Long

    /**
     * Dedicated preset-seed insert. ABORT (never REPLACE/IGNORE) so any unexpected conflict
     * surfaces as an exception instead of silently overwriting an existing row.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPresetAbort(source: FilterSource): Long

    /**
     * Dedicated custom-source insert. ABORT (never REPLACE/IGNORE) so any unexpected conflict
     * surfaces as an exception instead of silently overwriting an existing row.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCustomAbort(source: FilterSource): Long

    /**
     * CUSTOM-FILTER-B2: Insert a disabled custom source atomically in one Room transaction.
     *
     * Behavior:
     *  - Rejects blank [name] / [url] with [IllegalArgumentException].
     *  - Rejects trimmed, case-insensitive name duplicates (any custom or catalog/preset row)
     *    with [IllegalArgumentException].
     *  - Rejects exact stored-URL duplicates (any custom or catalog/preset row) with
     *    [IllegalArgumentException]. URL comparison remains exact string equality.
     *  - Inserts a placeholder row (id=0, category=CUSTOM, enabled=false, isPreset=false,
     *    referenceId=null, relativeFilePath="") via [insertCustomAbort] (ABORT).
     *  - Derives the final relativeFilePath from the generated id via
     *    [FilterSourceFileStore.relativeFilePathFor] and persists it with
     *    [updateRelativeFilePath], still inside the same transaction.
     *  - Re-reads and returns the finalized row.
     *  - No filesystem operations occur inside this DAO method.
     */
    @Transaction
    suspend fun insertCustomAtomically(
        name: String,
        url: String
    ): FilterSource {
        require(name.isNotBlank()) { "insertCustomAtomically: name must not be blank" }
        require(url.isNotBlank()) { "insertCustomAtomically: url must not be blank" }

        val existingName =
            getAllSources().firstOrNull {
                it.name.trim().equals(name.trim(), ignoreCase = true)
            }
        if (existingName != null) {
            throw IllegalArgumentException(
                "insertCustomAtomically: source with the same name already exists " +
                    "(id=${existingName.id})"
            )
        }

        val existingUrl = findByUrl(url)
        if (existingUrl != null) {
            throw IllegalArgumentException(
                "insertCustomAtomically: source with the same URL already exists " +
                    "(id=${existingUrl.id})"
            )
        }

        val placeholder = FilterSource(
            id = 0,
            name = name,
            url = url,
            category = FilterSourceCategory.CUSTOM,
            enabled = false,
            isPreset = false,
            relativeFilePath = "",
            referenceId = null
        )
        val generatedId = insertCustomAbort(placeholder).toInt()
        require(generatedId > 0) {
            "insertCustomAtomically: non-positive generated id ($generatedId); auto-increment broken?"
        }

        val relativePath = FilterSourceFileStore.relativeFilePathFor(generatedId)
        updateRelativeFilePath(generatedId, relativePath)

        return getSourceById(generatedId)
            ?: error("insertCustomAtomically: row $generatedId missing immediately after insert+update")
    }

    @Update
    suspend fun update(source: FilterSource)

    @Delete
    suspend fun delete(source: FilterSource)

    @Query("UPDATE FilterSource SET enabled = :enabled WHERE id = :id")
    suspend fun updateEnabledStatus(id: Int, enabled: Boolean)

    @Query("UPDATE FilterSource SET lastUpdateStatus = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: Int, status: String, error: String?)

    @Query(
        "UPDATE FilterSource SET lastUpdateStatus = :status, errorMessage = :errorMessage, etag = :etag, lastModified = :lastModified, checksum = :checksum, lastUpdated = :lastUpdated WHERE id = :id"
    )
    suspend fun updateDownloadSuccess(
        id: Int,
        status: String,
        errorMessage: String?,
        etag: String?,
        lastModified: String?,
        checksum: String?,
        lastUpdated: Long
    )

    @Query(
        "UPDATE FilterSource SET lastUpdateStatus = :status, errorMessage = :errorMessage, etag = :etag, lastModified = :lastModified, lastUpdated = :lastUpdated WHERE id = :id"
    )
    suspend fun updateDownloadNotModified(
        id: Int,
        status: String,
        errorMessage: String?,
        etag: String?,
        lastModified: String?,
        lastUpdated: Long
    )

    @Query(
        "UPDATE FilterSource SET lastUpdateStatus = :status, errorMessage = :errorMessage WHERE id = :id"
    )
    suspend fun updateDownloadFailure(id: Int, status: String, errorMessage: String?)

    @Query(
        "UPDATE FilterSource SET totalLineCount = :totalLineCount, parsedRuleCount = :parsedRuleCount, unsupportedRuleCount = :unsupportedRuleCount, invalidRuleCount = :invalidRuleCount, networkRuleCount = :networkRuleCount, cosmeticRuleCount = :cosmeticRuleCount, proceduralRuleCount = :proceduralRuleCount, scriptletRuleCount = :scriptletRuleCount, cspRuleCount = :cspRuleCount, htmlFilterRuleCount = :htmlFilterRuleCount, lastUpdated = :lastUpdated WHERE id = :id"
    )
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
        lastUpdated: Long
    )

    // ---- B1-only helpers -------------------------------------------------------
    // Preset identity by approved URL so seeding is idempotent by stable property, not by name.
    @Query("SELECT * FROM FilterSource WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): FilterSource?

    /**
     * Find the first source whose trimmed name equals [name] ignoring case.
     *
     * Comparison is deliberately performed in Kotlin rather than SQLite NOCASE so names
     * outside SQLite's ASCII-only NOCASE behavior receive Kotlin's case-insensitive handling.
     * This method performs no writes.
     */
    @Transaction
    suspend fun findByNameIgnoringCase(name: String): FilterSource? {
        val normalizedName = name.trim()
        return getAllSources().firstOrNull {
            it.name.trim().equals(normalizedName, ignoreCase = true)
        }
    }

    /**
     * Find the first source other than [excludedId] whose trimmed name equals [name]
     * ignoring case. Used by custom-source Edit so retaining the source's own name is valid.
     */
    @Transaction
    suspend fun findByNameIgnoringCaseExcludingId(
        name: String,
        excludedId: Int
    ): FilterSource? {
        val normalizedName = name.trim()
        return getAllSources().firstOrNull {
            it.id != excludedId &&
                it.name.trim().equals(normalizedName, ignoreCase = true)
        }
    }

    /**
     * Find a source other than [excludedId] with the exact stored URL.
     */
    @Query(
        "SELECT * FROM FilterSource " +
            "WHERE url = :url AND id != :excludedId LIMIT 1"
    )
    suspend fun findByUrlExcludingId(
        url: String,
        excludedId: Int
    ): FilterSource?

    // Generated-id → filesystem path finalization. After an insert returns the row id, the
    // repository persists the final relativeFilePath derived from that id.
    @Query("UPDATE FilterSource SET relativeFilePath = :relativeFilePath WHERE id = :id")
    suspend fun updateRelativeFilePath(id: Int, relativeFilePath: String)

    @Query("DELETE FROM FilterSource WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT COUNT(*) FROM FilterSource")
    suspend fun count(): Int

    /**
     * B5 JSON-CATALOG-S4B: Atomically reconcile the existing FilterSource table against a
     * catalog-derived candidate set inside a single Room-managed transaction.
     *
     * Behavior:
     *  - Reads all existing rows and computes a [ReconciliationPlan] via
     *    [FilterSourceCatalogReconciler.reconcile], entirely inside the SQLite transaction.
     *  - Inserts every [plan.inserts] row via the existing [insertPresetAbort] (ABORT).
     *  - Updates every [plan.updates] row by merging catalog-owned metadata (name, url,
     *    category, isPreset, referenceId) onto the EXISTING row, preserving all runtime/user
     *    state (enabled, relativeFilePath, diagnostics, checksum, status, errorMessage,
     *    etag, lastModified, lastUpdated). The merged row is written with the existing [update].
     *  - Does NOT touch [plan.unchangedIds].
     *  - Does NOT delete any row.
     *  - Returns the complete [ReconciliationPlan] (including [ReconciliationPlan.urlChangedIds]).
     *  - Any reconciliation or insert/update failure rolls back the whole transaction.
     */
    @Transaction
    suspend fun syncCatalogAtomically(
        candidates: List<FilterSource>
    ): ReconciliationPlan {
        val existing = getAllSources()
        val plan = FilterSourceCatalogReconciler.reconcile(existing, candidates)

        // Catalog-owned metadata fields overwritten from the candidate onto the existing row.
        // Runtime/user state below is intentionally carried from the existing row verbatim:
        //   enabled, lastUpdated, lastUpdateStatus, errorMessage, etag, lastModified,
        //   checksum, diagnostics (totalLineCount..htmlFilterRuleCount), relativeFilePath.
        val existingById = existing.associateBy { it.id }
        plan.updates.forEach { candidate ->
            val current = existingById[candidate.id]
            require(current != null) {
                "syncCatalogAtomically: update candidate id=${candidate.id} has no existing row"
            }
            val merged = current.copy(
                name = candidate.name,
                url = candidate.url,
                category = candidate.category,
                isPreset = candidate.isPreset,
                referenceId = candidate.referenceId
            )
            update(merged)
        }

        plan.inserts.forEach { insertPresetAbort(it) }

        return plan
    }
}

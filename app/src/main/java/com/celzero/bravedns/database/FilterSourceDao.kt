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

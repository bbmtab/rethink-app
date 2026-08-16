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
}

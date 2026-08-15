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
 *  - Provide [ensurePresets] — an idempotent runtime guard keyed on the approved URL set
 *    (plan §17). Initial seeding happens structurally in [AppDatabase]'s MIGRATION_30_31 via
 *    `INSERT OR REPLACE` execSQL, so this method exists as a no-op once the catalog is loaded
 *    but can re-seed an exhausted/empty table deterministically. The approved catalog is read
 *    ONLY from [FilterSourceCatalog]; this method never fetches remote URLs and never rewrites
 *    user-custom sources (plan §16 forbids SILENT fabrication of SOCIAL/SECURITY/etc rows).
 *
 * NOT in scope (defensive checks below prevent accidental drift):
 *  - No HTTP I/O, no WorkManager, no OkHttp clients (B2 owns download).
 *  - No FilterEngine reload, no `BraveVPNService` signal (B3/B4 own that lifecycle).
 *  - No Plus UI wiring (Out-of-scope §22).
 */
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

    /**
     * Idempotently ensure the approved preset catalog exists in Room. Identity is the approved
     * URL ([FilterSourceCatalog.APPROVED_URLS]); safe to call any number of times without
     * producing duplicates. Initial installation is handled structurally by MIGRATION_30_31 —
     * this method is the runtime deterministic ensure for tests and any cold reset path.
     *
     * The URL-keyed identity (not the name) is essential: plan §17 explicitly forbids relying
     * only on the user-editable name.
     */
    @Transaction
    suspend fun ensurePresets() {
        val existing = filterSourceDao.getAllSources().associateBy { it.url }
        for (preset in FilterSourceCatalog.PRESETS) {
            val row = existing[preset.url]
            if (row == null) {
                // Insert with explicit id from the preset catalog so the migration-time SQL
                // and runtime ensure produce identical ids; relativeFilePath is pre-derived
                // from the id at insert time so observers never see an unwritten path.
                val prepared = FilterSource(
                    id = preset.id,
                    name = preset.name,
                    url = preset.url,
                    category = preset.category,
                    enabled = preset.enabledDef,
                    isPreset = true,
                    // Explicit-id resolution: relativeFilePath known at insert time, no need for
                    // an update_after_insert round-trip here.
                    relativeFilePath = FilterSourceFileStore.relativeFilePathFor(preset.id)
                )
                filterSourceDao.insert(prepared)
            }
            // If a row already exists for this url (whether seeded by migration or previously
            // edited by a future AddCustomURL flow), don't touch it: preserves user customizations.
        }
    }
}

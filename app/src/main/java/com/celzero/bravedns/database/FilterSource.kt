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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * Advanced Filter Source metadata entity (DECISION-008): this is an Advanced Filter Source
 * (EasyList / AdGuard / custom URL) delivered to [FilterEngine] for Layer 7 MITM content
 * filtering. It is NOT a Rethink DNS blocklist — no source-flow exists from Rethink DNS to
 * FilterEngine. See docs/PLAN-FILTER-SOURCE-MANAGER.md §1.
 *
 * Room stores metadata only. Raw downloaded filter bodies live on the filesystem under
 * `filesDir/filter_sources/<source-id>/current.txt` (see [FilterSourceFileStore]) to avoid
 * SQLite CursorWindow memory spikes. [relativeFilePath] is the path of `current.txt` relative
 * to `appContext.filesDir`, e.g. `"filter_sources/source_3/current.txt"`. It is derived from
 * the generated row id, never from user-controlled name/url/category (path safety, plan §13).
 *
 * [category] is organizational UI metadata only; it does NOT restrict the parser — the
 * streaming compiler auto-detects every rule subtype regardless of category (plan §4 / DECISION-009).
 */
@Entity(
    tableName = "FilterSource",
    indices = [
        Index(value = ["category"], unique = false),
        Index(value = ["enabled"], unique = false),
        Index(value = ["url"], unique = false)
    ]
)
data class FilterSource(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val url: String,
    val category: String,
    val enabled: Boolean = true,
    val isPreset: Boolean = false,
    val lastUpdated: Long = 0L,
    val lastUpdateStatus: String = FilterSourceStatus.IDLE,
    val errorMessage: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val checksum: String? = null,
    // Per-source diagnostics & compatibility breakdown (populated in a later phase by
    // FilterSourceCompiler; stored verbatim here so the schema round-trips exact counts).
    val totalLineCount: Int = 0,
    val parsedRuleCount: Int = 0,
    val unsupportedRuleCount: Int = 0,
    val invalidRuleCount: Int = 0,
    val networkRuleCount: Int = 0,
    val cosmeticRuleCount: Int = 0,
    val proceduralRuleCount: Int = 0,
    val scriptletRuleCount: Int = 0,
    val cspRuleCount: Int = 0,
    val htmlFilterRuleCount: Int = 0,
    // Path of current.txt relative to appContext.filesDir, e.g. "filter_sources/source_3/current.txt".
    val relativeFilePath: String,
    // Stable reference to a row in the filters.json catalog (if any). Null when there is
    // no corresponding catalog entry. Added in migration 32→33; nullable, no backfill.
    // See B5 JSON-CATALOG-S1.
    val referenceId: Int? = null
) : Serializable

/**
 * Canonical (locked) UX taxonomy for Advanced Filter Sources. These values are organizational
 * metadata; the parser does not branch on them. Peter Lowe's list is described as "Ads / Trackers"
 * in the plan, but the locked taxonomy has no TRACKERS category — tracking is descriptive purpose
 * only, storage category is [ADS] (plan §4 special case).
 */
object FilterSourceCategory {
    const val ADS = "ADS"
    const val PRIVACY = "PRIVACY"
    const val SOCIAL = "SOCIAL"
    const val ANNOYANCES = "ANNOYANCES"
    const val SECURITY = "SECURITY"
    const val LANGUAGE_SPECIFIC = "LANGUAGE_SPECIFIC"
    const val OTHER = "OTHER"
    const val CUSTOM = "CUSTOM"
}

/**
 * Last-update status lifecycle for a [FilterSource]. B1 stores these as a plain String; the
 * downloader (B2) transitions a source through these states.
 */
object FilterSourceStatus {
    const val IDLE = "IDLE"
    const val IN_PROGRESS = "IN_PROGRESS"
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"
}

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

/**
 * Single source of truth for the approved Advanced Filter Source preset catalog
 * (plan §6). Room seeded with explicit, stable ids 1..7 via
 * `MIGRATION_30_31.execSQL(INSERT OR REPLACE ...)` and the runtime repository
 * `ensurePresets()` uses the same catalog (keyed on approved URL per plan §17 so
 * idempotence is a stable property, not the user-editable name).
 *
 * Schema ordering matches the INSERT columns used by the migration and the
 * Room-defined entity:
 *   id, name, url, category, enabled, isPreset
 *
 * Do NOT add categories, subg, or extras — plan &#167;16 forbids fabricating rows
 * for SOCIAL / SECURITY / LANGUAGE_SPECIFIC / OTHER. Do NOT silently fix URLs; if any URL
 * disagrees with [PLAN-FILTER-SOURCE-MANAGER](docs/PLAN-FILTER-SOURCE-MANAGER.md) &#167;6,
 * STOP with PRESET-DOC-CONFLICT instead of guessing.
 */
data class FilterSourcePreset(
    val id: Int,
    val name: String,
    val url: String,
    val category: String,
    val enabledDef: Boolean
)

object FilterSourceCatalog {

    /** Order matches the plan's "Preset-to-category mapping (initial)" table (plan §6). */
    val PRESETS: List<FilterSourcePreset> = listOf(
        FilterSourcePreset(
            id = 1,
            name = "AdGuard Base Filter",
            url = "https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt",
            category = FilterSourceCategory.ADS,
            enabledDef = true
        ),
        FilterSourcePreset(
            id = 2,
            name = "Peter Lowe's Blocklist",
            url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
            category = FilterSourceCategory.ADS, // plan §4 special case — descriptive "Trackers" storage ADS
            enabledDef = true
        ),
        FilterSourcePreset(
            id = 3,
            name = "EasyList",
            url = "https://easylist.to/easylist/easylist.txt",
            category = FilterSourceCategory.ADS,
            enabledDef = false
        ),
        FilterSourcePreset(
            id = 4,
            name = "AdGuard Tracking Protection",
            url = "https://filters.adtidy.org/extension/ublock/filters/3.txt",
            category = FilterSourceCategory.PRIVACY,
            enabledDef = false
        ),
        FilterSourcePreset(
            id = 5,
            name = "EasyPrivacy",
            url = "https://easylist.to/easylist/easyprivacy.txt",
            category = FilterSourceCategory.PRIVACY,
            enabledDef = false
        ),
        FilterSourcePreset(
            id = 6,
            name = "AdGuard Annoyances Filter",
            url = "https://filters.adtidy.org/extension/ublock/filters/14.txt",
            category = FilterSourceCategory.ANNOYANCES,
            enabledDef = false
        ),
        FilterSourcePreset(
            id = 7,
            name = "Fanboy's Annoyance List",
            url = "https://easylist.to/easylist/fanboy-annoyance.txt",
            category = FilterSourceCategory.ANNOYANCES,
            enabledDef = false
        )
    )

    /** Set of approved preset URLs. {@link #ensurePresets()} runs only when this membership changes
     *  to honour the implementation plan — but the membership is locked at this constant set. */
    val APPROVED_URLS: Set<String> = PRESETS.map { it.url }.toSet()
}

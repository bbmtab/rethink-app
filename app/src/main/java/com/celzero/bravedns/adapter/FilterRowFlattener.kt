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
package com.celzero.bravedns.adapter

import com.celzero.bravedns.database.FilterSourceCategory
import com.celzero.bravedns.viewmodel.FilterSourceCategoryUi

/**
 * Pure category-list → flat-row-list projection for Manage Filters (collapsed-by-default
 * UX fix, 2026-08-26).
 *
 * Contract:
 *  - An unseen category key in [expandedCategories] defaults to COLLAPSED: only its header
 *    row is emitted; no source rows are rendered until the header is tapped.
 *  - An expanded CUSTOM ([FilterSourceCategory.CUSTOM]) section emits [FilterAddCustomRow]
 *    between its header and its source rows — the creation entry point lives inside the
 *    Custom Filters section, never at page level. This holds even at totalCount == 0.
 *  - UI-local only: reads [expandedCategories], never writes it; never mutates any
 *    FilterSource entity or FilterSourceCategoryUi input.
 *
 * Kept side-effect-free so the whole contract is unit-testable on the JVM.
 */
object FilterRowFlattener {

    fun flatten(
        cats: List<FilterSourceCategoryUi>,
        expandedCategories: Map<String, Boolean>
    ): List<FilterRow> {
        return buildList {
            cats.forEach { cat ->
                // Unseen category => collapsed. Expansion state stays in the Activity's
                // UI-local map and never persists.
                val isOpen = expandedCategories[cat.categoryCode] ?: false
                add(
                    FilterCategoryRow(
                        categoryCode = cat.categoryCode,
                        displayName = cat.displayName,
                        enabledCount = cat.enabledCount,
                        totalCount = cat.totalCount,
                        expanded = isOpen
                    )
                )
                if (isOpen) {
                    if (cat.categoryCode == FilterSourceCategory.CUSTOM) {
                        add(FilterAddCustomRow)
                    }
                    cat.sources.forEach { src ->
                        add(FilterSourceRow(src, expanded = true))
                    }
                }
            }
        }
    }
}

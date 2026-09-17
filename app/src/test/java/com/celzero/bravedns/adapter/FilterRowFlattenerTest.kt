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

import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceCategory
import com.celzero.bravedns.database.FilterSourceStatus
import com.celzero.bravedns.viewmodel.FilterSourceCategoryUi
import com.celzero.bravedns.viewmodel.FilterSourceCategoryUi.Companion.group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the collapsed-by-default Manage Filters UX fix (2026-08-26).
 *
 * Covers [FilterRowFlattener] — the pure projection the Activity feeds to
 * [ManageFilterSourcesAdapter]:
 *  - unseen categories default COLLAPSED: header-only, zero source rows rendered;
 *  - expanded categories emit their source rows after the header;
 *  - an expanded CUSTOM section emits [FilterAddCustomRow] between header and rows,
 *    including at totalCount == 0;
 *  - row ids stay unique and stable across the new mixed-type list.
 *
 * Pure JVM: no Android framework, no Room, no persistence.
 */
class FilterRowFlattenerTest {

    /** Helper to build a domain entity with sensible defaults; JVM-only, never persisted. */
    private fun makeSource(
        id: Int,
        name: String,
        category: String = FilterSourceCategory.ADS,
        enabled: Boolean = false
    ): FilterSource = FilterSource(
        id = id,
        name = name,
        url = "https://example.com/list-$id.txt",
        category = category,
        enabled = enabled,
        lastUpdated = 0L,
        lastUpdateStatus = FilterSourceStatus.IDLE,
        errorMessage = null,
        relativeFilePath = "filter_sources/source_$id/current.txt"
    )

    // ---- 1. Unseen categories default COLLAPSED: header only, no source rows ----
    @Test
    fun unseenCategoriesDefaultCollapsed_headerOnly() {
        val cats = group(
            listOf(
                makeSource(1, "EasyList", category = FilterSourceCategory.ADS),
                makeSource(2, "MyList", category = FilterSourceCategory.CUSTOM)
            )
        )
        val rows = FilterRowFlattener.flatten(cats, emptyMap())
        // Exactly one row per category — headers only; nothing else rendered pre-tap.
        assertEquals(cats.size, rows.size)
        assertTrue(rows.all { it is FilterCategoryRow })
        // Every emitted header reports collapsed.
        rows.forEach { assertFalse((it as FilterCategoryRow).expanded) }
        // No custom-source row leaked for the collapsed CUSTOM bucket.
        assertFalse(rows.any { it is FilterSourceRow })
        assertFalse(rows.any { it is FilterAddCustomRow })
    }

    // ---- 2. Toggling a category expands it: source rows render after its header ----
    @Test
    fun expandedCategoryRendersSourcesAfterHeader() {
        val cats = group(
            listOf(
                makeSource(1, "A", category = FilterSourceCategory.ADS),
                makeSource(2, "B", category = FilterSourceCategory.ADS)
            )
        )
        val rows = FilterRowFlattener.flatten(cats, mapOf(FilterSourceCategory.ADS to true))
        val headerIdx = rows.indexOfFirst { it is FilterCategoryRow && it.categoryCode == FilterSourceCategory.ADS }
        val firstSourceIdx = rows.indexOfFirst { it is FilterSourceRow }
        assertTrue("ADS header must be present", headerIdx >= 0)
        // Header still present and marked expanded...
        val header = rows[headerIdx] as FilterCategoryRow
        assertTrue(header.expanded)
        // ...and both ADS sources follow their header, nothing before them.
        val adSources = rows.filterIsInstance<FilterSourceRow>()
        assertEquals(2, adSources.size)
        assertTrue(firstSourceIdx > headerIdx)
        // Other categories remain collapsed: total rows = 8 headers + 2 sources.
        assertEquals(10, rows.size)
    }

    // ---- 3. Expanded CUSTOM section emits Add-row BEFORE its source rows ----
    @Test
    fun expandedCustomEmitsAddRowBeforeCustomSources() {
        val cats = group(
            listOf(
                makeSource(9, "Z-Custom", category = FilterSourceCategory.CUSTOM),
                makeSource(4, "A-Custom", category = FilterSourceCategory.CUSTOM)
            )
        )
        val rows = FilterRowFlattener.flatten(cats, mapOf(FilterSourceCategory.CUSTOM to true))
        val headerIdx =
            rows.indexOfFirst { it is FilterCategoryRow && it.categoryCode == FilterSourceCategory.CUSTOM }
        val addIdx = rows.indexOfFirst { it is FilterAddCustomRow }
        val firstSrcIdx = rows.indexOfFirst { it is FilterSourceRow }
        assertTrue("Add row must exist", addIdx >= 0)
        assertTrue("Add row must sit AFTER the CUSTOM header", addIdx > headerIdx)
        assertTrue("Add row must sit BEFORE custom source rows", addIdx < firstSrcIdx)
        // Both custom sources render after the Add row.
        assertEquals(2, rows.filterIsInstance<FilterSourceRow>().size)
    }

    // ---- 4. Custom Filters at count 0: expandable section hosting only the Add row ----
    @Test
    fun customAtZeroCount_expandsToHeaderPlusAddRowOnly() {
        val cats = group(emptyList())
        val custom = cats.first { it.categoryCode == FilterSourceCategory.CUSTOM }
        assertEquals(0, custom.totalCount)
        val rows = FilterRowFlattener.flatten(cats, mapOf(FilterSourceCategory.CUSTOM to true))
        val customIdx =
            rows.indexOfFirst { it is FilterCategoryRow && it.categoryCode == FilterSourceCategory.CUSTOM }
        val addIdx = rows.indexOfFirst { it is FilterAddCustomRow }
        assertTrue(addIdx == customIdx + 1)
        // No source rows anywhere (all other buckets empty + collapsed).
        assertTrue(rows.filterIsInstance<FilterSourceRow>().isEmpty())
        // Exactly ONE Add row in the whole list.
        assertEquals(1, rows.filterIsInstance<FilterAddCustomRow>().size)
    }

    // ---- 5. Collapsed CUSTOM at count 0 renders NO Add row ----
    @Test
    fun collapsedCustomAtZeroCount_rendersNoAddRow() {
        val rows = FilterRowFlattener.flatten(group(emptyList()), emptyMap())
        assertFalse(rows.any { it is FilterAddCustomRow })
    }

    // ---- 6. Non-custom expanded categories never host an Add row ----
    @Test
    fun expandedNonCustomCategories_neverEmitAddRow() {
        val cats = group(
            listOf(
                makeSource(1, "Ads1", category = FilterSourceCategory.ADS),
                makeSource(2, "Priv1", category = FilterSourceCategory.PRIVACY)
            )
        )
        // group() always emits all 8 canonical buckets incl. CUSTOM; expand every one of
        // them EXCEPT CUSTOM so no section hosts the Add row.
        val expanded = cats
            .filter { it.categoryCode != FilterSourceCategory.CUSTOM }
            .associate { it.categoryCode to true }
        assertEquals(7, expanded.size)
        val rows = FilterRowFlattener.flatten(cats, expanded)
        assertEquals(0, rows.filterIsInstance<FilterAddCustomRow>().size)
    }

    // ---- 6b. Expand-then-collapse round trip: Add row appears, then disappears ----
    @Test
    fun customExpandThenCollapse_addRowAppearsThenDisappears() {
        val sources = listOf(makeSource(5, "Mine", category = FilterSourceCategory.CUSTOM))
        val cats = group(sources)

        val expandedRows =
            FilterRowFlattener.flatten(cats, mapOf(FilterSourceCategory.CUSTOM to true))
        assertEquals(1, expandedRows.filterIsInstance<FilterAddCustomRow>().size)
        assertEquals(1, expandedRows.filterIsInstance<FilterSourceRow>().size)

        val collapsedRows =
            FilterRowFlattener.flatten(cats, mapOf(FilterSourceCategory.CUSTOM to false))
        assertFalse(collapsedRows.any { it is FilterAddCustomRow })
        assertFalse(collapsedRows.any { it is FilterSourceRow })

        // Key absent again == collapsed (unseen defaults collapsed).
        val reCollapsedRows = FilterRowFlattener.flatten(cats, emptyMap())
        assertEquals(
            collapsedRows.map { it.id },
            reCollapsedRows.map { it.id }
        )
    }

    // ---- 7. Row ids unique + stable across a mixed expanded/collapsed list ----
    @Test
    fun mixedListIdsUniqueAndStable_includingAddRow() {
        val sources = listOf(
            makeSource(7, "G", category = FilterSourceCategory.ADS),
            makeSource(11, "K", category = FilterSourceCategory.CUSTOM)
        )
        val cats = group(sources)
        val expanded = mapOf(FilterSourceCategory.ADS to true, FilterSourceCategory.CUSTOM to true)

        val rows1 = FilterRowFlattener.flatten(cats, expanded)
        val rows2 = FilterRowFlattener.flatten(cats, expanded)

        assertEquals(rows1.map { it.id }, rows2.map { it.id })
        assertEquals(rows1.size, rows1.map { it.id }.distinct().size)
        // The Add row's id is namespaced apart from cat_/src_ ids.
        val addId = rows1.first { it is FilterAddCustomRow }.id
        assertTrue(addId.startsWith("action_"))
        rows1.forEach {
            if (it !is FilterAddCustomRow) assertNotEquals(addId, it.id)
        }
    }

    // ---- 8. Flattener never mutates inputs (UI-local contract preserved) ----
    @Test
    fun flattenDoesNotMutateInputs() {
        val src = makeSource(1, "Immutable", category = FilterSourceCategory.CUSTOM)
        val cats = group(listOf(src))
        val beforeHash = src.hashCode()
        val expandedIn = mutableMapOf<String, Boolean>(FilterSourceCategory.CUSTOM to true)
        val expandedSnapshot = expandedIn.toMap()

        FilterRowFlattener.flatten(cats, expandedIn)

        assertEquals(expandedSnapshot, expandedIn)
        assertEquals(beforeHash, src.hashCode())
        // CategoryUi objects are rebuilt per group() call; the flattener must not swap
        // their lists either.
        val custom = cats.first { it.categoryCode == FilterSourceCategory.CUSTOM }
        assertEquals(1, custom.sources.size)
    }
}

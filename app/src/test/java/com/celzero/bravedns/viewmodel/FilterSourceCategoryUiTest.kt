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

import com.celzero.bravedns.adapter.FilterCategoryRow
import com.celzero.bravedns.adapter.FilterRow
import com.celzero.bravedns.adapter.FilterSourceRow
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceCategory
import com.celzero.bravedns.database.FilterSourceStatus
import com.celzero.bravedns.viewmodel.FilterSourceCategoryUi.Companion.group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for B5 Slice-2R pure category/UI transformation (R5).
 *
 * Covers the deterministic grouping model [FilterSourceCategoryUi.group] and the read-only
 * row model ([FilterCategoryRow], [FilterSourceRow]) used by
 * [com.celzero.bravedns.adapter.ManageFilterSourcesAdapter].
 *
 * Each test maps to one bullet of the R5 acceptance list; the file must run with
 * 0 failures, 0 errors, 0 skipped from a vanilla JUnit runner.
 */
class FilterSourceCategoryUiTest {

    /** Helper to build a domain entity with sensible defaults; JVM-only, never persisted. */
    private fun makeSource(
        id: Int,
        name: String,
        category: String = FilterSourceCategory.ADS,
        enabled: Boolean = true,
        lastUpdateStatus: String = FilterSourceStatus.IDLE,
        lastUpdated: Long = 0L,
        parsedRuleCount: Int = 0,
        errorMessage: String? = null
    ): FilterSource = FilterSource(
        id = id,
        name = name,
        url = "https://example.com/list-$id.txt",
        category = category,
        enabled = enabled,
        lastUpdated = lastUpdated,
        lastUpdateStatus = lastUpdateStatus,
        errorMessage = errorMessage,
        relativeFilePath = "filter_sources/source_$id/current.txt"
    )

    // ---- 1. All 8 categories exist (R5 #1) ----
    @Test
    fun allEightCategoriesAlwaysEmitted_evenWhenEmpty() {
        val groups = group(emptyList())
        assertEquals(8, groups.size)
        assertEquals(
            listOf(
                "ADS", "PRIVACY", "SOCIAL", "ANNOYANCES",
                "SECURITY", "LANGUAGE_SPECIFIC", "OTHER", "CUSTOM"
            ),
            groups.map { it.categoryCode }
        )
        groups.forEach { g ->
            assertEquals(0, g.totalCount)
            assertEquals(0, g.enabledCount)
            assertTrue(g.sources.isEmpty())
            assertNotNull(g.displayName)
        }
    }

    // ---- 2. Canonical category ordering (R5 #2) ----
    @Test
    fun canonicalOrderIsLocked_regardlessOfInputOrder() {
        val shuffled = listOf(
            makeSource(1, "C", category = FilterSourceCategory.CUSTOM),
            makeSource(2, "A", category = FilterSourceCategory.ADS),
            makeSource(3, "S", category = FilterSourceCategory.SECURITY),
            makeSource(4, "L", category = FilterSourceCategory.LANGUAGE_SPECIFIC),
            makeSource(5, "P", category = FilterSourceCategory.PRIVACY)
        )
        val ordered = group(shuffled).map { it.categoryCode }
        assertEquals(
            listOf(
                "ADS", "PRIVACY", "SOCIAL", "ANNOYANCES",
                "SECURITY", "LANGUAGE_SPECIFIC", "OTHER", "CUSTOM"
            ),
            ordered
        )
    }

    // ---- 3. Presets grouped correctly (R5 #3) ----
    @Test
    fun sevenPresetsGroupedUnderCorrectCategories_customRemainsEmpty() {
        val sources = listOf(
            makeSource(1, "EasyList", category = FilterSourceCategory.ADS),
            makeSource(2, "EasyPrivacy", category = FilterSourceCategory.PRIVACY),
            makeSource(3, "FanboySocial", category = FilterSourceCategory.SOCIAL),
            makeSource(4, "FanboyAnnoyances", category = FilterSourceCategory.ANNOYANCES),
            makeSource(5, "MalwareDomains", category = FilterSourceCategory.SECURITY),
            makeSource(6, "EasyListGermany", category = FilterSourceCategory.LANGUAGE_SPECIFIC),
            makeSource(7, "AdGuardBase", category = FilterSourceCategory.OTHER)
        )
        val byCode = group(sources).associateBy { it.categoryCode }

        assertEquals(1, byCode[FilterSourceCategory.ADS]?.totalCount)
        assertEquals(1, byCode[FilterSourceCategory.PRIVACY]?.totalCount)
        assertEquals(1, byCode[FilterSourceCategory.SOCIAL]?.totalCount)
        assertEquals(1, byCode[FilterSourceCategory.ANNOYANCES]?.totalCount)
        assertEquals(1, byCode[FilterSourceCategory.SECURITY]?.totalCount)
        assertEquals(1, byCode[FilterSourceCategory.LANGUAGE_SPECIFIC]?.totalCount)
        assertEquals(1, byCode[FilterSourceCategory.OTHER]?.totalCount)
        assertEquals(0, byCode[FilterSourceCategory.CUSTOM]?.totalCount)
    }

    // ---- 4. Empty categories retained (R5 #4) ----
    @Test
    fun emptyCategoriesAreRetained_evenWhenOtherBucketsAreFull() {
        val sources = listOf(
            makeSource(1, "A1", category = FilterSourceCategory.ADS),
            makeSource(2, "P1", category = FilterSourceCategory.PRIVACY)
        )
        val result = group(sources)
        assertEquals(8, result.size)
        // The 6 buckets with no sources must still be present with totalCount == 0.
        for (code in listOf(
            FilterSourceCategory.SOCIAL,
            FilterSourceCategory.ANNOYANCES,
            FilterSourceCategory.SECURITY,
            FilterSourceCategory.LANGUAGE_SPECIFIC,
            FilterSourceCategory.OTHER,
            FilterSourceCategory.CUSTOM
        )) {
            val g = result.first { it.categoryCode == code }
            assertEquals(0, g.totalCount)
            assertTrue("expected empty bucket for $code", g.sources.isEmpty())
        }
    }

    // ---- 5. Enabled/total counts (R5 #5) ----
    @Test
    fun enabledAndTotalCountsReflectSourceFlags() {
        val sources = listOf(
            makeSource(1, "A1", category = FilterSourceCategory.ADS, enabled = true),
            makeSource(2, "A2", category = FilterSourceCategory.ADS, enabled = false),
            makeSource(3, "A3", category = FilterSourceCategory.ADS, enabled = false),
            makeSource(4, "A4", category = FilterSourceCategory.ADS, enabled = true)
        )
        val ads = group(sources).first { it.categoryCode == FilterSourceCategory.ADS }
        assertEquals(4, ads.totalCount)
        assertEquals(2, ads.enabledCount)
    }

    // ---- 6. Deterministic source ordering (R5 #6) ----
    @Test
    fun sourceOrderingWithinACategoryIsDeterministicByName() {
        val sources = listOf(
            makeSource(1, "Charlie", category = FilterSourceCategory.ADS),
            makeSource(2, "Alpha", category = FilterSourceCategory.ADS),
            makeSource(3, "Bravo", category = FilterSourceCategory.ADS),
            makeSource(4, "Delta", category = FilterSourceCategory.ADS)
        )
        // Re-shuffle the input order and confirm a stable sorted output.
        val a = group(sources).first { it.categoryCode == FilterSourceCategory.ADS }.sources.map { it.name }
        val b = group(sources.reversed()).first { it.categoryCode == FilterSourceCategory.ADS }.sources.map { it.name }
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"), a)
        assertEquals(a, b)
    }

    // ---- 7. Diagnostics mapping through grouping (R5 #7) ----
    @Test
    fun diagnosticsFieldsArePreservedOnGroupedSource() {
        val src = FilterSource(
            id = 1,
            name = "Stats",
            url = "https://example.com/stats.txt",
            category = FilterSourceCategory.ADS,
            enabled = true,
            lastUpdated = 123L,
            lastUpdateStatus = FilterSourceStatus.SUCCESS,
            totalLineCount = 8000,
            parsedRuleCount = 7000,
            unsupportedRuleCount = 50,
            invalidRuleCount = 25,
            networkRuleCount = 6000,
            cosmeticRuleCount = 700,
            proceduralRuleCount = 100,
            scriptletRuleCount = 20,
            cspRuleCount = 5,
            htmlFilterRuleCount = 175,
            relativeFilePath = "filter_sources/source_1/current.txt"
        )
        val grouped = group(listOf(src)).first { it.categoryCode == FilterSourceCategory.ADS }
        val s = grouped.sources.single()
        assertEquals(8000, s.totalLineCount)
        assertEquals(7000, s.parsedRuleCount)
        assertEquals(50, s.unsupportedRuleCount)
        assertEquals(25, s.invalidRuleCount)
        assertEquals(6000, s.networkRuleCount)
        assertEquals(700, s.cosmeticRuleCount)
        assertEquals(100, s.proceduralRuleCount)
        assertEquals(20, s.scriptletRuleCount)
        assertEquals(5, s.cspRuleCount)
        assertEquals(175, s.htmlFilterRuleCount)
    }

    // ---- 8. Zero diagnostics -> "Not compiled yet" representation (R5 #8) ----
    @Test
    fun zeroParsedRulesSignalsNotCompiledYet_downstreamRenderer() {
        val src = makeSource(
            id = 1,
            name = "Fresh",
            category = FilterSourceCategory.ADS,
            parsedRuleCount = 0,
            lastUpdateStatus = FilterSourceStatus.IDLE
        )
        // Grouping must not invent a non-zero count or mutate the source.
        val grouped = group(listOf(src)).first { it.categoryCode == FilterSourceCategory.ADS }
        val drained = grouped.sources.single()
        assertEquals(0, drained.parsedRuleCount)
        assertEquals(0, drained.totalLineCount)
        // The renderer rule (not compiled when parsedRuleCount == 0) is verified
        // separately by the adapter contract in [ManageFilterSourcesAdapter] — here we
        // only assert that the field value is preserved verbatim through the grouping.
        assertEquals(FilterSourceStatus.IDLE, drained.lastUpdateStatus)
    }

    // ---- 9. FAILED status preserved; errorMessage surfaces (R5 #9) ----
    @Test
    fun failedSourcesKeepErrorMessageThroughGrouping() {
        val src = makeSource(
            id = 1,
            name = "Broken",
            category = FilterSourceCategory.ADS,
            lastUpdateStatus = FilterSourceStatus.FAILED,
            errorMessage = "404 not found"
        )
        val grouped = group(listOf(src)).first { it.categoryCode == FilterSourceCategory.ADS }
        val drained = grouped.sources.single()
        assertEquals(FilterSourceStatus.FAILED, drained.lastUpdateStatus)
        assertEquals("404 not found", drained.errorMessage)
    }

    // ---- 10. lastUpdated == 0 -> no fake timestamp (R5 #10) ----
    @Test
    fun lastUpdatedZeroIsPassedThroughUnchanged() {
        val src = makeSource(
            id = 1,
            name = "Epoch",
            category = FilterSourceCategory.ADS,
            lastUpdated = 0L
        )
        val grouped = group(listOf(src)).first { it.categoryCode == FilterSourceCategory.ADS }
        val drained = grouped.sources.single()
        // Grouping must not coerce epoch 0 to a real timestamp; renderer is responsible
        // for showing "never" instead.
        assertEquals(0L, drained.lastUpdated)
    }

    // ---- 11. Flattened category/source rows have unique stable IDs (R5 #11) ----
    @Test
    fun flattenedRowsHaveUniqueStableIds_acrossTypes() {
        val sources = listOf(
            makeSource(7, "G", category = FilterSourceCategory.ADS),
            makeSource(11, "K", category = FilterSourceCategory.PRIVACY)
        )
        val cats = group(sources)
        val rows: List<FilterRow> = buildList {
            cats.forEach { cat ->
                add(
                    FilterCategoryRow(
                        categoryCode = cat.categoryCode,
                        displayName = cat.displayName,
                        enabledCount = cat.enabledCount,
                        totalCount = cat.totalCount,
                        expanded = true
                    )
                )
                cat.sources.forEach { src -> add(FilterSourceRow(src, expanded = true)) }
            }
        }
        // Every ID appears exactly once and is non-blank.
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
        rows.forEach { assertTrue("blank id", it.id.isNotBlank()) }
        // Header id != Source id even when the source id's numeric suffix matches.
        val headerForAds = rows.first { it is FilterCategoryRow && it.categoryCode == FilterSourceCategory.ADS }
        val sourceInAds = rows.first { it is FilterSourceRow }
        assertNotEquals(headerForAds.id, sourceInAds.id)
        assertTrue(headerForAds.id.startsWith("cat_"))
        assertTrue(sourceInAds.id.startsWith("src_"))
        // Re-flattening the same input yields identical ids (stability).
        val reRows: List<FilterRow> = buildList {
            cats.forEach { cat ->
                add(
                    FilterCategoryRow(
                        categoryCode = cat.categoryCode,
                        displayName = cat.displayName,
                        enabledCount = cat.enabledCount,
                        totalCount = cat.totalCount,
                        expanded = true
                    )
                )
                cat.sources.forEach { src -> add(FilterSourceRow(src, expanded = true)) }
            }
        }
        assertEquals(rows.map { it.id }, reRows.map { it.id })
    }

    // ---- 12. Expansion state does not modify domain entities (R5 #12) ----
    @Test
    fun expansionFlagDoesNotMutateUnderlyingSourceEntity() {
        val src = makeSource(
            id = 1,
            name = "Immutable",
            category = FilterSourceCategory.ADS,
            enabled = true,
            parsedRuleCount = 100,
            lastUpdated = 1_700_000_000L,
            lastUpdateStatus = FilterSourceStatus.SUCCESS
        )
        val beforeHash = src.hashCode()
        val beforeEnabled = src.enabled
        val beforeParsed = src.parsedRuleCount
        val beforeUpdated = src.lastUpdated
        // Build a row with various expansion flags. None must mutate the source.
        val r1 = FilterSourceRow(src, expanded = true)
        val r2 = r1.copy(expanded = false)
        val r3 = FilterSourceRow(src, expanded = false)
        // Reference identity (same object) holds regardless of wrapper flag.
        assertSame(src, r1.source)
        assertSame(src, r2.source)
        assertSame(src, r3.source)
        // Domain fields are untouched.
        assertEquals(beforeHash, src.hashCode())
        assertEquals(beforeEnabled, src.enabled)
        assertEquals(beforeParsed, src.parsedRuleCount)
        assertEquals(beforeUpdated, src.lastUpdated)
        // Sanity: nullable errorMessage stays nullable.
        assertNull(makeSource(2, "X").errorMessage)
        assertNotNull(src.errorMessage?.let { "ok" } ?: "")
        // Sanity check that a fails surface (R5 #9) keeps its text intact through wrappers.
        val failed = makeSource(
            id = 3, name = "Break", category = FilterSourceCategory.ADS,
            lastUpdateStatus = FilterSourceStatus.FAILED, errorMessage = "boom"
        )
        val failedRow = FilterSourceRow(failed, expanded = true).copy(expanded = false)
        assertEquals("boom", failedRow.source.errorMessage)
    }

    // ---- Bonus: unknown categories do not mutate the canonical 8-set; unknown strings are dropped. ----
    @Test
    fun unknownCategoryStringsAreDropped_notAddedToCanonicalSet() {
        val leman = makeSource(99, "Ghost", category = "UNKNOWN_LEGACY_TAG")
        val groups = group(listOf(leman))
        assertEquals(8, groups.size)
        val totalSources = groups.sumOf { it.totalCount }
        assertEquals(0, totalSources)
        // Key sanity: all category codes match the canonical set.
        groups.forEach { g ->
            assertTrue(
                "category $g.categoryCode not in canonical set",
                setOf(
                    "ADS", "PRIVACY", "SOCIAL", "ANNOYANCES",
                    "SECURITY", "LANGUAGE_SPECIFIC", "OTHER", "CUSTOM"
                ).contains(g.categoryCode)
            )
        }
        // No phantom row produced.
        assertFalse(groups.any { it.categoryCode == "UNKNOWN_LEGACY_TAG" })
    }
}

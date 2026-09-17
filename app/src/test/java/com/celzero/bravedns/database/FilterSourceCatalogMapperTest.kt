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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

class FilterSourceCatalogMapperTest {

    private fun findBundledAsset(): File {
        val candidates = listOf(
            File("app/src/main/assets/filter_sources/filters.json"),
            File("src/main/assets/filter_sources/filters.json"),
            File("../app/src/main/assets/filter_sources/filters.json"),
            File("../../app/src/main/assets/filter_sources/filters.json")
        )
        for (candidate in candidates) {
            if (candidate.exists()) return candidate
        }
        fail("Could not locate bundled asset filters.json at any of: " +
            candidates.joinToString { it.absolutePath })
        return File("")
    }

    private fun parseJson(json: String): FilterSourceCatalogJson.Catalog {
        return FilterSourceCatalogJson.parse(
            ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8))
        )
    }

    private val bundledCatalog: FilterSourceCatalogJson.Catalog by lazy {
        val asset = findBundledAsset()
        FilterSourceCatalogJson.parse(asset.inputStream())
    }

    private val mapped: List<FilterSource> by lazy {
        FilterSourceCatalogMapper.map(bundledCatalog)
    }

    // ---- T1: input=84 and output=84 -----------------------------------------------------------

    @Test
    fun mappedCounts_areExactly84() {
        assertEquals(84, bundledCatalog.filters.size)
        assertEquals(84, mapped.size)
    }

    // ---- T2: every output has non-null referenceId --------------------------------------------

    @Test
    fun everyOutput_hasNonNullReferenceId() {
        mapped.forEach { source ->
            assertNotNull(
                "referenceId must be non-null for id=${source.id}, name=${source.name}",
                source.referenceId
            )
        }
    }

    // ---- T3: all referenceIds, local IDs, and URLs are unique ---------------------------------

    @Test
    fun allReferenceIds_localIds_urls_areUnique() {
        val referenceIds = mapped.map { it.referenceId }
        assertEquals(84, referenceIds.size)
        assertEquals(84, referenceIds.toSet().size)

        val localIds = mapped.map { it.id }
        assertEquals(84, localIds.size)
        assertEquals(84, localIds.toSet().size)

        val urls = mapped.map { it.url }
        assertEquals(84, urls.size)
        assertEquals(84, urls.toSet().size)
    }

    // ---- T4: all 10 locked legacy mappings exactly --------------------------------------------

    @Test
    fun lockedLegacyMappings_areExact() {
        val byReferenceId = mapped.associate { it.referenceId!! to it }
        val expected = mapOf(
            2 to 1,
            204 to 2,
            101 to 3,
            3 to 4,
            118 to 5,
            14 to 6,
            122 to 7,
            123 to -1001,
            257 to -1002,
            102 to -1003
        )
        expected.forEach { (refId, localId) ->
            val source = byReferenceId[refId]
                ?: throw AssertionError("missing FilterSource for referenceId $refId")
            assertEquals(
                "referenceId $refId must map to localId $localId (got ${source.id})",
                localId, source.id
            )
        }
    }

    // ---- T5: generated examples: 1 -> -10001, 103 -> -10103, 258 -> -10258 ---------------------

    @Test
    fun generatedIdExamples_areCorrect() {
        val byReferenceId = mapped.associate { it.referenceId!! to it.id }
        assertEquals(-10001, byReferenceId[1])
        assertEquals(-10103, byReferenceId[103])
        assertEquals(-10258, byReferenceId[258])
    }

    // ---- T6: only referenceId 2 and 204 are enabled -------------------------------------------

    @Test
    fun onlyReferenceId2_and_204_areEnabled() {
        val enabled = mapped.filter { it.enabled }.map { it.referenceId }
        assertEquals(2, enabled.size)
        assertTrue(enabled.contains(2))
        assertTrue(enabled.contains(204))
    }

    // ---- T7: every output isPreset=true --------------------------------------------------------

    @Test
    fun everyOutput_isPresetTrue() {
        mapped.forEach { source ->
            assertTrue("isPreset must be true for id=${source.id}", source.isPreset)
        }
    }

    // ---- T8: every relativeFilePath matches its local ID --------------------------------------

    @Test
    fun relativeFilePath_matchesLocalId() {
        mapped.forEach { source ->
            val expectedPath = "filter_sources/source_${source.id}/current.txt"
            assertEquals(
                "relativeFilePath must follow source_<id>/current.txt for id=${source.id}",
                expectedPath, source.relativeFilePath
            )
        }
    }

    // ---- T9: canonical JSON metadata — output URL equals JSON downloadUrl ---------------------

    @Test
    fun canonicalJsonMetadata_urlsMatch() {
        val jsonByFilterId = bundledCatalog.filters.associate { it.filterId to it.downloadUrl }
        mapped.forEach { source ->
            val jsonUrl = jsonByFilterId[source.referenceId]
                ?: throw AssertionError("no JSON entry for referenceId ${source.referenceId}")
            assertEquals(
                "downloadUrl mismatch for referenceId ${source.referenceId}",
                jsonUrl, source.url
            )
        }
    }

    // ---- T10: category counts match the catalog ------------------------------------------------

    @Test
    fun categoryCounts_matchCatalog() {
        val counts = mapped.groupBy { it.category }
            .mapValues { it.value.size }
        assertEquals(3, counts[FilterSourceCategory.ADS])
        assertEquals(6, counts[FilterSourceCategory.PRIVACY])
        assertEquals(2, counts[FilterSourceCategory.SOCIAL])
        assertEquals(10, counts[FilterSourceCategory.ANNOYANCES])
        assertEquals(4, counts[FilterSourceCategory.SECURITY])
        assertEquals(4, counts[FilterSourceCategory.OTHER])
        assertEquals(55, counts[FilterSourceCategory.LANGUAGE_SPECIFIC])
    }

    // ---- T11: category derived from group, not from filter name -------------------------------

    @Test
    fun category_derivedFromGroupNotName() {
        val byReferenceId = mapped.associate { it.referenceId!! to it }

        val ref102 = byReferenceId[102]!!
        assertEquals(FilterSourceCategory.LANGUAGE_SPECIFIC, ref102.category)

        val ref243 = byReferenceId[243]!!
        assertEquals(FilterSourceCategory.LANGUAGE_SPECIFIC, ref243.category)

        val ref202 = byReferenceId[202]!!
        assertEquals(FilterSourceCategory.LANGUAGE_SPECIFIC, ref202.category)
    }

    // ---- T12: unknown catalog group is rejected ----------------------------------------------

    @Test
    fun unknownCatalogGroup_isRejected() {
        val json = """
            {
                "groups": [{"groupId": 1, "groupName": "Ad blocking"}, {"groupId": 8, "groupName": "Unknown"}],
                "tags": [],
                "filters": [
                    {"filterId": 1, "name": "A", "description": "a", "groupId": 1,
                     "subscriptionUrl": "https://a.com", "downloadUrl": "https://a.com/a.txt",
                     "homepage": "https://a.com", "expires": 1, "version": "1",
                     "timeUpdated": "2025-01-01", "deprecated": false, "trustLevel": "full",
                     "languages": [], "tags": []},
                    {"filterId": 2, "name": "B", "description": "b", "groupId": 8,
                     "subscriptionUrl": "https://b.com", "downloadUrl": "https://b.com/b.txt",
                     "homepage": "https://b.com", "expires": 1, "version": "1",
                     "timeUpdated": "2025-01-01", "deprecated": false, "trustLevel": "full",
                     "languages": [], "tags": []}
                ]
            }
        """.trimIndent()

        val catalog = parseJson(json)
        try {
            FilterSourceCatalogMapper.map(catalog)
            fail("expected IllegalArgumentException for unknown catalog group")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message must mention unmapped group: ${e.message}",
                e.message!!.contains("unmapped catalog group") ||
                    e.message!!.contains("Unknown")
            )
        }
    }

    // ---- T13: duplicate referenceId is rejected ----------------------------------------------

    @Test
    fun duplicateReferenceId_isRejected() {
        val catalog = FilterSourceCatalogJson.Catalog(
            groups = listOf(
                FilterSourceCatalogJson.CatalogGroup(groupId = 1, groupName = "Ad blocking")
            ),
            tags = emptyList(),
            filters = listOf(
                FilterSourceCatalogJson.CatalogFilter(
                    filterId = 1, name = "A", description = "a", groupId = 1,
                    subscriptionUrl = "https://a.com", downloadUrl = "https://a.com/a.txt",
                    homepage = "https://a.com", expires = 1, version = "1",
                    timeUpdated = "2025-01-01", deprecated = false, trustLevel = "full",
                    languages = emptyList(), tags = emptyList(), platformsExcluded = null
                ),
                FilterSourceCatalogJson.CatalogFilter(
                    filterId = 1, name = "B", description = "b", groupId = 1,
                    subscriptionUrl = "https://b.com", downloadUrl = "https://b.com/b.txt",
                    homepage = "https://b.com", expires = 1, version = "1",
                    timeUpdated = "2025-01-01", deprecated = false, trustLevel = "full",
                    languages = emptyList(), tags = emptyList(), platformsExcluded = null
                )
            )
        )
        try {
            FilterSourceCatalogMapper.map(catalog)
            fail("expected IllegalArgumentException for duplicate referenceId")
        } catch (e: IllegalArgumentException) {
            assertTrue("message must mention duplicate referenceId", e.message!!.contains("duplicate"))
        }
    }

    // ---- T14: default field values are left at entity defaults ----------------------------------

    @Test
    fun defaultFieldValues_areEntityDefaults() {
        mapped.forEach { source ->
            assertEquals(0L, source.lastUpdated)
            assertEquals(FilterSourceStatus.IDLE, source.lastUpdateStatus)
            assertEquals(null, source.errorMessage)
            assertEquals(null, source.etag)
            assertEquals(null, source.lastModified)
            assertEquals(null, source.checksum)
            assertEquals(0, source.totalLineCount)
            assertEquals(0, source.parsedRuleCount)
            assertEquals(0, source.unsupportedRuleCount)
            assertEquals(0, source.invalidRuleCount)
            assertEquals(0, source.networkRuleCount)
            assertEquals(0, source.cosmeticRuleCount)
            assertEquals(0, source.proceduralRuleCount)
            assertEquals(0, source.scriptletRuleCount)
            assertEquals(0, source.cspRuleCount)
            assertEquals(0, source.htmlFilterRuleCount)
        }
    }

    // ---- T15: output order matches input order --------------------------------------------------

    @Test
    fun outputOrder_matchesInputOrder() {
        val inputIds = bundledCatalog.filters.map { it.filterId }
        val outputRefIds = mapped.map { it.referenceId }
        assertEquals(inputIds, outputRefIds)
    }
}

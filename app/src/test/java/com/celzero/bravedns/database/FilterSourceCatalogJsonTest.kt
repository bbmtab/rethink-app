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

/**
 * Focused JVM tests for [FilterSourceCatalogJson].
 *
 * These are plain JUnit tests (no Robolectric, no instrumentation) that exercise
 * the typed Gson parser against the bundled `filters.json` asset and against
 * small inline JSON payloads for negative cases.
 *
 * See B5 JSON-CATALOG-S2.
 */
class FilterSourceCatalogJsonTest {

    /**
     * Locate the bundled asset on the filesystem. The test runs in the Gradle
     * JVM with the repository root as working directory, but we try several
     * candidate paths to be resilient.
     */
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
        return File("") // unreachable
    }

    /**
     * Parse a JSON string into a Catalog for negative tests.
     */
    private fun parseJson(json: String): FilterSourceCatalogJson.Catalog {
        return FilterSourceCatalogJson.parse(
            ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8))
        )
    }

    // ---- T1: bundled asset parses successfully ---------------------------------------------

    @Test
    fun bundledAsset_parsesSuccessfully() {
        val asset = findBundledAsset()
        val catalog = FilterSourceCatalogJson.parse(asset.inputStream())
        assertNotNull(catalog)
        assertEquals(7, catalog.groups.size)
        assertEquals(true, catalog.tags.isNotEmpty())
        assertEquals(true, catalog.filters.isNotEmpty())
    }

    // ---- T2: parsed filter count is exactly 84 ---------------------------------------------

    @Test
    fun parsedFilterCount_isExactly84() {
        val asset = findBundledAsset()
        val catalog = FilterSourceCatalogJson.parse(asset.inputStream())
        assertEquals("bundled filters.json must contain exactly 84 filter entries", 84, catalog.filters.size)
    }

    // ---- T3: all 84 filterIds and downloadUrls are unique ----------------------------------

    @Test
    fun allFilterIds_andDownloadUrls_areUnique() {
        val asset = findBundledAsset()
        val catalog = FilterSourceCatalogJson.parse(asset.inputStream())

        val filterIds = catalog.filters.map { it.filterId }
        assertEquals(84, filterIds.size)
        assertEquals(84, filterIds.toSet().size)

        val downloadUrls = catalog.filters.map { it.downloadUrl }
        assertEquals(84, downloadUrls.size)
        assertEquals(84, downloadUrls.toSet().size)
    }

    // ---- T4: all group references resolve --------------------------------------------------

    @Test
    fun allGroupReferences_resolve() {
        val asset = findBundledAsset()
        val catalog = FilterSourceCatalogJson.parse(asset.inputStream())
        val knownGroupIds = catalog.groups.map { it.groupId }.toSet()
        val unresolved = catalog.filters.filter { !knownGroupIds.contains(it.groupId) }
        assertTrue(
            "all filter.groupId values must reference an existing group; " +
                "unresolved: ${unresolved.map { it.filterId to it.groupId }}",
            unresolved.isEmpty()
        )
    }

    // ---- T5: optional platformsExcluded parses for an entry that contains it ----------------

    @Test
    fun platformsExcluded_parsesWhenPresent() {
        val asset = findBundledAsset()
        val catalog = FilterSourceCatalogJson.parse(asset.inputStream())
        val withPlatforms = catalog.filters.filter { it.hasPlatformsExcluded }
        assertFalse("at least one bundled filter must contain platformsExcluded", withPlatforms.isEmpty())

        // Verify the known entry (filterId 14, AdGuard Annoyances filter) has platformsExcluded ["ext_chromium_mv3"]
        val entry14 = catalog.filters.first { it.filterId == 14 }
        assertTrue(entry14.hasPlatformsExcluded)
        assertEquals(listOf("ext_chromium_mv3"), entry14.platformsExcluded)
    }

    // ---- T6: duplicate filterId is rejected -----------------------------------------------

    @Test
    fun duplicateFilterId_isRejected() {
        val json = """
            {
                "groups": [{"groupId": 1, "groupName": "Test"}],
                "tags": [],
                "filters": [
                    {"filterId": 1, "name": "A", "description": "a", "groupId": 1,
                     "subscriptionUrl": "https://a.com", "downloadUrl": "https://a.com/a.txt",
                     "homepage": "https://a.com", "expires": 1, "version": "1",
                     "timeUpdated": "2025-01-01", "deprecated": false, "trustLevel": "full",
                     "languages": [], "tags": []},
                    {"filterId": 1, "name": "B", "description": "b", "groupId": 1,
                     "subscriptionUrl": "https://b.com", "downloadUrl": "https://b.com/b.txt",
                     "homepage": "https://b.com", "expires": 1, "version": "1",
                     "timeUpdated": "2025-01-01", "deprecated": false, "trustLevel": "full",
                     "languages": [], "tags": []}
                ]
            }
        """.trimIndent()

        try {
            parseJson(json)
            fail("expected IllegalArgumentException for duplicate filterId")
        } catch (e: IllegalArgumentException) {
            assertTrue("message mentions duplicate filterId", e.message!!.contains("duplicate filterId"))
        }
    }

    // ---- T7: non-HTTPS downloadUrl is rejected --------------------------------------------

    @Test
    fun nonHttpsDownloadUrl_isRejected() {
        val json = """
            {
                "groups": [{"groupId": 1, "groupName": "Test"}],
                "tags": [],
                "filters": [
                    {"filterId": 1, "name": "A", "description": "a", "groupId": 1,
                     "subscriptionUrl": "https://a.com", "downloadUrl": "http://a.com/a.txt",
                     "homepage": "https://a.com", "expires": 1, "version": "1",
                     "timeUpdated": "2025-01-01", "deprecated": false, "trustLevel": "full",
                     "languages": [], "tags": []}
                ]
            }
        """.trimIndent()

        try {
            parseJson(json)
            fail("expected IllegalArgumentException for non-HTTPS downloadUrl")
        } catch (e: IllegalArgumentException) {
            assertTrue("message mentions HTTPS", e.message!!.contains("HTTPS"))
        }
    }

    // ---- T8: missing group reference is rejected -----------------------------------------

    @Test
    fun missingGroupReference_isRejected() {
        val json = """
            {
                "groups": [{"groupId": 1, "groupName": "Test"}],
                "tags": [],
                "filters": [
                    {"filterId": 1, "name": "A", "description": "a", "groupId": 999,
                     "subscriptionUrl": "https://a.com", "downloadUrl": "https://a.com/a.txt",
                     "homepage": "https://a.com", "expires": 1, "version": "1",
                     "timeUpdated": "2025-01-01", "deprecated": false, "trustLevel": "full",
                     "languages": [], "tags": []}
                ]
            }
        """.trimIndent()

        try {
            parseJson(json)
            fail("expected IllegalArgumentException for missing group reference")
        } catch (e: IllegalArgumentException) {
            assertTrue("message mentions unknown group", e.message!!.contains("unknown group"))
        }
    }
}

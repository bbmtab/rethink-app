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

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * B5 JSON-CATALOG-S4B: Focused Room/Robolectric tests for
 * [FilterSourceDao.syncCatalogAtomically].
 *
 * Mirrors the in-memory Room pattern from [FilterSourceRepositoryTest] (Robolectric,
 * in-memory DB, same test dispatcher). No new test dependencies.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FilterSourceCatalogDaoTest {

    @get:Rule val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var dao: FilterSourceDao
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries() // test-only; never in production
            .build()
        dao = db.filterSourceDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun findBundledAsset(): java.io.File {
        val candidates = listOf(
            java.io.File("app/src/main/assets/filter_sources/filters.json"),
            java.io.File("src/main/assets/filter_sources/filters.json"),
            java.io.File("../app/src/main/assets/filter_sources/filters.json"),
            java.io.File("../../app/src/main/assets/filter_sources/filters.json")
        )
        for (candidate in candidates) {
            if (candidate.exists()) return candidate
        }
        fail("Could not locate bundled asset filters.json at any of: " +
            candidates.joinToString { it.absolutePath })
        return java.io.File("")
    }

    /** The 84 catalog-derived candidates, mapped exactly as production does. */
    private val catalogCandidates: List<FilterSource> by lazy {
        val asset = findBundledAsset()
        val catalog = FilterSourceCatalogJson.parse(asset.inputStream())
        FilterSourceCatalogMapper.map(catalog)
    }

    /**
     * A FilterSource carrying distinctive non-default runtime/user state so tests can
     * prove those fields survive a sync unchanged.
     */
    private fun fullRuntimeSource(
        id: Int = 1,
        referenceId: Int? = 1,
        url: String = "https://example.com/a.txt",
        name: String = "Test Source",
        category: String = FilterSourceCategory.ADS,
        enabled: Boolean = true,
        isPreset: Boolean = true,
        lastUpdated: Long = 12345L,
        lastUpdateStatus: String = FilterSourceStatus.SUCCESS,
        errorMessage: String? = "old error",
        etag: String? = "etag-old",
        lastModified: String? = "Mon, 01 Jan 2024",
        checksum: String? = "abc123",
        totalLineCount: Int = 100,
        parsedRuleCount: Int = 90,
        unsupportedRuleCount: Int = 1,
        invalidRuleCount: Int = 2,
        networkRuleCount: Int = 50,
        cosmeticRuleCount: Int = 10,
        proceduralRuleCount: Int = 5,
        scriptletRuleCount: Int = 3,
        cspRuleCount: Int = 4,
        htmlFilterRuleCount: Int = 6,
        relativeFilePath: String = "filter_sources/source_1/current.txt"
    ): FilterSource = FilterSource(
        id = id,
        referenceId = referenceId,
        name = name,
        url = url,
        category = category,
        enabled = enabled,
        isPreset = isPreset,
        lastUpdated = lastUpdated,
        lastUpdateStatus = lastUpdateStatus,
        errorMessage = errorMessage,
        etag = etag,
        lastModified = lastModified,
        checksum = checksum,
        totalLineCount = totalLineCount,
        parsedRuleCount = parsedRuleCount,
        unsupportedRuleCount = unsupportedRuleCount,
        invalidRuleCount = invalidRuleCount,
        networkRuleCount = networkRuleCount,
        cosmeticRuleCount = cosmeticRuleCount,
        proceduralRuleCount = proceduralRuleCount,
        scriptletRuleCount = scriptletRuleCount,
        cspRuleCount = cspRuleCount,
        htmlFilterRuleCount = htmlFilterRuleCount,
        relativeFilePath = relativeFilePath
    )

    // ---- 1. Empty table + 84 candidates: 84 inserts, 84 rows in DB -----------------------------

    @Test
    fun emptyTable_sync84Candidates_inserts84AndDbContains84() = runTest(testDispatcher) {
        assertEquals("precondition: table empty", 0, dao.count())

        val plan = dao.syncCatalogAtomically(catalogCandidates)

        assertEquals("plan.inserts must be 84", 84, plan.inserts.size)
        assertTrue("plan.updates must be empty on first sync", plan.updates.isEmpty())
        assertTrue("plan.unchangedIds must be empty on first sync", plan.unchangedIds.isEmpty())
        assertTrue("plan.urlChangedIds must be empty on first sync", plan.urlChangedIds.isEmpty())

        val all = dao.getAllSources()
        assertEquals("database must contain exactly 84 catalog rows", 84, all.size)
        assertTrue("all inserted rows must be presets", all.all { it.isPreset })
    }

    // ---- 2. Second identical synchronization: zero inserts / zero updates, still 84 rows ---------

    @Test
    fun secondIdenticalSync_zeroInsertsZeroUpdates_84RowsUnchanged() = runTest(testDispatcher) {
        dao.syncCatalogAtomically(catalogCandidates)
        assertEquals(84, dao.count())

        val plan = dao.syncCatalogAtomically(catalogCandidates)

        assertEquals("second sync: zero inserts", 0, plan.inserts.size)
        assertEquals("second sync: zero updates", 0, plan.updates.size)
        assertEquals("second sync: 84 unchanged", 84, plan.unchangedIds.size)
        assertTrue("second sync: no url change", plan.urlChangedIds.isEmpty())

        val all = dao.getAllSources()
        assertEquals("database must remain exactly 84 rows", 84, all.size)
    }

    // ---- 3. Existing legacy preset (referenceId=null) claims referenceId + metadata, keeps state

    @Test
    fun legacyNullRefIdPreset_claimsRefIdAndMetadata_preservingRuntimeState() = runTest(testDispatcher) {
        // refId 2 is locked to local id 1 (per FilterSourceCatalogMapper.LEGACY_LOCAL_IDS).
        val candidate = catalogCandidates.first { it.referenceId == 2 }
        assertEquals("precondition: candidate for refId 2 occupies locked local id 1", 1, candidate.id)

        val legacy = fullRuntimeSource(
            id = 1,
            referenceId = null,
            isPreset = true,
            url = "https://old-url.example",
            name = "Old Legacy Name",
            category = FilterSourceCategory.PRIVACY,
            enabled = false,
            relativeFilePath = "filter_sources/source_1/current.txt",
            etag = "legacy-etag",
            lastModified = "Tue, 02 Feb 2026",
            checksum = "legacy-checksum",
            lastUpdateStatus = FilterSourceStatus.FAILED,
            errorMessage = "legacy error",
            totalLineCount = 4242,
            parsedRuleCount = 3000,
            networkRuleCount = 2500,
            cosmeticRuleCount = 300,
            lastUpdated = 777L
        )
        dao.insert(legacy)
        assertEquals(1, dao.count())

        val plan = dao.syncCatalogAtomically(listOf(candidate))

        // Reconciliation matched the legacy row by locked local id (rule 2): it is an update.
        assertEquals("legacy row must be updated (catalog-owned metadata differs)", 1, plan.updates.size)
        assertTrue("no inserts for a matched legacy row", plan.inserts.isEmpty())
        assertEquals("url changed", setOf(1), plan.urlChangedIds)

        val after = dao.getSourceById(1)!!
        // Catalog metadata + referenceId received:
        assertEquals(candidate.referenceId, after.referenceId)
        assertEquals(candidate.name, after.name)
        assertEquals(candidate.url, after.url)
        assertEquals(candidate.category, after.category)
        assertTrue("isPreset must be true after sync", after.isPreset)

        // Runtime/user state preserved verbatim:
        assertEquals("local id preserved", 1, after.id)
        assertEquals("enabled preserved", false, after.enabled)
        assertEquals("relativeFilePath preserved", "filter_sources/source_1/current.txt", after.relativeFilePath)
        assertEquals("etag preserved", "legacy-etag", after.etag)
        assertEquals("lastModified preserved", "Tue, 02 Feb 2026", after.lastModified)
        assertEquals("checksum preserved", "legacy-checksum", after.checksum)
        assertEquals("lastUpdateStatus preserved", FilterSourceStatus.FAILED, after.lastUpdateStatus)
        assertEquals("errorMessage preserved", "legacy error", after.errorMessage)
        assertEquals("lastUpdated preserved", 777L, after.lastUpdated)
        assertEquals("totalLineCount preserved", 4242, after.totalLineCount)
        assertEquals("parsedRuleCount preserved", 3000, after.parsedRuleCount)
        assertEquals("networkRuleCount preserved", 2500, after.networkRuleCount)
        assertEquals("cosmeticRuleCount preserved", 300, after.cosmeticRuleCount)
    }

    // ---- 4. Custom rows: byte/value-equivalent after sync, never in inserts/updates -------------

    @Test
    fun customRows_remainByteEquivalentAndExcludedFromPlan() = runTest(testDispatcher) {
        // Custom row occupying two positive ids that are NOT locked local IDs for any refId.
        // Locked ids are 1..7 (and negatives). Use 300 and 301 as safe custom ids.
        val custom1 = fullRuntimeSource(
            id = 300,
            referenceId = null,
            isPreset = false,
            name = "My Custom One",
            url = "https://custom.example/1.txt",
            category = FilterSourceCategory.CUSTOM,
            enabled = true,
            relativeFilePath = "filter_sources/source_300/current.txt",
            etag = "custom-etag-1",
            lastModified = "Wed, 03 Mar 2026",
            checksum = "custom-checksum-1",
            lastUpdateStatus = FilterSourceStatus.SUCCESS,
            errorMessage = "custom error 1",
            totalLineCount = 500,
            parsedRuleCount = 450,
            lastUpdated = 99L
        )
        val custom2 = fullRuntimeSource(
            id = 301,
            referenceId = null,
            isPreset = false,
            name = "My Custom Two",
            url = "https://custom.example/2.txt",
            category = FilterSourceCategory.OTHER,
            enabled = false,
            relativeFilePath = "filter_sources/source_301/current.txt"
        )
        dao.insert(custom1)
        dao.insert(custom2)

        val plan = dao.syncCatalogAtomically(catalogCandidates)

        // Custom rows are not candidates and must not be touched:
        assertFalse("custom id=300 must not be in inserts", plan.inserts.any { it.id == 300 })
        assertFalse("custom id=300 must not be in updates", plan.updates.any { it.id == 300 })
        assertFalse("custom id=300 must not be in unchangedIds", plan.unchangedIds.contains(300))
        assertFalse("custom id=300 must not be in urlChangedIds", plan.urlChangedIds.contains(300))

        assertFalse("custom id=301 must not be in inserts", plan.inserts.any { it.id == 301 })
        assertFalse("custom id=301 must not be in updates", plan.updates.any { it.id == 301 })
        assertFalse("custom id=301 must not be in unchangedIds", plan.unchangedIds.contains(301))
        assertFalse("custom id=301 must not be in urlChangedIds", plan.urlChangedIds.contains(301))

        // 84 catalog rows inserted:
        assertEquals("84 catalog inserts expected", 84, plan.inserts.size)
        assertEquals("86 total rows after sync", 86, dao.count())

        // Custom rows byte/value-equivalent after sync:
        val all = dao.getAllSources()
        val after1 = all.first { it.id == 300 }
        val after2 = all.first { it.id == 301 }

        assertEquals(custom1, after1)
        assertEquals(custom2, after2)
    }

    // ---- 5. Metadata update: name/url/category updated; urlChangedIds set; runtime untouched ----

    @Test
    fun metadataUpdate_changesNameUrlCategory_reportsUrlChange_preservesRuntime() = runTest(testDispatcher) {
        // refId 204 is locked to local id 2. Seed an existing row with stale metadata + runtime state.
        val candidate = catalogCandidates.first { it.referenceId == 204 }
        assertEquals("precondition: candidate for refId 204 occupies locked local id 2", 2, candidate.id)

        val existing = fullRuntimeSource(
            id = 2,
            referenceId = 204,
            isPreset = true,
            name = "Stale Name",
            url = "https://stale.example/filter.txt",
            category = FilterSourceCategory.ADS,
            enabled = true,
            relativeFilePath = "filter_sources/source_2/current.txt",
            etag = "stale-etag",
            lastModified = "Mon, 01 Jan 2024",
            checksum = "stale-checksum",
            lastUpdateStatus = FilterSourceStatus.SUCCESS,
            errorMessage = "stale error",
            totalLineCount = 222,
            parsedRuleCount = 200,
            lastUpdated = 555L
        )
        assertTrue("precondition: existing url must differ from candidate url", existing.url != candidate.url)
        dao.insert(existing)

        val plan = dao.syncCatalogAtomically(listOf(candidate))

        assertEquals("exactly one update", 1, plan.updates.size)
        assertTrue("no inserts", plan.inserts.isEmpty())
        assertEquals("urlChangedIds contains the local id", setOf(2), plan.urlChangedIds)

        val after = dao.getSourceById(2)!!
        // Catalog-owned metadata updated:
        assertEquals(candidate.name, after.name)
        assertEquals(candidate.url, after.url)
        assertEquals(candidate.category, after.category)
        assertEquals(candidate.referenceId, after.referenceId)
        assertTrue(after.isPreset)

        // Runtime/user state unchanged:
        assertEquals("local id preserved", 2, after.id)
        assertEquals("enabled preserved", true, after.enabled)
        assertEquals("relativeFilePath preserved", "filter_sources/source_2/current.txt", after.relativeFilePath)
        assertEquals("etag preserved", "stale-etag", after.etag)
        assertEquals("lastModified preserved", "Mon, 01 Jan 2024", after.lastModified)
        assertEquals("checksum preserved", "stale-checksum", after.checksum)
        assertEquals("lastUpdateStatus preserved", FilterSourceStatus.SUCCESS, after.lastUpdateStatus)
        assertEquals("errorMessage preserved", "stale error", after.errorMessage)
        assertEquals("lastUpdated preserved", 555L, after.lastUpdated)
        assertEquals("totalLineCount preserved", 222, after.totalLineCount)
        assertEquals("parsedRuleCount preserved", 200, after.parsedRuleCount)
    }

    // ---- 6. Collision rollback: preinsert custom row at candidate local id; sync throws; no writes

    @Test
    fun collisionRollback_abortsTransaction_noCandidateRowsWritten() = runTest(testDispatcher) {
        // refId 2 is locked to local id 1. Preinsert a CUSTOM row (referenceId=null, isPreset=false)
        // at id=1 to force the reconciler to raise IllegalArgumentException on the candidate.
        val collision = fullRuntimeSource(
            id = 1,
            referenceId = null,
            isPreset = false,
            name = "Colliding Custom",
            url = "https://custom.example/collision.txt",
            category = FilterSourceCategory.CUSTOM,
            enabled = true,
            relativeFilePath = "filter_sources/source_1/current.txt"
        )
        dao.insert(collision)
        assertEquals(1, dao.count())

        val candidateForRef2 = catalogCandidates.first { it.referenceId == 2 }
        assertEquals(1, candidateForRef2.id)

        var thrown: IllegalArgumentException? = null
        try {
            dao.syncCatalogAtomically(listOf(candidateForRef2))
            fail("expected IllegalArgumentException for custom-row local-ID collision")
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertNotNull("IllegalArgumentException must be thrown", thrown)
        assertTrue(
            "message must mention collision: ${thrown!!.message}",
            thrown.message!!.contains("collides")
        )

        // No candidate rows were partially inserted or updated:
        assertEquals("transaction rolled back: still only the colliding custom row", 1, dao.count())
        val after = dao.getSourceById(1)!!
        assertEquals("colliding custom row untouched", collision, after)

        // The candidate for refId 2 was never inserted:
        // (its locked id is 1, which holds the custom row; no other rows exist)
        assertNull("no catalog rows inserted after rollback", dao.getSourceById(2))
    }
}

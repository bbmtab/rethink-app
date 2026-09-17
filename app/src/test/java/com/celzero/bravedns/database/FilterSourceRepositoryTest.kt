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
import java.io.File
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Focused Room/DAO/repo tests for [FilterSource] B1.
 *
 * Runs on Robolectric with an in-memory Room database so queries/transactions are real.
 * Filesystem operations go through [FilterSourceFileStore] bound to a real Context, which
 * writes under `RuntimeEnvironment.application.filesDir/filter_sources/...` — fine on Robolectric.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FilterSourceRepositoryTest : KoinTest {

    @get:Rule val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var dao: FilterSourceDao
    private lateinit var fileStore: FilterSourceFileStore
    private lateinit var repo: FilterSourceRepository
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = RuntimeEnvironment.getApplication()
        // Robolectric provides a working Context; in-memory DB so each test is isolated.
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries() // test-only; never in production
            .build()
        dao = db.filterSourceDao()
        fileStore = FilterSourceFileStore(appContext)
        repo = FilterSourceRepository(dao, fileStore)
        // Standalone module — no production Koin wiring needed.
        startKoin { modules(emptyList()) }
    }

    @After
    fun tearDown() {
        db.close()
        stopKoin()
    }

    // ---- T1-T2: insert + generated id -------------------------------------------------
    @Test
    fun insert_returnsGeneratedId_greaterThanZero() = runTest(testDispatcher) {
        val src = repo.addSource(
            name = "Custom Test",
            url = "https://example.com/custom.txt",
            category = FilterSourceCategory.CUSTOM
        )
        assertTrue("generated id must be > 0", src.id > 0)
        assertEquals(FilterSourceCategory.CUSTOM, src.category)
    }

    // ---- T3: read by id -----------------------------------------------------------------
    @Test
    fun getSourceById_afterInsert_returnsRow() = runTest(testDispatcher) {
        val inserted = repo.addSource(
            name = "Read-Test",
            url = "https://example.com/read.txt",
            category = FilterSourceCategory.ADS
        )
        val fetched = repo.getSourceById(inserted.id)
        assertNotNull(fetched)
        assertEquals(inserted.id, fetched!!.id)
        assertEquals("Read-Test", fetched.name)
    }

    // ---- T4: update ---------------------------------------------------------------------
    @Test
    fun update_persistsChangedFields() = runTest(testDispatcher) {
        val src = repo.addSource(
            name = "Updatable",
            url = "https://example.com/upd.txt",
            category = FilterSourceCategory.OTHER,
            enabled = true
        )
        val updated = src.copy(enabled = false, errorMessage = "network unreachable")
        repo.update(updated)
        val reloaded = repo.getSourceById(src.id)
        assertNotNull(reloaded)
        assertFalse(reloaded!!.enabled)
        assertEquals("network unreachable", reloaded.errorMessage)
    }

    // ---- T5: delete ---------------------------------------------------------------------
    @Test
    fun delete_removesRowFromDatabase() = runTest(testDispatcher) {
        val src = repo.addSource(
            name = "Deletable",
            url = "https://example.com/del.txt",
            category = FilterSourceCategory.CUSTOM
        )
        repo.delete(src)
        assertNull(repo.getSourceById(src.id))
    }

    // ---- T6: getAll ---------------------------------------------------------------------
    @Test
    fun getAllSources_returnsAllRows_orderByPresetDescIdAsc() = runTest(testDispatcher) {
        repo.addSource("A", "https://a", FilterSourceCategory.ADS, isPreset = false)
        repo.addSource("B", "https://b", FilterSourceCategory.PRIVACY, isPreset = true)
        val all = repo.getAllSources()
        assertEquals(2, all.size)
        // isPreset=true should sort before isPreset=false per DAO ORDER BY
        assertTrue(all[0].isPreset)
        assertFalse(all[1].isPreset)
    }

    // ---- T7-T8: enabled toggle ----------------------------------------------------------
    @Test
    fun toggleEnabledStatus_persists_and_enabled_queryExcludesDisabled() = runTest(testDispatcher) {
        repo.addSource("ToggleMe", "https://toggle", FilterSourceCategory.ADS, enabled = true)
        val src = repo.getAllSources().first { it.name == "ToggleMe" }
        // disable
        repo.updateEnabledStatus(src.id, false)
        var enabled = repo.getEnabledSources()
        assertEquals("disabled row must not appear in getEnabledSources",
            0, enabled.count { it.id == src.id })

        // re-enable
        repo.updateEnabledStatus(src.id, true)
        enabled = repo.getEnabledSources()
        assertEquals("re-enabled row must appear in getEnabledSources",
            1, enabled.count { it.id == src.id })
    }

    // ---- T9: status + error -------------------------------------------------------------
    @Test
    fun updateStatus_persistsStatusAndError() = runTest(testDispatcher) {
        val src = repo.addSource(
            name = "Status-Test",
            url = "https://status.example",
            category = FilterSourceCategory.ADS
        )
        repo.updateStatus(src.id, FilterSourceStatus.FAILED, "DNS resolution failed")
        val reloaded = repo.getSourceById(src.id)
        assertNotNull(reloaded)
        assertEquals(FilterSourceStatus.FAILED, reloaded!!.lastUpdateStatus)
        assertEquals("DNS resolution failed", reloaded.errorMessage)
    }

    // ---- T10: diagnostic round-trip -----------------------------------------------------
    @Test
    fun diagnosticsRoundTrip_allCountsSurviveExact() = runTest(testDispatcher) {
        val src = repo.addSource(
            name = "Diag-RoundTrip",
            url = "https://diag.example",
            category = FilterSourceCategory.ADS,
            enabled = false
        ).copy(
            totalLineCount = 9999,
            parsedRuleCount = 8000,
            unsupportedRuleCount = 500,
            invalidRuleCount = 100,
            networkRuleCount = 4000,
            cosmeticRuleCount = 2000,
            proceduralRuleCount = 500,
            scriptletRuleCount = 800,
            cspRuleCount = 400,
            htmlFilterRuleCount = 200
        )
        repo.update(src)
        val reloaded = repo.getSourceById(src.id)
        assertNotNull(reloaded)
        assertEquals(9999, reloaded!!.totalLineCount)
        assertEquals(8000, reloaded.parsedRuleCount)
        assertEquals(500, reloaded.unsupportedRuleCount)
        assertEquals(100, reloaded.invalidRuleCount)
        assertEquals(4000, reloaded.networkRuleCount)
        assertEquals(2000, reloaded.cosmeticRuleCount)
        assertEquals(500, reloaded.proceduralRuleCount)
        assertEquals(800, reloaded.scriptletRuleCount)
        assertEquals(400, reloaded.cspRuleCount)
        assertEquals(200, reloaded.htmlFilterRuleCount)
    }

    // ---- T11: defaults ------------------------------------------------------------------
    @Test
    fun defaults_areCorrectWhenUsingParameterDefaults() = runTest(testDispatcher) {
        val src = repo.addSource(
            name = "Defaults",
            url = "https://defaults.example",
            category = FilterSourceCategory.CUSTOM
        )
        assertEquals("defaults: enabled should match constructor parameter", true, src.enabled)
        assertFalse("defaults: isPreset=false for user-add", src.isPreset)
        assertEquals(0L, src.lastUpdated)
        assertEquals(FilterSourceStatus.IDLE, src.lastUpdateStatus)
        assertNull(src.errorMessage)
        assertEquals(0, src.totalLineCount)
        assertEquals(0, src.parsedRuleCount)
        assertEquals(0, src.unsupportedRuleCount)
        assertEquals(0, src.invalidRuleCount)
        assertEquals(0, src.networkRuleCount)
        assertEquals(0, src.cosmeticRuleCount)
        assertEquals(0, src.proceduralRuleCount)
        assertEquals(0, src.scriptletRuleCount)
        assertEquals(0, src.cspRuleCount)
        assertEquals(0, src.htmlFilterRuleCount)
    }

    // ---- T12: canonical categories round-trip ------------------------------------------
    @Test
    fun allCanonicalCategories_roundTrip() = runTest(testDispatcher) {
        val categories = listOf(
            FilterSourceCategory.ADS,
            FilterSourceCategory.PRIVACY,
            FilterSourceCategory.SOCIAL,
            FilterSourceCategory.ANNOYANCES,
            FilterSourceCategory.SECURITY,
            FilterSourceCategory.LANGUAGE_SPECIFIC,
            FilterSourceCategory.OTHER,
            FilterSourceCategory.CUSTOM
        )
        categories.forEach { cat ->
            val src = repo.addSource("cat-$cat", "https://cat.$cat", cat)
            val fetched = repo.getSourceById(src.id)
            assertEquals("category round-trip for $cat", cat, fetched!!.category)
        }
    }

    @Test
    fun peterLowe_isStoredAsAds() = runTest(testDispatcher) {
        val src = repo.addSource(
            name = "Peter Lowe's Blocklist",
            url = "https://pgl.yoyo.org/adservers/serverlist.php",
            category = FilterSourceCategory.ADS
        )
        assertEquals(FilterSourceCategory.ADS, src.category)
    }

    // ---- T19-T21: path helpers ----------------------------------------------------------
    @Test
    fun filePathHelpers_deriveCorrectPaths_underFilterSourcesRoot() = runTest(testDispatcher) {
        val src = repo.addSource("Path-Test", "https://path.test", FilterSourceCategory.CUSTOM)
        val id = src.id
        val expectedRel = FilterSourceFileStore.relativeFilePathFor(id)
        val expectedTmp = FilterSourceFileStore.relativeDownloadTmpFor(id)

        assertEquals(expectedRel, src.relativeFilePath)
        assertEquals(expectedRel, fileStore.relativeFilePathFor(id))
        assertEquals(expectedTmp, fileStore.relativeDownloadTempFor(id))

        // Absolute paths must live inside appContext.filesDir
        assertTrue(fileStore.currentFile(id).absolutePath.startsWith(appContext.filesDir.absolutePath))
        assertTrue(fileStore.downloadTempFile(id).absolutePath.startsWith(appContext.filesDir.absolutePath))
        assertFalse("must not contain user-entered name in path",
            fileStore.currentFile(id).absolutePath.contains("Path-Test"))
    }

    // ---- T22: ensureRootExists / removeSourceDirectory ---------------------------------
    @Test
    fun ensureRootExists_createsDirectory_removeSourceDirectory_cleansUpOnlyThatSource() =
        runTest(testDispatcher) {
            val root = fileStore.ensureRootExists()
            assertTrue(root.exists())
            assertTrue(root.isDirectory)

            val s1 = repo.addSource("S1", "https://s1", FilterSourceCategory.CUSTOM)
            val s2 = repo.addSource("S2", "https://s2", FilterSourceCategory.CUSTOM)

            // touch files with real content so deleteRecursively has something to wipe
            fileStore.currentFile(s1.id).parentFile?.mkdirs()
            fileStore.currentFile(s1.id).writeText("s1-content")
            fileStore.currentFile(s2.id).parentFile?.mkdirs()
            fileStore.currentFile(s2.id).writeText("s2-content")

            repo.deleteById(s1.id)
            assertNull(repo.getSourceById(s1.id))
            assertFalse("s1 dir should be removed",
                fileStore.sourceDirectory(s1.id).exists())
            assertTrue("s2 dir must survive sibling cleanup",
                fileStore.sourceDirectory(s2.id).exists())
        }

    // ---- B5-4B-2 regression: catalog + MIGRATION_31_32 (6 tests) --------------------

    private fun migrationSeedRow(
        id: Int,
        name: String,
        url: String,
        category: String,
        enabled: Boolean,
        isPreset: Boolean
    ): FilterSource =
        FilterSource(
            id = id,
            name = name,
            url = url,
            category = category,
            enabled = enabled,
            isPreset = isPreset,
            relativeFilePath = FilterSourceFileStore.relativeFilePathFor(id)
        )

    @Test
    fun migration31To32_insertsThreeReservedPresetsAndPreservesLegacyRow() =
        runTest(testDispatcher) {
            val legacy =
                migrationSeedRow(
                        id = 1,
                        name = "Existing Legacy",
                        url = "https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt",
                        category = FilterSourceCategory.ADS,
                        enabled = false,
                        isPreset = true
                    )
                    .copy(totalLineCount = 42, parsedRuleCount = 17)

            dao.insert(legacy)
            AppDatabase.MIGRATION_31_32.migrate(db.openHelper.writableDatabase)

            assertEquals(4, repo.count())

            val preserved = repo.getSourceById(1)
            assertNotNull(preserved)
            assertEquals("Existing Legacy", preserved!!.name)
            assertFalse(preserved.enabled)
            assertEquals(42, preserved.totalLineCount)
            assertEquals(17, preserved.parsedRuleCount)

            val social = repo.getSourceById(-1001)
            val security = repo.getSourceById(-1002)
            val language = repo.getSourceById(-1003)

            assertNotNull(social)
            assertNotNull(security)
            assertNotNull(language)

            assertEquals(FilterSourceCategory.SOCIAL, social!!.category)
            assertEquals(FilterSourceCategory.SECURITY, security!!.category)
            assertEquals(FilterSourceCategory.LANGUAGE_SPECIFIC, language!!.category)

            assertFalse(social.enabled)
            assertFalse(security.enabled)
            assertFalse(language.enabled)

            assertTrue(social.isPreset)
            assertTrue(security.isPreset)
            assertTrue(language.isPreset)

            assertEquals(
                "filter_sources/source_-1001/current.txt",
                social.relativeFilePath
            )
            assertEquals(
                "filter_sources/source_-1002/current.txt",
                security.relativeFilePath
            )
            assertEquals(
                "filter_sources/source_-1003/current.txt",
                language.relativeFilePath
            )
        }

    @Test
    fun migration31To32_sameUrlCustomRowIsPreservedWithoutDuplicate() =
        runTest(testDispatcher) {
            val socialUrl = "https://easylist.to/easylist/fanboy-social.txt"
            val custom =
                migrationSeedRow(
                        id = 8,
                        name = "Existing Custom Social URL",
                        url = socialUrl,
                        category = FilterSourceCategory.CUSTOM,
                        enabled = true,
                        isPreset = false
                    )
                    .copy(totalLineCount = 55)

            dao.insert(custom)
            AppDatabase.MIGRATION_31_32.migrate(db.openHelper.writableDatabase)

            assertEquals(3, repo.count())
            assertNull(repo.getSourceById(-1001))
            assertNotNull(repo.getSourceById(-1002))
            assertNotNull(repo.getSourceById(-1003))

            val preserved = repo.getSourceById(8)
            assertNotNull(preserved)
            assertEquals("Existing Custom Social URL", preserved!!.name)
            assertEquals(FilterSourceCategory.CUSTOM, preserved.category)
            assertTrue(preserved.enabled)
            assertFalse(preserved.isPreset)
            assertEquals(55, preserved.totalLineCount)

            assertEquals(
                1,
                repo.getAllSources().count { it.url == socialUrl }
            )
        }

    @Test
    fun migration31To32_reservedIdDifferentUrlAbortsBeforeAnyInsert() =
        runTest(testDispatcher) {
            val collision =
                migrationSeedRow(
                    id = -1003,
                    name = "Occupied Reserved ID",
                    url = "https://example.test/occupied-reserved-id.txt",
                    category = FilterSourceCategory.CUSTOM,
                    enabled = true,
                    isPreset = false
                )

            dao.insert(collision)

            try {
                AppDatabase.MIGRATION_31_32.migrate(db.openHelper.writableDatabase)
                fail("expected reserved-id collision to abort MIGRATION_31_32")
            } catch (e: IllegalStateException) {
                assertTrue(
                    e.message.orEmpty().contains(
                        "MIGRATION_31_32: reserved preset id -1003 already occupied"
                    )
                )
            }

            assertEquals(1, repo.count())
            assertNull(repo.getSourceById(-1001))
            assertNull(repo.getSourceById(-1002))

            val preserved = repo.getSourceById(-1003)
            assertNotNull(preserved)
            assertEquals(
                "https://example.test/occupied-reserved-id.txt",
                preserved!!.url
            )
            assertFalse(preserved.isPreset)
        }

    @Test
    fun migration31To32_sameIdSameUrlPreservesExistingRow() =
        runTest(testDispatcher) {
            val socialUrl = "https://easylist.to/easylist/fanboy-social.txt"
            val existing =
                migrationSeedRow(
                        id = -1001,
                        name = "Existing Same Identity",
                        url = socialUrl,
                        category = FilterSourceCategory.CUSTOM,
                        enabled = true,
                        isPreset = false
                    )
                    .copy(totalLineCount = 77, parsedRuleCount = 66)

            dao.insert(existing)
            AppDatabase.MIGRATION_31_32.migrate(db.openHelper.writableDatabase)

            assertEquals(3, repo.count())

            val preserved = repo.getSourceById(-1001)
            assertNotNull(preserved)
            assertEquals("Existing Same Identity", preserved!!.name)
            assertEquals(FilterSourceCategory.CUSTOM, preserved.category)
            assertTrue(preserved.enabled)
            assertFalse(preserved.isPreset)
            assertEquals(77, preserved.totalLineCount)
            assertEquals(66, preserved.parsedRuleCount)

            assertNotNull(repo.getSourceById(-1002))
            assertNotNull(repo.getSourceById(-1003))
        }

    @Test
    fun migration31To32_secondRunIsIdempotent() =
        runTest(testDispatcher) {
            AppDatabase.MIGRATION_31_32.migrate(db.openHelper.writableDatabase)
            AppDatabase.MIGRATION_31_32.migrate(db.openHelper.writableDatabase)

            val all = repo.getAllSources()
            assertEquals(3, all.size)
            assertEquals(
                listOf(-1003, -1002, -1001),
                all.map { it.id }.sorted()
            )
            assertEquals(3, all.map { it.url }.toSet().size)
        }

        // ---- B5 JSON-CATALOG-S1 MIGRATION_32_33: referenceId schema addition ---------------------------------

    /**
     * Proves: database lama dapat dimigrasikan ke schema baru; kolom referenceId
     * nullable; data row lama tidak hilang.
     *
     * Simulates a pre-migration v32 FilterSource table by dropping the v33 table
     * and recreating it with the v32 schema (no referenceId column), inserts
     * rows, runs MIGRATION_32_33, then verifies:
     * - referenceId column exists and is NULL for all pre-migration rows
     * - All pre-migration rows are preserved (same count, same names)
     * - New rows can be inserted WITH a non-null referenceId
     */
    @Test
    fun migration32To33_addsNullableReferenceIdColumnAndPreservesRows() = runTest(testDispatcher) {
        val rawDb = db.openHelper.writableDatabase

        // Simulate pre-migration (v32) state: drop the v33 table and recreate
        // it with the v32 schema — no referenceId column.
        rawDb.execSQL("DROP TABLE IF EXISTS FilterSource")
        rawDb.execSQL(
            """
            CREATE TABLE FilterSource (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                url TEXT NOT NULL,
                category TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                isPreset INTEGER NOT NULL DEFAULT 0,
                lastUpdated INTEGER NOT NULL DEFAULT 0,
                lastUpdateStatus TEXT NOT NULL DEFAULT 'IDLE',
                errorMessage TEXT,
                etag TEXT,
                lastModified TEXT,
                checksum TEXT,
                totalLineCount INTEGER NOT NULL DEFAULT 0,
                parsedRuleCount INTEGER NOT NULL DEFAULT 0,
                unsupportedRuleCount INTEGER NOT NULL DEFAULT 0,
                invalidRuleCount INTEGER NOT NULL DEFAULT 0,
                networkRuleCount INTEGER NOT NULL DEFAULT 0,
                cosmeticRuleCount INTEGER NOT NULL DEFAULT 0,
                proceduralRuleCount INTEGER NOT NULL DEFAULT 0,
                scriptletRuleCount INTEGER NOT NULL DEFAULT 0,
                cspRuleCount INTEGER NOT NULL DEFAULT 0,
                htmlFilterRuleCount INTEGER NOT NULL DEFAULT 0,
                relativeFilePath TEXT NOT NULL
            )
            """.trimIndent()
        )
        rawDb.execSQL("CREATE INDEX IF NOT EXISTS index_FilterSource_category ON FilterSource(category)")
        rawDb.execSQL("CREATE INDEX IF NOT EXISTS index_FilterSource_enabled ON FilterSource(enabled)")
        rawDb.execSQL("CREATE INDEX IF NOT EXISTS index_FilterSource_url ON FilterSource(url)")

        // Insert legacy rows via raw SQL — no referenceId column exists at v32.
        rawDb.execSQL(
            "INSERT INTO FilterSource (id, name, url, category, enabled, isPreset, relativeFilePath) " +
            "VALUES (1, 'Legacy AdGuard', 'https://filters.example.com/adguard.txt', 'ADS', 1, 1, 'filter_sources/source_1/current.txt')"
        )
        rawDb.execSQL(
            "INSERT INTO FilterSource (id, name, url, category, enabled, isPreset, relativeFilePath) " +
            "VALUES (-1001, 'Legacy Social', 'https://example.com/social.txt', 'SOCIAL', 0, 1, 'filter_sources/source_-1001/current.txt')"
        )

        // Run migration 32→33 — adds nullable referenceId column.
        AppDatabase.MIGRATION_32_33.migrate(rawDb)

        // Verify all rows are preserved and referenceId is NULL (column is nullable).
        assertEquals("post-migration row count preserved", 2, repo.count())

        val legacy1 = repo.getSourceById(1)
        assertNotNull(legacy1)
        assertEquals("Legacy AdGuard", legacy1!!.name)
        assertNull("referenceId is NULL for legacy row id=1", legacy1.referenceId)

        val legacy2 = repo.getSourceById(-1001)
        assertNotNull(legacy2)
        assertEquals("Legacy Social", legacy2!!.name)
        assertNull("referenceId is NULL for reserved-id row id=-1001", legacy2.referenceId)

        // Verify a new row can be inserted WITH a non-null referenceId (column is functional).
        val newId = dao.insert(
            FilterSource(
                name = "Catalog Entry",
                url = "https://example.com/catalog.txt",
                category = FilterSourceCategory.ADS,
                relativeFilePath = "filter_sources/source_99/current.txt",
                referenceId = 42
            )
        )
        val withRef = repo.getSourceById(newId.toInt())
        assertNotNull(withRef)
        assertEquals(42, withRef!!.referenceId)
    }

    /**
     * Proves: migrasi lama tetap ada tanpa perubahan. Verifies version bounds
     * of the existing MIGRATION_31_32 (unchanged) and the new MIGRATION_32_33.
     */
    @Test
    fun migrationChain_oldMigration31To32UnchangedAnd32To33Present() {
        assertEquals(31, AppDatabase.MIGRATION_31_32.startVersion)
        assertEquals(32, AppDatabase.MIGRATION_31_32.endVersion)
        assertEquals(32, AppDatabase.MIGRATION_32_33.startVersion)
        assertEquals(33, AppDatabase.MIGRATION_32_33.endVersion)
    }

    // ---- B5 JSON-CATALOG-S4C: syncCatalog delegation tests ---------------------------------

    private fun smallCatalog(): FilterSourceCatalogJson.Catalog = FilterSourceCatalogJson.Catalog(
        groups = listOf(
            FilterSourceCatalogJson.CatalogGroup(groupId = 1, groupName = "Ad blocking"),
            FilterSourceCatalogJson.CatalogGroup(groupId = 2, groupName = "Privacy")
        ),
        tags = emptyList(),
        filters = listOf(
            FilterSourceCatalogJson.CatalogFilter(
                filterId = 1, name = "Test Ad", description = "a", groupId = 1,
                subscriptionUrl = "https://a.com", downloadUrl = "https://a.example/a.txt",
                homepage = "https://a.example", expires = 1, version = "1",
                timeUpdated = "2025-01-01", deprecated = false, trustLevel = "full",
                languages = emptyList(), tags = emptyList(), platformsExcluded = null
            ),
            FilterSourceCatalogJson.CatalogFilter(
                filterId = 2, name = "Test Privacy", description = "b", groupId = 2,
                subscriptionUrl = "https://b.com", downloadUrl = "https://b.example/b.txt",
                homepage = "https://b.example", expires = 1, version = "1",
                timeUpdated = "2025-01-01", deprecated = false, trustLevel = "full",
                languages = emptyList(), tags = emptyList(), platformsExcluded = null
            )
        )
    )

    @Test
    fun syncCatalog_mapsAndDelegatesExactlyOnce() = runTest(testDispatcher) {
        val catalog = smallCatalog()
        val expectedCandidates = FilterSourceCatalogMapper.map(catalog)

        val fakeDao = mockk<FilterSourceDao>(relaxed = true)
        val plan = ReconciliationPlan(
            inserts = expectedCandidates, updates = emptyList(),
            unchangedIds = emptySet(), urlChangedIds = emptySet()
        )
        coEvery { fakeDao.syncCatalogAtomically(expectedCandidates) } returns plan

        val repo = FilterSourceRepository(fakeDao, fileStore)
        val result = repo.syncCatalog(catalog)

        coVerify(exactly = 1) { fakeDao.syncCatalogAtomically(expectedCandidates) }
        assertEquals(plan, result)
    }

    @Test
    fun syncCatalog_returnsDaoPlanUnchanged() = runTest(testDispatcher) {
        val catalog = smallCatalog()
        val candidates = FilterSourceCatalogMapper.map(catalog)

        val insertCandidate = candidates[0]
        val updateCandidate = candidates[1]
        val frozenPlan = ReconciliationPlan(
            inserts = listOf(insertCandidate),
            updates = listOf(updateCandidate),
            unchangedIds = setOf(100),
            urlChangedIds = setOf(200)
        )

        val fakeDao = mockk<FilterSourceDao>(relaxed = true)
        coEvery { fakeDao.syncCatalogAtomically(candidates) } returns frozenPlan

        val repo = FilterSourceRepository(fakeDao, fileStore)
        val result = repo.syncCatalog(catalog)

        assertEquals("inserts must be returned unchanged", frozenPlan.inserts, result.inserts)
        assertEquals("updates must be returned unchanged", frozenPlan.updates, result.updates)
        assertEquals("unchangedIds must be returned unchanged", frozenPlan.unchangedIds, result.unchangedIds)
        assertEquals("urlChangedIds must be returned unchanged", frozenPlan.urlChangedIds, result.urlChangedIds)
        assertSame("returned plan must be the exact instance from DAO", frozenPlan, result)
    }

    @Test
    fun syncCatalog_propagatesDaoFailureWithoutLegacyFallback() = runTest(testDispatcher) {
        val catalog = smallCatalog()
        val candidates = FilterSourceCatalogMapper.map(catalog)
        val failure = IllegalArgumentException("syncCatalogAtomically exploded")

        val fakeDao = mockk<FilterSourceDao>(relaxed = true)
        coEvery { fakeDao.syncCatalogAtomically(candidates) } throws failure

        val repo = FilterSourceRepository(fakeDao, fileStore)

        val caught = try {
            repo.syncCatalog(catalog)
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertNotNull("IllegalArgumentException must propagate", caught)
        assertSame("exact exception instance must propagate", failure, caught)
        coVerify(exactly = 1) { fakeDao.syncCatalogAtomically(candidates) }
    }

    // ---- CUSTOM-FILTER-B3: addCustomSource (6 tests) ---------------------------------------

    @Test
    fun addCustomSource_validInput_returnsAddedDisabledCustomRow() = runTest(testDispatcher) {
        val result = repo.addCustomSource(
            name = "  My Custom List  ",
            url = "  https://example.com/lists/custom.txt?token=abc  "
        )

        assertTrue("expected Added", result is AddCustomSourceResult.Added)
        val added = result as AddCustomSourceResult.Added
        val src = added.source

        assertEquals("name must be trimmed", "My Custom List", src.name)
        assertEquals("url must be trimmed",
            "https://example.com/lists/custom.txt?token=abc", src.url)
        assertTrue("generated id must be > 0", src.id > 0)
        assertEquals(FilterSourceCategory.CUSTOM, src.category)
        assertFalse("custom source must be created disabled", src.enabled)
        assertFalse(src.isPreset)
        assertNull(src.referenceId)
        assertEquals("relativeFilePath must be finalized from generated id",
            FilterSourceFileStore.relativeFilePathFor(src.id), src.relativeFilePath)

        val persisted = repo.getSourceById(src.id)
        assertEquals("persisted row must equal returned source", persisted, src)

        assertTrue("filter_sources root must exist after successful add",
            fileStore.ensureRootExists().exists())
    }

    @Test
    fun addCustomSource_invalidInput_returnsInvalidWithoutMutation() = runTest(testDispatcher) {
        val emptyNameResult = repo.addCustomSource(name = "   ", url = "https://example.com/x.txt")
        assertTrue(emptyNameResult is AddCustomSourceResult.InvalidInput)
        assertEquals(CustomFilterSourceValidator.Error.EMPTY_NAME,
            (emptyNameResult as AddCustomSourceResult.InvalidInput).error)

        val badSchemeResult = repo.addCustomSource(name = "FTP List", url = "ftp://example.com/x.txt")
        assertTrue(badSchemeResult is AddCustomSourceResult.InvalidInput)
        assertEquals(CustomFilterSourceValidator.Error.UNSUPPORTED_SCHEME,
            (badSchemeResult as AddCustomSourceResult.InvalidInput).error)

        assertEquals("zero DAO writes on invalid input", 0, repo.count())
        assertFalse("filter_sources root must not be created on invalid input",
            fileStore.rootDirectory().exists())
    }

    @Test
    fun addCustomSource_existingCustomUrl_returnsDuplicateWithoutMutation() = runTest(testDispatcher) {
        val url = "https://example.com/dup-custom.txt"
        val first = repo.addCustomSource(name = "First", url = url)
        assertTrue(first is AddCustomSourceResult.Added)
        val original = (first as AddCustomSourceResult.Added).source

        val second = repo.addCustomSource(name = "Second Different Name", url = url)

        assertTrue("expected DuplicateUrl for exact stored URL",
            second is AddCustomSourceResult.DuplicateUrl)
        assertEquals(url, (second as AddCustomSourceResult.DuplicateUrl).url)
        assertEquals("count must remain one", 1, repo.count())

        val unchanged = repo.getSourceById(original.id)
        assertEquals(original, unchanged)
    }

    @Test
    fun addCustomSource_existingCatalogUrl_returnsDuplicateWithoutMutation() = runTest(testDispatcher) {
        val catalogUrl = "https://example.com/catalog-owned.txt"
        dao.insert(
            FilterSource(
                id = 0,
                name = "Catalog Owned",
                url = catalogUrl,
                category = FilterSourceCategory.ADS,
                enabled = true,
                isPreset = true,
                relativeFilePath = "",
                referenceId = 77
            )
        )
        val catalogRow = repo.getAllSources().single { it.url == catalogUrl }
        val beforeCount = repo.count()

        val result = repo.addCustomSource(name = "User Copy", url = catalogUrl)

        assertTrue("catalog-owned URL must also be treated as duplicate",
            result is AddCustomSourceResult.DuplicateUrl)
        assertEquals(catalogUrl, (result as AddCustomSourceResult.DuplicateUrl).url)
        assertEquals(beforeCount, repo.count())
        assertEquals("catalog row must be unchanged",
            catalogRow, repo.getSourceById(catalogRow.id))
    }

    @Test
    fun addCustomSource_duplicateCustomName_trimmedCaseInsensitive_isRejected() =
        runTest(testDispatcher) {
            val first =
                repo.addCustomSource(
                    name = "Same Name",
                    url = "https://one.example/a.txt"
                )
            assertTrue(first is AddCustomSourceResult.Added)
            val original = (first as AddCustomSourceResult.Added).source

            val second =
                repo.addCustomSource(
                    name = "  same name  ",
                    url = "https://two.example/b.txt"
                )

            assertTrue(second is AddCustomSourceResult.DuplicateName)
            assertEquals(
                "Same Name",
                (second as AddCustomSourceResult.DuplicateName).name
            )
            assertEquals(1, repo.count())
            assertEquals(original, repo.getSourceById(original.id))
        }

    @Test
    fun addCustomSource_duplicateCatalogName_isRejected() =
        runTest(testDispatcher) {
            dao.insert(
                FilterSource(
                    id = 0,
                    name = "Catalog Owned",
                    url = "https://catalog.example/original.txt",
                    category = FilterSourceCategory.ADS,
                    enabled = true,
                    isPreset = true,
                    relativeFilePath = "",
                    referenceId = 77
                )
            )
            val existing = repo.getAllSources().single()
            val beforeCount = repo.count()

            val result =
                repo.addCustomSource(
                    name = "catalog owned",
                    url = "https://user.example/alternative.txt"
                )

            assertTrue(result is AddCustomSourceResult.DuplicateName)
            assertEquals(
                "Catalog Owned",
                (result as AddCustomSourceResult.DuplicateName).name
            )
            assertEquals(beforeCount, repo.count())
            assertEquals(existing, repo.getSourceById(existing.id))
        }

    @Test
    fun addCustomSource_unexpectedDaoFailure_isRethrown() = runTest(testDispatcher) {
        val sentinel = IllegalStateException("sentinel: unexpected DAO failure")
        val fakeDao = mockk<FilterSourceDao>(relaxed = true)
        coEvery { fakeDao.findByNameIgnoringCase(any()) } returns null
        coEvery { fakeDao.findByUrl(any()) } returns null
        coEvery {
            fakeDao.insertCustomAtomically(any(), any())
        } throws sentinel

        val repo = FilterSourceRepository(fakeDao, fileStore)

        val caught = try {
            repo.addCustomSource(name = "Boom", url = "https://boom.example/list.txt")
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertNotNull("non-IllegalArgumentException must propagate", caught)
        assertSame("exact sentinel instance must propagate untouched", sentinel, caught)
        // Sanity: a non-IAE failure must NOT enter the race re-query path —
        // exactly one pre-insert findByUrl, zero re-queries.
        coVerify(exactly = 1) { fakeDao.findByUrl(any()) }
        coVerify(exactly = 1) { fakeDao.findByNameIgnoringCase(any()) }
    }

    // ---- CUSTOM-FILTER-EDIT-B2: disabled CUSTOM repository edit -----------------

    @Test
    fun editCustomSource_invalidOrMissingSource_returnsWithoutMutation() =
        runTest(testDispatcher) {
            val original =
                (repo.addCustomSource(
                    "Original",
                    "https://example.com/original.txt"
                ) as AddCustomSourceResult.Added).source

            val invalid =
                repo.editCustomSource(
                    original.id,
                    "   ",
                    "https://example.com/new.txt"
                )
            assertTrue(invalid is EditCustomSourceResult.InvalidInput)
            assertEquals(original, repo.getSourceById(original.id))

            val missing =
                repo.editCustomSource(
                    999_999,
                    "Missing",
                    "https://example.com/missing.txt"
                )
            assertEquals(
                EditCustomSourceResult.SourceNotFound(999_999),
                missing
            )
            assertEquals(1, repo.count())
        }

    @Test
    fun editCustomSource_nonCustomOrEnabledSource_isRejected() =
        runTest(testDispatcher) {
            val preset =
                repo.addSource(
                    name = "Preset",
                    url = "https://example.com/preset.txt",
                    category = FilterSourceCategory.ADS,
                    enabled = false,
                    isPreset = true
                )
            val presetBefore = repo.getSourceById(preset.id)

            assertEquals(
                EditCustomSourceResult.NotCustomSource(preset.id),
                repo.editCustomSource(
                    preset.id,
                    "Changed",
                    "https://example.com/changed.txt"
                )
            )
            assertEquals(presetBefore, repo.getSourceById(preset.id))

            val custom =
                (repo.addCustomSource(
                    "Enabled Custom",
                    "https://example.com/enabled.txt"
                ) as AddCustomSourceResult.Added).source
            repo.updateEnabledStatus(custom.id, true)
            val enabledBefore = repo.getSourceById(custom.id)

            assertEquals(
                EditCustomSourceResult.SourceEnabled(custom.id),
                repo.editCustomSource(
                    custom.id,
                    "Changed Enabled",
                    "https://example.com/changed-enabled.txt"
                )
            )
            assertEquals(enabledBefore, repo.getSourceById(custom.id))
        }

    @Test
    fun editCustomSource_duplicateNameOrUrl_excludesSelfAndRejectsOthers() =
        runTest(testDispatcher) {
            val target =
                (repo.addCustomSource(
                    "Target",
                    "https://example.com/target.txt"
                ) as AddCustomSourceResult.Added).source
            val other =
                (repo.addCustomSource(
                    "Existing Name",
                    "https://example.com/existing.txt"
                ) as AddCustomSourceResult.Added).source

            val duplicateName =
                repo.editCustomSource(
                    target.id,
                    "  existing name  ",
                    "https://example.com/alternative.txt"
                )
            assertTrue(duplicateName is EditCustomSourceResult.DuplicateName)
            assertEquals(
                "Existing Name",
                (duplicateName as EditCustomSourceResult.DuplicateName).name
            )

            val duplicateUrl =
                repo.editCustomSource(
                    target.id,
                    "Unique Name",
                    other.url
                )
            assertTrue(duplicateUrl is EditCustomSourceResult.DuplicateUrl)
            assertEquals(
                other.url,
                (duplicateUrl as EditCustomSourceResult.DuplicateUrl).url
            )

            val ownValues =
                repo.editCustomSource(target.id, target.name, target.url)
            assertTrue(ownValues is EditCustomSourceResult.Updated)
            assertEquals(
                target,
                (ownValues as EditCustomSourceResult.Updated).source
            )
            assertEquals(target, repo.getSourceById(target.id))
            assertEquals(2, repo.count())
        }

    @Test
    fun editCustomSource_nameOnly_preservesFilesAndMetadata() =
        runTest(testDispatcher) {
            val source =
                (repo.addCustomSource(
                    "Old Name",
                    "https://example.com/same-url.txt"
                ) as AddCustomSourceResult.Added).source

            val enriched =
                source.copy(
                    lastUpdated = 1234L,
                    lastUpdateStatus = FilterSourceStatus.SUCCESS,
                    etag = "etag-old",
                    lastModified = "yesterday",
                    checksum = "checksum-old",
                    totalLineCount = 100,
                    parsedRuleCount = 80,
                    unsupportedRuleCount = 10,
                    invalidRuleCount = 10,
                    networkRuleCount = 50,
                    cosmeticRuleCount = 20,
                    proceduralRuleCount = 2,
                    scriptletRuleCount = 3,
                    cspRuleCount = 4,
                    htmlFilterRuleCount = 1
                )
            repo.update(enriched)

            val currentFile = fileStore.currentFile(source.id)
            currentFile.parentFile!!.mkdirs()
            currentFile.writeText("old downloaded body")

            val result =
                repo.editCustomSource(
                    source.id,
                    "  New Name  ",
                    source.url
                )

            assertTrue(result is EditCustomSourceResult.Updated)
            val updated = (result as EditCustomSourceResult.Updated).source
            assertEquals(enriched.copy(name = "New Name"), updated)
            assertEquals(updated, repo.getSourceById(source.id))
            assertTrue(currentFile.exists())
            assertEquals("old downloaded body", currentFile.readText())
        }

    @Test
    fun editCustomSource_urlChange_removesOldFilesAndResetsMetadata() =
        runTest(testDispatcher) {
            val source =
                (repo.addCustomSource(
                    "Old Name",
                    "https://old.example.com/list.txt"
                ) as AddCustomSourceResult.Added).source

            val enriched =
                source.copy(
                    lastUpdated = 9876L,
                    lastUpdateStatus = FilterSourceStatus.FAILED,
                    errorMessage = "HTTP 403",
                    etag = "etag-old",
                    lastModified = "old-modified",
                    checksum = "old-checksum",
                    totalLineCount = 100,
                    parsedRuleCount = 70,
                    unsupportedRuleCount = 20,
                    invalidRuleCount = 10,
                    networkRuleCount = 40,
                    cosmeticRuleCount = 20,
                    proceduralRuleCount = 2,
                    scriptletRuleCount = 3,
                    cspRuleCount = 4,
                    htmlFilterRuleCount = 1
                )
            repo.update(enriched)

            val currentFile = fileStore.currentFile(source.id)
            val tempFile = fileStore.downloadTempFile(source.id)
            currentFile.parentFile!!.mkdirs()
            currentFile.writeText("old body")
            tempFile.writeText("old temporary body")

            val newUrl = "https://alternative.example.com/list.txt"
            val result =
                repo.editCustomSource(
                    source.id,
                    "Alternative Host",
                    newUrl
                )

            assertTrue(result is EditCustomSourceResult.Updated)
            val updated = (result as EditCustomSourceResult.Updated).source

            assertEquals(source.id, updated.id)
            assertEquals("Alternative Host", updated.name)
            assertEquals(newUrl, updated.url)
            assertEquals(FilterSourceCategory.CUSTOM, updated.category)
            assertFalse(updated.enabled)
            assertFalse(updated.isPreset)
            assertEquals(source.relativeFilePath, updated.relativeFilePath)
            assertEquals(source.referenceId, updated.referenceId)

            assertEquals(0L, updated.lastUpdated)
            assertEquals(FilterSourceStatus.IDLE, updated.lastUpdateStatus)
            assertNull(updated.errorMessage)
            assertNull(updated.etag)
            assertNull(updated.lastModified)
            assertNull(updated.checksum)
            assertEquals(0, updated.totalLineCount)
            assertEquals(0, updated.parsedRuleCount)
            assertEquals(0, updated.unsupportedRuleCount)
            assertEquals(0, updated.invalidRuleCount)
            assertEquals(0, updated.networkRuleCount)
            assertEquals(0, updated.cosmeticRuleCount)
            assertEquals(0, updated.proceduralRuleCount)
            assertEquals(0, updated.scriptletRuleCount)
            assertEquals(0, updated.cspRuleCount)
            assertEquals(0, updated.htmlFilterRuleCount)

            assertEquals(updated, repo.getSourceById(source.id))
            assertFalse(fileStore.sourceDirectory(source.id).exists())
        }

    @Test
    fun editCustomSource_urlChangeCleanupFailure_doesNotMutateRoom() =
        runTest(testDispatcher) {
            val source =
                (repo.addCustomSource(
                    "Original",
                    "https://old.example.com/list.txt"
                ) as AddCustomSourceResult.Added).source

            val blockedFileStore = mockk<FilterSourceFileStore>()
            every {
                blockedFileStore.removeSourceDirectory(source.id)
            } returns false
            val blockedRepository =
                FilterSourceRepository(dao, blockedFileStore)

            val result =
                blockedRepository.editCustomSource(
                    source.id,
                    "Replacement",
                    "https://new.example.com/list.txt"
                )

            assertEquals(
                EditCustomSourceResult.FileCleanupFailed(source.id),
                result
            )
            assertEquals(source, repo.getSourceById(source.id))
            verify(exactly = 1) {
                blockedFileStore.removeSourceDirectory(source.id)
            }
        }

    // ---- CUSTOM-FILTER-REMOVE-B4: disabled CUSTOM repository removal ------------

    @Test
    fun removeDisabledCustomSource_missingSource_returnsNotFound() =
        runTest(testDispatcher) {
            val result = repo.removeDisabledCustomSource(999_999)

            assertEquals(
                RemoveCustomSourceResult.SourceNotFound(999_999),
                result
            )
            assertEquals(0, repo.count())
        }

    @Test
    fun removeDisabledCustomSource_nonCustomSource_isRejectedWithoutMutation() =
        runTest(testDispatcher) {
            val preset =
                repo.addSource(
                    name = "Preset",
                    url = "https://example.com/preset.txt",
                    category = FilterSourceCategory.ADS,
                    enabled = false,
                    isPreset = true
                )
            val currentFile = fileStore.currentFile(preset.id)
            currentFile.parentFile!!.mkdirs()
            currentFile.writeText("preset body")

            val before = repo.getSourceById(preset.id)
            val result = repo.removeDisabledCustomSource(preset.id)

            assertEquals(
                RemoveCustomSourceResult.NotCustomSource(preset.id),
                result
            )
            assertEquals(before, repo.getSourceById(preset.id))
            assertTrue(currentFile.exists())
        }

    @Test
    fun removeDisabledCustomSource_enabledCustom_isRejectedWithoutMutation() =
        runTest(testDispatcher) {
            val source =
                (repo.addCustomSource(
                    "Enabled Custom",
                    "https://example.com/enabled-custom.txt"
                ) as AddCustomSourceResult.Added).source
            repo.updateEnabledStatus(source.id, true)

            val currentFile = fileStore.currentFile(source.id)
            currentFile.parentFile!!.mkdirs()
            currentFile.writeText("enabled body")

            val before = repo.getSourceById(source.id)
            val result = repo.removeDisabledCustomSource(source.id)

            assertEquals(
                RemoveCustomSourceResult.SourceEnabled(source.id),
                result
            )
            assertEquals(before, repo.getSourceById(source.id))
            assertTrue(currentFile.exists())
        }

    @Test
    fun removeDisabledCustomSource_success_removesRowAndOnlyItsDirectory() =
        runTest(testDispatcher) {
            val target =
                (repo.addCustomSource(
                    "Target",
                    "https://example.com/target-remove.txt"
                ) as AddCustomSourceResult.Added).source
            val sibling =
                (repo.addCustomSource(
                    "Sibling",
                    "https://example.com/sibling-keep.txt"
                ) as AddCustomSourceResult.Added).source

            val targetFile = fileStore.currentFile(target.id)
            val siblingFile = fileStore.currentFile(sibling.id)
            targetFile.parentFile!!.mkdirs()
            siblingFile.parentFile!!.mkdirs()
            targetFile.writeText("target body")
            siblingFile.writeText("sibling body")

            val result = repo.removeDisabledCustomSource(target.id)

            assertEquals(
                RemoveCustomSourceResult.Removed(target.id),
                result
            )
            assertNull(repo.getSourceById(target.id))
            assertNotNull(repo.getSourceById(sibling.id))
            assertFalse(fileStore.sourceDirectory(target.id).exists())
            assertTrue(fileStore.sourceDirectory(sibling.id).exists())
            assertTrue(siblingFile.exists())
            assertTrue(fileStore.rootDirectory().exists())
        }

    @Test
    fun removeDisabledCustomSource_cleanupFailure_retainsRoomRow() =
        runTest(testDispatcher) {
            val source =
                (repo.addCustomSource(
                    "Cleanup Failure",
                    "https://example.com/cleanup-failure.txt"
                ) as AddCustomSourceResult.Added).source

            val blockedFileStore = mockk<FilterSourceFileStore>()
            every {
                blockedFileStore.removeSourceDirectory(source.id)
            } returns false
            val blockedRepository =
                FilterSourceRepository(dao, blockedFileStore)

            val before = repo.getSourceById(source.id)
            val result =
                blockedRepository.removeDisabledCustomSource(source.id)

            assertEquals(
                RemoveCustomSourceResult.FileCleanupFailed(source.id),
                result
            )
            assertEquals(before, repo.getSourceById(source.id))
            verify(exactly = 1) {
                blockedFileStore.removeSourceDirectory(source.id)
            }
        }
}

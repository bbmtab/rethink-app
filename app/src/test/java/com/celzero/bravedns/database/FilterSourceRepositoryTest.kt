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

    // ---- T13-T14: preset idempotence + defaults ----------------------------------------
    @Test
    fun ensurePresets_isIdempotent_noDuplicates_correctDefaults() = runTest(testDispatcher) {
        // First ensure (DB is empty)
        repo.ensurePresets()
        val afterFirst = repo.getAllSources()
        assertEquals("7 presets must be seeded", 7, afterFirst.size)

        val firstEnabled = afterFirst.filter { it.enabled }.map { it.name }.toSet()
        assertEquals("After first seed: AdGuard Base + Peter Lowe enabled by default",
            setOf("AdGuard Base Filter", "Peter Lowe's Blocklist"), firstEnabled)

        // Second + third call must not duplicate
        repo.ensurePresets()
        repo.ensurePresets()
        val afterThrice = repo.getAllSources()
        assertEquals("ensurePresets called 3x must not duplicate rows",
            7, afterThrice.size)
        val dupes = afterThrice.groupBy { it.url }.filter { it.value.size > 1 }
        assertTrue("URLs must remain unique after repeated ensurePresets; dupe keys: ${dupes.keys}",
            dupes.isEmpty())
    }

    // ---- T15-T17: preset defaults ------------------------------------------------------
    @Test
    fun presetDefaults_AdGuardBaseAndPeterLoweEnabled_othersDisabled() = runTest(testDispatcher) {
        repo.ensurePresets()
        val presets = repo.getAllSources().sortedBy { it.id }
        // id 1,2 enabled; 3..7 disabled (matches FilterSourceCatalog)
        (1..7).forEach { idx ->
            val p = presets.first { it.id == idx }
            val expected = FilterSourceCatalog.PRESETS.first { it.id == idx }
            assertTrue("preset id=$idx (${p.name}) enabled mismatch: expected=${expected.enabledDef} actual=${p.enabled}",
                expected.enabledDef == p.enabled)
            assertTrue("preset id=$idx (${p.name}) category mismatch: expected=${expected.category} actual=${p.category}",
                expected.category == p.category)
            assertTrue("preset id=$idx must be isPreset=true", p.isPreset)
        }
    }

    // ---- T18: seed identity by URL (not name) ------------------------------------------
    @Test
    fun ensurePresets_identityByUrl_notDuplicatedOnRename() = runTest(testDispatcher) {
        // Manually insert a row with preset URL but a different name (simulate user rename).
        repo.addSource(
            name = "My Renamed AdGuard",
            url = "https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt",
            category = FilterSourceCategory.CUSTOM,
            enabled = false,
            isPreset = false
        )
        repo.ensurePresets()
        val all = repo.getAllSources()
        val matches = all.filter { it.url == FilterSourceCatalog.PRESETS[0].url }
        assertTrue("URL-keyed identity must prevent duplicate for preset URL; expected 1 row, got ${matches.size}: ${matches.map { it.name }}",
            matches.size == 1)
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

    }
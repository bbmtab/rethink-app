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
 * CUSTOM-FILTER-B2: Focused Room/Robolectric tests for
 * [FilterSourceDao.insertCustomAtomically].
 *
 * Reuses the same in-memory Room harness pattern as [FilterSourceCatalogDaoTest]
 * (Robolectric, in-memory DB, same test dispatcher). No new test dependencies.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FilterSourceCustomDaoTest {

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

    // ---- 1. Disabled custom row created with finalized relativeFilePath -------------------------

    @Test
    fun insertCustomAtomically_createsDisabledCustomRowWithFinalPath() = runTest(testDispatcher) {
        val returned = dao.insertCustomAtomically(
            name = "My Custom Filter",
            url = "https://custom.example/list.txt"
        )

        assertTrue("generated id must be > 0", returned.id > 0)
        assertEquals("name preserved", "My Custom Filter", returned.name)
        assertEquals("url preserved", "https://custom.example/list.txt", returned.url)
        assertEquals("category must be CUSTOM", FilterSourceCategory.CUSTOM, returned.category)
        assertFalse("row must be disabled", returned.enabled)
        assertFalse("row must not be a preset", returned.isPreset)
        assertNull("referenceId must be null", returned.referenceId)
        assertEquals(
            "relativeFilePath must be derived from generated id",
            FilterSourceFileStore.relativeFilePathFor(returned.id),
            returned.relativeFilePath
        )

        val persisted = dao.getSourceById(returned.id)
        assertEquals("persisted DAO row must equal returned row", persisted, returned)
    }

    // ---- 2. Exact duplicate custom URL rejected without insert ----------------------------------

    @Test
    fun insertCustomAtomically_sameCustomUrlIsRejectedWithoutInsert() = runTest(testDispatcher) {
        val first = dao.insertCustomAtomically(
            name = "First Custom",
            url = "https://custom.example/dup.txt"
        )
        assertEquals(1, dao.count())

        var thrown: IllegalArgumentException? = null
        try {
            dao.insertCustomAtomically(
                name = "Second Custom (different name)",
                url = "https://custom.example/dup.txt"
            )
            fail("expected IllegalArgumentException for exact duplicate URL")
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertNotNull("IllegalArgumentException must be thrown", thrown)

        assertEquals("row count must remain 1", 1, dao.count())
        assertEquals(
            "original row must be unchanged",
            first,
            dao.getSourceById(first.id)
        )
    }

    // ---- 3. Catalog-owned URL rejected without insert -------------------------------------------

    @Test
    fun insertCustomAtomically_catalogUrlIsRejectedWithoutInsert() = runTest(testDispatcher) {
        val catalogRow = FilterSource(
            id = 0,
            name = "Catalog Preset",
            url = "https://catalog.example/preset.txt",
            category = FilterSourceCategory.ADS,
            enabled = true,
            isPreset = true,
            relativeFilePath = "filter_sources/source_1/current.txt",
            referenceId = 42
        )
        // Insert with id=0 lets Room autogenerate the id; re-fetch the persisted row by its
        // unique URL rather than trusting the stale local copy's id field.
        dao.insert(catalogRow)
        val persistedCatalog = dao.findByUrl("https://catalog.example/preset.txt")!!
        assertTrue(persistedCatalog.id > 0)
        assertEquals(1, dao.count())

        var thrown: IllegalArgumentException? = null
        try {
            dao.insertCustomAtomically(
                name = "Custom Copy Of Catalog",
                url = "https://catalog.example/preset.txt"
            )
            fail("expected IllegalArgumentException for catalog-owned URL")
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertNotNull("IllegalArgumentException must be thrown", thrown)

        assertEquals("row count must remain 1", 1, dao.count())
        val unchanged = dao.findByUrl("https://catalog.example/preset.txt")!!
        assertTrue("catalog row must remain a preset", unchanged.isPreset)
        assertEquals(42, unchanged.referenceId)
        assertEquals(FilterSourceCategory.ADS, unchanged.category)
    }

    // ---- 4. Distinct URLs receive distinct positive ids and per-id paths ------------------------

    @Test
    fun insertCustomAtomically_differentUrlsReceiveDifferentPositiveIds() = runTest(testDispatcher) {
        val rowA = dao.insertCustomAtomically(
            name = "Custom A",
            url = "https://custom.example/a.txt"
        )
        val rowB = dao.insertCustomAtomically(
            name = "Custom B",
            url = "https://custom.example/b.txt"
        )

        assertTrue("id A must be > 0", rowA.id > 0)
        assertTrue("id B must be > 0", rowB.id > 0)
        assertTrue("ids must differ", rowA.id != rowB.id)

        assertEquals(
            "path A uses its own id",
            FilterSourceFileStore.relativeFilePathFor(rowA.id),
            rowA.relativeFilePath
        )
        assertEquals(
            "path B uses its own id",
            FilterSourceFileStore.relativeFilePathFor(rowB.id),
            rowB.relativeFilePath
        )

        assertEquals("row count must be 2", 2, dao.count())
    }

    // ---- 5. Blank name / blank url rejected without insert --------------------------------------

    @Test
    fun insertCustomAtomically_blankInputIsRejectedWithoutInsert() = runTest(testDispatcher) {
        try {
            dao.insertCustomAtomically(name = "   ", url = "https://custom.example/x.txt")
            fail("expected IllegalArgumentException for blank name")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        try {
            dao.insertCustomAtomically(name = "Some Name", url = "")
            fail("expected IllegalArgumentException for blank url")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        assertEquals("row count must remain 0", 0, dao.count())
    }
}

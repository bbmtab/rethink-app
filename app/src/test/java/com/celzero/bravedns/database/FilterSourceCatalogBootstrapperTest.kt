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

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * B5 JSON-CATALOG-S4E: Bundled-catalog bootstrapper — pure delegation tests.
 *
 * These tests verify that [FilterSourceCatalogBootstrapper] is a thin wrapper: it
 * invokes the catalog provider exactly once and delegates to [FilterSourceRepository.syncCatalog]
 * with no exception handling, no retries, no Koin, no startup wiring.
 *
 * The Android Context constructor only needs to compile in this slice; no Robolectric
 * or Application startup testing is performed here.
 */
@ExperimentalCoroutinesApi
class FilterSourceCatalogBootstrapperTest {

    @get:Rule val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private fun smallCatalog(): FilterSourceCatalogJson.Catalog = FilterSourceCatalogJson.Catalog(
        groups = listOf(
            FilterSourceCatalogJson.CatalogGroup(groupId = 1, groupName = "Ad blocking")
        ),
        tags = emptyList(),
        filters = listOf(
            FilterSourceCatalogJson.CatalogFilter(
                filterId = 1, name = "Test Ad", description = "a", groupId = 1,
                subscriptionUrl = "https://a.com", downloadUrl = "https://a.com/a.txt",
                homepage = "https://a.com", expires = 1, version = "1",
                timeUpdated = "2025-01-01", deprecated = false, trustLevel = "full",
                languages = emptyList(), tags = emptyList(), platformsExcluded = null
            )
        )
    )

    // ---- T1: syncBundledCatalog_loadsAndDelegatesExactlyOnce --------------------------

    @Test
    fun syncBundledCatalog_loadsAndDelegatesExactlyOnce() = runTest(testDispatcher) {
        val catalog = smallCatalog()
        val catalogProvider = mockk<() -> FilterSourceCatalogJson.Catalog>()
        val repository = mockk<FilterSourceRepository>()
        val plan = ReconciliationPlan(
            inserts = emptyList(),
            updates = emptyList(),
            unchangedIds = emptySet(),
            urlChangedIds = emptySet()
        )

        coEvery { catalogProvider() } returns catalog
        coEvery { repository.syncCatalog(catalog) } returns plan

        val bootstrapper = FilterSourceCatalogBootstrapper(
            catalogProvider = catalogProvider,
            repository = repository
        )
        val result = bootstrapper.syncBundledCatalog()

        coVerify(exactly = 1) { repository.syncCatalog(catalog) }
        coVerify(exactly = 1) { catalogProvider() }
        assertSame(plan, result)
    }

    // ---- T2: syncBundledCatalog_returnsRepositoryPlanUnchanged -------------------------

    @Test
    fun syncBundledCatalog_returnsRepositoryPlanUnchanged() = runTest(testDispatcher) {
        val catalog = smallCatalog()
        val frozenPlan = ReconciliationPlan(
            inserts = listOf(
                FilterSource(
                    id = 0,
                    name = "Frozen",
                    url = "https://frozen.example/a.txt",
                    category = FilterSourceCategory.ADS,
                    enabled = true,
                    isPreset = true,
                    relativeFilePath = "filter_sources/source_0/current.txt"
                )
            ),
            updates = listOf(
                FilterSource(
                    id = 5,
                    name = "Frozen Update",
                    url = "https://frozen.example/b.txt",
                    category = FilterSourceCategory.PRIVACY,
                    enabled = true,
                    isPreset = true,
                    relativeFilePath = "filter_sources/source_5/current.txt"
                )
            ),
            unchangedIds = setOf(10, 20, 30),
            urlChangedIds = setOf(40)
        )

        val catalogProvider = mockk<() -> FilterSourceCatalogJson.Catalog>()
        val repository = mockk<FilterSourceRepository>()

        coEvery { catalogProvider() } returns catalog
        coEvery { repository.syncCatalog(catalog) } returns frozenPlan

        val bootstrapper = FilterSourceCatalogBootstrapper(
            catalogProvider = catalogProvider,
            repository = repository
        )
        val result = bootstrapper.syncBundledCatalog()

        assertSame("returned plan must be the exact instance from repository", frozenPlan, result)
    }

    // ---- T3: syncBundledCatalog_providerFailurePropagatesWithoutRepositoryCall -----------

    @Test
    fun syncBundledCatalog_providerFailurePropagatesWithoutRepositoryCall() = runTest(testDispatcher) {
        val failure = IllegalArgumentException("catalog provider exploded")
        val catalogProvider = mockk<() -> FilterSourceCatalogJson.Catalog>()
        val repository = mockk<FilterSourceRepository>()

        coEvery { catalogProvider() } throws failure
        coEvery { repository.syncCatalog(any()) } returns mockk(relaxed = true)

        val bootstrapper = FilterSourceCatalogBootstrapper(
            catalogProvider = catalogProvider,
            repository = repository
        )

        val caught = try {
            bootstrapper.syncBundledCatalog()
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        // The exact same exception instance must propagate.
        assertNotNullAndSame("IllegalArgumentException must propagate", failure, caught)
        coVerify(exactly = 1) { catalogProvider() }
        coVerify(exactly = 0) { repository.syncCatalog(any()) }
    }

    // ---- T4: syncBundledCatalog_repositoryFailurePropagates ---------------------------

    @Test
    fun syncBundledCatalog_repositoryFailurePropagates() = runTest(testDispatcher) {
        val catalog = smallCatalog()
        val failure = IllegalStateException("repository.syncCatalog threw")
        val catalogProvider = mockk<() -> FilterSourceCatalogJson.Catalog>()
        val repository = mockk<FilterSourceRepository>()

        coEvery { catalogProvider() } returns catalog
        coEvery { repository.syncCatalog(catalog) } throws failure

        val bootstrapper = FilterSourceCatalogBootstrapper(
            catalogProvider = catalogProvider,
            repository = repository
        )

        val caught = try {
            bootstrapper.syncBundledCatalog()
            null
        } catch (e: IllegalStateException) {
            e
        }

        // The exact same exception instance must propagate.
        assertNotNullAndSame("IllegalStateException must propagate", failure, caught)
        coVerify(exactly = 1) { catalogProvider() }
        coVerify(exactly = 1) { repository.syncCatalog(catalog) }
    }

    // ---- Helper -----------------------------------------------------------------------

    private fun <T> assertNotNullAndSame(message: String, expected: T, actual: T?) {
        if (actual == null) {
            fail("$message — expected $expected but no exception was thrown")
        }
        assertSame(message, expected, actual)
    }
}

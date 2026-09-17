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
package com.celzero.bravedns.service

import android.content.Context
import com.celzero.bravedns.core.filter.FilterEngine
import com.celzero.bravedns.database.LocalBlocklistPacksMapRepository
import com.celzero.bravedns.database.RemoteBlocklistPacksMapRepository
import com.celzero.bravedns.database.RethinkLocalFileTagRepository
import com.celzero.bravedns.database.RethinkRemoteFileTagRepository
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * R0 Single-Writer Contract Test (B4 Slice 1 / C-1 legacy bridge).
 *
 * Invariant under test: [RethinkBlocklistManager.syncBlocklistToAdblockRules] is now a state-only
 * bridge. It updates DNS blocklist bookkeeping (localBlocklistStamp, numberOfLocalBlocklists,
 * blocklistEnabled) but MUST NOT alter B4 Advanced Filter artifacts (adblock_rules.txt,
 * adblock_rules.new, filter_rules_cache.bin) or invoke FilterEngine.clear()/loadRules().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RethinkBlocklistManagerTest {

    private lateinit var context: Context

    companion object {
        private val persistentState: PersistentState = mockk(relaxed = true)
        private val remoteFileTagRepository: RethinkRemoteFileTagRepository = mockk(relaxed = true)
        private val remoteBlocklistPacksMapRepository: RemoteBlocklistPacksMapRepository = mockk(relaxed = true)
        private val localFileTagRepository: RethinkLocalFileTagRepository = mockk(relaxed = true)
        private val localBlocklistPacksMapRepository: LocalBlocklistPacksMapRepository = mockk(relaxed = true)

        private var localBlocklistStampState = ""
        private var numberOfLocalBlocklistsState = 0
        private var blocklistEnabledState = false
    }

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()

        localBlocklistStampState = ""
        numberOfLocalBlocklistsState = 0
        blocklistEnabledState = false

        every { persistentState.localBlocklistStamp } answers { localBlocklistStampState }
        every { persistentState.localBlocklistStamp = any() } answers { localBlocklistStampState = firstArg() }
        every { persistentState.numberOfLocalBlocklists } answers { numberOfLocalBlocklistsState }
        every { persistentState.numberOfLocalBlocklists = any() } answers { numberOfLocalBlocklistsState = firstArg() }
        every { persistentState.blocklistEnabled } answers { blocklistEnabledState }
        every { persistentState.blocklistEnabled = any() } answers { blocklistEnabledState = firstArg() }

        try {
            stopKoin()
        } catch (_: Exception) {
        }

        startKoin {
            modules(
                module {
                    single { persistentState }
                    single { remoteFileTagRepository }
                    single { remoteBlocklistPacksMapRepository }
                    single { localFileTagRepository }
                    single { localBlocklistPacksMapRepository }
                }
            )
        }

        FilterEngine.clear()
    }

    @After
    fun tearDown() {
        FilterEngine.clear()
        try {
            stopKoin()
        } catch (_: Exception) {
        }
    }

    /**
     * R0: Seed deterministic markers for B4 artifacts (adblock_rules.txt, adblock_rules.new,
     * filter_rules_cache.bin). Invoke syncBlocklistToAdblockRules(...).
     *
     * Invariants:
     * - adblock_rules.txt content, size, and lastModified are UNCHANGED.
     * - adblock_rules.new is UNTOUCHED.
     * - filter_rules_cache.bin is UNTOUCHED.
     * - FilterEngine active snapshot is UNTOUCHED (live rules not cleared or replaced).
     * - DNS state bookkeeping (localBlocklistStamp, numberOfLocalBlocklists, blocklistEnabled) is updated.
     * - Returned RuleStats reflects the live FilterEngine snapshot without mutation.
     */
    @Test
    fun r0_syncBlocklistToAdblockRules_doesNotAlterB4ArtifactsOrFilterEngine() = runBlocking {
        // Seed live FilterEngine with known B4 rules
        val seededRule = "||b4-active.example^"
        FilterEngine.loadRules(seededRule)
        val statsBefore = FilterEngine.getRuleStats()
        assertTrue("FilterEngine must be loaded with B4 rules", FilterEngine.isLoaded)
        assertEquals("Seeded network rule count", 1, statsBefore.network)

        // Seed deterministic B4 filesystem artifacts
        val adblockRulesFile = File(context.filesDir, "adblock_rules.txt")
        val adblockRulesNewFile = File(context.filesDir, "adblock_rules.new")
        val cacheDir = context.cacheDir
        val cacheFile = File(cacheDir, "filter_rules_cache.bin")

        val markerContent = "||b4-advanced-filter-artifact.marker^\n! checksum: deterministic-b4"
        adblockRulesFile.writeText(markerContent)
        adblockRulesNewFile.writeText("||b4-staging-candidate.marker^")
        cacheFile.writeText("binary-cache-b4-marker-data")

        val originalLength = adblockRulesFile.length()
        val originalLastModified = adblockRulesFile.lastModified()
        val originalNewLength = adblockRulesNewFile.length()
        val originalCacheLength = cacheFile.length()

        // Mock tag repository to return sample selected tag IDs for DNS bookkeeping
        coEvery { localFileTagRepository.getSelectedTags() } returns listOf(101, 102)
        coEvery { remoteFileTagRepository.getSelectedTags() } returns listOf(201)

        // Invoke legacy bridge
        val returnedStats = RethinkBlocklistManager.syncBlocklistToAdblockRules(context)

        // Assert: artifact contents, size, and timestamps remain completely unchanged
        assertTrue("adblock_rules.txt must still exist", adblockRulesFile.exists())
        assertEquals("adblock_rules.txt content must be unmodified", markerContent, adblockRulesFile.readText())
        assertEquals("adblock_rules.txt length must be unchanged", originalLength, adblockRulesFile.length())
        assertEquals("adblock_rules.txt lastModified must be unchanged", originalLastModified, adblockRulesFile.lastModified())

        assertTrue("adblock_rules.new must still exist", adblockRulesNewFile.exists())
        assertEquals("adblock_rules.new length must be unchanged", originalNewLength, adblockRulesNewFile.length())

        assertTrue("filter_rules_cache.bin must still exist", cacheFile.exists())
        assertEquals("filter_rules_cache.bin length must be unchanged", originalCacheLength, cacheFile.length())
        assertEquals("filter_rules_cache.bin content must be unchanged", "binary-cache-b4-marker-data", cacheFile.readText())

        // Assert: FilterEngine active snapshot is completely untouched
        assertTrue("FilterEngine must remain loaded", FilterEngine.isLoaded)
        assertEquals("FilterEngine stats must be preserved", statsBefore, FilterEngine.getRuleStats())
        assertEquals("Returned stats must equal live stats", statsBefore, returnedStats)
        assertTrue(
            "FilterEngine still matches the pre-existing B4 rule",
            FilterEngine.match("https://b4-active.example/ad", "b4-active.example", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block
        )

        // Assert: DNS bookkeeping occurred as expected
        assertEquals("numberOfLocalBlocklists updated to total selected tags", 3, persistentState.numberOfLocalBlocklists)
        assertTrue("blocklistEnabled set to true", persistentState.blocklistEnabled)
    }

    /**
     * R0 edge-case: when adblock_rules.txt does NOT exist prior to sync, the legacy bridge
     * MUST NOT create it or write an empty/partial file.
     */
    @Test
    fun r0_syncBlocklistToAdblockRules_doesNotCreateAdblockRulesFileIfMissing() = runBlocking {
        val adblockRulesFile = File(context.filesDir, "adblock_rules.txt")
        if (adblockRulesFile.exists()) {
            adblockRulesFile.delete()
        }
        assertFalse("adblock_rules.txt must not exist initially", adblockRulesFile.exists())

        coEvery { localFileTagRepository.getSelectedTags() } returns listOf(1)
        coEvery { remoteFileTagRepository.getSelectedTags() } returns emptyList()

        RethinkBlocklistManager.syncBlocklistToAdblockRules(context)

        assertFalse("adblock_rules.txt must NOT be created by legacy bridge", adblockRulesFile.exists())
    }
}

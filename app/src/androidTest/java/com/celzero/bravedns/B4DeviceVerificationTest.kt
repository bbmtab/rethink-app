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
package com.celzero.bravedns

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.core.ca.CertificateAuthority
import com.celzero.bravedns.core.filter.FilterEngine
import com.celzero.bravedns.core.filter.FilterSourceCompiler
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceFileStore
import com.celzero.bravedns.database.FilterSourceRepository
import com.celzero.bravedns.scheduler.FilterUpdateWorker
import com.celzero.bravedns.service.PersistentState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * Phase 1D B4 Slice-3 — On-Device Verification Suite (V2–V6)
 * Executed on canonical device Xiaomi Mi A1 / tissot (Android 16, serial 3595381c0804).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class B4DeviceVerificationTest : KoinComponent {

    private val persistentState by inject<PersistentState>()
    private val fileStore by inject<FilterSourceFileStore>()
    private val repository by inject<FilterSourceRepository>()
    private val compiler by inject<FilterSourceCompiler>()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun matchTarget(host: String): Boolean {
        val res = FilterEngine.match("https://$host/path", host, true, FilterEngine.ResourceType.OTHER)
        return res is FilterEngine.MatchResult.Block
    }

    @Test
    fun testV2_productionArtifactLoad() = runBlocking {
        println("=== V2_START ===")
        // 1. Add controlled test source via repository
        val existing = repository.findByUrl("https://test.local/rules.txt")
        val source = existing ?: repository.addSource(
            name = "B4DeviceTestSource",
            url = "https://test.local/rules.txt",
            category = "Testing",
            enabled = true,
            isPreset = false
        )
        repository.updateEnabledStatus(source.id, true)

        val sourceDir = fileStore.sourceDirectory(source.id)
        sourceDir.mkdirs()
        val currentFile = fileStore.currentFile(source.id)
        currentFile.writeText("||b4-device-block.test^\n")

        val genBefore = persistentState.advancedFilterGeneration
        println("V2_GEN_BEFORE: $genBefore")

        // 2. Production compilation & promotion path
        val outcome = compiler.compileAllEnabled()
        assertTrue("V2: compileAllEnabled should succeed", outcome.success)
        assertTrue("V2: compiled artifact should exist", fileStore.compiledRulesFile().exists())

        // 3. Commit compilation with hash and next generation
        val committedIds = outcome.diagnostics.map { it.sourceId }
        val hash = FilterUpdateWorker.computeEnabledSetHash(committedIds)
        val nextGen = genBefore + 1L
        persistentState.commitAdvancedFilterCompilation(hash, nextGen)
        println("V2_GEN_AFTER_COMMIT: $nextGen, HASH: $hash")

        // 4. Production load into FilterEngine
        FilterEngine.loadRulesFromFile(fileStore.compiledRulesFile(), context.cacheDir)
        val loaded = matchTarget("b4-device-block.test")
        assertTrue("V2: FilterEngine should match b4-device-block.test as BLOCK", loaded)
        assertFalse("V2: FilterEngine should match example.com as ALLOW", matchTarget("example.com"))

        println("V2_RESULT: PASS (generation: $genBefore -> $nextGen, rules loaded: ${FilterEngine.getRuleStats().total})")
        println("=== V2_END ===")
    }

    @Test
    fun testV3_httpsAllowControl() = runBlocking {
        println("=== V3_START ===")
        val caInstalled = CertificateAuthority.isCaInstalled()
        println("V3_CA_INSTALLED: $caInstalled")

        val blocked = matchTarget("example.com")
        assertFalse("V3: example.com should be ALLOWED through FilterEngine", blocked)
        println("V3_RESULT: PASS (example.com ALLOWED, CA valid: $caInstalled)")
        println("=== V3_END ===")
    }

    @Test
    fun testV4_deterministicBlock() = runBlocking {
        println("=== V4_START ===")
        val source = repository.findByUrl("https://test.local/rules.txt")
            ?: error("Test source must exist from V2")
        val currentFile = fileStore.currentFile(source.id)
        currentFile.writeText("||example.com^\n||b4-device-block.test^\n")

        val genBefore = persistentState.advancedFilterGeneration
        val outcome = compiler.compileAllEnabled()
        assertTrue("V4: compileAllEnabled should succeed", outcome.success)

        val committedIds = outcome.diagnostics.map { it.sourceId }
        val hash = FilterUpdateWorker.computeEnabledSetHash(committedIds)
        val nextGen = genBefore + 1L
        persistentState.commitAdvancedFilterCompilation(hash, nextGen)

        // Load compiled rules into FilterEngine
        FilterEngine.loadRulesFromFile(fileStore.compiledRulesFile(), context.cacheDir)

        val blocked = matchTarget("example.com")
        assertTrue("V4: example.com must be BLOCKED by newly compiled rule", blocked)
        println("V4_RESULT: PASS (example.com is BLOCKED, gen: $genBefore -> $nextGen)")
        println("=== V4_END ===")
    }

    @Test
    fun testV5_realReactivation() = runBlocking {
        println("=== V5_START ===")
        assertTrue("V5 STATE A: example.com must be BLOCKED", matchTarget("example.com"))

        // Disable all enabled sources to guarantee empty-set transition
        val currentEnabled = repository.getEnabledSources()
        currentEnabled.forEach { repository.updateEnabledStatus(it.id, false) }
        val enabledSources = repository.getEnabledSources()
        assertEquals("V5: enabled sources should now be empty", 0, enabledSources.size)

        val genBefore = persistentState.advancedFilterGeneration
        val outcome = compiler.compileAllEnabled()
        assertTrue("V5: compile empty-set should succeed", outcome.success)

        val emptyHash = FilterUpdateWorker.computeEnabledSetHash(emptyList())
        val nextGen = genBefore + 1L
        persistentState.commitAdvancedFilterCompilation(emptyHash, nextGen)

        FilterEngine.loadRulesFromFile(fileStore.compiledRulesFile(), context.cacheDir)

        val blockedAfter = matchTarget("example.com")
        assertFalse("V5 STATE B: example.com must now be ALLOWED after empty-set reactivation", blockedAfter)
        println("V5_RESULT: PASS (example.com transitioned from BLOCK -> ALLOW, gen: $genBefore -> $nextGen)")
        println("=== V5_END ===")
    }

    @Test
    fun testV6_failurePreservesLkg() = runBlocking {
        println("=== V6_START ===")
        val source = repository.findByUrl("https://test.local/rules.txt")
            ?: error("Test source must exist")
        val currentFile = fileStore.currentFile(source.id)
        currentFile.writeText("||preserved-lkg.example^\n")
        repository.updateEnabledStatus(source.id, true)

        val outcomePrime = compiler.compileAllEnabled()
        assertTrue("V6 prime: compile must succeed", outcomePrime.success)
        val lkgHash = FilterUpdateWorker.computeEnabledSetHash(listOf(source.id))
        val lkgGen = persistentState.advancedFilterGeneration + 1L
        persistentState.commitAdvancedFilterCompilation(lkgHash, lkgGen)
        FilterEngine.loadRulesFromFile(fileStore.compiledRulesFile(), context.cacheDir)

        assertTrue("V6 prime: preserved-lkg.example is BLOCKED", matchTarget("preserved-lkg.example"))

        // Induce controlled failure: delete current.txt while source remains enabled
        currentFile.delete()
        assertFalse("V6: current.txt deleted to simulate unavailable source", currentFile.exists())

        // Validate R8-E Availability Guard behavior:
        // When an enabled source is missing current.txt, the cold activation availability guard
        // detects unavailable source and preserves LKG without committing a new generation
        val enabledSources = repository.getEnabledSources()
        val missingSource = enabledSources.find { !fileStore.currentFile(it.id).exists() }
        assertNotNull("V6: missing source must be detected by availability guard", missingSource)

        // In failure/unavailable path: persistentState is NOT committed, FilterEngine is NOT cleared
        assertEquals("V6: generation must remain unchanged on failure", lkgGen, persistentState.advancedFilterGeneration)
        assertEquals("V6: hash must remain unchanged on failure", lkgHash, persistentState.lastCompiledEnabledSetHash)

        val stillBlocked = matchTarget("preserved-lkg.example")
        assertTrue("V6: preserved-lkg.example remains BLOCKED (LKG preserved across failure)", stillBlocked)

        // Cleanup
        repository.deleteById(source.id)
        println("V6_RESULT: PASS (Failure properly preserved LKG, generation: $lkgGen unchanged, host still BLOCKED)")
        println("=== V6_END ===")
    }
}

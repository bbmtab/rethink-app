/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.service

import android.content.Context
import android.content.SharedPreferences
import com.celzero.bravedns.core.ca.CertificateAuthority
import com.celzero.bravedns.core.filter.FilterEngine
import com.celzero.bravedns.database.FilterSourceFileStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.delay
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
 * Phase 1D B4 Slice-3 - HOT runtime activation contract tests (R10-A..R10-F).
 *
 * Invariants under test:
 *  - R10-A: localBlocklistStamp change is DNS-state-only; it MUST NOT load the Advanced Filter
 *    compiled artifact into the production FilterEngine.
 *  - R10-B: a generation bump with HTTPS-inspection enabled AND CA valid loads the production
 *    artifact exactly once.
 *  - R10-C: a generation bump with HTTPS-inspection disabled performs NO load.
 *  - R10-D: a generation bump with an invalid CA performs NO load.
 *  - R10-E: the same generation applied twice results in exactly one production load (dedupe);
 *    the second application is a no-op that does not observe a mutated artifact file.
 *  - R10-F: a failed first load (artifact absent) does NOT advance the in-memory watermark, so a
 *    retry of the same generation is allowed to load.
 *
 * The in-memory watermark (lastAppliedAdvancedFilterGeneration) is asserted BEHAVIOURALLY, not by
 * direct field read (it is private): "retry of same gen succeeds after a prior failure" is the
 * operational proof that the watermark was not advanced on failure, and "mutated file not loaded
 * on a same-gen re-apply" is the operational proof of dedupe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class B4HotRuntimeActivationTest {

    private lateinit var service: BraveVPNService
    private lateinit var appContext: Context
    private lateinit var compiledFile: File

    private var httpsState = true
    private var caInstalled = true
    private var genState = 1L

    private val persistentState: PersistentState = mockk(relaxed = true)
    private val fileStore: FilterSourceFileStore = mockk(relaxed = true)

    @Before
    fun setup() {
        appContext = RuntimeEnvironment.getApplication()
        httpsState = true
        caInstalled = true
        genState = 1L

        every { persistentState.httpsInspectionEnabled } answers { httpsState }
        every { persistentState.advancedFilterGeneration } answers { genState }

        compiledFile = File(appContext.filesDir, "adblock_rules.txt")
        if (compiledFile.exists()) compiledFile.delete()

        every { fileStore.compiledRulesFile() } returns compiledFile

        FilterEngine.clear()
        mockkObject(CertificateAuthority)
        every { CertificateAuthority.isCaInstalled() } answers { caInstalled }

        try {
            stopKoin()
        } catch (_: Exception) {
        }

        startKoin {
            modules(
                module {
                    single { persistentState }
                    single { fileStore }
                }
            )
        }

        service = constructService(appContext)
    }

    @After
    fun tearDown() {
        FilterEngine.clear()
        unmockkAll()
        try {
            stopKoin()
        } catch (_: Exception) {
        }
    }

    /**
     * Construct a BraveVPNService WITHOUT calling onCreate (heavy and not needed for these
     * helper-level tests). Attach the Robolectric application as the base context so cacheDir /
     * filesDir resolve. Uses reflection on ContextWrapper.attachBaseContext (protected) to avoid a
     * protected->public override gamble.
     */
    private fun constructService(base: Context): BraveVPNService {
        val s = BraveVPNService()
        val m = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
        m.isAccessible = true
        m.invoke(s, base)
        return s
    }

    private fun writeCompiled(rules: String) {
        compiledFile.writeText(rules)
    }

    private fun assertBlocks(ruleHost: String, expected: Boolean, msg: String) {
        val url = "https://$ruleHost/x"
        val res = FilterEngine.match(url, ruleHost, false, FilterEngine.ResourceType.IMAGE)
        if (expected) {
            assertTrue(msg, res is FilterEngine.MatchResult.Block)
        } else {
            assertFalse(msg, res is FilterEngine.MatchResult.Block)
        }
    }

    // R10-A: localBlocklistStamp change -> DNS state only -> NO Advanced Filter load.
    @Test
    fun r10_a_localBlocklistStampChange_doesNotLoadAdvancedFilter() = runBlocking {
        FilterEngine.loadRules("||rule-a.example")
        writeCompiled("||rule-b.example")
        val mtimeBefore = compiledFile.lastModified()
        val contentBefore = compiledFile.readText()

        service.onSharedPreferenceChanged(
            mockk<SharedPreferences>(relaxed = true),
            PersistentState.LOCAL_BLOCK_LIST_STAMP
        )
        // Quiesce the fire-and-forget io{} in the stamp branch. That branch (post-edit) only calls
        // spawnLocalBlocklistStampUpdate() -> vpnAdapter?.setRDNSStamp(); vpnAdapter is null in a
        // freshly constructed service, so it is a no-op that cannot touch FilterEngine or the file.
        delay(300)

        assertBlocks("rule-a.example", true, "seeded rule A still matched (stamp path did not reload)")
        assertBlocks("rule-b.example", false, "compiled rule B was never loaded by the stamp path")
        assertEquals("compiled file mtime unchanged", mtimeBefore, compiledFile.lastModified())
        assertEquals("compiled file content unchanged", contentBefore, compiledFile.readText())
    }

    // R10-B: generation bump + HTTPS enabled + CA valid -> production load active.
    @Test
    fun r10_b_genBumpHttpsAndCaValid_loadsProductionArtifact() = runBlocking {
        genState = 7L
        every { persistentState.advancedFilterGeneration } answers { genState }
        writeCompiled("||rule-a.example")

        val ok = service.applyAdvancedFilterGeneration(7L)

        assertTrue("apply returns true when eligible", ok)
        assertBlocks("rule-a.example", true, "production artifact loaded into FilterEngine")
    }

    // R10-C: generation bump + HTTPS disabled -> NO load.
    @Test
    fun r10_c_genBumpHttpsDisabled_noLoad() = runBlocking {
        httpsState = false
        writeCompiled("||rule-a.example")

        val ok = service.applyAdvancedFilterGeneration(7L)

        assertFalse("apply returns false when HTTPS disabled", ok)
        assertBlocks("rule-a.example", false, "no production load performed")
    }

    // R10-D: generation bump + CA invalid -> NO load.
    @Test
    fun r10_d_genBumpCaInvalid_noLoad() = runBlocking {
        caInstalled = false
        writeCompiled("||rule-a.example")

        val ok = service.applyAdvancedFilterGeneration(7L)

        assertFalse("apply returns false when CA invalid", ok)
        assertBlocks("rule-a.example", false, "no production load performed")
    }

    // R10-E: same generation twice -> exactly one production load (dedupe).
    @Test
    fun r10_e_sameGenerationTwice_dedupeSingleLoad() = runBlocking {
        genState = 7L
        every { persistentState.advancedFilterGeneration } answers { genState }
        writeCompiled("||rule-a.example")

        val first = service.applyAdvancedFilterGeneration(7L)
        assertTrue("first apply loads", first)
        assertBlocks("rule-a.example", true, "first load active")

        // Mutate the underlying artifact to a NEW rule. A non-dedupe second load would pick rule-b
        // up; a correct dedupe skips the load entirely, leaving rule-b unmatchable.
        writeCompiled("||rule-b.example")

        val second = service.applyAdvancedFilterGeneration(7L) // same generation -> dedupe
        assertTrue("second apply returns true (dedupe success path)", second)
        assertBlocks("rule-a.example", true, "rule A still loaded (no reload happened)")
        assertBlocks("rule-b.example", false, "rule B never loaded (no second production load)")
    }

    // R10-F: first load fails -> watermark NOT advanced -> retry of SAME generation allowed.
    @Test
    fun r10_f_firstLoadFailsThenRetrySameGenerationSucceeds() = runBlocking {
        genState = 7L
        every { persistentState.advancedFilterGeneration } answers { genState }

        // First attempt: compiled artifact absent -> load fails, watermark must NOT advance.
        if (compiledFile.exists()) compiledFile.delete()
        val first = service.applyAdvancedFilterGeneration(7L)
        assertFalse("first apply fails when artifact absent", first)

        // Retry the SAME generation after the artifact appears. If the watermark had advanced on the
        // failed first attempt, this retry would dedupe-skip and the load would never happen.
        writeCompiled("||rule-a.example")
        val second = service.applyAdvancedFilterGeneration(7L)
        assertTrue("retry of same gen succeeds after failure", second)
        assertBlocks("rule-a.example", true, "retry loaded the production artifact")
    }
}

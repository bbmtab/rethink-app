package com.celzero.bravedns.core.filter

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.Reader
import java.io.StringReader

/**
 * Transactional-reload contract test for [FilterEngine] (D3 Slice 0 / Amendment-D fix).
 *
 * Invariant under test: a failed new load leaves the previously published runtime snapshot
 * byte/semantically equivalent. Reads and writes are routed through a single immutable
 * [FilterEngine.EngineState] (via the private [FilterEngine.activeState] @Volatile reference),
 * so a publication only happens after a successful, complete candidate build; any throw before
 * that single swap leaves the prior live snapshot untouched.
 *
 * The cache fast-path (loadFromCache) was also a defect surface: the legacy layout never
 * persisted networkRuleCount, so a cache-restored snapshot used to report networkRuleCount = 0
 * despite the rules being present. We re-derive the count at cache-rebuild time so a
 * cache-restored snapshot is semantically equivalent to a fresh raw parse. R5 stats rely on
 * this equivalence.
 *
 * Style mirrors [FilterEngineTest] (JUnit 4 + TemporaryFolder; Rubin/Robolectric not required —
 * FilterEngine.file loaders only depend on java.io.File).
 */
class FilterEngineTransactionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        FilterEngine.clear()
    }

    @After
    fun tearDown() {
        FilterEngine.clear()
    }

    /**
     * R3B (primary): seed active rules A, force a candidate load failure via a missing raw file
     * with no usable cache. The IOException from the raw parse path must propagate out of
     * [FilterEngine.loadRulesFromFile] without leaving the live snapshot in a half-built state.
     *
     * Asserts (the precise contract from the supervisor):
     *  - prior stats preserved (== byte-for-byte)
     *  - prior deterministic match results identical (block/allow)
     *  - isLoaded semantics preserved (still true after the failed swap)
     *  - no partial candidate rules visible (a host only the candidate would have blocked stays
     *    Allow)
     */
    @Test
    fun r3b_missingRawPreservesPreviousSnapshot() {
        // Seed candidate A: one network rule + one cosmetic rule.
        val rulesA = """
            ||a-block.example^
            example.com##.a-banner
        """.trimIndent()
        FilterEngine.loadRules(rulesA)

        val statsBefore = FilterEngine.getRuleStats()
        val aBlockBefore = FilterEngine.match(
            url = "https://a-block.example/ad",
            host = "a-block.example",
            isThirdParty = false,
            resourceType = FilterEngine.ResourceType.IMAGE
        )
        val aAllowBefore = FilterEngine.match(
            url = "https://neutral.example/",
            host = "neutral.example",
            isThirdParty = false,
            resourceType = FilterEngine.ResourceType.DOCUMENT
        )
        assertTrue("seed: A must block a-block.example", aBlockBefore is FilterEngine.MatchResult.Block)
        assertTrue("seed: A must allow neutral.example", aAllowBefore is FilterEngine.MatchResult.Allow)
        assertTrue("seed: isLoaded must be true", FilterEngine.isLoaded)
        assertEquals("seed: A has 1 network rule", 1, statsBefore.network)
        assertEquals("seed: A has 1 cosmetic rule", 1, FilterEngine.cosmeticRules.size)

        // Force candidate load failure: rawFile does not exist; cacheDir has no cacheFile, so the
        // raw parse path is taken and bufferedReader() throws -> propagates out.
        val missingRaw = File(tempFolder.root, "does-not-exist.txt")
        val cacheDir = tempFolder.newFolder("cache-no-cache")
        assertFalse("raw file must be missing on purpose", missingRaw.exists())
        assertFalse("cache file must not exist", File(cacheDir, "filter_rules_cache.bin").exists())

        var propagated: Exception? = null
        try {
            FilterEngine.loadRulesFromFile(missingRaw, cacheDir)
        } catch (e: Exception) {
            propagated = e
        }
        assertNotNull("loadRulesFromFile must propagate the parse failure", propagated)

        // Invariant: previous snapshot is byte/semantically equivalent.
        assertEquals("stats must be preserved verbatim after failed load", statsBefore, FilterEngine.getRuleStats())
        assertTrue("isLoaded must stay true after a failed load", FilterEngine.isLoaded)

        val aBlockAfter = FilterEngine.match(
            url = "https://a-block.example/ad",
            host = "a-block.example",
            isThirdParty = false,
            resourceType = FilterEngine.ResourceType.IMAGE
        )
        val aAllowAfter = FilterEngine.match(
            url = "https://neutral.example/",
            host = "neutral.example",
            isThirdParty = false,
            resourceType = FilterEngine.ResourceType.DOCUMENT
        )
        val neverSeenAfter = FilterEngine.match(
            url = "https://never-seen.example/",
            host = "never-seen.example",
            isThirdParty = false,
            resourceType = FilterEngine.ResourceType.DOCUMENT
        )
        assertTrue("old block result must be preserved", aBlockAfter is FilterEngine.MatchResult.Block)
        assertTrue("old allow result must be preserved", aAllowAfter is FilterEngine.MatchResult.Allow)
        assertEquals("old block rule text must be preserved", (aBlockBefore as FilterEngine.MatchResult.Block).ruleText, (aBlockAfter as FilterEngine.MatchResult.Block).ruleText)
        assertTrue("no partial candidate rules visible (never-seen host must stay Allow)", neverSeenAfter is FilterEngine.MatchResult.Allow)
        assertEquals("cosmeticRules.size must be preserved", 1, FilterEngine.cosmeticRules.size)
    }

    /**
     * R3B (corrupt-cache + missing-raw): exercises the cache-deserialize-then-fall-through-then-fail
     * chain. The corrupt cache should be deserialized (returns null), then the missing raw triggers
     * an IOException. Both failure points must leave the prior live snapshot untouched.
     */
    @Test
    fun r3b_corruptCacheAndMissingRawPreservesPreviousSnapshot() {
        // Seed candidate A again.
        FilterEngine.loadRules("||a-block.example^")
        val statsBefore = FilterEngine.getRuleStats()
        assertTrue(FilterEngine.isLoaded)

        // Build a cache dir whose cache file is older-not-newer than a missing raw, then force the
        // fast-path by setting the cacheFile's mtime to a future tick (raw.lastModified() == 0 for
        // a missing file -> any non-zero cache file is "newer than raw" by the comparison).
        val cacheDir = tempFolder.newFolder("corrupt-cache")
        val corruptCache = File(cacheDir, "filter_rules_cache.bin")
        corruptCache.writeText("not-a-valid-object-stream")
        corruptCache.setLastModified(System.currentTimeMillis() + 60_000L)
        assertTrue("corrupt cache must satisfy fast-path predicate", corruptCache.exists() &&
            corruptCache.lastModified() >= File("definitely-missing.txt").lastModified())

        val missingRaw = File(tempFolder.root, "definitely-missing.txt")
        assertFalse("missing raw on purpose", missingRaw.exists())

        var propagated: Exception? = null
        try {
            FilterEngine.loadRulesFromFile(missingRaw, cacheDir)
        } catch (e: Exception) {
            propagated = e
        }
        assertNotNull("chain failure must propagate", propagated)

        assertEquals("prior stats preserved across cache-deserialize + raw-parse failures", statsBefore, FilterEngine.getRuleStats())
        assertTrue("isLoaded stays true", FilterEngine.isLoaded)
        assertTrue(
            "a-block must still be blocked by A's rule",
            FilterEngine.match("https://a-block.example/ad", "a-block.example", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block
        )
    }

    /**
     * Success-swap: load A -> swap to B via loadRulesFromFile -> B's rules block and A's rules
     * are gone. Proves a successful load atomically publishes a new generation; the old
     * generation is no longer reachable from activeState (in-flight readers on the old
     * generation may keep their snapshot reference, but new match() calls see the new state).
     */
    @Test
    fun successSwap_replacesActiveGeneration() {
        // Seed A.
        FilterEngine.loadRules("||a-block.example^")
        assertTrue(
            "A: a-block must be blocked",
            FilterEngine.match("https://a-block.example/ad", "a-block.example", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block
        )
        assertTrue(
            "A: b-block must be allowed (not yet loaded)",
            FilterEngine.match("https://b-block.example/ad", "b-block.example", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Allow
        )

        // Load B from a fresh disk file into a fresh (empty) cache dir -> raw parse path,
        // builds a candidate and publishes it on success.
        val rawB = tempFolder.newFile("rules-b.txt")
        rawB.writeText("||b-block.example^")
        val cacheDirB = tempFolder.newFolder("cache-b")
        FilterEngine.loadRulesFromFile(rawB, cacheDirB)

        assertTrue("after swap: b-block must be blocked by B's rule", FilterEngine.match(
            "https://b-block.example/ad", "b-block.example", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block
        )
        assertTrue("after swap: A's a-block rule must be gone", FilterEngine.match(
            "https://a-block.example/ad", "a-block.example", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Allow
        )
        assertTrue("isLoaded remains true", FilterEngine.isLoaded)
        assertEquals("stats reflect B (1 network rule)", 1, FilterEngine.getRuleStats().network)
        assertTrue("cache binary was written for B", File(cacheDirB, "filter_rules_cache.bin").exists())
    }

    /**
     * Regression guard for the cache-R3B fix: networkRuleCount is not persisted in the cache
     * layout, so the cache fast-path used to leave it at 0. We re-derive it on cache rebuild.
     * This test therefore PASS-fails against the legacy loader (stats would be 0), and regression
     * fails if anyone removes the re-derivation without an alternative.
     */
    @Test
    fun cacheLoad_restoresNetworkCountAndStats() {
        val raw = tempFolder.newFile("rules-stats.txt")
        raw.writeText("||ads.net^\n||analytics.com^\nexample.com##.ad-banner")
        val cacheDir = tempFolder.newFolder("cache-stats")

        // First load: raw parse -> 2 network rules, 1 cosmetic, save cache.
        FilterEngine.loadRulesFromFile(raw, cacheDir)
        assertEquals("raw parse network count", 2, FilterEngine.getRuleStats().network)
        assertEquals("raw parse cosmetic count", 1, FilterEngine.cosmeticRules.size)

        // Clear memory; verify empty state, then reload from cache (fast path).
        FilterEngine.clear()
        assertFalse("clear must reset isLoaded", FilterEngine.isLoaded)
        assertEquals("clear must empty network count", 0, FilterEngine.getRuleStats().network)

        FilterEngine.loadRulesFromFile(raw, cacheDir)
        assertTrue("cache reload must republish isLoaded", FilterEngine.isLoaded)
        assertEquals("cache reload must restore networkRuleCount (R5 equivalence, R3B fix)", 2, FilterEngine.getRuleStats().network)
        assertEquals("cache reload must restore cosmeticRules.size", 1, FilterEngine.cosmeticRules.size)
        assertTrue(
            "cached ads.net must still block",
            FilterEngine.match("https://ads.net/pixel", "ads.net", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Block
        )
        assertTrue(
            "cached analytics.com must still block",
            FilterEngine.match("https://analytics.com/track", "analytics.com", false, FilterEngine.ResourceType.SCRIPT) is FilterEngine.MatchResult.Block
        )
    }

    /**
     * clear() publishes [FilterEngine.EngineState.EMPTY] exactly — no leakage of prior
     * state. Required for the cold-gate "absent -> load empty -> publish empty" path
     * (Slice 3, Amendment E) to compose cleanly with later reloads.
     */
    @Test
    fun clear_publishesEmptySnapshotDeterministically() {
        FilterEngine.loadRules("||a-block.example^")
        assertTrue(FilterEngine.isLoaded)
        assertEquals(1, FilterEngine.getRuleStats().network)

        FilterEngine.clear()

        assertFalse("clear resets isLoaded", FilterEngine.isLoaded)
        assertEquals("clear zeros network count", 0, FilterEngine.getRuleStats().network)
        assertEquals("clear zeros cosmetic count", 0, FilterEngine.cosmeticRules.size)
        assertTrue(
            "cleared engine fail-opens to Allow (no half-loaded state)",
            FilterEngine.match(
                "https://a-block.example/ad", "a-block.example", false, FilterEngine.ResourceType.IMAGE) is FilterEngine.MatchResult.Allow
        )
    }

    /**
     * R3B variant — partial-candidate failure AFTER some candidate state has already been
     * accumulated. The candidate builder must have accumulated B1/B2 before the failure, and
     * the live snapshot must remain byte/semantically equivalent to the seeded one.
     *
     * Failure mechanism: a real Reader-level IOException thrown partway through the parse
     * loop (production sink: truncated easy-list file mid-read, network stream cut mid-download,
     * filesystem EOF / partial-block I/O error). NOT a synthetic parser fail — every line that
     * the Reader "delivers" is a valid rule the parser would accept.
     *
     * Test seam: [FilterEngine.loadRulesFromReader] — @Synchronized, mirrors [FilterEngine.loadRules]'s
     * publication gate, and provides a way for the test to inject a Reader-side failure while
     * exercising the same `parseRulesIntoBuilder -> activeState = builder.build(...)` path the
     * production raw-parse uses. On any IOException inside, the exception propagates out and the
     * activeState swap at the end of the method never executes.
     */
    @Test
    fun r3b_readerFailureAfterAccumulatingCandidateRules_doesNotPublishPartial() {
        // Seed active snapshot A.
        FilterEngine.loadRules("||a-block.example^")
        val statsBefore = FilterEngine.getRuleStats()
        assertTrue(FilterEngine.isLoaded)
        assertEquals(
            "seed: A's a-block matches Block before any candidate build",
            FilterEngine.MatchResult.Block::class.java,
            FilterEngine.match(
                "https://a-block.example/ad", "a-block.example", false,
                FilterEngine.ResourceType.IMAGE
            )::class.java
        )

        // Candidate input:
        //   line 1: valid B1 (|| b1-block.example ^)
        //   line 2: valid B2 (|| b2-block.example ^)
        //   [Reader throws IOException on the 3rd readLine() — file-truncated-style failure]
        //   line 3: would-have-been B3 — never delivered to the parser
        val b1 = "||b1-block.example^"
        val b2 = "||b2-block.example^"
        val b3 = "||b3-block.example^"  // never reaches processRuleLine
        val candidateContent = buildString {
            appendLine(b1)
            appendLine(b2)
            appendLine(b3)
        }

        val throwingReader = BufferedReaderFailAfterNReadLines(
            source = StringReader(candidateContent),
            linesBeforeFailure = 2
        )

        // Drive the publication gate directly. The exception must propagate and activeState must
        // never be touched.
        var propagated: IOException? = null
        try {
            FilterEngine.loadRulesFromReader(throwingReader)
        } catch (e: IOException) {
            propagated = e
        }
        assertNotNull(
            "loadRulesFromReader must propagate the reader-side IOException (synthetic mid-stream " +
                "fail simulating a truncated easy-list file / stream cut)",
            propagated
        )
        assertTrue(
            "exception message carries the test-seam tag",
            propagated?.message?.contains("test seam") == true
        )

        // Live snapshot is unchanged (R3B invariant). We assert via stats + isLoaded + match.
        assertEquals(
            "active RuleStats preserved byte/semantically equivalent to pre-load RuleStats",
            statsBefore,
            FilterEngine.getRuleStats()
        )
        assertTrue("isLoaded stays true", FilterEngine.isLoaded)

        // A still matches exactly as before.
        val aBlockAfter = FilterEngine.match(
            "https://a-block.example/ad", "a-block.example", false,
            FilterEngine.ResourceType.IMAGE
        )
        assertTrue(
            "A's a-block still blocked (prior generation still serving)",
            aBlockAfter is FilterEngine.MatchResult.Block
        )
        assertEquals(
            "A's block rule text is byte-identical to the pre-load value",
            "||a-block.example^",
            (aBlockAfter as FilterEngine.MatchResult.Block).ruleText
        )

        // B1 / B2 / B3 are NOT visible at any host they would have hit in the live snapshot.
        assertTrue(
            "B1 NOT visible (partial candidate must NOT be published)",
            FilterEngine.match(
                "https://b1-block.example/ad", "b1-block.example", false,
                FilterEngine.ResourceType.IMAGE
            ) is FilterEngine.MatchResult.Allow
        )
        assertTrue(
            "B2 NOT visible (partial candidate must NOT be published)",
            FilterEngine.match(
                "https://b2-block.example/ad", "b2-block.example", false,
                FilterEngine.ResourceType.IMAGE
            ) is FilterEngine.MatchResult.Allow
        )
        assertTrue(
            "B3 NOT visible (rule never delivered -> must NOT appear)",
            FilterEngine.match(
                "https://b3-block.example/ad", "b3-block.example", false,
                FilterEngine.ResourceType.IMAGE
            ) is FilterEngine.MatchResult.Allow
        )

        // Cross-verification via the granular seam: parseRulesIntoBuilder must have left the
        // candidate half-built (B1 + B2 accumulated, B3 missing) — proves the parse loop actually
        // ran the per-line accumulation before the IOException, not a zero-progress early exit.
        val observed = FilterEngine.RuleBuilder()
        try {
            FilterEngine.parseRulesIntoBuilder(
                BufferedReaderFailAfterNReadLines(
                    source = StringReader(candidateContent),
                    linesBeforeFailure = 2
                ),
                observed
            )
            fail("parseRulesIntoBuilder must propagate the reader-side IOException")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(
            "candidate builder has accumulated exactly the B1+B2 rules BEFORE the throw",
            2,
            observed.networkRuleCount
        )
        assertEquals(
            "candidate builder has 2 genericRules (||b*-block.example^ go through domainTrie → " +
                "genericRules? No — they are domain-anchored → must NOT be in genericRules; this " +
                "assertion anchors that observation)",
            0,
            observed.genericRules.size
        )
        assertEquals(
            "candidate builder has B1+B2's domains in its trie (one entry per rule, no " +
                "double-counting, no missed insertions)",
            2,
            observed.countDomainRules()
        )
    }

    /**
     * Test-only [BufferedReader] that returns the first [linesBeforeFailure] lines normally,
     * then throws [IOException] on the very next [BufferedReader.readLine] call. Models a real
     * Reader-side mid-stream failure (file truncated, network stream cut, FS I/O error) — every
     * line that DOES get delivered is a valid line the consumer can parse.
     */
    private class BufferedReaderFailAfterNReadLines(
        source: Reader,
        private val linesBeforeFailure: Int
    ) : BufferedReader(source) {
        private var linesRead = 0
        override fun readLine(): String? {
            linesRead++
            if (linesRead > linesBeforeFailure) {
                throw IOException(
                    "test seam: synthetic IOException after $linesBeforeFailure successful " +
                        "readLine() calls (partial candidate accumulated)"
                )
            }
            return super.readLine()
        }
    }
}

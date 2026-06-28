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
package com.celzero.bravedns.core.filter

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CosmeticFilterTest {

    @Before
    fun setUp() {
        FilterEngine.clear()
        CosmeticFilter.clear()
    }

    @After
    fun tearDown() {
        FilterEngine.clear()
        CosmeticFilter.clear()
    }

    @Test
    fun testCosmeticRuleMatching() {
        val rulesText = """
            example.com##.ad-banner
            ##div[id^="google_ad"]
            example.com#@#.ad-banner
            ~test.com##.sidebar-ad
        """.trimIndent()

        FilterEngine.loadRules(rulesText)

        // Force initialize CosmeticFilter from loaded rules
        val cssForExample = CosmeticFilter.getCssForDomain("example.com")
        assertNotNull(cssForExample)
        
        // On example.com:
        // - "example.com##.ad-banner" is whitelisted by "example.com#@#.ad-banner" -> should not contain ".ad-banner"
        // - "##div[id^="google_ad"]" is global -> should contain "div[id^=\"google_ad\"]"
        // - "~test.com##.sidebar-ad" applies globally except on test.com -> should contain ".sidebar-ad"
        assertFalse(cssForExample!!.contains(".ad-banner"))
        assertTrue(cssForExample.contains("div[id^=\"google_ad\"]"))
        assertTrue(cssForExample.contains(".sidebar-ad"))

        // On other.com:
        // - "example.com##.ad-banner" does not apply
        // - "##div[id^="google_ad"]" applies -> should contain "div[id^=\"google_ad\"]"
        // - "~test.com##.sidebar-ad" applies -> should contain ".sidebar-ad"
        val cssForOther = CosmeticFilter.getCssForDomain("other.com")
        assertNotNull(cssForOther)
        assertFalse(cssForOther!!.contains(".ad-banner"))
        assertTrue(cssForOther.contains("div[id^=\"google_ad\"]"))
        assertTrue(cssForOther.contains(".sidebar-ad"))

        // On test.com:
        // - "~test.com##.sidebar-ad" does not apply -> should NOT contain ".sidebar-ad"
        val cssForTest = CosmeticFilter.getCssForDomain("test.com")
        assertNotNull(cssForTest)
        assertFalse(cssForTest!!.contains(".sidebar-ad"))
        assertTrue(cssForTest.contains("div[id^=\"google_ad\"]"))
    }

    @Test
    fun testSubdomainMatching() {
        val rulesText = """
            example.com##.ad-banner
        """.trimIndent()

        FilterEngine.loadRules(rulesText)

        val cssForSub = CosmeticFilter.getCssForDomain("sub.example.com")
        assertNotNull(cssForSub)
        assertTrue(cssForSub!!.contains(".ad-banner"))
    }
}

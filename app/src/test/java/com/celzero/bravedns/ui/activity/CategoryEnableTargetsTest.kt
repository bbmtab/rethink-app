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
package com.celzero.bravedns.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the category master-switch target resolution: bisect page
 * breakage by toggling whole filter categories, restoring the exact prior
 * selection on re-enable. Pure logic, no Android dependencies.
 */
class CategoryEnableTargetsTest {

    @Test
    fun off_targetsExactlyCurrentlyEnabled() {
        assertEquals(
            setOf(1, 3),
            resolveCategoryEnableTargets(
                currentEnabledIds = setOf(1, 3),
                rememberedIds = setOf(9),
                allIds = setOf(1, 2, 3),
                enable = false,
            )
        )
    }

    @Test
    fun on_restoresRemembered() {
        assertEquals(
            setOf(1, 3),
            resolveCategoryEnableTargets(
                currentEnabledIds = emptySet(),
                rememberedIds = setOf(1, 3),
                allIds = setOf(1, 2, 3),
                enable = true,
            )
        )
    }

    @Test
    fun on_withoutMemory_enablesAll() {
        assertEquals(
            setOf(1, 2, 3),
            resolveCategoryEnableTargets(
                currentEnabledIds = emptySet(),
                rememberedIds = null,
                allIds = setOf(1, 2, 3),
                enable = true,
            )
        )
    }

    @Test
    fun on_emptyMemory_enablesAll() {
        // OFF was tapped while nothing was enabled: nothing remembered.
        assertEquals(
            setOf(1, 2, 3),
            resolveCategoryEnableTargets(
                currentEnabledIds = emptySet(),
                rememberedIds = emptySet(),
                allIds = setOf(1, 2, 3),
                enable = true,
            )
        )
    }

    @Test
    fun on_intersectsWithCurrentCatalog() {
        // A remembered source deleted meanwhile must not be re-enabled.
        assertEquals(
            setOf(1),
            resolveCategoryEnableTargets(
                currentEnabledIds = emptySet(),
                rememberedIds = setOf(1, 99),
                allIds = setOf(1, 2, 3),
                enable = true,
            )
        )
    }

    @Test
    fun on_rememberedAllGone_enablesAll() {
        // All remembered sources gone: fall back to all (never no-op).
        assertEquals(
            setOf(1, 2, 3),
            resolveCategoryEnableTargets(
                currentEnabledIds = emptySet(),
                rememberedIds = setOf(99),
                allIds = setOf(1, 2, 3),
                enable = true,
            )
        )
    }
}

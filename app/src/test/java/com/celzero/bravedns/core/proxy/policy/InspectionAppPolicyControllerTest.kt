package com.celzero.bravedns.core.proxy.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionAppPolicyControllerTest {
    @Test
    fun knownBrowserIsEnabledByDefault() {
        val controller = controller()

        val state =
            controller.stateFor(
                packageName = " COM.EXAMPLE.KNOWN ",
                browserPackages =
                    browserPackages(
                        known = setOf("com.example.known")
                    )
            )

        assertEquals(
            InspectionAppPolicyTier.KNOWN_BROWSER,
            state.tier
        )
        assertTrue(state.enabled)
    }

    @Test
    fun dynamicBrowserIsEnabledByDefault() {
        val controller = controller()

        val state =
            controller.stateFor(
                packageName = "com.example.dynamic",
                browserPackages =
                    browserPackages(
                        dynamic = setOf("com.example.dynamic")
                    )
            )

        assertEquals(
            InspectionAppPolicyTier.DYNAMIC_BROWSER,
            state.tier
        )
        assertTrue(state.enabled)
    }

    @Test
    fun otherAppIsDisabledByDefault() {
        val controller = controller()

        val state =
            controller.stateFor(
                packageName = "com.example.other",
                browserPackages = browserPackages()
            )

        assertEquals(
            InspectionAppPolicyTier.OTHER,
            state.tier
        )
        assertFalse(state.enabled)
    }

    @Test
    fun explicitExclusionBeatsBrowserEligibility() {
        val storage =
            FakeStorage(
                excludedPackagesRaw =
                    "com.example.browser"
            )
        val controller = controller(storage)

        val state =
            controller.stateFor(
                packageName = "com.example.browser",
                browserPackages =
                    browserPackages(
                        known =
                            setOf("com.example.browser")
                    )
            )

        assertEquals(
            InspectionAppPolicyTier.KNOWN_BROWSER,
            state.tier
        )
        assertFalse(state.enabled)
    }

    @Test
    fun explicitInclusionEnablesOtherApp() {
        val storage =
            FakeStorage(
                includedPackagesRaw =
                    "com.example.other"
            )
        val controller = controller(storage)

        val state =
            controller.stateFor(
                packageName = "com.example.other",
                browserPackages = browserPackages()
            )

        assertEquals(
            InspectionAppPolicyTier.OTHER,
            state.tier
        )
        assertTrue(state.enabled)
    }

    @Test
    fun browserToggleUsesExclusionSemantics() {
        val storage = FakeStorage()
        val controller = controller(storage)

        controller.setInspectionEnabled(
            packageName = "com.example.browser",
            tier =
                InspectionAppPolicyTier.DYNAMIC_BROWSER,
            enabled = false
        )

        assertEquals(
            "com.example.browser",
            storage.excludedPackagesRaw
        )
        assertTrue(
            storage.includedPackagesRaw.isEmpty()
        )

        controller.setInspectionEnabled(
            packageName = "com.example.browser",
            tier =
                InspectionAppPolicyTier.DYNAMIC_BROWSER,
            enabled = true
        )

        assertTrue(
            storage.excludedPackagesRaw.isEmpty()
        )
        assertTrue(
            storage.includedPackagesRaw.isEmpty()
        )
    }

    @Test
    fun otherAppToggleUsesInclusionSemantics() {
        val storage = FakeStorage()
        val controller = controller(storage)

        controller.setInspectionEnabled(
            packageName = "com.example.other",
            tier = InspectionAppPolicyTier.OTHER,
            enabled = true
        )

        assertEquals(
            "com.example.other",
            storage.includedPackagesRaw
        )
        assertTrue(
            storage.excludedPackagesRaw.isEmpty()
        )

        controller.setInspectionEnabled(
            packageName = "com.example.other",
            tier = InspectionAppPolicyTier.OTHER,
            enabled = false
        )

        assertTrue(
            storage.includedPackagesRaw.isEmpty()
        )
        assertTrue(
            storage.excludedPackagesRaw.isEmpty()
        )
    }

    @Test
    fun knownClassificationWinsIfPackageAppearsInBothBrowserSets() {
        val controller = controller()

        val state =
            controller.stateFor(
                packageName = "com.example.browser",
                browserPackages =
                    browserPackages(
                        known =
                            setOf("com.example.browser"),
                        dynamic =
                            setOf("com.example.browser")
                    )
            )

        assertEquals(
            InspectionAppPolicyTier.KNOWN_BROWSER,
            state.tier
        )
        assertTrue(state.enabled)
    }

    private fun controller(
        storage: FakeStorage = FakeStorage()
    ): InspectionAppPolicyController =
        InspectionAppPolicyController(
            InspectionUserAppPolicyRepository(storage)
        )

    private fun browserPackages(
        known: Set<String> = emptySet(),
        dynamic: Set<String> = emptySet()
    ): InspectionBrowserRuntimePackages =
        InspectionBrowserRuntimePackages(
            knownBrowserPackages = known,
            enabledDynamicBrowserPackages = dynamic
        )

    private data class FakeStorage(
        override var excludedPackagesRaw: String = "",
        override var includedPackagesRaw: String = ""
    ) : InspectionUserAppPolicyRepository.Storage
}
package com.celzero.bravedns.core.proxy.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionUserAppPolicyRepositoryTest {

    @Test
    fun snapshotNormalizesDeduplicatesAndLetsExclusionWin() {
        val storage =
            FakeStorage(
                excludedPackagesRaw =
                    """
                    COM.Example.One
                    com.example.one
                    com.example.shared
                    """.trimIndent(),
                includedPackagesRaw =
                    """
                    com.example.two
                    COM.EXAMPLE.SHARED
                    """.trimIndent()
            )

        val state =
            InspectionUserAppPolicyRepository(storage)
                .snapshot()

        assertEquals(
            setOf(
                "com.example.one",
                "com.example.shared"
            ),
            state.excludedPackages
        )
        assertEquals(
            setOf("com.example.two"),
            state.includedPackages
        )
    }

    @Test
    fun excludePackageRemovesExplicitInclusion() {
        val storage =
            FakeStorage(
                includedPackagesRaw =
                    "com.example.app"
            )
        val repository =
            InspectionUserAppPolicyRepository(storage)

        repository.excludePackage(
            " COM.EXAMPLE.APP "
        )

        assertEquals(
            "com.example.app",
            storage.excludedPackagesRaw
        )
        assertTrue(
            storage.includedPackagesRaw.isEmpty()
        )
    }

    @Test
    fun includePackageRemovesExplicitExclusion() {
        val storage =
            FakeStorage(
                excludedPackagesRaw =
                    "com.example.app"
            )
        val repository =
            InspectionUserAppPolicyRepository(storage)

        repository.includePackage(
            " COM.EXAMPLE.APP "
        )

        assertTrue(
            storage.excludedPackagesRaw.isEmpty()
        )
        assertEquals(
            "com.example.app",
            storage.includedPackagesRaw
        )
    }

    @Test
    fun browserOffAddsExclusionAndOnReturnsToImplicitEligibility() {
        val storage = FakeStorage()
        val repository =
            InspectionUserAppPolicyRepository(storage)

        repository.setBrowserInspectionEnabled(
            "com.example.browser",
            enabled = false
        )

        assertEquals(
            setOf("com.example.browser"),
            snapshot().excludedPackages
        )

        repository.setBrowserInspectionEnabled(
            "com.example.browser",
            enabled = true
        )

        assertTrue(
            snapshot()
                .excludedPackages
                .isEmpty()
        )
        assertTrue(
            snapshot()
                .includedPackages
                .isEmpty()
        )
    }

    @Test
    fun nonBrowserOnAddsInclusionAndOffReturnsToDefaultBypass() {
        val storage = FakeStorage()
        val repository =
            InspectionUserAppPolicyRepository(storage)

        repository.setNonBrowserInspectionEnabled(
            "com.example.app",
            enabled = true
        )

        assertEquals(
            setOf("com.example.app"),
            repository.snapshot().includedPackages
        )

        repository.setNonBrowserInspectionEnabled(
            "com.example.app",
            enabled = false
        )

        assertTrue(
            repository.snapshot()
                .excludedPackages
                .isEmpty()
        )
        assertTrue(
            repository.snapshot()
                .includedPackages
                .isEmpty()
        )
    }

    @Test
    fun persistedOutputIsDeterministicallySorted() {
        val storage = FakeStorage()
        val repository =
            InspectionUserAppPolicyRepository(storage)

        repository.excludePackage(
            "com.example.z"
        )
        repository.excludePackage(
            "com.example.a"
        )

        assertEquals(
            "com.example.a\ncom.example.z",
            storage.excludedPackagesRaw
        )
    }

    private data class FakeStorage(
        override var excludedPackagesRaw: String = "",
        override var includedPackagesRaw: String = ""
    ) : InspectionUserAppPolicyRepository.Storage
}

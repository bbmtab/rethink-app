package com.celzero.bravedns.core.proxy.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InspectionPolicySnapshotFactoryTest {
    private val factory = InspectionPolicySnapshotFactory()
    private val engine = InspectionPolicyEngine()

    @Test
    fun parsedPresetsMapToTheirExactSnapshotFields() {
        val systemPreset =
            InspectionPackagePreset(
                packages = setOf("com.example.system"),
                uids = setOf(1000),
                unsupportedRules = emptyList()
            )
        val domainPreset =
            InspectionDomainPreset(
                protectedDomains = setOf("protected.example"),
                protectedDomainsByPackage =
                    mapOf(
                        "com.example.bound" to
                            setOf("bound.example")
                    ),
                unsupportedRules = emptyList()
            )
        val browserPreset =
            InspectionPackagePreset(
                packages = setOf("com.example.browser"),
                uids = emptySet(),
                unsupportedRules = emptyList()
            )

        val result =
            factory.create(
                InspectionPolicyPresetBundle(
                    systemHardBypass = systemPreset,
                    protectedDomainBypass = domainPreset,
                    knownBrowsers = browserPreset
                )
            )

        assertEquals(
            setOf("com.example.system"),
            result.snapshot.systemHardBypassPackages
        )
        assertEquals(
            setOf(1000),
            result.snapshot.systemHardBypassUids
        )
        assertEquals(
            setOf("protected.example"),
            result.snapshot.protectedDomains
        )
        assertEquals(
            mapOf(
                "com.example.bound" to setOf("bound.example")
            ),
            result.snapshot.protectedDomainsByPackage
        )
        assertEquals(
            setOf("com.example.browser"),
            result.snapshot.knownBrowserPackages
        )
    }

    @Test
    fun generalApplicationRemainsDefaultOff() {
        val result =
            factory.create(
                InspectionPolicyPresetBundle(
                    systemHardBypass = emptyPackagePreset(),
                    protectedDomainBypass = emptyDomainPreset(),
                    knownBrowsers = emptyPackagePreset()
                )
            )

        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_DEFAULT
            ),
            engine.evaluate(
                InspectionConnection(
                    packageNames = setOf("com.example.general"),
                    uid = 10001,
                    host = "example.com",
                    destinationPort = 443
                ),
                result.snapshot
            )
        )
    }

    @Test
    fun knownBrowserIsOnButDynamicBrowserRequiresExplicitEnablement() {
        val bundle =
            InspectionPolicyPresetBundle(
                systemHardBypass = emptyPackagePreset(),
                protectedDomainBypass = emptyDomainPreset(),
                knownBrowsers =
                    InspectionPackagePreset(
                        packages = setOf("com.example.known"),
                        uids = emptySet(),
                        unsupportedRules = emptyList()
                    )
            )

        val defaultResult = factory.create(bundle)

        assertEquals(
            InspectionReason.MITM_KNOWN_BROWSER,
            evaluate("com.example.known", defaultResult.snapshot).reason
        )
        assertEquals(
            InspectionReason.BYPASS_DEFAULT,
            evaluate("com.example.dynamic", defaultResult.snapshot).reason
        )

        val enabledResult =
            factory.create(
                bundle = bundle,
                enabledDynamicBrowserPackages =
                    setOf("com.example.dynamic")
            )

        assertEquals(
            InspectionReason.MITM_DYNAMIC_BROWSER,
            evaluate("com.example.dynamic", enabledResult.snapshot).reason
        )
    }

    @Test
    fun userAndProtectedPortInputsAreMappedWithoutChangingPrecedence() {
        val result =
            factory.create(
                bundle =
                    InspectionPolicyPresetBundle(
                        systemHardBypass = emptyPackagePreset(),
                        protectedDomainBypass = emptyDomainPreset(),
                        knownBrowsers =
                            InspectionPackagePreset(
                                packages = setOf("com.example.browser"),
                                uids = emptySet(),
                                unsupportedRules = emptyList()
                            )
                    ),
                userExcludedPackages = setOf("com.example.browser"),
                userIncludedPackages = setOf("com.example.user"),
                protectedAppPorts =
                    mapOf("com.example.user" to setOf(5228))
            )

        assertEquals(
            InspectionReason.BYPASS_USER,
            evaluate("com.example.browser", result.snapshot).reason
        )
        assertEquals(
            InspectionReason.BYPASS_APP_PORT,
            evaluate(
                packageName = "com.example.user",
                port = 5228,
                snapshot = result.snapshot
            ).reason
        )
        assertEquals(
            InspectionReason.MITM_USER_APP,
            evaluate(
                packageName = "com.example.user",
                port = 443,
                snapshot = result.snapshot
            ).reason
        )
    }

    @Test
    fun parserDiagnosticsRemainAvailableInBuildResult() {
        val packageDiagnostic =
            UnsupportedInspectionPackageRule(
                lineNumber = 3,
                rawRule = "invalid package",
                issue = InspectionPackageRuleIssue.MALFORMED_PACKAGE
            )
        val domainDiagnostic =
            UnsupportedInspectionDomainRule(
                lineNumber = 5,
                rawRule = "*.example.com",
                issue = InspectionDomainRuleIssue.UNSUPPORTED_WILDCARD
            )
        val bundle =
            InspectionPolicyPresetBundle(
                systemHardBypass =
                    InspectionPackagePreset(
                        packages = emptySet(),
                        uids = emptySet(),
                        unsupportedRules = listOf(packageDiagnostic)
                    ),
                protectedDomainBypass =
                    InspectionDomainPreset(
                        protectedDomains = emptySet(),
                        protectedDomainsByPackage = emptyMap(),
                        unsupportedRules = listOf(domainDiagnostic)
                    ),
                knownBrowsers = emptyPackagePreset()
            )

        val result = factory.create(bundle)

        assertSame(bundle, result.sourceBundle)
        assertEquals(
            listOf(packageDiagnostic),
            result.sourceBundle.systemHardBypass.unsupportedRules
        )
        assertEquals(
            listOf(domainDiagnostic),
            result.sourceBundle.protectedDomainBypass.unsupportedRules
        )
    }

    private fun evaluate(
        packageName: String,
        snapshot: InspectionPolicySnapshot,
        port: Int = 443
    ): InspectionPolicyResult =
        engine.evaluate(
            InspectionConnection(
                packageNames = setOf(packageName),
                uid = 10001,
                host = "example.com",
                destinationPort = port
            ),
            snapshot
        )

    private fun emptyPackagePreset(): InspectionPackagePreset =
        InspectionPackagePreset(
            packages = emptySet(),
            uids = emptySet(),
            unsupportedRules = emptyList()
        )

    private fun emptyDomainPreset(): InspectionDomainPreset =
        InspectionDomainPreset(
            protectedDomains = emptySet(),
            protectedDomainsByPackage = emptyMap(),
            unsupportedRules = emptyList()
        )
}
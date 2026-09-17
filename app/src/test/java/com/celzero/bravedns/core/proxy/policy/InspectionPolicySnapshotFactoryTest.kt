package com.celzero.bravedns.core.proxy.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InspectionPolicySnapshotFactoryTest {
    private val factory = InspectionPolicySnapshotFactory()
    private val engine = InspectionPolicyEngine()

    @Test
    fun parsedPresetsAndBuiltInRegistryMapToSnapshotFields() {
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
            InspectionKnownBrowserRegistry.packages +
                "com.example.browser",
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

    @Test
    fun compatibilityPresetMapsToDistinctSnapshotFieldAndKeepsDiagnostics() {
        val diagnostic =
            InspectionAppExclusionDiagnostic(
                entryIndex = 2,
                fieldName = "package_name",
                rawValue = "com.example.compatibility",
                issue = InspectionAppExclusionIssue.DUPLICATE_PACKAGE
            )
        val compatibilityPreset =
            InspectionAppExclusionPreset(
                rules =
                    listOf(
                        InspectionAppExclusionRule(
                            packageName = "com.example.compatibility",
                            publicIssueUrl = null,
                            privateIssueUrl = null,
                            comment = "Compatibility exclusion",
                            isPublic = true
                        )
                    ),
                excludedPackages =
                    setOf("com.example.compatibility"),
                diagnostics = listOf(diagnostic)
            )
        val bundle =
            InspectionPolicyPresetBundle(
                systemHardBypass = emptyPackagePreset(),
                protectedDomainBypass = emptyDomainPreset(),
                knownBrowsers = emptyPackagePreset(),
                compatibilityExclusions = compatibilityPreset
            )

        val result =
            factory.create(
                bundle = bundle,
                userExcludedPackages = setOf("com.example.user")
            )

        assertEquals(
            setOf("com.example.compatibility"),
            result.snapshot.compatibilityExcludedPackages
        )
        assertEquals(
            setOf("com.example.user"),
            result.snapshot.userExcludedPackages
        )
        assertSame(
            compatibilityPreset,
            result.sourceBundle.compatibilityExclusions
        )
        assertEquals(
            listOf(diagnostic),
            result.sourceBundle.compatibilityExclusions.diagnostics
        )
    }

    @Test
    fun compatibilityExclusionFromPresetBeatsMitmEligibility() {
        val packageName = "com.example.compatibility"
        val compatibilityPreset =
            InspectionAppExclusionPreset(
                rules =
                    listOf(
                        InspectionAppExclusionRule(
                            packageName = packageName,
                            publicIssueUrl = null,
                            privateIssueUrl = null,
                            comment = null,
                            isPublic = false
                        )
                    ),
                excludedPackages = setOf(packageName),
                diagnostics = emptyList()
            )
        val result =
            factory.create(
                bundle =
                    InspectionPolicyPresetBundle(
                        systemHardBypass = emptyPackagePreset(),
                        protectedDomainBypass = emptyDomainPreset(),
                        knownBrowsers =
                            InspectionPackagePreset(
                                packages = setOf(packageName),
                                uids = emptySet(),
                                unsupportedRules = emptyList()
                            ),
                        compatibilityExclusions =
                            compatibilityPreset
                    ),
                userIncludedPackages = setOf(packageName),
                enabledDynamicBrowserPackages = setOf(packageName)
            )

        assertEquals(
            emptySet<String>(),
            result.snapshot.userExcludedPackages
        )
        assertEquals(
            setOf(packageName),
            result.snapshot.compatibilityExcludedPackages
        )
        assertEquals(
            InspectionReason.BYPASS_COMPATIBILITY,
            evaluate(packageName, result.snapshot).reason
        )
    }

    @Test
    fun includedDomainPresetMapsToSnapshotAndSourceBundle() {
        val includedDiagnostic =
            UnsupportedInspectionDomainRule(
                lineNumber = 1,
                rawRule = "*.unsupported.example",
                issue = InspectionDomainRuleIssue.UNSUPPORTED_WILDCARD
            )
        val includedPreset =
            InspectionDomainPreset(
                protectedDomains = setOf("included.example"),
                protectedDomainsByPackage =
                    mapOf(
                        "com.example.scoped" to
                            setOf("scoped.example")
                    ),
                unsupportedRules = listOf(includedDiagnostic)
            )
        val bundle =
            InspectionPolicyPresetBundle(
                systemHardBypass = emptyPackagePreset(),
                protectedDomainBypass = emptyDomainPreset(),
                knownBrowsers = emptyPackagePreset(),
                includedDomainMitm = includedPreset
            )

        val result = factory.create(bundle)

        assertEquals(
            setOf("included.example"),
            result.snapshot.includedDomains
        )
        assertEquals(
            false,
            result.snapshot.includedDomains.contains("scoped.example")
        )
        assertSame(includedPreset, result.sourceBundle.includedDomainMitm)
        assertEquals(
            listOf(includedDiagnostic),
            result.sourceBundle.includedDomainMitm.unsupportedRules
        )
    }

    @Test
    fun domainModeDefaultsToAllExceptProtected() {
        val domainPreset =
            InspectionDomainPreset(
                protectedDomains = setOf("protected.example"),
                protectedDomainsByPackage = emptyMap(),
                unsupportedRules = emptyList()
            )
        val bundle =
            InspectionPolicyPresetBundle(
                systemHardBypass = emptyPackagePreset(),
                protectedDomainBypass = domainPreset,
                knownBrowsers =
                    InspectionPackagePreset(
                        packages = setOf("com.example.browser"),
                        uids = emptySet(),
                        unsupportedRules = emptyList()
                    )
            )

        val result = factory.create(bundle)

        assertEquals(
            InspectionDomainMode.ALL_EXCEPT_PROTECTED,
            result.snapshot.domainMode
        )
        assertEquals(
            emptySet<String>(),
            result.snapshot.includedDomains
        )
        assertEquals(
            InspectionReason.BYPASS_DOMAIN,
            evaluate(
                packageName = "com.example.browser",
                snapshot = result.snapshot,
                host = "protected.example"
            ).reason
        )
    }

    @Test
    fun onlyIncludedModeFlowsThroughFactoryWithoutEnablingGeneralApplications() {
        val bundle =
            InspectionPolicyPresetBundle(
                systemHardBypass = emptyPackagePreset(),
                protectedDomainBypass = emptyDomainPreset(),
                knownBrowsers =
                    InspectionPackagePreset(
                        packages = setOf("com.example.browser"),
                        uids = emptySet(),
                        unsupportedRules = emptyList()
                    ),
                includedDomainMitm =
                    InspectionDomainPreset(
                        protectedDomains = setOf("example.com"),
                        protectedDomainsByPackage = emptyMap(),
                        unsupportedRules = emptyList()
                    )
            )

        val result =
            factory.create(
                bundle = bundle,
                domainMode = InspectionDomainMode.ONLY_INCLUDED
            )

        assertEquals(
            InspectionDomainMode.ONLY_INCLUDED,
            result.snapshot.domainMode
        )
        assertEquals(
            setOf("example.com"),
            result.snapshot.includedDomains
        )
        assertEquals(
            InspectionReason.MITM_KNOWN_BROWSER,
            evaluate(
                packageName = "com.example.browser",
                snapshot = result.snapshot,
                host = "example.com"
            ).reason
        )
        assertEquals(
            InspectionReason.MITM_KNOWN_BROWSER,
            evaluate(
                packageName = "com.example.browser",
                snapshot = result.snapshot,
                host = "sub.example.com"
            ).reason
        )
        assertEquals(
            InspectionReason.BYPASS_DOMAIN_MODE,
            evaluate(
                packageName = "com.example.browser",
                snapshot = result.snapshot,
                host = "outside.example"
            ).reason
        )
        assertEquals(
            InspectionReason.BYPASS_DEFAULT,
            evaluate(
                packageName = "com.example.general",
                snapshot = result.snapshot,
                host = "example.com"
            ).reason
        )
    }

    @Test
    fun builtInKnownBrowserRegistryIsDefaultOnWhenPresetIsEmpty() {
        val result =
            factory.create(
                InspectionPolicyPresetBundle(
                    systemHardBypass = emptyPackagePreset(),
                    protectedDomainBypass = emptyDomainPreset(),
                    knownBrowsers = emptyPackagePreset()
                )
            )

        assertEquals(
            InspectionKnownBrowserRegistry.packages,
            result.snapshot.knownBrowserPackages
        )
        assertEquals(
            144,
            result.snapshot.knownBrowserPackages.size
        )
        assertEquals(
            InspectionReason.MITM_KNOWN_BROWSER,
            evaluate(
                packageName = "com.android.chrome",
                snapshot = result.snapshot
            ).reason
        )
        assertEquals(
            InspectionReason.MITM_KNOWN_BROWSER,
            evaluate(
                packageName = "org.mozilla.firefox",
                snapshot = result.snapshot
            ).reason
        )
        assertEquals(
            InspectionReason.MITM_KNOWN_BROWSER,
            evaluate(
                packageName = "com.microsoft.emmx",
                snapshot = result.snapshot
            ).reason
        )
    }

    @Test
    fun userExclusionWinsOverBuiltInKnownBrowserRegistry() {
        val packageName = "com.brave.browser"
        val result =
            factory.create(
                bundle =
                    InspectionPolicyPresetBundle(
                        systemHardBypass = emptyPackagePreset(),
                        protectedDomainBypass = emptyDomainPreset(),
                        knownBrowsers = emptyPackagePreset()
                    ),
                userExcludedPackages = setOf(packageName)
            )

        assertEquals(
            true,
            result.snapshot.knownBrowserPackages.contains(packageName)
        )
        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_USER
            ),
            evaluate(
                packageName = packageName,
                snapshot = result.snapshot
            )
        )
    }

    private fun evaluate(
        packageName: String,
        snapshot: InspectionPolicySnapshot,
        port: Int = 443,
        host: String = "example.com"
    ): InspectionPolicyResult =
        engine.evaluate(
            InspectionConnection(
                packageNames = setOf(packageName),
                uid = 10001,
                host = host,
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

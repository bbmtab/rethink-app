package com.celzero.bravedns.core.proxy.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class InspectionPolicyEngineTest {
    private val engine = InspectionPolicyEngine()

    @Test
    fun systemPackageBypassWinsOverEveryMitmInclusion() {
        val connection =
            InspectionConnection(
                packageNames = setOf("com.example.system"),
                uid = 10001,
                host = "example.com",
                destinationPort = 443
            )
        val policy =
            InspectionPolicySnapshot(
                systemHardBypassPackages = setOf("com.example.system"),
                compatibilityExcludedPackages =
                    setOf("com.example.system"),
                knownBrowserPackages = setOf("com.example.system"),
                userIncludedPackages = setOf("com.example.system"),
                enabledDynamicBrowserPackages = setOf("com.example.system")
            )

        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_SYSTEM
            ),
            engine.evaluate(connection, policy)
        )
    }

    @Test
    fun systemUidBypassWinsWhenPackageNamesAreUnavailable() {
        val connection =
            InspectionConnection(
                packageNames = emptySet(),
                uid = 10042,
                host = "example.com",
                destinationPort = 443
            )
        val policy =
            InspectionPolicySnapshot(
                systemHardBypassUids = setOf(10042)
            )

        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_SYSTEM
            ),
            engine.evaluate(connection, policy)
        )
    }

    @Test
    fun userExclusionWinsOverKnownBrowserInclusion() {
        val connection = connection(packageName = "com.example.browser")
        val policy =
            InspectionPolicySnapshot(
                userExcludedPackages = setOf("com.example.browser"),
                knownBrowserPackages = setOf("com.example.browser")
            )

        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_USER
            ),
            engine.evaluate(connection, policy)
        )
    }

    @Test
    fun protectedDomainMatchesExactHostAndSubdomains() {
        val policy =
            InspectionPolicySnapshot(
                protectedDomains = setOf("protected.example")
            )

        assertEquals(
            InspectionReason.BYPASS_DOMAIN,
            engine.evaluate(
                connection(host = "protected.example"),
                policy
            ).reason
        )
        assertEquals(
            InspectionReason.BYPASS_DOMAIN,
            engine.evaluate(
                connection(host = "Api.Protected.Example."),
                policy
            ).reason
        )
    }

    @Test
    fun protectedAppPortRequiresBothPackageAndPort() {
        val policy =
            InspectionPolicySnapshot(
                protectedAppPorts =
                    mapOf("com.example.push" to setOf(5228, 5229, 5230)),
                userIncludedPackages = setOf("com.example.push")
            )

        assertEquals(
            InspectionReason.BYPASS_APP_PORT,
            engine.evaluate(
                connection(
                    packageName = "com.example.push",
                    destinationPort = 5228
                ),
                policy
            ).reason
        )
        assertEquals(
            InspectionReason.MITM_USER_APP,
            engine.evaluate(
                connection(
                    packageName = "com.example.push",
                    destinationPort = 443
                ),
                policy
            ).reason
        )
    }

    @Test
    fun mitmEligibilityReasonsRemainDistinct() {
        assertEquals(
            InspectionReason.MITM_KNOWN_BROWSER,
            engine.evaluate(
                connection(packageName = "com.example.known"),
                InspectionPolicySnapshot(
                    knownBrowserPackages = setOf("com.example.known")
                )
            ).reason
        )
        assertEquals(
            InspectionReason.MITM_USER_APP,
            engine.evaluate(
                connection(packageName = "com.example.user"),
                InspectionPolicySnapshot(
                    userIncludedPackages = setOf("com.example.user")
                )
            ).reason
        )
        assertEquals(
            InspectionReason.MITM_DYNAMIC_BROWSER,
            engine.evaluate(
                connection(packageName = "com.example.dynamic"),
                InspectionPolicySnapshot(
                    enabledDynamicBrowserPackages =
                        setOf("com.example.dynamic")
                )
            ).reason
        )
    }

    @Test
    fun unknownOrUnresolvedApplicationDefaultsToBypass() {
        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_DEFAULT
            ),
            engine.evaluate(
                InspectionConnection(
                    packageNames = emptySet(),
                    uid = null,
                    host = "example.com",
                    destinationPort = 443
                ),
                InspectionPolicySnapshot()
            )
        )
    }

    @Test
    fun sharedUidUsesConservativeAnyPackagePrecedence() {
        val connection =
            InspectionConnection(
                packageNames =
                    setOf(
                        "com.example.included",
                        "com.example.excluded"
                    ),
                uid = 10077,
                host = "example.com",
                destinationPort = 443
            )
        val policy =
            InspectionPolicySnapshot(
                userExcludedPackages = setOf("com.example.excluded"),
                knownBrowserPackages = setOf("com.example.included")
            )

        assertEquals(
            InspectionReason.BYPASS_USER,
            engine.evaluate(connection, policy).reason
        )
    }

    @Test
    fun packageScopedDomainBypassDoesNotLeakToAnotherApplication() {
        val policy =
            InspectionPolicySnapshot(
                protectedDomainsByPackage =
                    mapOf(
                        "com.example.bound" to setOf("bound.example")
                    )
            )

        assertEquals(
            InspectionReason.BYPASS_DOMAIN,
            engine.evaluate(
                connection(
                    packageName = "com.example.bound",
                    host = "api.bound.example"
                ),
                policy
            ).reason
        )
        assertEquals(
            InspectionReason.BYPASS_DEFAULT,
            engine.evaluate(
                connection(
                    packageName = "com.example.other",
                    host = "api.bound.example"
                ),
                policy
            ).reason
        )
    }

    @Test
    fun sharedUidHonorsAnyPackageScopedDomainBypass() {
        val connection =
            InspectionConnection(
                packageNames =
                    setOf(
                        "com.example.other",
                        " COM.EXAMPLE.BOUND "
                    ),
                uid = 10077,
                host = "Bound.Example.",
                destinationPort = 443
            )
        val policy =
            InspectionPolicySnapshot(
                protectedDomainsByPackage =
                    mapOf(
                        " com.example.bound " to
                            setOf(" bound.example. ")
                    )
            )

        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_DOMAIN
            ),
            engine.evaluate(connection, policy)
        )
    }

    @Test
    fun compatibilityExclusionWinsOverEveryMitmInclusion() {
        val packageName = "com.example.compatibility"
        val policy =
            InspectionPolicySnapshot(
                compatibilityExcludedPackages = setOf(packageName),
                domainMode = InspectionDomainMode.ONLY_INCLUDED,
                includedDomains = setOf("example.com"),
                knownBrowserPackages = setOf(packageName),
                userIncludedPackages = setOf(packageName),
                enabledDynamicBrowserPackages = setOf(packageName)
            )

        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_COMPATIBILITY
            ),
            engine.evaluate(
                connection(packageName = packageName),
                policy
            )
        )
    }

    @Test
    fun userExclusionKeepsPrecedenceOverCompatibilityExclusion() {
        val packageName = "com.example.excluded"
        val policy =
            InspectionPolicySnapshot(
                userExcludedPackages = setOf(packageName),
                compatibilityExcludedPackages = setOf(packageName),
                knownBrowserPackages = setOf(packageName)
            )

        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_USER
            ),
            engine.evaluate(
                connection(packageName = packageName),
                policy
            )
        )
    }

    @Test
    fun sharedUidUsesAnyCompatibilityExcludedPackage() {
        val connection =
            InspectionConnection(
                packageNames =
                    setOf(
                        "com.example.included",
                        " COM.EXAMPLE.COMPATIBILITY "
                    ),
                uid = 10077,
                host = "example.com",
                destinationPort = 443
            )
        val policy =
            InspectionPolicySnapshot(
                compatibilityExcludedPackages =
                    setOf("com.example.compatibility"),
                knownBrowserPackages =
                    setOf("com.example.included"),
                userIncludedPackages =
                    setOf("com.example.included")
            )

        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_COMPATIBILITY
            ),
            engine.evaluate(connection, policy)
        )
    }

    @Test
    fun allExceptProtectedModeDoesNotUseIncludedDomainsAsAGate() {
        val packageName = "com.example.browser"
        val policy =
            InspectionPolicySnapshot(
                domainMode =
                    InspectionDomainMode.ALL_EXCEPT_PROTECTED,
                includedDomains = setOf("different.example"),
                knownBrowserPackages = setOf(packageName)
            )

        assertEquals(
            InspectionReason.MITM_KNOWN_BROWSER,
            engine.evaluate(
                connection(
                    packageName = packageName,
                    host = "example.com"
                ),
                policy
            ).reason
        )
    }

    @Test
    fun onlyIncludedModeBypassesEligibleAppOnUnlistedDomain() {
        val packageName = "com.example.browser"
        val policy =
            InspectionPolicySnapshot(
                domainMode = InspectionDomainMode.ONLY_INCLUDED,
                includedDomains = setOf("included.example"),
                knownBrowserPackages = setOf(packageName)
            )

        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_DOMAIN_MODE
            ),
            engine.evaluate(
                connection(
                    packageName = packageName,
                    host = "unlisted.example"
                ),
                policy
            )
        )
    }

    @Test
    fun onlyIncludedModeAllowsEligibleAppOnExactAndSubdomain() {
        val packageName = "com.example.browser"
        val policy =
            InspectionPolicySnapshot(
                domainMode = InspectionDomainMode.ONLY_INCLUDED,
                includedDomains = setOf("included.example"),
                knownBrowserPackages = setOf(packageName)
            )

        assertEquals(
            InspectionReason.MITM_KNOWN_BROWSER,
            engine.evaluate(
                connection(
                    packageName = packageName,
                    host = "included.example"
                ),
                policy
            ).reason
        )
        assertEquals(
            InspectionReason.MITM_KNOWN_BROWSER,
            engine.evaluate(
                connection(
                    packageName = packageName,
                    host = "Api.Included.Example."
                ),
                policy
            ).reason
        )
    }

    @Test
    fun onlyIncludedModeDoesNotEnableGeneralApplication() {
        val policy =
            InspectionPolicySnapshot(
                domainMode = InspectionDomainMode.ONLY_INCLUDED,
                includedDomains = setOf("included.example")
            )

        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_DEFAULT
            ),
            engine.evaluate(
                connection(
                    packageName = "com.example.general",
                    host = "included.example"
                ),
                policy
            )
        )
    }

    @Test
    fun onlyIncludedModeWithEmptyRegistryBypassesSafely() {
        val packageName = "com.example.browser"
        val policy =
            InspectionPolicySnapshot(
                domainMode = InspectionDomainMode.ONLY_INCLUDED,
                includedDomains = emptySet(),
                knownBrowserPackages = setOf(packageName)
            )

        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_DOMAIN_MODE
            ),
            engine.evaluate(
                connection(packageName = packageName),
                policy
            )
        )
    }

    @Test
    fun onlyIncludedModeDoesNotApplyProtectedDomainRegistry() {
        val packageName = "com.example.browser"
        val policy =
            InspectionPolicySnapshot(
                domainMode = InspectionDomainMode.ONLY_INCLUDED,
                protectedDomains = setOf("protected.example"),
                includedDomains = setOf("included.example"),
                knownBrowserPackages = setOf(packageName)
            )

        assertEquals(
            InspectionPolicyResult(
                InspectionDecision.BYPASS,
                InspectionReason.BYPASS_DOMAIN_MODE
            ),
            engine.evaluate(
                connection(
                    packageName = packageName,
                    host = "protected.example"
                ),
                policy
            )
        )
    }

    private fun connection(
        packageName: String = "com.example.browser",
        host: String = "example.com",
        destinationPort: Int = 443
    ): InspectionConnection =
        InspectionConnection(
            packageNames = setOf(packageName),
            uid = 10001,
            host = host,
            destinationPort = destinationPort
        )
}

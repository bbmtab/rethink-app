package com.celzero.bravedns.core.proxy.policy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CancellationException

/**
 * Pure JVM tests for [InspectionConnectionPolicyEvaluator]. No Android imports,
 * no real sockets, no asset reads, no mocks — only injected identity functions
 * and in-memory [InspectionPolicySnapshot] values.
 */
class InspectionConnectionPolicyEvaluatorTest {

    private val dummySocket: Socket =
        Socket()

    private fun knownBrowserSnapshot(
        knownBrowsers: Set<String> = setOf("com.example.browser")
    ): InspectionPolicySnapshot =
        InspectionPolicySnapshot(
            knownBrowserPackages = knownBrowsers
        )

    @Test
    fun knownBrowserConnectionReturnsMitm() = runBlocking {
        val evaluator =
            InspectionConnectionPolicyEvaluator(
                policySnapshot = knownBrowserSnapshot(),
                identityResolver = {
                    InspectionConnectionIdentity(
                        uid = 1000,
                        packageNames = setOf("com.example.browser")
                    )
                }
            )

        val result =
            evaluator.evaluate(
                clientSocket = dummySocket,
                host = "example.com",
                destinationPort = 443
            )

        assertEquals(
            InspectionDecision.MITM,
            result.decision
        )
        assertEquals(
            InspectionReason.MITM_KNOWN_BROWSER,
            result.reason
        )
    }

    @Test
    fun compatibilityExclusionWinsOverKnownBrowser() = runBlocking {
        val evaluator =
            InspectionConnectionPolicyEvaluator(
                policySnapshot =
                    InspectionPolicySnapshot(
                        knownBrowserPackages = setOf("com.example.browser"),
                        compatibilityExcludedPackages =
                            setOf("com.example.browser")
                    ),
                identityResolver = {
                    InspectionConnectionIdentity(
                        uid = 1000,
                        packageNames = setOf("com.example.browser")
                    )
                }
            )

        val result =
            evaluator.evaluate(
                clientSocket = dummySocket,
                host = "example.com",
                destinationPort = 443
            )

        assertEquals(
            InspectionDecision.BYPASS,
            result.decision
        )
        assertEquals(
            InspectionReason.BYPASS_COMPATIBILITY,
            result.reason
        )
    }

    @Test
    fun systemUidWinsOverKnownBrowser() = runBlocking {
        val evaluator =
            InspectionConnectionPolicyEvaluator(
                policySnapshot =
                    InspectionPolicySnapshot(
                        knownBrowserPackages = setOf("com.example.browser"),
                        systemHardBypassUids = setOf(1000)
                    ),
                identityResolver = {
                    InspectionConnectionIdentity(
                        uid = 1000,
                        packageNames = setOf("com.example.browser")
                    )
                }
            )

        val result =
            evaluator.evaluate(
                clientSocket = dummySocket,
                host = "example.com",
                destinationPort = 443
            )

        assertEquals(
            InspectionDecision.BYPASS,
            result.decision
        )
        assertEquals(
            InspectionReason.BYPASS_SYSTEM,
            result.reason
        )
    }

    @Test
    fun protectedDomainWinsOverKnownBrowser() = runBlocking {
        val evaluator =
            InspectionConnectionPolicyEvaluator(
                policySnapshot =
                    InspectionPolicySnapshot(
                        knownBrowserPackages = setOf("com.example.browser"),
                        protectedDomains = setOf("protected.example")
                    ),
                identityResolver = {
                    InspectionConnectionIdentity(
                        uid = 1000,
                        packageNames = setOf("com.example.browser")
                    )
                }
            )

        val result =
            evaluator.evaluate(
                clientSocket = dummySocket,
                host = "sub.protected.example",
                destinationPort = 443
            )

        assertEquals(
            InspectionDecision.BYPASS,
            result.decision
        )
        assertEquals(
            InspectionReason.BYPASS_DOMAIN,
            result.reason
        )
    }

    @Test
    fun unresolvedIdentityReturnsDefaultBypass() = runBlocking {
        val evaluator =
            InspectionConnectionPolicyEvaluator(
                policySnapshot = knownBrowserSnapshot(),
                identityResolver = {
                    InspectionConnectionIdentity(
                        uid = null,
                        packageNames = emptySet()
                    )
                }
            )

        val result =
            evaluator.evaluate(
                clientSocket = dummySocket,
                host = "example.com",
                destinationPort = 443
            )

        assertEquals(
            InspectionDecision.BYPASS,
            result.decision
        )
        assertEquals(
            InspectionReason.BYPASS_DEFAULT,
            result.reason
        )
    }

    @Test
    fun identityRuntimeFailureReturnsDefaultBypass() = runBlocking {
        val evaluator =
            InspectionConnectionPolicyEvaluator(
                policySnapshot = knownBrowserSnapshot(),
                identityResolver = {
                    throw RuntimeException("simulated resolver failure")
                }
            )

        val result =
            evaluator.evaluate(
                clientSocket = dummySocket,
                host = "example.com",
                destinationPort = 443
            )

        assertEquals(
            InspectionDecision.BYPASS,
            result.decision
        )
        assertEquals(
            InspectionReason.BYPASS_DEFAULT,
            result.reason
        )
    }

    @Test
    fun identityCancellationIsRethrown() {
        val evaluator =
            InspectionConnectionPolicyEvaluator(
                policySnapshot = knownBrowserSnapshot(),
                identityResolver = {
                    throw CancellationException("cancelled")
                }
            )

        try {
            kotlinx.coroutines.runBlocking {
                evaluator.evaluate(
                    clientSocket = dummySocket,
                    host = "example.com",
                    destinationPort = 443
                )
            }
            org.junit.Assert.fail(
                "CancellationException must propagate from identity resolver"
            )
        } catch (expected: CancellationException) {
            // Expected
        }
    }
}

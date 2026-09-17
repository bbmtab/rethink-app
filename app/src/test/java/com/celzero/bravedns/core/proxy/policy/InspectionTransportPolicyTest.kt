package com.celzero.bravedns.core.proxy.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionTransportPolicyTest {
private val transportPolicy =
InspectionTransportPolicy()

@Test
fun knownBrowserUdp443ForcesTcp() {
    val result =
        evaluate(
            packageName = "com.example.browser",
            policy =
                InspectionPolicySnapshot(
                    knownBrowserPackages =
                        setOf("com.example.browser")
                )
        )

    assertTrue(result.forceTcp)
    assertEquals(
        InspectionReason.MITM_KNOWN_BROWSER,
        result.inspectionResult?.reason
    )
}

@Test
fun dynamicBrowserUdp443ForcesTcp() {
    val result =
        evaluate(
            packageName = "com.example.dynamic",
            policy =
                InspectionPolicySnapshot(
                    enabledDynamicBrowserPackages =
                        setOf("com.example.dynamic")
                )
        )

    assertTrue(result.forceTcp)
    assertEquals(
        InspectionReason.MITM_DYNAMIC_BROWSER,
        result.inspectionResult?.reason
    )
}

@Test
fun userIncludedAppUdp443ForcesTcp() {
    val result =
        evaluate(
            packageName = "com.example.app",
            policy =
                InspectionPolicySnapshot(
                    userIncludedPackages =
                        setOf("com.example.app")
                )
        )

    assertTrue(result.forceTcp)
    assertEquals(
        InspectionReason.MITM_USER_APP,
        result.inspectionResult?.reason
    )
}

@Test
fun userExcludedKnownBrowserDoesNotForceTcp() {
    val result =
        evaluate(
            packageName = "com.example.browser",
            policy =
                InspectionPolicySnapshot(
                    knownBrowserPackages =
                        setOf("com.example.browser"),
                    userExcludedPackages =
                        setOf("com.example.browser")
                )
        )

    assertFalse(result.forceTcp)
    assertEquals(
        InspectionReason.BYPASS_USER,
        result.inspectionResult?.reason
    )
}

@Test
fun systemHardBypassUidDoesNotForceTcp() {
    val result =
        transportPolicy.evaluate(
            packageNames =
                setOf("com.example.browser"),
            uid = 1000,
            host = "example.com",
            destinationPort = 443,
            isUdp = true,
            policy =
                InspectionPolicySnapshot(
                    systemHardBypassUids =
                        setOf(1000),
                    knownBrowserPackages =
                        setOf("com.example.browser")
                )
        )

    assertFalse(result.forceTcp)
    assertEquals(
        InspectionReason.BYPASS_SYSTEM,
        result.inspectionResult?.reason
    )
}

@Test
fun compatibilityExcludedBrowserDoesNotForceTcp() {
    val result =
        evaluate(
            packageName = "com.example.browser",
            policy =
                InspectionPolicySnapshot(
                    compatibilityExcludedPackages =
                        setOf("com.example.browser"),
                    knownBrowserPackages =
                        setOf("com.example.browser")
                )
        )

    assertFalse(result.forceTcp)
    assertEquals(
        InspectionReason.BYPASS_COMPATIBILITY,
        result.inspectionResult?.reason
    )
}

@Test
fun protectedDomainDoesNotForceTcp() {
    val result =
        evaluate(
            packageName = "com.example.browser",
            host = "secure.example.com",
            policy =
                InspectionPolicySnapshot(
                    protectedDomains =
                        setOf("example.com"),
                    knownBrowserPackages =
                        setOf("com.example.browser")
                )
        )

    assertFalse(result.forceTcp)
    assertEquals(
        InspectionReason.BYPASS_DOMAIN,
        result.inspectionResult?.reason
    )
}

@Test
fun defaultAppUdp443DoesNotForceTcp() {
    val result =
        evaluate(
            packageName = "com.example.app",
            policy = InspectionPolicySnapshot()
        )

    assertFalse(result.forceTcp)
    assertEquals(
        InspectionReason.BYPASS_DEFAULT,
        result.inspectionResult?.reason
    )
}

@Test
fun knownBrowserTcp443DoesNotApplyTransportConstraint() {
    val result =
        transportPolicy.evaluate(
            packageNames =
                setOf("com.example.browser"),
            uid = 12345,
            host = "example.com",
            destinationPort = 443,
            isUdp = false,
            policy =
                InspectionPolicySnapshot(
                    knownBrowserPackages =
                        setOf("com.example.browser")
                )
        )

    assertFalse(result.forceTcp)
    assertNull(result.inspectionResult)
}

@Test
fun knownBrowserUdpNon443DoesNotApplyTransportConstraint() {
    val result =
        transportPolicy.evaluate(
            packageNames =
                setOf("com.example.browser"),
            uid = 12345,
            host = "example.com",
            destinationPort = 8443,
            isUdp = true,
            policy =
                InspectionPolicySnapshot(
                    knownBrowserPackages =
                        setOf("com.example.browser")
                )
        )

    assertFalse(result.forceTcp)
    assertNull(result.inspectionResult)
}

@Test
fun knownBrowserUdp443WithUnknownHostStillForcesTcp() {
    val result =
        evaluate(
            packageName = "com.example.browser",
            host = "",
            policy =
                InspectionPolicySnapshot(
                    knownBrowserPackages =
                        setOf("com.example.browser")
                )
        )

    assertTrue(result.forceTcp)
    assertEquals(
        InspectionReason.MITM_KNOWN_BROWSER,
        result.inspectionResult?.reason
    )
}

private fun evaluate(
    packageName: String,
    host: String = "example.com",
    policy: InspectionPolicySnapshot
): InspectionTransportPolicyResult =
    transportPolicy.evaluate(
        packageNames = setOf(packageName),
        uid = 12345,
        host = host,
        destinationPort = 443,
        isUdp = true,
        policy = policy
    )
}


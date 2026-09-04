package com.celzero.bravedns.core.proxy

import com.celzero.bravedns.core.ca.CertificateAuthority
import com.celzero.bravedns.core.proxy.policy.InspectionConnectionPolicyEvaluator
import com.celzero.bravedns.core.proxy.policy.InspectionConnectionIdentity
import com.celzero.bravedns.core.proxy.policy.InspectionDecision
import com.celzero.bravedns.core.proxy.policy.InspectionPolicySnapshot
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * JVM-based integration/unit tests for LocalHttpsProxy.
 * Verifies proxy initialization, connect tunneling, error handling, and socket piping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LocalHttpsProxyTest {

    private val TEST_PORT = 18443

    @Before
    fun setUp() {
        CertificateAuthority.resetCA()
        CertificateAuthority.initializeCA()
    }

    @After
    fun tearDown() {
        LocalHttpsProxy.stop()
    }

    @Test
    fun testProxyStartStop() {
        LocalHttpsProxy.start(TEST_PORT)
        
        // Wait briefly for server socket to bind
        Thread.sleep(150)
        
        try {
            Socket("localhost", TEST_PORT).use { socket ->
                assertTrue("Proxy should successfully accept socket connections on port $TEST_PORT", socket.isConnected)
            }
        } catch (e: Exception) {
            fail("Failed to connect to LocalHttpsProxy: ${e.message}")
        } finally {
            LocalHttpsProxy.stop()
        }
    }

    @Test
    fun testProxyReturnsBadGatewayOnInvalidUpstream() {
        io.mockk.mockkObject(com.celzero.bravedns.service.VpnController)
        io.mockk.every {
            com.celzero.bravedns.service.VpnController.protectSocket(any())
        } returns Unit

        try {
            LocalHttpsProxy.start(TEST_PORT)
            Thread.sleep(150)

            Socket("localhost", TEST_PORT).use { socket ->
                val out = socket.getOutputStream()
                // Request a CONNECT tunnel to an unallocated local port
                out.write(
                    (
                        "CONNECT localhost:59999 HTTP/1.1\r\n" +
                            "Host: localhost:59999\r\n\r\n"
                    ).toByteArray()
                )
                out.flush()

                val reader =
                    BufferedReader(InputStreamReader(socket.getInputStream()))
                val responseLine = reader.readLine()
                assertNotNull(
                    "Proxy must return an HTTP response line",
                    responseLine
                )
                assertTrue(
                    "Unreachable upstream should yield a 502 Bad Gateway response, " +
                        "got: $responseLine",
                    responseLine.contains("502") ||
                        responseLine.contains("Bad Gateway")
                )
            }
        } finally {
            LocalHttpsProxy.stop()
            io.mockk.unmockkObject(
                com.celzero.bravedns.service.VpnController
            )
        }
    }

    @Test
    fun testInitializationClearsLegacyBypassAndHandshakeFailureDoesNotPersist() {
        val mockState =
            io.mockk.mockk<com.celzero.bravedns.service.PersistentState>(relaxed = true)

        var savedHosts = "custom-pinned.com"
        io.mockk.every { mockState.httpsBypassHosts } answers { savedHosts }
        io.mockk.every { mockState.httpsBypassHosts = any() } answers {
            savedHosts = firstArg()
        }

        LocalHttpsProxy.initialize(mockState)

        assertEquals(
            "Legacy persisted bypass hosts must be cleared during initialization",
            "",
            savedHosts
        )
        assertTrue(
            "A cleared legacy host must remain eligible for inspection",
            LocalHttpsProxy.shouldInspectDomain("custom-pinned.com")
        )
        assertTrue(
            "A subdomain of a cleared legacy host must remain eligible",
            LocalHttpsProxy.shouldInspectDomain("api.custom-pinned.com")
        )
        assertTrue(
            "Static domain policy is now owned by InspectionPolicyEngine",
            LocalHttpsProxy.shouldInspectDomain("google.com")
        )

        val method =
            LocalHttpsProxy::class.java.getDeclaredMethod(
                "addToBypassCache",
                String::class.java
            )
        method.isAccessible = true
        method.invoke(LocalHttpsProxy, "test-failed-handshake.com")

        assertTrue(
            "TLS handshake failure must not create a dynamic bypass",
            LocalHttpsProxy.shouldInspectDomain("test-failed-handshake.com")
        )
        assertEquals(
            "TLS handshake failure must not be persisted",
            "",
            savedHosts
        )
    }

    @Test
    fun missingPolicyEvaluatorBypassesByDefault() {
        LocalHttpsProxy.stop()

        val dummySocket = Socket()

        val result = runBlocking {
            LocalHttpsProxy.evaluateInspectionPolicy(
                clientSocket = dummySocket,
                host = "example.com",
                destinationPort = 443
            )
        }

        assertEquals(InspectionDecision.BYPASS, result.decision)
    }

    @Test
    fun configuredPolicyEvaluatorControlsDecision() {
        LocalHttpsProxy.stop()

        val evaluator =
            InspectionConnectionPolicyEvaluator(
                policySnapshot =
                    InspectionPolicySnapshot(
                        knownBrowserPackages = setOf("com.example.browser")
                    ),
                identityResolver = {
                    InspectionConnectionIdentity(
                        uid = 1000,
                        packageNames = setOf("com.example.browser")
                    )
                }
            )
        LocalHttpsProxy.setInspectionPolicyEvaluator(evaluator)

        val dummySocket = Socket()

        val result = runBlocking {
            LocalHttpsProxy.evaluateInspectionPolicy(
                clientSocket = dummySocket,
                host = "example.com",
                destinationPort = 443
            )
        }

        assertEquals(InspectionDecision.MITM, result.decision)
    }
}

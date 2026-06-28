package com.celzero.bravedns.core.proxy

import com.celzero.bravedns.core.ca.CertificateAuthority
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * JVM-based integration/unit tests for LocalHttpsProxy.
 * Verifies proxy initialization, connect tunneling, error handling, and socket piping.
 */
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
        LocalHttpsProxy.start(TEST_PORT)
        Thread.sleep(150)

        try {
            Socket("localhost", TEST_PORT).use { socket ->
                val out = socket.getOutputStream()
                // Request a CONNECT tunnel to an unallocated local port (guaranteed to fail connection)
                out.write("CONNECT localhost:59999 HTTP/1.1\r\nHost: localhost:59999\r\n\r\n".toByteArray())
                out.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val responseLine = reader.readLine()
                assertNotNull("Proxy must return an HTTP response line", responseLine)
                assertTrue(
                    "Unreachable upstream should yield a 502 Bad Gateway response, got: $responseLine",
                    responseLine.contains("502") || responseLine.contains("Bad Gateway")
                )
            }
        } finally {
            LocalHttpsProxy.stop()
        }
    }

    @Test
    fun testDynamicBypassAndPersistence() {
        val mockState = io.mockk.mockk<com.celzero.bravedns.service.PersistentState>(relaxed = true)
        
        // Return a pre-saved bypass host string when requested
        var savedHosts = "custom-pinned.com"
        io.mockk.every { mockState.httpsBypassHosts } answers { savedHosts }
        io.mockk.every { mockState.httpsBypassHosts = any() } answers { savedHosts = firstArg() }

        // Initialize the proxy with our mockState
        LocalHttpsProxy.initialize(mockState)

        // The pre-saved host should be bypassed
        assertFalse("custom-pinned.com should be bypassed", LocalHttpsProxy.shouldInspectDomain("custom-pinned.com"))
        assertFalse("subdomain should also be bypassed", LocalHttpsProxy.shouldInspectDomain("api.custom-pinned.com"))
        assertTrue("other domain should be inspected", LocalHttpsProxy.shouldInspectDomain("example.com"))

        // Simulate a TLS handshake failure for a domain using reflection to invoke the private helper
        val method = LocalHttpsProxy::class.java.getDeclaredMethod("addToBypassCache", String::class.java)
        method.isAccessible = true
        method.invoke(LocalHttpsProxy, "test-failed-handshake.com")

        assertFalse("test-failed-handshake.com should be dynamically bypassed now", LocalHttpsProxy.shouldInspectDomain("test-failed-handshake.com"))
        assertTrue("mockState should have been updated with the new bypass list", savedHosts.contains("test-failed-handshake.com"))
    }
}

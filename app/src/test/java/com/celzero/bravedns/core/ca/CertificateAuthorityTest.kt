package com.celzero.bravedns.core.ca

import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Unit tests for CertificateAuthority.
 * Verified to run in non-Android environments due to fallback keystore logic.
 */
class CertificateAuthorityTest {

    @Before
    fun setUp() {
        // Reset CA to ensure clean state before each test
        CertificateAuthority.resetCA()
        CertificateAuthority.initializeCA()
    }

    @Test
    fun testInitializeCA_generatesRootCA() {
        val certBytes = CertificateAuthority.exportCaCert()
        assertNotNull("Root CA cert bytes should not be null", certBytes)
        assertTrue("Root CA cert bytes should not be empty", certBytes.isNotEmpty())

        val factory = CertificateFactory.getInstance("X.509")
        val cert = factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

        assertTrue("Issuer should contain RethinkDNS", cert.issuerX500Principal.name.replace(" ", "").contains("RethinkDNS"))
        assertTrue("Subject should contain RethinkDNS", cert.subjectX500Principal.name.replace(" ", "").contains("RethinkDNS"))
        assertTrue("Root CA should be a self-signed CA cert", cert.basicConstraints >= 0)
    }

    @Test
    fun testGenerateLeafCert_generatesValidLeafCert() {
        val hostname = "google.com"
        val leafCert = CertificateAuthority.generateLeafCert(hostname)

        assertNotNull("Leaf certificate should not be null", leafCert)
        assertTrue("Subject should contain google.com", leafCert.subjectX500Principal.name.replace(" ", "").contains("CN=google.com"))
        assertTrue("Issuer should contain RethinkDNS", leafCert.issuerX500Principal.name.replace(" ", "").contains("RethinkDNS"))
        assertEquals("Leaf cert should have basicConstraints set to -1 (Not a CA)", -1, leafCert.basicConstraints)

        // Verify leaf certificate is signed by Root CA
        val rootCertBytes = CertificateAuthority.exportCaCert()
        val factory = CertificateFactory.getInstance("X.509")
        val rootCert = factory.generateCertificate(ByteArrayInputStream(rootCertBytes)) as X509Certificate

        try {
            leafCert.verify(rootCert.publicKey)
        } catch (e: Exception) {
            fail("Leaf certificate verification failed: ${e.message}")
        }
    }

    @Test
    fun testGenerateLeafCert_containsSubjectAlternativeName() {
        val hostname = "example.com"
        val leafCert = CertificateAuthority.generateLeafCert(hostname)

        val sanList = leafCert.subjectAlternativeNames
        assertNotNull("Leaf certificate must contain SAN extension", sanList)
        
        var foundSan = false
        for (item in sanList) {
            // item is a List where index 0 is type (2 is DNSName) and index 1 is value
            if (item.size >= 2 && item[0] == 2 && item[1] == hostname) {
                foundSan = true
                break
            }
        }
        assertTrue("Subject Alternative Name should match hostname '$hostname'", foundSan)
    }

    @Test
    fun testGenerateLeafCert_cachesCertificates() {
        val hostname = "rethinkdns.com"
        val firstCert = CertificateAuthority.generateLeafCert(hostname)
        val secondCert = CertificateAuthority.generateLeafCert(hostname)

        assertSame("Subsequent calls for the same hostname should return the same cached certificate instance", firstCert, secondCert)
    }

    @Test
    fun testResetCA_clearsCA() {
        val hostname = "yahoo.com"
        val cert1 = CertificateAuthority.generateLeafCert(hostname)

        CertificateAuthority.resetCA()
        CertificateAuthority.initializeCA()

        val cert2 = CertificateAuthority.generateLeafCert(hostname)
        assertNotSame("After reset, dynamic certificates should be regenerated with a new root", cert1, cert2)
    }

    @Test
    fun testGenerateLeafKeyAndCert_returnsValidKeyAndCert() {
        val hostname = "google.com"
        val keyAndCert = CertificateAuthority.generateLeafKeyAndCert(hostname)

        assertNotNull("KeyAndCert should not be null", keyAndCert)
        assertNotNull("Private key should not be null", keyAndCert.privateKey)
        assertNotNull("Certificate should not be null", keyAndCert.certificate)
        assertTrue("Subject should contain google.com", keyAndCert.certificate.subjectX500Principal.name.replace(" ", "").contains("CN=google.com"))
    }

    @Test
    fun testGetRootCertificate_returnsRootCertificate() {
        val rootCert = CertificateAuthority.getRootCertificate()
        assertNotNull("Root certificate should not be null", rootCert)
        assertTrue("Root certificate should have basicConstraints >= 0", rootCert.basicConstraints >= 0)
    }
}

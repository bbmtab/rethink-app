package com.celzero.bravedns.core.ca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.TimeZone

class CaCertificateExporterTest {

    @Test
    fun buildDisplayName_hasTimestampPrefixAndExpectedSuffix() {
        val name = CaCertificateExporter.buildDisplayName(
            nowMillis = 0L,
            timeZone = TimeZone.getTimeZone("UTC"),
            nonce = "abcdef12"
        )
        assertEquals("19700101_000000_000_abcdef12_rethinkdns_root_ca.crt", name)
    }

    @Test
    fun buildDisplayName_differentNonceProducesDifferentName() {
        val a = CaCertificateExporter.buildDisplayName(
            nowMillis = 1_700_000_000_000L,
            timeZone = TimeZone.getTimeZone("UTC"),
            nonce = "abcdef12"
        )
        val b = CaCertificateExporter.buildDisplayName(
            nowMillis = 1_700_000_000_000L,
            timeZone = TimeZone.getTimeZone("UTC"),
            nonce = "12345678"
        )
        assertNotEquals(a, b)
    }

    @Test
    fun buildDisplayName_rejectsInvalidNonce_wrongLength() {
        assertThrows(IllegalArgumentException::class.java) {
            CaCertificateExporter.buildDisplayName(
                nowMillis = 0L,
                timeZone = TimeZone.getTimeZone("UTC"),
                nonce = "abc"
            )
        }
    }

    @Test
    fun buildDisplayName_rejectsInvalidNonce_nonHex() {
        assertThrows(IllegalArgumentException::class.java) {
            CaCertificateExporter.buildDisplayName(
                nowMillis = 0L,
                timeZone = TimeZone.getTimeZone("UTC"),
                nonce = "ghijklmn"
            )
        }
    }

    @Test
    fun buildDisplayName_usesProvidedTimezone() {
        val name = CaCertificateExporter.buildDisplayName(
            nowMillis = 0L,
            timeZone = TimeZone.getTimeZone("GMT+07:00"),
            nonce = "abcdef12"
        )
        assertEquals("19700101_070000_000_abcdef12_rethinkdns_root_ca.crt", name)
    }
}

package com.celzero.bravedns.core.proxy.policy

import java.io.ByteArrayInputStream
import java.net.IDN
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionDomainPresetParserTest {
    @Test
    fun commentsBlanksNormalizationAndDuplicatesAreHandled() {
        val result =
            parse(
                """
                // ordinary comment
                ! alternate comment

                 Example.COM.
                example.com
                """
            )

        assertEquals(setOf("example.com"), result.protectedDomains)
        assertTrue(result.protectedDomainsByPackage.isEmpty())
        assertTrue(result.unsupportedRules.isEmpty())
    }

    @Test
    fun packageBindingsAreGroupedWithoutBecomingGlobal() {
        val result =
            parse(
                """
                api.example${'$'}app=com.Example.App
                cdn.example${'$'}app=com.example.app
                api.example${'$'}app=com.example.app
                """
            )

        assertTrue(result.protectedDomains.isEmpty())
        assertEquals(
            mapOf(
                "com.example.app" to
                    setOf("api.example", "cdn.example")
            ),
            result.protectedDomainsByPackage
        )
        assertTrue(result.unsupportedRules.isEmpty())
    }

    @Test
    fun globalAndPackageScopedFormsRemainDistinct() {
        val result =
            parse(
                """
                shared.example
                shared.example${'$'}app=com.example.bound
                """
            )

        assertEquals(
            setOf("shared.example"),
            result.protectedDomains
        )
        assertEquals(
            mapOf(
                "com.example.bound" to setOf("shared.example")
            ),
            result.protectedDomainsByPackage
        )
    }

    @Test
    fun wildcardIsReportedInsteadOfSilentlyMisparsed() {
        val result = parse("ping.*.adguard.io")

        assertTrue(result.protectedDomains.isEmpty())
        assertTrue(result.protectedDomainsByPackage.isEmpty())
        assertEquals(
            listOf(
                UnsupportedInspectionDomainRule(
                    lineNumber = 1,
                    rawRule = "ping.*.adguard.io",
                    issue =
                        InspectionDomainRuleIssue.UNSUPPORTED_WILDCARD
                )
            ),
            result.unsupportedRules
        )
    }

    @Test
    fun malformedAndUnknownModifierRulesAreReported() {
        val result =
            parse(
                """
                https://bad.example
                example.com${'$'}foo=bar
                example.com${'$'}app=
                """
            )

        assertEquals(
            listOf(
                InspectionDomainRuleIssue.MALFORMED_RULE,
                InspectionDomainRuleIssue.UNSUPPORTED_MODIFIER,
                InspectionDomainRuleIssue.MALFORMED_RULE
            ),
            result.unsupportedRules.map { it.issue }
        )
        assertEquals(
            listOf(1, 2, 3),
            result.unsupportedRules.map { it.lineNumber }
        )
    }

    @Test
    fun internationalDomainIsNormalizedToAscii() {
        val unicodeDomain = "onlinebanking-hüttenberger-bank.de"
        val result = parse(unicodeDomain)

        assertEquals(
            setOf(
                IDN.toASCII(
                    unicodeDomain,
                    IDN.USE_STD3_ASCII_RULES
                ).lowercase(Locale.US)
            ),
            result.protectedDomains
        )
        assertTrue(result.unsupportedRules.isEmpty())
    }

    private fun parse(text: String): InspectionDomainPreset =
        InspectionDomainPresetParser.parse(
            ByteArrayInputStream(
                text.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
        )
}
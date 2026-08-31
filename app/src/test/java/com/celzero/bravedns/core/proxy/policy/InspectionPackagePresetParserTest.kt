package com.celzero.bravedns.core.proxy.policy

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionPackagePresetParserTest {
    private val parser = InspectionPackagePresetParser()

    @Test
    fun packageListSkipsCommentsNormalizesAndDeduplicates() {
        val preset =
            parser.parsePackages(
                input(
                    """
                    // browser comment
                    com.Example.Browser

                    ! generated-list comment
                    com.example.browser
                    org.example.Second
                    """.trimIndent()
                )
            )

        assertEquals(
            setOf("com.example.browser", "org.example.second"),
            preset.packages
        )
        assertTrue(preset.uids.isEmpty())
        assertTrue(preset.unsupportedRules.isEmpty())
    }

    @Test
    fun packageAndUidListKeepsTheTwoKindsDistinct() {
        val preset =
            parser.parsePackagesAndUids(
                input(
                    """
                    // system identities
                    1001
                    com.android.providers.downloads
                    1000
                    COM.EXAMPLE.SYSTEM
                    """.trimIndent()
                )
            )

        assertEquals(
            setOf(
                "com.android.providers.downloads",
                "com.example.system"
            ),
            preset.packages
        )
        assertEquals(setOf(1001, 1000), preset.uids)
        assertTrue(preset.unsupportedRules.isEmpty())
    }

    @Test
    fun packageOnlyListDoesNotSilentlyTreatUidAsPackage() {
        val preset =
            parser.parsePackages(
                input(
                    """
                    com.example.browser
                    1001
                    """.trimIndent()
                )
            )

        assertEquals(setOf("com.example.browser"), preset.packages)
        assertTrue(preset.uids.isEmpty())
        assertEquals(
            listOf(
                UnsupportedInspectionPackageRule(
                    lineNumber = 2,
                    rawRule = "1001",
                    issue = InspectionPackageRuleIssue.UNEXPECTED_UID
                )
            ),
            preset.unsupportedRules
        )
    }

    @Test
    fun malformedPackageIsRetainedAsDiagnostic() {
        val preset =
            parser.parsePackages(
                input(
                    """
                    com.example.valid
                    invalid package
                    singleSegment
                    com..broken
                    """.trimIndent()
                )
            )

        assertEquals(setOf("com.example.valid"), preset.packages)
        assertEquals(
            listOf(
                UnsupportedInspectionPackageRule(
                    lineNumber = 2,
                    rawRule = "invalid package",
                    issue = InspectionPackageRuleIssue.MALFORMED_PACKAGE
                ),
                UnsupportedInspectionPackageRule(
                    lineNumber = 3,
                    rawRule = "singleSegment",
                    issue = InspectionPackageRuleIssue.MALFORMED_PACKAGE
                ),
                UnsupportedInspectionPackageRule(
                    lineNumber = 4,
                    rawRule = "com..broken",
                    issue = InspectionPackageRuleIssue.MALFORMED_PACKAGE
                )
            ),
            preset.unsupportedRules
        )
    }

    @Test
    fun invalidUidIsRetainedAsDiagnostic() {
        val preset =
            parser.parsePackagesAndUids(
                input(
                    """
                    -1
                    999999999999999999999
                    +42
                    """.trimIndent()
                )
            )

        assertTrue(preset.packages.isEmpty())
        assertTrue(preset.uids.isEmpty())
        assertEquals(
            listOf(
                UnsupportedInspectionPackageRule(
                    lineNumber = 1,
                    rawRule = "-1",
                    issue = InspectionPackageRuleIssue.MALFORMED_UID
                ),
                UnsupportedInspectionPackageRule(
                    lineNumber = 2,
                    rawRule = "999999999999999999999",
                    issue = InspectionPackageRuleIssue.MALFORMED_UID
                ),
                UnsupportedInspectionPackageRule(
                    lineNumber = 3,
                    rawRule = "+42",
                    issue = InspectionPackageRuleIssue.MALFORMED_UID
                )
            ),
            preset.unsupportedRules
        )
    }

    @Test
    fun duplicatePackagesAndUidsPreserveFirstSeenOrder() {
        val preset =
            parser.parsePackagesAndUids(
                input(
                    """
                    com.example.first
                    1001
                    COM.EXAMPLE.FIRST
                    com.example.second
                    1001
                    1000
                    """.trimIndent()
                )
            )

        assertEquals(
            listOf("com.example.first", "com.example.second"),
            preset.packages.toList()
        )
        assertEquals(listOf(1001, 1000), preset.uids.toList())
        assertTrue(preset.unsupportedRules.isEmpty())
    }

    private fun input(value: String): ByteArrayInputStream =
        ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))
}
package com.celzero.bravedns.core.proxy.policy

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionAppExclusionPresetParserTest {
    private val parser = InspectionAppExclusionPresetParser()

    @Test
    fun validEntriesNormalizePackagesAndPreserveMetadata() {
        val preset =
            parser.parse(
                input(
                    """
                    [
                      {
                        "package_name": "Com.Example.App",
                        "public_issue_url": " https://example.test/public ",
                        "private_issue_url": null,
                        "comment": " breaks under inspection ",
                        "public": true
                      },
                      {
                        "package_name": "org.example.second",
                        "public": false
                      }
                    ]
                    """.trimIndent()
                )
            )

        assertEquals(
            listOf(
                InspectionAppExclusionRule(
                    packageName = "com.example.app",
                    publicIssueUrl = "https://example.test/public",
                    privateIssueUrl = null,
                    comment = "breaks under inspection",
                    isPublic = true
                ),
                InspectionAppExclusionRule(
                    packageName = "org.example.second",
                    publicIssueUrl = null,
                    privateIssueUrl = null,
                    comment = null,
                    isPublic = false
                )
            ),
            preset.rules
        )
        assertEquals(
            setOf("com.example.app", "org.example.second"),
            preset.excludedPackages
        )
        assertTrue(preset.diagnostics.isEmpty())
    }

    @Test
    fun unknownFieldIsDiagnosedWithoutDiscardingValidEntry() {
        val preset =
            parser.parse(
                input(
                    """
                    [
                      {
                        "package_name": "com.example.app",
                        "public": true,
                        "future_flag": true
                      }
                    ]
                    """.trimIndent()
                )
            )

        assertEquals(setOf("com.example.app"), preset.excludedPackages)
        assertEquals(
            listOf(
                InspectionAppExclusionDiagnostic(
                    entryIndex = 1,
                    fieldName = "future_flag",
                    rawValue = "true",
                    issue = InspectionAppExclusionIssue.UNKNOWN_FIELD
                )
            ),
            preset.diagnostics
        )
    }

    @Test
    fun malformedRequiredFieldsRejectEntriesWithDiagnostics() {
        val preset =
            parser.parse(
                input(
                    """
                    [
                      {},
                      {
                        "package_name": "singleSegment",
                        "public": true
                      },
                      {
                        "package_name": "com.example.missing"
                      },
                      {
                        "package_name": "com.example.bad",
                        "public": "true"
                      }
                    ]
                    """.trimIndent()
                )
            )

        assertTrue(preset.rules.isEmpty())
        assertTrue(preset.excludedPackages.isEmpty())
        assertEquals(
            listOf(
                InspectionAppExclusionIssue.MISSING_PACKAGE_NAME,
                InspectionAppExclusionIssue.MALFORMED_PACKAGE,
                InspectionAppExclusionIssue.MISSING_PUBLIC_FLAG,
                InspectionAppExclusionIssue.MALFORMED_PUBLIC_FLAG
            ),
            preset.diagnostics.map { it.issue }
        )
    }

    @Test
    fun malformedOptionalFieldRejectsEntry() {
        val preset =
            parser.parse(
                input(
                    """
                    [
                      {
                        "package_name": "com.example.app",
                        "public_issue_url": 7,
                        "public": true
                      }
                    ]
                    """.trimIndent()
                )
            )

        assertTrue(preset.rules.isEmpty())
        assertEquals(
            listOf(
                InspectionAppExclusionDiagnostic(
                    entryIndex = 1,
                    fieldName = "public_issue_url",
                    rawValue = "7",
                    issue =
                        InspectionAppExclusionIssue.MALFORMED_OPTIONAL_FIELD
                )
            ),
            preset.diagnostics
        )
    }

    @Test
    fun duplicatePackageKeepsFirstValidEntryAndDiagnosesLaterEntry() {
        val preset =
            parser.parse(
                input(
                    """
                    [
                      {
                        "package_name": "com.example.app",
                        "comment": "first",
                        "public": true
                      },
                      {
                        "package_name": "COM.EXAMPLE.APP",
                        "comment": "second",
                        "public": false
                      }
                    ]
                    """.trimIndent()
                )
            )

        assertEquals(1, preset.rules.size)
        assertEquals("first", preset.rules.single().comment)
        assertEquals(setOf("com.example.app"), preset.excludedPackages)
        assertEquals(
            listOf(
                InspectionAppExclusionDiagnostic(
                    entryIndex = 2,
                    fieldName = "package_name",
                    rawValue = "com.example.app",
                    issue = InspectionAppExclusionIssue.DUPLICATE_PACKAGE
                )
            ),
            preset.diagnostics
        )
    }

    @Test
    fun malformedDocumentRootAndEntryAreDiagnosed() {
        assertEquals(
            InspectionAppExclusionIssue.MALFORMED_JSON,
            parser.parse(input("[")).diagnostics.single().issue
        )
        assertEquals(
            InspectionAppExclusionIssue.ROOT_NOT_ARRAY,
            parser.parse(input("{}")).diagnostics.single().issue
        )
        assertEquals(
            InspectionAppExclusionIssue.ENTRY_NOT_OBJECT,
            parser.parse(input("[7]")).diagnostics.single().issue
        )
    }

    private fun input(value: String): ByteArrayInputStream =
        ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))
}

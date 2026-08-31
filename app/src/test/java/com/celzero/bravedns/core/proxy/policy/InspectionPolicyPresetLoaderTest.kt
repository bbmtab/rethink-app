package com.celzero.bravedns.core.proxy.policy

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InspectionPolicyPresetLoaderTest {
    @Test
    fun loadMapsEverySourceToItsExactBundleFieldAndClosesStreams() {
        val openedPaths = mutableListOf<String>()
        val openedStreams = mutableListOf<CloseTrackingInputStream>()
        val payloads =
            mapOf(
                InspectionPolicyPresetLoader.SYSTEM_HARD_BYPASS_ASSET_PATH to
                    "1000\ncom.example.system\n",
                InspectionPolicyPresetLoader.PROTECTED_DOMAIN_BYPASS_ASSET_PATH to
                    "protected.example\nbound.example\$app=com.example.bound\n",
                InspectionPolicyPresetLoader.KNOWN_BROWSERS_ASSET_PATH to
                    "com.Example.Browser\n"
            )
        val loader =
            InspectionPolicyPresetLoader(
                InspectionPolicyPresetSource { assetPath ->
                    openedPaths += assetPath
                    val stream =
                        CloseTrackingInputStream(
                            payloads
                                .getValue(assetPath)
                                .toByteArray(Charsets.UTF_8)
                        )
                    openedStreams += stream
                    stream
                }
            )

        val bundle = loader.load()

        assertEquals(
            InspectionPackagePreset(
                packages = setOf("com.example.system"),
                uids = setOf(1000),
                unsupportedRules = emptyList()
            ),
            bundle.systemHardBypass
        )
        assertEquals(
            InspectionDomainPreset(
                protectedDomains = setOf("protected.example"),
                protectedDomainsByPackage =
                    mapOf(
                        "com.example.bound" to
                            setOf("bound.example")
                    ),
                unsupportedRules = emptyList()
            ),
            bundle.protectedDomainBypass
        )
        assertEquals(
            InspectionPackagePreset(
                packages = setOf("com.example.browser"),
                uids = emptySet(),
                unsupportedRules = emptyList()
            ),
            bundle.knownBrowsers
        )
        assertEquals(
            listOf(
                InspectionPolicyPresetLoader.SYSTEM_HARD_BYPASS_ASSET_PATH,
                InspectionPolicyPresetLoader.PROTECTED_DOMAIN_BYPASS_ASSET_PATH,
                InspectionPolicyPresetLoader.KNOWN_BROWSERS_ASSET_PATH
            ),
            openedPaths
        )
        assertTrue(openedStreams.all { it.closed })
    }

    @Test
    fun loadPreservesDiagnosticsFromEveryParserMode() {
        val payloads =
            mapOf(
                InspectionPolicyPresetLoader.SYSTEM_HARD_BYPASS_ASSET_PATH to
                    "+42\n",
                InspectionPolicyPresetLoader.PROTECTED_DOMAIN_BYPASS_ASSET_PATH to
                    "ping.*.adguard.io\n",
                InspectionPolicyPresetLoader.KNOWN_BROWSERS_ASSET_PATH to
                    "1001\n"
            )
        val loader =
            InspectionPolicyPresetLoader(
                InspectionPolicyPresetSource { assetPath ->
                    ByteArrayInputStream(
                        payloads
                            .getValue(assetPath)
                            .toByteArray(Charsets.UTF_8)
                    )
                }
            )

        val bundle = loader.load()

        assertEquals(
            listOf(InspectionPackageRuleIssue.MALFORMED_UID),
            bundle.systemHardBypass.unsupportedRules.map { it.issue }
        )
        assertEquals(
            listOf(InspectionDomainRuleIssue.UNSUPPORTED_WILDCARD),
            bundle.protectedDomainBypass.unsupportedRules.map { it.issue }
        )
        assertEquals(
            listOf(InspectionPackageRuleIssue.UNEXPECTED_UID),
            bundle.knownBrowsers.unsupportedRules.map { it.issue }
        )
    }

    @Test
    fun missingSourceFailurePropagatesWithoutOpeningLaterSources() {
        val openedPaths = mutableListOf<String>()
        var systemStream: CloseTrackingInputStream? = null
        val loader =
            InspectionPolicyPresetLoader(
                InspectionPolicyPresetSource { assetPath ->
                    openedPaths += assetPath
                    when (assetPath) {
                        InspectionPolicyPresetLoader.SYSTEM_HARD_BYPASS_ASSET_PATH ->
                            CloseTrackingInputStream(
                                "1000\n".toByteArray(Charsets.UTF_8)
                            ).also { systemStream = it }

                        InspectionPolicyPresetLoader.PROTECTED_DOMAIN_BYPASS_ASSET_PATH ->
                            throw FileNotFoundException(assetPath)

                        else ->
                            throw AssertionError(
                                "A later source must not be opened"
                            )
                    }
                }
            )

        try {
            loader.load()
            fail("Expected FileNotFoundException")
        } catch (failure: FileNotFoundException) {
            assertEquals(
                InspectionPolicyPresetLoader.PROTECTED_DOMAIN_BYPASS_ASSET_PATH,
                failure.message
            )
        }

        assertEquals(
            listOf(
                InspectionPolicyPresetLoader.SYSTEM_HARD_BYPASS_ASSET_PATH,
                InspectionPolicyPresetLoader.PROTECTED_DOMAIN_BYPASS_ASSET_PATH
            ),
            openedPaths
        )
        assertTrue(systemStream?.closed == true)
    }

    private class CloseTrackingInputStream(
        bytes: ByteArray
    ) : ByteArrayInputStream(bytes) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
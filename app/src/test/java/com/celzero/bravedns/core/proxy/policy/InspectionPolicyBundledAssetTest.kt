package com.celzero.bravedns.core.proxy.policy

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InspectionPolicyBundledAssetTest {
    @Test
    fun bundledProtectedDomainPresetMatchesPinnedUpstreamArtifact() {
        val asset =
            findBundledAsset(
                "https_inspection/https_inspection_protected_domains_plus"
            )

        assertEquals(85173L, asset.length())
        assertEquals(
            "cf2699dbd93b9a3c6a94e1927bd58803ffbaa61ef2a814df36858b37652ad856",
            sha256(asset)
        )
        assertEquals(4569, asset.readLines(Charsets.UTF_8).size)

        val preset =
            asset.inputStream().use { input ->
                InspectionDomainPresetParser.parse(input)
            }

        assertEquals(4308, preset.protectedDomains.size)
        assertEquals(27, preset.protectedDomainsByPackage.size)
        assertEquals(
            51,
            preset.protectedDomainsByPackage.values.sumOf {
                domains -> domains.size
            }
        )
        assertEquals(
            listOf(
                UnsupportedInspectionDomainRule(
                    lineNumber = 4302,
                    rawRule = "\"lastpass.com\"",
                    issue = InspectionDomainRuleIssue.MALFORMED_RULE
                ),
                UnsupportedInspectionDomainRule(
                    lineNumber = 4542,
                    rawRule = "ping.*.adguard.io",
                    issue =
                        InspectionDomainRuleIssue.UNSUPPORTED_WILDCARD
                )
            ),
            preset.unsupportedRules
        )
        assertTrue("lastpass.com" in preset.protectedDomains)
        assertEquals(
            setOf("facebook.com"),
            preset.protectedDomainsByPackage[
                "com.instagram.android"
            ]
        )
    }

    @Test
    fun bundledNoticePinsSourceLicenseAndArtifact() {
        val notice =
            findBundledAsset(
                "https_inspection/NOTICE.txt"
            ).readText(Charsets.UTF_8)

        val requiredStatements =
            listOf(
                "Upstream author: AdGuard",
                "Upstream license declaration: MIT",
                "SPDX-License-Identifier: MIT",
                "Pinned commit: 5d3e4ca4b79958e28e30c8cc48a9e0be95c813b8",
                "SHA-256: cf2699dbd93b9a3c6a94e1927bd58803ffbaa61ef2a814df36858b37652ad856",
                "Git blob (raw bytes): 83d53badac61f0401b9dfbb9423dedb0e8fb9e22",
                "Byte count: 85173",
                "Logical line count: 4569",
                "Local modifications: none",
                "Standalone upstream LICENSE/COPYING/NOTICE file at pinned commit: none"
            )

        requiredStatements.forEach { statement ->
            assertTrue(
                "NOTICE.txt must contain: $statement",
                notice.contains(statement)
            )
        }
    }

    @Test
    fun bundledSystemHardBypassPresetMatchesPinnedArtifact() {
        val asset =
            findBundledAsset(
                "https_inspection/https_inspection_package_bypass_plus"
            )

        assertEquals(656L, asset.length())
        assertEquals(
            "81e950c9765ab1c7833a34dcdf9e0073facdd14e4756a52afaec98f1ee3cc9ec",
            sha256(asset)
        )

        val preset =
            asset.inputStream().use { input ->
                InspectionPackagePresetParser().parsePackagesAndUids(input)
            }

        assertEquals(
            setOf(1000, 1001),
            preset.uids
        )
        assertEquals(
            setOf(
                "com.android.providers.downloads",
                "com.android.providers.downloads.ui",
                "com.celzero.bravedns.plus",
                "com.coloros.providers.downloads.ui"
            ),
            preset.packages
        )
        assertTrue(preset.unsupportedRules.isEmpty())
    }

    @Test
    fun bundledKnownBrowserPresetMatchesPinnedArtifact() {
        val asset =
            findBundledAsset(
                "https_inspection/https_inspection_known_browsers_plus"
            )

        assertEquals(1415L, asset.length())
        assertEquals(
            "4821ffae9fd3a41ad1ae106ff030671e0398e4a18c155228b3f5d5c6be23e795",
            sha256(asset)
        )

        val preset =
            asset.inputStream().use { input ->
                InspectionPackagePresetParser().parsePackages(input)
            }

        assertEquals(60, preset.packages.size)
        assertTrue(preset.uids.isEmpty())
        assertTrue(preset.unsupportedRules.isEmpty())
        assertTrue("com.android.chrome" in preset.packages)
        assertTrue("com.brave.browser" in preset.packages)
        assertTrue("org.mozilla.firefox" in preset.packages)
        assertTrue("com.microsoft.emmx" in preset.packages)
    }

    @Test
    fun bundledCompatibilityExclusionsMatchPinnedArtifact() {
        val asset =
            findBundledAsset(
                "https_inspection/https_inspection_compatibility_bypass_plus.json"
            )

        assertEquals(52396L, asset.length())
        assertEquals(
            "ac2735cd5fc0e947b74249d4f59e10cd18e421aa4755d03a70ec8c8ddd3b95f9",
            sha256(asset)
        )

        val preset =
            asset.inputStream().use { input ->
                InspectionAppExclusionPresetParser().parse(input)
            }

        assertEquals(229, preset.rules.size)
        assertEquals(229, preset.excludedPackages.size)
        assertTrue(preset.diagnostics.isEmpty())
        assertTrue(preset.rules.all { it.isPublic })
        assertEquals(
            188,
            preset.rules.count { it.publicIssueUrl != null }
        )
    }

    @Test
    fun bundledIncludedDomainPresetMatchesPinnedArtifact() {
        val asset =
            findBundledAsset(
                "https_inspection/https_inspection_domain_targets_plus"
            )

        assertEquals(811L, asset.length())
        assertEquals(
            "182d55f35f3fff793b3008d73276f14fc6474cd8044a8aac1b7d7ec624141d5b",
            sha256(asset)
        )

        val preset =
            asset.inputStream().use { input ->
                InspectionDomainPresetParser.parse(input)
            }

        assertEquals(
            setOf(
                "googleapis.com",
                "graph.facebook.com",
                "doubleclick.net",
                "googleadservices.com"
            ),
            preset.protectedDomains
        )
        assertTrue(preset.protectedDomainsByPackage.isEmpty())
        assertTrue(preset.unsupportedRules.isEmpty())
    }

    private fun findBundledAsset(relativePath: String): File {
        val candidates =
            listOf(
                File("app/src/main/assets", relativePath),
                File("src/main/assets", relativePath),
                File("../app/src/main/assets", relativePath),
                File("../../app/src/main/assets", relativePath)
            )

        candidates.firstOrNull { it.isFile }?.let { return it }

        fail(
            "Could not locate bundled asset $relativePath at any of: " +
                candidates.joinToString { it.absolutePath }
        )
        return File("")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }

        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}

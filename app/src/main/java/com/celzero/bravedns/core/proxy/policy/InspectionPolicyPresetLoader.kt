package com.celzero.bravedns.core.proxy.policy

import java.io.InputStream

fun interface InspectionPolicyPresetSource {
    fun open(assetPath: String): InputStream
}

class InspectionPolicyPresetLoader(
    private val source: InspectionPolicyPresetSource,
    private val packageParser: InspectionPackagePresetParser =
        InspectionPackagePresetParser(),
    private val appExclusionParser: InspectionAppExclusionPresetParser =
        InspectionAppExclusionPresetParser()
) {
    fun load(): InspectionPolicyPresetBundle {
        val systemHardBypass =
            packageParser.parsePackagesAndUids(
                source.open(SYSTEM_HARD_BYPASS_ASSET_PATH)
            )
        val protectedDomainBypass =
            InspectionDomainPresetParser.parse(
                source.open(PROTECTED_DOMAIN_BYPASS_ASSET_PATH)
            )
        val knownBrowsers =
            packageParser.parsePackages(
                source.open(KNOWN_BROWSERS_ASSET_PATH)
            )
        val compatibilityExclusions =
            appExclusionParser.parse(
                source.open(COMPATIBILITY_EXCLUSIONS_ASSET_PATH)
            )
        val includedDomainMitm =
            InspectionDomainPresetParser.parse(
                source.open(INCLUDED_DOMAIN_MITM_ASSET_PATH)
            )

        return InspectionPolicyPresetBundle(
            systemHardBypass = systemHardBypass,
            protectedDomainBypass = protectedDomainBypass,
            knownBrowsers = knownBrowsers,
            compatibilityExclusions = compatibilityExclusions,
            includedDomainMitm = includedDomainMitm
        )
    }

    companion object {
        const val SYSTEM_HARD_BYPASS_ASSET_PATH =
            "https_inspection/https_inspection_package_bypass_plus"

        const val PROTECTED_DOMAIN_BYPASS_ASSET_PATH =
            "https_inspection/https_inspection_protected_domains_plus"

        const val KNOWN_BROWSERS_ASSET_PATH =
            "https_inspection/https_inspection_known_browsers_plus"

        const val COMPATIBILITY_EXCLUSIONS_ASSET_PATH =
            "https_inspection/https_inspection_compatibility_bypass_plus.json"

        const val INCLUDED_DOMAIN_MITM_ASSET_PATH =
            "https_inspection/https_inspection_domain_targets_plus"
    }
}

package com.celzero.bravedns.core.proxy.policy

import java.io.InputStream

fun interface InspectionPolicyPresetSource {
    fun open(assetPath: String): InputStream
}

class InspectionPolicyPresetLoader(
    private val source: InspectionPolicyPresetSource,
    private val packageParser: InspectionPackagePresetParser =
        InspectionPackagePresetParser()
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

        return InspectionPolicyPresetBundle(
            systemHardBypass = systemHardBypass,
            protectedDomainBypass = protectedDomainBypass,
            knownBrowsers = knownBrowsers
        )
    }

    companion object {
        const val SYSTEM_HARD_BYPASS_ASSET_PATH =
            "https_inspection/pkg_exclusions.txt"

        const val PROTECTED_DOMAIN_BYPASS_ASSET_PATH =
            "https_inspection/ssl_allow_list.txt"

        const val KNOWN_BROWSERS_ASSET_PATH =
            "https_inspection/filter_https_traffic_inclusions.txt"
    }
}
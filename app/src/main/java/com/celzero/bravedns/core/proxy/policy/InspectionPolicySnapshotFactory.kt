package com.celzero.bravedns.core.proxy.policy

data class InspectionPolicyPresetBundle(
    val systemHardBypass: InspectionPackagePreset,
    val protectedDomainBypass: InspectionDomainPreset,
    val knownBrowsers: InspectionPackagePreset,
    val compatibilityExclusions: InspectionAppExclusionPreset =
        InspectionAppExclusionPreset(
            rules = emptyList(),
            excludedPackages = emptySet(),
            diagnostics = emptyList()
        ),
    val includedDomainMitm: InspectionDomainPreset =
        InspectionDomainPreset(
            protectedDomains = emptySet(),
            protectedDomainsByPackage = emptyMap(),
            unsupportedRules = emptyList()
        )
)

data class InspectionPolicySnapshotBuildResult(
    val snapshot: InspectionPolicySnapshot,
    val sourceBundle: InspectionPolicyPresetBundle
)

class InspectionPolicySnapshotFactory {
    fun create(
        bundle: InspectionPolicyPresetBundle,
        userExcludedPackages: Set<String> = emptySet(),
        protectedAppPorts: Map<String, Set<Int>> = emptyMap(),
        userIncludedPackages: Set<String> = emptySet(),
        enabledDynamicBrowserPackages: Set<String> = emptySet(),
        domainMode: InspectionDomainMode =
            InspectionDomainMode.ALL_EXCEPT_PROTECTED
    ): InspectionPolicySnapshotBuildResult =
        InspectionPolicySnapshotBuildResult(
            snapshot =
                InspectionPolicySnapshot(
                    systemHardBypassPackages =
                        bundle.systemHardBypass.packages,
                    systemHardBypassUids =
                        bundle.systemHardBypass.uids,
                    userExcludedPackages = userExcludedPackages,
                    compatibilityExcludedPackages =
                        bundle.compatibilityExclusions.excludedPackages,
                    domainMode = domainMode,
                    protectedDomains =
                        bundle.protectedDomainBypass.protectedDomains,
                    protectedDomainsByPackage =
                        bundle
                            .protectedDomainBypass
                            .protectedDomainsByPackage,
                    includedDomains =
                        bundle.includedDomainMitm.protectedDomains,
                    protectedAppPorts = protectedAppPorts,
                    knownBrowserPackages =
                        bundle.knownBrowsers.packages +
                            InspectionKnownBrowserRegistry.packages,
                    userIncludedPackages = userIncludedPackages,
                    enabledDynamicBrowserPackages =
                        enabledDynamicBrowserPackages
                ),
            sourceBundle = bundle
        )
}

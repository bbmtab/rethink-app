package com.celzero.bravedns.core.proxy.policy

data class InspectionPolicyPresetBundle(
    val systemHardBypass: InspectionPackagePreset,
    val protectedDomainBypass: InspectionDomainPreset,
    val knownBrowsers: InspectionPackagePreset
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
        enabledDynamicBrowserPackages: Set<String> = emptySet()
    ): InspectionPolicySnapshotBuildResult =
        InspectionPolicySnapshotBuildResult(
            snapshot =
                InspectionPolicySnapshot(
                    systemHardBypassPackages =
                        bundle.systemHardBypass.packages,
                    systemHardBypassUids =
                        bundle.systemHardBypass.uids,
                    userExcludedPackages = userExcludedPackages,
                    protectedDomains =
                        bundle.protectedDomainBypass.protectedDomains,
                    protectedDomainsByPackage =
                        bundle
                            .protectedDomainBypass
                            .protectedDomainsByPackage,
                    protectedAppPorts = protectedAppPorts,
                    knownBrowserPackages =
                        bundle.knownBrowsers.packages,
                    userIncludedPackages = userIncludedPackages,
                    enabledDynamicBrowserPackages =
                        enabledDynamicBrowserPackages
                ),
            sourceBundle = bundle
        )
}
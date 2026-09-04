package com.celzero.bravedns.core.proxy.policy

data class InspectionBrowserRuntimePackages(
  val knownBrowserPackages: Set<String>,
  val enabledDynamicBrowserPackages: Set<String>,
) {
  val allowedPackages: Set<String>
    get() = knownBrowserPackages + enabledDynamicBrowserPackages
}

object InspectionBrowserRuntimePackageResolver {
  fun resolve(
    capabilities: Iterable<InspectionBrowserCapability>,
    selfPackageName: String,
    additionalDeniedPackages: Set<String> = emptySet(),
  ): InspectionBrowserRuntimePackages {
    val enabledDynamicBrowserPackages =
      InspectionDynamicBrowserClassifier.classify(
        capabilities,
        selfPackageName,
        additionalDeniedPackages,
      )

    return InspectionBrowserRuntimePackages(
      knownBrowserPackages = InspectionKnownBrowserRegistry.packages,
      enabledDynamicBrowserPackages = enabledDynamicBrowserPackages,
    )
  }
}
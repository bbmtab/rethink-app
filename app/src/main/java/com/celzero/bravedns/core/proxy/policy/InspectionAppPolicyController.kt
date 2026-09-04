package com.celzero.bravedns.core.proxy.policy

import java.util.Locale

enum class InspectionAppPolicyTier {
    KNOWN_BROWSER,
    DYNAMIC_BROWSER,
    OTHER
}

data class InspectionAppToggleState(
    val tier: InspectionAppPolicyTier,
    val enabled: Boolean
)

class InspectionAppPolicyController(
    private val repository: InspectionUserAppPolicyRepository
) {
    fun stateFor(
        packageName: String,
        browserPackages: InspectionBrowserRuntimePackages
    ): InspectionAppToggleState {
        val normalizedPackage = normalizePackage(packageName)

        if (normalizedPackage.isEmpty()) {
            return InspectionAppToggleState(
                tier = InspectionAppPolicyTier.OTHER,
                enabled = false
            )
        }

        val knownBrowsers =
            browserPackages.knownBrowserPackages
                .mapTo(mutableSetOf(), ::normalizePackage)

        val dynamicBrowsers =
            browserPackages.enabledDynamicBrowserPackages
                .mapTo(mutableSetOf(), ::normalizePackage)

        val tier =
            when {
                normalizedPackage in knownBrowsers ->
                    InspectionAppPolicyTier.KNOWN_BROWSER

                normalizedPackage in dynamicBrowsers ->
                    InspectionAppPolicyTier.DYNAMIC_BROWSER

                else ->
                    InspectionAppPolicyTier.OTHER
            }

        val userPolicy = repository.snapshot()

        val enabled =
            when {
                normalizedPackage in userPolicy.excludedPackages ->
                    false

                tier == InspectionAppPolicyTier.KNOWN_BROWSER ->
                    true

                tier == InspectionAppPolicyTier.DYNAMIC_BROWSER ->
                    true

                normalizedPackage in userPolicy.includedPackages ->
                    true

                else ->
                    false
            }

        return InspectionAppToggleState(
            tier = tier,
            enabled = enabled
        )
    }

    fun setInspectionEnabled(
        packageName: String,
        tier: InspectionAppPolicyTier,
        enabled: Boolean
    ) {
        when (tier) {
            InspectionAppPolicyTier.KNOWN_BROWSER,
            InspectionAppPolicyTier.DYNAMIC_BROWSER ->
                repository.setBrowserInspectionEnabled(
                    packageName = packageName,
                    enabled = enabled
                )

            InspectionAppPolicyTier.OTHER ->
                repository.setNonBrowserInspectionEnabled(
                    packageName = packageName,
                    enabled = enabled
                )
        }
    }

    private fun normalizePackage(packageName: String): String =
        packageName
            .trim()
            .lowercase(Locale.US)
}
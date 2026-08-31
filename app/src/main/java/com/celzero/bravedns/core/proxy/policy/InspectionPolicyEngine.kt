package com.celzero.bravedns.core.proxy.policy

import java.util.Locale

enum class InspectionDecision {
    BYPASS,
    MITM
}

enum class InspectionReason {
    BYPASS_SYSTEM,
    BYPASS_USER,
    BYPASS_DOMAIN,
    BYPASS_APP_PORT,
    MITM_KNOWN_BROWSER,
    MITM_USER_APP,
    MITM_DYNAMIC_BROWSER,
    BYPASS_DEFAULT
}

data class InspectionConnection(
    val packageNames: Set<String>,
    val uid: Int?,
    val host: String,
    val destinationPort: Int
)

data class InspectionPolicySnapshot(
    val systemHardBypassPackages: Set<String> = emptySet(),
    val systemHardBypassUids: Set<Int> = emptySet(),
    val userExcludedPackages: Set<String> = emptySet(),
    val protectedDomains: Set<String> = emptySet(),
    val protectedDomainsByPackage: Map<String, Set<String>> = emptyMap(),
    val protectedAppPorts: Map<String, Set<Int>> = emptyMap(),
    val knownBrowserPackages: Set<String> = emptySet(),
    val userIncludedPackages: Set<String> = emptySet(),
    val enabledDynamicBrowserPackages: Set<String> = emptySet()
)

data class InspectionPolicyResult(
    val decision: InspectionDecision,
    val reason: InspectionReason
)

class InspectionPolicyEngine {
    fun evaluate(
        connection: InspectionConnection,
        policy: InspectionPolicySnapshot
    ): InspectionPolicyResult {
        val packages = connection.packageNames.mapTo(mutableSetOf(), ::normalizePackage)

        if (
            packages.matchesAny(policy.systemHardBypassPackages) ||
                connection.uid?.let(policy.systemHardBypassUids::contains) == true
        ) {
            return bypass(InspectionReason.BYPASS_SYSTEM)
        }

        if (packages.matchesAny(policy.userExcludedPackages)) {
            return bypass(InspectionReason.BYPASS_USER)
        }

        if (
            matchesDomain(connection.host, policy.protectedDomains) ||
                matchesPackageScopedDomain(
                    connection.host,
                    packages,
                    policy.protectedDomainsByPackage
                )
        ) {
            return bypass(InspectionReason.BYPASS_DOMAIN)
        }

        if (
            policy.protectedAppPorts.any { (protectedPackage, ports) ->
                normalizePackage(protectedPackage) in packages &&
                    connection.destinationPort in ports
            }
        ) {
            return bypass(InspectionReason.BYPASS_APP_PORT)
        }

        if (packages.matchesAny(policy.knownBrowserPackages)) {
            return mitm(InspectionReason.MITM_KNOWN_BROWSER)
        }

        if (packages.matchesAny(policy.userIncludedPackages)) {
            return mitm(InspectionReason.MITM_USER_APP)
        }

        if (packages.matchesAny(policy.enabledDynamicBrowserPackages)) {
            return mitm(InspectionReason.MITM_DYNAMIC_BROWSER)
        }

        return bypass(InspectionReason.BYPASS_DEFAULT)
    }

    private fun Set<String>.matchesAny(candidates: Set<String>): Boolean {
        if (isEmpty() || candidates.isEmpty()) return false
        val normalizedCandidates =
            candidates.mapTo(mutableSetOf(), ::normalizePackage)
        return any(normalizedCandidates::contains)
    }

    private fun matchesDomain(
        host: String,
        protectedDomains: Set<String>
    ): Boolean {
        val normalizedHost = normalizeDomain(host)
        if (normalizedHost.isEmpty()) return false

        return protectedDomains.any { candidate ->
            val normalizedCandidate = normalizeDomain(candidate)
            normalizedCandidate.isNotEmpty() &&
                (
                    normalizedHost == normalizedCandidate ||
                        normalizedHost.endsWith(".$normalizedCandidate")
                    )
        }
    }

    private fun matchesPackageScopedDomain(
        host: String,
        packages: Set<String>,
        protectedDomainsByPackage: Map<String, Set<String>>
    ): Boolean {
        if (
            packages.isEmpty() ||
                protectedDomainsByPackage.isEmpty()
        ) {
            return false
        }

        return protectedDomainsByPackage.any {
            (protectedPackage, domains) ->
            normalizePackage(protectedPackage) in packages &&
                matchesDomain(host, domains)
        }
    }

    private fun normalizePackage(packageName: String): String =
        packageName.trim().lowercase(Locale.US)

    private fun normalizeDomain(domain: String): String =
        domain.trim().trimEnd('.').lowercase(Locale.US)

    private fun bypass(reason: InspectionReason): InspectionPolicyResult =
        InspectionPolicyResult(InspectionDecision.BYPASS, reason)

    private fun mitm(reason: InspectionReason): InspectionPolicyResult =
        InspectionPolicyResult(InspectionDecision.MITM, reason)
}
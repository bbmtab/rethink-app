package com.celzero.bravedns.core.proxy.policy

import com.celzero.bravedns.service.PersistentState
import java.util.Locale

data class InspectionUserAppPolicyState(
    val excludedPackages: Set<String>,
    val includedPackages: Set<String>
)

class InspectionUserAppPolicyRepository internal constructor(
    private val storage: Storage
) {

    internal interface Storage {
        var excludedPackagesRaw: String
        var includedPackagesRaw: String
    }

    constructor(persistentState: PersistentState) :
        this(PersistentStateStorage(persistentState))

    @Synchronized
    fun snapshot(): InspectionUserAppPolicyState {
        val excluded = decode(storage.excludedPackagesRaw)
        val included =
            decode(storage.includedPackagesRaw) - excluded

        return InspectionUserAppPolicyState(
            excludedPackages = excluded,
            includedPackages = included
        )
    }

    @Synchronized
    fun excludePackage(packageName: String) {
        val normalized = normalizePackage(packageName)
        if (normalized.isEmpty()) return

        val current = snapshot()
        persist(
            excluded = current.excludedPackages + normalized,
            included = current.includedPackages - normalized
        )
    }

    @Synchronized
    fun includePackage(packageName: String) {
        val normalized = normalizePackage(packageName)
        if (normalized.isEmpty()) return

        val current = snapshot()
        persist(
            excluded = current.excludedPackages - normalized,
            included = current.includedPackages + normalized
        )
    }

    @Synchronized
    fun clearExplicitState(packageName: String) {
        val normalized = normalizePackage(packageName)
        if (normalized.isEmpty()) return

        val current = snapshot()
        persist(
            excluded = current.excludedPackages - normalized,
            included = current.includedPackages - normalized
        )
    }

    fun setBrowserInspectionEnabled(
        packageName: String,
        enabled: Boolean
    ) {
        if (enabled) {
            clearExplicitState(packageName)
        } else {
            excludePackage(packageName)
        }
    }

    fun setNonBrowserInspectionEnabled(
        packageName: String,
        enabled: Boolean
    ) {
        if (enabled) {
            includePackage(packageName)
        } else {
            clearExplicitState(packageName)
        }
    }

    private fun persist(
        excluded: Set<String>,
        included: Set<String>
    ) {
        val canonicalExcluded =
            excluded
                .map(::normalizePackage)
                .filter { it.isNotEmpty() }
                .toSortedSet()

        val canonicalIncluded =
            included
                .map(::normalizePackage)
                .filter { it.isNotEmpty() }
                .toSortedSet() - canonicalExcluded

        storage.excludedPackagesRaw =
            encode(canonicalExcluded)

        storage.includedPackagesRaw =
            encode(canonicalIncluded)
    }

    private fun decode(raw: String): Set<String> =
        raw
            .lineSequence()
            .map(::normalizePackage)
            .filter { it.isNotEmpty() }
            .toCollection(linkedSetOf())

    private fun encode(packages: Set<String>): String =
        packages
            .toSortedSet()
            .joinToString("\n")

    private fun normalizePackage(packageName: String): String =
        packageName
            .trim()
            .lowercase(Locale.US)

    private class PersistentStateStorage(
        private val persistentState: PersistentState
    ) : Storage {
        override var excludedPackagesRaw: String
            get() =
                persistentState
                    .httpsInspectionExcludedPackagesRaw
            set(value) {
                persistentState
                    .httpsInspectionExcludedPackagesRaw = value
            }

        override var includedPackagesRaw: String
            get() =
                persistentState
                    .httpsInspectionIncludedPackagesRaw
            set(value) {
                persistentState
                    .httpsInspectionIncludedPackagesRaw = value
            }
    }
}

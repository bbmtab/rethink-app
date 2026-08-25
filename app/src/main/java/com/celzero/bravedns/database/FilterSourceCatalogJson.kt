/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.database

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.InputStream

/**
 * B5 JSON-CATALOG-S2: Typed, validated Gson parser for the bundled `filters.json` asset.
 *
 * This parser reads the catalog JSON into plain DTOs and validates structural integrity
 * (non-empty groups/filters, positive-unique filterId, HTTPS-unique downloadUrl,
 * resolvable group references, non-blank names). It does NOT map DTOs to Room
 * [FilterSource] entities — that mapping is intentionally deferred to a later slice.
 *
 * Unknown JSON fields are silently tolerated (Gson default behavior), so future
 * schema additions will not break parsing.
 *
 * The parsed catalog is an immutable snapshot; consumers read [Catalog.groups],
 * [Catalog.tags], and [Catalog.filters] directly.
 */
object FilterSourceCatalogJson {

    /**
     * DTO for a filter-grouping category (e.g. "Ad blocking", "Privacy").
     * Only [groupId] and [groupName] are mapped; any extra JSON fields in the
     * group object (e.g. groupDescription, displayNumber) are ignored by Gson.
     */
    data class CatalogGroup(
        @SerializedName("groupId") val groupId: Int,
        @SerializedName("groupName") val groupName: String
    )

    /**
     * DTO for a tag/keyword classification (e.g. "purpose:ads", "lang:en").
     */
    data class CatalogTag(
        @SerializedName("tagId") val tagId: Int,
        @SerializedName("keyword") val keyword: String
    )

    /**
     * DTO for a single filter source entry in the catalog.
     */
    data class CatalogFilter(
        @SerializedName("filterId") val filterId: Int,
        @SerializedName("name") val name: String,
        @SerializedName("description") val description: String?,
        @SerializedName("groupId") val groupId: Int,
        @SerializedName("subscriptionUrl") val subscriptionUrl: String?,
        @SerializedName("downloadUrl") val downloadUrl: String,
        @SerializedName("homepage") val homepage: String?,
        @SerializedName("expires") val expires: Int = 0,
        @SerializedName("version") val version: String?,
        @SerializedName("timeUpdated") val timeUpdated: String?,
        @SerializedName("deprecated") val deprecated: Boolean = false,
        @SerializedName("trustLevel") val trustLevel: String?,
        @SerializedName("languages") val languages: List<String> = emptyList(),
        @SerializedName("tags") val tags: List<Int> = emptyList(),
        @SerializedName("platformsExcluded") val platformsExcluded: List<String>? = null
    ) {
        // Convenience computed from platformsExcluded for callers that need it.
        val hasPlatformsExcluded: Boolean get() = !platformsExcluded.isNullOrEmpty()
    }

    /**
     * Validated in-memory catalog: groups, tags, and filters parsed from JSON.
     */
    data class Catalog(
        @SerializedName("groups") val groups: List<CatalogGroup>,
        @SerializedName("tags") val tags: List<CatalogTag>,
        @SerializedName("filters") val filters: List<CatalogFilter>
    )

    /**
     * Parse the catalog from [inputStream] and validate structural integrity.
     *
     * @throws IllegalArgumentException if any validation rule is violated.
     */
    fun parse(inputStream: InputStream): Catalog = inputStream.use { stream ->
        stream.bufferedReader().use { reader ->
            val catalog = Gson().fromJson(reader, Catalog::class.java)
            validate(catalog)
            catalog
        }
    }

    /**
     * Open and parse the bundled asset at `filter_sources/filters.json`.
     *
     * Uses [Context.getAssets] to locate the file in the APK's asset bundle.
     */
    fun parseFromAssets(context: Context): Catalog =
        context.assets.open("filter_sources/filters.json").use { parse(it) }

    /**
     * Validate the parsed [catalog] against structural rules (B5 JSON-CATALOG-S2):
     *
     * 1. groups must not be empty
     * 2. filters must not be empty
     * 3. filterId must be positive and unique
     * 4. downloadUrl must be non-empty, HTTPS, and unique
     * 5. every filter.groupId must reference an existing group
     * 6. required filter name must be non-blank
     * 7. optional platformsExcluded must parse when present (Gson handles type-safe
     *    deserialization before we reach here; no extra action needed)
     * 8. unknown future JSON fields are tolerated (Gson default, no action here)
     *
     * @throws IllegalArgumentException with a concise reason on the first violation.
     */
    private fun validate(catalog: Catalog) {
        // Rule 1: groups must not be empty
        if (catalog.groups.isEmpty()) {
            throw IllegalArgumentException("catalog groups must not be empty")
        }

        // Rule 2: filters must not be empty
        if (catalog.filters.isEmpty()) {
            throw IllegalArgumentException("catalog filters must not be empty")
        }

        val knownGroupIds = catalog.groups.mapTo(mutableSetOf()) { it.groupId }

        val seenFilterIds = mutableSetOf<Int>()
        val seenDownloadUrls = mutableSetOf<String>()

        for (filter in catalog.filters) {
            // Rule 3a: filterId must be positive
            if (filter.filterId <= 0) {
                throw IllegalArgumentException(
                    "filterId must be positive, got ${filter.filterId} for filter '${filter.name}'"
                )
            }

            // Rule 3b: filterId must be unique
            if (!seenFilterIds.add(filter.filterId)) {
                throw IllegalArgumentException(
                    "duplicate filterId ${filter.filterId} for filter '${filter.name}'"
                )
            }

            // Rule 6: required filter name must be non-blank
            if (filter.name.isBlank()) {
                throw IllegalArgumentException(
                    "filter name must be non-blank for filterId ${filter.filterId}"
                )
            }

            // Rule 4a: downloadUrl must be non-empty
            if (filter.downloadUrl.isBlank()) {
                throw IllegalArgumentException(
                    "downloadUrl must be non-empty for filterId ${filter.filterId}"
                )
            }

            // Rule 4b: downloadUrl must be HTTPS
            if (!filter.downloadUrl.startsWith("https://", ignoreCase = true)) {
                throw IllegalArgumentException(
                    "downloadUrl must be HTTPS for filterId ${filter.filterId}: ${filter.downloadUrl}"
                )
            }

            // Rule 4c: downloadUrl must be unique
            if (!seenDownloadUrls.add(filter.downloadUrl)) {
                throw IllegalArgumentException(
                    "duplicate downloadUrl for filterId ${filter.filterId}: ${filter.downloadUrl}"
                )
            }

            // Rule 5: every filter.groupId must reference an existing group
            if (!knownGroupIds.contains(filter.groupId)) {
                throw IllegalArgumentException(
                    "groupId ${filter.groupId} for filterId ${filter.filterId} references unknown group"
                )
            }
        }
    }
}

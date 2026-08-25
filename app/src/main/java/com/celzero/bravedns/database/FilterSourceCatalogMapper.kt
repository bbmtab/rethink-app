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

/**
 * Pure mapper from the validated bundled filter catalog to preset [FilterSource] candidates.
 *
 * Row identity is stable across catalog metadata changes: the ten existing presets keep their
 * locked local ids, while every other catalog filter uses the reserved generated-id range.
 */
object FilterSourceCatalogMapper {

    private val LEGACY_LOCAL_IDS = mapOf(
        2 to 1,
        204 to 2,
        101 to 3,
        3 to 4,
        118 to 5,
        14 to 6,
        122 to 7,
        123 to -1001,
        257 to -1002,
        102 to -1003
    )

    private val CATEGORY_BY_GROUP_NAME = mapOf(
        "Ad blocking" to FilterSourceCategory.ADS,
        "Privacy" to FilterSourceCategory.PRIVACY,
        "Social widgets" to FilterSourceCategory.SOCIAL,
        "Annoyances" to FilterSourceCategory.ANNOYANCES,
        "Security" to FilterSourceCategory.SECURITY,
        "Other" to FilterSourceCategory.OTHER,
        "Language-specific" to FilterSourceCategory.LANGUAGE_SPECIFIC
    )

    /**
     * Convert [catalog] into preset entity candidates without Android, filesystem, network, or
     * Room access.
     *
     * @throws IllegalArgumentException if catalog identity, URL, or group mapping is ambiguous.
     */
    fun map(catalog: FilterSourceCatalogJson.Catalog): List<FilterSource> {
        val seenReferenceIds = mutableSetOf<Int>()
        val seenUrls = mutableSetOf<String>()
        for (filter in catalog.filters) {
            require(seenReferenceIds.add(filter.filterId)) {
                "duplicate referenceId ${filter.filterId}"
            }
            require(seenUrls.add(filter.downloadUrl)) {
                "duplicate URL for referenceId ${filter.filterId}: ${filter.downloadUrl}"
            }
        }

        val categoryByGroupId = mutableMapOf<Int, String>()
        for (group in catalog.groups) {
            val category = CATEGORY_BY_GROUP_NAME[group.groupName]
                ?: throw IllegalArgumentException(
                    "unmapped catalog group ${group.groupId}: '${group.groupName}'"
                )
            require(categoryByGroupId.put(group.groupId, category) == null) {
                "duplicate catalog groupId ${group.groupId}"
            }
        }

        val localIds = catalog.filters.map { filter ->
            LEGACY_LOCAL_IDS[filter.filterId] ?: (-10000 - filter.filterId)
        }
        require(localIds.toSet().size == localIds.size) {
            "computed local IDs must be unique"
        }

        val output = catalog.filters.zip(localIds).map { (filter, localId) ->
            val category = categoryByGroupId[filter.groupId]
                ?: throw IllegalArgumentException(
                    "unmapped groupId ${filter.groupId} for referenceId ${filter.filterId}"
                )
            FilterSource(
                id = localId,
                referenceId = filter.filterId,
                name = filter.name,
                url = filter.downloadUrl,
                category = category,
                enabled = filter.filterId == 2 || filter.filterId == 204,
                isPreset = true,
                relativeFilePath = "filter_sources/source_${localId}/current.txt"
            )
        }

        require(output.size == catalog.filters.size) {
            "output count ${output.size} differs from input count ${catalog.filters.size}"
        }
        return output
    }
}

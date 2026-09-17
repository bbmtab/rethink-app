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
 * B5 JSON-CATALOG-S4A: Pure Kotlin reconciler that computes a [ReconciliationPlan]
 * from a list of existing [FilterSource] rows and a list of catalog-derived
 * candidate [FilterSource]s.
 *
 * This slice is pure (no Android, Room, filesystem, network, coroutine, or DI).
 * Transaction execution against Room lives in a later slice (S4B).
 *
 * Matching rules (in order):
 * 1. Match an existing preset by the candidate's non-null [FilterSource.referenceId].
 * 2. For legacy rows whose [FilterSource.referenceId] is still null, match by the
 *    candidate's locked local ID only when [FilterSource.isPreset] is true.
 * 3. Never claim or modify a custom row (referenceId == null && isPreset == false).
 * 4. If a candidate local ID is occupied by a custom row or by a preset with a
 *    different non-null referenceId, throw [IllegalArgumentException].
 * 5. Reject duplicate candidate IDs, referenceIds, or URLs.
 * 6. Reject duplicate non-null referenceIds in existing rows.
 * 7. Perform no deletion — existing rows absent from the catalog remain untouched.
 */
object FilterSourceCatalogReconciler {

    /**
     * Fields on [FilterSource] that are catalog-owned metadata and may be overwritten
     * during an update.
     */
    private fun catalogOwnedEqual(a: FilterSource, b: FilterSource): Boolean =
        a.name == b.name &&
            a.url == b.url &&
            a.category == b.category &&
            a.isPreset == b.isPreset &&
            a.referenceId == b.referenceId

    /**
     * Compute which fields of [existing] would change if replaced by [candidate].
     * Returns true when at least one catalog-owned metadata field differs.
     */
    private fun needsUpdate(existing: FilterSource, candidate: FilterSource): Boolean =
        !catalogOwnedEqual(existing, candidate)

    /**
     * Pure reconciliation: produce a [ReconciliationPlan] describing how to bring
     * [existing] in sync with [candidates] without deleting anything.
     *
     * @throws IllegalArgumentException if any matching, collision, or duplicate rule is violated.
     */
    fun reconcile(
        existing: List<FilterSource>,
        candidates: List<FilterSource>
    ): ReconciliationPlan {
        // ---- Rule 6: reject duplicate non-null referenceIds in existing rows ----
        val existingNonNullRefIds = existing.mapNotNull { it.referenceId }
        require(existingNonNullRefIds.size == existingNonNullRefIds.toSet().size) {
            "duplicate non-null referenceId in existing rows"
        }

        // ---- Rule 5: reject duplicate candidate IDs ----
        val candidateIds = candidates.map { it.id }
        require(candidateIds.size == candidateIds.toSet().size) {
            "duplicate candidate local IDs"
        }

        // ---- Rule 5: reject duplicate candidate referenceIds (non-null only) ----
        val candidateNonNullRefIds = candidates.mapNotNull { it.referenceId }
        require(candidateNonNullRefIds.size == candidateNonNullRefIds.toSet().size) {
            "duplicate non-null candidate referenceIds"
        }

        // ---- Rule 5: reject duplicate candidate URLs ----
        val candidateUrls = candidates.map { it.url }
        require(candidateUrls.size == candidateUrls.toSet().size) {
            "duplicate candidate URLs"
        }

        // ---- Build lookup for existing rows ----
        // By non-null referenceId first (Rule 1).
        val existingByRefId: MutableMap<Int, FilterSource> = LinkedHashMap()
        for (row in existing) {
            if (row.referenceId != null) {
                // Rule 6 already validated uniqueness, but double-check on insertion.
                require(existingByRefId.put(row.referenceId, row) == null) {
                    "duplicate non-null referenceId ${row.referenceId} in existing rows"
                }
            }
        }

        // By local ID for legacy null-referenceId presets (Rule 2).
        val existingById: MutableMap<Int, FilterSource> = LinkedHashMap()
        for (row in existing) {
            require(existingById.put(row.id, row) == null) {
                "duplicate local id ${row.id} in existing rows"
            }
        }

        val inserts = mutableListOf<FilterSource>()
        val updates = mutableListOf<FilterSource>()
        val unchangedIds = LinkedHashSet<Int>()
        val urlChangedIds = LinkedHashSet<Int>()

        for (candidate in candidates) {
            val refId = candidate.referenceId
            var matched: FilterSource? = null

            // Rule 1: match by non-null referenceId.
            if (refId != null) {
                matched = existingByRefId[refId]
            }

            // Rule 2: for legacy rows whose referenceId is null, match by locked local ID
            // only when the existing row is a preset.
            if (matched == null && refId != null && candidate.isPreset) {
                val byId = existingById[candidate.id]
                if (byId != null && byId.referenceId == null && byId.isPreset) {
                    matched = byId
                }
            }

            if (matched != null) {
                // Rule 4: If a candidate local ID is occupied by a different row that
                // was NOT matched by referenceId, reject.
                val occupyingById = existingById[candidate.id]
                if (occupyingById != null && occupyingById !== matched) {
                    // The candidate's locked local ID is occupied by a row we did not
                    // match. This is a collision (custom row or preset with different refId).
                    throw IllegalArgumentException(
                        "candidate id=${candidate.id} (referenceId=${candidate.referenceId}) " +
                            "collides with existing id=${occupyingById.id} " +
                            "(referenceId=${occupyingById.referenceId}, isPreset=${occupyingById.isPreset})"
                    )
                }

                if (needsUpdate(matched, candidate)) {
                    updates.add(candidate)
                } else {
                    unchangedIds.add(matched.id)
                }

                if (matched.url != candidate.url) {
                    urlChangedIds.add(matched.id)
                }
            } else {
                // No match — this is a new insert.
                // Rule 4: verify the candidate's local ID is not occupied.
                val occupyingById = existingById[candidate.id]
                if (occupyingById != null) {
                    throw IllegalArgumentException(
                        "candidate id=${candidate.id} (referenceId=${candidate.referenceId}) " +
                            "collides with existing id=${occupyingById.id} " +
                            "(referenceId=${occupyingById.referenceId}, isPreset=${occupyingById.isPreset})"
                    )
                }
                inserts.add(candidate)
            }
        }

        return ReconciliationPlan(
            inserts = inserts,
            updates = updates,
            unchangedIds = unchangedIds,
            urlChangedIds = urlChangedIds
        )
    }
}

/**
 * Plan describing how to reconcile existing [FilterSource] rows with catalog-derived candidates.
 *
 * @property inserts Pure candidate rows to insert (no matching existing row).
 * @property updates Candidate rows to upsert over matched existing rows (catalog-owned metadata differs).
 * @property unchangedIds Local IDs of matched rows whose catalog-owned metadata is already identical.
 * @property urlChangedIds Local IDs of rows whose URL changed (subset of updates + unchanged, but
 *   in practice URL change implies metadata differs so it's a subset of updates).
 */
data class ReconciliationPlan(
    val inserts: List<FilterSource>,
    val updates: List<FilterSource>,
    val unchangedIds: Set<Int>,
    val urlChangedIds: Set<Int>
)

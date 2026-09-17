/*
 * Copyright 2026 RethinkDNS and its authors
 * Read-only UI grouping model for Manage Filters (B5 Slice-2).
 */
package com.celzero.bravedns.viewmodel

import com.celzero.bravedns.database.FilterSource

/** UI-only grouping of sources by canonical category. */
data class FilterSourceCategoryUi(
    val categoryCode: String,
    val displayName: String,
    val sources: List<FilterSource>,
    val enabledCount: Int,
    val totalCount: Int
) {
    companion object {
        private val LABELS = mapOf(
            "ADS" to "Ads",
            "PRIVACY" to "Privacy",
            "SOCIAL" to "Social",
            "ANNOYANCES" to "Annoyances",
            "SECURITY" to "Security",
            "LANGUAGE_SPECIFIC" to "Language-specific",
            "OTHER" to "Other Filters",
            "CUSTOM" to "Custom Filters"
        )

        private val ORDER = listOf(
            "ADS", "PRIVACY", "SOCIAL", "ANNOYANCES",
            "SECURITY", "LANGUAGE_SPECIFIC", "OTHER", "CUSTOM"
        )

        fun group(sources: List<FilterSource>): List<FilterSourceCategoryUi> {
            val byCategory = sources.groupBy { it.category }
            // Always emit all 8 canonical categories so the UI retains empty buckets
            // (B5 Slice-2R R2 / R5 test #4). Per R5 the canonical order is locked and
            // empty categories must be retained for stable rendering.
            return ORDER.map { code ->
                val group = byCategory[code] ?: emptyList()
                FilterSourceCategoryUi(
                    categoryCode = code,
                    displayName = LABELS[code] ?: code,
                    sources = group.sortedBy { it.name },
                    enabledCount = group.count { it.enabled },
                    totalCount = group.size
                )
            }
        }
    }
}

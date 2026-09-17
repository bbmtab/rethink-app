/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.viewmodel

import android.content.Context
import android.text.format.DateUtils
import com.celzero.bravedns.R
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceStatus

/**
 * Single source of truth for the read-only Advanced Filtering summary semantics
 * (B5 Slice-1R — summary semantics fix).
 *
 * Both the fdroid Plus tab ([com.celzero.bravedns.ui.fragment.RethinkPlusFragment])
 * and the full-flavor Manage Filters shell
 * ([com.celzero.bravedns.ui.activity.ManageFilterSourcesActivity] via
 * [ManageFilterSourcesViewModel]) project their summaries through this formatter so
 * the Plus card and the Manage Filters screen always describe the same state.
 *
 * Semantics (B5 Slice-1R):
 *  - `enabledSources` is strictly `sources.filter { it.enabled }`.
 *  - A disabled source is NEVER "pending" and NEVER "failed"; it is simply not in use.
 *  - "Pending" applies only to enabled sources that have not yet produced
 *    usable/current data: lastUpdateStatus is IDLE or IN_PROGRESS and
 *    parsedRuleCount == 0 (no compiled output yet).
 *  - "Failed" applies only to enabled sources whose lastUpdateStatus == FAILED.
 *    A failure is never relabeled as "pending" and never rendered as "0 rules".
 *  - Zero/uninitialized `lastUpdated` timestamps are ignored; epoch/0 never renders
 *    as a real update time.
 */
object FilterSourceSummaryFormatter {

    /** Pure projection of the FilterSource list into summary counts. No clock, no Context. */
    data class FilterSourceSummary(
        val totalSources: Int,
        val enabledSources: Int,
        val pendingSources: Int,
        val failedSources: Int,
        val totalRules: Int,
        val lastUpdated: Long
    )

    /** Compute the summary from the current source list. Safe to call off the main thread. */
    fun compute(list: List<FilterSource>): FilterSourceSummary {
        val enabled = list.filter { it.enabled }
        val failed = enabled.count { it.lastUpdateStatus == FilterSourceStatus.FAILED }

        fun hasUsableData(s: FilterSource): Boolean =
            s.lastUpdateStatus == FilterSourceStatus.SUCCESS || s.parsedRuleCount > 0

        // Pending = enabled, not failed, and not yet usable (IDLE/IN_PROGRESS w/ no rules).
        val pending = enabled.count {
            it.lastUpdateStatus != FilterSourceStatus.FAILED && !hasUsableData(it)
        }

        val totalRules = enabled.sumOf { it.parsedRuleCount }
        val lastUpdated = enabled
            .mapNotNull { it.lastUpdated.takeIf { ts -> ts > 0L } }
            .maxOrNull() ?: 0L

        return FilterSourceSummary(
            totalSources = list.size,
            enabledSources = enabled.size,
            pendingSources = pending,
            failedSources = failed,
            totalRules = totalRules,
            lastUpdated = lastUpdated
        )
    }

    /**
     * Render the summary as a single human-readable string for the UI.
     *
     * Must be called on the main thread (it reads the clock and Android resources).
     *
     * State precedence (first match wins):
     *  1. No enabled sources → inactive.
     *  2. Any enabled source FAILED → truthful failure/degraded state (pending suppressed).
     *  3. Enabled sources all pending with 0 rules → pending state (disabled sources
     *     never contribute to this count).
     *  4. Enabled sources have a valid non-zero lastUpdated → active with relative time.
     *  5. Otherwise (enabled with rules, fresh/unknown timestamp) → active without time.
     */
    fun format(context: Context, s: FilterSourceSummary): String {
        return when {
            s.enabledSources == 0 ->
                context.getString(R.string.plus_filter_sources_inactive)
            s.failedSources > 0 ->
                context.getString(
                    R.string.plus_filter_sources_failed,
                    s.enabledSources,
                    s.totalRules,
                    s.failedSources
                )
            s.pendingSources > 0 && s.totalRules == 0 ->
                context.getString(R.string.plus_filter_sources_pending, s.pendingSources)
            s.lastUpdated > 0L ->
                context.getString(
                    R.string.plus_filter_sources_updated,
                    s.enabledSources,
                    s.totalRules,
                    formatRelativeTime(context, s.lastUpdated)
                )
            else ->
                context.getString(
                    R.string.plus_filter_sources_summary,
                    s.enabledSources,
                    s.totalRules
                )
        }
    }

    private fun formatRelativeTime(context: Context, timestamp: Long): String {
        // DateUtils uses the device's "now" plus the formatter context for locale.
        val now = System.currentTimeMillis()
        return DateUtils.getRelativeTimeSpanString(
            timestamp,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }
}

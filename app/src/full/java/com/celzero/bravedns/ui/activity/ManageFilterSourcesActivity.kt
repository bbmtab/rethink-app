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
package com.celzero.bravedns.ui.activity

import android.os.Bundle
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FilterCategoryRow
import com.celzero.bravedns.adapter.FilterRow
import com.celzero.bravedns.adapter.FilterSourceRow
import com.celzero.bravedns.adapter.ManageFilterSourcesAdapter
import com.celzero.bravedns.databinding.ActivityManageFilterSourcesBinding
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.viewmodel.FilterSourceCategoryUi
import com.celzero.bravedns.viewmodel.FilterSourceSummaryFormatter
import com.celzero.bravedns.viewmodel.ManageFilterSourcesViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Read-only Manage Filters shell (B5 Slice-2R).
 *
 * Navigable from the Plus Advanced Filtering card's "Manage Filters" button. Displays:
 *  - the unified summary (delegated to [FilterSourceSummaryFormatter])
 *  - group headers for the 8 canonical categories
 *  - per-source truthful status + diagnostics
 *
 * The Activity owns the UI-local category expand/collapse map (never persists), and
 * flattens the ViewModel's category-grouped state into a single list of [FilterRow]
 * mixed row types consumed by [ManageFilterSourcesAdapter].
 *
 * NOT implemented in this slice: source enable/disable, download, compile,
 * custom-source creation and delete. Those remain deferred to later B5 slices.
 */
class ManageFilterSourcesActivity : BaseActivity(R.layout.activity_manage_filter_sources) {

    private val b by viewBinding(ActivityManageFilterSourcesBinding::bind)
    private val vm: ManageFilterSourcesViewModel by viewModel()
    private val adapter = ManageFilterSourcesAdapter()

    /** UI-local expand/collapse state for category headers — never persisted. */
    private val expandedCategories = mutableMapOf<String, Boolean>()

    /** Last categories projection — used by category toggle to rebuild the flat row list. */
    private var lastCategories: List<FilterSourceCategoryUi> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.let { bar ->
            bar.title = getString(R.string.plus_manage_filters_title)
            bar.subtitle = getString(R.string.plus_filter_sources_desc)
        }

        b.recyclerFilterSources.layoutManager = LinearLayoutManager(this)
        b.recyclerFilterSources.adapter = adapter
        adapter.onCategoryToggle = { code -> toggleCategory(code) }
        adapter.onSourceToggle = { source, enabled ->
            vm.setSourceEnabled(source.id, enabled)
        }

        vm.refresh()
        observeState()
    }

    private fun observeState() {
        vm.summary.observe(this) { summary ->
            bindAggregate(summary)
        }
        vm.categories.observe(this) { cats ->
            lastCategories = cats
            rebuildRows()
        }
    }

    private fun toggleCategory(code: String) {
        // UI-local mutation only — never touches Room, PersistentState, or any
        // FilterSource entity (R3). Default state for an unseen category is expanded.
        expandedCategories[code] = !(expandedCategories[code] ?: true)
        rebuildRows()
    }

    private fun rebuildRows() {
        val cats = lastCategories
        val rows = buildList<FilterRow> {
            cats.forEach { cat ->
                val isOpen = expandedCategories[cat.categoryCode] ?: true
                add(
                    FilterCategoryRow(
                        categoryCode = cat.categoryCode,
                        displayName = cat.displayName,
                        enabledCount = cat.enabledCount,
                        totalCount = cat.totalCount,
                        expanded = isOpen
                    )
                )
                if (isOpen) {
                    cat.sources.forEach { src ->
                        add(FilterSourceRow(src, expanded = true))
                    }
                }
            }
        }
        adapter.submitList(rows)
        // emptyState visible only when no source sits in any category. Category headers
        // for empty buckets still render (B5 Slice-2R R2 "empty categories retained").
        val anySource = cats.any { it.totalCount > 0 }
        b.emptyState.isVisible = !anySource
    }

    private fun bindAggregate(summary: FilterSourceSummaryFormatter.FilterSourceSummary) {
        b.tvAggregateSummary.text = FilterSourceSummaryFormatter.format(this, summary)
        // Second line kept hidden in Slice-1R so only the unified summary string is shown.
        b.tvAggregateRules.isVisible = false
    }
}

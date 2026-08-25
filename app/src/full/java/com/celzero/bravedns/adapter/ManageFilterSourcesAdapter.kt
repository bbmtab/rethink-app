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
package com.celzero.bravedns.adapter

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceStatus
import com.celzero.bravedns.databinding.ListItemFilterSourceBinding

/**
 * Grouped read-only adapter for Manage Filters (B5 Slice-2R).
 *
 * Row types:
 *  - [FilterCategoryRow]   — non-interactive category header (count summary). Tapping
 *                            toggles category expand/collapse via [onCategoryToggle]. The
 *                            expansion state itself lives in the Activity (UI-local map)
 *                            and never persists; this adapter never mutates domain
 *                            entities.
 *  - [FilterSourceRow]     — single FilterSource item with truthful status + diagnostics.
 *                            Entirely read-only: no switches, no delete, no download, no
 *                            compile trigger. Mutation surfaces are deferred to B5 Slice-3.
 *
 * All rows use unique stable IDs for DiffUtil; [DiffUtil.ItemCallback] keys on that ID.
 */
sealed class FilterRow {
    abstract val id: String
}

data class FilterCategoryRow(
    val categoryCode: String,
    val displayName: String,
    val enabledCount: Int,
    val totalCount: Int,
    val expanded: Boolean = true
) : FilterRow() {
    override val id: String = "cat_$categoryCode"
}

data class FilterSourceRow(
    val source: FilterSource,
    val expanded: Boolean = true
) : FilterRow() {
    override val id: String = "src_${source.id}"
}

class ManageFilterSourcesAdapter :
    ListAdapter<FilterRow, RecyclerView.ViewHolder>(RowDiffCallback) {

    /**
     * Optional callback invoked when the user taps a category header. The Activity uses
     * this to flip the UI-local expand/collapse map and re-submit a flat list. The adapter
     * never mutates any supplied state itself.
     */
    var onCategoryToggle: ((String) -> Unit)? = null

    /**
     * Optional callback invoked when the user toggles a source's enable switch. The
     * Activity uses this to call [ManageFilterSourcesViewModel.setSourceEnabled]. The
     * adapter never mutates any supplied state itself.
     */
    var onSourceToggle: ((FilterSource, Boolean) -> Unit)? = null

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is FilterCategoryRow -> TYPE_HEADER
        is FilterSourceRow -> TYPE_SOURCE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.list_item_filter_category_header, parent, false)
            HeaderHolder(v)
        } else {
            val b = ListItemFilterSourceBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            SourceHolder(b)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is FilterCategoryRow -> (holder as HeaderHolder).bind(row)
            is FilterSourceRow -> (holder as SourceHolder).bind(row)
        }
    }

    inner class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvCategoryName)
        private val tvCount: TextView = itemView.findViewById(R.id.tvCategoryCount)

        fun bind(row: FilterCategoryRow) {
            val hasSources = row.totalCount > 0
            val glyph = when {
                !hasSources -> ""
                row.expanded -> "▼ "
                else -> "▶ "
            }
            tvName.text = glyph + row.displayName
            tvCount.text = if (row.totalCount == 0) {
                "0"
            } else {
                "${row.enabledCount} / ${row.totalCount}"
            }
            if (hasSources) {
                itemView.setOnClickListener {
                    onCategoryToggle?.invoke(row.categoryCode)
                }
            } else {
                itemView.setOnClickListener(null)
            }
            itemView.isClickable = hasSources
            itemView.isFocusable = hasSources
        }
    }

    inner class SourceHolder(private val b: ListItemFilterSourceBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(row: FilterSourceRow) {
            val item = row.source
            b.tvSourceName.text = item.name
            b.chipSourceCategory.text = item.category
            b.tvSourceUrl.text = item.url
            b.tvSourceStatus.text = formatStatus(item)
            b.tvSourceDiagnostics.text = formatDiagnostics(item)
            b.tvSourceDiagnostics.visibility = if (row.expanded) View.VISIBLE else View.GONE
            b.switchEnabled.setOnCheckedChangeListener(null)
            b.switchEnabled.isChecked = row.source.enabled
            b.switchEnabled.isEnabled = true
            b.switchEnabled.setOnCheckedChangeListener { _, checked ->
                onSourceToggle?.invoke(row.source, checked)
            }
            // Read-only: rows themselves are never clickable; per-row diagnostics are
            // toggled via category header (UI-local in the Activity) — domain entity is
            // never mutated by this adapter.
            b.root.isClickable = false
            b.root.isFocusable = false
        }

        private fun formatStatus(item: FilterSource): String {
            val statusLine = when (item.lastUpdateStatus) {
                FilterSourceStatus.IN_PROGRESS -> "⏳ Downloading"
                FilterSourceStatus.SUCCESS -> "✓ Ready"
                FilterSourceStatus.FAILED -> "✗ Failed"
                else -> "• Idle"
            }
            val rules = if (item.parsedRuleCount > 0) {
                "${item.parsedRuleCount} rules"
            } else {
                // Truthful "Not compiled yet" — never rendered as a fake timestamp.
                "Not compiled yet"
            }
            val updated = if (item.lastUpdated > 0L) {
                val now = System.currentTimeMillis()
                DateUtils.getRelativeTimeSpanString(
                    item.lastUpdated, now, DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                ).toString()
            } else {
                // lastUpdated == 0 -> never render a fake timestamp.
                "never"
            }
            return "$statusLine  ·  $rules  ·  Updated $updated"
        }

        private fun formatDiagnostics(item: FilterSource): String {
            // R4 truthful diagnostics breakdown rendered directly from FilterSource fields.
            val sb = StringBuilder()
            sb.append("Total lines: ").append(item.totalLineCount).append('\n')
            sb.append("Parsed: ").append(item.parsedRuleCount).append('\n')
            if (item.unsupportedRuleCount > 0) {
                sb.append("Unsupported: ").append(item.unsupportedRuleCount).append('\n')
            }
            if (item.invalidRuleCount > 0) {
                sb.append("Invalid: ").append(item.invalidRuleCount).append('\n')
            }
            sb.append("Network: ").append(item.networkRuleCount).append('\n')
            sb.append("CSS: ").append(item.cosmeticRuleCount).append('\n')
            sb.append("Procedural: ").append(item.proceduralRuleCount).append('\n')
            sb.append("Scriptlet: ").append(item.scriptletRuleCount).append('\n')
            sb.append("CSP: ").append(item.cspRuleCount).append('\n')
            sb.append("HTML: ").append(item.htmlFilterRuleCount)
            if (item.lastUpdateStatus == FilterSourceStatus.FAILED
                && !item.errorMessage.isNullOrBlank()) {
                sb.append('\n').append("Error: ").append(item.errorMessage)
            }
            return sb.toString().trimEnd()
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SOURCE = 1

        private val RowDiffCallback = object : DiffUtil.ItemCallback<FilterRow>() {
            override fun areItemsTheSame(old: FilterRow, new: FilterRow) = old.id == new.id
            override fun areContentsTheSame(old: FilterRow, new: FilterRow) = old == new
        }
    }
}

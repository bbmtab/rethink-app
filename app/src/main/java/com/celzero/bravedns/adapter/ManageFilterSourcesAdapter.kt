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
import androidx.core.view.isVisible
import com.celzero.bravedns.R
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.database.FilterSourceCategory
import com.celzero.bravedns.database.FilterSourceStatus
import com.celzero.bravedns.databinding.ListItemFilterSourceBinding

/**
 * Grouped read-only adapter for Manage Filters (B5 Slice-2R).
 *
 * Row types:
 *  - [FilterCategoryRow]   — interactive category header (count summary). Tapping toggles
 *                            category expand/collapse via [onCategoryToggle]. Headers are
 *                            tappable even at totalCount == 0 (empty buckets expand too;
 *                            e.g. Custom Filters hosts the Add action when expanded). The
 *                            expansion state itself lives in the Activity (UI-local map,
 *                            default collapsed) and never persists; this adapter never
 *                            mutates domain entities.
 *  - [FilterAddCustomRow]  — inline "Add custom filter" action rendered between the
 *                            Custom Filters header and its source rows whenever that
 *                            section is expanded. Tapping invokes [onAddCustomFilter].
 *  - [FilterSourceRow]     — single FilterSource item with truthful status + diagnostics.
 *                            Shows an enable switch for every source and one overflow control
 *                            only for user-owned CUSTOM sources. The Activity owns all actions.
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
    val expanded: Boolean = false
) : FilterRow() {
    override val id: String = "cat_$categoryCode"
}

/** Inline "Add custom filter" action for the expanded Custom Filters section. */
object FilterAddCustomRow : FilterRow() {
    override val id: String = "action_add_custom"
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
     * Optional callback invoked when the user taps the inline Add-custom-filter action in
     * the expanded Custom Filters section. The Activity uses this to show its creation
     * dialog. The adapter never mutates any supplied state itself.
     */
    var onAddCustomFilter: (() -> Unit)? = null

    /**
     * Optional callback invoked when the user toggles a source's enable switch. The
     * Activity uses this to call [ManageFilterSourcesViewModel.setSourceEnabled]. The
     * adapter never mutates any supplied state itself.
     */
    var onSourceToggle: ((FilterSource, Boolean) -> Unit)? = null

    /**
     * Invoked when the overflow control on a user-owned CUSTOM source is tapped.
     * The Activity owns menu rendering and action selection.
     */
    var onCustomSourceMenu: ((View, FilterSource) -> Unit)? = null

    private var busySourceId: Int? = null

    /**
     * Disable the switch and overflow control for one source while a destructive
     * operation is in progress. Passing null clears the busy row.
     */
    fun setBusySourceId(sourceId: Int?) {
        if (busySourceId == sourceId) return
        busySourceId = sourceId
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is FilterCategoryRow -> TYPE_HEADER
        is FilterAddCustomRow -> TYPE_ADD_CUSTOM
        is FilterSourceRow -> TYPE_SOURCE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_filter_category_header, parent, false)
                HeaderHolder(v)
            }

            TYPE_ADD_CUSTOM -> {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_filter_add_custom, parent, false)
                AddCustomHolder(v)
            }

            else -> {
                val b = ListItemFilterSourceBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                SourceHolder(b)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is FilterCategoryRow -> (holder as HeaderHolder).bind(row)
            is FilterAddCustomRow -> (holder as AddCustomHolder).bind()
            is FilterSourceRow -> (holder as SourceHolder).bind(row)
        }
    }

    inner class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvCategoryName)
        private val tvCount: TextView = itemView.findViewById(R.id.tvCategoryCount)

        fun bind(row: FilterCategoryRow) {
            // Collapsed-by-default UX (2026-08-26 fix): every header always shows an
            // expand glyph and stays tappable — including empty buckets at count 0,
            // whose expanded section hosts the inline Add-custom-filter action.
            val glyph = if (row.expanded) "▼ " else "▶ "
            tvName.text = glyph + row.displayName
            tvCount.text = if (row.totalCount == 0) {
                "0"
            } else {
                "${row.enabledCount} / ${row.totalCount}"
            }
            itemView.setOnClickListener {
                onCategoryToggle?.invoke(row.categoryCode)
            }
            itemView.isClickable = true
            itemView.isFocusable = true
        }
    }

    inner class AddCustomHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val btnAdd: View = itemView.findViewById(R.id.btnAddCustomFilterRow)

        fun bind() {
            btnAdd.setOnClickListener {
                onAddCustomFilter?.invoke()
            }
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

            val canManageCustomSource =
                item.category == FilterSourceCategory.CUSTOM &&
                    !item.isPreset

            val sourceBusy = busySourceId == item.id
            b.btnCustomSourceMenu.isVisible = canManageCustomSource
            b.btnCustomSourceMenu.isEnabled =
                canManageCustomSource && !sourceBusy
            b.btnCustomSourceMenu.setOnClickListener(null)
            if (canManageCustomSource) {
                b.btnCustomSourceMenu.setOnClickListener { anchor ->
                    onCustomSourceMenu?.invoke(anchor, item)
                }
            }
            b.switchEnabled.setOnCheckedChangeListener(null)
            b.switchEnabled.isChecked = row.source.enabled
            b.switchEnabled.isEnabled = !sourceBusy
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
        private const val TYPE_ADD_CUSTOM = 2

        private val RowDiffCallback = object : DiffUtil.ItemCallback<FilterRow>() {
            override fun areItemsTheSame(old: FilterRow, new: FilterRow) = old.id == new.id
            override fun areContentsTheSame(old: FilterRow, new: FilterRow) = old == new
        }
    }
}

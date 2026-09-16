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
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FilterRowFlattener
import com.celzero.bravedns.adapter.ManageFilterSourcesAdapter
import com.celzero.bravedns.database.CustomFilterSourceValidator
import com.celzero.bravedns.database.FilterSource
import com.celzero.bravedns.databinding.ActivityManageFilterSourcesBinding
import com.celzero.bravedns.databinding.DialogAddCustomFilterBinding
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.viewmodel.CustomSourceCreationState
import com.celzero.bravedns.viewmodel.CustomSourceEditState
import com.celzero.bravedns.viewmodel.CustomSourceRemovalState
import com.celzero.bravedns.viewmodel.FilterSourceCategoryUi
import com.celzero.bravedns.viewmodel.FilterSourceSummaryFormatter
import com.celzero.bravedns.viewmodel.ManageFilterSourcesViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Read-only Manage Filters shell (B5 Slice-2R).
 *
 * Navigable from the Plus Advanced Filtering card's "Manage Filters" button. Displays:
 *  - the unified summary (delegated to [FilterSourceSummaryFormatter])
 *  - group headers for the 8 canonical categories
 *  - per-source truthful status + diagnostics
 *
 * The Activity owns the UI-local category expand/collapse map (never persists; unseen
 * categories default COLLAPSED), and flattens the ViewModel's category-grouped state via
 * [FilterRowFlattener] into a single list of mixed row types consumed by
 * [ManageFilterSourcesAdapter].
 *
 * NOT implemented in this slice: source enable/disable, download, compile,
 * custom-source creation and delete. Those remain deferred to later B5 slices.
 */
class ManageFilterSourcesActivity : BaseActivity(R.layout.activity_manage_filter_sources) {

    private val b by viewBinding(ActivityManageFilterSourcesBinding::bind)
    private val vm: ManageFilterSourcesViewModel by viewModel()
    private val adapter = ManageFilterSourcesAdapter()

    /** In-flight "Add custom filter" dialog + its binding, if shown. */
    private var addCustomFilterDialog: AlertDialog? = null
    private var addCustomFilterBinding: DialogAddCustomFilterBinding? = null

    /** Non-null only while the shared custom-filter dialog is in Edit mode. */
    private var editingCustomSourceId: Int? = null

    /** Prevents a second destructive action while one removal transaction is active. */
    private var removalInProgress = false

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
        adapter.onAddCustomFilter = {
            showAddCustomFilterDialog()
        }
        adapter.onCustomSourceMenu = { anchor, source ->
            showCustomSourceMenu(anchor, source)
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
        vm.customSourceCreation.observe(this) { state ->
            if (editingCustomSourceId != null) return@observe

            when (state) {
                CustomSourceCreationState.Idle -> {
                    setCustomFilterDialogBusy(false)
                }

                CustomSourceCreationState.Creating -> {
                    setCustomFilterDialogBusy(true)
                }

                is CustomSourceCreationState.Added -> {
                    Toast.makeText(
                        this,
                        getString(R.string.custom_filter_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                    addCustomFilterDialog?.dismiss()
                }

                is CustomSourceCreationState.InvalidInput -> {
                    setCustomFilterDialogBusy(false)
                    showCustomFilterValidationError(state.error)
                }

                is CustomSourceCreationState.DuplicateName -> {
                    setCustomFilterDialogBusy(false)
                    addCustomFilterBinding?.tilCustomFilterName?.error =
                        getString(
                            R.string.custom_filter_error_duplicate_name,
                            state.name
                        )
                }

                is CustomSourceCreationState.DuplicateUrl -> {
                    setCustomFilterDialogBusy(false)
                    addCustomFilterBinding?.tilCustomFilterUrl?.error =
                        getString(R.string.custom_filter_error_duplicate_url)
                }

                is CustomSourceCreationState.Failed -> {
                    setCustomFilterDialogBusy(false)
                    Toast.makeText(
                        this,
                        getString(R.string.custom_filter_error_create_failed, state.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        vm.customSourceEdit.observe(this) { state ->
            if (editingCustomSourceId == null) return@observe

            when (state) {
                CustomSourceEditState.Idle -> {
                    setCustomFilterDialogBusy(false)
                }

                is CustomSourceEditState.Editing -> {
                    setCustomFilterDialogBusy(true)
                }

                is CustomSourceEditState.Updated -> {
                    Toast.makeText(
                        this,
                        getString(R.string.custom_filter_edit_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                    addCustomFilterDialog?.dismiss()
                }

                is CustomSourceEditState.InvalidInput -> {
                    setCustomFilterDialogBusy(false)
                    showCustomFilterValidationError(state.error)
                }

                is CustomSourceEditState.DuplicateName -> {
                    setCustomFilterDialogBusy(false)
                    addCustomFilterBinding?.tilCustomFilterName?.error =
                        getString(
                            R.string.custom_filter_error_duplicate_name,
                            state.name
                        )
                }

                is CustomSourceEditState.DuplicateUrl -> {
                    setCustomFilterDialogBusy(false)
                    addCustomFilterBinding?.tilCustomFilterUrl?.error =
                        getString(R.string.custom_filter_error_duplicate_url)
                }

                is CustomSourceEditState.SourceEnabled -> {
                    setCustomFilterDialogBusy(false)
                    Toast.makeText(
                        this,
                        getString(R.string.custom_filter_edit_requires_disabled),
                        Toast.LENGTH_LONG
                    ).show()
                    addCustomFilterDialog?.dismiss()
                }

                is CustomSourceEditState.SourceNotFound -> {
                    setCustomFilterDialogBusy(false)
                    Toast.makeText(
                        this,
                        getString(R.string.custom_filter_error_source_missing),
                        Toast.LENGTH_LONG
                    ).show()
                    addCustomFilterDialog?.dismiss()
                }

                is CustomSourceEditState.NotCustomSource -> {
                    setCustomFilterDialogBusy(false)
                    Toast.makeText(
                        this,
                        getString(R.string.custom_filter_error_not_custom),
                        Toast.LENGTH_LONG
                    ).show()
                    addCustomFilterDialog?.dismiss()
                }

                is CustomSourceEditState.FileCleanupFailed -> {
                    setCustomFilterDialogBusy(false)
                    Toast.makeText(
                        this,
                        getString(R.string.custom_filter_error_cleanup_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }

                is CustomSourceEditState.Failed -> {
                    setCustomFilterDialogBusy(false)
                    Toast.makeText(
                        this,
                        getString(
                            R.string.custom_filter_error_edit_failed,
                            state.message
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        vm.customSourceRemoval.observe(this) { state ->
            when (state) {
                CustomSourceRemovalState.Idle -> {
                    clearRemovalBusyState()
                }

                is CustomSourceRemovalState.Removing -> {
                    removalInProgress = true
                    adapter.setBusySourceId(state.sourceId)
                }

                is CustomSourceRemovalState.Removed -> {
                    clearRemovalBusyState()
                    Toast.makeText(
                        this,
                        getString(R.string.custom_filter_removed),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                is CustomSourceRemovalState.SourceNotFound -> {
                    clearRemovalBusyState()
                    Toast.makeText(
                        this,
                        getString(R.string.custom_filter_error_source_missing),
                        Toast.LENGTH_LONG
                    ).show()
                }

                is CustomSourceRemovalState.NotCustomSource -> {
                    clearRemovalBusyState()
                    Toast.makeText(
                        this,
                        getString(R.string.custom_filter_error_not_custom),
                        Toast.LENGTH_LONG
                    ).show()
                }

                is CustomSourceRemovalState.SourceEnabled -> {
                    clearRemovalBusyState()
                    Toast.makeText(
                        this,
                        getString(R.string.custom_filter_error_state_changed),
                        Toast.LENGTH_LONG
                    ).show()
                }

                is CustomSourceRemovalState.FileCleanupFailed -> {
                    clearRemovalBusyState()
                    Toast.makeText(
                        this,
                        getString(R.string.custom_filter_error_cleanup_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }

                is CustomSourceRemovalState.Failed -> {
                    clearRemovalBusyState()
                    Toast.makeText(
                        this,
                        getString(
                            R.string.custom_filter_error_remove_failed,
                            state.message
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun toggleCategory(code: String) {
        // UI-local mutation only — never touches Room, PersistentState, or any
        // FilterSource entity (R3). Default state for an unseen category is collapsed.
        expandedCategories[code] = !(expandedCategories[code] ?: false)
        rebuildRows()
    }

    private fun rebuildRows() {
        val rows = FilterRowFlattener.flatten(lastCategories, expandedCategories)
        adapter.submitList(rows)
        // emptyState visible only when no source sits in any category. Category headers
        // for empty buckets still render (B5 Slice-2R R2 "empty categories retained") and
        // stay expandable — Custom Filters hosts the inline Add action even at count 0.
        val anySource = lastCategories.any { it.totalCount > 0 }
        b.emptyState.isVisible = !anySource
    }

    private fun bindAggregate(summary: FilterSourceSummaryFormatter.FilterSourceSummary) {
        b.tvAggregateSummary.text = FilterSourceSummaryFormatter.format(this, summary)
        // Second line kept hidden in Slice-1R so only the unified summary string is shown.
        b.tvAggregateRules.isVisible = false
    }

    private fun showCustomSourceMenu(
        anchor: View,
        source: FilterSource
    ) {
        if (removalInProgress) return

        PopupMenu(this, anchor).apply {
            menu.add(
                0,
                MENU_EDIT_CUSTOM_SOURCE,
                0,
                R.string.custom_filter_edit
            )
            menu.add(
                0,
                MENU_REMOVE_CUSTOM_SOURCE,
                1,
                R.string.custom_filter_remove
            )
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDIT_CUSTOM_SOURCE -> {
                        showEditCustomFilterDialog(source)
                        true
                    }

                    MENU_REMOVE_CUSTOM_SOURCE -> {
                        showRemoveCustomFilterConfirmation(source)
                        true
                    }

                    else -> false
                }
            }
            show()
        }
    }

    private fun showRemoveCustomFilterConfirmation(
        source: FilterSource
    ) {
        if (removalInProgress) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_filter_remove_dialog_title)
            .setMessage(
                getString(
                    R.string.custom_filter_remove_dialog_message,
                    source.name
                )
            )
            .setNegativeButton(R.string.lbl_cancel, null)
            .setPositiveButton(R.string.custom_filter_remove) { _, _ ->
                if (!removalInProgress) {
                    removalInProgress = true
                    adapter.setBusySourceId(source.id)
                    vm.removeCustomSource(source.id)
                }
            }
            .show()
    }

    private fun clearRemovalBusyState() {
        removalInProgress = false
        adapter.setBusySourceId(null)
    }

    private fun showAddCustomFilterDialog() {
        showCustomFilterDialog(source = null)
    }

    private fun showEditCustomFilterDialog(source: FilterSource) {
        if (source.enabled) {
            Toast.makeText(
                this,
                getString(R.string.custom_filter_edit_requires_disabled),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        showCustomFilterDialog(source)
    }

    private fun showCustomFilterDialog(source: FilterSource?) {
        if (addCustomFilterDialog?.isShowing == true) return

        if (source == null) {
            vm.clearCustomSourceCreationState()
        } else {
            vm.clearCustomSourceEditState()
        }
        editingCustomSourceId = source?.id

        val dialogBinding =
            DialogAddCustomFilterBinding.inflate(layoutInflater)
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(
                    if (source == null) {
                        R.string.custom_filter_dialog_title
                    } else {
                        R.string.custom_filter_edit_dialog_title
                    }
                )
                .setView(dialogBinding.root)
                .setNegativeButton(R.string.lbl_cancel, null)
                .setPositiveButton(R.string.lbl_save, null)
                .create()

        if (source != null) {
            dialogBinding.etCustomFilterName.setText(source.name)
            dialogBinding.etCustomFilterUrl.setText(source.url)
        }

        addCustomFilterBinding = dialogBinding
        addCustomFilterDialog = dialog

        dialog.setOnDismissListener {
            val wasEditing = editingCustomSourceId != null

            if (addCustomFilterDialog === dialog) {
                addCustomFilterDialog = null
                addCustomFilterBinding = null
                editingCustomSourceId = null
            }

            if (wasEditing) {
                vm.clearCustomSourceEditState()
            } else {
                vm.clearCustomSourceCreationState()
            }
        }

        dialog.show()

        dialog.getButton(
            AlertDialog.BUTTON_POSITIVE
        ).setOnClickListener {
            dialogBinding.tilCustomFilterName.error = null
            dialogBinding.tilCustomFilterUrl.error = null

            val name =
                dialogBinding.etCustomFilterName.text
                    ?.toString()
                    .orEmpty()
            val url =
                dialogBinding.etCustomFilterUrl.text
                    ?.toString()
                    .orEmpty()

            when (
                val validation =
                    CustomFilterSourceValidator.validate(name, url)
            ) {
                is CustomFilterSourceValidator.Result.Invalid -> {
                    showCustomFilterValidationError(validation.error)
                }

                is CustomFilterSourceValidator.Result.Valid -> {
                    val sourceId = editingCustomSourceId
                    if (sourceId == null) {
                        vm.createCustomSource(
                            validation.name,
                            validation.url
                        )
                    } else {
                        vm.editCustomSource(
                            sourceId,
                            validation.name,
                            validation.url
                        )
                    }
                }
            }
        }
    }

    private fun showCustomFilterValidationError(
        error: CustomFilterSourceValidator.Error
    ) {
        val dialogBinding = addCustomFilterBinding ?: return

        when (error) {
            CustomFilterSourceValidator.Error.EMPTY_NAME -> {
                dialogBinding.tilCustomFilterName.error =
                    getString(R.string.custom_filter_error_empty_name)
            }

            CustomFilterSourceValidator.Error.EMPTY_URL -> {
                dialogBinding.tilCustomFilterUrl.error =
                    getString(R.string.custom_filter_error_empty_url)
            }

            CustomFilterSourceValidator.Error.INVALID_URL -> {
                dialogBinding.tilCustomFilterUrl.error =
                    getString(R.string.custom_filter_error_invalid_url)
            }

            CustomFilterSourceValidator.Error.UNSUPPORTED_SCHEME -> {
                dialogBinding.tilCustomFilterUrl.error =
                    getString(R.string.custom_filter_error_unsupported_scheme)
            }

            CustomFilterSourceValidator.Error.MISSING_HOST -> {
                dialogBinding.tilCustomFilterUrl.error =
                    getString(R.string.custom_filter_error_missing_host)
            }

            CustomFilterSourceValidator.Error.FRAGMENT_NOT_ALLOWED -> {
                dialogBinding.tilCustomFilterUrl.error =
                    getString(R.string.custom_filter_error_fragment)
            }
        }
    }

    private fun setCustomFilterDialogBusy(busy: Boolean) {
        val dialog = addCustomFilterDialog ?: return
        val dialogBinding = addCustomFilterBinding ?: return

        dialogBinding.etCustomFilterName.isEnabled = !busy
        dialogBinding.etCustomFilterUrl.isEnabled = !busy
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !busy
        dialog.setCancelable(!busy)
        dialog.setCanceledOnTouchOutside(!busy)
    }

    private companion object {
        const val MENU_EDIT_CUSTOM_SOURCE = 1
        const val MENU_REMOVE_CUSTOM_SOURCE = 2
    }

    override fun onDestroy() {
        adapter.setBusySourceId(null)
        addCustomFilterDialog?.dismiss()
        addCustomFilterDialog = null
        addCustomFilterBinding = null
        super.onDestroy()
    }
}

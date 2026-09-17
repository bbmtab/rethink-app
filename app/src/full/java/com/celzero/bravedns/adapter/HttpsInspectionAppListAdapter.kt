/*
 * Copyright 2022 RethinkDNS and its authors
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

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.core.proxy.policy.InspectionAppPolicyController
import com.celzero.bravedns.core.proxy.policy.InspectionAppPolicyTier
import com.celzero.bravedns.core.proxy.policy.InspectionBrowserRuntimePackages
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.databinding.ListItemHttpsInspectionAppBinding
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HttpsInspectionAppListAdapter(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val controller: InspectionAppPolicyController,
    private val browserPackages: InspectionBrowserRuntimePackages
) : PagingDataAdapter<
        AppInfo,
        HttpsInspectionAppListAdapter.AppViewHolder
    >(
        DIFF_CALLBACK
    ) {
    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<AppInfo>() {
                override fun areItemsTheSame(
                    oldItem: AppInfo,
                    newItem: AppInfo
                ): Boolean =
                    oldItem.uid == newItem.uid &&
                        oldItem.packageName == newItem.packageName

                override fun areContentsTheSame(
                    oldItem: AppInfo,
                    newItem: AppInfo
                ): Boolean =
                    oldItem == newItem &&
                        oldItem.appName == newItem.appName
            }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): AppViewHolder {
        val binding =
            ListItemHttpsInspectionAppBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: AppViewHolder,
        position: Int
    ) {
        val appInfo = getItem(position) ?: return
        holder.bind(appInfo)
    }

    inner class AppViewHolder(
        private val b: ListItemHttpsInspectionAppBinding
    ) : RecyclerView.ViewHolder(b.root) {
        fun bind(appInfo: AppInfo) {
            val state =
                controller.stateFor(
                    packageName = appInfo.packageName,
                    browserPackages = browserPackages,
                    uid = appInfo.uid
                )

            b.appName.text = appInfo.appName
            b.appPackage.text = appInfo.packageName
            b.appPolicy.text =
                when (state.tier) {
                    InspectionAppPolicyTier.SYSTEM_BYPASS ->
                        context.getString(
                            R.string.https_inspection_system_bypass
                        )

                    InspectionAppPolicyTier.KNOWN_BROWSER ->
                        context.getString(
                            R.string.https_inspection_known_browser
                        )

                    InspectionAppPolicyTier.DYNAMIC_BROWSER ->
                        context.getString(
                            R.string.https_inspection_dynamic_browser
                        )

                    InspectionAppPolicyTier.OTHER ->
                        context.getString(
                            R.string.https_inspection_other_app
                        )
                }

            b.httpsInspectionSwitch.setOnCheckedChangeListener(
                null
            )
            b.httpsInspectionSwitch.isChecked = state.enabled

            val mutable =
                state.tier != InspectionAppPolicyTier.SYSTEM_BYPASS

            b.httpsInspectionSwitch.isEnabled = mutable
            b.httpsInspectionSwitch.isClickable = mutable

            b.httpsInspectionSwitch.contentDescription =
                context.getString(
                    R.string.https_inspection_app_switch_desc,
                    appInfo.appName
                )

            if (mutable) {
                b.httpsInspectionSwitch.setOnCheckedChangeListener {
                        _,
                        enabled ->
                    controller.setInspectionEnabled(
                        packageName = appInfo.packageName,
                        tier = state.tier,
                        enabled = enabled,
                        uid = appInfo.uid
                    )
                }
            }

            lifecycleOwner.lifecycleScope.launch(
                Dispatchers.IO
            ) {
                val icon =
                    Utilities.getIcon(
                        context,
                        appInfo.packageName,
                        appInfo.appName
                    )

                withContext(Dispatchers.Main) {
                    b.appIcon.setImageDrawable(icon)
                }
            }
        }
    }
}
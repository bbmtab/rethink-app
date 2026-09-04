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
package com.celzero.bravedns.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.scheduler.RefreshAppsJob

/**
 * Listens for Android package lifecycle broadcasts and enqueues a one-time
 * [RefreshAppsJob] to reconcile Rethink's Room app-info inventory and the
 * in-memory [com.celzero.bravedns.service.FirewallManager] cache with the
 * system's current installed-package state.
 *
 * Add, remove, replace, and change are all routed through the same
 * [RefreshAppsJob] path because the worker performs a full inventory
 * reconciliation against `pm.getInstalledPackages(...)` regardless of which
 * action triggered it.
 *
 * This receiver does not invoke the inventory-refresh API on
 * RefreshDatabase directly. It enqueues the existing [RefreshAppsJob]
 * (declared in `app/src/main/.../scheduler/RefreshAppsJob.kt`) as a
 * unique one-time WorkManager job. WorkManager survives process death,
 * runs on its own background executor, and is the lifecycle-safe
 * execution surface appropriate for a BroadcastReceiver's tight time
 * budget.
 *
 * Declared in `app/src/full/AndroidManifest.xml` for the `full` flavor only.
 * Not declared in `main`, `play`, or `website` flavors.
 */
class BravePackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // schemeSpecificPart for a `package:` URI yields the package name.
        // Null/blank URIs are ignored (e.g. system-wide broadcasts without
        // a per-package data payload).
        val packageName = intent.data?.schemeSpecificPart
        if (!shouldRequestRefresh(action, packageName)) {
            return
        }
        val appContext = context.applicationContext
        val request =
            OneTimeWorkRequestBuilder<RefreshAppsJob>()
                .setInputData(
                    workDataOf(
                        RefreshAppsJob.INPUT_REFRESH_ACTION to
                            packageChangeRefreshAction(),
                    ),
                )
                .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }

    companion object {
        /**
         * Unique work name used for the enqueued one-time
         * [RefreshAppsJob]. Using WorkManager's unique-work API with
         * `ExistingWorkPolicy.REPLACE` coalesces bursts of
         * install/remove/replace broadcasts (e.g. during a batch
         * update). Package-change requests use a forced reconciliation
         * so the final pass cannot be suppressed by the normal
         * AUTO/INTERACTIVE refresh throttle.
         */
        const val UNIQUE_WORK_NAME: String = "refresh_apps_after_package_change"

        /**
         * Package lifecycle broadcasts represent authoritative installed-app
         * inventory changes and must bypass RefreshDatabase's one-minute
         * AUTO/INTERACTIVE refresh throttle.
         */
        internal fun packageChangeRefreshAction(): Int =
            RefreshDatabase.ACTION_REFRESH_FORCE

        /**
         * Pure action/package-name validation. Kept `internal` and
         * side-effect free so it can be unit-tested without
         * Robolectric, Android runtime, or WorkManager.
         *
         * Returns true only when [action] is one of the five supported
         * package lifecycle actions AND [packageName] is non-null and
         * non-blank.
         */
        internal fun shouldRequestRefresh(action: String?, packageName: String?): Boolean {
            if (action == null) return false
            if (packageName == null) return false
            if (packageName.isBlank()) return false
            return action == Intent.ACTION_PACKAGE_ADDED ||
                action == Intent.ACTION_PACKAGE_REMOVED ||
                action == Intent.ACTION_PACKAGE_FULLY_REMOVED ||
                action == Intent.ACTION_PACKAGE_REPLACED ||
                action == Intent.ACTION_PACKAGE_CHANGED
        }
    }
}

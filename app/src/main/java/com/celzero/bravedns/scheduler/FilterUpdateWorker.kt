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
package com.celzero.bravedns.scheduler

import Logger
import Logger.LOG_TAG_SCHEDULER
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.celzero.bravedns.download.FilterSourceDownloadManager
import com.celzero.bravedns.core.filter.FilterSourceCompiler
import com.celzero.bravedns.database.FilterSourceFileStore
import com.celzero.bravedns.database.FilterSourceRepository
import com.celzero.bravedns.service.PersistentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Background worker for scheduled refresh of enabled Advanced Filter Sources.
 *
 * Guarantees (docs/PLAN-FILTER-SOURCE-MANAGER.md §11, §15, §23):
 *  - 24-hour periodic interval ([INTERVAL_HOURS]).
 *  - NetworkType constraint: [NetworkType.UNMETERED] (Wi-Fi/Ethernet only, saving mobile data).
 *  - Unique periodic work: [ExistingPeriodicWorkPolicy.KEEP] prevents duplicate or runaway jobs.
 *  - Target: queries existing enabled FilterSource rows only.
 *  - NEVER calls `ensurePresets()` (G0 concurrency rule: seeding is startup-only).
 *  - NEVER compiles rules or touches FilterEngine (B3/B4 boundaries strictly respected).
 *  - Individual source failure does not abort other downloads or delete existing current.txt (G7 isolation).
 */
class FilterUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    private val downloadManager by inject<FilterSourceDownloadManager>()
    private val compiler by inject<FilterSourceCompiler>()
    private val fileStore by inject<FilterSourceFileStore>()
    private val repository by inject<FilterSourceRepository>()
    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAG = "FilterUpdateWorker"

        /** Unique name used for [WorkManager.enqueueUniquePeriodicWork]. */
        const val WORK_NAME = "FilterUpdateWorker"

        /** Scheduled refresh interval in hours (24 hours). */
        const val INTERVAL_HOURS = 24L

        /**
         * Schedule unique periodic work for refreshing filter sources.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()

            val request = PeriodicWorkRequestBuilder<FilterUpdateWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Logger.i(LOG_TAG_SCHEDULER, "$TAG; scheduled unique periodic filter update (every ${INTERVAL_HOURS}h on UNMETERED network)")
        }

        /**
         * Cancel scheduled unique periodic work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Logger.i(LOG_TAG_SCHEDULER, "$TAG; periodic filter update cancelled")
        }

        /**
         * Deterministic SHA-256 hash of the sorted, comma-joined set of enabled FilterSource IDs.
         * Used as an inexpensive watermark to detect changes in the enabled-set so the worker
         * can decide whether a recompile is needed without diffing source bodies.
         */
        fun computeEnabledSetHash(enabledIds: List<Int>): String {
            val canonical = enabledIds.sorted().joinToString(",")
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            Logger.i(LOG_TAG_SCHEDULER, "$TAG; doWork: beginning scheduled filter update check")
            try {
                val results = downloadManager.refreshAllEnabled()
                val hasContentChange = results.any { result ->
                    result is FilterSourceDownloadManager.DownloadResult.Success && !result.notModified && result.bytesDownloaded > 0
                }
                val enabledSources = repository.getEnabledSources()
                val enabledIds = enabledSources.map { it.id }
                val currentEnabledHash = computeEnabledSetHash(enabledIds)
                val enabledSetChanged = currentEnabledHash != persistentState.lastCompiledEnabledSetHash
                val artifactAbsent = !fileStore.compiledRulesFile().exists()
                val triggerCompile = hasContentChange || enabledSetChanged || artifactAbsent

                Logger.i(
                    LOG_TAG_SCHEDULER,
                    "$TAG; doWork: hasContentChange=$hasContentChange, enabledSetChanged=$enabledSetChanged, artifactAbsent=$artifactAbsent, triggerCompile=$triggerCompile"
                )

                if (triggerCompile) {
                    val outcome = compiler.compileAllEnabled()
                    if (outcome.success) {
                        val nextGen = persistentState.advancedFilterGeneration + 1L
                        persistentState.commitAdvancedFilterCompilation(currentEnabledHash, nextGen)
                        Logger.i(LOG_TAG_SCHEDULER, "$TAG; compile succeeded, generation incremented to $nextGen")
                    } else {
                        Logger.e(LOG_TAG_SCHEDULER, "$TAG; compileAllEnabled failed: ${outcome.errorMessage}")
                    }
                } else {
                    Logger.i(LOG_TAG_SCHEDULER, "$TAG; no compile needed (no changes)")
                }

                Result.success()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_SCHEDULER, "$TAG; doWork: unhandled error during scheduled update: ${e.message}", e)
                Result.failure()
            }
        }
    }
}

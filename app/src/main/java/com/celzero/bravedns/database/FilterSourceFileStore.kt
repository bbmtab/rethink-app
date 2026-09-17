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

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Owns the on-disk directory layout for Advanced Filter Sources under
 * `appContext.filesDir/filter_sources/source_<id>/`. B1 stores layouts only: it MUST NOT
 * download, compile, or write filter bodies. Filter bodies arrive via B2 (FilterSourceDownloadManager
 * → `download.tmp` → atomic promote to `current.txt`) and are compiled by B3 (FilterSourceCompiler).
 *
 * Path safety (plan §13): every path is derived from the database row id, NEVER from the user-
 * entered source name/url/category. That eliminates path-traversal vectors and prevents invalid
 * filename problems (e.g. URL slashes).
 *
 * Layout (per source):
 * ```
 * <filesDir>/
 * ├── filter_sources/
 * │   └── source_<id>/
 * │       ├── current.txt       # last known-good raw filter list (B2/B3 reads)
 * │       └── download.tmp      # in-flight streaming download target (B2)
 * ├── adblock_rules.txt         # last-known-good compiled artifact (B4 atomic activate target)
 * └── adblock_rules.new         # staged compiled artifact (B3 atomic-promotion writer)
 *
 * <cacheDir>/
 * └── filter_rules_cache.bin    # pre-parsed binary rule cache (FilterEngine CACHE_VERSION=5 layout)
 * ```
 */
class FilterSourceFileStore(private val context: Context) {

    private val filesDir: File
        get() = context.applicationContext.filesDir

    /** Absolute path of the `filter_sources/` root. */
    fun rootDirectory(): File = File(filesDir, Companion.ROOT_DIR_NAME)

    /** Absolute path of the per-source directory. */
    fun sourceDirectory(id: Int): File =
        File(rootDirectory(), "${Companion.SOURCE_DIR_PREFIX}$id")

    /** Absolute path to the per-source `current.txt`. */
    fun currentFile(id: Int): File =
        File(sourceDirectory(id), Companion.CURRENT_FILE_NAME)

    /** Absolute path to the per-source `download.tmp`. */
    fun downloadTempFile(id: Int): File =
        File(sourceDirectory(id), Companion.DOWNLOAD_TMP_NAME)

    /**
     * Absolute path to the staged compiled rules (`filesDir/adblock_rules.new`). B3 writes
     * the staged artifact here; B4 atomically renames it to [compiledRulesFile]. B3 MUST NOT
     * touch [compiledRulesFile] — that's a B4 boundary.
     */
    fun stagedRulesFile(): File =
        File(filesDir, Companion.STAGED_RULES_NAME)

    /**
     * Absolute path to the last-known-good compiled rules (`filesDir/adblock_rules.txt`).
     * Reserved for B4 atomic activation; exposed here only for path math and tests. B3 MUST NOT
     * write this file — last-known-good must be preserved on compile failure.
     */
    fun compiledRulesFile(): File =
        File(filesDir, Companion.COMPILED_RULES_NAME)

    /**
     * Absolute path to the pre-parsed binary cache (`cacheDir/filter_rules_cache.bin`).
     * B3 writes this mirroring FilterEngine's `CACHE_VERSION=5` layout. FilterEngine's
     * `loadRulesFromFile()` reads it on next startup (or explicitly on hot-reload).
     */
    fun cacheFile(): File =
        File(context.applicationContext.cacheDir, Companion.CACHE_FILE_NAME)

    /** Canonical relativeFilePath string for the source id (what Room stores). */
    fun relativeFilePathFor(id: Int): String = Companion.relativeFilePathFor(id)

    /** Canonical download.tmp path string for the source id. */
    fun relativeDownloadTempFor(id: Int): String = Companion.relativeDownloadTmpFor(id)

    /**
     * Ensure the root directory exists. Idempotent — safe to call repeatedly. Throws [IOException]
     * if directory creation fails.
     */
    @Throws(IOException::class)
    fun ensureRootExists(): File {
        val root = rootDirectory()
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Unable to create filter_sources root: ${root.absolutePath}")
        }
        return root
    }

    /**
     * Recursively remove the per-source directory at `source_<id>/`. Leaves siblings intact
     * and never removes the parent `filter_sources/` directory — that protects unrelated
     * source directories during cleanup (plan §15).
     *
     * Returns true if the directory was not present OR was successfully removed. Returns false
     * only if a remove attempt failed.
     */
    fun removeSourceDirectory(id: Int): Boolean {
        val dir = sourceDirectory(id)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    companion object {
        // Layout constants exposed so tests (and repository code that doesn't yet hold an
        // instance reference) can drive the path math without instantiating a Context-bound object.
        const val ROOT_DIR_NAME = "filter_sources"
        const val SOURCE_DIR_PREFIX = "source_"
        const val CURRENT_FILE_NAME = "current.txt"
        const val DOWNLOAD_TMP_NAME = "download.tmp"
        const val STAGED_RULES_NAME = "adblock_rules.new"
        const val COMPILED_RULES_NAME = "adblock_rules.txt"
        const val CACHE_FILE_NAME = "filter_rules_cache.bin"

        /** Build the canonical relativeFilePath for [id]'s `current.txt`. Plan §11 layout. */
        fun relativeFilePathFor(id: Int): String =
            "$ROOT_DIR_NAME/${SOURCE_DIR_PREFIX}$id/$CURRENT_FILE_NAME"

        /** Build the relative download.tmp path. */
        fun relativeDownloadTmpFor(id: Int): String =
            "$ROOT_DIR_NAME/${SOURCE_DIR_PREFIX}$id/$DOWNLOAD_TMP_NAME"
    }
}

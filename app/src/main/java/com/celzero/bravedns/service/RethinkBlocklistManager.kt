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
package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_DNS
import Logger.LOG_TAG_VPN
import android.content.Context
import com.celzero.bravedns.R
import com.celzero.bravedns.core.filter.FilterEngine
import com.celzero.bravedns.core.filter.FilterEngine.RuleStats
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.data.FileTagDeserializer
import com.celzero.bravedns.database.LocalBlocklistPacksMap
import com.celzero.bravedns.database.LocalBlocklistPacksMapRepository
import com.celzero.bravedns.database.RemoteBlocklistPacksMap
import com.celzero.bravedns.database.RemoteBlocklistPacksMapRepository
import com.celzero.bravedns.database.RethinkLocalFileTag
import com.celzero.bravedns.database.RethinkLocalFileTagRepository
import com.celzero.bravedns.database.RethinkRemoteFileTag
import com.celzero.bravedns.database.RethinkRemoteFileTagRepository
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG
import com.celzero.bravedns.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.RDNS
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.io.use
import kotlin.text.isNotBlank
import kotlin.text.isNotEmpty
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RethinkBlocklistManager : KoinComponent {

    private val syncMutex = Mutex()

    private val remoteFileTagRepository by inject<RethinkRemoteFileTagRepository>()
    private val remoteBlocklistPacksMapRepository by inject<RemoteBlocklistPacksMapRepository>()
    private val localFileTagRepository by inject<RethinkLocalFileTagRepository>()
    private val localBlocklistPacksMapRepository by inject<LocalBlocklistPacksMapRepository>()
    private val persistentState by inject<PersistentState>()

    private const val EMPTY_SUBGROUP = "others"

    private const val PARENTAL_CONTROL_TAG = "ParentalControl"
    private const val SECURITY_TAG = "Security"
    private const val PRIVACY_TAG = "Privacy"

    data class RethinkBlockType(val name: String, val label: Int, val desc: Int)

    data class PacksMappingKey(val pack: String, val level: Int)

    enum class RethinkBlocklistType {
        LOCAL,
        REMOTE;

        companion object {
            fun getType(id: Int): RethinkBlocklistType {
                if (id == LOCAL.ordinal) return LOCAL

                return REMOTE
            }
        }

        fun isLocal(): Boolean {
            return this == LOCAL
        }

        fun isRemote(): Boolean {
            return this == REMOTE
        }
    }

    enum class DownloadType(val id: Int) {
        LOCAL(0),
        REMOTE(1);

        fun isLocal(): Boolean {
            return this == LOCAL
        }

        fun isRemote(): Boolean {
            return this == REMOTE
        }
    }

    val PARENTAL_CONTROL =
        RethinkBlockType(
            PARENTAL_CONTROL_TAG,
            R.string.rbl_parental_control,
            R.string.rbl_parental_control_desc
        )
    val SECURITY = RethinkBlockType(SECURITY_TAG, R.string.rbl_security, R.string.rbl_security_desc)
    val PRIVACY = RethinkBlockType(PRIVACY_TAG, R.string.rbl_privacy, R.string.rbl_privacy_desc)

    // read and parse the json file, either remote or local blocklist
    // returns the parsed FileTag list, on error return empty array list
    suspend fun readJson(context: Context, type: DownloadType, timestamp: Long): Boolean {
        // TODO: merge both the remote and local json parsing into one
        return if (type.isRemote()) {
            readRemoteJson(context, timestamp)
        } else {
            readLocalJson(context, timestamp)
        }
    }

    private suspend fun readLocalJson(context: Context, timestamp: Long): Boolean {
        val packsBlocklistMapping: Multimap<PacksMappingKey, Int> = HashMultimap.create()
        try {
            val dbFileTagLocal: MutableList<RethinkLocalFileTag> = mutableListOf()
            val dir =
                Utilities.blocklistDownloadBasePath(
                    context,
                    LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    timestamp
                )

            val file = Utilities.blocklistFile(dir, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return false

            val jsonString = file.bufferedReader().use { it.readText() }
            // register the type adapter to deserialize the class.
            // see FileTag.kt for more info (FileTagDeserializer)
            val gson =
                GsonBuilder()
                    .registerTypeAdapter(FileTag::class.java, FileTagDeserializer())
                    .create()
            val entries: JsonObject = Gson().fromJson(jsonString, JsonObject::class.java)
            entries.entrySet().forEach {
                val t = gson.fromJson(it.value, FileTag::class.java)
                // add subg tag as "others" if its empty
                if (t.subg.isEmpty()) {
                    t.subg = EMPTY_SUBGROUP
                }
                t.group = t.group.lowercase()
                val l = getRethinkLocalObj(t)

                if (l.pack?.isNotEmpty() == true) {
                    l.pack?.forEachIndexed { index, s ->
                        // if the pack is empty or level is empty, then skip
                        if (s.isEmpty() || l.level == null || l.level?.isEmpty() == true) {
                            l.level = arrayListOf()
                            packsBlocklistMapping.put(PacksMappingKey(s, 0), l.value)
                            return@forEachIndexed
                        }
                        val level = l.level?.getOrNull(index) ?: 2
                        packsBlocklistMapping.put(PacksMappingKey(s, level), l.value)
                    }
                }

                dbFileTagLocal.add(l)
            }
            val selectedTags = localFileTagRepository.getSelectedTags()
            // edge case: found a residual block list entry still available in the database
            // during the insertion of new block list entries. This occurred when the number of
            // block lists in the preceding list is greater than the current list. Always
            // empty the data base entries before creating new entries.
            localFileTagRepository.deleteAll()
            localFileTagRepository.insertAll(dbFileTagLocal.toList())
            localFileTagRepository.updateTags(selectedTags.toSet(), 1)
            localBlocklistPacksMapRepository.deleteAll()
            // insert the packs and level mapping in the database
            localBlocklistPacksMapRepository.insertAll(
                packsBlocklistMapping.keySet().map { key ->
                    LocalBlocklistPacksMap(
                        key.pack,
                        key.level,
                        packsBlocklistMapping.get(key).toList(),
                        dbFileTagLocal.firstOrNull { it.pack?.contains(key.pack) == true }?.group ?: ""
                    )
                }
            )
            Logger.i(LOG_TAG_DNS, "New Local blocklist files inserted into database")
            return true
        } catch (ioException: IOException) {
            Logger.e(
                LOG_TAG_DNS,
                "Failure reading json file, blocklist type: remote, timestamp: $timestamp",
                ioException
            )
        }
        return false
    }

    private suspend fun readRemoteJson(context: Context, timestamp: Long): Boolean {
        try {
            val packsBlocklistMapping: Multimap<PacksMappingKey, Int> = HashMultimap.create()
            val dbFileTagRemote: MutableList<RethinkRemoteFileTag> = mutableListOf()

            val dir =
                Utilities.blocklistDownloadBasePath(
                    context,
                    REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    timestamp
                )

            val file = Utilities.blocklistFile(dir, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return false

            // register type-adapter to enable custom deserialization of the FileTag object.
            // see FileTag.kt for more info (FileTagDeserializer)
            val gson =
                GsonBuilder()
                    .registerTypeAdapter(FileTag::class.java, FileTagDeserializer())
                    .create()

            val jsonString = file.bufferedReader().use { it.readText() }
            val entries: JsonObject = Gson().fromJson(jsonString, JsonObject::class.java)
            entries.entrySet().forEach {
                val t = gson.fromJson(it.value, FileTag::class.java)
                // add subg tag as "others" if its empty
                if (t.subg.isEmpty()) {
                    t.subg = EMPTY_SUBGROUP
                }
                t.group = t.group.lowercase()
                val r = getRethinkRemoteObj(t)

                if (r.pack?.isNotEmpty() == true) {
                    r.pack?.forEachIndexed { index, s ->
                        // if the pack is empty or the level is null, skip the entry
                        if (s.isEmpty() || r.level == null || r.level?.isEmpty() == true) {
                            r.level = arrayListOf()
                            packsBlocklistMapping.put(PacksMappingKey(s, 0), r.value)
                            return@forEachIndexed
                        }
                        // if the level is empty, then set the level to 2 (assume highest) #756
                        val level = r.level?.getOrNull(index) ?: 2
                        packsBlocklistMapping.put(PacksMappingKey(s, level), r.value)
                    }
                }
                dbFileTagRemote.add(r)
                // if (DEBUG) Log.d(Logger.LOG_TAG_DNS, "Remote file tag: $r")
                Logger.i(
                    LOG_TAG_DNS,
                    "Remote file tag: ${r.group}, ${r.pack}, ${r.simpleTagId}, ${r.level}, ${r.value}, ${r.entries}, ${r.isSelected}, ${r.show}, ${r.subg}, ${r.uname}, ${r.url}, ${r.vname}"
                )
            }
            val selectedTags = remoteFileTagRepository.getSelectedTags()
            // edge case: found a residual block list entry still available in the database
            // during the insertion of new block list entries. This occurred when the number of
            // block lists in the preceding list is greater than the current list. Always
            // empty the data base entries before creating new entries.
            remoteFileTagRepository.deleteAll()
            remoteFileTagRepository.insertAll(dbFileTagRemote.toList())
            remoteFileTagRepository.updateTags(selectedTags.toSet(), 1)
            // insert the packs and level mapping in the database
            remoteBlocklistPacksMapRepository.deleteAll()
            remoteBlocklistPacksMapRepository.insertAll(
                packsBlocklistMapping.keySet().map { key ->
                    RemoteBlocklistPacksMap(
                        key.pack,
                        key.level,
                        packsBlocklistMapping.get(key).toList(),
                        dbFileTagRemote.firstOrNull { it.pack?.contains(key.pack) == true }?.group ?: ""
                    )
                }
            )
            Logger.i(LOG_TAG_DNS, "New Remote blocklist files inserted into database")
            return true
        } catch (ioException: IOException) {
            Logger.crash(
                LOG_TAG_DNS,
                "Failure reading json file, blocklist type: remote, timestamp: $timestamp",
                ioException
            )
        }
        return false
    }

    private fun getRethinkLocalObj(t: FileTag): RethinkLocalFileTag {
        return RethinkLocalFileTag(
            t.value,
            t.uname,
            t.vname,
            t.group,
            t.subg,
            t.pack,
            t.level,
            t.urls,
            t.show,
            t.entries,
            t.simpleTagId,
            t.isSelected
        )
    }

    private fun getRethinkRemoteObj(t: FileTag): RethinkRemoteFileTag {
        return RethinkRemoteFileTag(
            t.value,
            t.uname,
            t.vname,
            t.group,
            t.subg,
            t.pack,
            t.level,
            t.urls,
            t.show,
            t.entries,
            t.simpleTagId,
            t.isSelected
        )
    }

    suspend fun updateFiletagRemote(remote: RethinkRemoteFileTag) {
        remoteFileTagRepository.update(remote)
    }

    suspend fun updateFiletagLocal(local: RethinkLocalFileTag) {
        localFileTagRepository.update(local)
    }

    suspend fun updateFiletagsRemote(values: Set<Int>, isSelected: Int) {
        remoteFileTagRepository.updateTags(values, isSelected)
    }

    suspend fun updateFiletagsLocal(values: Set<Int>, isSelected: Int) {
        localFileTagRepository.updateTags(values, isSelected)
    }

    suspend fun getSelectedFileTagsLocal(): List<Int> {
        return localFileTagRepository.getSelectedTags()
    }

    suspend fun getSelectedFileTagsRemote(): List<Int> {
        return remoteFileTagRepository.getSelectedTags()
    }

    suspend fun clearTagsSelectionRemote() {
        remoteFileTagRepository.clearSelectedTags()
    }

    suspend fun clearTagsSelectionLocal() {
        localFileTagRepository.clearSelectedTags()
    }

    fun cpSelectFileTag(localFileTags: RethinkLocalFileTag): Int {
        io {
            val selectedTags =
                getTagsFromStamp(persistentState.localBlocklistStamp, RethinkBlocklistType.LOCAL)
                    .toMutableSet()

            // remove the tag from the local blocklist if it exists and current selection is 0
            if (selectedTags.contains(localFileTags.value) && !localFileTags.isSelected) {
                selectedTags.remove(localFileTags.value)
            } else if (!selectedTags.contains(localFileTags.value) && localFileTags.isSelected) {
                // only add the tag if it is not already present
                selectedTags.add(localFileTags.value)
            } else {
                // no-op
            }

            val stamp = getStamp(selectedTags, RethinkBlocklistType.LOCAL)
            persistentState.localBlocklistStamp = stamp
        }
        return localFileTagRepository.contentUpdate(localFileTags)
    }

    suspend fun getStamp(fileValues: Set<Int>, type: RethinkBlocklistType): String {
        return try {
            val flags = convertListToCsv(fileValues)
            val flags2Stamp = getRDNS(type)?.flagsToStamp(flags, Backend.EB32) ?: ""
            Logger.d(LOG_TAG_VPN, "${type.name} flags: $flags; stamp: $flags2Stamp")
            flags2Stamp
        } catch (e: java.lang.Exception) {
            Logger.e(LOG_TAG_VPN, "err stamp2tags for $fileValues of type: ${type.name} ${e.message}, $e")
            ""
        }
    }

    suspend fun getTagsFromStamp(stamp: String, type: RethinkBlocklistType): Set<Int> {
        return try {
            val tags = convertCsvToList(getRDNS(type)?.stampToFlags(stamp))
            Logger.d(LOG_TAG_VPN, "${type.name} stamp: $stamp; tags: $tags")
            tags
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "err tags2stamp for $stamp of type: ${type.name} ${e.message}, $e")
            setOf()
        }
    }

    /**
     * Syncs selected blocklists to adblock_rules.txt for MITM filtering.
     * Downloads/reads selected blocklist rule files, parses via FilterEngine,
     * writes combined rules to filesDir/adblock_rules.txt, updates stamp.
     *
     * @param context App context for filesDir access
     * @param blocklistId Optional specific tag ID to sync; if null, syncs all selected
     * @return RuleStats with breakdown of parsed rules
     */
    suspend fun syncBlocklistToAdblockRules(
        context: Context,
        blocklistId: Int? = null
    ): FilterEngine.RuleStats = syncMutex.withLock {
        // 1. Determine which tag IDs to sync
        val selectedTags = if (blocklistId != null) {
            setOf(blocklistId)
        } else {
            val local = getSelectedFileTagsLocal().toSet()
            val remote = getSelectedFileTagsRemote().toSet()
            local + remote
        }

        Logger.i(LOG_TAG_DNS, "syncBlocklistToAdblockRules: syncing tags $selectedTags")

        // 2. Get the latest downloaded JSON metadata dir (both local and remote)
        val localTimestamp = persistentState.localBlocklistTimestamp
        val remoteTimestamp = persistentState.remoteBlocklistTimestamp

        val allRulesText = mutableListOf<String>()

        // Helper to read rule files from a timestamped download directory
        suspend fun readRuleFilesFromDir(type: DownloadType, timestamp: Long, tagsToSync: Set<Int>) {
            if (timestamp == 0L) return
            val dirPath = Utilities.blocklistDownloadBasePath(
                context,
                if (type.isLocal()) LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME else REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                timestamp
            )
            val dir = File(dirPath)
            if (!dir.exists()) return

            // Parse the JSON metadata to find selected blocklists
            val file = Utilities.blocklistFile(dirPath, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return
            val jsonString = file.bufferedReader().use { it.readText() }
            val gson = GsonBuilder()
                .registerTypeAdapter(FileTag::class.java, FileTagDeserializer())
                .create()
            val entries: JsonObject = Gson().fromJson(jsonString, JsonObject::class.java)

            entries.entrySet().forEach {
                val t = gson.fromJson(it.value, FileTag::class.java)
                if (tagsToSync.contains(t.value)) {
                    // Found a selected blocklist - read its rule text file
                    // Rule files are typically named after the tag or simpleTagId
                    // simpleTagId is Int; INVALID_SIMPLE_TAG_ID = -1 means not set
                    val ruleFileName = if (t.simpleTagId != -1) t.simpleTagId.toString() else "blocklist_${t.value}.txt"
                    val ruleFile = File(dir, ruleFileName)
                    if (ruleFile.exists()) {
                        val ruleText = ruleFile.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                        allRulesText.add(ruleText)
                        Logger.i(LOG_TAG_DNS, "Read rules from ${ruleFile.name}: ${ruleText.length} chars")
                    } else {
                        Logger.w(LOG_TAG_DNS, "Rule file not found for tag ${t.value}: expected $ruleFileName")
                    }
                }
            }
        }

        // Read local and remote rule files
        readRuleFilesFromDir(DownloadType.LOCAL, localTimestamp, selectedTags)
        readRuleFilesFromDir(DownloadType.REMOTE, remoteTimestamp, selectedTags)

        if (allRulesText.isEmpty()) {
            Logger.w(LOG_TAG_DNS, "No rule text found for selected tags; writing empty adblock_rules.txt")
        }

        // 3. Parse all rules through FilterEngine to validate and count
        FilterEngine.clear()
        val combinedText = allRulesText.joinToString("\n")
        if (combinedText.isNotBlank()) {
            FilterEngine.loadRules(combinedText)
        }

        // 4. Write combined rules to adblock_rules.txt in filesDir
        val adblockRulesFile = File(context.filesDir, "adblock_rules.txt")
        adblockRulesFile.writeText(combinedText, StandardCharsets.UTF_8)
        // fsync guard: ensure data is flushed to storage
        try {
            val fos = FileOutputStream(adblockRulesFile.absolutePath, true)
            fos.fd.sync()
            fos.close()
        } catch (e: IOException) {
            Logger.w(LOG_TAG_DNS, "Failed to fsync adblock_rules.txt: ${e.message}")
        }
        // Delete file if empty to avoid parsing empty file
        if (adblockRulesFile.length() == 0L) {
            adblockRulesFile.delete()
            Logger.i(LOG_TAG_DNS, "Deleted empty adblock_rules.txt")
        }
        Logger.i(LOG_TAG_DNS, "Wrote adblock_rules.txt (${combinedText.length} chars) to ${adblockRulesFile.absolutePath}")

        // 5. Update localBlocklistStamp with current selection
        val newStamp = getStamp(selectedTags, RethinkBlocklistType.LOCAL)
        persistentState.localBlocklistStamp = newStamp
        persistentState.numberOfLocalBlocklists = selectedTags.size
        persistentState.blocklistEnabled = true

        // 6. Return stats for UI feedback
        return FilterEngine.getRuleStats()
    }

    private fun convertCsvToList(csv: String?): Set<Int> {
        if (csv == null) return setOf()

        return csv.split(",").map { it.toIntOrNull() ?: 0 }.toSet()
    }

    private fun convertListToCsv(s: Set<Int>): String {
        return s.joinToString(",")
    }

    private suspend fun getRDNS(type: RethinkBlocklistType): RDNS? {
        return VpnController.getRDNS(type)
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}

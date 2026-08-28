# Filter Source Manager â€” Implementation & Architecture Plan

> **STATUS: CURRENT â€” PHASE-1D-B IMPLEMENTATION PLAN**
>
> Authority:
> - [[docs/DECISIONS.md#DECISION-008]] â€” Original Rethink DNS and Advanced Filter Sources are independent subsystems
> - [[docs/UNIFIED_UI_ARCHITECTURE.md]] â€” Plus = Filters (Filters surface, Exclusions)
> - [[docs/ARCHITECTURE-MAPPING.md]] â€” Packet flow: DNS sinkhole inheritance independent from FilterEngine
>
> Scope: Advanced Filter Sources only.
>
> Rethink DNS blocklists are NOT FilterEngine source material.
> This plan does not manage, synchronize, compile, or mirror Rethink DNS blocklists.

---

## 1. Advanced Filter Sources Ownership

This manager owns **only** Advanced Filter Sources â€” external filter subscription lists
delivered to `FilterEngine` for Layer 7 MITM content filtering:

```
Plus (user-facing)
â””â”€â”€ Advanced Filtering
    â””â”€â”€ Manage Filters
        â”œâ”€â”€ Ads
        â”œâ”€â”€ Privacy
        â”œâ”€â”€ Social
        â”œâ”€â”€ Annoyances
        â”œâ”€â”€ Security
        â”œâ”€â”€ Language-specific
        â”œâ”€â”€ Other Filters
        â””â”€â”€ Custom Filters
                â†“
            enabled FilterSource list
                â†“
            FilterEngine
                (automatic rule-type handling â€” see Â§5.1)
                â”œâ”€â”€ HTTP/network filtering
                â”œâ”€â”€ Cosmetic CSS
                â”œâ”€â”€ Scriptlet
                â”œâ”€â”€ Procedural
                â”œâ”€â”€ CSP
                â””â”€â”€ HTML filtering
```

**Note:** The Plus-tab tree above is **source/category-oriented** â€” normal users select filter *content* grouped by purpose (Ads / Privacy / â€¦), not which internal engine technique to enable. Network/Cosmetic/Scriptlet/Procedural/CSP/HTML handling is **automatic** inside `FilterEngine`: it inspects compiled rule subtypes and applies each supported injection path without a per-type user toggle. Subtype support and per-source rule-type counts remain available in **diagnostics** only. See DECISION-009.

**Original Rethink DNS policy** (Configure â†’ DNS â†’ Rethink Blocklists â†’ DNS sinkhole) is a
**separate, independent subsystem** managed elsewhere (not by this manager). There is
**NO source-flow: Rethink DNS blocklists â†’ FilterEngine**. See DECISION-008.

## 2. Core Architectural Principles & Guardrails

1. **Source Diagnostics & Compatibility Breakdown (Not Blind Compatibility):**
   - EasyList, AdGuard Base, AdGuard Annoyances, and custom feeds have overlapping but
     distinct syntax/modifiers.
   - `FilterSourceCompiler` records per-source metrics: `parsedRules`,
     `unsupportedRules`, `invalidRules`, and rule sub-type breakdown
     (network, cosmetic, procedural, scriptlet, csp, html).
   - This provides clear visibility if a list has 95% compatibility vs 60% unsupported
     modifiers.

2. **Accurate Complexity & Matching Semantics:**
   - Suffix Trie domain matching is $O(L)$ where $L$ is the number of hostname domain
     labels/depth, providing fast localized candidate lookup.
   - Generic wildcard/regex rules are evaluated against candidates in priority order
     (Whitelists with `$important` &rarr; Blocks with `$important` &rarr; Whitelists &rarr;
     Blocks).

3. **Room for Metadata, Filesystem for Raw Content:**
   - SQLite/Room stores only structured metadata (`FilterSource` entity).
   - Raw downloaded filter lists live on the filesystem under
     `filesDir/filter_sources/<source-id>/current.txt` to prevent SQLite
     `CursorWindow` memory allocation spikes.

4. **Staged Compilation & Atomic Swap (Zero-Downtime / Fallback Safety):**
   - Pipeline pattern:
     ```text
     download.tmp â†’ validate â†’ parse â†’ compatibility metrics â†’
         compile staged output â†’ sanity/stat checks â†’
         atomic activation â†’ FilterEngine reload
     ```
   - If a download is corrupted or compilation fails, **existing active rules remain
     untouched**. Retains last-known-good compiled artifact.

5. **Streaming Ingestion & Low-RAM Safety (Mi A1 / 3GB RAM Protection):**
   - Compiler streams input line-by-line via `BufferedReader`.
   - Never loads entire large files (e.g. 5â€“10 MB feeds) into single in-memory
     `String` buffers (`readText()` is strictly prohibited during compilation).

6. **Automatic Rule-Type Handling (no per-type user toggles):**
   - **Source Subscription:** Dictates which rules are downloaded, parsed, and
     compiled into the active rule set â€” grouped for the user by category
     (Ads, Privacy, Social, Annoyances, Security, Language-specific, Other Filters,
     Custom Filters). Users toggle *sources*, never individual engine techniques.
   - **Automatic Rule-Type Handling:** Once a `FilterSource` is enabled, `FilterEngine`
     inspects the compiled rule subtypes and applies each supported injection path
     (network, cosmetic, scriptlet, procedural, CSP, HTML) automatically.
     - *Example:* If AdGuard Base is enabled, its cosmetic / scriptlet / procedural
       rules are compiled; `LocalHttpsProxy` applies each subtype automatically based
       on what exists â€” there is no "Cosmetic CSS" switch to flip off.
   - Subtype support and per-source rule-type counts are exposed in **diagnostics**
     only, so users can audit coverage without gating engine behavior.

7. **Category-Oriented Preset Defaults:**
   - Out of the box, default to 1â€“2 general-purpose Advanced Filter Sources
     (e.g., AdGuard Base + a lightweight tracker list).
   - Specialized lists (EasyPrivacy, Annoyances, Social, URL Tracking) are opt-in
     to avoid duplicate rule explosion, RAM bloat, and rule conflicts.

8. **Clear UI Boundary in Plus Tab â€” Filters Only:**
   - **Advanced Filtering:** Houses the `Manage Filters` surface â€” an
     enabled-source summary (`X lists enabled â€¢ N rules â€¢ updated â€¦`) and a
     category-oriented source selector (Ads, Privacy, Social, Annoyances,
     Security, Language-specific, Other Filters, Custom Filters). No per-engine
     capability toggles are exposed here; rule-type handling is automatic
     (see principle #6) and exposed only via diagnostics.
   - **DNS Blocklist â†’ MITM:** Retired / HISTORICAL. Domain-level DNS blocking
     now propagates automatically via DNS sinkhole inheritance
     (`activeNetwork.getAllByName()` â†’ Rethink DNS resolver â†’ `0.0.0.0` sinkhole).
     No manual bridge is required or exposed in Plus. See DECISION-008 / Section 12.

---

## Filter Source Manager â€” UX Taxonomy (Plus tab)

### Plus â†’ Advanced Filtering â€” target UX

```
PLUS
â”‚
â”œâ”€â”€ 1. HTTPS Inspection
â”‚   â”œâ”€â”€ Master toggle (persist: httpsInspectionEnabled)
â”‚   â”œâ”€â”€ CA status badge
â”‚   â”œâ”€â”€ CA actions: Install / Re-install / Export to Downloads
â”‚   â””â”€â”€ Per-app HTTPS filtering
â”‚
â”œâ”€â”€ 2. Advanced Filtering
â”‚   â”œâ”€â”€ Enabled filter summary â€” e.g. `2 lists enabled â€¢ 48,210 rules â€¢ updated 1h ago`
â”‚   â””â”€â”€ Manage Filters  â”€â”€â–º  (category-oriented source selector)
â”‚       â”œâ”€â”€ Ads
â”‚       â”œâ”€â”€ Privacy
â”‚       â”œâ”€â”€ Social
â”‚       â”œâ”€â”€ Annoyances
â”‚       â”œâ”€â”€ Security
â”‚       â”œâ”€â”€ Language-specific
â”‚       â”œâ”€â”€ Other Filters
â”‚       â””â”€â”€ Custom Filters
â”‚
â””â”€â”€ 3. Exclusions
    â”œâ”€â”€ Domain exclusions
    â””â”€â”€ App exclusions
```

### What the user controls vs. what the engine does automatically

| User-facing control | Source of truth |
|---------------------|-----------------|
| **Enable / disable a `FilterSource`** (per category) | `FilterSource.enabled` flag â†’ included in next staged compile |
| **Add Custom URL** | new `FilterSource` (category = Custom Filters by default) |
| **Update All Sources** | triggers `FilterSourceDownloadManager` refresh |
| **HTTPS Inspection ON/OFF** | `PersistentState.httpsInspectionEnabled` |

| Automatic FilterEngine behavior (NOT user toggles) | Diagnostic surfacing |
|----------------------------------------------------|----------------------|
| Network rule matching (`\|\|domain^`, `\|http*`, `/regex/`) | per-source `[Net: â€¦]` badge |
| Cosmetic CSS injection (`##selector`) | per-source `[CSS: â€¦]` badge |
| Scriptlet injection (`#%#//scriptlet(...)`) | per-source `[Scriptlet: â€¦]` badge |
| Procedural filtering (`#?#:has(...)`) | per-source `[Proc: â€¦]` badge |
| CSP header injection (`$csp=â€¦`) | per-source `[CSP: â€¦]` badge |
| HTML element removal (`##^tag`) | per-source `[HTML: â€¦]` badge |

> **Rule:** Normal users select *what filter content* to enable (sources/categories),
> not *which internal engine technique* to enable. Network/Cosmetic/Scriptlet/
> Procedural/CSP/HTML handling is determined automatically by `FilterEngine` from the
> compiled rule subtypes. Subtype support and per-source rule-type counts are
> exposed **only in diagnostics** (per-source detail screen), never as Plus-tab feature
> toggles. See DECISION-009.

### Preset-to-category mapping (initial)

| Category | Preset `FilterSource`(s) | Default enabled |
|----------|---------------------------|------------------|
| Ads | EasyList, AdGuard Base | AdGuard Base **Yes**; EasyList opt-in |
| Privacy | AdGuard Tracking Protection, EasyPrivacy | opt-in |
| Social | Social media blockers | opt-in |
| Annoyances | AdGuard Annoyances, Fanboy Annoyances | opt-in |
| Security | Security/malware lists | opt-in |
| Language-specific | locale-scoped lists (e.g. ID, CN) | opt-in |
| Other Filters | non-category-specific third-party lists | opt-in |
| Custom Filters | User-provided URLs | opt-in |

> `category` is **organizational metadata** attached to a `FilterSource`. It does **not**
> restrict the parser â€” the streaming compiler auto-detects every rule subtype present in
> the fetched list regardless of category.

---

## 3. Data Model & Storage Architecture

### 3.1 Room Database Entity: `FilterSource`

```kotlin
package com.celzero.bravedns.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "FilterSource")
data class FilterSource(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,                      // e.g., "AdGuard Base", "EasyList", "Custom URL"
    val url: String,                       // HTTP/HTTPS URL
    val category: String,                  // ADS, PRIVACY, SECURITY, ANNOYANCES, CUSTOM
    val enabled: Boolean = true,           // Included in active compilation
    val isPreset: Boolean = false,         // Built-in preset vs user custom
    val lastUpdated: Long = 0L,            // Timestamp of last successful sync
    val lastUpdateStatus: String = "IDLE", // IDLE, IN_PROGRESS, SUCCESS, FAILED
    val errorMessage: String? = null,
    val etag: String? = null,              // HTTP ETag for 304 conditional request
    val lastModified: String? = null,      // HTTP Last-Modified header
    val checksum: String? = null,          // SHA-256 hash of downloaded raw text

    // Per-Source Diagnostics & Compatibility Breakdown
    val totalLineCount: Int = 0,
    val parsedRuleCount: Int = 0,
    val unsupportedRuleCount: Int = 0,
    val invalidRuleCount: Int = 0,

    // Rule category breakdown
    val networkRuleCount: Int = 0,
    val cosmeticRuleCount: Int = 0,
    val proceduralRuleCount: Int = 0,
    val scriptletRuleCount: Int = 0,
    val cspRuleCount: Int = 0,
    val htmlFilterRuleCount: Int = 0,

    // Path relative to appContext.filesDir (e.g., "filter_sources/source_1/current.txt")
    val relativeFilePath: String
) : Serializable
```

### 3.2 Database DAO: `FilterSourceDao`

```kotlin
@Dao
interface FilterSourceDao {
    @Query("SELECT * FROM FilterSource ORDER BY isPreset DESC, id ASC")
    fun getAllSourcesLiveData(): LiveData<List<FilterSource>>

    @Query("SELECT * FROM FilterSource ORDER BY isPreset DESC, id ASC")
    suspend fun getAllSources(): List<FilterSource>

    @Query("SELECT * FROM FilterSource WHERE enabled = 1")
    suspend fun getEnabledSources(): List<FilterSource>

    @Query("SELECT * FROM FilterSource WHERE id = :id")
    suspend fun getSourceById(id: Int): FilterSource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: FilterSource): Long

    @Update
    suspend fun update(source: FilterSource)

    @Delete
    suspend fun delete(source: FilterSource)

    @Query("UPDATE FilterSource SET enabled = :enabled WHERE id = :id")
    suspend fun updateEnabledStatus(id: Int, enabled: Boolean)

    @Query("UPDATE FilterSource SET lastUpdateStatus = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: Int, status: String, error: String?)
}
```

---

## 4. Download & Subscription Engine (`FilterSourceDownloadManager`)

```
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                     FILTER DOWNLOAD & UPDATE PIPELINE                  â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                                    â”‚
                        1. Trigger Update (Manual / Scheduled)
                                    â”‚
                                    â–¼
                2. HTTP GET with Conditional Headers
                   â€¢ If-None-Match: <cached ETag>
                   â€¢ If-Modified-Since: <cached Last-Modified>
                   â€¢ User-Agent: RethinkDNS-Android/1.0
                                    â”‚
                     â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
                     â–¼                             â–¼
              [304 Not Modified]             [200 OK]
              â€¢ Skip redownload              â€¢ Stream response to `download.tmp`
              â€¢ Keep existing file           â€¢ Enforce 25 MB max file safety limit
              â€¢ Mark status SUCCESS          â€¢ Compute SHA-256 on the fly
                     â”‚                             â”‚
                     â”‚                             â–¼
                     â”‚                      3. Basic Sanity Check
                     â”‚                         â€¢ Reject HTML error pages (e.g. <!DOCTYPE)
                     â”‚                         â€¢ Check non-empty line stream
                     â”‚                             â”‚
                     â”‚                             â–¼
                     â”‚                      4. Atomic File Promotion
                     â”‚                         â€¢ Replace `filter_sources/<id>/current.txt`
                     â”‚                         â€¢ Update ETag, Checksum, LastUpdated in DB
                     â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                                    â”‚
                                    â–¼
                      5. Invoke `FilterSourceCompiler`
```

---

## 5. Streaming Ingestion & Sub-Engine Dispatch (`FilterSourceCompiler`)

```
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                             STREAMING FILTER COMPILATION PIPELINE                          â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                                               â”‚
               Iterate over enabled `filter_sources/<id>/current.txt` (Streaming)
                                               â”‚
                                               â–¼
                                Line-by-Line Syntax Classifier
                                (Record Parsed / Unsupported / Invalid)
                                               â”‚
     â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
     â–¼                  â–¼                  â–¼                  â–¼                  â–¼           â–¼
[Network Rules]    [CSS Rules]      [Procedural]       [Scriptlets]        [CSP Rules]   [HTML Rules]
 `||domain^`        `##selector`     `#?#selector`      `#%#//scriptlet`   `$csp=...`     `##^tag`
     â”‚                  â”‚                  â”‚                  â”‚                  â”‚           â”‚
     â–¼                  â–¼                  â–¼                  â–¼                  â–¼           â–¼
`FilterEngine`   `CosmeticFilter`  `ProceduralFilter` `ScriptletFilter`   `CspInjector` `HtmlFilter`
(Trie & Generic)  (Domain CSS Map)  (JS Observer Map)  (Scriptlet Map)    (CSP Map)     (Jsoup DOM)
     â”‚                  â”‚                  â”‚                  â”‚                  â”‚           â”‚
     â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                                               â”‚
                                               â–¼
                              Staged Persistence to `adblock_rules.new`
                                               â”‚
                                               â–¼
                              Integrity & Rule Count Validation Check
                                               â”‚
                                               â–¼
                              Atomic Rename `adblock_rules.new` âž” `adblock_rules.txt`

                              NOTE: `adblock_rules.txt` is now the compiled output
                              artifact generated EXCLUSIVELY by Advanced Filter
                              Sources. The legacy DNSâ†’MITM bridge use is OBSOLETE
                              per DECISION-008. No Rethink DNS blocklist data
                              feeds this file.

                              Write binary cache `cacheDir/filter_rules_cache.bin`
                                               â”‚
                                               â–¼
                              Hot-Reload Signal to Running VPN
                              â€¢ `BraveVPNService.reloadAdblockRules()`
```

### 5.1 Sub-Engine Distribution & Runtime Execution:

| Sub-Engine | Syntax Markers | Target Layer | Execution Mechanism | Runtime Handling |
|------------|----------------|--------------|---------------------|------------------------|
| **FilterEngine (Network)** | `||domain^`, `\|http*`, `/regex/`, `$third-party`, `$image`, `$script` | MITM Request Interception | Evaluated in `LocalHttpsProxy.onRequestInspection()`. Returns `Allow` or `Block`. | Automatic â€” gated by HTTPS Inspection master toggle only |
| **CosmeticFilter** | `##.ad-class`, `###ad-id`, `example.com##.banner` | Response Body (CSS) | Injects `<style>...</style>` into HTML `</head>`. | Automatic â€” applied when compiled CSS rules exist; subtype count shown in diagnostics |
| **ProceduralFilter** | `#?#:has(...)`, `#?#:xpath(...)`, `#?#:upward(...)` | Response Body (JS) | Injects procedural DOM observer `<script>` into HTML `</head>`. | Automatic â€” applied when compiled procedural rules exist; subtype count shown in diagnostics |
| **ScriptletFilter** | `#%#//scriptlet('abort-on-property-read', '...')` | Response Body (JS) | Injects scriptlet library + invocations **first** before page scripts run. | Automatic â€” applied when compiled scriptlet rules exist; subtype count shown in diagnostics |
| **CspInjector** | `||example.com^$csp=script-src 'self'` | HTTP Headers | Injects or merges `Content-Security-Policy` header on responses. | Automatic â€” applied when compiled CSP rules exist; subtype count shown in diagnostics |
| **HtmlFilter** | `##^script:has-text(...)`, `##^div#ad-box` | Response Body (DOM) | Uses Jsoup to remove matching elements directly from server response body. | Automatic â€” applied when compiled HTML rules exist; subtype count shown in diagnostics |

---

## 6. Preset Filter Sources & Conservative Defaults

| Preset Name | Category | URL | Default Enabled | Rationale |
|-------------|----------|-----|-----------------|-----------|
| **AdGuard Base Filter** | Ads | `https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt` | **Yes** | Comprehensive core adblocking rules with cosmetic, scriptlet, and procedural coverage. |
| **Peter Lowe's Blocklist** | Ads / Trackers | `https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext` | **Yes** | Lightweight, rock-solid ad/tracking server list with zero false positives. |
| **EasyList** | Ads | `https://easylist.to/easylist/easylist.txt` | **No (Optional)** | Standard adblock list; kept optional by default to avoid massive duplicate rule overlap with AdGuard Base. |
| **AdGuard Tracking Protection** | Privacy | `https://filters.adtidy.org/extension/ublock/filters/3.txt` | **No (Optional)** | Dedicated tracking protection rules. |
| **EasyPrivacy** | Privacy | `https://easylist.to/easylist/easyprivacy.txt` | **No (Optional)** | Tracking prevention rules. |
| **AdGuard Annoyances Filter** | Annoyances | `https://filters.adtidy.org/extension/ublock/filters/14.txt` | **No (Optional)** | Cookie popups, mobile app banners, widgets. |
| **Fanboy's Annoyance List** | Annoyances | `https://easylist.to/easylist/fanboy-annoyance.txt` | **No (Optional)** | Comprehensive annoyance list. |

---

## 7. UI / UX Integration in Plus Tab (Filters)

```
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                               PLUS (FILTERS)                           â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚ â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”‚
â”‚ â”‚ HTTPS Inspection                                         [ ON ]    â”‚ â”‚
â”‚ â”‚ CA Certificate: Installed & Trusted                                â”‚ â”‚
â”‚ â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ â”‚
â”‚                                                                        â”‚
â”‚ â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”‚
â”‚ â”‚ Advanced Filtering                                                 â”‚ â”‚
â”‚ â”‚ 2 lists enabled â€¢ 48,210 rules â€¢ updated 1h ago                    â”‚ â”‚
â”‚ â”‚ [ Manage Filters ]              [ âŸ³ Check for Updates ]            â”‚ â”‚
â”‚ â”‚ Rule-type handling (network / CSS / scriptlet / procedural /     â”‚ â”‚
â”‚ â”‚   CSP / HTML) is automatic; see per-source diagnostics.            â”‚ â”‚
â”‚ â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ â”‚
â”‚                                                                        â”‚
â”‚ â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â” â”‚
â”‚ â”‚ Exclusions                                                         â”‚ â”‚
â”‚ â”‚ [ Domain Exclusions (12) ]           [ App Exclusions (4) ]        â”‚ â”‚
â”‚ â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

### 7.1 "Manage Filters" Screen:
Organized by **category** (user intent grouping, not parser capability). The default
category taxonomy is:

```
MANAGE FILTERS

â”œâ”€â”€ Ads
â”œâ”€â”€ Privacy
â”œâ”€â”€ Social
â”œâ”€â”€ Annoyances
â”œâ”€â”€ Security
â”œâ”€â”€ Language-specific
â”œâ”€â”€ Other Filters
â””â”€â”€ Custom Filters
```

- **Categorized list** by the above groups (each group expands to its enabled/available
  `FilterSource` presets + custom URLs; e.g. *Ads* â†’ EasyList, AdGuard Base).
- **Item view** (per `FilterSource`):
  - Source Title, Category, and URL.
  - Switch: Enable / Disable the source (toggles compilation inclusion, **not** an
    engine rule-type gate).
  - Diagnostic Stats: `42,150 parsed â€¢ 120 unsupported â€¢ 12 invalid`.
  - Sub-type badges (diagnostics only, NOT user toggles):
    `[Net: 18k] [CSS: 20k] [Scriptlet: 850] [Proc: 1.2k] [CSP: 400] [HTML: 1.7k]`.
  - Sync status & timestamp.
- **Bottom Action:** **+ Add Custom URL** (Dialog validates format before insertion)
  â€” assigned to a category by the user (default **Custom Filters**).
- **Menu Action:** **Update All Sources**.

> A `category` is **organizational metadata** attached to a `FilterSource`. It does
> **NOT** restrict parser/syntax selection â€” the parser auto-detects rule subtypes from
> the list itself (network / cosmetic / scriptlet / procedural / CSP / HTML).

---

## 8. Implementation Phases & Milestones

### Phase 1: Database & Data Layer
- Create `FilterSource` Room entity, `FilterSourceDao`, and `FilterSourceRepository`.
- Seed preset sources with conservative defaults (AdGuard Base + Peter Lowe enabled).
- Unit tests for database CRUD, diagnostics serialization, and defaults.

### Phase 2: Downloader Engine (`FilterSourceDownloadManager`)
- Implement streaming HTTP download client with ETag and Last-Modified support.
- Stream directly to `filesDir/filter_sources/<id>/download.tmp`.
- SHA-256 calculation and 25 MB size cap enforcement.
- WorkManager `FilterUpdateWorker` background scheduler (24h, Wi-Fi/unmetered only).

### Phase 2 Evidence Contract (G0–G9)

Every gate below must produce a named artifact before B2 is sealed. Artifacts are
textual logs, Room queries, or file-system evidence — no UI screenshots, no
device-visible production changes solely for testing.

**Validation boundary (locked, cannot change without reopening B2 scope):**
```
B2 validates:
  - HTTP success (200 or 304)
  - response size <= 25 MB
  - non-empty content
  - not an obvious HTML/error payload
  - file I/O integrity
  - SHA-256 checksum matches recorded value

B2 DOES NOT:
  - parse AdGuard syntax
  - count parsed/unsupported/invalid rules
  - classify CSS/scriptlet/CSP/etc.
  - compile rules
  - touch FilterEngine
  - create or modify adblock_rules.txt / adblock_rules.new / filter_rules_cache.bin
```

Syntax parsing, compilation, and `FilterEngine` integration are **B3** responsibilities.

**Atomic promotion boundary (locked):**
```
B2 atomic promotion:
  filter_sources/source_N/download.tmp  →  filter_sources/source_N/current.txt

B4 atomic activation (NOT B2):
  adblock_rules.new  →  adblock_rules.txt
```

B2 must not create, modify, or delete `adblock_rules*` files or `filter_rules_cache.bin`.

---

#### G0 — Seed / Worker Concurrency

`FilterUpdateWorker` MUST NOT call `ensurePresets()` or otherwise create
`FilterSource` rows. Preset seeding remains a single startup responsibility
(e.g. `Application.onCreate` or equivalent singleton init).

If implementation requires concurrent preset creation:
  → STOP-B2-SEED-CONCURRENCY
  → solve DB-level uniqueness / serialization first.

Worker operates **only** on already-existing `FilterSource` rows.

**Artifact:** `phase1d_b2_g0_concurrency_evidence.txt`
**Acceptance:** Worker source review confirms no `ensurePresets()` call path from
worker context; Room row-creation traceable to single startup path only.

---

#### G1 — Conditional 304 Round Trip

Use a deterministic HTTP fixture/server for the protocol gate. Live AdGuard
servers are not required for this gate.

Sequence:
1. First request → `200 OK` + `ETag` + `Last-Modified` stored in `FilterSource`
2. Second request sends:
   - `If-None-Match: <stored ETag>`
   - `If-Modified-Since: <stored Last-Modified>`
3. Server → `304 Not Modified`
4. `current.txt` unchanged; status records successful non-download round trip

**Artifact:** `phase1d_b2_g1_304_evidence.txt`
**Acceptance:** Request/response headers captured; `current.txt` SHA-256 before
and after round trip identical; `lastUpdateStatus` reflects SUCCESS or
equivalent NOT_MODIFIED semantic.

---

#### G2 — 200 + Streaming SHA-256 + Promotion

1. `200 OK` response streamed to `download.tmp`
2. SHA-256 computed during stream (digest captured before promotion)
3. Validation passes: size ≤ 25 MB, non-empty, not HTML error payload
4. Atomic promotion: `download.tmp` → `current.txt`
5. `current.txt` SHA-256 computed after promotion; compared to stream digest

Note: `download.tmp` may be absent after promotion. Evidence captures the
stream digest before promotion and `current.txt` digest after — not a
post-promotion `download.tmp` hash.

**Artifact:** `phase1d_b2_g2_sha256_evidence.txt`
**Acceptance:** Two hashes recorded; stream digest == post-promotion hash;
`FilterSource.checksum` matches; file mtime updated; ETag/Last-Modified stored.

---

#### G3 — 25 MB Hard Cap

Serve a deterministic response > 25 MB from fixture server.

Required behavior:
- Stream rejected at or before 25 MB boundary
- `lastUpdateStatus = FAILED`
- `errorMessage` populated (non-null, meaningful)
- `download.tmp` removed
- Existing `current.txt` SHA-256 unchanged
- `current.txt` mtime unchanged (where filesystem permits)

**Artifact:** `phase1d_b2_g3_cap_evidence.txt`
**Acceptance:** SHA-256 comparison before/after shows no change;
`download.tmp` absent; Room row shows FAILED + errorMessage.

---

#### G4 — Invalid Response Preservation

Serve a deterministic HTML/error payload (e.g. `<!DOCTYPE html><html>…`).

Required behavior:
- Validation rejects response as non-filter-list content
- `lastUpdateStatus = FAILED`
- `errorMessage` populated
- `download.tmp` cleaned
- Last-known-good `current.txt` untouched

Compare:
- `current.txt` SHA-256 before rejection attempt
- `current.txt` SHA-256 after rejection attempt
- Hashes identical

**Artifact:** `phase1d_b2_g4_error_state_evidence.txt`
**Acceptance:** SHA-256 invariant; `download.tmp` absent; Room row shows FAILED.

---

#### G5 — HTTP Cache Metadata

Deterministic server returns both `ETag` and `Last-Modified` headers on `200 OK`.

After successful download:
- `FilterSource.etag` persisted (non-null)
- `FilterSource.lastModified` persisted (non-null)

Next request:
- Both conditional headers replayed: `If-None-Match` + `If-Modified-Since`

On `304`:
- `checksum` / `current.txt` retained (no unnecessary rewrite)
- mtime of `current.txt` unchanged

**Artifact:** `phase1d_b2_g5_caching_evidence.txt`
**Acceptance:** Room query shows both fields populated after 200; request log
shows both headers sent on subsequent call; 304 path confirmed.

---

#### G6 — Failed-State Observability

Any deterministic failure (G3/G4 or network timeout) must produce observable
state through repository/Room and diagnostic logging:

- `lastUpdateStatus = FAILED`
- `errorMessage != null` and meaningful (not generic "error")
- Last successful `checksum` retained in row
- Last-known-good `current.txt` retained on filesystem

Evidence gathered via:
- Room/repository query on affected `FilterSource` row
- Logcat filter on download manager tag

**No Plus UI requirement in B2.** UI surfacing is tested in B5.

**Artifact:** `phase1d_b2_g6_error_observability_evidence.txt`
**Acceptance:** Room row fields confirmed; logcat shows error at appropriate
severity (WARN or ERROR, not silent); file content unchanged.

---

#### G7 — Multi-Source Download Isolation

Two `FilterSource` rows active simultaneously:

- **Source A**: valid `200 OK` → validation passes → `current.txt` promoted
- **Source B**: corrupt / error response → validation fails → `FAILED` → old `current.txt` retained

Required invariants:
- A's success does not affect B's state
- B's failure does not overwrite A's `current.txt`
- Strict: `adblock_rules.txt` untouched; `adblock_rules.new` absent or untouched
- `FilterSourceCompiler` NOT invoked as part of B2 download behavior

**Artifact:** `phase1d_b2_g7_multisource_evidence.txt`
**Acceptance:** SHA-256 of both `current.txt` files before and after; only A
changed; B unchanged; no `adblock_rules*` files modified.

---

#### G8 — Physical Device Live Download Smoke

Device: Xiaomi Mi A1 / tissot / Android 16 (`3595381c0804`)

Use at least one real approved preset source (AdGuard Base Filter or Peter Lowe).

Prove:
- Actual network request issued (not stub/mock)
- Streaming download observed
- Successful validation
- `current.txt` exists on device filesystem
- Room metadata updated (checksum, lastUpdated, lastUpdateStatus = SUCCESS)
- Zero FATAL crashes
- Zero OOM kills
- Zero SQLite / Room errors in logcat

If `Update All` is implemented at repository/worker level: test one complete
update-all cycle. Do **not** add temporary production UI solely to trigger a
manual test — use adb shell `am` or worker introspection.

**Artifact:** `phase1d_b2_g8_device_evidence.txt`
**Acceptance:** Logcat filtered to `FilterSource` / `FilterUpdate` tags;
`adb shell` Room query; file system listing of `filesDir/filter_sources/`.

---

#### G9 — WorkManager Contract

`FilterUpdateWorker` uses `PeriodicWorkRequest` with:
- Interval: 24 hours
- Network constraint: `NetworkType.UNMETERED` (Wi-Fi/unmetered semantics chosen
  by project — document which literal is used)

Required proof:
- `PeriodicWorkRequest` constructed with intended 24h interval
- Unique work — duplicate schedule attempts do not create overlapping workers
- Worker processes **only** existing enabled `FilterSource` rows
- Worker does **not** call `ensurePresets()` or create seed rows
- Worker failure does **not** delete last-known-good files or corrupt `current.txt`

Do not wait 24 hours physically. Use WorkManager test / introspection mechanism
(e.g. `WorkManagerTestInitHelper`, `WorkManager.getWorkInfosByTagLiveData`,
or equivalent) to verify schedule and constraints.

**Artifact:** `phase1d_b2_g9_worker_evidence.txt`
**Acceptance:** Worker source review + test-run output; 24h interval confirmed
in `PeriodicWorkRequest` builder call; `UNMETERED` constraint literal named;
no preset-seeding path from worker context.

---

### Phase 3: Streaming Ingestion & Atomic Compiler (`FilterSourceCompiler`)
- Implement streaming line-by-line syntax classifier and per-source diagnostic counter.
- Staged compilation to `filesDir/adblock_rules.new` &rarr; validation &rarr; atomic rename to `filesDir/adblock_rules.txt`.
- `adblock_rules.txt` compiled output is EXCLUSIVE to Advanced Filter Sources
  (EasyList/AdGuard/AdGuard Annoyances/Custom URL) â€” category-oriented source
  selection drives compilation, but `adblock_rules.txt` is regenerated from the
  union of all enabled `FilterSource` content regardless of category.
- The legacy DNSâ†’MITM bridge (`syncBlocklistToAdblockRules`) is OBSOLETE per
  DECISION-008 â€” not revived. `adblock_rules.txt` ownership belongs solely to
  Advanced Filter Source compilation; Rethink DNS blocklists feed it not at all
  (DNS blocking propagates separately through DNS sinkhole inheritance).
- Binary cache persistence `cacheDir/filter_rules_cache.bin`.
- Hot-reload trigger via `BraveVPNService.reloadAdblockRules()`.

### Phase 4: UI & Source Wiring (No Capability Toggles)
- Build `FilterSourceActivity` / `FilterSourcesBottomSheet` with category grouping (Ads,
  Privacy, Social, Annoyances, Security, Language-specific, Other Filters, Custom Filters)
  and per-source diagnostics display.
- Build `AddCustomFilterSourceDialog` with URL validation; user assigns the new custom
  source to a category (default **Custom Filters**).
- Connect Plus Tab's `cardAdvancedFiltering` to show live source metrics
  (`X lists enabled â€¢ N rules â€¢ updated â€¦`) and route to `Manage Filters`.
  **No per-engine capability switches are wired here** â€” rule-type handling is automatic
  inside `FilterEngine` based on compiled rule subtypes (see Â§5.1). Capability
  gating flags, if any remain in the proxy layer, are internal/engine-level and surfaced
  only via diagnostics, never as normal user toggles.

---

### B4.5 HTTPS Inspection Policy — Architecture Layer (documented in DECISION-010)

Architecture lock only; implementation deferred to B4.5 implementation phase.

| Sub-feature | Scope | Status |
|-------------|-------|--------|
| Known browser registry | Maintained hardcoded package IDs; default ON when installed | DOCUMENTED |
| Dynamic browser fallback | `ACTION_VIEW` + `CATEGORY_BROWSABLE` + `https://` query (supplementary: `ROLE_BROWSER`, `CATEGORY_APP_BROWSER`). Best-effort, depends on package visibility. Default OFF. | DOCUMENTED |
| Per-app opt-in | Other apps: default OFF, user opts in individually | DOCUMENTED |
| System hard bypass | Internal safety list (Play Services / GSF / IMS …); not user-editable | DOCUMENTED |
| Domain + app-port protection | Scoped, not global-port | DOCUMENTED |
| Resource protection | Stream-only fallback at body-size threshold; raw-TCP mid-flow switch prohibited | DOCUMENTED |

**Architecture boundary (DECISION-010):** `InspectionPolicyEngine` resolves MITM
vs bypass before `CONNECT` is accepted. It delegates to `HttpsInspectionPolicy`,
`SystemBypassPolicy`, and `ResourceProtectionPolicy`. Returns a single
BYPASS/MITM decision with reason code using only metadata available pre-CONNECT
(app/UID, host, port).

`ResourceProtectionPolicy` is a separate post-MITM stage: it downgrades
`MITM_FULL` → `MITM_STREAM_ONLY` based on response body size and never produces
a BYPASS result.

Threshold ownership: byte thresholds (rewrite-size, DOM-processing) are
**B4.5 implementation parameters**, not an architecture decision. Initial values
are unlocked; numerical tuning by B4.5 from device/performance evidence does not
require reopening DECISION-010, provided the locked semantics (large body →
STREAM_ONLY, never raw-TCP mid-flow) are preserved.

**Roadmap order:**

```
B1    Data / storage foundation              SEALED
B2    Downloader + validation                SEALED
B3    Parser / compiler + diagnostics        SEALED
B4    Atomic activation + rollback           SEALED
B4.5  HTTPS Inspection Policy                OPEN — DOC5 governs the design; full preset-driven policy is not implemented
B5    Manage Filters + custom source UI       IMPLEMENTED — add, edit, remove, enable and disable flows committed
B6    End-to-end verification                 BLOCKED — unit and device UI gates pass; controlled website filtering has not passed
```

DECISION-010 is already GOVERNING, so the former B2 architecture block is closed. B4.5 remains open: the repository does not yet contain the complete preset-driven HTTPS inspection policy or a production `InspectionPolicyEngine`.

---

### Current verification status — 2026-08-28

Implementation commit:

- `ed8a0a2b774cf9795b17a44ad52ad77204b33b86`
- Branch: `phase1d-advanced-filter`
- Commit subject: `feat(filter): manage custom filter sources`

Completed evidence:

- Custom sources can be added, edited, removed, enabled and disabled.
- URL-only edit persisted byte-exactly across two cold relaunches.
- Repository tests: 41 passed.
- ViewModel transaction tests: 38 passed.
- Row flattener tests: 9 passed.
- Initial feature commit: `ed8a0a2b774cf9795b17a44ad52ad77204b33b86`.
- Tracked-file closure commit: `ca797a1d179b060b602c26664814111b640ffd8a`, adding seven support files omitted from the initial commit.
- Additional closure suites: `CustomFilterSourceValidatorTest` (9) and `FilterSourceCustomDaoTest` (5).
- Total targeted JUnit tests: 102 passed, 0 failed, 0 errors, 0 skipped.
- APK and device UI verification used SHA-256 `39710942B1A17DDB92B2C1F25D8DD6420E2C7411D7ECD011E40F577F16983E36`.

Open findings:

- No user-visible toast currently confirms a filter-generation hot reload after an enable/disable operation.
- A manual browser test found that web access worked before adding the custom filter, failed with HTTPS inspection enabled after the filter was added, and worked again when HTTPS inspection was disabled.
- This observation does not prove that custom website filtering works. The controlled OFF → ON → OFF website test is still required.
- The expected HTTPS policy preset files are not present in the repository, and the current behavior is only a partial hardcoded hybrid policy.
- B11R confirmed that removal persisted, but its pre-remove XML contradicted the runner output. Treat that run as evidence with an audit caveat, not as a clean end-to-end PASS.

Release implication:

- The Filter Source Manager implementation is committed and pushed.
- Release-candidate status remains blocked by the HTTPS/browser regression and the missing controlled website-filtering proof.

Do not alter the existing Definition of Done checkboxes. They remain the release-level checklist.

### Phase 5: Testing, Memory Profiling & Device Verification
- Device verification on Mi A1 A16 (`3595381c0804`).
- Low-RAM profiling (ensure compilation of AdGuard Base + Peter Lowe never exceeds 35 MB transient heap).
- Network tests verifying 304 Not Modified conditional updates.
- Verify fallback safety (corrupt download leaves active rules untouched).

---

### B2 / B3 / B4 Ownership Boundary

This boundary exists to prevent evidence-gate leakage during phased
implementation. Each phase's seal depends on **its own** gates only.

```
B2  Downloader + Validation
    ├── HTTP transport: conditional GET, streaming, size cap
    ├── File lifecycle: download.tmp → current.txt per source
    ├── Checksum: SHA-256 computed during stream
    ├── Cache metadata: ETag / Last-Modified persistence
    ├── Error handling: FAILED state, errorMessage, file preservation
    ├── WorkManager: 24h / UNMETERED worker (no seed path)
    ├── Evidence: G0–G9 only
    └── STRICTLY DOES NOT:
        ├── parse filter syntax
        ├── compile rules
        ├── create adblock_rules.txt / adblock_rules.new / filter_rules_cache.bin
        ├── invoke FilterSourceCompiler
        └── surface Plus-tab UI (B5)

B3  Parser / Compiler + Diagnostics
    ├── Streaming line-by-line syntax classifier
    ├── Per-source diagnostics (parsed / unsupported / invalid / subtype counts)
    ├── Atomic staged compilation: adblock_rules.new → adblock_rules.txt
    ├── Binary cache: filter_rules_cache.bin
    └── Hot-reload trigger: BraveVPNService.reloadAdblockRules()

B4  Atomic Activation + Rollback
    ├── adblock_rules.new → adblock_rules.txt atomic rename (B3 delivers staged file)
    ├── Rollback on compilation failure (retain last-known-good)
    └── B2 download promotion is a separate, independent atomic step
        (filter_sources/source_N/download.tmp → current.txt — NOT adblock_rules)

B5  Manage Filters + Exclusions UI
    ├── Plus-tab category-oriented source selector
    ├── Per-source diagnostics display
    ├── Error state surfacing (errorMessage → user-visible)
    └── Manual / scheduled "Update All" trigger UI
```

**Key rule:** An artifact named in G1–G9 that touches `adblock_rules*` or
`filter_rules_cache.bin` is a B3/B4 leak — B2 seal fails on that finding.

---

### Resource Host Envelope

Build and test resource limits are enforced via Gradle CLI flags or test runner
configuration. `gradle.properties` is **not modified** for these limits.

```
Normal build / compile:
  --max-workers=3

Focused / heavy unit-test suites (full-suite with FilterSource tests):
  --max-workers=2
```

These are operational constraints, not architectural decisions. They may be
adjusted based on device evidence (Mi A1 A16, 3 GB RAM) without reopening any
decision document.

---

## 9. Definition of Done (DoD)

- [ ] Room database migrations and repository tests pass cleanly.
- [ ] Multi-source download with conditional 304 caching verified with live network tests.
- [ ] Per-source diagnostics (`parsed`, `unsupported`, `invalid`) correctly recorded and displayed.
- [ ] Streaming compiler keeps transient heap usage low on 3GB/4GB Android devices.
- [ ] Staged atomic swap guarantees zero downtime and corrupt-download rollback.
- [ ] Plus tab UI clearly separates DNS Blocklists (Configure â†’ DNS) from Filter Sources
      (Plus â†’ Advanced Filtering â†’ Manage Filters) and provides intuitive category-oriented controls.
- [ ] Zero memory leaks, zero `FATAL` crashes, and clean logcat during full list synchronization.

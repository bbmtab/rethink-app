# RethinkDNS — Unified UI Architecture

> **Purpose:** Complete map of RethinkDNS UI surface, organized by feature modules and user flows. This is our own architecture — no external references. Use as the single source of truth for Phase 1+ UI work (Plus tab redesign, MITM/adblock integration, auto-restart UX, etc.).
> **Current state:** Phase 0 complete (CA + MITM + FilterEngine verified). Phase 1 starting: blocklist bridge → Plus tab → auto-restart.

---

## 🎛️ MAIN ENTRY POINTS

### Bottom Navigation (all flavors)
| Tab | Primary Fragment | Flavors | Notes |
|-----|------------------|---------|-------|
| **Home** | `HomeScreenFragment` → `HomeScreenActivity` | All | VPN on/off, live stats, active profile |
| **Stats** | `SummaryStatisticsFragment` | All | Per-app data, events, network logs |
| **Plus** | `RethinkPlusFragment` (full flavor — hoisted from fdroid 1b) | All flavors | **Filters (MITM/adblock)** — HTTPS Inspection / Advanced Filtering / Exclusions (unified, flavor-agnostic) |
| **Configure** | `ConfigureFragment` → 8 cards → activities | All | DNS, Firewall, Proxy, VPN, Logs, Anti-Censorship, Apps, Advanced |
| **About** | `AboutFragment` | All | Version, legal, support |

---

## 🏠 HOME — VPN CONTROL CENTER

**Files:** `HomeScreenFragment.kt`, `HomeScreenActivity.kt`, `fragment_home_screen.xml`

| Control | Behavior |
|---------|----------|
| Big VPN toggle | Starts/stops `BraveVPNService` via `VpnController` |
| Connection status card | Shows active profile (DNS, WireGuard, RPN) — RPN profile remains in backend / VPN settings; not exposed in Plus UI (DECISION-007) |
| Data usage | Live TX/RX from `TrafficStats` |
| Bottom sheet (gear icon) | `HomeScreenSettingBottomSheet` — quick toggles |

---

## 📊 STATS — USAGE INSIGHTS

**Files:** `SummaryStatisticsFragment.kt`, `activity_detailed_statistics.xml`, `DetailedStatisticsActivity.kt`

| Screen | Purpose |
|--------|---------|
| Summary tab | Per-app data, total TX/RX, time-series charts |
| Events tab | `EventLogger` records — DNS blocks, firewall hits, connection open/close |
| Network logs | Raw packet-level captures (PCAP export) |
| App-wise logs | `AppWiseDomainLogsActivity`, `AppWiseIpLogsActivity` |

---

## ➕ PLUS — "RETHINK PLUS" HUB (KEY REDESIGN TARGET)

### Current state (post-Phase-1c / supervisor-audited 2026-08-13)
| Flavor | Fragment | Status |
|--------|----------|--------|
| **all (fdroid / full / play / website)** | `RethinkPlusFragment.kt` (full flavor — hoisted from fdroid 1b; R100) | **Filters (MITM/adblock)** — HTTPS Inspection / Advanced Filtering / Exclusions |

```
Note: `RethinkPlusDashboardFragment.kt` (RPN subscription UI) and `ServerSelectionFragment.kt` (RPN server picker) are deleted from the working tree (executed pivot 2026-08-09; supervisor-audited 2026-08-10). The fdroid `RethinkPlusFragment.kt` was hoisted to `full/` (R100). Play/website `RethinkPlusFragment.kt` (billing UI) is also deleted; the Plus surface is MITM/adblock-only for all flavors.
```

### Target architecture — unified Filters surface (all flavors; DECISION-007)

```
Plus (bottom nav tab) → RethinkPlusFragment (full flavor, hoisted fdroid→full)
│
├── 1. HTTPS Inspection
│   ├── Master toggle (persist: httpsInspectionEnabled)
│   ├── CA status badge (✅ INSTALLED / ⚠️ NOT INSTALLED)
│   ├── CA actions: Install / Re-install / Export to Downloads
│   └── Apps (three-tier eligibility — DECISION-010)
        ├── Known browsers      default ON  (maintained hardcoded package registry)
        ├── Detected browsers   default OFF (dynamic fallback; user enables individually)
        └── Other apps          default OFF (explicit user opt-in required)
│
├── 2. Advanced Filtering
│   ├── Enabled filter source summary — e.g. `2 lists enabled • 48,210 rules • updated 1h ago`
│   └── Manage Filters
│       ├── Ads
│       ├── Privacy
│       ├── Social
│       ├── Annoyances
│       ├── Security
│       ├── Language-specific
│       ├── Other Filters
│       └── Custom Filters
│
│   Network/Cosmetic/Scriptlet/Procedural/CSP/HTML rule handling is **automatic**
│   inside FilterEngine — it inspects compiled rule subtypes and applies each
│   supported injection path automatically. Subtype support and per-source rule-type
│   counts are exposed only in diagnostics / technical capability reporting, **not** as
│   separate user-facing feature toggles. See DECISION-009.
│
└── 3. Exclusions
    ├── Domain exclusions (skip MITM for these)
    └── App exclusions (skip routing these through MITM)
```

**Key principle (DECISION-007 + DECISION-008):** Plus is the universal MITM/adblock Filters surface — identical for fdroid / full / play / website. No RPN subscription/management UI lives in Plus. No DNS blocklist management or DNS→HTTPS synchronization belongs in Plus.

**DNS Policy Ownership (DECISION-008):** Original Rethink DNS blocklist selection and DNS blocking policy remain exclusively under Configure → DNS → Rethink Blocklists. HTTPS Inspection inherits tested sinkhole/blocklist enforcement automatically via `activeNetwork.getAllByName()` → Rethink DNS resolver → `0.0.0.0` sinkhole — no manual bridge required. Advanced Filter Sources (EasyList / AdGuard / Custom URL) are a separate independent subsystem supplying FilterEngine with cosmetic / scriptlet / procedural / CSP / HTML rules. Rethink DNS blocklists are NOT FilterEngine source material. Network/Cosmetic/Scriptlet/Procedural/CSP/HTML rule execution remains an **automatic** FilterEngine capability (DECISION-009) — surfaced via per-source diagnostics only, never as normal Plus-tab feature toggles.

---

## ⚙️ CONFIGURE — MODULE CARDS

**Files:** `ConfigureFragment.kt`, `fragment_configure.xml` → 8 cards → Activities:

| Card | Activity | Sub-screens / Bottom Sheets |
|------|----------|-----------------------------|
| **Apps** | `AppListActivity` | Per-app DNS/firewall/proxy rules → `AppDomainRulesBottomSheet`, `AppIpRulesBottomSheet` |
| **DNS** | `DnsDetailActivity` | DoH/DoT/DoQ servers, custom DNS, blocklists (`RethinkBlocklistFragment`), provider list |
| **Firewall** | `FirewallActivity` | Per-app allow/block, global rules, Wi-Fi/Mobile/Roaming conditions |
| **Proxy** | `ProxySettingsActivity` | Upstream proxy (SOCKS/HTTP), per-app routing |
| **VPN** | `TunnelSettingsActivity` | WireGuard, RPN, MTU, routing mode |
| **Logs** | `NetworkLogsActivity` | Live logcat, PCAP, DNS log, connection tracker |
| **Anti-Censorship** | `AntiCensorshipActivity` | Domain fronting, SNI, bootstrap DNS |
| **Advanced** | `AdvancedSettingActivity` | Theme, language, auto-update, backup/restore, developer tools |

---

## 🔒 HTTPS INSPECTION — DETAILED FLOW (relocated from CertificateSetupActivity)

### Screens (new locations in Plus tab)
| Screen | Source Logic | Purpose |
|--------|--------------|---------|
| Plus tab section | **NEW** (was `CertificateSetupActivity`) | Master entry for all HTTPS inspection |
| CA status card | `CertificateAuthority.isCaInstalled()` polling | Live badge + actions |
| CA install flow | `CertificateAuthority` + `KeyChain`/`ACTION_VIEW` | System CA installer |
| HTTPS toggle | `persistentState.httpsInspectionEnabled` | Master on/off (disabled until CA installed) |
| Per-app HTTPS filter | **NEW** (pattern: `HttpsFilteredAppsFragment`) | Allowlist apps for MITM |
| Exclusions | **NEW** | Domains/apps to skip MITM |

### CA Install Mechanics (preserve, just relocate)
```kotlin
// 1. Generate CA in AndroidKeyStore
CertificateAuthority.generateAndStoreRootCA(context)

// 2. Export DER bytes → FileProvider → ACTION_VIEW with application/x-x509-ca-cert
val intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, "application/x-x509-ca-cert")
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
installCertLauncher.launch(intent)

// 3. System Settings opens → user taps "Install" → names it "RethinkDNS Root CA"

// 4. Poll isCaInstalled() every 1s → update badge ✅ INSTALLED → enable HTTPS toggle
```

### Auto-restart UX (new requirement)
- User flips HTTPS toggle ON → dialog: **"App will restart to apply change"** → OK → process restart
- User flips HTTPS toggle OFF → same dialog → restart
- **Any non-hot-pluggable setting follows this pattern**

---

## 🌐 DNS BLOCKLIST — EXISTING + MITM BRIDGE

### Current DNS blocklist UI (working)
| Screen | Fragment/Activity | Layout |
|--------|-------------------|--------|
| Blocklist main | `RethinkBlocklistFragment` | `fragment_rethink_blocklist.xml` |
| Simple / Advanced tabs | TabLayout | `list_item_rethink_blocklist_simple.xml` / `adv` |
| Local blocklists | `LocalBlocklistsBottomSheet` | `bottom_sheet_local_blocklists.xml` |
| Remote blocklists | `RemoteBlocklistPacksMapViewModel` | — |

> ## 🌐 DNS BLOCKLIST — EXISTING + MITM BRIDGE (HISTORICAL / RETIRED)
>
> ### Historical / retired design (pre-Phase-1D-A3)
>
> ```
> User selects list in DNS blocklist UI
>        │
>        ▼
> RethinkBlocklistManager downloads list (existing)
>        │
>        ▼
> SYNCBLOCKLISTTOADBLOCKRULES BRIDGE — OBSOLETE (A2 STOP-P2)
>        │
>        ▼
> Write to adblock_rules.txt (app filesDir)
>        │
>        ▼
> FilterEngine.loadRulesFromFile(adblock_rules.txt)
>        │
>        ▼
> LocalHttpsProxy.proxyListener.match() now has URL-path & cosmetic rules
>        │
>        ▼
> Plus tab shows: "Synced ✓ | 82,096 rules | Cosmetic: 23,749 | Scriptlet: 0 | CSP: 0 | HTML: 0 | Procedural: 0"
>
> ## ⚠️ OBSOLETED BY DECISION-008 (2026-08-15):
>
> syncBlocklistToAdblockRules is OBSOLETE pending source cleanup.
> Rethink DNS blocklists are NOT FilterEngine source material.
> Advanced Filter Sources are a separate independent subsystem.
> No documented source-flow: Rethink DNS blocklists → FilterEngine.
> ```
>
> **Status: OBSOLETE — Phase-1D-A3 (2026-08-15) + A2 STOP-P2.**
>
> The original Phase-1a architecture proposed a manual DNS→MITM bridge (`syncBlocklistToAdblockRules` → `adblock_rules.txt` → `FilterEngine`). This was **empirically invalidated**:
>
> 1. **A2 STOP-P2**: `syncBlocklistToAdblockRules()` failed — selected tag 54 existed, but implementation expected raw text not available from Rethink's compiled DNS artifacts. Bridge implementation was stopped pending redesign.
> 2. **Phase-1D-A3**: Live device audit (Xiaomi Mi A1 A16) proved domain-level blocking propagates automatically via DNS sinkhole inheritance (`activeNetwork.getAllByName()` → Rethink DNS → `0.0.0.0` → `502 Bad Gateway`). No manual bridge needed for domain blocking.
>
> **Current status**: The bridge concept is **obsolete pending source cleanup**. Rethink DNS blocklists are NOT FilterEngine source material. Advanced Filter Sources (EasyList / AdGuard / Custom URL) are a separate independent subsystem.

---

## 🛡️ FIREWALL — PER-APP RULES

**Files:** `FirewallActivity.kt`, `FirewallSettingsFragment.kt`, bottom sheets

| Feature | Implementation |
|---------|----------------|
| Per-app allow/block | `AppDomainRulesBottomSheet`, `AppIpRulesBottomSheet` |
| Global rules | `FirewallGlobalRulesFragment` |
| Quick actions | `FirewallAppFilterBottomSheet` |
| Wi-Fi / Mobile / Roaming | Condition on rule |
| Rule-based (not boolean) | `FirewallRuleDetailsFragment` — Allow/Block, target, condition |

---

## 🧭 NAVIGATION MAP (complete)

```
HomeScreenActivity (hosts NavHostFragment)
│
├─ HomeFragment (HomeScreenFragment)
│
├─ StatsFragment (SummaryStatisticsFragment)
│   └─ DetailedStatisticsActivity
│
├─ PlusFragment (RethinkPlusFragment — full flavor; hoisted fdroid→full)
│   └─ Filters UX — MITM / adblock (all flavors: fdroid / full / play / website) — HTTPS Inspection, Advanced Filtering (source/category-oriented), Exclusions
│
├─ ConfigureFragment
│   ├─ Apps → AppListActivity
│   ├─ DNS → DnsDetailActivity
│   │    ├─ RethinkBlocklistFragment
│   │    └─ LocalBlocklistsBottomSheet
│   ├─ Firewall → FirewallActivity
│   │    ├─ AppDomainRulesBottomSheet
│   │    ├─ AppIpRulesBottomSheet
│   │    ├─ FirewallGlobalRulesFragment
│   │    └─ FirewallRuleDetailsFragment
│   ├─ Proxy → ProxySettingsActivity
│   ├─ VPN → TunnelSettingsActivity
│   ├─ Logs → NetworkLogsActivity
│   │    ├─ ActivityEventsActivity
│   │    ├─ ActivityAppWiseDomainLogsActivity
│   │    └─ ActivityAppWiseIpLogsActivity
│   ├─ Anti-Censorship → AntiCensorshipActivity
│   └─ Advanced → AdvancedSettingActivity
│
└─ AboutFragment
```

---

## 📂 FLAVOR DIFFERENCES (critical for Plus tab)

| Feature | fdroid | full | play | website |
|---------|--------|------|------|---------|
| Filters (Plus tab) — MITM/adblock | ✅ | ✅ | ✅ | ✅ |
| RPN subscription UI (Plus tab) | — (retired / deferred — DECISION-007) | — (retired) | — (retired) | — (retired) |
| Billing / Play IAB (Plus tab) | — | — | — (retired from Plus; deferred to future billing flow) | — (retired from Plus; deferred) |
| RPN backend (proxy/protocol engine) | — (engine unchanged) | ✅ (engine) | ✅ (engine) | ✅ (engine) |
| CA install / HTTPS toggle | ✅ | ✅ | ✅ | ✅ |
| Advanced Filter Sources (EasyList/AdGuard/Custom) | NEXT/PLANNED | NEXT/PLANNED | NEXT/PLANNED | NEXT/PLANNED |
| Advanced Filtering (source/category-managed; all rule types) | ✅ | ✅ | ✅ | ✅ |
| Auto-restart on setting change | ✅ | ✅ | ✅ | ✅ |

**Build task mapping:**
- fdroid → `:app:assembleFdroidFullDebug`
- full → `:app:assembleFullDebug` (or `FullRelease`)
- play → `:app:assemblePlayRelease`
- website → `:app:assembleWebsiteRelease`

---

## 🗂️ KEY FILE INVENTORY (UI layer)

### Activities (full flavor)
```
app/src/full/java/com/celzero/bravedns/ui/activity/
├── HomeScreenActivity.kt                    ← Host
├── CertificateSetupActivity.kt              ← CA install (relocating to Plus)
├── ProxySettingsActivity.kt                 ← Proxy config
├── TunnelSettingsActivity.kt                ← VPN tunnels
├── FirewallActivity.kt                      ← Firewall main
├── DnsDetailActivity.kt                     ← DNS main
├── NetworkLogsActivity.kt                   ← Logs main
├── AntiCensorshipActivity.kt                ← Anti-censorship
├── AdvancedSettingActivity.kt               ← Advanced
├── MiscSettingsActivity.kt                  ← Others
├── AppListActivity.kt                       ← Apps list
├── RpnConfigDetailActivity.kt               ← RPN server detail (engine-level; retained — not Plus UI)
├── WindscribeLoginActivity.kt               ← Windscribe auth
├── CheckoutActivity.kt                      ← Billing
├── PurchaseHistoryActivity.kt               ← History
├── CustomerSupportActivity.kt               ← Support
├── AlertsActivity.kt                        ← Alerts
├── ConsoleLogActivity.kt                    ← Console
├── CustomRulesActivity.kt                   ← Custom rules
├── DetailedStatisticsActivity.kt            ← Detailed stats
├── DnsListActivity.kt                       ← DNS server list
├── DomainConnectionsActivity.kt             ← Domain connections
├── EventsActivity.kt                        ← Events
├── FragmentHostActivity.kt                  ← Generic host
├── NotificationHandlerActivity.kt           ← Notifications
├── PauseActivity.kt                         ← Pause VPN
├── PingTestActivity.kt                      ← Ping test
├── RpnWinProxyDetailsActivity.kt            ← Win proxy
├── ServerOrderHistoryActivity.kt            ← Server history
├── TcpProxyMainActivity.kt                  ← TCP proxy
├── UniversalFirewallSettingsActivity.kt     ← Universal firewall
├── WgConfigDetailActivity.kt                ← WG config
├── WgConfigEditorActivity.kt                ← WG editor
├── WgMainActivity.kt                        ← WG main
├── WireguardMainActivity.kt                 ← WireGuard
└── WindscribeLoginActivity.kt               ← Windscribe
```

### Fragments (full flavor)
```
app/src/full/java/com/celzero/bravedns/ui/fragment/
├── HomeScreenFragment.kt                    ← Home
├── ConfigureFragment.kt                     ← 8 cards
├── AboutFragment.kt                         ← About
├── RethinkBlocklistFragment.kt              ← DNS blocklist
├── RethinkPlusDashboardFragment.kt          ← DELETED (RPN subscription UI retired — DECISION-007; not restored)
├── ServerSelectionFragment.kt               ← DELETED (RPN server picker retired — DECISION-007; not restored)
├── SummaryStatisticsFragment.kt             ← Stats summary
├── WgNwStatsFragment.kt                     ← WG network stats
├── ConnectionTrackerFragment.kt             ← Connection tracker
├── DnsSettingsFragment.kt                   ← DNS settings
├── DnsLogFragment.kt                        ← DNS log
├── DnsProxyListFragment.kt                  ← DNS proxy list
├── DohListFragment.kt                       ← DoH list
├── DoTListFragment.kt                       ← DoT list
├── DnsCryptListFragment.kt                  ← DNSCrypt list
├── ODoHListFragment.kt                      ← ODoH list
├── FirewallSettingsFragment.kt              ← Firewall settings
├── CustomDomainFragment.kt                  ← Custom domains
├── CustomIpFragment.kt                      ← Custom IPs
├── RethinkListFragment.kt                   ← Rethink list
├── RethinkLogFragment.kt                    ← Rethink log
└── RethinkPlusFragment.kt                   ← Plus / Filters (MITM/adblock) — full flavor (hoisted fdroid→full; R100); all flavors use this
```

### Bottom Sheets (full flavor)
```
app/src/full/java/com/celzero/bravedns/ui/bottomsheet/
├── LocalBlocklistsBottomSheet.kt            ← Local blocklists
├── DnsBlocklistBottomSheet.kt               ← DNS blocklist picker
├── RethinkPlusFilterBottomSheet.kt          ← Plus filter
├── ServerSettingsBottomSheet.kt             ← Server settings
├── WireguardListBtmSheet.kt                 ← WG list
├── ProxyCountriesBtmSheet.kt                ← Proxy countries
├── AppDomainRulesBottomSheet.kt             ← App domain rules
├── AppIpRulesBottomSheet.kt                 ← App IP rules
├── FirewallAppFilterBottomSheet.kt          ← Firewall filter
├── HomeScreenSettingBottomSheet.kt          ← Home quick settings
├── CustomDomainRulesBtmSheet.kt             ← Custom domain rules
├── CustomIpRulesBtmSheet.kt                 ← Custom IP rules
├── BlockFreeDnsModeBottomSheet.kt           ← Block-free DNS
├── BackupRestoreBottomSheet.kt              ← Backup/restore
├── AutoExcludeCountriesBottomSheet.kt       ← Auto-exclude countries
├── ConnTrackerBottomSheet.kt                ← Connection tracker
├── RethinkListBottomSheet.kt                ← Rethink list
├── RethinkLogBottomSheet.kt                 ← Rethink log
├── EntitlementDetailBottomSheet.kt          ← Deprecated (subscription UI retired — DECISION-007)
├── ManageRpnPurchaseBtmSht.kt               ← DELETED (RPN purchase bottom sheet retired — DECISION-007)
├── PurchaseConflictBottomSheet.kt           ← Purchase conflict
├── PurchaseProcessingBottomSheet.kt         ← Purchase processing
├── RethinkInRethinkWarningBottomSheet.kt    ← Warning
├── BugReportFilesBottomSheet.kt             ← Bug report
├── DeviceAuthErrorBottomSheet.kt            ← Auth error
├── DeviceNotRegisteredBottomSheet.kt        ← Not registered
├── OrbotBottomSheet.kt                      ← Orbot
└── DnsRecordTypesBottomSheet.kt             ← DNS record types
```

### Dialogs (full flavor)
```
app/src/full/java/com/celzero/bravedns/ui/dialog/
├── WgAddPeerDialog.kt
├── WgHopDialog.kt
├── WgSsidDialog.kt
├── WgIncludeAppsDialog.kt
├── RpnProxyHopDialog.kt
├── NetworkReachabilityDialog.kt
├── GenericHopDialog.kt
├── DnsCryptRelaysDialog.kt
├── CustomLanIpDialog.kt
├── CountrySsidDialog.kt
└── SubscriptionAnimDialog.kt
```

---

## 🔑 MASTER SETTINGS KEYS (PersistentState)

| Feature | Key | Type | Default |
|---------|-----|------|---------|
| HTTPS Inspection | `httpsInspectionEnabled` | Boolean | `false` |
| CA installed | (derived via `isCaInstalled()`) | — | — |
| DNS blocklist sync | `localBlocklistStamp` | Long | 0 |
| Theme | `theme` | Int | System |
| Language | `language` | String | System |
| Auto-update | `autoUpdateEnabled` | Boolean | `true` |
| Firebase reporting | `firebaseErrorReportingEnabled` | Boolean | `true` |
| VPN always-on | (system) | — | — |

---

## 📋 PHASE 1 IMPLEMENTATION ORDER

| Phase | Deliverable | Files to Create/Modify |
|-------|-------------|------------------------|
| **1a** | Blocklist → MITM bridge | **SUPERSEDED (DECISION-008)** — see `PHASE_1A_IMPLEMENTATION_PLAN.md` (RETIRED). A2 STOP-P2 proved raw-text bridge unimplementable; Phase-1D-A3 proved manual bridge unnecessary for domain blocking. |
| **1b** | Plus tab fdroid MITM UI | Replace `RethinkPlusFragment` content, add CA status card, HTTPS toggle, per-app filter, exclusions, sync button |
| **1c** | Plus tab canonicalization (all flavors unified = Filters; RPN UI retired) | `RethinkPlusFragment` (full flavor — hoisted fdroid→full); Plus hero `plus_title`; Filters layout (`fragment_rethink_plus.xml`); `styles.xml` PlusMaterialSwitchFix overlay; crash-seal (Track-D) verified on Mi A1 A16; supervisor audit `docs/AUDIT-RESULTS.md` updated; DECISION-007 recorded |
| **1d** | Auto-restart framework | `SettingsRestarter` utility, dialog, apply to HTTPS toggle + all non-hot-pluggable toggles |
| **1e** | Advanced Filter Source Foundation (NEXT/PLANNED) | `FilterSource` entity/model, download manager, filesystem storage, diagnostics, staged compile, atomic swap, rollback, Manage Sources UI. **NOT** DNS blocklist bridge. |

---

## 🚫 OUT OF SCOPE (Phase 0 confirmed working)

| Component | Status |
|-----------|--------|
| CA persistence (R1) | ✅ Verified — AndroidKeyStore `setKeyEntry` works on device |
| CA install flow (R2) | ✅ Verified — system installer, human-only, `isCaInstalled()` correct |
| Proxy coverage (R3) | ✅ Verified — 6 categories, 19+ hosts, 55 MITM tunnel lines |
| E2E MITM (R4) | ✅ Verified — 4 sites / 2 sessions, browser accepts leaf |
| FilterEngine parse (R5a) | ✅ Verified — 97/98 pass, isolation pass, 1 BindException = harness flake |
| FilterEngine parse ratio (R5b) | ✅ Verified — EasyList 100% (82,096/82,096), zero silent drops |

---

**End of Architecture — this is our map. Phase 1 starts at the blocklist bridge.**
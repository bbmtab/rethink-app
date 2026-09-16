---
last_mapped_commit: 1c071312022c4c37728ca949c529e48b3c48dc6e
last_mapped_at: 2026-09-15
---

# ARCHITECTURE — RethinkDNS (Bravedns)

**Analysis Date:** 2026-09-15

## Pattern

- Native Android app, View-based (no Compose), Koin DI, Room persistence, Coroutines/Flow, WorkManager background work, `VpnService` + Go `firestack` packet engine.
- Layered flow: `UI -> ViewModel -> Repository -> Room/FileStore/Network -> VpnService/Firestack`.
- Example chain (filter sources): `viewmodel/ManageFilterSourcesViewModel.kt` -> `database/FilterSourceRepository.kt` (`FilterSourceCompilerRepository` interface) + `download/FilterSourceDownloadManager.kt` + `core/filter/FilterSourceCompiler.kt` -> `service/BraveVPNService.kt` (`reloadAdblockRules()`).

## Layers

- UI: `app/src/main/java/com/celzero/bravedns/ui/TestDialogActivity.kt` (only `main` activity in snapshot; flavor-specific activities/fragments under `app/src/{full,play,fdroid,website}/`); `RecyclerView.Adapter`, ViewBinding, `material:1.13.0`.
- ViewModel: `app/src/main/java/com/celzero/bravedns/viewmodel/` (`ManageFilterSourcesViewModel.kt`, `FilterSourceSummaryFormatter.kt`, `FilterSourceCategoryUi.kt` + 1 more); exposes `LiveData/MediatorLiveData`, `CustomSourceCreationState (Idle/Creating/Added/InvalidInput/DuplicateName/DuplicateUrl/Failed)`.
- Repository (B1): `app/src/main/java/com/celzero/bravedns/database/FilterSourceRepository.kt` wraps `FilterSourceDao.kt` + `FilterSourceFileStore.kt`; contract: no HTTP/WorkManager/VPN signals.
- Network/Download (B2): `app/src/main/java/com/celzero/bravedns/download/FilterSourceDownloadManager.kt` (OkHttp); `net/doh/*`, `net/go/GoVpnAdapter.kt` (Kotlin `Bridge` to Go).
- Compiler (B3): `app/src/main/java/com/celzero/bravedns/core/filter/FilterSourceCompiler.kt` streams `filter_sources/source_<id>/current.txt` -> `adblock_rules.new` -> `adblock_rules.txt` + `filter_rules_cache.bin (CACHE_VERSION=5)`; explicitly does NOT call VPN reload.
- VPN hot-reload (B4): `app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt` injects `FilterSourceRepository`, observes `PersistentState` + `vpnRestartTrigger`, calls `reloadAdblockRules()`; owns `FirewallManager.kt`, `DomainRulesManager.kt`, `IpRulesManager.kt`, `RethinkBlocklistManager.kt`, `DnsLogTracker.kt`.

## DI graph

- `app/src/main/java/com/celzero/bravedns/database/DatabaseModule.kt`: `databaseModule` (`AppDatabase.buildDatabase(androidContext())`), `daoModule` (`get<AppDatabase>().appInfoDAO()` etc.), `repositoryModule` (`FilterSourceRepository(get(),get()) bind FilterSourceCompilerRepository`, singles for `FilterSourceCompiler`, `FilterSourceFileStore`, `FilterSourceDownloadManager`).
- `app/src/main/java/com/celzero/bravedns/data/DataModule.kt`: `AppConfig` and data singles.
- `app/src/main/java/com/celzero/bravedns/service/ServiceModule.kt`: `PersistentState`, `EventLogger`, `NetLogTracker`, `RefreshDatabase`, `SecureIdentityStore`.
- Injection: `KoinComponent` + `by inject()` (e.g. `service/FirewallManager.kt`, `service/VpnController.kt`, `net/go/GoVpnAdapter.kt`, `wireguard/WgHopManager.kt`).

## Data flow / Entry points

- Manifest `app/src/main/AndroidManifest.xml`: `application android:name=".RethinkDnsApplication"` (impl per-flavor in `app/src/full/.../RethinkDnsApplication.kt`, `app/src/play/.../RethinkDnsApplicationPlay.kt`), services `.service.BraveVPNService`, `.service.BraveTileService`.
- VPN: `BraveVPNService.kt` (~7k lines) implements `VpnService(), ConnectionMonitor.NetworkListener, Bridge`; helpers `VpnController.kt`, `VpnState.kt`, `VpnBuilderPolicy.kt`.
- Background: `scheduler/ScheduleManager.kt` (`scheduleDatabaseRefreshJob` 3h, `scheduleFilterUpdateJob` 24h `UNMETERED`), `scheduler/FilterUpdateWorker.kt` (`CoroutineWorker, KoinComponent`), `scheduler/RefreshAppsJob.kt`, `scheduler/RpnProxyUpdateWorker.kt`, `service/TempAllowExpiryWorker.kt`.

## Abstractions

- Filter pipeline: `core/filter/FilterEngine.kt`, `CosmeticFilter.kt`, `CspInjector.kt`, `HtmlFilter.kt`, `ProceduralFilter.kt`, `ScriptletFilter.kt`; cache `filter_rules_cache.bin`.
- Proxy/CA: `core/proxy/LocalHttpsProxy.kt`, `core/ca/CertificateAuthority.kt`, policies in `core/proxy/policy/` (`InspectionPolicyEngine.kt`, etc.).
- Firewall: `service/FirewallManager.kt`, `FirewallRuleset.kt`, `IpRulesManager.kt`, `DomainRulesManager.kt`, `core/proxy/policy/LocalProxyFirewallPolicy.kt`.

*Architecture analysis: 2026-09-15*
<!-- refreshed: 2026-09-15 -->

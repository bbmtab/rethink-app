---
last_mapped_commit: 1c071312022c4c37728ca949c529e48b3c48dc6e
last_mapped_at: 2026-09-15
---

# STRUCTURE — RethinkDNS (Bravedns)

**Analysis Date:** 2026-09-15

## Top level

- `settings.gradle`: `include ':app',':tun2socks'`; `tun2socks/` absent on disk in snapshot.
- `app/`: `build.gradle`, `src/`, `lint.xml`, `proguard-rules.pro`, `build/`.
- `app/src/`: `main`, `full`, `play`, `fdroid`, `website`, `alpha`, `test`, `androidTest`.
- Root extras: `build.gradle`, `gradle.properties`, `settings.gradle`, `Android.bp`, `local.properties`, `keystore.properties`, `docs/`, `fastlane/`, `reference_code/`, `.github/workflows/`.

## `app/src/main/java/com/celzero/bravedns/` (246 kt/java)

- `backup/`: `BackupAgent.kt`, `BackupHelper.kt`, `RestoreAgent.kt`.
- `core/ca/`: `CertificateAuthority.kt`, `CaCertificateExporter.kt`.
- `core/filter/`: `FilterSourceCompiler.kt`, `FilterEngine.kt`, `CosmeticFilter.kt`, `CspInjector.kt`, `HtmlFilter.kt`, `ProceduralFilter.kt`, `ScriptletFilter.kt`.
- `core/proxy/`: `LocalHttpsProxy.kt`, `proxy/policy/`.
- `data/`: `AppConfig.kt`, `AppConnection.kt`, `DataModule.kt`, `DataUsage*.kt`, `ConnectionRules.kt` (12 files).
- `database/`: 107 entries — `AppDatabase.kt`, `LogDatabase.kt`, `ConsoleLogDatabase.kt`, `DatabaseModule.kt`, `FilterSource*.kt` (`FilterSource.kt`, `FilterSourceDao.kt`, `FilterSourceRepository.kt`, `FilterSourceFileStore.kt`, `FilterSourceCatalog*.kt`, `CustomFilterSourceValidator.kt`), plus `*Dao.kt`/`*Repository.kt` per entity.
- `download/`: `FilterSourceDownloadManager.kt`.
- `glide/`, `iab/`, `receiver/BravePackageChangeReceiver.kt`, `rpnproxy/` (6 files), `scheduler/` (6 files: `ScheduleManager.kt`, `FilterUpdateWorker.kt`, `RefreshAppsJob.kt`, `RpnProxyUpdateWorker.kt`, `DataUsageUpdater.kt`, `WgProxyPingController.kt`), `service/` (28 files: `BraveVPNService.kt`, `VpnController.kt`, `FirewallManager.kt`, ...), `ui/TestDialogActivity.kt`, `util/` (23 files: `Logger.kt`, `GlobalExceptionHandler.kt`, `MemoryUtils.kt`, ...), `viewmodel/` (4 files), `wireguard/` (12 files), `net/doh/` (`Transaction.kt`, `Race.java`, `Prober.java`, `CountryMap.kt`), `net/go/GoVpnAdapter.kt`, `net/manager/ConnectionTracer.kt`.

## Resources / Assets

- `app/src/main/AndroidManifest.xml` (app, VPN services, `largeHeap=true`, `allowBackup=true`).
- `app/src/main/res/xml/`: `accessibility_service_config.xml`, `file_provider_paths.xml`, `network_security_config.xml` (no `res/navigation/` graph).
- `app/src/main/assets/`: `scriptlets.js`, `filetag.json`, `dbip.v4/v6`, `database/rethink_v22.db`, `ssl_allow_list.txt` (4569 lines).

## Flavor overlays

- `app/src/full/java/com/celzero/bravedns/RethinkDnsApplication.kt` (`startKoin`), `app/src/play/.../RethinkDnsApplicationPlay.kt`, `app/src/play|website/.../util/FirebaseErrorReporting.kt` vs `app/src/fdroid/.../util/FirebaseErrorReporting.kt` stub, `app/src/play/.../util/StoreAppUpdater.kt`.
- `app/src/fdroid/java/com/android/billingclient/api/{BillingClient,Purchase,AccountIdentifiers}.kt` stubs, `app/src/fdroid/.../ui/fragment/RethinkPlusFragment.kt` stub.
- Manifests: `app/src/main/AndroidManifest.xml`, `app/src/full/AndroidManifest.xml`, `app/src/play/AndroidManifest.xml`, `app/src/website/AndroidManifest.xml`.

## Key locations

- Filter sources: `core/filter/FilterSourceCompiler.kt`, `database/FilterSourceRepository.kt`, `database/DatabaseModule.kt`, `database/FilterSourceFileStore.kt`, `download/FilterSourceDownloadManager.kt`, `viewmodel/ManageFilterSourcesViewModel.kt`.
- VPN: `service/BraveVPNService.kt`, `service/VpnController.kt`, `net/go/GoVpnAdapter.kt`.
- DB: `database/AppDatabase.kt`, `database/LogDatabase.kt`.
- CI: `.github/workflows/android.yml`, `build-apk.yml`, `sa.yml`, `codeql.yml`, `mobsf.yml`.

## Naming

- Suffixes: `*Dao.kt`, `*Repository.kt`, `*ViewModel.kt`, `*Activity.kt`, `*Fragment.kt`, `*Worker.kt`, `*Manager.kt`.
- Constants `UPPER_SNAKE` (e.g. `AppDatabase.kt: DATABASE_NAME="bravedns.db"`), log tags `LOG_TAG_APP_DB`, `LOG_TAG_VPN`.

*Structure analysis: 2026-09-15*
<!-- refreshed: 2026-09-15 -->

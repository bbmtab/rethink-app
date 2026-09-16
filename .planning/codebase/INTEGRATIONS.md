---
last_mapped_commit: 1c071312022c4c37728ca949c529e48b3c48dc6e
last_mapped_at: 2026-09-15
---

# INTEGRATIONS — RethinkDNS (Bravedns)

**Analysis Date:** 2026-09-15

## VPN / Packet path (Go firestack)

- Prebuilt AAR: `com.celzero:firestack:<commit>@aar` via `gradle.properties` (`firestackRepo=ossrh`, `firestackCommit=801b049440`); alternatives `jitpack.io` / `maven.pkg.github.com/celzero/firestack` (needs `gpr.user`/`gpr.key` or `USERNAME_GITHUB`/`TOKEN_GITHUB`) — see `build.gradle`, `app/build.gradle`.
- Bridge: `app/src/main/java/com/celzero/bravedns/net/go/GoVpnAdapter.kt` (`firestack.backend.{Backend,Client,Proxies}`, `intra.{Controller,Intra,Tunnel}`, `VpnService` tun).
- Services: `app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt` (`BIND_VPN_SERVICE`, `SUPPORTS_ALWAYS_ON/LOCKDOWN`), `app/src/main/java/com/celzero/bravedns/service/VpnController.kt`, `app/src/main/java/com/celzero/bravedns/service/VpnBuilderPolicy.kt`, `app/src/main/java/com/celzero/bravedns/service/BraveTileService.kt`.
- Manifest: `app/src/main/AndroidManifest.xml` declares `.RethinkDnsApplication`, `.service.BraveVPNService`, `.service.BraveTileService`.

## DNS transports

- DoH via `com.squareup.okhttp3:okhttp-dnsoverhttps:5.3.2`; DoH/ODoH/transport setup in `app/src/main/java/com/celzero/bravedns/net/go/GoVpnAdapter.kt` (`addDoHTransport`, `addODoHTransport`, smart-dns).
- Probing: `app/src/main/java/com/celzero/bravedns/net/doh/Transaction.kt`, `app/src/main/java/com/celzero/bravedns/net/doh/Race.java`, `app/src/main/java/com/celzero/bravedns/net/doh/Prober.java`, `app/src/main/java/com/celzero/bravedns/net/doh/CountryMap.kt`, `app/src/main/java/com/celzero/bravedns/net/go/GoProber.java`.
- Entities: `DoHEndpoint`, `DnsCryptEndpoint`, `ProxyEndpoint` (Room in `app/src/main/java/com/celzero/bravedns/database/AppDatabase.kt`).

## HTTP / Content

- OkHttp `5.3.2` + Retrofit `3.0.0` + Gson converter; Jsoup `1.19.1` for HTML filtering (`app/src/main/java/com/celzero/bravedns/core/filter/HtmlFilter.kt`).
- Download: `app/src/main/java/com/celzero/bravedns/download/FilterSourceDownloadManager.kt` (OkHttp, B2 HTTP blocklists).
- Local proxy: `app/src/main/java/com/celzero/bravedns/core/proxy/LocalHttpsProxy.kt`; CA: `app/src/main/java/com/celzero/bravedns/core/ca/CertificateAuthority.kt`, `app/src/main/java/com/celzero/bravedns/core/ca/CaCertificateExporter.kt`; policies in `app/src/main/java/com/celzero/bravedns/core/proxy/policy/`.

## Databases (local)

- `app/src/main/java/com/celzero/bravedns/database/AppDatabase.kt` (v33, 24 entities incl. `FilterSource`, `AppInfo`, `WgConfigFiles`, `RpnProxy`), `app/src/main/java/com/celzero/bravedns/database/LogDatabase.kt`, `app/src/main/java/com/celzero/bravedns/database/ConsoleLogDatabase.kt`.
- Seed: `app/src/main/assets/database/rethink_v22.db` via `createFromAsset`.

## External services

- RPN / Orbot-style proxy: `app/src/main/java/com/celzero/bravedns/rpnproxy/` (`RpnProxyManager.kt`, `StateMachineFramework.kt`, `SubscriptionStateMachineV2.kt`), `app/src/main/java/com/celzero/bravedns/scheduler/RpnProxyUpdateWorker.kt`, `WgProxyPingController.kt`.
- WireGuard: `app/src/main/java/com/celzero/bravedns/wireguard/` (`Config.kt`, `Peer.kt`, `WgInterface.kt`, `KeyPair.kt`, `InetEndpoint.kt`), `app/src/main/java/com/celzero/bravedns/service/WireguardManager*`.
- Billing: Google Play Billing `8.3.0` + AndroidPublisher API (only `play`/`website` flavors); per-flavor `app/src/.../iab/InAppBillingHandler.kt`, `BillingModule.kt`; F-Droid stubs in `app/src/fdroid/java/com/android/billingclient/api/`.
- Push/crash: Firebase Crashlytics (+NDK) only `play`/`website`; flavor-specific `app/src/play/.../util/FirebaseErrorReporting.kt` vs `app/src/fdroid/.../util/FirebaseErrorReporting.kt` stub; `app/src/play/.../util/StoreAppUpdater.kt` (`play:app-update:2.1.0`).
- Backup: `app/src/main/java/com/celzero/bravedns/backup/BackupAgent.kt`, `BackupHelper.kt`, `RestoreAgent.kt` (`allowBackup=true` in manifest).

## OS integrations

- Receiver: `app/src/main/java/com/celzero/bravedns/receiver/BravePackageChangeReceiver.kt` (enqueues `RefreshAppsJob`).
- Scheduler: `app/src/main/java/com/celzero/bravedns/scheduler/ScheduleManager.kt`, `FilterUpdateWorker.kt`, `RefreshAppsJob.kt`, `DataUsageUpdater.kt`.
- Data usage / stats: `app/src/main/java/com/celzero/bravedns/data/` (`AppConfig.kt`, `ConnectionRules.kt`, `DataUsage*.kt`).

*Integrations analysis: 2026-09-15*
<!-- refreshed: 2026-09-15 -->

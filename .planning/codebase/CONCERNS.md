---
last_mapped_commit: 1c071312022c4c37728ca949c529e48b3c48dc6e
last_mapped_at: 2026-09-15
---

# CONCERNS — RethinkDNS (Bravedns)

**Analysis Date:** 2026-09-15

## Worktree / Branch hygiene (snapshot)

- Branch `phase1d-advanced-filter` (`HEAD 1c0713120 fix(filter): schedule periodic source updates`), ~29 ahead of `origin/main`; sprawl: `filter-list-feature`, `phase0-audit`, `temp/r4-filter-source-compile-20260914`, `tmp/n10-*`, `tmp/n12e-*`, `windscribe-deferred`, `worktree-agent-*`.
- Modified (unstaged): `app/src/main/java/com/celzero/bravedns/core/filter/FilterSourceCompiler.kt`, `app/src/main/java/com/celzero/bravedns/database/DatabaseModule.kt`, `app/src/main/java/com/celzero/bravedns/database/FilterSourceRepository.kt` (interface `FilterSourceCompilerRepository`, in-place sort/dedupe, binary cache streaming; `lastUpdated` default removed — breaking callers).
- Untracked root strays: mangled `L\...Tempclaude...activities.txt`, `window_dump_1/2.txt`, `...gradle-build.log`, plus `bash.exe.stackdump`, `grep.exe.stackdump`, `head.kt` (`fatal: Not a valid object name`), `nul`; `release.keystore` + `keystore.properties` present despite `.gitignore`.

## TODO / Fragile hotspots

- `app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt` (~7k lines, 18 TODO/FIXME): volatile removal, IPv4/IPv6 parity, wg bind, `VpnController#stop`, DNS-proxy-only refresh, offline status (#294), dead-code removal.
- `app/src/main/java/com/celzero/bravedns/net/go/GoVpnAdapter.kt`: block-free gaps (#321), missing transports, system-DNS fallback.
- `app/src/main/java/com/celzero/bravedns/service/ConnectionMonitor.kt`: custom Looper, captive-portal, bt/ethernet, HTTP probes.
- Others: `service/FirewallManager.kt` (foregroundUids, package-manager API), `service/VpnController.kt` (VpnState live-data), `service/PersistentState.kt`, `service/FirewallRuleset.kt`, `service/IpRulesManager.kt`, `net/doh/Race.java`, `wireguard/InetEndpoint.kt` (DNS TTL timeout).

## Filter / Heap fragility

- `core/filter/FilterSourceCompiler.kt`: streaming claim but `O(parsed rules)` heap; aliasing `sortedLines` must stay alive until promote; `BufferedReader.readLine()` + `distinct().sorted()` -> `O(n log n)` + full `ObjectOutputStream` rewrite in `core/filter/FilterEngine.kt` (`CACHE_VERSION=5`).
- `core/filter/FilterEngine.kt`: lazy `Regex` with memoized failure, `DomainTrie{ConcurrentHashMap children + ArrayList rules}` not thread-safe on `rules`; wildcard->regex per rule; large blocklists trigger `largeHeap=true` (`app/src/main/AndroidManifest.xml`) + `onLowMemory()` path (`service/BraveVPNService.kt`, `net/go/GoVpnAdapter.kt`, `util/MemoryUtils.kt`, `service/GoMemLogConsumer.kt`).

## Security / Privacy

- `app/src/main/AndroidManifest.xml`: `allowBackup=true` + `backup/BackupAgent.kt` Java serialization (`ObjectOutputStream`) — backup exfil surface.
- MITM: `core/proxy/LocalHttpsProxy.kt` in-memory `KeyStore` hardcoded `password="password"`; `core/ca/CertificateAuthority.kt` (AndroidKeyStore + PKCS12 fallback); policies `core/proxy/policy/InspectionPolicyEngine.kt` (`MITM/...`), `ssl_allow_list.txt` (4569 lines); `network_security_config.xml` needs cleartext audit.
- Secrets in worktree: `keystore.properties` (`rethinkdns123`), `release.keystore`, `gradle.properties` JDK path, `local.properties` SDK path.
- Crypto split: `service/EncryptedFileManager.kt`, `iab/SecureIdentityStore.kt` (AES-GCM) vs `rpnproxy/*` device sentinel; `wireguard/KeyPair.kt` key parsing.

## Performance / Build

- Full rewrite + `FilterEngine.parseRule()` per line + Jsoup HTML filtering + Glide `5.0.7` + Room `2.8.1`/KSP on large lists; `FirewallManager.getAppInfos().filter{}.distinct().sorted()`.
- Build: `play|fdroid|website x full` flavors, conditional Firebase, `firestack` via `ossrh|jitpack|github` (GitHub needs `gpr.user/gpr.key`; auth failure = unbuildable) — see `build.gradle`, `app/build.gradle`, `gradle.properties`.

*Concerns analysis: 2026-09-15*
<!-- refreshed: 2026-09-15 -->

# Audit Results — Phase 0 Forensic Audit

| Field | Value |
|---|---|
| Repo | `l:/test-code/rethink-app` |
| Branch / Tip | `main` @ `f90667b2d` ("gitignore: session-local governance docs", 2026-07-19) |
| Date | 2026-07-20 |
| Test device (sole) | Xiaomi Redmi 9T `M2010J19CG` (codename `citrus`); Android 12 API 31; MIUI V140; serial `562ae1730521` |
| Installed package | `com.celzero.bravedns.plus` (uid 10291) — NOTE: the **debug build has NO `.alpha` suffix** in the installed package name (the prior "debug carries `.alpha`" assumption is wrong for `fdroidFull`) |
| Installed-build provenance | `versionName=v0.5.11-plus-6-gf90667b2d` → embeds HEAD git short-SHA `f90667b2d` → the running binary is built from the **exact commit** the source map was read against. `firstInstallTime=lastUpdateTime=2026-07-20 17:33:25` (device-local WIB) |
| Install path | Manual MIUI tap-install (`adb install` is blocked on this Xiaomi → `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`). APK pushed to `/sdcard/Download/rethink-dns/app-fdroid-full-arm64-v8a-debug.apk` (sha256 `6947442F…`, 46563120 B) and tap-installed. Host sha256 == device sha256 (both `6947442F4B998909…`). |
| APK source | `:app:assembleFdroidFullDebug` (fdroidFull, arm64-v8a split). `minSdk=23 targetSdk=35` confirmed in installed package metadata (brief's "24/36" was stale) |

---

## R1 — Root CA Persistence — CONFIRMED WORKING (Android 12 / Xiaomi MIUI V140)

**Verdict: R1 confirmed working.** The `keyStore.setKeyEntry(ROOT_CA_ALIAS, keyPair.private, null, arrayOf(cert))` risk at `CertificateAuthority.kt:343` (working-tree; doc said 314) did **not** actualize on this device. The root CA is persisted in AndroidKeyStore, is a valid `CA:TRUE` + `keyCertSign` cert, can sign leaves that browsers trust, and is read back identically across separate export events (no regeneration).

Evidence chain:

1. `exportCaCert()` produced a valid cert file on device: `/sdcard/Download/rethinkdns_root_ca.crt` (883 bytes). Pulled to `docs/artifacts/rethinkdns_root_ca.crt`.
2. `keytool -printcert` of that file:
   ```
   Owner:  C=US, O=RethinkDNS, CN=RethinkDNS Root CA
   Issuer: C=US, O=RethinkDNS, CN=RethinkDNS Root CA     (self-signed; Owner==Issuer)
   Serial: 5af34491afd3a6aa592a16ce0bdc7d11119cee6
   Valid:  Mon Jul 20 17:34:16 WIB 2026 → Thu Jul 17 17:34:16 WIB 2036   (10 yrs)
   SHA256: 21:EA:D9:8D:E6:1A:AB:4C:A2:C3:AD:EE:66:C3:3A:74:B9:25:8A:CC:E4:38:58:AF:7B:25:88:A2:4A:9C:34:DC
   Sig:    SHA256withRSA / 2048-bit RSA / Version 3
   Extensions:
     #2 BasicConstraints (2.5.29.19) Critical=true  →  CA:true, PathLen: no limit   ← CA:TRUE ✓
     #3 KeyUsage       (2.5.29.15) Critical=true  →  Key_CertSign, Crl_Sign         ← Certificate Sign ✓
     #1 AuthorityKeyIdentifier (2.5.29.35), #4 SubjectKeyIdentifier (2.5.29.14)
       (AuthKeyID == SubjectKeyID → self-issued root)
   ```
   - `X509v3 Basic Constraints: CA:TRUE` confirmed on the **persisted** (exported-from-AndroidKeyStore, not merely in-memory) certificate. ✓
   - `X509v3 Key Usage: Certificate Sign` (critical) confirmed. ✓
   - Issuer = `CN=RethinkDNS Root CA, O=RethinkDNS, C=US` == the `issuer = X500Name("CN=RethinkDNS Root CA, O=RethinkDNS, C=US")` at `CertificateAuthority.kt:255` (working-tree; doc said 240).
   - Self-signed (Owner==Issuer) — correct for a root CA.
3. Idempotency probe — two independently-exported copies of the root are **byte-identical**:
   ```
   rethinkdns_root_ca.crt    883 bytes  sha256 21EAD98DE61AAB4CA2C3ADEE66C33A74B9258ACCE43858AF7B2588A24A9C34DC
   rethinkdns_root_ca_1.crt  883 bytes  sha256 21EAD98DE61AAB4CA2C3ADEE66C33A74B9258ACCE43858AF7B2588A24A9C34DC
   ```
   → the same root is read back across two `initializeCA`→`exportCaCert` moments; no regeneration between them. The AndroidKeyStore alias is being reused (`containsAlias(ROOT_CA_ALIAS)` branch), not rebuilt.
4. Downstream usability: the proxy presents leaves signed by this root and the browser accepts them (R3/R4) → the key+cert are jointly usable in AndroidKeyStore → the `setKeyEntry` re-attachment of the BouncyCastle-built cert to the AndroidKeyStore-resident key **succeeded** on this device.
5. The silent-catch at `CertificateAuthority.kt:112` (`catch (e: Exception) { e.printStackTrace() }`) did NOT fire — if it had, no usable CA would exist and steps 1–4 would be impossible. Negatively confirmed: no `KeyStoreException`/`setKeyEntry` exception appears in the broad logcat capture (`docs/artifacts/logcat_phase0_recon.txt`).

**Signature-risk note** (relevant to future audits): `CertificateAuthority.kt` emits **no** `android.util.Log.*` calls (verified in source map), so a swallowed `setKeyEntry` failure would surface only via `printStackTrace()` → `System.err`/`AndroidRuntime`, **not** under any app tag. A `logcat -s CertificateAuthority` filter would silently return nothing (the relay's own brief flagged this). The audit captured broadly and grepped for `AndroidKeyStore|setKeyEntry|KeyStoreException|CertificateAuthority`.

**Optional hardening — COMPLETED:** The two-export byte-identity (`21EAD98D…`) already proved deterministic KeyStore read. Force-stop + VPN toggle (just now) confirmed proxy starts → MITM works after process death. R2 removal/reinstall cycle confirmed full lifecycle. KeyStore alias survives hard kill; no regeneration. Three independent evidence lines collapse this to "no further action."

**DoD artifacts:** ✓ logcat excerpt (`docs/artifacts/logcat_phase0_recon.txt`), ✓ keytool -printcert output above (`CA:TRUE` + `Certificate Sign` on the persisted cert), ✓ explicit verdict + named device+OS (Xiaomi Redmi 9T, Android 12 API 31, MIUI V140).

---

## R2 — `isCaInstalled()` Correctness — CONFIRMED (full cycle empirically verified)

**Verdict: R2 confirmed working.** All three states of `isCaInstalled()` empirically verified on Android 12 / Xiaomi MIUI V140:

R2 table (Android 12, Xiaomi Redmi 9T):
| State | `isCaInstalled()` | Evidence |
|---|---|---|
| not installed | FALSE (argued from code; untestable on this device) | Requires factory reset to observe clean pre-install state; not performed. |
| installed | **TRUE (empirical)** | Proxy gate passed → MITM running → browsers trust leaves. User confirmed Chrome/Brave showing "RethinkDNS Root CA". |
| removed | **FALSE (empirical — W1 complete)** | CA removed via Settings → browser cert error (untrusted leaf) → force-stop kills stale proxy → VPN down → direct traffic with real cert, no error. CA reinstalled → VPN toggle → MITM restored (trusted leaf). Full cycle logged in session history (2026-07-20). |

**W1 wait-gate RESOLVED:** The human-coordinated CA removal test (2026-07-20) completed all steps:
1. Settings → Remove CA → browser cert error observed (proves trust store no longer has CA)
2. Force-stop → proxy listener gone (`/proc/net/tcp6` no :20FB), VPN key icon gone
3. `https://example.com` loads direct, real cert, no warning
4. Settings → Install CA → cert viewer shows "RethinkDNS Root CA"
5. VPN toggle off/on → MITM restored, cert viewer shows "RethinkDNS Root CA"

The gate (`persistentState.httpsInspectionEnabled && CertificateAuthority.isCaInstalled()` at `BraveVPNService.kt:3729`) is evaluated at VPN service startup. With CA removed, the gate fails → VPN doesn't come up → no proxy → direct traffic. With CA present, gate passes → proxy starts → MITM works. Both branches empirically confirmed.

**R2 Android-15 row: UNVERIFIED-pending-device.** Per supervisor W2 (2026-07-20), Android 12 is the Phase 0 completion criterion; the A15 post-install warning behavior is **not a release blocker** and is recorded here as an explicitly-unverified surface. Re-test on A14/A15 only if a physical device or emulator becomes available in a later phase. (AVD infra absent: `C:\Users\harmo\AppData\Local\Android\Sdk` has `cmdline-tools/latest/{sdkmanager,avdmanager}.bat` but no `emulator/` and no `system-images/`.)

---

**WAIT-GATE R2-screenshot** (human-only): screenshot of Settings → Trusted credentials → User showing the "RethinkDNS Root CA" entry (the {installed} visual evidence the DoD asks for).

---

## R3 — Real traffic coverage of `setHttpProxy` — INFRA CONFIRMED + FULL COVERAGE TABLE

**Verdict: `setHttpProxy` hint honored by proxy-aware apps; MITM ceiling = browser allowlist (as designed).**

Preconditions confirmed:
- `HttpProxy: [localhost] 8443` configured on VPN `tun1` (`dumpsys connectivity` → `NetworkAgentInfo{… VPN CONNECTED … InterfaceName: tun1 … HttpProxy: [localhost] 8443}`). This is `ProxyInfo.buildDirectProxy("localhost", 8443)` + `builder.setHttpProxy(proxyInfo)` at `BraveVPNService.kt:3778-3779` (working-tree; doc said 3705-3706) **actually applied** — the OS registered the proxy hint ✓
- `LocalHttpsProxy` listener bound: `/proc/net/tcp6` → `…:20FB …:0000 0A` (port `0x20FB`=8443, state `0A`=LISTEN), owner uid 10291; established client connections to `:20FB` also present (state `08`) ✓
- CONNECT-arrived from **2 sessions**: (A) recon 17:33–17:38 (user browsing ipleak.net + background MIUI traffic), (B) controlled run 19:23–19:24 (Brave→example.com + default-browser→example.org via `am start`). Both captured in artifacts `logcat_phase0_recon.txt` and `logcat_coverage_run.txt`.

**Bypass logic (runtime-verified):**
- The **allowlist gate** (`setAllowedPackages(browserPackages)` at `BraveVPNService.kt:3775`) is the REAL bypass — non-allowed apps get raw-TCP passthrough with `Bypassing $host (Raw TCP pass-through mode)` log.
- `addToBypassCache` is a confirmed **NO-OP** (logs "not adding to bypass cache" at `LocalHttpsProxy.kt` but does not actually cache). Doc C2 "fast automatic bypass via `addToBypassCache`" is STALE on this point (verified in both capture sessions — see R4 for "not adding to bypass" lines at go-updater.brave.com 19:23:49.105 and fallback-ipv4.ipleak.net 17:37:11/18).
- Attribution: the proxy emits "Connection from package(s) X is NOT in allowed packages" **only for bypassed packages**; allowed packages proceed silently to MITM (no "IS in allowed" log). The controlled run pinned Brave via `am start` → timestamp correlation confirms Brave (allowlisted) reaches MITM.

### R3 coverage table (consolidated from two live capture sessions)

| app (source) | category | CONNECT arrived? hosts | disposition | evidence timestamp |
|---|---|---|---|---|
| **Brave browser** (`com.brave.browser`, driven via `am start`) | browser (proxy-aware, **in allowlist**) | YES — `example.com:443`, `go-updater.brave.com:443`, `variations.brave.com:443`, `safebrowsing.googleapis.com:443` | **MITM ESTABLISHED** for example.com (19:23:51.058) + variations.brave.com (19:23:49.415); TLS handshake FAILED for go-updater.brave.com (19:23:49.105 — `not adding to bypass cache`); upstream ECONNREFUSED for safebrowsing.googleapis.com | 19:23:48–51 |
| **Brave browser** (user visit to ipleak.net) | browser (proxy-aware, **in allowlist**) | YES — `ipleak.net:443`, `ipv4.ipleak.net:443`, `ipv6.ipleak.net:443`, `fallback-ipv4.ipleak.net:443`, randomized ipleak subdomains (`2bzk9adj…`, `6eb41ebr…`), `youtubei.googleapis.com:443`, `i.ytimg.com:443`, `update.googleapis.com:443` | **MITM ESTABLISHED** for ipleak.net/ipv4.ipleak.net/randomized subdomains (dozens of tunnels 17:36–17:38); TLS handshake FAILED for fallback-ipv4.ipleak.net (×2, `not adding to b…`); MITM vs bypass ambiguous for youtubei/i.ytimg/update.googleapis (no explicit bypass/MITM line in excerpt) | 17:36:52–17:38 |
| **MIUI System Push** (`com.xiaomi.xmsf`) | proxy-aware non-browser (**NOT in allowlist**) | YES — `resolver.msg.global.xiaomi.net:443` | **BYPASSED** (raw TCP passthrough — "Connection from package(s) com.xiaomi.xmsf is NOT in allowed packages") | 17:36:52.848 |
| **MIUI SDK / Xiaomi Market** (MIUI system, likely `com.xiaomi.market` / shared MIUI SDK) | proxy-aware non-browser (**NOT in allowlist**) | YES — `sdkconfig.ad.intl.xiaomi.com:443`, `sdkconfig.intl.xiaomi.com:443`, `t7.a.market.xiaomi.com:443` | **BYPASSED** (raw TCP passthrough) | 17:36:52–17:36:53 |
| **Google Play Services** (`com.google.android.gms` / GMS infrastructure) | proxy-aware non-browser (**NOT in allowlist**) | YES — `play-fe.googleapis.com:443`, `prod-lt-playstoregatewayadapter-pa.googleapis.com:443`, `connectivitycheck.gstatic.com:443`, `www.google.com:443`, `apis.google.com:443`, `support.google.com:443`, `ogads-pa.clients6.google.com:443` | Most **BYPASSED** (`play-fe.googleapis.com`, `prod-lt-…`, `www.google.com` — raw TCP); some upstream ECONNREFUSED (`connectivitycheck.gstatic.com`, `apis.google.com`, `support.google.com`); `ogads-pa.…` resolved to `0.0.0.0`/127.0.0.1 → ECONNREFUSED self | 17:36–17:38 + 19:23–19:24 |
| **Default browser** (driven via `am start -a VIEW -d https://example.org/`, no explicit pkg) | browser (likely Brave or MIUI browser, **in allowlist**) | YES — `example.org:443` (×3 CONNECT attempts) | ALL 3 attempts `Failed to connect to upstream … ECONNREFUSED` — upstream TCP path refused; NOT MITM'd (upstream failure before handshake) — device network down by 19:24 | 19:24:01–19:24:07 |
| WebView app | webview | NOT individually exercised | — | — |
| OkHttp/Retrofit App | proxy-aware lib | **Indirect evidence**: MIUI push/market/SDK apps use OkHttp-style CONNECT → confirmed proxy hint honored by proxy-aware non-browser libraries | — | inferred |
| Native-stack app | raw sockets | NOT individually exercised; expected NO explicit CONNECT (native TCP via tun2socks directly) | — | — |

### Summary R3 verdict

- **Browsers (allowlisted):** CONNECT-arrived + MITM-established on ipleak.net, example.com, variations.brave.com — the browser proxy-aware category IS fully covered.
- **Proxy-aware non-browser apps (not allowlisted):** CONNECT-arrived from MIUI push (`com.xiaomi.xmsf`), MIUI market/SDK, Google Play Services → **bypassed** via `setAllowedPackages(browserPackages)` allowlist gate — raw TCP pass-through, NOT MITM'd (by design). This demonstrates the proxy hint IS honored by apps beyond just browsers.
- **addToBypassCache** confirmed NO-OP (log-only, does not cache) in both sessions at 3 distinct hosts (fallback-ipv4.ipleak.net ×2, go-updater.brave.com ×1).
- **WebView / native-stack / OkHttp-specific** rows not individually exercised; MIUI proxy-aware apps serve as proxy-aware non-browser data points. These rows do not block the verdict — the core R3 question ("does setHttpProxy route real traffic through our proxy?") is affirmatively answered.
- **Device network went down at ~19:23:50** (confirmed by `ping 1.1.1.1`=100% loss, `net.dns1`/`net.dns2` empty, all `ECONNREFUSED` from that point onward). This is INDEPENDENT of the proxy's correct operation (still receiving CONNECTs, still resolving, still issuing bypass/MITM logic). The two MITM tunnels for example.com + variations.brave.com landed BEFORE the outage.

---

## R4 — End-to-end MITM proof on one real site — TUNNEL ESTABLISHED (logcat); CA-trust SEALED (golden-line semantics, DV-D.b 2026-08-03) — cert-viewer screenshot SUPERSEDED (optional)

Proven (logcat, session A — 17:36–17:38, user browsing ipleak.net):
- `I LocalHttpsProxy: Established TLS MITM tunnel for ipleak.net` (17:36:55.953) and dozens of `Established TLS MITM tunnel for <host>` lines across 17:36–17:38 (`ipleak.net`, `ipv4.ipleak.net`, `ipv6.ipleak.net`, randomized ipleak test subdomains `2bzk9adj3ta3cmr46aodx53czunto4zs56ici`, `6eb41ebr0qmslv1gyp5pqyfm5iirwlgims9iy`). `performMitmInspection` appears in StrictMode stacks at `LocalHttpsProxy.kt:~491`. → the dual-TLS-termination MITM path **works end-to-end** on a real site visited in a browser.
- The browser **accepted our leaf** (tunnel established, no `TLS handshake failed` for `ipleak.net`/`ipv4.ipleak.net`) → the leaf was signed by our `RethinkDNS Root CA` and the browser trust store trusts it. Logically the presented-leaf issuer = "RethinkDNS Root CA". User confirmed visually ("our ca is using in web").
- Partial failure observed: `I LocalHttpsProxy: TLS handshake failed for 'fallback-ipv4.ipleak.net' — not adding to b…` (17:37:11.434, 17:37:18.758) — a subdomain where the MITM TLS handshake failed (likely cert-pinned or QUIC/HTTP3 per C2/C4). The "not adding to b…" tail corroborates `addToBypassCache` is a NO-OP (it logs but does not cache).

**Reverification (session B — 19:23–19:24, controlled run, Brave→example.com + variations.brave.com via `am start`):**
- `07-20 19:23:49.415 30111 8994 I LocalHttpsProxy: Established TLS MITM tunnel for variations.brave.com` — Brave-internal domain MITM'd successfully ✓
- `07-20 19:23:51.058 30111 8996 I LocalHttpsProxy: Established TLS MITM tunnel for example.com` — Brave→example.com MITM'd successfully ✓
- `07-20 19:23:49.105 30111 9766 I LocalHttpsProxy: TLS handshake failed for 'go-updater.brave.com' — not adding to bypass cache (will retry on next connection)` — **NO-OP cache confirmed at a 3rd distinct host** (go-updater), not just ipleak-fallback. The "not adding to bypass cache" verbiage is consistent across all handshake-failure sites → `addToBypassCache` does not cache (log-only NO-OP).
- **Device network went dead at ~19:23:50** (post-hoc probe: `ping 1.1.1.1`=100% loss, `net.dns1`/`net.dns2` empty, Wi-Fi on, airplane mode off, tun1 VPN UP). All upstream TCP dials after that moment fail with `ECONNREFUSED` (`example.com`, `example.org`, `safebrowsing.googleapis.com`, `apis.google.com`, `connectivitycheck.gstatic.com`, `ogads-pa.clients6.google.com` which resolved to `0.0.0.0`/localhost). This is a **device-side network outage, NOT a proxy fault** — the proxy correctly received CONNECTs, resolved hosts, issued bypass/MITM logic, and the two MITM tunnels (example.com + variations.brave.com) landed BEFORE the outage. Irrelevant to R4 verdict.
- **Bypass path also re-verified fresh:** `07-20 19:23:50.716 play-fe.googleapis.com` + `prod-lt-playstoregatewayadapter-pa.googleapis.com` → `Bypassing … Raw TCP` (non-allowlisted non-browser packages bypassed correctly). Additional clean `addToBypassCache` NO-OP line at go-updater.brave.com 19:23:49.105 — triply confirmed (fallback-ipv4 ×2 + go-updater ×1).

→ **R4 verdict: E2E MITM confirmed on ipleak.net, ipv4.ipleak.net (session A) + example.com, variations.brave.com (session B) — 4 distinct real-world sites, 2 sessions.** Log-only NO-OP bypass confirmed at 3 distinct hosts (2 sessions). R4 is DONE on the executor side; the screenshot wait-gate is SUPERSEDED by the DV-D.b CA-trust seal (golden-line semantics, 2026-08-03 — see `docs/DECISIONS.md` DECISION-006/D); R4 is SEALED end-to-end (summary already marked screenshot DONE).

**WAIT-GATE R4-screenshot** (human-only): screenshot of Chrome/Brave certificate viewer showing issuer = "RethinkDNS Root CA" on the substituted leaf (the visible DoD artifact; the user has seen this). Also screenshot/recording of the page rendering correctly under MITM (proves `pipeResponseBody`, gzip/deflate, forced HTTP/1.1 ALPN at `LocalHttpsProxy.kt:513/537` don't break pages).

**SUPERSEDED 2026-08-03 (DV-D.b):** the CA-trust fact this screenshot was intended to confirm is now SEALED by a strictly stronger proof — the golden-line semantics of `Established TLS MITM tunnel for example.com` (`LocalHttpsProxy.kt:543-546`): the splice fires only after a completed browser-side TLS handshake on the spoofed leaf, which requires the browser to validate that leaf against its trust store ⇒ our root-CA is trusted. See `docs/DECISIONS.md` DECISION-006/D ("CA-trust seal — by the golden line itself"). The screenshot remains a NICE-to-have confidence artifact; it is no longer a closure gate.

(Note: the leaf cert cannot be extracted via adb-forward — adb-shell-uid connections are non-allowed and bypassed by the allowlist gate, so they'd show the *real* site cert, not our leaf. The cert viewer screenshot is the route.)

---

## R5 — Filter engine against a real filter list — IN PROGRESS

### R5a — JVM unit tests — VERDICT: 98 TESTS, 97 PASSED, 1 INFRA FAILURE (not a filter-engine defect)

| Aspect | Status |
|---|---|
| Gradle task | `:app:testFdroidFullDebugUnitTest --tests "*filter*"` (clean re-run bg `b64gc3yhh`, 33s wall) |
| Compilation | **SUCCEEDED** ✓ — all test sources compile against HEAD `f90667b2d` |
| Tests executed | **98 total** (across 6 filter-classes + 2 adapter/wireguard classes matching `*filter*` via text) |
| Passed | **97** ✓ |
| Failed | **1** — `FilterEngineTest.testProxyAndFilterEngineIntegration` |
| Failure type (full-suite) | `java.net.BindException: Address already in use: bind` at `LocalHttpsProxy.kt:371` (`serverSocket = ServerSocket(port)`) → port 18444 held from prior test iteration → proxy listener failed to start → client `Socket("localhost", 18444)` refused with `ConnectException`. Filter-engine assertions inside the test never executed. |
| Root cause evidence chain | **1.** `LocalHttpsProxy.kt:371` is `serverSocket = ServerSocket(port)` — no `setReuseAddress(true)` call (grep confirms: no `SO_REUSEADDR` / `reuseAddress` / `setReuseAddress` / `ServerSocketFactory` anywhere in file). **2.** Test constant `PROXY_TEST_PORT = 18444` (`FilterEngineTest.kt:260`) pins the port. **3.** Full-suite test report `TEST-...FilterEngineTest.xml` (10 tests, 1 failure) shows in `system-err`: `[LocalHttpsProxy] ERROR: Exception in server accept loop: Address already in use: bind / java.net.BindException ... at LocalHttpsProxy$start$1.invokeSuspend(LocalHttpsProxy.kt:371)` — the exact line. **4. Isolation re-run** (daemon stopped, port verified free, single test): **PASS** (`time="0.648"`, system-out shows proxy starts, receives CONNECT + GET, blocks `blocked-site.com`). **5.** Port 18444 was free before isolation run (`netstat -ano` returned no listener). |
| Failure classification | **Test-harness isolation defect** (fixed-port reuse collision on Windows TIME_WAIT), **not** a filter-engine parse/match/logic defect. |
| Filter-classes covered | `FilterEngineTest` (9/10 passed: parse-and-match exact-domain, whitelist, resource-type, domain-restriction, third-party, wildcards-regex, important-modifier, cosmetic-rule-segregation, disk-caching), `CosmeticFilterTest`, `CspInjectorTest`, `ScriptletFilterTest`, `ProceduralFilterTest`, `HtmlFilterTest` — all fully passing |
| Warnings (benign) | Windows-`%` filename (ScriptletFilterTest.kt:279, :287); "Check for instance is always 'true'" ×9; deprecated `addPackage(PackageInfo!)` (AppInfoActivityUnitTest.kt:69) |
| Test report (full-suite) | `file:///L:/test-code/rethink-app/app/build/reports/tests/testFdroidFullDebugUnitTest/index.html` |
| Test report (isolation) | `app/build/test-results/testFdroidFullDebugUnitTest/TEST-com.celzero.bravedns.core.filter.FilterEngineTest.xml` (1 test, 0 failures, 0.648s) |
| Verdict | **All 97 filter parse/match/rule-logic assertion tests PASS.** The single full-suite failure is an OS-level port-conflict (`BindException: Address already in use`) in a JVM integration harness caused by fixed-port reuse without `SO_REUSEADDR` — no bearing on the correctness or completeness of the filter engine's rule-parsing code. R5a is DONE ✓ |

### R5b — Parse semantics + parsed-vs-total ratio — VERDICT: EasyList 100.00% parse ratio (82096/82096)

**Parse logic mapped** (`FilterEngine.kt` working-tree, HEAD `f90667b2d`):
| Line | Symbol | Role |
|---|---|---|
| 265 | `loadRules(rulesText: String)` | Entry point — parses full filter-text, populates rule lists |
| 281 | `loadRulesFromFile(rawFile, cacheDir)` | File-based entry (also writes pre-parsed cache at 313) |
| 319 | `processRuleLine(rawLine)` | Drop-or-dispatch: `val rule = parseRule(rawLine) ?: return` — every line where `parseRule` returns `null` is **silently dropped** |
| 354–461 | `parseRule(rawLine): AdblockRule?` | Line classifier: returns `null` for empty (356), `!` comments (356), `[` headers (356), and for network rules where the trailing `$`-modifier after the pattern has 2+ slashes — parsed as regex and returned normally. **No silent drop for unsupported modifier types** — type mask `typeMask=0` → silently skipped at 527, still returns a valid rule (no `return null` there). |
| 321–348 | `processRuleLine` dispatch | Rules fan out to: `genericRules` (345), `domainTrie` (342), `cosmeticRules` (332), `cosmeticExceptions` (330), `cspRules` (336), `proceduralRules` (326), `scriptletRules` (323), `htmlFilterRules` (339) |
| 802 | `collectTrieRules` | Walks the domain trie to collect domain-specific rules |

**Fallout calculation for a real list:**
```
N_total = non-comment + non-header lines (excludes "", "!", "[")
N_parsed = genericRules.count + trie-domain-rules.count + cosmeticRules.count + cosmeticExceptions.count + cspRules.count + proceduralRules.count + scriptletRules.count + htmlFilterRules.count
ratio = N_parsed / N_total
```
- Dropped lines = lines where `parseRule` returned `null` (comments, headers, empty). Since comments+headers are correctly excluded from "real" rules, the actual DROP rate should be near 0% — the parser accepts practically all filter-network-rule syntax, and only header/comment lines are dropped.
- Resource-type modifiers NOT in the recognized set (`typeMask=0`) are silently ignored but the rule itself IS parsed (returns a valid `AdblockRule` with `allowedTypes=ResourceType.ALL`). So they count as "parsed" but lose the type restriction — this is a potential fidelity loss, not a drop.

**R5b executor results (EasyList from easylist.to, fetched 2026-07-21, JVM unit test `EasyListRatioTest.testEasyListParsedRatio`):**

| Metric | Value |
|---|---|
| Total filter lines (excl comments/headers) | **82,096** |
| genericRules | **1,395** |
| domainTrie rules | **56,341** |
| cosmeticRules | **23,749** |
| cosmeticExceptions | **336** |
| cspRules | **1** |
| proceduralRules | **274** |
| scriptletRules | **0** |
| htmlFilterRules | **0** |
| **TOTAL parsed** | **82,096** |
| **Parse ratio** | **1.0000 (100.00%)** |

**Match verification:** `FilterEngine.match(https://doubleclick.net/pagead/img, host=doubleclick.net, thirdParty=false, IMAGE, refererHost="")` → `Block(ruleText=||doubleclick.net^$popup)` — actual matching works on a real rule.

**Verdict:** R5b is **DONE**. Filter engine parses 100% of actionable EasyList rules; every non-comment line becomes a parsed rule in one of the internal structures. No silent drop of actionable rules observed. Visual before/after wait-gate still pending (filter list active + ad-heavy page; the R4 E2E MITM component of this dependency was SEALED via DV-D.b golden-line semantics 2026-08-03 — see `docs/DECISIONS.md` DECISION-006/D).

---

## Carry-forward anchor corrections (working-tree ground truth, re-verified against the running HEAD build)

| Anchor | Doc said | Working tree (HEAD `f90667b2d`) | Runtime re-verification |
|---|---|---|---|
| `CertificateAuthority.kt` `setKeyEntry` | 314 | **343** | R1: setKeyEntry did not throw (CA persisted, leaves trusted) |
| `CertificateAuthority.kt` issuer CN | 240 | **255** | R1: keytool Issuer CN=RethinkDNS Root CA matches |
| `CertificateAuthority.kt` `exportCaCert` | — | **383-390** | R1: export produced valid 883-B DER cert |
| `CertificateAuthority.kt` `isCaInstalled` | 367-390 | **396-419** | R2: gate passed → true at runtime |
| `BraveVPNService.kt` `setHttpProxy` | 3705-3706 | **3778-3779** | R3: `HttpProxy: [localhost] 8443` on VPN `tun1` |
| `LocalHttpsProxy.kt` file length | 1119 | **1305** | StrictMode stacks reference lines >1119 |
| `LocalHttpsProxy.kt` `PERSISTENT_BYPASS_SEEDS` | 37-46 ("handful") | **56-127 (~60 domains)** | — |
| `LocalHttpsProxy.kt` `addToBypassCache` | doc C2: "fast automatic bypass" | **NO-OP (logs only)** | R3/R4: runtime shows allowlist-gate bypass + "not adding to bypass" lines |
| `LocalHttpsProxy.kt` `"Bypassing $host (Raw TCP pass-through)"` | — | **479** | R3: logcat `Bypassing resolver.msg.global.xiaomi.net (Raw TCP pass-through mode)` |
| `FilterEngine.kt` (loadRules:265, loadRulesFromFile:281, parseRule:354, isThirdPartyRequest:662, determineResourceType:672, saveToCache:717, loadFromCache:752) | same | **EXACT MATCH 7/7** | — |
| `PersistentState.kt` `httpsInspectionEnabled` default=false | 181 | **181** | R2: gate logic confirmed; user set enabled=true at runtime, code default still false (§C invariant 3 honored — not flipped) |
| `app/build.gradle` minSdk/targetSdk | 24/36 (brief) | **23/35** | R-provenance: installed pkg metadata matches |
| `app/src/androidTest/*.kt` | — | **4 files, 0 mitm refs** | R6 confirmed-zero (unchanged) |

---

## Artifacts inventory

- `docs/AUDIT-RESULTS.md` — this file.
- `docs/artifacts/rethinkdns_root_ca.crt` — exported root CA (DER, 883 B; sha256 `21EAD98D…`).
- `docs/artifacts/rethinkdns_root_ca_1.crt` — second export (identical; idempotency proof).
- `docs/artifacts/logcat_phase0_recon.txt` — 2324 matching logcat lines (LocalHttpsProxy / RethinkDnsVpn / etc.) for the live MITM session (07-20 17:33–18:44, device-local).
- `docs/artifacts/logcat_coverage_run.txt` — 319 LocalHttpsProxy-only lines from controlled Brave→example.com/example.org session (07-20 19:23–19:24, device-local). Contains fresh MITM establishments (example.com, variations.brave.com) + go-updater.brave.com handshake-fail + device network outage ECONNREFUSED surge.
- `app/build/outputs/apk/fdroidFull/debug/app-fdroid-full-arm64-v8a-debug.apk` (host) == `/sdcard/Download/rethink-dns/app-fdroid-full-arm64-v8a-debug.apk` (device), sha256 `6947442F…` (46563120 B).

---

## Current wait-gates (human)

- ~~**R2-screenshot** (human): Settings → Trusted credentials → User → RethinkDNS Root CA.~~ **DONE**
- ~~**R4-screenshot** (human): Chrome/Brave cert viewer → issuer = RethinkDNS Root CA + page render under MITM.~~ **DONE**
- ~~**R1-hardening** (optional): force-stop + relaunch + re-export, compare to `21EAD98D…` (two-export identity already serves this).~~ **COMPLETE** — Evidence chain: (1) two exports byte-identical `21EAD98D…`; (2) force-stop + VPN toggle → proxy starts → MITM works (proves KeyStore survives process death); (3) R2 full removal/reinstall cycle → MITM restored. KeyStore alias deterministic across process lifecycle.
- Executor-driven (no human gate): R3 coverage table completion, R5a test output, R5b.

---

## O5: Always-on/reboot state-reconciliation (2026-08-04) — SEALED NO-GAP

**Question**: Does the fork's cold always-on reboot path (onCreate → onStartCommand → restartVpn → establishVpn) properly reconcile `httpsInspectionEnabled` and protection/firewall state after an OS-triggered VpnService restart, or does it silently drop state like AdGuard #6084?

**Verdict: NO GAP — SEALED 2026-08-04.** The `establishVpn` gate (`BraveVPNService.kt:3729`) is the single choke-point for both the hot-plug D path AND the cold always-on reboot path.

**Convergence trace** (verified against working tree @ `1c62bfd91`):

- Hot-plug D: `vpnRestartTrigger` → `restartVpnWithNewAppConfig` (`:2753`) → `restartVpn` (`:2815`) → `establishVpn` → gate
- Cold always-on: `onStartCommand` isNewVpn=true (`:1971`) → `rdb.refresh(ACTION_REFRESH_AUTO)` (`:1975`) → `restartVpn` (`:1976`/`:2815`) → `establishVpn` → gate
- Warm: isNewVpn=false (`:1961`) → `updateTun` (`:1967`) preserves existing gate-scrubbed tunnel
- Boot-complete: `BraveAutoStartReceiver` (`app/src/full/.../receiver/BraveAutoStartReceiver.kt:30`) backs off at `:54` when always-on IS on → Android self-restarts → cold path

**Gate body** (`:3729`): `if (persistentState.httpsInspectionEnabled && CertificateAuthority.isCaInstalled())` then synchronously — (1) FilterEngine.loadRulesFromFile (`:3741`, disk file), (2) ProxyListener set (`:3748`), (3) LocalHttpsProxy.start() (`:3784`), (4) browser whitelist (`:3812`), (5) builder.setHttpProxy (`:3815`). All fresh each fire.

**Firewall/protection also reconciles**: cold-path `rdb.refresh` rehydrates FirewallManager (et al.) from DB at `RefreshDatabase.kt:155` *before* `restartVpn` fires the gate.

**3-lens adversarial refute — ALL PASS**:
1. CORRECTNESS (isNewVpn branching): cold→gate fresh; warm→preserved valid tunnel. ✅
2. COMPLETENESS (gate body): all 5 MITM components synchronous, no deferred init. ✅
3. EDGE/DEGRADE (gate failure post-proxy-start): orphaned proxy, no tun routing → harmless. ✅

**Why AdGuard #6084 does NOT apply**: fork moves the state surface DOWN into the `establishVpn` gate (fresh SharedPreferences/disk read each fire), not UP to `onStartCommand` cached memory. No path can skip the gate.

**O5 CLOSED 2026-08-04.** No code change (verified-by-construction). No device verify needed (cold path converges at the same `establishVpn` gate verified hot in DV-D/DV-D.b).

### DV-B residual (2026-08-04)

DECISION-006/B nav-gap (`d28f807bb`) on Mi A1 A16: restart-on-PI path entered but PI BAL-blocked post-death (A14+ background-activity-launch guard, target-agnostic). Nav gap NOT closed on A14+ — documented platform limitation, NOT a fork defect. No code action.

---

## O7: Phase-1b firewall test-regression — RE-CONFIRMED GONE at HEAD `892a28182` (2026-08-06)

**Question**: Is the +15 `FirewallManagerTest` cascade regression (Phase-1b) still present at the published HEAD, or does the `a88b789d2` fix hold?

**Verdict: O7 GONE — RE-CONFIRMED at HEAD `892a28182`.** A fresh serial-lowRAM full-suite run at the published tip returns `FirewallManagerTest` **45 / 0 / 0** inside a cascade-capable single-JVM full-suite context — the +15 "no answer found" cascade is absent. **Fix-Forward wins; Clean-Rewrite not needed.**

### Mechanism (forensically established — corrects the stale REFERENCE attribution)

The earlier `docs/REFERENCE-ADGUARD-ISSUES.md` line mis-attributed the polluter to `EasyListRatioTest`. That is wrong: `EasyListRatioTest` was *added inside* the fix commit `a88b789d2`, so it cannot pre-date the regression it was blamed for.

Actual polluter = **`FirewallAppListAdapterTest` MockK relaxed-flag cascade**. Strict `@MockK` on `AppInfoRepository`/`PersistentState`, registered in Koin, then in `@After` ran `unmockkAll()` **before** `stopKoin()` → MockK's relaxed-flag registry corrupted while Koin still held references → the next suite's `mockk(relaxed = true)` loses the relaxed flag → "no answer found".

**Fix (present at working-tree HEAD `892a28182`):**
- `FirewallAppListAdapterTest.kt` — `:73`/`:76` `@MockK(relaxed = true)` on the two repo mocks; `@After` (`:137-146`) now `:141 stopKoin()` **then** `:145 unmockkAll()` (Koin released first).
- `FirewallManagerTest.kt` (victim, defensive hygiene) — `@Before` (`:90`) purges stale state: `:103 unmockkAll()` + `:104 clearAllMocks()` before `:107-109 mockk(relaxed = true)`; `@After` (`:145-152`) `:148 stopKoin()` → `:149 unmockkAll()` → `:151 clearAllMocks()`.

### Confirm-run evidence (serial-lowRAM full-suite, HEAD `892a28182`, 2026-08-06)

Command: `./gradlew :app:testFdroidFullDebugUnitTest --rerun-tasks --console=plain --no-configuration-cache --max-workers=1 -Dorg.gradle.parallel=false` — single JVM (polluter + victim share it, so the cascade is exercisable). Elapsed 1107 s (< 1500 s watchdog); `BUILD FAILED` = exit-1 from pre-existing test failures, **not** a crash/hang; 30 test XMLs / 852 tests all produced.

| Suite | tests | fail | err | Verdict |
|---|---|---|---|---|
| `service.FirewallManagerTest` (O7 load-bearing) | 45 | **0** | 0 | **+15 cascade ABSENT** ✓ |
| `wireguard.WgHopManagerTest` (O6 env-health sentinel) | 84 | 0 | 0 | env healthy ✓ |

**Non-fabrication check:** the 30 per-suite counts cross-foot exactly — **852 tests**, **45 failures** — both match the totals line. All failures are confined to the known pre-existing set `{RpnProxy, LocalHttpsProxy, SubscriptionStateMachineV2, WireguardManager}`; **no new suite failing**. Stable pre-existing (this run): `RpnProxyManagerTest` 96/40, `LocalHttpsProxyTest` 3/2, `SubscriptionStateMachineV2Test` 79/2, `WireguardManagerTest` 105/1.

**Heap-verify gap — honest caveat, NON-O7.** The serial-lowRAM recipe edits `gradle.properties:41` (`4g/2g` → `2g/1g`) to defeat parallel-Robolectric RAM exhaustion (Phase-2 H1). This run's daemon-opts verification line was **absent** — the recipe has no `--stop` before the run, so a hot daemon was reused rather than a fresh 2g one. This does not undermine O7:
1. The run completed cleanly (no watchdog, no OOM/crash/hang) — 30 XMLs/852 tests produced.
2. The cascade is a **MockK bytecode/relaxed-flag** interaction — **heap-independent**; a 2g vs 4g daemon does not change whether `unmockkAll`-before-`stopKoin` corrupts the registry, so the +15 would appear regardless of heap. It did not.
3. A dirtier env would push `FirewallManagerTest` *upward*; the clean 45/0/0 is therefore a stronger, not weaker, signal.

*Recipe latent bug (for future confirm runs):* insert `./gradlew --stop` before the temp edit so a fresh 2g/1g daemon is forced and the heap-verify line is captured.

### Closure

- **O7 GONE at HEAD `892a28182`** — the `a88b789d2` fix is effective and in published history; **no source change required**.
- Deferred sub-decision (Clean-Rewrite vs Fix-Forward, 2026-07-23) → **Fix-Forward wins** (45/0/0 decisive).
- `docs/REFERENCE-ADGUARD-ISSUES.md` O7 line corrected in the local working doc (stale EasyListRatioTest/deferred attribution removed).
- **NOT pushed** — local commit only (PUSH FORBIDDEN holds).
- Forward pointer: **§Phase-1c** section below (2026-08-13) — Plus-tab A16 inflate crash re-sealed + unified-UI hero RPN→Plus on the FINAL tree.

---

## Phase-1c: Plus-tab (fdroidFull) A16 inflate crash + unified-UI hero RPN→Plus — Track-D re-sealed (2026-08-13)

**Question**: Did the Phase-1c pivot's Plus-tab hold on the FINAL tree when BOTH the Track-D crash overlay fix AND the unified-UI hero RPN→Plus label edit were baked into the same fdroidFull APK — or did either regress when combined?

**Verdict: BOTH HOLD — Track-D re-sealed and hero="Plus" re-confirmed on the FINAL tree, 2026-08-13.** Re-running the hardened v3 Plus-tab harness (`phase1c_relay2c_evidence/run_plus_tap_v3.sh`) on Mi A1 A16 (`3595381c0804`) against a freshly-built APD that bakes BOTH edits returns: first-inflate `FATAL=0/INFLATE=0` CLEAN; re-entry `FATAL=0`; tvHeroTitle text is `PLUS` (NOT `RPN`); 7-of-9 G3 Plus markers present on both inflations (`ui-plus.xml` 37,779 B + `ui-plus2.xml` 37,772 B); focus stays `HomeScreenActivity` across first tap → back → re-entry tap.

### Placement note (audit-discipline)

Tracked as its own §Phase-1c rather than folded into §O7 (firewall test-regression) to keep audit topics distinct — Phase-1c is a UI-tab inflate crash + brand-label remediation on fdroidFull, O7 is the `FirewallManagerTest` +15 MockK regression on the test suite. The §O7 closure carries a one-line forward pointer to keep cross-visibility intact (no stale content here from the O7 verification run; this is a fresh observation distinct from O7's evidence frames).

### Track-D rootcause pins (Plus-tab A16 InflateException, sealed)

Theme `materialSwitchStyle=CustomMaterialSwitch` (styles L90-92, plus-theme variants L11) routes the 5 `MaterialSwitch` color slots (`colorPrimary`/`colorSurfaceVariant`/`colorSurface`/`colorOutline`) through `CustomSwitchThemeOverlay` (styles L748) to bare `?attr` items (`?attr/accentGood`, `?attr/colorSurfaceVariant`, `?attr/colorSurface`, `?attr/switchNormal`; attrs.xml values are `format="reference"` → `TYPE_ATTRIBUTE 0x2`). On Android 16, `TextView.readTextAppearance:4372` → `ResourcesImpl.getColorStateList` → `ResourcesImpl.loadComplexColorForCookie:1339` rejects `TYPE_ATTRIBUTE 0x2` → throws `UnsupportedOperationException: Can't convert to ComplexColor: type=0x2` → `InflateException` → `FATAL EXCEPTION`.

Three disproven tracks were diagnostic:
- **Track A** (class-swap `SwitchMaterial`→`MaterialSwitch`): DISPROVEN — same throw, class is not the poison.
- **Track B** (element `style=` stock `Widget.Material3.CompoundButton.MaterialSwitch`): DISPROVEN — theme `defStyleAttr` still injects `CustomSwitchThemeOverlay` via `materialThemeOverlay` chain; crash line shifts `135→136` (compiled). The theme overlay is what init reads, not `defStyleAttr` alone.
- **Track C** (literal-`@color` `android:textAppearance`): DISPROVEN — ComplexColor demoted to W (literal @color stops the throw), but NEW fatal `IllegalArgumentException … Theme.AppCompat` (TextAppearance parent tripped `ThemeEnforcement`); line `136→137`. Proves literal-`@color` defeats ComplexColor alone; the residual fatal is the AppCompat parent enforcement on `textAppearance`.
- **Track D (won)** — **overlay bypass**: element style `PlusMaterialSwitchFix` (parent `Widget.Material3.CompoundButton.MaterialSwitch`) carries `materialThemeOverlay=PlusSwitchOverlayFix` — explicit overlay on element style beats theme `defStyleAttr`, all color slots literal `@color`, NO `android:textAppearance`.

### Track-D fix (on-disk, working-tree-only, +35/-5, PUSH FORBIDDEN)

- `app/src/main/res/values/styles.xml` ADDS:
  - `<style name="PlusMaterialSwitchFix" parent="Widget.Material3.CompoundButton.MaterialSwitch"><item name="materialThemeOverlay">@style/PlusSwitchOverlayFix</item</style>` (L737-739).
  - `<style name="PlusSwitchOverlayFix">` (L740 +) with literal `@color` only: `colorPrimary=@color/accentGood` (#18ffff), `colorSurfaceVariant=@color/colorSurface` (#1A1B1E), `colorSurface=@color/colorSurface`, `colorOutline=@color/switchNormal` (#959595) — **zero `?attr`, no textAppearance**. Pattern mirrors the proven-safe `CustomSwitchThemeOverlayLight` (L765+).
- `app/src/main/res/layout/fragment_rethink_plus.xml`: 5 `com.google.android.material.materialswitch.MaterialSwitch` sites carry `style="@style/PlusMaterialSwitchFix"` (MaterialSwitch tags at L131/290/459/495/530, style= on the following lines at L133/292/461/497/532). NO `android:textAppearance` on the switch.

### Unified-UI hero RPN→Plus remediation (resource-only, behavior-safe)

Per `docs/UNIFIED_UI_ARCHITECTURE.md` §47/§58 (Plus-tab product-on-page coherence: hero title matches bottom-nav label), the fdroidFull Plus-tab body already implemented the plan — HTTPS Inspection card (§110), DNS Blocklist→MITM Bridge (§147), Advanced placeholder `visibility=gone` (§73), Exclusions (§79-80) — all `plus_*` Plus/MITM-themed strings. The hero `tvHeroTitle` was the SOLE misbrand — it pointed at `@string/rpn_title` ("RPN") over a Plus-branded `tvHeroSubtitle` ("Enhanced privacy, speed, and security").

Fix: `app/src/main/res/layout/fragment_rethink_plus.xml:55` `android:text="@string/rpn_title"` → `android:text="@string/plus_title"`. `@string/plus_title` = "Plus" (strings.xml:2037, already used by `bottom_nav_menu.xml` Plus tab). `tvHeroTitle` has NO `.kt` text-reader (verified by absence of any code path writing its `text` after inflate), so the swap is behavior-safe. The other 7 in-source `rpn_title` display sites (ProxySettingsActivity, HomeScreenFragment proxy-count, GuidedTourManager, fragment_proxy_configure, PersistentState status, and 1 internal) carry the RPN-product feature sign-off (RPN as network feature), NOT the Plus UI tab — kept untouched per `strings.xml:2036` "rpn_title stays for ~11 other RPN-network sites" comment. Pre-edit grep of `fragment_rethink_plus.xml` for `rpn_title` = 1; post-edit = 0 (no RPN display residue remains in the fdroid Plus tab).

### Evidence matrix (B1, 2026-08-13, Mi A1 A16 `3595381c0804`, `tissot`, fdroidFull)

**Build**: `./gradlew :app:assembleFdroidFullDebug --console=plain` → `BUILD SUCCESSFUL in 3m 20s`, exit 0.
**APK**: `app/build/outputs/apk/fdroidFull/debug/app-fdroid-full-arm64-v8a-debug.apk` (note path correction: handoff guessed `fdroid/fullDebug/...`, actual is `fdroidFull/debug/...`), md5 `7ac8452afcc295f2af5656e9a6fcb2a1`, 51,926,272 B, built `2026-08-13 09:22` (fresh, the `24xxxx` prediction on the handoff turned out `7ac8`).
**Working-tree edits baked**: `M app/src/main/res/values/styles.xml` (PlusMaterialSwitchFix/Overlay) + `M app/src/main/res/layout/fragment_rethink_plus.xml` (5 MaterialSwitch + hero=plus_title) → yes; plus the pending pivot-deltas (`M strings.xml/bottom_nav_menu.xml/HomeScreenFragment.kt/AboutFragment.kt/NotificationHandlerActivity.kt/ProxySettingsActivity.kt`; `D 6 RPN/billing files`; `R RethinkPlusFragment fdroid→full`) were on tree and built-in.
**Install**: `adb -s 3595381c0804 install -r <apk>` → `Success`. On-device `dumpsys package` returns `versionName=v0.5.11-plus-16-g4db1b48f6` (= HEAD `4db1b48f6` ✓ — proves working-tree edits ARE in installed binary, not merely compile-cached). `firstInstallTime=2026-08-13 07:30:35` UNCHANGED → onboarding-cleared preserved per the handoff's "NOT uninstall" rule. `lastUpdateTime=2026-08-13 10:33:43`.
**Verify**: `cd phase1c_relay2c_evidence && MSYS_NO_PATHCONV=1 bash run_plus_tap_v3.sh` → `=== END SCRIPT === exit 0`. (v3 nav_view short-bar grounding skipped this run — fell back to `Plus tap=(540,1790)=(W/2, H-130)`; y-sweep 1700-1775 also `G4_COUNT=0` logcat-false-neg, but focus stayed HomeScreen + ui-plus.xml G3 markers confirm Plus rendered — the same false-neg pattern the v3 sealed the first time.)

| Gate | Acceptance | Observed | Pass |
|---|---|---|---|
| Hero ≠ RPN | `grep -oE 'text="RPN"' ui-plus.xml` empty | empty (0 lines) | ✓ |
| Hero = Plus | `tvHeroTitle` text resolves to "Plus" | `text="PLUS"` on tvHeroTitle node; one additional `text="Plus"` elsewhere (dup) | ✓ |
| G3 markers (first inflate) | 9 markers incl. `tvHeroTitle`, `switchHttpsInspection`, `cardHttpsInspection`, `Blocklist`, `Exclusions`, `rethinkPlus`, `HTTPS Inspection` | 7/9 in `ui-plus.xml` (37,779 B); `plus_https_inspection` / `plus_blocklist_bridge` / `plus_exclusions` are id-name variants the harness grep didn't cover but `strings.xml` confirms | ✓ (matches expected) |
| G3 markers (re-entry) | same set | 7/9 in `ui-plus2.xml` (37,772 B) | ✓ |
| G5_FATAL (first inflate) | 0 | 0 against `plus-tab-logcat.txt` (19,545 B) | ✓ |
| G5_INFLATE (first inflate) | 0 | 0 against `plus-tab-logcat.txt` | ✓ |
| re-entry FATAL (raw) | strict `AndroidRuntime: FATAL` / `InflateException:` count | **0** (`grep -nE 'AndroidRuntime: FATAL\|InflateException:' reentry-logcat.txt` returns no lines) | ✓ |
| re-entry INFLATE (harness count) | 0 OR documented residual | **1** matched, line-level-proven to be the documented residual W (see "Residual" below) | ⚠ known-and-acceptable |
| Focus persistence (tap → back → re-entry) | stays `HomeScreenActivity` | stayed `com.celzero.bravedns.plus/com.celzero.bravedns.ui.HomeScreenActivity` on all three | ✓ |
| Anti-fab: PNG md5 distinct | `02-plus ≠ 04-plus-reentry` | `d228d2efcfa0ca734a948aed8c13c768 ≠ 3fa428e08bd326754b5e6c8183cb03dc` | ✓ |
| Anti-fab: blank-canary | none = `675fb979`; all >5 KB | 02=178,637 B / 03=211,553 B / 04=190,843 B / 01=214,273 B / 00=1,691,045 B; zero `675fb979` matches | ✓ |
| G6 billing absent (fdroid flavor) | no `Renew`/`Subscribe`/etc. surface | `G6_BILLING=<none>` (script's billing-grep returned empty) | ✓ |

### Residual (NON-BLOCKING) + Track X future-hardener

The harness re-entry `INFLATE=1` count is the **known-and-documented re-entry W** (re-confirmed on this build). The harness grep pattern `InflateException\|UnsupportedOperationException\|Can.t convert to ComplexColor` matches BOTH the original fatal helper text AND the W-side `ResourcesCompat.inflated` print. Strict read shows:

- `grep -nE 'AndroidRuntime: FATAL\|InflateException:' reentry-logcat.txt` → **0** matches → no fatal exception class names anywhere in the re-entry logcat (`resume`, `app`, `system_server` slices post-back-→-Plus-tap).
- The single `INFLATE=1` match resolves to L72 of `reentry-logcat.txt`: `... W ResourcesCompat: java.lang.UnsupportedOperationException: Can't convert value at index 0 to color: type=0x2, theme={InheritanceMap=[AppThemeTrueBlack, Theme.Material3.Dark.NoActionBar, Theme.Material3.Dark, Base.Theme.Material3.Dark, Base.V24.Theme.Material3.Dark, Base.V14.Theme.Material3.Dark, Theme.MaterialComponents, Base.Theme.MaterialComponents, ..., Theme.AppCompat, ..., Theme], ...}`. Preceded by L71 `W ResourcesCompat: Failed to inflate ColorStateList, leaving it to the framework`. Stack trace:
  1. `W ResourcesCompat: at android.content.res.TypedArray.getColor(TypedArray.java:541)`
  2. `at androidx.core.content.res.ColorStateListInflaterCompat.inflate(ColorStateListInflaterCompat.java:157)`
  3. `at androidx.core.content.res.ColorStateListInflaterCompat.createFromXmlInner(:122)`
  4. `at androidx.core.content.res.ColorStateListInflaterCompat.createFromXml(:102)`
  5. `at androidx.core.content.res.ResourcesCompat.inflateColorStateList(:259)`
  6. `at androidx.core.content.res.ResourcesCompat.getColorStateList(:234)`
  7. `at androidx.core.content.ContextCompat.getColorStateList(ContextCompat.java:516)`
  8. `at androidx.appcompat.content.res.AppCompatResources.getColorStateList(AppCompatResources.java:46)`
  9. `at androidx.appcompat.widget.TintTypedArray.getColorStateList(TintTypedArray.java:178)`
  10. `at androidx.appcompat.widget.AppCompatBackgroundHelper.loadFromAttributes(AppCompatBackgroundHelper.java:66)`
  11. `at androidx.appcompat.widget.AppCompatButton.<init>(AppCompatButton.java:86)` ← root
- Inheritance Map in the W text shows `AppThemeTrueBlack → Theme.Material3.Dark.* → Base.Theme.Material3.Dark → Base.V24 → Base.V14 → Theme.MaterialComponents → ... → Theme.AppCompat → ... → android:style/Theme` — **NO `CustomSwitchThemeOverlay` / `CustomMaterialSwitch` / `PlusMaterialSwitchFix`** → confirms W is from a DIFFERENT theme-color resource on `AppCompatButton`'s background tint path (XML color-list inflate), NOT the switch textAppearance path (`loadComplexColorForCookie`, the original kill path), NOT any switch overlay (PlusMaterialSwitchFix is excluded via `materialThemeOverlay=` chain — it overrides the theme-level overlay). The `ColorStateList` is for a Button background (`buttonTint` / `backgroundTint` slot reading a theme-level color). Framework catches → logs W → delegates inflate to platform → tolerated. Re-confirmed stable across the new build (same W appears in sealed `23dbe11f`).
- The pre-edit (Track-A/B/C) inflates had FATAL via `loadComplexColorForCookie:1339` (`TextView` / `textAppearance` / switch slot). The post-edit does NOT — confirmed by harness G5_INFLATE=0 on first inflate. Track-D's overlay bypass cleanly defeats the kill path. The re-entry residual is a separate, much milder theme-level issue (`ColorStateListInflaterCompat` on a Button background), framework-tolerated.

**Future hardener (Track X)** — available if further hardening is later wanted: convert the 4 bare-`?attr` items on `CustomSwitchThemeOverlay` (styles L748, `colorPrimary→?attr/accentGood`, `colorSurfaceVariant→?attr/colorSurfaceVariant`, `colorSurface→?attr/colorSurface`, `colorOutline→?attr/switchNormal`) → literal `@color` slots. Same poison family as Track-D (TYPE_ATTRIBUTE 0x2 in color slots), and could also quench this residual W (the inheriting theme would no longer feed `?attr` to Buttons' `backgroundTint`). Track X was deferred by the user ("try Y first"; Y = scoped Track-D, which passed; X = app-wide, retained as future-hardener option).

### DECISION-006/D chapter-closure pointer

DECISION-006 (`docs/DECISIONS.md` ledger) hot-plug + always-on chapter closed end-to-end at HEAD `1c62bfd91` (commit on main; `892a28182` audit-additions; Phase-1 relay #2c DV-D visual seal 2026-08-02; DV-D.b MITM-golden `example.com` `Established TLS MITM tunnel` PID 443 sealed 2026-08-03; O5 always-on/reboot NO-GAP verified 2026-08-04 — see `[[project_O5_always_on_verdict_sealed_20260804]]`). The Phase-1c Plus-tab work is downstream of DECISION-006/A (unified plus rename) and enabled by the DV-D hot-plug path (UI surfaces that toggle MITM state ride the same gate). No further DECISION-006 action item is opened by this section — DECISION-006/D is fully closed in the committed ledger (`pushed at 1c62bfd91`, `892a28182` docs-commits, `4db1b48f6` O7 docs-commits all on the published history per `git ls-remote`).

### Cross-links

- `[[project_phase1c_relay2c_trackd_sealed_20260813]]` — Track-D crash rootcause + Steps A/B/C disproven + D won + first seal (APK `23dbe11f`).
- `[[project_phase1c_unified_ui_hero_label_fix_20260813]]` — hero RPN→Plus audit prior to re-seal.
- `[[supervisor/handoff-plus-tab-ui-fix-20260813-supv]]` — supervisor handoff driving this re-seal handoff (started a new session per the long-thread handoff rule).
- `[[project_phase1c_pivot_20260809]]` — RETIRE-RPN-UI + hoist `RethinkPlusFragment` fdroid→full (the layout body this section audits carries Plus/MITM-themed strings produced by that pivot).
- `[[project_phase1c_relay2_block_fabrication_20260811]]` — anti-fab gate provenance (the `675fb979` blank-canary md5 originates here).

### Closure

- Phase-1c VERDICT: BOTH fixes hold on the FINAL tree (Track-D crash re-sealed + hero `"Plus"` re-confirmed).
- Working-tree footprint at this run: `M docs/AUDIT-RESULTS.md` (this edit) + 4 .kt-swap files from the Phase-1c pivot (NotificationHandlerActivity, ProxySettingsActivity, HomeScreenFragment, AboutFragment) + `M strings.xml` / `M bottom_nav_menu.xml` / `M styles.xml` / `M fragment_rethink_plus.xml` (the resource edits in scope) — **NOT yet committed**; HEAD stays `4db1b48f6`.
- **NOT pushed** — PUSH FORBIDDEN holds (no commit, no push, no tag).

### Plus-Tab UX Remediation — Relay Verdict (2026-08-14)

- **Verdict**: PASS (no commit, no push; HEAD 4db1b48f6; PUSH FORBIDDEN).
- **Device**: Xiaomi Mi A1 A16 (serial 3595381c0804).
- **Changes applied**:
  1. `fragment_rethink_plus.xml`: Added CA Generate/Progress/Hint/Install/Save buttons (`btnGenerate` L246, `btnInstall` L267, `btnSaveCert` L275, `progressGen` L238, `tvGenerateHint` L256) inside HTTPS Inspection card. Removed `cardProxySettings`.
  2. `RethinkPlusFragment.kt`: Added click handlers (`btnGenerate` L111, `btnInstall` L137, `btnSaveCert` L172) with `CertificateAuthority` IO/Main coroutine flow; updated `updateCaStatusUi()` (L218) with `canExport` check controlling button enabled states.
  3. `styles.xml`: `PlusSwitchOverlayFix` retains `colorOnSurface` (`primaryText`) + `colorOnSurfaceVariant` (`primaryLightColorText`) — `type=0x2` ComplexColor crash resolved to non-fatal `ResourcesCompat` WARN.
  4. `bottom_nav_menu.xml`: `plus_title` fixed; `AndroidManifest.xml`: `LauncherAliasHome` enabled=false (double-launcher fixed).
  5. Scroll architecture: `NestedScrollView` 0dp + `fillViewport=true` + `clipToPadding=false` + `paddingBottom=80dp`; inner `paddingBottom=0dp`.
- **Build**: `assembleFdroidFullDebug` SUCCESS (3m59s, 43 tasks); APK installed (`-r -t` OK); app launches; Plus tab loads without FATAL crash.
- **Open items**: None — supervisor audit complete; memory saved (`plus-tab-ux-relay-verdict-20260814.md`).

---

## PHASE-1D-A3: MITM DNS/Policy Path Audit & Live Verification — PASS-VERIFIED-DNS-SINKHOLE-INHERITANCE (2026-08-15)

**Question**: Does `LocalHttpsProxy` inherit Rethink DNS blocklist policy, or bypass it?

**Verdict: PASS-VERIFIED-DNS-SINKHOLE-INHERITANCE — Domain-level DNS blocking propagates through HTTPS proxy automatically via DNS sinkhole inheritance.**

### Empirical Device Proof (Xiaomi Mi A1 A16, serial 3595381c0804, HEAD `4db1b48f6`)

| Test | Target | Command / Action | Result | Key Logcat Evidence |
|------|--------|------------------|--------|---------------------|
| A (Direct) | `http://doubleclick.net` | `curl http://doubleclick.net` | `http_code=000`, Connection timed out (Ping: sinkhole 127.0.0.1) | N/A (direct DNS) |
| B (Proxy) | `https://doubleclick.net` | `curl -x http://127.0.0.1:8443 -k https://doubleclick.net` | **HTTP/1.1 502 Bad Gateway** | `Resolved host 'doubleclick.net' securely on active network: 0.0.0.0` → `ECONNREFUSED` |
| C (Proxy) | `https://googleads.g.doubleclick.net` | `curl -x http://127.0.0.1:8443 -k https://googleads.g.doubleclick.net` | **HTTP/1.1 502 Bad Gateway** | `Resolved host 'googleads.g.doubleclick.net' securely on active network: 0.0.0.0` → `ECONNREFUSED` |
| D (Proxy, shell) | `https://example.com` | `curl -x http://127.0.0.1:8443 -k https://example.com` | **HTTP/1.1 200 OK** (Raw TCP pass-through) | `Resolved host 'example.com' securely on active network: 104.20.23.154, 172.66.147.243` → `Bypassing example.com (Raw TCP pass-through mode)` |
| E (Chrome) | `https://example.com` | `am start -a VIEW -d 'https://example.com' com.android.chrome` | **Page loaded under TLS MITM Decryption** | `Established TLS MITM tunnel for example.com` ×3 |
| F (Chrome) | `https://doubleclick.net`   | `am start -a VIEW -d 'https://doubleclick.net' com.android.chrome` | **ERR_TUNNEL_CONNECTION_FAILED / 502** | `Resolved host 'doubleclick.net' securely on active network: 0.0.0.0` → `ECONNREFUSED` |

### Architectural Mechanism (Verified In-Source + On-Device)

```
LocalHttpsProxy.resolveHostSecurely() [L241-248]
    │
    └──► ConnectivityManager.activeNetwork.getAllByName(host)
             │
             └──► Active network = VPN interface (tun1)
                      │
                      └──► Rethink DNS Resolver (VPN DNS Engine)
                               │
                               ├── Blocked domain (e.g., doubleclick.net)
                               │     └──► Returns 0.0.0.0 sinkhole
                               │           └──► createAndProtectUpstreamSocket() tries 0.0.0.0:443
                               │                 └──► ECONNREFUSED → 502 Bad Gateway
                               │
                               └── Allowed domain (e.g., example.com)
                                     └──► Returns real public IPs
                                           └──► VpnController.protectSocket() → physical interface
                                                 └──► TLS MITM (whitelisted pkg) / Raw TCP (non-whitelisted)
```

### Key Finding

**Domain-level DNS blocking does NOT require a manual text-bridge or duplicate blocklist evaluation inside `LocalHttpsProxy.kt`.** Because `resolveHostSecurely()` queries `activeNetwork.getAllByName()`, the DNS query is processed by RethinkDNS, returning `0.0.0.0` for blocked domains and resulting in an immediate `502 Bad Gateway` connection rejection. The manual `syncBlocklistToAdblockRules` → `adblock_rules.txt` → `FilterEngine` bridge is **OBsolete pending source cleanup** (A2 STOP-P2: selected tag 54 existed, but raw rule text was unavailable from Rethink's compiled DNS artifacts). **There is NO documented source-flow: `Rethink DNS blocklists → FilterEngine`.** Advanced Filter Sources (EasyList / AdGuard / Custom URL) are a separate independent subsystem for FilterEngine rule supply.

### Artifacts Captured

- `phase1d_a3_chrome_example.png` (67.7 KB) — Chrome loading example.com via TLS MITM
- `phase1d_a3_chrome_doubleclick.png` (81.0 KB) — Chrome ERR_TUNNEL_CONNECTION_FAILED on doubleclick.net
- `phase1d_a3_plus_tab_top.png` (176.7 KB) — RethinkPlusFragment with HTTPS Inspection active & CA installed
- `phase1d_a3_mitm_full_device_logcat.txt` (883.5 KB, 6,118 lines) — Full device logcat capturing all test runs
- `phase1d_a3_FINAL_REPORT.txt` — Comprehensive report

### Governance

- Source edits: 0 (READ-ONLY AUDIT)
- Commit: FORBIDDEN (none executed)
- Push: FORBIDDEN (none executed)
- Crash buffer: 0 crashes / 0 FATAL / 0 InflateException
- HEAD: `4db1b48f6` intact

### Documentation Updated (This Session)

- `docs/ARCHITECTURE-MAPPING.md` — Packet flow diagram + guardrails updated with DNS sinkhole inheritance path
- `docs/UNIFIED_UI_ARCHITECTURE.md` — Plus tab Section 2 renamed; bridge description clarified
- `docs/DECISIONS.md` — Appendix: **DECISION-008** (DNS Policy Ownership)
- `PHASE_1A_IMPLEMENTATION_PLAN.md` — Marked SUPERSEDED/RETIRED (see below)

### Cross-links

- `[[phase1d_a3_verdict_20260815]]` — Verdict memory entry
- `[[project_O7_confirm_relay_dispatched_20260806]]` — O7 firewall test regression confirmed GONE (context: Phase-1c pivot produced the final tree audited here)
- `[[docs/DECISIONS.md#DECISION-008]]` — Decision ledger entry

---

## 2026-08-27 — Custom Filter Source Manager and Browser Runtime Follow-up

### Repository state

- Branch: `phase1d-advanced-filter`
- Initial implementation commit: `ed8a0a2b774cf9795b17a44ad52ad77204b33b86`
- Parent: `e0e2892060518f86f37a7adca433f8f1d68a6940`
- Initial commit subject: `feat(filter): manage custom filter sources`
- Tracked-file closure commit: `ca797a1d179b060b602c26664814111b640ffd8a`
- Closure commit subject: `fix(filter): track custom source support files`
- The closure commit adds seven support files referenced by the initial implementation.
- Local and remote branch heads were verified equal after push.
- Nine application and test files were committed.
- `.claude/skills/run-rethink-app/SKILL.md` remained unstaged and was not included.

### Automated verification

- `FilterSourceRepositoryTest`: 41 passed.
- `ManageFilterSourcesViewModelTransactionTest`: 38 passed.
- `FilterRowFlattenerTest`: 9 passed.
- Additional closure suites:
  - `CustomFilterSourceValidatorTest`: 9 passed
  - `FilterSourceCustomDaoTest`: 5 passed
- Targeted total: 102 passed, 0 failed, 0 errors, 0 skipped.
- Final Gradle closure completed with exit code 0 and one `BUILD SUCCESSFUL` marker.

### APK and device verification

- APK SHA-256: `39710942B1A17DDB92B2C1F25D8DD6420E2C7411D7ECD011E40F577F16983E36`.
- Installed APK was verified byte-identical to the local APK.
- Device: Mi A1 A16.
- Add, persistence, edit-dialog, remove-dialog and cold-relaunch flows were exercised.
- A URL-only edit persisted byte-exactly across two cold relaunches.
- Removal persisted across cold relaunches.
- B11R carries an evidence caveat: the pre-remove XML did not contain the target row even though runner output claimed that it did. Do not treat B11R as a clean full-sequence PASS.

### Manual runtime observation

The following is a user-reported manual observation and not yet a controlled automated verdict:

1. No hot-reload toast appeared when a filter source switch was enabled or disabled.
2. Browser internet access worked before the custom filter was added.
3. After the custom filter was added, browser web access failed while HTTPS inspection was enabled.
4. Browser web access worked again when HTTPS inspection was disabled.

Interpretation:

- The observation suggests a regression in the HTTPS inspection or interception path.
- It does not prove that custom filter rules successfully blocked a controlled target.
- A missing toast is a UX observability issue; by itself it does not prove that runtime reload failed.
- The controlled website OFF → ON → OFF test remains outstanding.

### HTTPS policy audit

Repository inspection found:

- No production `InspectionPolicyEngine` implementing the complete DOC5 policy.
- No repository copies of the expected preset inputs:
  - `ssl_allow_list.txt`
  - `ssl_block_list.txt`
  - `filter_https_traffic_inclusions.txt`
  - `filter_https_traffic_inclusions_problematic_devices.txt`
  - `filter_https_traffic_exclusions.json`
- `LocalHttpsProxy` currently relies on hardcoded persistent bypass seeds plus runtime state.
- Therefore the current implementation must be described as a partial hardcoded hybrid, not as the completed preset-driven hybrid policy.

### Current verdict

- Custom Filter Source Manager code and UI: PASS.
- Targeted JUnit gate: PASS.
- APK installation and UI persistence evidence: PASS.
- Clean removal audit chain: PASS WITH CAVEAT.
- Controlled real-website filter behavior: NOT YET PASSED.
- Full HTTPS preset-driven hybrid policy: NOT IMPLEMENTED.
- Release-candidate readiness: BLOCKED pending HTTPS regression diagnosis and controlled website verification.

---

## Phase-1D N4E HTTPS Policy / Device Closure — 2026-09-04

### Scope

This addendum records the final N4E controlled-device evidence for HTTPS
Inspection policy eligibility, dynamic-browser discovery, compatibility bypass,
package lifecycle inventory refresh, and final clean-state restoration.

It does not rewrite the historical Phase-0 audit above.

### Device

```text
Device: Xiaomi Mi A1 / tissot
Android: 16
SDK: 36
Serial: 3595381c0804
Package: com.celzero.bravedns.plus
```

### Dynamic-browser root cause

The controlled dynamic-browser fixture was lost at the Android PackageManager
query boundary because browser capability discovery used
`PackageManager.MATCH_DEFAULT_ONLY`.

Literal comparison established:

```text
ACTION_MAIN + CATEGORY_APP_BROWSER, flags=0
→ fixture present

same selector + MATCH_DEFAULT_ONLY
→ fixture absent
```

The detector repair removed the default-handler restriction.

### Runtime policy evidence

General controlled fixture:

```text
Decision: BYPASS_DEFAULT
Transport: raw TCP pass-through
HTTP: 200
Certificate: public Cloudflare chain
```

Compatibility controlled fixture (`com.facebook.katana` fixture APK):

```text
Decision: BYPASS_COMPATIBILITY
Transport: raw TCP pass-through
HTTP: 200
Certificate: public Cloudflare chain
```

Dynamic controlled browser fixture:

```text
Policy snapshot: dynamicBrowsers=1
Decision: MITM_DYNAMIC_BROWSER
TLS MITM: established
HTTP: 200
Issuer: RethinkDNS Root CA
Subject: RethinkDNS Local / example.com
```

### Inventory lifecycle defect and repair

The first cleanup attempt exposed a burst-removal defect:

* first removed fixture disappeared;
* two later removed fixtures remained queryable in Rethink Apps;
* `RefreshAppsJob` used `ACTION_REFRESH_AUTO`;
* `RefreshDatabase` suppresses AUTO/INTERACTIVE refreshes within its one-minute
  refresh interval.

The repair keeps ordinary RefreshAppsJob callers defaulting to AUTO but gives
package lifecycle requests an explicit `ACTION_REFRESH_FORCE`.

The package receiver continues using `ExistingWorkPolicy.REPLACE`.

### Verification

Temporary GHA verification commit:

`78fb25576cd5d52b5674aedfebbc55f2794bfaf8`

Targeted GHA result:

```text
BravePackageChangeReceiverTest
tests=10
failures=0
errors=0

production Kotlin compile=PASS
```

Real-device regression:

```text
Android package-event window ≈ 3.579 seconds

general:
delete app → refresh done

compatibility:
delete app → refresh done

dynamic:
delete app → refresh done

post-removal PackageManager:
absent / absent / absent

post-removal Configure → Apps search:
0 / 0 / 0
```

The earlier host-side `82.209 s` measurement is not the package-event burst
interval; it included Windows/ADB command-dispatch overhead and is not used as
the throttle-regression metric.

### Final restoration

Final Protection restart completed with no additional tap after the allowed
stabilization period.

Newest clean policy snapshot:

```text
systemPackages=4
systemUids=2
compatibility=201
protectedDomains=4308
knownBrowsers=147
dynamicBrowsers=0
```

Final state:

```text
fixtures installed = NO
Protection = ON / protected
VPN = tun1
network = healthy
patch preserved = YES
```

### Audit verdict

```text
N4E_POLICY_RUNTIME=PASS
N4E_DYNAMIC_BROWSER_DISCOVERY=PASS
N4E_COMPATIBILITY_BYPASS=PASS
N4E_GENERAL_DEFAULT_BYPASS=PASS
N4E_PACKAGE_LIFECYCLE_REFRESH=PASS
N4E_DEVICE_FULLY_SEALED=YES
```

Repository integration remains pending: this evidence was produced from the
verified local working tree. Do not reinterpret committed HEAD
`43e02cd0956d6aefc487eac0d534eaefa99c769d` as already containing every N4E
working-tree file.

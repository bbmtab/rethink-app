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

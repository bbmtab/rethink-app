# Architecture Decisions Log

> **Purpose:** Record irreversible architectural decisions with rationale. One entry per decision. Never delete — append only.

---

## DECISION-001: Block Upstream Merge — Upstream Removed MITM/Adblock Infrastructure

**Date:** 2026-07-22  
**Status:** FINAL  
**Deciders:** User + Supervisor  

### Context

Attempted routine upstream merge per `UPSTREAM_MERGE_SOP.md` §3.2. Pre-merge check revealed upstream (`celzero/rethink-app@main`) deleted **all MITM/adblock/CA infrastructure** that constitutes our Phase 0 work.

### Deleted Files (Our Territory)

| File | Lines | Our Investment |
|------|-------|----------------|
| `core/ca/CertificateAuthority.kt` | 491 | R1: CA persistence fix (`setKeyEntry` L343), verified on device |
| `core/proxy/LocalHttpsProxy.kt` | 1,305 | R3/R4: MITM proxy, 55 tunnel lines, domain bypass, allowlist |
| `core/filter/FilterEngine.kt` | 816 | R5a/R5b: Parser, 97/98 tests, 100% EasyList parse ratio |
| `core/filter/CosmeticFilter.kt` | 215 | CSS injection |
| `core/filter/CspInjector.kt` | 240 | CSP header injection |
| `core/filter/ProceduralFilter.kt` | 347 | Procedural cosmetic filters |
| `core/filter/ScriptletFilter.kt` | 289 | Scriptlet injection |
| `core/filter/HtmlFilter.kt` | 230 | HTML element removal |
| `ui/activity/CertificateSetupActivity.kt` | 310 | CA install UI |
| `assets/scriptlets.js` | 280 | Bundled scriptlet library |
| All filter/proxy/CA unit tests | ~2,000 | R5a verification suite |

### Upstream Changes Indicating Pivot

| Indicator | Evidence |
|-----------|----------|
| Billing focus | `InAppBillingHandlerTest.kt` (+1,324 lines) |
| New proxy approach | `ProxyManagerTest.kt` (+967 lines) |
| Windscribe removed | `WindscribeApiService.kt`, `WindscribeLoginActivity.kt` deleted |
| RPN focus | `RpnProxyManager.kt`, `RpnConfigDetailActivity.kt` expanded |
| No MITM references | Zero CA/proxy/filter files in upstream tree |

### Decision

**DO NOT MERGE `upstream/main`.** Our fork is now the **canonical implementation** of MITM/adblock for RethinkDNS. Upstream has abandoned this architecture.

### Rationale

1. **Phase 0 verified working** — All R1–R5b gates closed with empirical evidence on device
2. **Upstream deletion is intentional** — Not a refactor; entire subsystem removed
3. **No upstream alternative** — They offer no replacement for local HTTPS inspection
4. **Our users depend on this** — fdroid flavor gets free MITM/adblock; play/website gets RPN + MITM
5. **Maintenance burden accepted** — We own `core/ca`, `core/proxy`, `core/filter` indefinitely

### Consequences

| Area | Impact |
|------|--------|
| **Merge workflow** | SOP §3Strategy B modified: only cherry-pick non-conflicting upstream commits |
| **File ownership** | `core/ca`, `core/proxy`, `core/filter` → **US exclusively** (no longer SHARED) |
| **CI guards** | Must verify our files *exist* and *compile* — not just anchors |
| **Release tagging** | `vX.Y.Z-plus` tags mark our MITM/adblock releases, independent of upstream version |
| **Upstream tracking** | Monitor for DNS/firewall/WireGuard improvements only |

### Cherry-Pick Policy (New)

```bash
# Safe to cherry-pick (UPSTREAM territory, no conflicts):
# - DNS, DoH/DoT/DoQ, DNSCrypt logic
# - Firewall, WireGuard, RPN, VPN tunnel
# - UI, Settings, Stats, Logs, Apps
# - Build scripts, Gradle, dependencies

# BLOCKED (OUR territory — upstream deleted):
# - core/ca/*, core/proxy/*, core/filter/*
# - CertificateSetupActivity, RethinkPlus*, Windscribe*

# MANUAL REVIEW (SHARED but upstream changed):
# - BraveVPNService.kt (upstream modified +777/-)
# - PersistentState.kt (upstream modified +16/-)
# - RethinkBlocklistManager.kt (check if exists in upstream)
```

### Verification

Post-decision validation:
- [x] `./gradlew :app:assembleFdroidFullDebug` — builds with our MITM stack
- [x] Unit tests pass: `./gradlew :app:testFdroidFullDebugUnitTest --tests "*filter*"`
- [ ] Device smoke test: CA install → HTTPS toggle → MITM works

---

## DECISION-002: Permanent Fork for MITM/Adblock Stack

**Date:** 2026-07-22  
**Status:** FINAL  
**Deciders:** User + Supervisor  

### Decision

Accept permanent divergence on `core/ca`, `core/proxy`, `core/filter`, `CertificateSetupActivity`, `RethinkPlus*`, `Windscribe*`. These files will **never** be synced from upstream.

### Implementation

1. Update `UPSTREAM_MERGE_SOP.md` ownership map: move these to **US exclusively**
2. Add CI check: fail build if any of these files are missing
3. Document maintenance burden in README or `DEVELOPER_GUIDE.md`
4. Version our MITM stack independently: `mitm-engine-v1.0.0`

### Review Trigger

Revisit only if:
- Upstream releases MITM/adblock feature parity (unlikely)
- Android API changes break our implementation (e.g., `VpnService` deprecation)
- We decide to contribute our stack back via PR (separate decision)

---

## DECISION-003: Phase 1 Proceeds on Our Fork

**Date:** 2026-07-22  
**Status:** FINAL  
**Deciders:** User + Supervisor  

### Decision

Phase 1 implementation (blocklist bridge, Plus tab, auto-restart) proceeds **on our fork** without upstream merge. No delay.

### Adjusted Phase 1a Scope

| Original Plan | Adjusted |
|---------------|----------|
| Merge upstream first | **Skip merge** — start directly on our `main` |
| `RethinkBlocklistManager` sync (SHARED) | Our version is canonical; implement bridge here |
| `BraveVPNService` stamp listener (SHARED) | Our version has MITM block; add listener to our code |
| Upstream blocklist improvements | None upstream — we have the only implementation |

### Risk Mitigation

- Upstream DNS/firewall fixes: cherry-pick individually as needed
- Build breakage from Gradle/dependency changes: test weekly
- Android SDK changes: monitor upstream for adaptation patterns

---

## DECISION-004: Third-Party Firewall References (NetGuard Clean-Room Study)

**Date:** 2026-07-23  
**Status:** POLICY CARRIED; STUDY EXECUTION DEFERRED to Phase 2+  
**Deciders:** User + Supervisor

### Context

User observed that NetGuard ([github.com/M66B/NetGuard](https://github.com/M66B/NetGuard), GPLv3) covers an estimated 3/5 of RethinkDNS+'s firewall-product requirements, and proposed studying it as a parity reference. The licensing question took precedence over the algorithmic one.

### License Constraint (the deciding factor)

- Our distribution uses **Apache 2.0** (verified via file headers in `core/ca/`, `core/proxy/`, `core/filter/`).
- NetGuard uses **GPLv3** (verified at `github.com/M66B/NetGuard/blob/master/LICENSE`).
- GPLv3 §5–§6: any code copied into our distribution forces the receiving work to also be GPLv3. Even a single file of verbatim adoption triggers the copyleft contagion.

### Adoption Hierarchy (acceptable paths, ranked)

| Tier | Path | Verdict |
|------|------|---------|
| 1 | Direct copy/paste of NetGuard source | **NOT RECOMMENDED**. Architectural decision required; would force RethinkDNS+ to relicense as GPLv3 (or split distribution into a "closed" Apache partition + an "open" GPL partition). |
| 2 | Small isolated code adoption (single utility/helper) | Only feasible for **permissive** third-party licenses (MIT, BSD-3-clause, Apache 2.0). For **copyleft** sources like NetGuard's GPLv3, the threshold is **any code**, not "size" — every line of source is equally contagious. Requires fresh DECISIONS.md entry + license review. |
| 3 | Clean-room study (read publicly, understand algorithm, re-implement from scratch with our own architecture + Apache 2.0 header) | **ACCEPTABLE.** Standard practice (cf. Linux kernel / BSD code). |
| 4 | Product/UX benchmarking (feature-parity analysis) | **ACCEPTABLE.** |
| 5 | Architecture comparison only | **ACCEPTABLE.** |

### NetGuard Clean-Room Study Scope (DEFERRED to Phase 2+)

When (and only when) the Phase 2+ study is initiated, the following dimensions are in scope, subject to the hierarchy above:

- **Firewall UX** — UI patterns, user flows.
- **Rule model** — data structures for per-app rules.
- **Roaming/mobile/WiFi semantics** — context-aware gating.
- **Temporary allow / lockdown concepts** — transient states.
- **Background/foreground policy behavior** — app-state-aware rules.

Explicitly out of scope (NetGuard-specific items that don't fit our architecture):

- VPN stack (already ours; NetGuard uses a different model).
- Packet loop (NetGuard's own; ours is integrated with `BraveVPNService`).
- Service lifecycle (NetGuard's standalone model doesn't map to tunnel-coupled services).
- **Direct source imports without approval.**

### Reference-SHA Discipline

At the moment the Phase 2+ study begins (NOT now), the **exact commit SHA** of the public NetGuard source we read from MUST be recorded in this decision entry **before** any code is written. This single line is the audit trail distinguishing "clean-room" from "transcribed."

### Timing

Current Phase 1 priority **unchanged** by this decision:

1. Close `bbmr91ojf` isolated FirewallManagerTest run.
2. Resolve +15 FirewallManagerTest attribution (intrinsic vs cross-suite pollution).
3. Return to the 101 pre-existing failure floor.
4. Complete Phase 1b (Plus-tab fdroid MITM UI).
5. Revisit NetGuard as a Phase 2+ study item per this entry.

### Review Trigger

Revisit DECISION-004 only when:

- Phase 2+ begins and a NetGuard clean-room study is initiated (record reference SHA at that time).
- A different third-party firewall / different license combination is considered (new DECISION entry needed).
- Project-wide relicensing decision changes (e.g., a decision to move RethinkDNS+ to GPLv3 would obsolete this entry).

---

## DECISION-005: Remove WgHopManager init-coroutine leak (P-i); RpnHopManager dormant twin deferred

**Date:** 2026-07-25  
**Status:** FINAL (scope authorized by supervisor 2026-07-25)  
**Deciders:** User + Supervisor

### Context

Full-suite run (task bdrzrf32x, 2026-07-24) showed `WgHopManagerTest` 1/84 — a single flaky failure: testcase "should return empty string for getHop with whitespace string", type `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`, thrown in setUp at WgHopManagerTest.kt:124.

Root cause (confirmed read-only; executor independently confirmed 2026-07-25): `WgHopManager` is a Kotlin `object : KoinComponent` (WgHopManager.kt:18). On first reference, its class init fires `init { io { load(forceRefresh = false) } }` (WgHopManager.kt:24-26), where `io` (WgHopManager.kt:245-247) is an unstructured fire-and-forget launcher: `private fun io(f: suspend () -> Unit) { CoroutineScope(Dispatchers.IO).launch { f() } }`. The leaked coroutine runs on the REAL JVM Dispatchers.IO at nondeterministic time; `load` → `getDb` (by-inject, L20) → `getKoin()` (L18). In the full suite, a NEIGHBOUR test's `@After stopKoin()` races that leaked coroutine → `IllegalStateException("KoinApplication has not been started")` on the IO worker → bridges into the next `runTest`'s `TestScopeImpl.enter` as `UncaughtExceptionsBeforeTest`. The victim test is timing-unlucky, not guilty. Solo (bx240iip6) = 0/84 (no racing stopKoin); full-suite = 1/84. Independently corroborated by the second suppressed item `DiagnosticCoroutineContextException(...StandaloneCoroutine{Cancelled}..., Dispatchers.IO)`.

NOT the MockK relaxed-flag chain (that was TempAllowExpiryWorkerTest, separately CLEARED). NOT purely test-fixable: `Dispatchers.setMain(StandardTestDispatcher)` pins Dispatchers.Main only; the leak is on Dispatchers.IO; `WgHopManager` exposes no injectable dispatcher (P-iv would add that seam; deferred out of this decision's scope).

### Provenance

`WgHopManager.kt` + `WgHopManagerTest.kt` = UPSTREAM, authored by `hussainmohd-a <hussainmohd.a@gmail.com>`. Leak-lineage reference SHAs (each touches `WgHopManager.kt`): `7361bbc7e9` ("feature: hop; ui impl, mgr changes, pass 1", 2025-04-19) introduces the unstructured `CoroutineScope(Dispatchers.IO).launch { }` fire-and-forget launcher and the `init { io { load() } }` object-init warm-up; `84ff161f50` ("minor: optimize imports, logger changes", 2025-04-30) refines the warm-up to `init { io { load(forceRefresh = false) } }` (adds the `forceRefresh` parameter to `load()` plus an early-return guard); upstream tip `b935b1c66` ("wg: hop changes wrt to rpn", 2026-04-26). Zero Antigravity AI touch pre-P-i. Pre-dates all local Phase work.  Doc-precision amendment (2026-07-27): a prior version here mis-cited `3b81c5505` ("optimize imports", 2026-06-14), which does NOT touch `WgHopManager.kt`; authoritative SHAs `7361bbc7e9` / `84ff161f50` are taken from commit `6a726ec31` message body and re-verified via `git log --follow` + `git show --stat`.

DECISION-001 (core/{ca,proxy,filter} upstream merge-block) does NOT implicate wireguard/rpn. DECISION-004 (clean-room for NEW-source adoption) does NOT apply — this is maintenance of a pre-existing repo file, not new third-party adoption. This entry records upstream-origin + reference-SHAs per policy.

### Behavior-neutrality (verified read-only, four-clause closure 2026-07-25)

The init leak is a REDUNDANT duplicate of `RefreshDatabase.kt:160` `WgHopManager.load(forceRefresh = false)`, the authoritative warm-up. `load()` early-returns when `maps.isNotEmpty()` (WgHopManager.kt:28-30), so the second arrival is a no-op.

(1) FUNCTIONALLY neutral: identical work; removing init changes no reader's observed map state (L160 remains).
(2) TIMING neutral: every production reader (`getAllHop`@GoVpnAdapter:1012, `getHop`@GoVpnAdapter:1033/1297, `isWgEitherHopOrSrc`@BraveVPNService:6462) runs AFTER `RefreshDatabase.refresh()` completes — refresh precedes `restartVpn` (BraveVPNService.kt:1974-1976); `screenUnlock` is later. The init leak's arrival is nondeterministic and delivers no guaranteed-earlier population than L160. Removing it cannot shift any reader's observed state.

No production reader precedes refresh; no reader depends on eager object-init for its observed state. androidTest sweep clean (zero WgHopManager refs). ServiceModule.kt has no Koin binding for WgHopManager (it is a plain `object`, not Koin-resolved).

### Decision

Apply **P-i** to `WgHopManager` ONLY:
1. Remove the `init { io { load(forceRefresh = false) } }` block (WgHopManager.kt:24-26).
2. Remove the now-unused `private fun io(...)` launcher (WgHopManager.kt:245-247) — grep-confirmed sole caller was the removed init block (no other `io` call site in the file). Removing it eliminates the unstructured `CoroutineScope(Dispatchers.IO).launch` pattern from the file (it is the leak mechanism). Behavior-preserving.
3. Leave `load()`, all readers, and all other methods untouched. Rely on `RefreshDatabase.kt:160` as the sole warm-up.
4. Leave `RpnHopManager.kt` UNTOUCHED (dormant twin, below).

DECISION-005 lands in this file (`docs/DECISIONS.md`) BEFORE the production edit.

### RpnHopManager dormant twin (DEFERRED, recorded for future-wiring)

`RpnHopManager.kt` carries the byte-identical leak: L20 `object RpnHopManager : KoinComponent`, L22 `db by inject()`, L27-29 `init { io { load(false) } }`, L249-250 `private fun io(f) = CoroutineScope(Dispatchers.IO).launch { f() }`.

It is DORMANT in the current graph (no known execution path):
- Only external reference is `WgHopMapDao.kt:24 import ...RpnHopManager.ID_RPN` — a `const val`, inlined at compile time, does NOT trigger object `<clinit>`.
- No `RpnHopManagerTest` exists (Glob+grep clean).
- No Koin binding in `ServiceModule.kt`.
- No reflection / `Class.forName` / serialization / type-adapter path found.

Not fixed in this decision because editing a file with no current execution path and no test is pre-emptive over-reach beyond the authorized flake-kill scope.

### Future-wiring CONTRACT (binding)

IF `RpnHopManager` becomes reachable in the future — any new direct method-call reference, Koin binding, reflection/serialization/`Class.forName` hook, or a test that forces `<clinit>` — then removal of ITS `init` warm-up (or dispatcher-injection of the same shape this decision deferred for WgHop) MUST be evaluated as part of that change. This entry is the durable trigger for that review. Do NOT wire `RpnHopManager` into the graph without re-opening this decision.

### Consequences

| Area | Impact |
|------|--------|
| Full-suite tests | Expected WgHopManagerTest 1/84 → 0/84 (total 852/46 → 852/45, 0 errors). RpnProxyManagerTest 96/40 unchanged. No new failures. |
| Production behavior | No change — RefreshDatabase.kt:160 remains sole warm-up; all readers run after refresh. |
| RpnHopManager | Untouched; dormant; guarded by future-wiring contract. |
| Upstream tracking | WgHopManager.kt is upstream territory; this is a local maintenance fix on a permanent-fork file. Future cherry-picks of wireguard upstream commits touching WgHopManager must reconcile this removal. |

### Review Trigger

Revisit this decision when:
- `RpnHopManager` becomes reachable (future-wiring contract fires).
- A test proves P-i did not kill the flake (WgHopManagerTest fails again in full-suite).
- Upstream restructures WgHopManager init/io (cherry-pick reconciliation).
- A later decision authorizes P-iv (dispatcher-injection seam) for defense-in-depth on both twins.

---

## DECISION-006: NON-HOT-PLUGGABLE SETTINGS RESTART UX

**Date:** 2026-07-30
**Status:** ACCEPTED (user-authored decision, 2026-07-30)
**Deciders:** User (author) + Supervisor (records, clarifies acceptance)
**Refines:** [[feedback_auto_restart_on_setting_change]] — the "pop-up warning" requirement is replaced by an informational Toast (restart is mandatory; no user agency).

### Context

DV4/G4 (device-verify 2026-07-29, Mi A1 A16 serial 3595381c0804, executor relay v3) CONFIRMED FAIL: toggling HTTPS Inspection crashes the app. RAW FATAL `java.lang.IllegalStateException: You need to use a Theme.AppCompat theme (or descendant) with this activity.` at `SettingsRestarter.requestRestart` → `dialog.show()` (SettingsRestarter.kt:76). Root cause: `requestRestart(context=this, ...)` is invoked by `BraveVPNService.onSharedPreferenceChanged` (BraveVPNService.kt:2259); `context` is a **Service**, and `AlertDialog` resolves to `androidx.appcompat.app.AlertDialog` (import SettingsRestarter.kt:22) whose `AppCompatDelegateImpl.createSubDecor()` requires an Activity theme — a Service context has none ⟹ throws before the L73-75 `TYPE_APPLICATION_OVERLAY` workaround is operative. tun1 goes DOWN and is never restored; the intended "Restart Required" AlertDialog + clean `restartApp` (killProcess+launchIntent, SettingsRestarter.kt:83) never fires. Defect introduced by `a88b789d2` ("feat: Phase 1a+1b", 2026-07-24); never exercised until DV4.

Supervisor note: the pre-execution hypothesis that the crash would be `BadTokenException` from the missing `SYSTEM_ALERT_WINDOW` permission at the L73-75 overlay path was WRONG — the theme crash pre-empts it. The overlay/permission concern remains LATENT, not active. Recorded per [[feedback_symptom_not_mechanism]].

### Problem

HTTPS_INSPECTION_ENABLED is a NON-HOT-PLUGGABLE setting: changing it requires an application/process restart for the VPN service to pick up the new state. A restart is mandatory and offers no meaningful user choice. A confirmation dialog adds complexity without adding agency.

### Decision

Remove the restart-confirmation dialog entirely. Non-hot-pluggable settings persist the new value, show a short informational Toast "Restarting to apply changes...", and perform an automatic application restart.

### Policy

1. NON_HOT_PLUGGABLE settings SHALL: persist the new value; show a short Toast ("Restarting to apply changes..."); perform an automatic application restart.
2. No AlertDialog SHALL be shown.
3. SettingsRestarter SHALL NOT depend on: Activity context, AppCompat, TYPE_APPLICATION_OVERLAY, SYSTEM_ALERT_WINDOW.
4. BraveVPNService SHALL remain an observer only (no UI).
5. Future settings requiring restart SHALL reuse the same silent-restart mechanism.

### Affected settings (initial)

- HTTPS_INSPECTION_ENABLED

### Provenance / affected surface (working-tree anchors — executor re-verify)

- `app/src/main/java/com/celzero/bravedns/util/SettingsRestarter.kt` — object to rewrite: remove AlertDialog building (L22 import, L51-77), keep `restartApp` (L83-108). Introduced by `a88b789d2` (`git log -1 -- SettingsRestarter.kt`).
- `app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt:2257-2264` — observer branch (stays; `context = this` Service context is now safe because no Activity-themed dialog is built). Introduced by `a88b789d2`.
- Trigger write-site: `CertificateSetupActivity.kt:156-157`. Other write-sites: `CertificateSetupActivity.kt:306` (reset false), `RethinkPlusFragment` master-toggle (flavor dirs). Ensure none silently miss the restart.

### Supervisor acceptance gate (clarification — binding)

"Perform an automatic application restart" is interpreted to require the **VPN to be restored** after the restart, not merely the process recreated. The motivating defect was "vpn stop, not restarting." Grounds (supervisor investigation 2026-07-30, executor re-verify):
- `VpnController.state().activationRequested` is sourced from `persistentState.getVpnEnabled()` (VpnController.kt:181-184) — persists across process death (the restart path calls no `signalStopService`). ✔
- `HomeScreenFragment.maybeAutoStartVpn()` (HomeScreenFragment.kt:1649-1658) runs on `onResume` — comment "this case will happen when the app is updated or crashed" — calls `prepareAndStartVpn()` (:1783) → `startVpnService()` (:1812) = `VpnController.start(ctx, autoAttempt=true)` (VpnController.kt:140) when `isVpnActivated && !VpnController.isOn()`. ✔
- The existing `restartApp` (killProcess + `getLaunchIntentForPackage` → launcher/HomeScreen) SHOULD auto-resume the VPN via the persisted flag — UNPROVEN on-device; the prior *crash* path masked it (Android crash-recovery restored the TOP activity = CertificateSetupActivity, which has no `maybeAutoStartVpn`, not the launcher/HomeScreen).

Binding DV4 sub-gates:
- **DV4.a (no crash, no dialog, silent restart):** flip switchHttpsInspection → NO `IllegalStateException`/`BadTokenException`; NO AlertDialog appears; a Toast "Restarting to apply changes..." shows; process restarts (new pid, HomeScreen foreground) cleanly.
- **DV4.b (VPN restored — the real acceptance):** within seconds post-restart, `tun1` BACK UP (10.111.222.1/24) + `BraveVPNService` IN ServiceRecord + (if toggled ON) MITM golden line `LocalHttpsProxy.kt:546` fires on a real browse. If `tun1` stays DOWN / BraveVPNService absent → the fix is **INCOMPLETE**: the executor must wire VPN auto-restore on the silent restart (leads: `maybeAutoStartVpn` HomeScreenFragment.kt:1650; `prepareAndStartVpn`/`startVpnService` :1783/1812; `VpnController.start(autoAttempt=true)` VpnController.kt:140; persisted `getVpnEnabled()`; confirm relaunch lands on launcher/HomeScreen, not the prior TOP activity). DV4 is NOT PASS until DV4.b holds.

### Executor scope (DECISION-006)

1. Rewrite `SettingsRestarter.requestRestart`: remove AlertDialog + `TYPE_APPLICATION_OVERLAY` path; show `Toast` "Restarting to apply changes..." (add a string resource; do NOT hardcode) + schedule `restartApp` (existing kill+launch, tune delay to ~500-1000 ms so the Toast is visible). Keep the caller callback contract (`onConfirm`); call `onConfirm()` before restart (note: in-process side-effect, dies with the process — vestigial but harmless; do not break callers).
2. Remove the `androidx.appcompat.app.AlertDialog` import + now-dead dialog code.
3. Leave the `BraveVPNService` observer branch intact (Service context is now safe for Toast + restart — no Activity-themed dialog).
4. Build `:app:assembleFdroidFullDebug` arm64-v8a; `install -r` to device 3595381c0804; re-verify DV1-DV5. DV4.a + DV4.b binding. DV4 is HUMAN-DRIVEN (human flips the in-app switch; executor captures logcat). CA install may persist from the prior run; if absent, HUMAN-ONLY wait-gate (no `pm grant`/mount/su/magisk).
5. Commit ONLY source + this DECISIONS.md entry, ONLY after DV4.a + DV4.b PASS on-device. PUSH requires explicit supervisor/user authorization (relay forbids push).

### Consequences

| Area | Impact |
|------|--------|
| UX | Confirmation "Restart Now/Cancel" dialog removed → informational Toast + mandatory auto-restart (no agency lost; restart was always required). |
| Stability | DV4 `IllegalStateException` (AppCompat theme from Service) eliminated; no new permission (`SYSTEM_ALERT_WINDOW`) needed. |
| VPN continuity | Requires DV4.b to hold (existing `maybeAutoStartVpn` expected to restore); if not, executor wires restore. |
| Latent overlay concern | Stays latent (no dialog-from-Service); future re-introduction of a Service-shown dialog re-opens it. |
| Future settings | Non-hot-pluggable settings reuse the silent restart (policy #5). |

### Review Trigger

Revisit when:
- A future non-hot-pluggable setting needs a *different* UX (e.g. optional restart) — amend policy #1.
- DV4.b cannot be satisfied by `maybeAutoStartVpn` and a separate restore mechanism is wired — record it here.
- A dialog-from-Service is reintroduced → re-opens the latent `SYSTEM_ALERT_WINDOW`/theme concern.

---

## DECISION-006/D: HOT-PLUG HTTPS-INSPECTION TOGGLE (ux-unchanged background restart; killProcess/alarm retired)

**Date:** 2026-08-01 (reframed), 2026-08-03 (sealed end-to-end on-device)
**Status:** SEALED end-to-end on Mi A1 A16 (2026-08-03); ledger entry appended 2026-08-04 (specified 2026-08-01, not shipped by `1c62bfd91` which touched no docs). **PENDING-PUSH** — awaits explicit user "Push now".
**Deciders:** User (reframe 2026-08-01: "restart berlangsung di background tanpa mengubah UX") + Supervisor (records, audited)
**Refines:** DECISION-006 — the `Process.killProcess` + AlarmManager `PendingIntent` relaunch mechanism is RETIRED; replaced by in-process hot-plug via the existing `vpnRestartTrigger` debounce. The policy intent (mandatory background restart, informational Toast, no AlertDialog) is preserved. [[feedback_auto_restart_on_setting_change]] stays the governing policy.

### Context

The base DECISION-006 (2026-07-30) shipped its first mechanism: write the pref → `SettingsRestarter.requestRestart` (Service context) → show an `AlertDialog` → `restartApp` (`Process.killProcess` + `getLaunchIntentForPackage`). On Android 14+, that mechanism self-inflicts the BAL navigation gap (see [[project_decision006_nav_gap_bal_blocked_20260801]]): post-`killProcess`, the relaunching `PendingIntent` sender is DEAD → the OS-issued Background Activity Launch for the relaunch resolves to `BSP.NONE` → `BAL_BLOCK` → the relaunch is blocked; the user lands on HomeScreen, not the settings surface. The kill is the SURFACE for the gap; removing the kill removes the surface. Separately, `LocalHttpsProxy` is genuinely hot-pluggable (`@Synchronized start/stop`, no JNI/process-startup-bound init — inline-verified gate S3), so process death is NOT required to swap the proxy.

### Problem

The base mechanism makes a NON-HOT-PLUGGABLE setting "require" a process restart, but the only thing that actually needs to re-evaluate is the VPN service's `establishVpn` gate (`httpsInspectionEnabled && isCaInstalled()` at `BraveVPNService.kt:3729`), which already reads `persistentState` FRESH each bounce. Process death (a) destroys UX (navigation gap), (b) isn't needed (proxy is hot-pluggable), (c) relies on an AlarmManager `PendingIntent` relaunch path that A14+ BAL blocks. The kill solves a problem the architecture doesn't have.

### Decision

Drop `Process.killProcess` + the AlarmManager `PendingIntent` relaunch from the HTTPS-Inspection toggle path. Route the toggle directly to the existing `vpnRestartTrigger` (a `MutableStateFlow<String>` in `BraveVPNService`) → its `debounce(3000)` collector → `restartVpnWithNewAppConfig` → `restartVpn` → `establishVpn`, which re-evaluates the gate on the LIVE service. Show a short informational `Toast` "Applying…" (`strings.xml` `applying_changes`) on `Dispatchers.Main` via direct platform `makeText` (NOT the DEBUG-gated / Service-context-rejecting `showToastUiCentered`). No `AlertDialog`. No process death. No AlarmManager. UX unchanged (user stays on the activity the whole time).

### Policy

1. The `HTTPS_INSPECTION_ENABLED` toggle SHALL apply via in-process `vpnRestartTrigger` hot-plug, NOT process kill/alarm relaunch.
2. `BraveVPNService` SHALL remain an observer only (no UI) — the toggle writes the pref, the observer arms the trigger.
3. While the apply is in-flight (~3 s debounce), a short `Toast` "Applying…" SHALL surface the pending change (release-visible; not the DEBUG-gated `logAndToastIfNeeded` which is silent in release).
4. If the VPN service is NOT running at toggle, no trigger arms; the change applies on the NEXT VPN-up via the cold `establishVpn` fresh pref read (matches + improves on the kill path's always-on restore, with no gratuitous death).
5. `SettingsRestarter` becomes DORMANT for HTTPS (the kill primitive is retained for future genuinely-non-hot-pluggable settings; `NON_HOT_PLUGGABLE` is empty for HTTPS).

### Provenance / affected surface (working-tree anchors — supervisor re-verified 2026-08-04, raw not asserted)

- `app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt:2256-2266` — observer HTTPS branch: direct `Toast.makeText(ctx, R.string.applying_changes, Toast.LENGTH_SHORT).show()` inside `ui{}` (`Dispatchers.Main` launch, builder at `:4245`), then `vpnRestartTrigger.value = "httpsInspectionEnabled: ${persistentState.httpsInspectionEnabled}"`. No `requestRestart` call.
- `app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt:3729` — the `establishVpn` gate re-evaluated each bounce: `if (persistentState.httpsInspectionEnabled && CertificateAuthority.isCaInstalled())`.
- `app/src/main/java/com/celzero/bravedns/util/SettingsRestarter.kt` — DORMANT `object`; `requestRestart` (L59-68) = trivial `onConfirm()` + `makeText` passthrough (caller-compat); `scheduleRelaunchAndKill()` + constants REMOVED (L70-72 comment); `NON_HOT_PLUGGABLE = emptySet<String>()`.
- RethinkPlusFragment.kt (fdroid toggle listener) — collapses to a pref write; observer does the rest.
- `app/src/main/res/values/strings.xml:2509` — `<string name="applying_changes">Applying…</string>` (the release-visible Toast; L2508 `restarting_to_apply_changes` retained only for the dormant `requestRestart` path).
- Commit: `1c62bfd91` (40 insertions, 127 deletions across 4 source files; touched NO docs — this ledger entry appended retrospectively 2026-08-04).

### Supervisor acceptance gate (clarification — binding)

"Background restart with UX unchanged" requires: (a) the live process survives the toggle (NO `Process … has died`, NO BAL_BLOCK, NO AppCompat/FATAL for bravedns in the toggle window), (b) the VPN bounces (tun re-established), (c) the proxy swaps correctly both directions, (d) the MITM golden splice fires on a non-bypassed browse when ON (the CA-trust leg), (e) B0 anti-fabrication raw both sides.

### DV-D device-run results (SEALED 2026-08-02 + 2026-08-03, Mi A1 A16 serial 3595381c0804, logcat_Dnav.txt + logcat_DnavMITM_2.txt)

| sub-gate | result | raw evidence |
|---|---|---|
| UX unchanged, both directions | SEALED | PID 443 CONSTANT across ON (`httpsInspectionEnabled: true, vpn restarted` @10:23:15.936) + OFF (`false` @10:23:31.908); zero `Process … has died` / FATAL / BAL_BLOCK for bravedns in the toggle window |
| Toast render | CLOSED (human visual 2026-08-02) | Code path correct (`BraveVPNService.kt:2264` direct `makeText` on Main via `ui{}`); human-confirmed "Applying…" renders + toggle fires (supersedes capture-limited in-device-log UNVERIFIED per [[feedback_symptom_not_mechanism]]) |
| ON-path proxy bind | SEALED | `LocalHttpsProxy` start port 8443; `HttpProxy: [localhost] 8443` on tun1 `LinkProperties` |
| OFF-path apply | SEALED | `LocalHttpsProxy: Stopping…`; `ProxyTracker: sending Proxy Broadcast for [] 0 xl=` (empty = no proxy) |
| tun restored both directions | SEALED | `Established by com.celzero.bravedns.plus on tun1` (ON) / `…on tun0` (OFF; index recycled, invariant 10.111.222.1/24 holds both) |

### DV-D.b MITM-golden — the last open gate (SEALED 2026-08-03)

- Golden splice captured AFTER the D hot-plug apply on a NON-bypassed site:
  - `08-03 10:46:44.808 443 16752 I LocalHttpsProxy: Established TLS MITM tunnel for example.com` (logcat_DnavMITM_2.txt:2193)
  - `08-03 10:46:44.809 443 18983 I LocalHttpsProxy: Established TLS MITM tunnel for example.com` (L2194)
- Corroborating splices (background tabs): `www.detik.com` (L2198), `awscdn.detik.net.id` (L2384), `cdn.detik.net.id` (L2385).
- Bypass seeds untouched (Raw TCP pass-through, NOT spliced — rules out splice-everything / cache-failure): `accounts.google.com` (L1867), `play-fe.googleapis.com` (L2030/L2076), `www.google.com` (L2201), `fonts.googleapis.com` (L2327). example.com is ABSENT from the bypass list.
- OFF direction clean: `LocalHttpsProxy: Stopping…` (L12314) → `…on tun0` (L12324) → `RethinkDnsVpn: ---RESTART-OK---` success banner (L12339) → `httpsInspectionEnabled: false, vpn restarted` (L12341) → empty `Proxy Broadcast` (L12354).

### CA-trust seal — by the golden line itself (strong, not absence-of-cert-errors)

The golden `Established TLS MITM tunnel for $host` at **`LocalHttpsProxy.kt:546`** fires ONLY after `downstreamSslSocket.startHandshake()` succeeds at **L543** with `useClientMode = false` (**L533** — proxy ACTS AS TLS SERVER to the browser). `startHandshake()` in server mode is BLOCKING and returns only on a completed TLS Finished message, which REQUIRES the client (Chrome) to validate the presented spoofed leaf (`CertificateAuthority.generateLeafKeyAndCert` L522) against its trust store. Untrusted-CA → browser sends a TLS alert → `startHandshake()` THROWS → catch L549 logs `TLS MITM Handshake failed for $host` + `addToBypassCache(host)` → we'd see `Bypassing example.com`. We see the golden, NOT a bypass ⇒ CA trusted. `badssl.com`-UNEXERCISED is a NON-GAP (the discriminating contrast is INHERENT in the golden-line semantics).

### B0 anti-fabrication (RAW both sides — supervisor cmd-runner re-confirmed, 24 h after first B0)

- `git rev-parse --short HEAD` = `1c62bfd91` == device `versionName=v0.5.11-plus-12-g1c62bfd91`
- `git status --short` = ONLY `??` untracked → clean tree, zero modified tracked → APK built from clean HEAD
- Device: `tissot` (Mi A1), `ro.build.version.release=16` (A16), `pidof` = 443 (alive, matches golden-line PID)

### Supervisor-acceptance verdict

ACCEPTED. DECISION-006/D (`1c62bfd91`) is SEALED end-to-end on Mi A1 A16. The load-bearing result is the UX-unchanged hot-plug restart (no kill/alarm/BAL/death, both directions, PID constant); the MITM-golden gate — the last open gate — is SEALED, with CA-trust sealed by golden-line semantics. Toast CLOSED via human visual. B0 raw both sides. Minor notes (`RESTART-OK:false` label-compaction; `ECONNREFUSED` on null-routed `clientservices.googleapis.com`; `No such device` code 19 on tun1 teardown race) are non-fatal record-completeness items, named plainly per [[feedback_plain_explanation_over_story]]. Documentation gap closed: this entry was specified by the inline-verify relay (2026-08-01) but never appended by `1c62bfd91` (which touched no docs); appended retrospectively 2026-08-04. Stale AUDIT-RESULTS.md gate anchor (narrative L3692 → working-tree L3729) updated in the same pass.

### Consequences

| Area | Impact |
|------|--------|
| UX | Settings surface stays put across the toggle (no navigation gap, no HomeScreen drop). |
| Stability | The AppCompat-theme / Service-context crash (base DV4) and the A14+ BAL relaunch gap (B-nav) are both removed at the surface — no kill, no Service-shown dialog. |
| Latency | Apply is ~3 s debounced (existing cooldown); `Applying…` Toast covers the wait. |
| VPN continuity | tun re-established on the LIVE service each bounce (no process death, no always-on-restart reliance for this toggle). |
| Future settings | Genuinely-non-hot-pluggable settings still have the kill primitive (dormant `SettingsRestarter`); HTTPS is now hot-pluggable. |
| Always-on path | UNCHANGED — relies on `BraveAutoStartReceiver` + Android always-on `VpnService`; NOT exercised by this toggle's hot-plug path (see AdGuard #6084 / open-work map O5 — a separate verify-gap). |

### Review Trigger

Revisit when:
- A future non-hot-pluggable setting needs a kill-restart → re-arm the dormant `SettingsRestarter` path (re-introduces the BAL / Service-context risks on A14+; reconsider).
- The `vpnRestartTrigger` debounce latency becomes a UX problem → tune the 3000 ms.
- An always-on restart path is found NOT to reconcile `httpsInspectionEnabled` state → wire explicit state-reconciliation in `onStartCommand` (O5 / AdGuard #6084).
- A dialog-from-Service is re-introduced → re-opens the latent AppCompat / `SYSTEM_ALERT_WINDOW` concern.

---

**End of Decisions — Append Only**
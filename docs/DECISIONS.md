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


## DV-B nav-gap residual (A14+) — DOCUMENTED LIMITATION, not a fixable gap

**Status:** recorded 2026-08-04. The B-path fix [d28f807bb](retarget PendingIntent → CertificateSetupActivity) is **technically INEFFECTIVE on A14+**: `BACKGROUND_ACTIVITY_LAUNCH_BLOCKED` is target-agnostic post-death — even a correctly-targeted pending-intent relaunch is BAL_BLOCKed. So the B-path could not close the nav-gap on A16 (Mi A1, see `logcat_Dnav.txt`).

**Resolved in practice by DECISION-006/D (`1c62bfd91`):** the D-fix retired the entire `killProcess` → process-death → PendingIntent → BAL chain. With no kill, the process never dies (PID constant), no post-death relaunch/PI fires, so there is no BAL and **no navigation to restore** — the user stays on CertificateSetupActivity throughout (DV-D.a/c/d SEALED). The nav-gap is therefore OPERATIVELY RESOLVED by construction: the mechanism that created it no longer runs.

**Residual classification:** a documented limitation of the (now-superseded) B-path, **not** a defect of the shipped D-fix. No action required. Re-arms ONLY if a future genuinely-non-hot-pluggable setting re-introduces a kill-restart (see DECISION-006/D review-trigger above) — at which point the A14+ BAL target-agnostic block re-applies and the nav-gap re-opens. The HTTPS-inspection toggle itself is hot-pluggable, so this residual is presently inert.

---

## O5: ALWAYS-ON/REBOOT STATE-RECONCILIATION (AdGuard #6084 verify-gap)

**Raised:** 2026-08-03 (supervisor). **Closed:** 2026-08-04 (static / architectural — mechanism traced in source, anchors re-verified against working-tree HEAD `1c62bfd91`).
**Decision:** NO GAP. Always-on `httpsInspectionEnabled` state-reconciliation on cold reboot is guaranteed; AdGuard #6084 does not apply to this fork's architecture.

### Finding

Cold always-on reboot and in-process hot-plug converge at a single gate that freshly reads disk-backed / KeyStore-backed state and synchronously loads all five MITM components.

**The gate** — [BraveVPNService.kt:3729](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L3729):
`if (persistentState.httpsInspectionEnabled && com.celzero.bravedns.core.ca.CertificateAuthority.isCaInstalled())`

freshly reads `httpsInspectionEnabled` (SharedPreferences → disk-backed → survives reboot) and `isCaInstalled()` (AndroidKeyStore → survives reboot) on every `establishVpn`. True-branch body, synchronous (sequence inside one `if`):
1. `FilterEngine.loadRulesFromFile(rulesFile, cacheDir)` — [L3741](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L3741) (disk rules file)
2. `LocalHttpsProxy.proxyListener = object …` (binds `FilterEngine.match` to proxy) — [L3748](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L3748)
3. `LocalHttpsProxy.start()` — [L3784](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L3784)
4. `LocalHttpsProxy.setAllowedPackages(browserPackages)` — [L3812](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L3812)
5. `builder.setHttpProxy(ProxyInfo.buildDirectProxy("localhost", 8443))` — [L3815-3816](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L3815-L3816)
False-branch teardown (else) — [L3824-3825](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L3824-L3825): `LocalHttpsProxy.proxyListener = null; LocalHttpsProxy.stop()` — disabled / CA-missing cold reboot removes the proxy cleanly; no stale-proxy leak.

**Convergence — both reach the gate through `establishVpn` ([L2815](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L2815)):**
- Hot-plug: `vpnRestartTrigger` → `restartVpnWithNewAppConfig` ([L2748](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L2748)) → `restartVpn` ([L2753](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L2753)/[L2786](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L2786)) → `establishVpn` → gate L3729.
- Cold always-on reboot: Android restarts `VpnService` (null intent) → `onStartCommand` ([L1898](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L1898)) → `connectionMonitor.onVpnStart()` ([L1943](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L1943)) → isNewVpn branch ([L1972](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L1972)) → `restartVpn(this, opts, "startVpn")` ([L1976](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L1976)) → `establishVpn` → gate L3729.
- Auto-start (non-always-on boot): `BraveAutoStartReceiver` ([BraveAutoStartReceiver.kt:54](../app/src/full/java/com/celzero/bravedns/receiver/BraveAutoStartReceiver.kt#L54) — `VpnController.state().activationRequested && !VpnController.isAlwaysOn(context)`) → `VpnController.start` → `onStartCommand` → gate as above.

**Why `isNewVpn` is reliably `true` on cold reboot** — [ConnectionMonitor.kt:670-671](../app/src/main/java/com/celzero/bravedns/service/ConnectionMonitor.kt#L670-L671):
`val isNewVpn = !::cm.isInitialized`
`cm` is a late-init instance field of `ConnectionMonitor`, itself a fresh instance field of `BraveVPNService` ([L195](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L195)). After OS reboot the `VpnService` process is recreated → fresh `ConnectionMonitor` → `cm` uninitialized → `isNewVpn = true` → the `restartVpn`/gate branch runs. `isNewVpn = false` only on a warm in-process restart (network change, app-config update), where the tunnel was already gate-verified at establish time and is merely updated by `updateTun` ([L2192](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L2192)) — correct-by-construction, not a stale read.

### Related — firewall + manager rehydration on cold start (6th leg)

The cold `isNewVpn` branch rehydrates firewall and all rule-manager state from DB **before** `restartVpn` touches the gate. At [BraveVPNService.kt:1972-1976](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L1972-L1976) the `else { io("startVpn") { rdb.refresh(ACTION_REFRESH_AUTO) { restartVpn … } } }` block runs `rdb.refresh` with `restartVpn` as its callback. In [RefreshDatabase.kt](../app/src/main/java/com/celzero/bravedns/database/RefreshDatabase.kt) `process()`, the callback `a.cb()` runs in the **`finally`** block ([L235-236](../app/src/main/java/com/celzero/bravedns/database/RefreshDatabase.kt#L235-L236)) — i.e. strictly after the manager loads [L155-162](../app/src/main/java/com/celzero/bravedns/database/RefreshDatabase.kt#L155-L162): `FirewallManager.load()`, `IpRulesManager.load()`, `DomainRulesManager.load()`, `ProxyManager.load()`, `WireguardManager.load()`, `WgHopManager.load()`, `RpnProxyManager.load()`. So on cold start: managers rehydrate from DB → `restartVpn` → `establishVpn` → gate (MITM 5-step) → `builder.establish()`. The comment at [L1973](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L1973) ("refresh should happen before restartVpn, otherwise the new vpn will not have app, ip, domain rules") is enforced by the `finally`-callback mechanism, not just convention.

**Honesty note on the AUTO-refresh no-op guard** ([RefreshDatabase.kt:137-144](../app/src/main/java/com/celzero/bravedns/database/RefreshDatabase.kt#L137-L144)): the manager loads are skipped when `latestRefreshTime > 0 && current - latestRefreshTime < FULL_REFRESH_INTERVAL` (1 min) for an AUTO/INTERACTIVE action. This does NOT create a gap: (a) `a.cb()` lives in `finally`, so `restartVpn` fires regardless — the gate still runs; (b) on a *warm* within-interval skip the managers are already in-memory in the same process (so skipping is correct); (c) on *cold* reboot `latestRefreshTime` is a fresh `0L` instance field ([L101](../app/src/main/java/com/celzero/bravedns/database/RefreshDatabase.kt#L101)) → `latestRefreshTime > 0` is false → guard bypassed → loads run. No path skips the gate.

### Why AdGuard #6084 does NOT apply

AdGuard #6084 posits that after OS-restart `onStartCommand` might silently drop protection state unless the service explicitly re-reads preferences. Two assumptions make that real; both are false here:

1. _"onStartCommand might early-return / skip the re-read on a null intent."_ Always-on restart passes a null `Intent`. In this fork `intent` is never dereferenced before the gate: L1898→L1943→L1976 uses `intent` for nothing (only rethinkUid, pid, `VpnController.onConnectionStateChanged`, `startForegroundService`, `setVpnEnabled`, `onVpnStart`). A null intent is harmless; the gate is reached. ✓
2. _"State might be read from a stale in-memory cache that does not survive reboot."_ The gate reads `persistentState.httpsInspectionEnabled`, backed by `SharedPreferences` — Android reloads it from disk on process start. No in-memory sticky flag survives reboot. The gate is one-shot: every `restartVpn → establishVpn` fires it fresh. ✓

The warm `updateTun` path (isNewVpn=false) BY DESIGN does not re-read, but only runs when an existing gate-verified tunnel is updated in-process — there is no stale-config opportunity (the live tunnel was built by a fresh gate pass; any inter-establish `httpsInspectionEnabled` change is itself hot-pluggable via `vpnRestartTrigger`, which re-fires the gate).

### Adversarial refute (3 lenses) — ALL PASS

**LENS 1 (correctness — isNewVpn branching):** isNewVpn=true → gate fires fresh → state correct. isNewVpn=false → an existing tunnel established by a prior gate-pass is merely updated; the prior pass already scrubbed state, and inter-establish changes are hot-pluggable (re-fire the gate). No branch carries stale `httpsInspection` state. ✓

**LENS 2 (completeness — gate-body coverage):** the true-branch synchronously and unconditionally performs all 5 MITM steps inside the same `if`; any step failure is caught ([L3819](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L3819)) and logged, not silently dropped. The false-branch (else, L3824-3825) explicitly nulls the listener and stops the proxy — a disabled or CA-missing cold reboot does not leak a stale proxy. Both branches reconcile. The 6th leg (firewall/manager rehydration) runs pre-gate on the same cold path, so rule state is also fresh. ✓

**LENS 3 (mechanism — is the gate actually reached, or can onStartCommand bypass it?):**
- intent never dereferenced pre-gate → null-intent safe.
- `cm` uninitialized on every fresh process (verified at source, L670-671) → isNewVpn=true on every cold start.
- startForeground failure (L1924 / L1934) stops the service entirely (no VPN up → nothing to protect → no gap).
- the only path that skips the gate (warm `updateTun`) presupposes a gate-verified tunnel already exists.
⇒ Every path that establishes a VPN tunnel reaches the gate; every path that doesn't reach it establishes no tunnel. ✓

### Provenance

- Re-verified against working-tree HEAD `1c62bfd91`; all anchors checked by direct Read/Grep (the adversarial workflow stalled 3×6 attempts over 36 min and returned null — discarded; direct verification used per [[feedback-symptom-not-mechanism]]).
- The prior relay draft named a class `BraveAutomaticStartReceiver` — **fabricated**; the real class is `BraveAutoStartReceiver` ([L30](../app/src/full/java/com/celzero/bravedns/receiver/BraveAutoStartReceiver.kt#L30)). This entry uses the real name.
- Closes the open-work item O5 / DECISION-006/D review-trigger (above: "An always-on restart path is found NOT to reconcile …").
- Closes the AdGuard issue-tracker dossier item O5 (see `docs/REFERENCE-ADGUARD-ISSUES.md`, cluster map O1-O8; O5 = always-on/reboot).

### Limitation noted (honest)

`establishVpn` catches the `start()` exception ([L3819](../app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt#L3819)) and logs it but does NOT stop the VPN — a partial-start failure (e.g. rules unreadable, CA present) leaves the tunnel up with a proxy that failed its setup. This is a pre-existing edge posture, NOT introduced by DECISION-006/D and NOT the AdGuard #6084 concern; recorded for completeness, not an O5 gap.

---

## DECISION-007: RETIRE RPN USER-FACING UI; PLUS = FILTERS UX ACROSS ALL FLAVORS

### Rationale (2026-08-13 — supervisor directive)
The Plus tab in the canonical fork is the MITM/adblock Filters surface. The previous Phase-1 architecture that split Plus by flavor (fdroid = MITM-only, full/play/website = MITM + RPN subscription) is superseded by a unified, flavor-agnostic Filters surface. RPN as a backend-enabled proxy protocol continues to live in `Configure → VPN / Proxy` (TunnelSettingsActivity, RpnProxyManager), but the Plus-tab user-facing RPN subscription, purchase, account-state, and server-selection UI is retired/deferred. This is a product/UX decision, not an execution drift: the RPN UI removal aligns with the current working tree (HEAD `4db1b48f6` — the executed pivot RETIRE-RPN + HOIST fdroid→full). The Plus UX is rebuilt as `Filters`, not as `RPN + MITM`.

### Scope (final — applies to fdroid / full / play / website)
All four flavors expose the same Plus tab content (Filters):

| Section | Sub-features | Source file |
|---------|--------------|-------------|
| 1. HTTPS Inspection | Master toggle; CA status badge; CA Install / Re-install / Export CA; Per-app HTTPS filtering (`HttpsFilteredAppsFragment` pattern) | `styles.xml` PlusMaterialSwitchFix / `fragment_rethink_plus.xml` |
| 2. Advanced Filtering | Manage Filters (category-oriented source selector: Ads, Privacy, Social, Annoyances, Security, Language-specific, Other Filters, Custom Filters) with source enable/disable toggles + enabled-source summary (`X lists enabled • N rules`); presets EasyList, AdGuard Base, AdGuard Annoyances, Custom URL | Added to Plus design (reuses FilterEngine cosmetic/scriptlet/procedural/CSP/HTML rules automatically; per-engine rule-type toggles retired per DECISION-009 — rule types handled automatically, subtype counts kept in diagnostics) |
| 3. Exclusions | Domain exclusions; App exclusions | `fragment_rethink_plus.xml` L385+ area |

**DNS Blocklist → MITM bridge: OBSOLETE per DECISION-008.** Domain-level DNS blocking propagates automatically via `activeNetwork.getAllByName()` → Rethink DNS resolver → `0.0.0.0` sinkhole. No DNS-to-Plus bridge UI exists.

The `Plus` bottom-nav label and hero label (`plus_title` = "Plus") survive; the `rpn_title` label ("RPN") stays preserved for the RPN-feature/protocol sites elsewhere (no blanket rename). The Plus nav item (`bottom_nav_menu.xml:17`) uses `plus_title`. The layout hero (`fragment_rethink_plus.xml:55`) uses `plus_title`.

### Flavor result (all flavors = same Plus/Filters surface)
- fdroid: Plus → Filters (MITM/adblock) — no RPN UI
- full: Plus → Filters (MITM/adblock) — no RPN UI
- play: Plus → Filters (MITM/adblock) — no RPN UI; Play billing/IAB files (`InAppBillingHandler` / `BillingListener` / `SubscriptionCheckWorker` / `GooglePlaySubsAdapter` / `SubscriptionPurchaseProcessor` / etc.) are out of Plus UX scope (some retained in `play/` flavor if needed for non-Plus purchase flows; Plus surface itself is MITM-only).
- website: Plus → Filters (MITM/adblock) — no RPN UI; Stripe billing/IAB files out of Plus UX scope.

### Deleted / out of Plus UX scope (accepted as executed — NO restoration)
These files were deleted from the working tree at the executed pivot (`project_phase1c_pivot_20260809.md` — supervisor-audited APPROVE 2026-08-10):
- `RethinkPlusDashboardFragment.kt` (the RPN+MITM dashboard; full flavor)
- `ServerSelectionFragment.kt` (RPN server picker; full flavor)
- `ManageRpnPurchaseBtmSht.kt` (RPN purchase bottom sheet; full flavor)
- `RethinkPlusFragment.kt` in `play/` and `website/` flavors (RPN/purchase UI; deprecated in play/website flavors)
- `InAppBillingHandler.kt` / `SubscriptionCheckWorker.kt` / `GooglePlaySubsAdapter.kt` / `BillingListener.kt` (play billing layer; full flavor / play flavor copies — out of Plus UX; some fdroid copies kept for non-Plus billing if needed)
- `fragment_rethink_plus_premium.xml`, `fragment_server_selection.xml`, `activity_rethink_plus_dashboard.xml`, `nav_rethink_plus.xml`
The `RethinkPlusFragment.kt` in `full/` is the hoisted fdroid MITM-only fragment (from R100 `fdroid→full` rename); it remains the canonical Filters surface.

### Supersedes
- DECISION-001 L52 (`docs/DECISIONS.md`:52): the "fdroid gets free MITM/adblock; **play/website gets RPN + MITM**" flavor-gating for Plus is superseded by "All flavors get Plus/Filters; RPN UI is retired/deferred". The RPN subscription/management portion is no longer a Plus-tab feature; the MITM/adblock portion remains the canonical Plus-tab feature (unchanged from Phase-1b fdroid work, sealed by Track-D 2026-08-13).
- `docs/UNIFIED_UI_ARCHITECTURE.md` §Plus (L15, L47-89, L206-208, L396) — updated in the same session to the unified Filters surface.
- Active Phase-1c relay `project_phase1c_pivot_20260809.md` / `project_phase1c_relay1_pivot_verdict_20260810.md`: the RETIRE-RPN-UI + HOIST fdroid→full pivot is ratified by this decision.

### References (source-of-truth anchors — working tree HEAD `4db1b48f6`)
- Plus hero label: `app/src/main/res/layout/fragment_rethink_plus.xml:55` — `android:text="@string/plus_title"` (reads "Plus")
- Plus bottom-nav label: `app/src/main/res/menu/bottom_nav_menu.xml:17` — `android:title="@string/plus_title"`
- Filters sections layout: `app/src/main/res/layout/fragment_rethink_plus.xml` (line 68+: HTTPS Inspection card; blocklist bridge section; exclusions section; hero banner L36-56 with `plus_title`)
- SwitchMaterial crash fix (Track-D): [styles.xml:737-745](docs/unified_ui_architecture.md) (`PlusMaterialSwitchFix` / `PlusSwitchOverlayFix` overlay with literal `@color` values, zero `?attr` refs); 5 `MaterialSwitch` elements at [fragment_rethink_plus.xml:131/290/459/495/530](app/src/main/res/layout/fragment_rethink_plus.xml#L131)
- Filters backend machinery (unchanged): `PersistentState.httpsInspectionEnabled` (L15 in arch doc), `CertificateAuthority`, `RethinkBlocklistManager`, `FilterEngine`, `LocalHttpsProxy` — observed, toggled, NOT edited by this UX phase (DECISION-006/D hot-plug verified 2026-08-04/2026-08-03).
- Auto-restart / always-on: DECISION-006/D (`killProcess`/`PendingIntent` retired; `vpnRestartTrigger` hot-plug; no BAL, no crash) — sealed 2026-08-04; O7 `WgHop` 84/0/0; `FirewallManagerTest` 45/0/0.

### Status
**GOVERNING** — supersedes the Phase-1c plan-file `plans/zippy-snuggling-brook.md` (scope B supervisor-approved 2026-08-07) only where it conflicts with the RETIRE-RPN + HOIST fdroid→full pivot. The plan-file's scope B constraints (no engine edits, no new MITM mechanism, flavor-gated nav graph, `plus_title` relabel, build + serial-lowRAM test, device verify no-crash) remain intact and are satisfied by the Track-D fix + relabel execution. The pivot replaces "merge RPN + MITM" with "Plus = Filters (single surface, all flavors)". No new RPN UI is to be introduced. No restoration of `RethinkPlusDashboardFragment` or the billing/IAB layer into Plus is permitted.

### Wait-gate / deferred gates
- DV5 (cert-swap / MITM engine end-to-end): NOT a Phase-1c gate; remains a separately-verified, separately-closed subsystem (DECISION-006/D DV-D.b MITM golden sealed 2026-08-03; CA-trust sealed by `LocalHttpsProxy.kt:543` `useClientMode=false` semantics; `badssl-UNEXERCISED` is a documented non-gap per 2026-08-03 entry — not reopened).
- CA install: remains HUMAN-ONLY (no automation); remains out of plus-tab automation scope.
- Play/website billing/IAB layer: deferred/retired from Plus; any future billing UI is outside Phase-1c/this decision.
- RPN subscription UI: deferred; only restored if a NEW product decision supersedes DECISION-007.

### Honesty / residual notes
- `InAppBillingHandler` hub deleted (working-tree `D`) with ~26 surviving references (`BillingResponse`, `PricingPhase`, `SubscriptionPurchaseProcessor`, etc. across `play/` and `website/`) — these references are currently masked by the `google-services` build gap (play/website APK assembly depends on `google-services` which is missing in the fdroid build); if `google-services` is restored, these dangling references become active compile-breaks. Closing them (delete or redirect) is deferred to a follow-on executor relay (NOT bundled in this supervisor-audited audit); the supervisor's audit notes them for future tracking.
- `RpnWinProxyDetailsActivity.kt` (win proxy detail screen) still exists in `full/` — it is not part of the Plus UI; remains out of scope.
- `RethinkPlusViewModel.kt` (full / play / website copies) and `SubscriptionStateMachineV2.kt` / `SubscriptionStatus.kt` (main) survive — these are backend subscription-state machinery, not Plus-UI surface; no action required unless a future decision revives subscription UI.
- The `action_switch_to_rethinkPlusDashboardFragment` nav action (and `rethinkPlusDashboardFragment` nav destination in `app/src/full/res/navigation/app_navigation.xml`) remains in the graph; since `ServerSelectionFragment` is deleted from tree, the nav dest resolves to a non-existent class. This is a known residual: the nav graph was not fully cleaned at the pivot; the action is unreachable via Plus-tab navigation (since Plus lands on RethinkPlusFragment), but references in `HomeScreenActivity.kt` L819/865/888 (back press / highlight) and `play/website` RethinkPlusFragment L294 (`FragmentHostActivity` pop-back) still reference it. Cleaning the nav graph (removing dead actions/dests or redirecting to `RethinkPlusFragment`) is deferred to executor relay #2b / #2c (not included in this supervisor audit).

---

---

## DECISION-008: DNS POLICY OWNERSHIP — TWO INDEPENDENT SUBSYSTEMS

**Date:** 2026-08-15
**Status:** FINAL
**Deciders:** User + Supervisor
**Origin:** Phase-1D-A3 MITM DNS/Policy Path Audit + A2 STOP-P2 (empirical device proof on Xiaomi Mi A1 A16)

### Context

Phase-1D-A3 was commissioned to answer: **Does LocalHttpsProxy inherit Rethink DNS blocklist policy, or bypass it?**

The initial static-source hypothesis (Phase-1D-A3 relay #1) claimed "LIVE-B" — that `resolveHostSecurely()` bypasses the VPN DNS resolver and connects to real IPs. **This was conclusively disproven by empirical device testing on Mi A1 A16 (serial 3595381c0804).**

Simultaneously, **A2 STOP-P2** proved the manual DNS→MITM bridge (`syncBlocklistToAdblockRules`) is **unimplementable** — selected tag 54 existed, but the implementation expected raw text unavailable from Rethink's compiled DNS artifacts.

### Decision

**Original Rethink DNS subsystem is the sole source-of-truth for DNS blocklist selection and DNS blocking policy.**

**HTTPS Inspection does not expose a separate DNS bridge UI.**

**Rethink DNS blocklists are NOT FilterEngine source material.**

**Advanced Filter Sources are a separate independent subsystem** supplying FilterEngine with dedicated filter syntax (EasyList, AdGuard, Custom URL).

**Legacy `syncBlocklistToAdblockRules()` is obsolete pending source cleanup.**

### Two Independent Systems (Locked Final Architecture)

```
A. ORIGINAL RETHINK DNS POLICY

Configure
  └── DNS
      └── Rethink Blocklists
          └── Original Rethink DNS policy
              ├── domain-level DNS blocking
              ├── sinkhole behavior
              └── original Rethink DNS list selection
```

```
B. ADVANCED FILTERING (NEXT/PLANNED — independent subsystem)

Plus
  └── Advanced Filtering
      └── Filter Sources (Manage Sources)
          ├── EasyList
          ├── AdGuard Base
          ├── AdGuard Annoyances
          └── Custom URL
              ↓
          FilterEngine
              ├── network HTTP rules
              ├── cosmetic rules
              ├── scriptlets
              ├── procedural rules
              ├── CSP rules
              └── HTML filtering rules
```

**There is NO documented source-flow: `Rethink DNS blocklists → FilterEngine`**

### Implementation Consequences

| Area | Change |
|------|--------|
| **Plus Tab UI** | NO DNS blocklist section. Sections: HTTPS Inspection, Advanced Filtering, Exclusions only. |
| **Configure → DNS** | Remains sole DNS blocklist manager (RethinkBlocklistFragment, LocalBlocklistsBottomSheet). |
| **Plus UX** | No manual DNS→HTTPS bridge, no "Sync Now", no DNS list selector. |
| **FilterEngine** | Receives rules ONLY from dedicated Advanced Filter Sources (EasyList/AdGuard/Custom URL), NOT from Rethink DNS blocklists. |
| **Legacy syncBlocklistToAdblockRules** | OBSOLETE pending source cleanup. Not repurposed for advanced filters. |
| **adblock_rules.txt** | OBSOLETE — no current production role. |

### A2 STOP-P2 (Preserved)

**A2 result (2026-08-14):** `syncBlocklistToAdblockRules()` was implemented but failed — selected tag 54 existed, but implementation expected raw rule text unavailable from Rethink's compiled DNS artifacts. **STOP-P2 issued.** Bridge not completed.

### A3 Sinkhole Inheritance (Preserved)

**A3 result (2026-08-15):** Phase-1D-A3 live device audit (Xiaomi Mi A1 A16) proved domain-level blocking propagates automatically via DNS sinkhole inheritance:
- `resolveHostSecurely()` uses `ConnectivityManager.activeNetwork.getAllByName(host)`
- Active network = VPN interface → DNS resolved by Rethink DNS engine
- Blocked domains → `0.0.0.0` sinkhole → `ECONNREFUSED` → `502 Bad Gateway`
- Allowed domains → real IPs → `VpnController.protectSocket()` → TLS MITM / Raw TCP

**Manual DNS raw-list bridge is obsolete** — not retained for cosmetic/scriptlet extraction.

### Advanced Filter Source Ownership (NEXT/PLANNED)

Advanced Filter Sources are a **new independent subsystem** NOT yet implemented:

```
FilterSource (entity/model)
├── id
├── name
├── url
├── enabled
├── update metadata
├── parsed count
├── unsupported count
├── invalid count
└── subtype counts

Storage:
├── Room → metadata only
└── Filesystem → raw source content → staged compiled content

Pipeline:
download.tmp → validate → parse → compatibility stats → compile staged output → sanity checks → atomic swap → FilterEngine reload
Failure: retain last-known-good active source
```

**Status: NEXT/PLANNED — not implemented.**

### Verification

- [x] A2 STOP-P2 preserved: bridge failed, stopped, not repurposed
- [x] A3 sinkhole inheritance verified: 6 live device tests on Mi A1 A16
- [x] Full logcat captured (6,118 lines)
- [x] Zero source edits required (architecture already correct)
- [x] HEAD `4db1b48f6` intact; commit=FORBIDDEN; push=FORBIDDEN

### Review Trigger

Revisit only if:
- Android API changes break `activeNetwork.getAllByName()` VPN routing behavior
- A new use case requires domain-level blocking inside MITM *before* DNS resolution
- Upstream (if ever) adds competing MITM stack with different DNS integration
- Advanced Filter Source Foundation (Phase-1D-B) is implemented

---

## DECISION-009: ADVANCED FILTER UX OWNERSHIP — SOURCE/CATEGORY-ORIENTED, NOT ENGINE-CAPABILITY-ORIENTED

**Date:** 2026-08-15
**Status:** FINAL
**Deciders:** User + Supervisor
**Origin:** Phase-1D-DOC4 UX taxonomy re-seal; supersedes the legacy Plus-tab "capability toggle" framing

### Context

The pre-DECISION-008 Plus-tab architecture exposed a flat list of rule-family
"feature" toggles to normal users:

```
Advanced Filtering
├── Cosmetic CSS injection toggle
├── Scriptlet injection toggle
├── Procedural cosmetic toggle
├── CSP (Content Security Policy) toggle
└── HTML filtering toggle
```

These are **FilterEngine implementation capabilities** (parser rule-type subtypes), not
intuitive user-facing filter features. Presenting them as normal user toggles causes:

1. **Confusion** — users do not know whether to enable "Cosmetic CSS" for adblocking;
2. **Mis-aligned mental model** — users think they are choosing techniques rather than
   filter *content*;
3. **Category ambiguity** — a single source such as "AdGuard Annoyances" spans multiple
   rule subtypes, so per-subtype toggles are the wrong decomposition.

### Decision

```
Advanced Filtering
│
├── enabled filter/source summary
└── Manage Filters
        ├── Ads
        ├── Privacy
        ├── Social
        ├── Annoyances
        ├── Security
        ├── Language-specific
        ├── Other Filters
        └── Custom Filters
```

Plus is **source/category-oriented**, **not** engine-capability-oriented:

1. Normal users select *what filter content* to enable — filter **sources** grouped by
   purpose (Ads, Privacy, Social, Annoyances, Security, Language-specific, Other Filters,
   Custom Filters).
2. Users toggle **sources**, never individual engine rule-type techniques.
3. **Network / Cosmetic / Scriptlet / Procedural / CSP / HTML handling is automatic**
   inside `FilterEngine`: it inspects compiled rule subtypes and applies each supported
   injection path without a per-type user toggle.
4. **Subtype support and per-source rule-type counts remain available in diagnostics
   only** (per-source detail screen), so users can audit coverage without gating engine
   behavior.

> A `category` attached to a `FilterSource` is **organizational metadata**. It does
> **not** restrict the parser — `FilterSourceCompiler` auto-detects every rule subtype
> present in the fetched list regardless of category.

### What changed (documentation only)

| Before (retired) | After |
|------------------|-------|
| "Cosmetic CSS toggle" (user toggle) | Automatic; subtype count in diagnostics |
| "Scriptlet toggle" (user toggle) | Automatic; subtype count in diagnostics |
| "Procedural toggle" (user toggle) | Automatic; subtype count in diagnostics |
| "CSP toggle" (user toggle) | Automatic; subtype count in diagnostics |
| "HTML Filtering toggle" (user toggle) | Automatic; subtype count in diagnostics |
| "Manage Filter Sources" label | "Manage Filters" (category-oriented) |
| — | Category taxonomy: Ads / Privacy / Social / Annoyances / Security / Language-specific / Other Filters / Custom Filters |

### What did NOT change

- **DECISION-008 remains intact:** Original Rethink DNS policy and Advanced Filter
  Sources are two independent subsystems. There is **NO documented source-flow**:
  `Rethink DNS blocklists → FilterEngine`. The legacy `syncBlocklistToAdblockRules()`
  bridge is **obsolete pending source cleanup** (A2 STOP-P2) — not repurposed.
- **FilterEngine sub-engines are unchanged:** `FilterEngine` (network rules),
  `CosmeticFilter`, `ProceduralFilter`, `ScriptletFilter`, `CspInjector`, `HtmlFilter`
  all remain; only their *UX exposure* changes (automatic, not user-toggled).
- **`adblock_rules.txt` ownership** remains EXCLUSIVE to Advanced Filter Source
  compilation (Documented in docs/DECISIONS.md §DECISION-008 L675,
  docs/PLAN-FILTER-SOURCE-MANAGER.md §5 NOTE + §Phase 3).
- **No code behavior changes.** This is a UX/documentation decision only.

### Implementation consequence (Phase-1D-B)

- 1D-B5 (Manage Filters UI) must render the category taxonomy and per-source
  diagnostics; it must **not** render per-engine-capability toggles as normal controls.
- `FilterSourcesBottomSheet` / `FilterSourceActivity` expose `Manage Filters`; each
  source item shows enable/disable (source-level) + subtype badge diagnostics.
- Any residual `switchCosmeticFilter` / `switchProceduralFilter` / `switchScriptletFilter`
  / `switchCspFiltering` / `switchHtmlFiltering` flags in the proxy layer are
  **engine-level / internal** and surfaced only via diagnostics, never as Plus-tab UI.

### Status: GOVERNING

---

## DECISION-010: HTTPS INSPECTION ELIGIBILITY AND BYPASS POLICY

**Date:** 2026-08-15
**Status:** GOVERNING
**Deciders:** User + Supervisor
**Origin:** PHASE-1D-DOC5 baseline `5ec97f93` — documentation-only relay; no implementation committed.

### Context

HTTPS Inspection requires a per-connection eligibility decision before the MITM proxy
accepts a `CONNECT` tunnel. The decision interacts with:

- whether the originating application is a browser the project wishes to inspect,
- whether the user has explicitly opted an application in or out,
- whether the connection targets a domain or (application, port) tuple that must
  never be intercepted for safety or operational reasons,
- whether the body of an already-established MITM session is large enough to
  justify downgrading from full modification to stream-only pass-through.

Rethink adapts concepts from the ADBye project
(`BypassManager` / `uidAllowlist` / domain-suffix and port bypass /
resource-threshold logic). ADBye is a **design reference only**:

```
ADBye BypassManager
        ↓
adapt concepts
        ↓
Rethink policy architecture

NOT:
copy class unchanged
```

ADBye targets PCAPdroid / JNI capture; Rethink uses a userspace HTTP proxy
injected via `VpnService.Builder.setHttpProxy`. The transport assumptions are
different, so any code reuse requires architectural adaptation, not direct copy.

### Decision

Eligibility for HTTPS Inspection is resolved by `InspectionPolicyEngine` before
the proxy accepts or rejects a `CONNECT` tunnel. The engine is the **sole
authority** for MITM/bypass decisions; no other component (UI, VPN service,
connection tracker) may bypass it.

#### Donor architecture

- ADBye `BypassManager` is a design/source reference demonstrating hard app
  bypass, HTTPS exemptions, domain-suffix bypass, protected ports, and resource
  thresholds — all concepts retained in Rethink's policy model.
- ADBye implementation details (PCAPdroid/JNI, `uidAllowlist` mixing names and
  UIDs, automatic conversion of domain bypasses to AdGuard exception rules, and
  global port bypass) are **rejected**; see §4, §6, §7 for each rejection.

#### Known browser default policy

A **maintained hardcoded package registry** identifies known browsers. An entry
is added only after the maintainer has verified both the production package ID
and the branding correctness of the application.

```
KNOWN BROWSER
├── maintained hardcoded package registry
├── if installed → HTTPS Inspection ON by default
└── primary / default fast deterministic path
```

Package names such as `Chrome/Brave/Firefox/Edge` are cited as examples;
authoritative package IDs are recorded in the `InspectionPolicyEngine` registry
(or its backing data) at implementation time and verified against the Google
Play production signatures.

**Do not invent or hardcode package names in DECISION-010 without that
verification.**

#### Dynamic browser fallback

Browsers not in the known registry are handled via dynamic discovery. This
fallback is **best-effort** only: results depend on Android **package visibility**
(API 30+), and apps without appropriate `<queries>` declarations in their
manifest may not appear even when installed. Known-registry browsers are not
affected by an empty discovery result.

```
DYNAMIC BROWSER DISCOVERY
├── fallback for browser not present in known registry
├── detected through Android browser capability signals
└── OFF by default until user enables it
```

**Primary discovery signal:** query `PackageManager` for activities handling
`ACTION_VIEW` + `CATEGORY_BROWSABLE` with an `https://` URI scheme.

**Supplementary signals** (where available, in addition to the primary query):
- `ROLE_BROWSER` role (Android API 29+ via `RoleManager`)
- `CATEGORY_APP_BROWSER` (secondary signal only; Android documentation warns
  against using this category alone as a primary intent-filter key — it is
  used here only to supplement the `ACTION_VIEW` + `CATEGORY_BROWSABLE` query)

Only browsers are eligible for dynamic discovery. Non-browser applications are
never auto-discovered for HTTPS inspection.

#### Other applications

```
OTHER APPLICATIONS
├── OFF by default
└── explicit user opt-in required
```

#### Policy precedence

Decisions are resolved in the following order. **Hard bypass always beats user
inclusion.**

```
connection
    ↓
SYSTEM HARD BYPASS?
    YES → BYPASS_SYSTEM
    ↓ NO

USER APP EXCLUSION?
    YES → BYPASS_USER
    ↓ NO

PROTECTED DOMAIN?
    YES → BYPASS_DOMAIN
    ↓ NO

PROTECTED APP + PORT?
    YES → BYPASS_APP_PORT
    ↓ NO

KNOWN BROWSER INSTALLED?
    YES → MITM_KNOWN_BROWSER
    ↓ NO

USER EXPLICIT APP INCLUDE?
    YES → MITM_USER_APP
    ↓ NO

DYNAMICALLY DETECTED BROWSER
(ACTION_VIEW + CATEGORY_BROWSABLE + https;
 CATEGORY_APP_BROWSER/ROLE_BROWSER supplementary)
AND USER ENABLED?
    YES → MITM_DYNAMIC_BROWSER
    ↓ NO

BYPASS (no-match default)
```

#### Package vs UID model

The ADBye `uidAllowlist` pattern mixes package names and UID strings in one
collection. Rethink **rejects this ambiguity**:

```
Set<String> uidAllowlist           ← REJECTED (ambiguous, ADBye pattern)

Rethink target model:

protectedPackages : package names

protectedUids :
    only if implementation proves a UID-level policy is actually needed
```

A collection of package-name strings must never be named `uidAllowlist`. If a
UID-level override is eventually needed (e.g., shared-UID application groups),
`protectedUids` is introduced as a separate typed set with its own resolution
rules — not by conflating the two namespaces.

#### System hard bypass

System hard bypass is an **internal safety layer for critical services**. It
operates outside the ordinary user-editable exclusion list.

Conceptual examples inherited from donor research (Play Services / Play Store,
GSF, IMS) are research context only. The final Rethink registry **must be
audited and validated** before implementation; entries must not be copied blindly
from ADBye or any other source.

```
SYSTEM HARD BYPASS
≠ normal user exclusion
```

Hard-bypass entries are not surfaced in the standard exclusions UI and are not
removable by the user without root or developer intervention.

#### Domain bypass is not a FilterEngine whitelist

HTTPS MITM bypass and adblock exception rules are **mechanistically distinct**:

```
HTTPS MITM bypass       ≠    adblock exception rule
```

A domain that is HTTPS-bypassed must **not** automatically generate an AdGuard
exception rule (`@@||domain^`). ADBye currently couples these two concerns by
exporting its domain bypasses as AdGuard exception rules; Rethink must **not
inherit that coupling**.

```
InspectionPolicy
    → whether TLS is MITMed or bypassed

FilterEngine
    → whether request/content is blocked or modified
```

These subsystems operate independently. A domain may be HTTPS-bypassed while
still being blocked at the DNS or FilterEngine level, and vice versa.

#### App + port protection

ADBye uses global port bypass (`port 5228 → bypass every application`). Rethink
rejects global port trust:

```
port 5228 → bypass every application     ← REJECTED (global, unscoped)
```

Rethink policy is **scoped to (application, destination port)**:

```
critical push service
+   5228 / 5229 / 5230
→  protected (app + port) connection
```

The tuple `(protectedPackage, destinationPort)` is the minimum resolvable unit
for port-level protection. Global port bypass is not permitted because it trusts
all applications on that port regardless of origin.

#### Resource protection

Resource protection governs behavior after a MITM tunnel is already established
and a large body is detected in-flight.

```
TLS MITM already established
        ↓
large body detected
        ↓
DO NOT switch to raw TCP
        ↓
degrade expensive body processing
        ↓
STREAM_ONLY
```

| Body size | Decision | Processing |
|-----------|----------|------------|
| Small (within rewrite-size limit) | `MITM_FULL` | Full parsing, DOM injection, modification |
| Large (exceeds rewrite-size or DOM-processing limit) | `MITM_STREAM_ONLY` | TLS proxy remains; no whole-body buffering; no DOM/Jsoup processing |

**Locking the following as prohibited:**

```
MITM TLS
→ threshold reached
→ reconnect same flow as raw TLS
```

Switching from MITM to raw TCP mid-flow is forbidden. Once the proxy has
accepted the `CONNECT` and completed the TLS handshake with both client and
upstream, the TLS tunnel is maintained for the lifetime of the connection. Only
the *processing depth* is degraded. This preserves the integrity of the MITM
session and prevents observable reconnect artifacts that some clients would
treat as a MITM downgrade attack.

#### Decision / result model

Conceptual decision reasons used for diagnostics and device evidence. Exact
enumeration names may differ in implementation.

```
BYPASS_SYSTEM
BYPASS_USER
BYPASS_DOMAIN
BYPASS_APP_PORT

MITM_KNOWN_BROWSER
MITM_USER_APP
MITM_DYNAMIC_BROWSER

MITM_STREAM_ONLY
```

These reason codes are diagnostic identifiers, not public API contracts.

#### Target component architecture

**Pre-MITM decision — `InspectionPolicyEngine`**

```
                  CONNECTION
                       ↓
               app / UID / host / port
                       ↓
            InspectionPolicyEngine
             /                 \
            ↓                   ↓
        BYPASS                  MITM
                                      ↓
                               TLS interception
                                      ↓
                                 HTTP response
                                      ↓
                            ResourceProtectionPolicy
                                 /             \
                                ↓               ↓
                          MITM_FULL       MITM_STREAM_ONLY
                                \             /
                                 ↓           ↓
                           FilterEngine
                   (where applicable)
```

`InspectionPolicyEngine` (and its three sub-policies) resolves BYPASS vs MITM
using only data available before `CONNECT`: package/UID, host, port. It has no
visibility into response body size and must not attempt to use it.

`ResourceProtectionPolicy` acts only after MITM is established and the HTTP
response is available. It downgrades `MITM_FULL` → `MITM_STREAM_ONLY` based on
body size. It never produces a BYPASS decision. `MITM_STREAM_ONLY` is a
processing mode, not an initial eligibility result.

Responsibility boundaries:

| Component | Scope |
|-----------|-------|
| `HttpsInspectionPolicy` | App eligibility: known registry, dynamic discovery, user includes, user exclusions |
| `SystemBypassPolicy` | Internal safety: protected packages, optional UIDs, protected domains, protected (app, port) tuples |
| `ResourceProtectionPolicy` | Post-MITM only: body-size thresholds; downgrades MITM_FULL → MITM_STREAM_ONLY. Never produces BYPASS decisions. |
| `InspectionPolicyEngine` | Pre-MITM orchestrator: resolves precedence, returns (BYPASS / MITM, reason). Must not consult body size. |

`InspectionPolicyEngine` is called before the proxy accepts `CONNECT`. It does
not modify `FilterEngine` state; it only decides MITM vs bypass. FilterEngine
receives flows that have already passed the MITM gate.

#### UX architecture consequence

The Plus tab HTTPS Inspection screen exposes the following user controls:

```
PLUS
├── HTTPS Inspection
│   ├── master toggle
│   ├── CA status badge (✅ INSTALLED / ⚠️ NOT INSTALLED)
│   ├── Install / Re-install CA
│   ├── Save / Export CA
│   └── Apps
│       ├── Known browsers      default ON
│       ├── Detected browsers   default OFF if not in known registry
│       └── Other apps          default OFF, explicit opt-in
│
├── Advanced Filtering
│   └── Manage Filters
│
└── Exclusions
    ├── App exclusions (user-editable, BYPASS_USER)
    └── Domain exclusions (user-editable, BYPASS_DOMAIN)
```

System hard bypass entries are **internal safety only**. They are not surfaced
as ordinary user-editable exclusions and are not mixed with `BYPASS_USER`
entries.

Detected browsers (dynamic fallback) appear in the Apps list but start in the
OFF state. The user flips each one to ON individually; there is no bulk-enable
for the entire detected-browser category.

#### Roadmap

```
B1  Data / storage foundation              SEALED  √
B2  Downloader + validation                PENDING (blocked by DECISION-010)
B3  Parser / compiler + diagnostics        PENDING
B4  Atomic activation + rollback           PENDING

B4.5 HTTPS Inspection Policy               ← DOC5 / DECISION-010 governs this
     ├── known-browser registry
     ├── dynamic discovery fallback
     ├── per-app opt-in
     ├── user exclusions (app + domain)
     ├── hard system bypass
     ├── protected domain / app-port policy
     └── resource protection

B5  Manage Filters + Exclusions UI         PENDING
B6  Full physical-device verification      PENDING
```

DECISION-010 must be sealed **before** B2 implementation starts. B4.5
implementation must be scoped to the architecture documented here; no component
crosses the boundary defined in Target component architecture without a new
decision.

### What changed (documentation only)

- DECISION-010 appended to `docs/DECISIONS.md`.
- `docs/ARCHITECTURE-MAPPING.md` updated to insert `InspectionPolicyEngine` boundary
  before the LocalHttpsProxy MITM section.
- `docs/UNIFIED_UI_ARCHITECTURE.md` updated to reflect known-browser default-ON /
  detected-browser default-OFF / other-app default-OFF apps list under Plus →
  HTTPS Inspection.
- `docs/PLAN-FILTER-SOURCE-MANAGER.md` updated to reference B4.5 as the HTTPS
  inspection policy phase in the roadmap.
- `docs/PLAN-HTTPS-INSPECTION-POLICY.md` created as the dedicated authority
  document for this subsystem, preventing PLAN-FILTER-SOURCE-MANAGER from
  becoming a dumping ground for HTTPS policy details.

### What did NOT change

- No Kotlin, Java, or XML source files were modified.
- No Room migration was added.
- No `gradle` command was run.
- No device verification was performed.
- No commit or push occurred.
- B2 (Downloader + Validation) was not started.
- B4.5 implementation was not started.
- `gradle.properties` was not touched.

### Implementation consequence

DECISION-010 is an architecture lock for Phase-1D-B4.5. B2 and B4.5
implementations must each include a boundary-review step verifying that their
code respects the precedence order, the package-name model, the system-bypass
isolation, the domain-vs-FilterEngine separation, the scoped port policy, and
the resource-protection rules documented here.

### Review Trigger

Re-open this decision if any of the following occur before implementation:

- A new application type (non-browser) is proposed for default-ON inspection.
- A request is made to expose system hard-bypass entries in the user-facing
  exclusions UI.
- A request is made to merge domain MITM bypass with FilterEngine exception rules.
- An implementation proposes switching from MITM to raw TCP mid-flow for any
  reason.
- Any change to the locked semantics: large body → MITM_STREAM_ONLY → never
  raw-TCP mid-flow.

**Threshold note:** Numerical byte thresholds (rewrite-size, DOM-processing) are
implementation-tunable by B4.5 from device and performance evidence without
reopening DECISION-010. Adjusted values must preserve the locked semantics above.

---

**End of Decisions — Append Only**

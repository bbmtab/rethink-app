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

**End of Decisions — Append Only**
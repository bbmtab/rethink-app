# HTTPS Inspection Policy — Authority Document

**Status:** Governing architecture lock — full preset-driven policy not yet implemented (2026-08-27)
**Authority:** `docs/DECISIONS.md` § DECISION-010
**Implementation baseline:** `phase1d-advanced-filter` @ `ca797a1d179b060b602c26664814111b640ffd8a`
**Scope:** This document owns the HTTPS Inspection eligibility, bypass, and
resource-protection policy layer. No other planning document should contain
HTTPS policy details; they live here.

---

## 1. Purpose

`InspectionPolicyEngine` is the **sole decision authority** for whether a
connection is intercepted by the MITM proxy (`LocalHttpsProxy`) or bypassed. It
resolves MITM vs bypass before any `CONNECT` tunnel is accepted. This document
locks the architecture for that layer **before** implementation begins (B4.5).

---

## 2. Donor architecture

**Design reference:** ADBye `BypassManager` and its associated logic:

- hard application bypass (UID-level blocking of MITM),
- HTTPS exemption list (domains never intercepted),
- domain-suffix bypass (e.g. `*.corp.example.com`),
- protected destination ports,
- body-size / resource-threshold logic (bypass or downgrade when payload is large).

**Adaptation rule:**

```
ADBye BypassManager
        ↓
adapt concepts only
        ↓
Rethink policy architecture

NOT:
copy class unchanged
```

ADBye operates inside PCAPdroid's JNI packet-capture layer. Rethink's MITM
proxy runs as a userspace HTTP proxy injected via `VpnService.Builder.setHttpProxy`.
Transport assumptions differ: ADBye sees raw packets at the kernel boundary; Rethink
sees `CONNECT` requests at an application-level proxy. Any reuse of ADBye concepts
must be re-implemented against the proxy abstraction, not ported.

**Specific ADBye patterns explicitly REJECTED for Rethink** (see §3–§8 below):

| ADBye pattern | Rethink decision |
|---|---|
| `uidAllowlist` mixing package names and UID strings | REJECTED — use typed `protectedPackages` + optional separate `protectedUids` |
| Automatic export of domain bypasses as AdGuard `@@` exception rules | REJECTED — MITM bypass and FilterEngine exception are independent |
| Global port bypass (`port 5228 → bypass all apps`) | REJECTED — use scoped `(app, port)` tuples instead |
| `>5 MB` → flow bypass, `>20 MB` → download bypass | REJECTED — use MITM degradation (stream-only), never raw-TCP mid-flow switch |

---

## 3. Default application policy

Applications are tiered by how they interact with HTTPS Inspection.

### 3.1 Known browser — default ON

A **maintained hardcoded package registry** identifies browsers that the project
has explicitly audited and approved for default inspection.

```
KNOWN BROWSER
├── maintained hardcoded package registry
├── if installed → HTTPS Inspection ON by default
└── primary / default fast deterministic path
```

**Registry rules:**

- An entry is added only after the maintainer has verified:
  - the production package ID (e.g. `com.android.chrome`) against Google Play,
  - the branding correctness of the application (it must be a browser, not a
    Chromium-based app with no UI),
  - that enabling HTTPS inspection against it does not break core functionality
    in ways the user cannot recover from.
- The registry is **code-level data** (e.g. a typed `Set<String>` or `List<Browsers>`),
  not a user-editable preference.
- Examples cited here (`Chrome`, `Brave`, `Firefox`, `Edge`) are **illustrative
  only**; authoritative package IDs are recorded in the registry at implementation
  time. Do not invent or copy package names without verification.

**Behavior:** Any application whose package name matches the known-browser
registry and is currently installed is placed in the MITM-eligible set with no
further user action required.

### 3.2 Dynamic browser fallback — default OFF

Applications not in the known registry but discoverable via Android browser
capability signals:

```
DYNAMIC BROWSER DISCOVERY
├── fallback for browser not present in known registry
├── detected through Android browser capability signals
└── OFF by default until user enables it
```

**Discovery mechanism (best-effort — no guarantee of completeness):**

The primary signal is an intent query for activities that handle
`ACTION_VIEW` + `CATEGORY_BROWSABLE` with an `https://` URI scheme.
Additional signals that may supplement the primary query (where available):
- `ROLE_BROWSER` (Android API 29+ / `RoleManager`)
- `CATEGORY_APP_BROWSER` (secondary signal only; see rejection note below)

> `CATEGORY_APP_BROWSER` **alone** is REJECTED as the primary discovery signal.
> Android's documentation explicitly warns that this category is not intended as
> a primary intent-filter key for action resolution. It is used here only as a
> supplementary signal alongside an `ACTION_VIEW` + `CATEGORY_BROWSABLE` query.

**Best-effort characteristics:**

- Dynamic discovery results depend on Android **package visibility** (API 30+).
  Apps without a matching `<queries>` block in their manifest will receive an
  empty result set from `queryIntentActivities()` even when browsers are
  installed — this is a framework limitation, not a bug.
- As a best-effort fallback: a non-empty or empty result set does not block MITM
  for known-registry browsers. Missing dynamic results simply mean fewer choices
  appear in the Plus-tab Apps list; the user is not prevented from using HTTPS
  Inspection for known-registry browsers.
- Discovered browsers appear in the Plus-tab Apps list but start in the OFF state.
- The user must flip each detected browser to ON individually; no bulk-enable.
- If a browser later graduates to the known registry, its stored user preference
  (ON/OFF) is respected; it is not force-ON.

### 3.3 Other (non-browser) applications — default OFF, explicit opt-in

```
OTHER APPLICATIONS
├── OFF by default
└── explicit user opt-in required
```

No non-browser application is auto-discovered or auto-included. The user must
manually add an application via the Plus-tab Apps list.

---

## 4. Policy precedence

`InspectionPolicyEngine` resolves decisions in the following order. **Hard bypass
entries always beat user inclusion; a user cannot override a system hard bypass.**

```
connection
    ↓
1. SYSTEM HARD BYPASS?           YES → BYPASS_SYSTEM       (highest)
2. USER APP EXCLUSION?           YES → BYPASS_USER
3. PROTECTED DOMAIN?             YES → BYPASS_DOMAIN
4. PROTECTED APP + PORT?         YES → BYPASS_APP_PORT
5. KNOWN BROWSER INSTALLED?      YES → MITM_KNOWN_BROWSER
6. USER EXPLICIT APP INCLUDE?    YES → MITM_USER_APP
7. DYNAMIC BROWSER + ENABLED?    YES → MITM_DYNAMIC_BROWSER
8. (no match)                             BYPASS             (lowest)
```

**Contract:** Step N is only evaluated if steps 1..N-1 all resolved NO. The
first YES wins.

---

## 5. System hard bypass

### 5.1 Definition

System hard bypass is an **internal safety layer** for critical Android system
services whose TLS sessions must never be intercepted. It operates entirely
outside the ordinary user-editable exclusion list.

```
SYSTEM HARD BYPASS
≠ normal user exclusion
```

### 5.2 Registry governance

The initial registry is populated from **research context only** — ADBye's
donor data (Play Services / Play Store, GSF, IMS) is a starting point for
investigation, not an authoritative list.

The final registry **must be audited and validated** before implementation:

- each entry must be tied to a concrete operational rationale (e.g., "breaking
  this would prevent Play Store from completing IAP receipts"),
- each entry must be verified against the current Android API surface (some
  legacy ADBye entries may no longer apply),
- the audit must be recorded before code is written.

### 5.3 UI exposure

Hard-bypass entries are **not surfaced** in the Plus-tab Exclusions screen. They
are internal-only. A user cannot remove or add entries without root or
developer-mode intervention.

---

## 6. Domain bypass vs FilterEngine separation

HTTPS MITM bypass and adblock exception rules solve **different problems** at
**different layers**. They must not be coupled.

```
HTTPS MITM bypass          ≠    adblock exception rule
```

| Concern | Subsystem | Effect |
|---------|-----------|--------|
| TLS interception decision | `InspectionPolicyEngine` | Proxy accepts or skips `CONNECT` |
| Request/body modification | `FilterEngine` | Blocks, allows, or cosmetic-injects content |

**Concrete prohibition:**

> A domain that is HTTPS-bypassed must **not** automatically generate an
> AdGuard exception rule (e.g. `@@||domain^`).

**Independence examples:**

- A domain may be HTTPS-bypassed (browser not inspected) while still being
  DNS-blocklisted (e.g. `doubleclick.net` → `0.0.0.0`).
- A domain may pass MITM (no bypass entry) but have all its cosmetic rules
  disabled by FilterEngine for other reasons.
- User exclusions at the domain level apply to MITM bypass only; they do not
  imply any FilterEngine rule generation.

**Anti-pattern to reject from ADBye:** ADBye currently exports its domain
bypasses as AdGuard exception rules. Rethink must **not inherit that coupling**;
the two subsystem outputs are maintained independently.

---

## 7. App + port protection

### 7.1 Rejection of global port bypass

```
port 5228 → bypass every application     ← REJECTED
```

Global port trust is insecure: any application (malware, data exfil initiator)
can reach Google's FCM / push infrastructure on those ports and be trusted
implicitly. Rethink rejects this.

### 7.2 Scoped policy

Port-level protection is scoped to `(protectedPackage, destinationPort)`:

```
critical push service
+   5228 / 5229 / 5230
→  protected (app + port) connection
```

The minimum resolvable unit is `(application/UID, destination port)`. Trust is
granted only to the originating application the registry authorizes, not to all
applications reaching that port.

**Implementation implication:** `SystemBypassPolicy` stores tuples of
`(protectedPackage, Set<Int> protectedPorts)`, not a flat port allowlist.

---

## 8. Resource protection

### 8.1 Purpose

After `CONNECT` is accepted and both TLS handshakes (client↔proxy, proxy↔upstream)
complete, the proxy has committed to the MITM session. Resource protection
governs what happens when the body of an in-flight response is very large.

### 8.2 Locked behavior

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

| Condition | Decision | Proxy behavior |
|-----------|----------|----------------|
| Body within rewrite-size / DOM limit | `MITM_FULL` | Full buffering, parsing, DOM injection, content modification |
| Body exceeds limit | `MITM_STREAM_ONLY` | No whole-body buffering; no DOM/Jsoup processing; pass bytes through as a transparent TLS tunnel |

### 8.3 Prohibited

```
MITM TLS
→ threshold reached
→ reconnect same flow as raw TLS
```

**Why this is prohibited:**

- Switching from MITM to raw TCP mid-flow requires tearing down the existing
  TLS session and opening a new one — observable to the client as a TLS
  renegotiation or connection reset.
- Some clients (particularly browsers with certificate pinning) interpret a
  mid-flow proxy disconnect as a MITM downgrade attack and abort the request
  with a hard error rather than a soft retry.
- The `CONNECT` has already been established; breaking it mid-use provides no
  security benefit (the proxy is already trusted by the client at this point).

### 8.4 Threshold ownership

Exact byte thresholds (rewrite-size limit, DOM-processing limit) are a
**B4.5 HTTPS Inspection / Resource Protection implementation parameter**, not
an architecture decision. They are recorded here as tunable constants.

**Locked semantics (cannot change without reopening DECISION-010):**

- Large body → `MITM_STREAM_ONLY`
- `MITM_STREAM_ONLY` → never raw-TCP mid-flow conversion

**Numerical thresholds:**

Initial threshold values are **NOT LOCKED** by DECISION-010. Values may be
adjusted from device and performance evidence without reopening the
architectural decision, provided the locked semantics above continue to hold.

---

## 9. Decision reason model

Conceptual reason codes resolved by `InspectionPolicyEngine`. Used for
diagnostics and device evidence; exact enumeration names may differ in
implementation.

```
BYPASS_SYSTEM           — system hard bypass (step 1)
BYPASS_USER             — user explicit exclusion (step 2)
BYPASS_DOMAIN           — protected domain (step 3)
BYPASS_APP_PORT         — protected (app, port) tuple (step 4)

MITM_KNOWN_BROWSER      — matched known registry (step 5)
MITM_USER_APP           — user explicitly included non-browser app (step 6)
MITM_DYNAMIC_BROWSER    — dynamic browser discovery + user enabled (step 7)

MITM_STREAM_ONLY        — MITM established but body degraded to stream-only
```

---

## 10. Target component architecture

### 10.1 Data flow

```
PRE-MITM DECISION
═════════════════

         CONNECTION
              ↓
       app / UID / host / port
              ↓
    InspectionPolicyEngine
       /            \
      ↓              ↓
   BYPASS           MITM
                               ↓
                          TLS interception
                               ↓
                         HTTP response
                               ↓

POST-MITM RESOURCE DECISION
════════════════════════════

                  ResourceProtectionPolicy
                       /                \
                      ↓                  ↓
                MITM_FULL         MITM_STREAM_ONLY
                      \                /
                       ↓              ↓
                    FilterEngine
            (where applicable)
```

**Key separation:**

- `InspectionPolicyEngine` (and its three sub-policies) resolves BYPASS vs MITM
  **before** any `CONNECT` tunnel is accepted. It has no visibility into response
  body size and must not attempt to account for it.
- `ResourceProtectionPolicy` acts **only after MITM is established** and the HTTP
  response (or its headers) are available. It downgrades `MITM_FULL` → `MITM_STREAM_ONLY`
  based on body size. It does not produce a BYPASS decision.
- `MITM_STREAM_ONLY` is a **post-MITM processing mode**, not an initial CONNECT
  eligibility result. It appears in the reason code list (§9) for diagnostics
  purposes only.

### 10.2 Component responsibilities

| Component | Responsibility | Data source |
|-----------|---------------|-------------|
| `HttpsInspectionPolicy` | App eligibility: known registry, dynamic discovery, user includes/excludes | `HttpsInspectionPolicyRepository` (or equivalent) backed by `PersistentState` + registry |
| `SystemBypassPolicy` | Internal safety bypass: protected packages, optional UIDs, domains, (app, port) tuples | Internal registry; **not** user-editable |
| `ResourceProtectionPolicy` | Post-MITM body-size thresholds; downgrades MITM_FULL → MITM_STREAM_ONLY once response body is available. Does not produce BYPASS decisions. | Tunable constants (see §8.4) |
| `InspectionPolicyEngine` | Orchestrator: evaluates precedence, returns `(decision, reason)` | Calls the three policies above |

### 10.3 Invariants

- `InspectionPolicyEngine` is the **only** component that may produce a BYPASS
  vs MITM decision before `CONNECT`. No caller may shortcut around it.
- FilterEngine is **never called** in a bypass context; it receives connections
  only after MITM is accepted.
- The precedence order (§4) is immutable without reopening DECISION-010.

---

## 11. Roadmap position and implementation status

```
B1    Data / storage foundation              SEALED
B2    Downloader + validation                SEALED
B3    Parser / compiler + diagnostics        SEALED
B4    Atomic activation + rollback           SEALED
B4.5  HTTPS Inspection Policy                OPEN — governing design exists; full policy is not implemented
B5    Manage Filters + custom source UI      IMPLEMENTED — add/edit/remove/enable/disable flows exist
B6    End-to-end verification                BLOCKED — controlled website filtering has not passed
```

DECISION-010 and this document remain the governing architecture for B4.5.
That architecture status must not be confused with implementation completion.

At the implementation baseline:

* No production `InspectionPolicyEngine` implements the complete documented
  pre-CONNECT policy.
* `LocalHttpsProxy` uses a partial hardcoded hybrid of persistent bypass seeds
  and runtime state.
* An empty allowed-package set makes all packages eligible.
* Dynamic TLS failures are not persisted across initialization.
* The complete preset-driven application, domain, and port policy has not been
  wired into the runtime.

Custom filter-source management and its transaction path are implemented, with
102/102 targeted JUnit tests passing. The tracked-file closure baseline is
`ca797a1d179b060b602c26664814111b640ffd8a`. That progress does not seal B4.5.

Physical-device source-management checks passed, but browser access failed with
HTTPS Inspection ON after a custom filter was added and recovered when HTTPS
Inspection was disabled for that browser. A controlled real-website
OFF → ON → OFF custom-filter test has not passed. B4.5, B6, and
release-candidate readiness therefore remain open.

---

## 12. Deferred compatibility verification

**Status:** TODO — remaining tests run only after Windows Gradle/R.jar and
device-test infrastructure is stable.

AdGuard compatibility data provides regression scenarios, not automatic Rethink
policy. External exclusions require independent Rethink verification before
production use.

### 12.1 Locked baseline

- Verified known browsers are inspected by default.
- Dynamic browsers remain OFF until enabled by the user.
- General/non-browser applications remain OFF by default.
- Unknown or unresolved applications default to BYPASS.
- System and user exclusions beat every MITM inclusion.
- TLS failure must not create or persist a dynamic bypass.
- HTTPS MITM policy and QUIC policy are separate.
- `quic_pkg_exclusions.txt` means QUIC is allowed for matching packages; it does
  not mean block QUIC or force TCP.

References:

- https://github.com/AdguardTeam/CompatibilityIssues
- https://github.com/AdguardTeam/AdguardForAndroid/issues/5497
- https://github.com/AdguardTeam/AdguardForAndroid/issues/5617
- https://github.com/AdguardTeam/AdguardForAndroid/issues/5689
- https://github.com/AdguardTeam/AdguardForAndroid/issues/6076

### 12.2 Implementation and verification TODO

- [x] Slice-1R3 Robolectric verification: focused tests 1/1 + 1/1 and complete
  `LocalHttpsProxyTest` 3/3 PASS.
- [ ] Rerun `InspectionPolicyEngineTest` 8/8 after Windows Gradle/R.jar
  infrastructure stabilizes; the last attempt did not reach the test phase.
- [ ] Load verified app/domain presets into one immutable policy snapshot.
- [ ] Invoke `InspectionPolicyEngine` before accepting CONNECT.
- [ ] Preserve package-scoped rules such as
  `domain.example$app=com.example.app`.
- [ ] Implement QUIC policy independently from HTTPS BYPASS/MITM.
- [ ] Atomically reload the complete policy generation.
- [ ] Log decisions and reasons without logging decrypted payloads.

### 12.3 Deferred compatibility tests

- [ ] General applications such as WeChat receive default BYPASS.
- [ ] Package exclusion beats browser or user inclusion.
- [ ] Package-scoped domain bypass does not affect another app.
- [ ] QUIC allowance does not automatically create HTTPS bypass.
- [ ] HTTPS bypass does not automatically allow QUIC.
- [ ] Handshake failure does not mutate or persist policy.
- [ ] Chrome positive control reaches MITM with the correct reason.
- [ ] WeChat Mini Program works under default BYPASS (issue #5689).
- [ ] WeChat/AliExpress media works with verified QUIC policy (issue #5497).
- [ ] Google Search/Gboard succeeds on the first attempt (issue #5617).
- [ ] Chrome MoQT/WebTransport is not broken by QUIC policy (issue #6076).
- [ ] Restart preserves package, domain, QUIC, decision, and reason state.

For each failure, isolate ordinary filtering rules, tracking transformations,
DNS, QUIC, upstream proxy, and HTTPS interception separately before adding a
default exclusion.

### Remaining preset intake gates

#### Accepted N3 data baseline

N3D bundles only `app/src/main/assets/https_inspection/ssl_allow_list.txt`. It is the byte-identical generated Android exclusion artifact from `AdguardTeam/HttpsExclusions` commit `5d3e4ca4b79958e28e30c8cc48a9e0be95c813b8`, with SHA-256 `cf2699dbd93b9a3c6a94e1927bd58803ffbaa61ef2a814df36858b37652ad856`.

The bundled asset is limited to protected-domain policy input. N3D did not add Android loading, dependency injection, runtime policy publication, UI integration, QUIC handling, or changes to the default treatment of applications. The consolidated policy suite passed 32 tests with zero failures and zero errors.

#### Remaining inputs are not authorized for bundling

The following external or attached inputs remain research material and must not be copied into application assets by a later implementation slice without satisfying the corresponding gate:

- `pkg_exclusions.txt`: the AdGuard meaning is exclusion from VPN routing and filtering, while the current Rethink policy model describes HTTPS-inspection bypass. These are not equivalent scopes.
- `filter_https_traffic_inclusions.txt`: its AdGuard meaning enables HTTPS filtering by default for listed browsers. Rethink requires every known-browser entry to be independently verified before it can become a default-ON policy entry.
- `filter_https_traffic_inclusions_problematic_devices.txt`: device-specific selection criteria and fallback behavior have not been modeled.
- `filter_https_traffic_exclusions.json`: its schema, selection precedence, and Android integration contract have not been accepted.
- `quic_pkg_exclusions.txt`: QUIC allow, block, bypass, and fallback behavior are not represented by the current HTTPS inspection decision model.
- `ssl_block_list.txt`: its authoritative source, redistribution terms, and whitelist-mode semantics have not been accepted.

`AdguardTeam/CompatibilityIssues` may be used as a research reference, but its repository did not expose a `LICENSE`, `COPYING`, `NOTICE`, or package-level license declaration during the 2026-08-31 audit. Project governance therefore does not authorize redistributing those raw lists. A transformed attachment is not an acceptable substitute for an authorized raw upstream artifact.

#### First-party registry TODO

- [ ] Audit every proposed system hard-bypass UID and package against an authoritative platform source and record a concrete operational rationale. Do not copy third-party VPN or OEM package exclusions merely because they appear in another product.
- [ ] Build the known-browser registry as first-party maintained data. Verify the production package identifier, browser identity, and essential HTTPS-inspection functionality for each entry.
- [ ] Start the browser audit with Chrome, Brave, Firefox, and Edge, while recording device evidence and unresolved compatibility failures separately.
- [ ] Resolve the existing Edge package candidate discrepancy during an authorized runtime-integration slice: `com.microsoft.empath` is not the verified Microsoft Edge package identifier; the verified Google Play identifier is `com.microsoft.emmx`.
- [ ] Define how problematic-device browser entries are selected before creating any corresponding asset.
- [ ] Design and approve QUIC policy independently before adding a QUIC package registry.
- [ ] Define the schema and precedence for `filter_https_traffic_exclusions.json` before parsing or bundling it.
- [ ] Create each remaining asset only after its provenance, redistribution authorization, semantics, parser contract, and focused tests are accepted.
- [ ] Connect the preset loader to Android assets and runtime policy publication only after all mandatory registries required by the selected policy mode exist.

#### Locked defaults while these TODOs remain open

- General non-browser applications remain default OFF for HTTPS inspection unless explicitly included by the user.
- Dynamically detected browsers remain default OFF unless explicitly enabled by the user.
- A known-browser entry may become default ON only after its individual audit is accepted.
- No matching rule continues to produce `BYPASS_DEFAULT`.
- Missing registries must not be hidden by creating empty placeholder assets.
- The pure-JVM loader and parser implementation remains disconnected from Android runtime policy publication.

## 13. Document ownership

| Document | Role |
|----------|------|
| `docs/DECISIONS.md` (DECISION-010) | Governing decision; defines what is locked |
| `docs/PLAN-HTTPS-INSPECTION-POLICY.md` (this file) | Detailed policy specification; implementation guide for B4.5 |
| `docs/ARCHITECTURE-MAPPING.md` | Component boundary diagram and precedence order |
| `docs/UNIFIED_UI_ARCHITECTURE.md` | Plus-tab UX consequences |
| `docs/PLAN-FILTER-SOURCE-MANAGER.md` | Roadmap reference only (B4.5 entry); not policy details |

No other `docs/` file should contain HTTPS inspection policy specifics. If
future work requires policy additions, they are appended to this file and
DECISION-010, not scattered across other planning documents.

---

**End of HTTPS Inspection Policy Authority Document**
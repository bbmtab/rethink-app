# HTTPS Inspection Policy — Authority Document

**Status:** Governing architecture + N4E/N9/N10 canonical runtime closure sealed (2026-09-09); preset provenance/redistribution divergence is a release blocker; direct RULE20 device execution is deferred because no natural UDP/443 control fixture was available
**Authority:** `docs/DECISIONS.md` § DECISION-010 plus the 2026-09-04 N4E, 2026-09-07 N9, and 2026-09-09 N10 addenda
**Canonical branch baseline:** `phase1d-advanced-filter` @ `63bc8593df3efa83b51f68146b1216f4320f8e44`
**Canonical sealed implementation:** N4E policy/runtime, N9 per-app policy state/UI and transport enforcement, and N10 local-proxy domain/resolved-IP firewall authority with blocked Network Logs persistence are committed. Focused tests, GHA, and physical-device gates are closed. RULE20 direct device execution remains a deferred evidence item, not a source/GHA failure.
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

### 3.2 Dynamic browser fallback — default ON unless explicitly excluded

Applications not in the maintained known-browser registry but detected through
Android browser capability signals are MITM-eligible by default.

```text
DYNAMIC BROWSER DISCOVERY
├── fallback for a browser not present in the known registry
├── capability-based Android package discovery
├── if detected and not user-excluded → HTTPS Inspection ON
└── user exclusion is the explicit escape hatch for compatibility failures
```

**Implemented capability model:**

The dynamic classifier accepts a package when either:

1. Android reports the package through the browser-app capability query
   (`ACTION_MAIN` + `CATEGORY_APP_BROWSER`), or
2. the package independently resolves both HTTP and HTTPS browser probes.

The package-manager queries are intentionally not restricted by
`PackageManager.MATCH_DEFAULT_ONLY`. N4E real-device evidence proved that
`MATCH_DEFAULT_ONLY` incorrectly removed a valid controlled dynamic-browser
fixture from the `CATEGORY_APP_BROWSER` result set.

Dynamic discovery remains best-effort and is subject to Android package
visibility and the package's declared intent capabilities.

**Default behavior:**

* A discovered dynamic browser is MITM-eligible immediately.
* There is no required per-browser "enabled" registry for dynamic browsers.
* There is no dedicated dynamic-browser opt-in storage requirement.
* User app exclusion has higher precedence than browser classification.
* If a browser breaks under MITM, the user turns HTTPS Inspection OFF for that
  app, which records/uses the app exclusion path.
* Turning the app back ON removes that exclusion and restores normal browser
  MITM eligibility.
* Classification tier and exclusion state are independent: moving between
  dynamic and known-browser classification must not erase an explicit user
  exclusion.

The final dedicated HTTPS-app management UI must reuse Rethink's existing
installed-app inventory (package/UID/name/icon/install/remove refresh). It must
not create a second installed-app database solely for HTTPS Inspection.

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

```text
connection
    ↓
1. SYSTEM HARD BYPASS?           YES → BYPASS_SYSTEM
2. USER APP EXCLUSION?           YES → BYPASS_USER
3. COMPATIBILITY EXCLUSION?      YES → BYPASS_COMPATIBILITY
4. PROTECTED DOMAIN?             YES → BYPASS_DOMAIN
   DOMAIN MODE REJECTS HOST?     YES → BYPASS_DOMAIN_MODE
5. PROTECTED APP + PORT?         YES → BYPASS_APP_PORT
6. KNOWN BROWSER INSTALLED?      YES → MITM_KNOWN_BROWSER
7. USER EXPLICIT APP INCLUDE?    YES → MITM_USER_APP
8. DYNAMIC BROWSER DETECTED?     YES → MITM_DYNAMIC_BROWSER
9. NO MATCH                            → BYPASS_DEFAULT
```

**Contract:** Each later branch is evaluated only if every earlier branch did
not resolve the connection. The first applicable result wins.

`USER APP EXCLUSION` therefore remains the user compatibility escape hatch for
known and dynamic browsers. `COMPATIBILITY EXCLUSION` is a separate project
policy layer and also wins before protected-domain and MITM eligibility logic.

The compatibility and domain-mode rows above are a documentation precision
sync with the already accepted canonical runtime; they do not introduce new
behavior in this documentation slice.

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

```text
BYPASS_SYSTEM           — system hard bypass
BYPASS_USER             — user explicit application exclusion
BYPASS_COMPATIBILITY    — compatibility exclusion
BYPASS_DOMAIN           — protected global/package-scoped domain
BYPASS_DOMAIN_MODE      — current domain mode does not permit MITM for host
BYPASS_APP_PORT         — protected (app, destination-port) tuple
BYPASS_DEFAULT          — no MITM eligibility rule matched

MITM_KNOWN_BROWSER      — matched maintained known-browser registry
MITM_USER_APP           — user explicitly included non-browser application
MITM_DYNAMIC_BROWSER    — capability-detected dynamic browser

MITM_STREAM_ONLY        — post-MITM processing mode; large body degraded to
                          streaming without raw-TCP mid-flow conversion
```

Do not rename Kotlin enums.

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
| `HttpsInspectionPolicy` | App eligibility: known registry, dynamic capability discovery, user includes, and user exclusions; browsers are default-ON unless excluded | `HttpsInspectionPolicyRepository` (or equivalent) backed by `PersistentState` + registry |
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

```text
B1    Data / storage foundation              SEALED
B2    Downloader + validation                SEALED
B3    Parser / compiler + diagnostics        SEALED
B4    Atomic activation + rollback           SEALED
B4.5  HTTPS Inspection Policy                SEALED — canonical policy/per-app/transport/firewall integration at 63bc8593d
B5    Manage Filters + custom source UI      IMPLEMENTED
B6    End-to-end verification                SEALED FOR CURRENT PHASE-1D ACCEPTANCE
                                              — N4E policy/device closed
                                              — N9 repeated-toggle hot apply device-closed
                                              — N10 proxy firewall + blocked-log persistence device-closed
                                              — RULE20 direct execution deferred for lack of natural UDP/443 stimulus
```

Canonical repository integration is complete. The N4E policy/runtime stack,
N9 per-app management and transport stack, repeated-toggle hot-apply repair,
and N10 local-proxy firewall authority/log persistence are committed on
`phase1d-advanced-filter` at
`63bc8593df3efa83b51f68146b1216f4320f8e44`.

The remaining device item is narrower: direct execution evidence for RULE20
requires a real application flow that naturally reaches Rethink as UDP/443
while the same flow is MITM-eligible. Multiple installed control fixtures did
not produce such traffic. This is recorded as deferred verification, not as an
implementation failure.

DECISION-010 plus its 2026-09-04 and 2026-09-07 addenda remain the governing
architecture and product contract for B4.5.

### N4E implementation/runtime closure

The canonical branch contains the preset-driven HTTPS eligibility and bypass
runtime used by the N4E physical-device acceptance tests.

The verified runtime includes:

* `InspectionPolicyEngine` as the pre-CONNECT MITM/BYPASS authority;
* immutable policy snapshot construction from the accepted preset inputs;
* known-browser and dynamic-browser eligibility;
* dynamic browser capability discovery without
  `PackageManager.MATCH_DEFAULT_ONLY`;
* compatibility bypass;
* protected-domain policy input;
* user/system exclusion precedence;
* decision/reason diagnostics;
* automatic installed-app inventory reconciliation for Android package
  lifecycle changes.

The controlled Mi A1 / Android 16 matrix proved:

```text id="p5otkq"
general application
→ BYPASS_DEFAULT
→ raw TCP
→ HTTP 200
→ public certificate

compatibility fixture
→ BYPASS_COMPATIBILITY
→ raw TCP
→ HTTP 200
→ public certificate

dynamic browser fixture
→ MITM_DYNAMIC_BROWSER
→ TLS MITM
→ HTTP 200
→ RethinkDNS Root CA
```

Dynamic-browser discovery and package-lifecycle inventory defects found during
N4E were repaired and retested.

The final clean policy snapshot after fixture removal and one Protection
OFF → ON restart reported:

```text id="3rswjb"
systemPackages=4
systemUids=2
compatibility=201
protectedDomains=4308
knownBrowsers=147
dynamicBrowsers=0
```

N4E repository integration is complete. Remaining work is limited to separately
scoped compatibility/resource follow-up and the deferred direct-device RULE20
execution evidence described below.

The N4E/N9 implementation is canonical in the history leading to the current
N10 head, `63bc8593df3efa83b51f68146b1216f4320f8e44`.
The temporary GHA workflow used to verify the transport implementation remains
temp-branch-only and is intentionally absent from the canonical tree.

---

## 12. Deferred compatibility and follow-up verification

**Status:** N4E core policy/runtime/device acceptance is sealed. The items in
this section are follow-up compatibility, QUIC, UI, and hardening work; they do
not reopen the completed N4E runtime/device gate.

AdGuard compatibility data provides regression scenarios, not automatic Rethink
policy. External exclusions require independent Rethink verification before
production use.

### 12.1 Locked baseline

- Verified known browsers are inspected by default unless explicitly excluded.
- Capability-detected dynamic browsers are inspected by default unless explicitly excluded.
- Dynamic browsers do not require a separate user-enabled set.
- General/non-browser applications remain OFF by default unless explicitly included.
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

### 12.2 Remaining follow-up work

The core N4E runtime integration items that were formerly listed here are now
implemented and device-verified: policy snapshot publication,
`InspectionPolicyEngine` pre-CONNECT resolution, dynamic-browser capability
discovery, compatibility bypass, protected-domain input, and decision/reason
logging.

#### N9 canonical transport + hot-apply closure — 2026-09-07

Canonical commit:

`a3c6a00b3c2f4e8b35b2b72bcf0c059cea957f82`
(`feat(https): enforce inspection transport policy`)

The committed N9 transport boundary is:

```text
firestack connection metadata
        ↓
ordinary FirewallRuleset evaluation
        ↓
existing firewall rule grounded?
        ├── YES → existing firewall block wins unchanged
        └── NO
             ↓
active immutable HTTPS policy snapshot available?
        ├── NO  → ordinary firewall result unchanged
        └── YES
             ↓
UDP destination port 443?
        ├── NO  → ordinary firewall result unchanged
        └── YES
             ↓
InspectionTransportPolicy
        ↓
InspectionPolicyEngine
        ├── BYPASS → ordinary firewall result unchanged
        └── MITM
             ↓
          RULE20
          stall UDP/443
             ↓
          application may retry over TCP
             ↓
          LocalHttpsProxy inspection path
```

The transport rule is package-agnostic. It does not hardcode Chrome, YouTube,
or the donor `quic_pkg_exclusions.txt` package set. Its source of truth is the
same immutable policy snapshot currently installed in `LocalHttpsProxy`.

`RULE20` is a dedicated `stall` firewall result labelled HTTPS Inspection. It is
not RULE6 and does not change ordinary global UDP-block semantics.

The repeated per-app policy restart event was also repaired. Consecutive
ON→OFF and OFF→ON changes can mutate the same preference key, so equal
`MutableStateFlow<String>` values previously risked conflation. App-policy
restart reasons now carry a monotonically increasing process-local sequence:

```text
httpsInspectionAppPolicy[1]: <key>
httpsInspectionAppPolicy[2]: <key>
...
```

The existing 3000 ms debounce remains unchanged. Multiple rapid preference
changes may still coalesce to one final VPN rebuild because only the latest
persisted policy state needs activation.

Verification:

```text
GHA run                 = 34046896707
InspectionTransportPolicyTest = 11/11 PASS
fdroidFullDebug compile = PASS
fdroidFullDebug assemble = PASS
artifact                = 9993434969

Brave ON→OFF:
  unique app-policy restart event
  public certificate after exclusion

Brave OFF→ON:
  second distinct app-policy restart event
  no manual Protection restart
  RethinkDNS Root CA restored
  MITM_KNOWN_BROWSER + TLS MITM tunnel restored
```

Direct RULE20 execution is not yet device-proven. The following natural-control
fixtures produced zero qualifying UDP/443 flows during the captured control
windows:

```text
Chrome          0
YouTube         0
YouTube Music   0
Google Play     0
```

Additional donor-preset QUIC fixtures were not installed on the device. No
RULE20 failure was observed because no real UDP/443 control stimulus reached
the rule gate.

#### N10 local-proxy firewall authority closure — 2026-09-08/09

Canonical commits:

```text
N10A  d6d3602880193e4f6250ce01c7b0eac46380faac
      fix(firewall): gate local proxy before upstream

N10B  24b7a292a96ff230345992fce942af5d12c36d79
      fix(firewall): enforce resolved ip rules before upstream

N10C  63bc8593df3efa83b51f68146b1216f4320f8e44
      fix(firewall): persist local proxy block logs
```

N10 does not change `InspectionPolicyEngine` precedence. It closes the
firewall-authority gap created by the direct physical-network socket used by
`LocalHttpsProxy`:

1. CONNECT and plain HTTP requests call the existing
   `BraveVPNService.firewall()` authority with original client identity,
   hostname and port before proxy-side DNS, socket protection, upstream
   connect, CONNECT 200, request inspection, or MITM.
2. A direct upstream destination is resolved without protecting or connecting
   the socket. The same authority is called again with `destinationIp` before
   `VpnController.protectSocket()` and `Socket.connect()`.
3. A configured upstream HTTP proxy keeps the target unresolved because that
   proxy owns DNS; no fabricated destination IP is supplied.
4. A block returns `HTTP/1.1 403 Forbidden`, closes the flow, and records the
   decision through the existing connection-log pipeline.
5. A missing evaluator or ordinary evaluation exception fails closed;
   `CancellationException` is rethrown.

The implementation deliberately reuses `firewall()` and does not invoke
`processFirewallRequest()` or directly consult `DomainRulesManager` /
`IpRulesManager` from the proxy layer.

Verification closure:

```text
LocalHttpsProxyTest at N10A = 10/10 PASS
LocalHttpsProxyTest at N10B/N10C = 11/11 PASS

N10A GHA run = 34174825194, success
N10B GHA run = 34198839548, success
N10C GHA run = 34225352812, success

N10A device = Chrome-specific example.com domain block before DNS/upstream;
                rule deleted; connectivity restored
N10B device = Chrome-specific 1.1.1.1 IP block after resolution and before
                protect/connect/MITM; rule deleted; connectivity restored
N10C device = Chrome-specific 1.0.0.1:0 IP block persisted as a Network Logs
                TCP/443 row with reason "IP / Port (App)"; rule deleted;
                blocked row retained; connectivity restored
```

No temporary firewall rule remains on the device. No merge commit or force push
was used during the three canonical fast-forward gates.

Remaining work is intentionally narrower:

* [x] Implement policy-driven HTTPS transport enforcement for MITM-eligible
  UDP/443 using the active immutable policy snapshot (`InspectionTransportPolicy`
  + dedicated `FirewallRuleset.RULE20`).
* [ ] Capture direct real-device RULE20 execution when a natural fixture emits
  qualifying UDP/443 traffic. Current status: deferred because all available
  installed control fixtures produced zero UDP/443.
* [ ] Complete the remaining external compatibility scenarios in §12.3.
* [ ] Verify package-scoped domain/app edge cases not exercised by the controlled
  N4E three-fixture matrix.
* [x] Complete the dedicated per-app HTTPS management UI by reusing Rethink's
  existing installed-app inventory (N9).
* [ ] Continue first-party browser-registry maintenance and production package
  identity verification.
* [ ] Tune post-MITM resource thresholds from device/performance evidence while
  preserving `MITM_STREAM_ONLY` semantics.

Project sequencing after these items is locked by DECISION-012: finish all
MITM/adblock work and release verification first, integrate and push the
shipping state to `main`, complete the intended release gate, and only then
create the upstream-maintenance bridge on a separate branch. The bridge is not
part of B4.5 and must not be mixed into this policy closure.

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

#### Canonical governance divergence: N12 partial remediation completed

Commit `82004b55eb195ae8b4aa0a65cb685a1f4a250423` originally
bundled four inputs beyond the `ssl_allow_list.txt` intake authorized by
DECISION-011.

N12 partially remediates that divergence:

* `ssl_block_list.txt` was renamed to
  `https_inspection_inclusions.txt`, given explicit inspection—not
  blocking—semantics, and retained with four documented parent domains;
* `filter_https_traffic_exclusions.json` was independently reviewed for obsolete
  package identities, pruned from 201 to 191 entries, and re-pinned at SHA-256
  `a204969c31dbae110a2a19aebab9cdbd99dc6f1c3a8d6263102d23ded313ac05`;
* `pkg_exclusions.txt` and `filter_https_traffic_inclusions.txt` remain unchanged;
* `quic_pkg_exclusions.txt` and
  `filter_https_traffic_inclusions_problematic_devices.txt` remain unbundled.

Package identifiers are factual identifiers and may be maintained by Rethink
without permission from the affected application publisher. The remaining gate
is ownership and maintainability of the curated policy decisions: current
package identity, reproducible rationale, precedence, device evidence where
needed, and accurate NOTICE/provenance documentation.

The functional N4E runtime/device closure remains valid. N12 does not yet make
the complete preset set release-clean.

The policy constraints for these inputs remain:

- `pkg_exclusions.txt`: the AdGuard meaning is exclusion from VPN routing and filtering, while the current Rethink policy model describes HTTPS-inspection bypass. These are not equivalent scopes.
- `filter_https_traffic_inclusions.txt`: its AdGuard meaning enables HTTPS filtering by default for listed browsers. Rethink requires every known-browser entry to be independently verified before it can become a default-ON policy entry.
- `filter_https_traffic_inclusions_problematic_devices.txt`: not currently
  bundled; device-specific selection criteria and fallback behavior have not
  been modeled.
- `filter_https_traffic_exclusions.json`: runtime loading and precedence already
  exist. N12B removed ten obsolete identities and retained 191 unchanged
  survivors. First-party rationale, maintenance metadata, weak-entry device
  verification, and current-package review remain open.
- `quic_pkg_exclusions.txt`: not currently bundled; remains donor
  research/compatibility data and is
  not the runtime authority for inspection force-TCP. Canonical N9 transport
  enforcement is package-agnostic and derives its result from
  `InspectionPolicyEngine`. Any future package-specific QUIC exception registry
  requires separate provenance, compatibility, and policy approval.
- `https_inspection_inclusions.txt`: contains four broad parent domains that are
  candidates for HTTPS inspection in include-only domain mode. It is not a DNS
  or firewall blocklist. Compatibility fallout must be handled through package
  exclusions or package-scoped domain bypass, not by silently narrowing the
  global advertising/analytics inspection candidates.

`AdguardTeam/CompatibilityIssues` may be used as a research reference, but its repository did not expose a `LICENSE`, `COPYING`, `NOTICE`, or package-level license declaration during the 2026-08-31 audit. Project governance therefore does not authorize redistributing those raw lists. A transformed attachment is not an acceptable substitute for an authorized raw upstream artifact.

#### First-party registry TODO

- [x] Rename `ssl_block_list.txt` to
  `https_inspection_inclusions.txt`, document its inspection semantics, retain
  all four broad domains, and pin focused asset tests.
- [x] Remove the ten independently identified obsolete package identities from
  `filter_https_traffic_exclusions.json`.
- [ ] Audit every proposed system hard-bypass UID and package against an authoritative platform source and record a concrete operational rationale. Do not copy third-party VPN or OEM package exclusions merely because they appear in another product.
- [ ] Build the known-browser registry as first-party maintained data. Verify the production package identifier, browser identity, and essential HTTPS-inspection functionality for each entry.
- [ ] Start the browser audit with Chrome, Brave, Firefox, and Edge, while recording device evidence and unresolved compatibility failures separately.
- [ ] Resolve the existing Edge package candidate discrepancy during an authorized runtime-integration slice: `com.microsoft.empath` is not the verified Microsoft Edge package identifier; the verified Google Play identifier is `com.microsoft.emmx`.
- [ ] Define how problematic-device browser entries are selected before creating any corresponding asset.
- [ ] Do not add a package-specific QUIC registry merely to implement HTTPS
  inspection force-TCP; that enforcement is already policy-driven. Add a
  package registry only if separately verified compatibility exceptions require
  one, with its own provenance and policy decision.
- [ ] Define the schema and precedence for `filter_https_traffic_exclusions.json` before parsing or bundling it.
- [ ] Create each remaining asset only after its provenance, redistribution authorization, semantics, parser contract, and focused tests are accepted.
- [ ] Confirm or implement the production/UI selector for `ONLY_INCLUDED`.
- [ ] Device-test the weak or contradictory compatibility queue before removing
  additional entries.
- [ ] Test current `org.cryptomator`; do not inherit the obsolete
  `org.cryptomator.beta` decision.
- [ ] Add first-party reason, verification date/version, and maintenance status
  for retained compatibility entries.
- [ ] Correct NOTICE/provenance without claiming that documentation itself grants
  redistribution rights.
- [ ] Test Google Search Lite, Bing News, and Yandex Search across both embedded
  browser and native API flows.
- [ ] Preserve ColorOS update/download routing exclusions unless device evidence
  proves that removal is safe.

#### Locked defaults for remaining follow-up work

* General non-browser applications remain default OFF for HTTPS inspection
  unless explicitly included by the user.
* Known browsers are default ON unless an earlier system/user exclusion wins.
* Capability-detected dynamic browsers are default ON unless an earlier
  system/user exclusion wins.
* Dynamic browsers do not require a separate per-package enabled registry.
* No matching eligibility rule continues to produce `BYPASS_DEFAULT`.
* Missing or unsupported registries must not be hidden by creating fabricated
  placeholder policy.
* Android runtime policy publication is implemented; future preset additions
  must preserve the same immutable-snapshot and precedence contract.

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

# Windscribe Keygen — Deferred (UI temporarily unreachable)

## TL;DR

The Windscribe Pro integration is **not safe to ship**. Until the keygen
problem below is fixed, the only valid state is "users cannot reach this
flow." This branch (`windscribe-deferred`, off `main`) applies one kill-switch
that hides the entry button, no-ops the launch path, and makes the activity
self-finish — so neither risk outcome below can be reached by a real user.

This is a deliberate, reversible disable, **not** a fix. It exists so the
mitm-adblock-parity work can stabilize without a known-bad WireGuard path
sitting reachable in the meantime. Pick this back up after that work lands.

## What's disabled and where

One switch, defined once, read at every reachability point:

- **Switch:** `WindscribeFeatureGate.TEMPORARILY_DISABLED`
  (`app/src/full/java/com/celzero/bravedns/ui/activity/WindscribeFeatureGate.kt`).
- **Entry point (the only in-app launcher):** the Windscribe FAB in
  `WgMainActivity.kt` (~line 364) — hidden (`View.GONE`) when disabled, and
  its `openWindscribeLoginActivity()` (~line 426) no-ops early.
- **Activity self-finish (deep-link defense in depth):**
  `WindscribeLoginActivity.onCreate()` (~line 47) calls `finish()` and
  returns before any UI setup when disabled.

The activity is declared in `app/src/full/AndroidManifest.xml` (~line 110)
with **no intent-filter and no `android:exported` attribute**, so its default
exported state is `false` — there is no deep link to begin with. The
`onCreate` self-finish is belt-and-suspenders against any future regression
that adds an intent-filter or flips export. It is **not** relied on as the
sole gate; the FAB + launch no-op are.

All Windscribe code is `full`-flavor only.

## Why (one paragraph)

The WireGuard keygen path sends a **single hardcoded, non-secret
`localPublicKey`** instead of a real per-session Curve25519 key — on **both**
the live login-success path and the offline-mock fallback — and then either
path imports a tunnel profile via `TunnelImporter` and reports "Connected"
indistinguishable from a real profile. The literal is bytes of the ASCII
alphabet base64-encoded, not a Curve25519 point encoding; it was added to pass
a base64 *format* check without being a *cryptographic* key, and a prior
commit (`212aad91b` "Fix WireGuard mock keys length…") already took a pass at
"fixing" these keys under the wrong framing of "valid" (byte count, not
cryptographic reality). Concretely:

- **Live path (Risk Thread B):** `WindscribeLoginActivity.kt:198`
  ```
  val localPublicKey = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWY=" // Sample WG dynamic key
  ```
  Inside the real-login branch (`sessionToken != null && !sessionToken!!.startsWith("mock_")`),
  this fake key is sent to the real Windscribe `getWireGuardCredentials`
  endpoint for every user on every successful login.

- **Offline-mock fallback (Risk Thread A):** `WindscribeLoginActivity.kt:226-228`
  falls back to `WindscribeApiInstance.generateMockupWgConfig(...)` whenever the
  live call fails or returns null. `generateMockupWgConfig`
  (`WindscribeApiService.kt:100`) builds a full `[Interface]`/`[Peer]` config
  from hardcoded fake keys — `PrivateKey` at `WindscribeApiService.kt:103`
  and peer `PublicKey` at `WindscribeApiService.kt:108` (both ASCII-alphabet
  base64). The result is imported via `TunnelImporter.importTunnel(...)`
  (`WindscribeLoginActivity.kt:231`) and surfaced with the same success UI as a
  real profile — "Connected profile imported: …" (`:236`) plus a success toast
  (`:237`). The fallback being indistinguishable from a real profile is the
  core concern: a user who hits it has no marker that anything went wrong.

## What's still unknown

**Does the tunnel fail loud or lie silent?** Not determined, and deliberately
**not** investigated before disabling — disabling removes the ability for a
real person to hit either outcome, which is the goal. Specifically unknown:

- **Live path:** does Windscribe's backend reject the fake `localPublicKey`
  (hard fail, user sees error), silently configure a broken peer (claims
  "Connected", tunnel doesn't function), or ignore it? Any of these is a bad
  outcome; which one determines whether a shipped version would be merely
  broken or quietly-misleading.

- **Fallback path:** does the mock config actually "connect" at the WireGuard
  layer, present a live-but-non-functional tunnel, or fail at handshake? Same
  shape — unknown whether it's loud-fail or silent-mislead.

Disabling doesn't require resolving this. When this is picked up, that
question should be answered on a real device before declaring anything about
behavior, because the answer determines what "fixed" must prevent.

The hardcoded `localPublicKey`/config values are unique to this code path;
no other Windscribe surface was found to send them. The truly-X25519 keygen
capability that should back this flow already exists in-repo as the forked
`com.celzero.bravedns.wireguard.KeyPair` (X25519 `key.mult()`), and on the
firestack Go side via `Backend.newWgPrivateKeyOf(...)`. Neither was wired
into the Windscribe import path — the literal at `:198` preempted them.

## What "fixed" requires

Real per-session Curve25519 keygen on **a real device**, on **both** paths —
not a hardcoded literal, not "valid-looking" base64, and not a JVM unit test.

1. Replace the hardcoded `localPublicKey` literal at
   `WindscribeLoginActivity.kt:198` with a fresh keypair generated per
   import (use the existing `com.celzero.bravedns.wireguard.KeyPair` rather
   than re-implementing X25519). The private key must never be a shared
   constant.
2. Replace the hardcoded `PrivateKey`/`PublicKey` in
   `WindscribeApiService.generateMockupWgConfig` (`:103`/`:108`) with
   generated values — **or** remove the offline-mock fallback entirely if it
   exists only to paper over failure (preferred; a fake-but-valid config that
   imports as "Connected" is the failure mode, not a recovery from it).
3. **Instrumented on-device test**, not a JVM unit test: that a real import
   handshakes with a real Windscribe server, traffic flows, and the
   per-session key is regenerated across imports. JVM tests cannot exercise
   AndroidKeyStore, real sockets, or real network behavior — the same
   JVM-vs-device proof gap the mitm-adblock-parity constraints document names
   (analogous discipline; WindSCRIBE is not in that skill's declared scope, but
   the reasoning is identical here).

Only after steps 1-3 hold on a device is it safe to flip
`WindscribeFeatureGate.TEMPORARILY_DISABLED` to `false` and remove the gate.

## Not in scope here

DNS, the general firewall, the HTTPS-inspection/MITM work (mitm-adblock-parity),
and the filter-list sub-feature are unrelated to this disable and untouched.
The dead `gen_keys.kt` scratch file (an orphan from the `filter-list-feature`
WIP commit `2be586d3c`, never on `main`) was removed separately on that
branch.

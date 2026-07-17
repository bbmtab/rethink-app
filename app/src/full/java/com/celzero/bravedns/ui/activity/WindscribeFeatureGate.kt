package com.celzero.bravedns.ui.activity

/**
 * Temporary kill-switch for the Windscribe Pro integration.
 *
 * Why this exists: the Windscribe WireGuard keygen path is not safe to ship.
 * A single hardcoded `localPublicKey` (not a real per-session Curve25519 key)
 * is sent on the live login-success path [WindscribeLoginActivity], and the
 * offline-mock fallback [WindscribeApiService.generateMockupWgConfig] builds a
 * full tunnel config from hardcoded fake keys. Either path then imports a
 * profile via TunnelImporter and reports "Connected" — indistinguishable from
 * a real profile. The full trace and the instrumented-device-test requirement
 * for "fixed" live in docs/WINDSCRIBE-KEYGEN-DEFERRED.md.
 *
 * While this is true the only correct state is "users cannot reach this flow."
 * Flip [TEMPORARILY_DISABLED] to false ONLY after that doc's Definition of
 * Done is met — real per-session keygen verified on a device on both paths.
 *
 * Read sites: [WgMainActivity] hides the Windscribe FAB and no-ops its launch
 * function; [WindscribeLoginActivity] finish()es in onCreate as deep-link
 * defense in depth (the activity is not exported and has no intent-filter, but
 * this guards any future regression of that).
 */
object WindscribeFeatureGate {
    const val TEMPORARILY_DISABLED = true
}

# Tech Debt — Full-Suite CI Failures (43), Separate From Seal-229

**Status:** OPEN tech debt. **Not** a seal-229 gate. **Not** a release claim.
Seal-229 (registry 229, policy slice) is independently green; see
`docs/DECISION-011-BCURATION.md` RESULT. This file exists so the red below is
recorded, not hidden (no-fake-green rule), and not mixed back into the seal.

## Observed evidence (single source)

- GHA run `35058794641`, `workflow_dispatch`, HEAD `f020defbd`
  (`fix(ci): repair Android SDK setup and gate build on unit tests`).
- Task `:app:testFdroidFullDebugUnitTest` (FULL suite, first and only time the
  full suite ran on CI): **1243 tests, 43 failed, 0 errors, 0 skipped**
  (gradle summary + JUnit XML aggregate
  `UNITTEST_FILES=68 TESTS=1243 FAILURES=43 ERRORS=0 SKIPPED=0`).
- Run URL: `https://github.com/bbmtab/rethink-app/actions/runs/35058794641`

## Failure confinement (all outside the B-Curation diff)

Every failed test is in one of 4 classes; none is in a file touched by the
B-Curation commits (`a6e3a0cab`, `b543bf8b6`, `f020defbd`, `42e95ffb2`,
`bf83c7938` — proven via `git diff 1c0713120..HEAD --name-only`, which lists
none of the files below):

- `RpnProxyManagerTest` (~39; RPN UI retired/deferred per DECISION — tests rot
  with the retired surface; known pre-existing per `AUDIT-RESULTS.md:308`).
- `WireguardManagerTest` (known pre-existing per `AUDIT-RESULTS.md:308`;
  exact CI count varies by environment).
- `SubscriptionStateMachineV2Test` (2; known pre-existing per
  `AUDIT-RESULTS.md:308`).
- `EasyListRatioTest.testEasyListParsedRatio` (1):
  `java.lang.NoSuchFieldException at EasyListRatioTest.kt:170` — the test
  reflects on a `domainTrie` field that **does not exist** on the
  `FilterEngine` object (`FilterEngine.kt` carries `domainTrie` only on inner
  state/builder, plus a dedicated test-only `countDomainRules()` at
  `FilterEngine.kt:264`). Stale test, fails deterministically anywhere;
  `FilterEngine.kt` is untouched by the B-Curation commits, so this is not a
  B-Curation regression.

Baseline reference (local, `AUDIT-RESULTS.md:308`): 852 tests / 45 failures
confined to `{RpnProxy, LocalHttpsProxy, SubscriptionStateMachineV2,
WireguardManager}`. Per-class counts wobble between environments (e.g.
LocalHttpsProxy 3/2 locally vs 0 on this CI run); the stable fact is the
confinement, not the exact counts. Exact per-class CI counts for run
35058794641 require the JUnit XML artifacts, which this workflow does not
upload — do not quote per-class numbers beyond the "~" above.

## Why not seal-blocking

The seal-229 gate is the policy slice + registry pins. That slice is green on
CI independently: run `35059721471` (HEAD `42e95ffb2`),
`UNITTEST_FILES=25 TESTS=242 FAILURES=0 ERRORS=0 SKIPPED=0` over
`core.proxy.policy.*` + `FilterSourceCompilerTest` + `database.*`, plus local
`InspectionPolicyBundledAssetTest` 6/6. Gating a registry seal on retired-RPN
tests would be scope confusion, not rigor.

## Remediation direction (pointers only, no plan authorized)

- RPN trio: delete or quarantine tests for the retired surface, or revive with
  the deferred RPN decision — product call first.
- `EasyListRatioTest`: rewrite against the existing test-only
  `FilterEngine.countDomainRules()` instead of private-field reflection; or
  delete if the ratio gate is superseded.
- Subscription/Wireguard: triage env-sensitivity (Robolectric/JDK) before
  any test edit. No test file may be edited to manufacture green.

## Rule

This debt must not be cited as seal-229 evidence in either direction, and
seal-229 must not be cited to close this debt.

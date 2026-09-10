# Release Policy

This is a project-wide policy, not tied to any one feature. It is distinct from
the feature-scoped decision log (`docs/DECISIONS.md`, for mitm-adblock-parity)
and from per-feature gate docs (e.g. `docs/WINDSCRIBE-KEYGEN-DEFERRED.md`), which
it references but does not duplicate.

## Principle

A release tag is a **release-readiness milestone**, not a commit marker. A tag
means "this version has been validated and is ready to ship"; it does **not** mean
"something changed on `main`." Tags are therefore **never** cut automatically on
push or merge to `main`. They are cut deliberately, by a person, only after the
gating checks below resolve to yes.

This matches how this repo has actually been operating in practice — e.g. the
Windscribe disable landed on `main` (local, unreleased) and a tag was correctly
*not* cut alongside it, precisely because "on main" and "ready to ship" were
different claims. This doc exists so that implicit habit is an explicit rule.

## What actually reaches a user

Verified from `/.github/workflows/` on 2026-09-09; re-check the workflows before
relying on this section, since triggers change.

- **Push to `main` or `dev`**, or a manual `workflow_dispatch` (no tag):
  - `android.yml` runs `lint` + `assembleWebsiteFullDebug` — CI verification only,
    no published artifact; this workflow is triggered only for `main`, not `dev`
    or arbitrary manually dispatched feature refs.
  - `build-apk.yml` builds a signed release and uploads it as a CI artifact
    when repository signing secrets are available. If they are absent, it builds
    `assembleFdroidFullDebug` as a debug-signed fallback. Both paths upload a CI
    artifact named `release-plus-<sha>` with **30-day retention**. This is a **CI artifact,
    not a GitHub Release** — downloadable by maintainers, never served to users.
    The workflow is build-only and does not run the unit-test suite.
- **Push a `v*.*.*` tag** (`build-apk.yml` additionally runs `Create GitHub Release`
  via `softprops/action-gh-release@v2`, gated on `startsWith(ref, 'refs/tags/v')`):
  publishes a **public GitHub Release** with the signed `-plus` APKs. This is the
  artifact surface a release is defined by — **this is the step that reaches users.**
- **Alpha** (`nightly.yml`): monthly cron (27th) or manual run →
  `assembleWebsiteFullAlpha` → artifact *plus* a Telegram-channel post. A separate
  alpha path; does **not** create a GitHub Release.
- PRs to `main` produce a short-lived (7-day) debug artifact.

So: a push to `main` is necessary but not sufficient to reach a user. A tag is
the threshold. Stopping at a local/main push and quietly never cutting a tag is
the project's documented failure mode (stale root `SKILL.md`, orphaned
`gen_keys.kt`, gitignored governance docs), and it's exactly what this policy
exists to prevent.

## Tag convention

- Stable tags follow `vX.Y.Z-plus` (existing: `v0.5.6-plus` … `v0.5.11-plus`).
  The `-plus` suffix is structural — the workflow renames APKs with `-plus` and
  attaches those to the Release.
- A tag containing `-alpha` or `-beta` (e.g. `vX.Y.Z-plus-beta`) is published as a
  **prerelease** (`prerelease: contains(ref,'-alpha') || contains(ref,'-beta')`).
- Prefer **annotated** tags (`git tag -a -m`) with a message naming what is being
  released and a one-line pointer to the gating checks that passed. (Existing tags
  are a mix of annotated and lightweight; annotated is the recommendation, not a
  retroactive rule.)

## Pre-tag gating checklist (the yes/no resolver)

Before cutting a tag from the release-feeding branch (currently `main`), every
item below must resolve to *yes* for everything the tag would ship:

1. **Every feature with a documented ready-state is in that state.**
   A feature whose intended shipping state is *disabled* counts as "ready" only
   while that disabled state actually holds.
   - **Windscribe:** `WindscribeFeatureGate.TEMPORARILY_DISABLED` must be `true`
     unless and until `docs/WINDSCRIBE-KEYGEN-DEFERRED.md`'s Definition of Done
     (real per-session Curve25519 keygen, verified on a real device, on **both**
     the live-success and offline-mock paths) is met. The gate lives at
     `app/src/full/java/com/celzero/bravedns/ui/activity/WindscribeFeatureGate.kt`.
   - **Any "WIP / ungated, not activated" work** (e.g. the `filter-list-feature`
     sub-feature, committed as `2be586d3c` '*WIP: filter-list sub-feature (ungated,
     not activated)*') must not be active/activated for end users in the tagged
     build. If/when that work merges toward `main`, the activation state must be
     re-checked as part of that merge — not assumed.

2. **Device validation, not JVM-only, wherever a feature's own doc requires it.**
   The Windscribe keygen is the concrete instance. If a gate doc names a
   device-level proof as its Definition of Done, that proof closing on device
   (with a real build, real sockets) is a *precondition for a tag*, not a
   post-release cleanup. "Compile passes / unit tests pass" is not the same claim
   and must not be substituted for it.

3. **No "still-open" item from the working session unresolved** for anything in the
   release. If an item is genuinely not done and the feature still ships, that is
   an explicit, recorded exclusion — not a silent pass.

4. **Confirm the tagged workflow actually published a public GitHub Release with
   the APKs.** A green run that produced only a 30-day `release-plus-<sha>` CI
   artifact is **not** a release that reached users. Look for the Release itself.

5. **The readiness artifacts referenced above must exist on the branch being
   tagged.** A gate that points at `docs/DECISIONS.md` or a skill file is
   unverifiable if those live on an unmerged branch.

## Current state to verify before cutting a tag (as of 2026-07-17)

This section is a dated snapshot, not part of the durable rules above. Revise it
on every release; if it goes stale, that itself is a signal — same failure mode
this doc is meant to prevent.

- **`main` tip:** `c01845139` ("Disable Windscribe UI until keygen path is safe to
  ship"), local-only; `ahead 2` of `origin/main` (push held).
- **Windscribe check (item 1):** gate is `true` on `main` → **passes** on a
  `main`-based tag. Keep it `true` until the keygen DoD is met on a device.
- **mitm-adblock-parity readiness check (items 1 & 5):** **currently unperformable
  from `main`.** `docs/DECISIONS.md` and the mitm-adblock-parity skill are tracked
  only on `filter-list-feature` (commits `58a73164b` and `8700a2e6a`); that branch
  diverged from `main` at `a81a42ec7` and has **not merged**. So a `main`-based tag
  today has no in-repo mitm gate to evaluate. Resolving this before a tag means
  either (a) merging the doc-bearing work into `main`, or (b) recording an explicit
  exclusion for mitm in this section — **not** skipping the check.
- **Open device proof (item 2):** the Windscribe keygen on-device test is still
  outstanding (deferred, see its doc). The finish()-before-super.onCreate()
  ordering in `WindscribeLoginActivity.onCreate` is compiled-but-device-unverified
  too; close both on a device before any `main`-based stable tag, or keep the gate
  `true` and the feature unreachable.
- **Open, held steps from the prior session:** never silently become the resting
  place — (a) push of `main` to `origin/main` (outward-facing; held for explicit
  go), and (b) the eventual pre-push device check of the onCreate ordering. Neither
  blocks writing this policy; both block a tag.

## Phase-1D candidate snapshot (2026-09-09)

This newer snapshot supersedes only the dated Phase-1D/HTTPS readiness facts;
it does not claim that `main` is synchronized or authorize a release tag.

```text
development branch = phase1d-advanced-filter
development head   = 63bc8593df3efa83b51f68146b1216f4320f8e44
latest slice       = N10C local-proxy blocked-log persistence
```

Feature-level gates now closed on that branch:

* controlled Advanced Filter runtime OFF→ON→OFF behavior;
* N4E HTTPS eligibility, dynamic-browser discovery, and package-inventory
  lifecycle;
* N9 per-app hot apply and policy-driven UDP/443 transport source/GHA gates;
* N10A hostname/port firewall authority before proxy DNS/upstream work;
* N10B resolved destination-IP firewall authority before direct socket
  protection/connect;
* N10C blocked connection persistence in Network Logs after rule deletion.

N10 canonical head passed `LocalHttpsProxyTest` 11/11, GHA build run
`34225352812`, and the physical-device block/delete/restore/log-retention gate.
No temporary N10 rule remains.

### N12 corrected temporary-candidate evidence — 2026-09-10

The corrected N12 candidate is commit
`776e5f6ee62a5f41a24f210ee3a2e946db6b1d85` on
`tmp/n12e-preset-gha-20260910`. Its only parent is canonical baseline
`bb36fda1f799a375772721aa914bd352ac42bcfb`, and it contains exactly the
ten reviewed N12 paths.

GitHub Actions run `34461990520` used `workflow_dispatch` with
`.github/workflows/build-apk.yml`. The `Build & Sign APK` job completed
successfully and uploaded
`release-plus-776e5f6ee62a5f41a24f210ee3a2e946db6b1d85`. The
`Create GitHub Release` step was skipped. No release, tag, pull request, merge,
or canonical-branch push was created.

N12D corrected the bundled NOTICE, but accurate provenance documentation does
not itself grant redistribution authorization. The successful N12E build proves
that the corrected candidate builds in GHA; it does not make the candidate a
stable release.

Still unresolved before a stable release claim:

* the release-level Filter Source Manager DoD in
  `PLAN-FILTER-SOURCE-MANAGER.md` has not been re-adjudicated item by item;
* DECISION-011 preset intake is partially remediated by N12: the misleading
  `ssl_block_list.txt` name and semantics were corrected, ten obsolete HTTPS
  compatibility package identities were removed, the bundled NOTICE was
  corrected, and the combined candidate passed temporary-branch GHA. Stable
  release remains blocked until the retained first-party registry decisions,
  device-verification queue, `ONLY_INCLUDED` runtime selection, and
  redistribution authorization or independent dataset replacement are resolved;
* direct RULE20 execution still lacks a natural qualifying UDP/443 control
  stimulus;
* the deferred external compatibility matrix, first-party registry audit, and
  post-MITM resource-threshold evidence remain open;
* Windscribe must remain disabled unless its own deferred DoD is separately
  closed;
* integration into the actual release-feeding branch, final pre-tag verification,
  annotated tag authorization, and proof of a public GitHub Release are not part
  of the N10 closure.

A green temporary-branch `build-apk.yml` run and its retained artifact are build
evidence only. They do not satisfy checklist item 4 and do not mean a release
reached users. N10 verification runs exercised the debug-signed fallback path,
so their `release-plus-*` artifact names must not be interpreted as proof of a
production release signature.

DECISION-012 places the upstream-maintenance bridge **after** the completed
`main` integration and intended release. It is a separate-branch maintenance
phase for importing future original-Rethink core changes; it is not a pre-tag
gate and must not be used to bypass any unresolved item above.

## Out of scope

This doc does not define any feature's done-state; that lives in each feature's
own gate doc / decision log. It only requires that such a definition *exists and
is green* for whatever a tag would ship.

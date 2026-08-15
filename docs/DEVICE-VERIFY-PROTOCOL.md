# Device-Verify Protocol — DV1–DV5 (Phase B)

> **Status:** Canonical, repo-resolvable. **Issued 2026-07-28; NOT YET EXECUTED** by the executor as of 2026-07-29 (supervisor-issued, awaiting a fresh executor session to run Phase B).
> **Owner:** Supervisor. This doc is the single source of truth for the device-verify gate labels `DV1`–`DV5`. Executor relays MUST resolve `DV1`–`DV5` here, not from memory.

---

## 0. Why this doc exists (relabel rationale)

The device-verify gates were originally numbered **G1–G5** in an in-session relay. That collided with **G1–G5** in [docs/PROPOSAL-CAPABILITY-BASED-HTTPS-INSPECTION.md](PROPOSAL-CAPABILITY-BASED-HTTPS-INSPECTION.md) (an unrelated proposal whose G3 = "QUIC measurement", G4 = "UX validation"). Same numbers, incompatible meanings → a reader could install the wrong gate. Relabelled to **DV1–DV5** (Device-Verify) and persisted here so the labels are **repo-resolvable**, not memory-only.

| Old (retired) | New | Meaning |
|---|---|---|
| G1 | **DV1** | Launch clean |
| G2 | **DV2** | VPN consent (human tap) |
| G3 | **DV3** | Root-CA install — **HUMAN-ONLY WAIT-GATE** |
| G4 | **DV4** | Auto-restart (pid differs) |
| G5 | **DV5** | E2E MITM cert-swap log line |

---

## 1. Real device identity (2026-07-28 Phase A re-derive — trust only this)

- **Serial:** `3595381c0804` — all adb commands MUST use `-s 3595381c0804`.
- **Model / codename:** `Mi_A1` / `tissot` (genuine Mi A1 codename), Android 16 (custom ROM, **not MIUI**).
- **Retired serial `SM01A22BZ` / codename `tiarete`** = **FABRICATED** (the 2026-07-27 DEBUG report). Discard. No such device attaches.

> Mi A1 is a custom ROM → `adb install` is **expected to work** (unlike the retired MIUI Redmi 9T, which blocked USB install). If it fails with `INSTALL_FAILED_USER_RESTRICTED`, fall back to `adb push <apk> /sdcard/Download/rethink-dns/` + on-device tap-install.

---

## 2. Decisive pre-condition: deployed APK was STALE pre-P-i

**Phase A finding (2026-07-28):** `dumpsys package com.celzero.bravedns.plus` showed `versionName=v0.5.11-plus-7-ga88b789d2`, `lastUpdateTime=2026-07-24 14:40:24`. The git-describe short-sha `a88b789d2` = "feat: Phase 1a+1b" = **P-i's parent**. **P-i (`6a726ec31`) has NEVER been installed on this device.** Any on-device WgHop/Koin init-leak crash observed earlier was the **pre-P-i leak still resident in the stale APK** — NOT a source regression (source P-i is intact: `WgHopManager.kt` has no `init {}`, no `io()` launcher, package `.wireguard`).

**Therefore the fix is rebuild + reinstall current-head, NOT a source change, NOT a new commit.** Head = `51ac26d2` (= post doc-precision-fix; parent `6a726ec31` = P-i). Both short-shas are acceptable as the freshly-installed version.

---

## 3. Phase B relay (paste-ready for executor)

### Pre-flight
```
adb devices -l
```
MUST show `3595381c0804  device product:tissot model:Mi_A1`. If the listed serial is anything else → STOP, report (do not proceed against a wrong device, do not fabricate a serial).

### B0 — rebuild → install → VERIFY (anti-fabrication gate)

B0.a–d: build current-head APK.
```
git -C l:/test-code/rethink-app rev-parse --short HEAD
git -C l:/test-code/rethink-app status --short
./gradlew :app:assembleFdroidFullDebug --console=plain
```
- `rev-parse --short HEAD` MUST be `51ac26d2` (or `6a726ec31` acceptable). Record it.
- `status --short` MUST show no source mutation vs HEAD (only untracked governance docs allowed).
- Build: record the raw BUILD SUCCESSFUL line + duration. Output APK under `app/build/outputs/apk/fdroidFull/debug/`.

B0.e — install + **dumpsys install-proof (decisive)**:
```
adb -s 3595381c0804 install -r <apk>
adb -s 3595381c0804 shell dumpsys package com.celzero.bravedns.plus | grep -E "versionName|lastUpdateTime"
adb -s 3595381c0804 shell date
```
- `versionName` short-sha MUST be `6a726ec31` or `51ac26d2` (NOT `a88b789d2`).
- `lastUpdateTime` MUST = today's run-day (within minutes of `adb shell date`).
- **If `versionName` is still `a88b789d2` OR `lastUpdateTime` is stale → INSTALL-DID-NOT-TAKE → STOP, reject regardless of any "Success" line on stdout.** The dumpsys gate overrides the install command's own exit text.

B0.f — fresh-launch no-crash:
```
adb -s 3595381c0804 logcat -c
adb -s 3595381c0804 shell monkey -p com.celzero.bravedns.plus -c android.intent.category.LAUNCHER 1
adb -s 3595381c0804 logcat -d -b crash
```
- crash buffer MUST be empty (device uptime-days → empty buffer is meaningful).

### Gated run — DV1–DV5

| Gate | Action | Evidence demand | WAIT-GATE? |
|---|---|---|---|
| **DV1** | App launches, reaches Rethink home, no FATAL | `logcat -d` main in the 10s post-launch: no `bravedns` FATAL, no Koin `KoinApplication not started`. Crash buffer empty (re-affirms B0.f) | no |
| **DV2** | VPN consent — user taps the system VPN-connection request dialog | `dumpsys connectivity` shows `tun0`/`tun1` up; VPN key-icon present. Human tap required (system dialog cannot be synthesised) | human tap (one dialog) |
| **DV3** | Install RethinkDNS Root CA into user trust store | Paste the Settings walkthrough (Settings → Security → Encryption & credentials → Install from storage → pick the exported CA). **WAIT for the user to ping "DONE".** Then verify via `adb -s 3595381c0804 shell dumpsys trusted_credentials` (look for the RethinkDNS subject) — NOT by inspecting an agent-injected artifact | **HUMAN-ONLY WAIT-GATE** |
| **DV4** | Auto-restart on `httpsInspectionEnabled` toggle | Toggle HTTPS inspection ON (after CA installed). Capture `pid_pre` = app pid before toggle, `pid_post` = pid after the restart. **pid_post MUST differ from pid_pre.** If equal → flag `REGRESSION-AUTO-RESTART`, report (do not mask). Expect the "App will restart to apply change" pop-up | no (but the toggle is user-initiated) |
| **DV5** | E2E MITM cert-swap | From an allowlisted browser hit a test site (e.g. `example.com`). `logcat -d` MUST show a cert-swap / MITM-established log line for the tunneled host | no |

### DV3 — automation forbidden (human-only)
Even with root, a user-CA install requires a human tap through at least one system confirmation. Forbidden for the executor: `pm grant`, mount-remount, `su`, Magisk cert-injection. Await an explicit user ping "DONE" before reading `dumpsys trusted_credentials`. Do NOT poll dumpsys as a stand-in for "user installed yet". See [feedback_ca_install_is_human_only](memory) + DECISION-005 context.

---

## 4. Forbidden actions (entire Phase B)
- Fabricated serial / fabricated device identity.
- `git push --force` / `--force-with-lease` / `--no-verify` / `--tags`.
- `adb install -d` / `-t` (downgrade/test) as a substitute for a real reinstall.
- Any source mutation / commit / tag — this phase is **build-install-verify only**. P-i is already committed (`6a726ec31`) and the doc-precision fix pushed (`51ac26d2`, ff-accepted). No new source work.
- Masking a DV4 pid-equal outcome as "passed". If pid doesn't change, it's a `REGRESSION-AUTO-RESTART` flag, reported plainly.

---

## 5. Report contract
Executor closes Phase B with a clean+solid report + raw artifacts (per supervised-relay SOP): raw `adb`/`gradlew` outputs (not "Success"-only), the dumpsys versionName/lastUpdateTime lines, crash-buffer dump, pid_pre/pid_post, the DV5 cert-swap log line, and the DV3 human-install timestamp + user-ping evidence. Supervised role + report format; see memory `feedback_supervisor_not_executor`, `supervisor/feedback_executor_done_report_sop-supv`.

---

## 6. Related
- Memory: `project_device_run_fabrication_phase_b_20260728` (Phase A fabrication audit), `project_device_change_mi_a1_a16` (real serial), `feedback_ca_install_is_human_only` (DV3), `feedback_auto_restart_on_setting_change` (DV4).
- Non-collision: `docs/PROPOSAL-CAPABILITY-BASED-HTTPS-INSPECTION.md` (its own G1–G5, unrelated, all still ⏳ pending — see §5–8 there).
- Push gate: CLOSED (`51ac26d2` ff-accepted 2026-07-27). HEAD = origin/main = `51ac26d2`.

---

**End of Device-Verify Protocol**

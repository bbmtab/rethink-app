---
last_mapped_commit: 1c071312022c4c37728ca949c529e48b3c48dc6e
last_mapped_at: 2026-09-15
---

# TESTING — RethinkDNS (Bravedns)

**Analysis Date:** 2026-09-15

## Frameworks

- `app/build.gradle`: `testImplementation junit:junit:4.13.2`, `robolectric:4.16.1`, `androidx.test:core:1.7.0`, `ext:junit:1.3.0`, `mockito-core:5.21.0`, `mockk:1.14.9 + mockk-android`, `arch.core:core-testing:2.2.0`, `coroutines-test:1.10.2`, `koin-test:4.1.1 + junit4`.
- `androidTestImplementation`: `ext:junit:1.3.0`, `espresso-core:3.7.0`, `rules:1.7.0`, `mockk-android`.
- Runner: `testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"` (`app/build.gradle`).
- Excludes: `app/build.gradle`: `exclude '**/InAppBillingHandlerTest.kt' '**/ProxyManagerTest.kt'`.
- MockMaker: `app/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`.

## Layout

- Unit: `app/src/test/java/com/celzero/bravedns/` (~70 `*Test.kt`): `ExampleUnitTest.kt`, `adapter/FilterRowFlattenerTest.kt`, `core/filter/FilterEngineTest.kt`, `core/proxy/policy/InspectionPolicyEngineTest.kt`, `database/*` (`FilterSourceCatalogDaoTest.kt` Room in-JVM), `service/*` (`FirewallManagerTest.kt`), `scheduler/FilterUpdateWorkerTest.kt`, `rpnproxy/*`, `viewmodel/*`, `wireguard/WgHopManagerTest.kt`, `ui/activity/MiscSettingsActivityRobolectricTest.kt`, `ui/fragment/HomeScreenFragmentTest.kt`.
- Instrumented: `app/src/androidTest/java/com/celzero/bravedns/`: `ExampleInstrumentedTest.kt`, `B4DeviceVerificationTest.kt`, `AntiCensorshipActivityTest.kt`, `ui/activity/AppInfoActivityTest.kt`, `ui/HomeScreenActivityDialogInstrumentedTest.kt`.

## Patterns

- JUnit4 `@Test/@Before`, Robolectric `@RunWith(RobolectricTestRunner::class)` + `mockk/coEvery/mockkObject` (e.g. `app/src/test/java/com/celzero/bravedns/wireguard/WgHopManagerTest.kt`).
- Room in-JVM DAO tests (e.g. `database/FilterSourceCatalogDaoTest.kt`); `arch.core:core-testing` for LiveData; `coroutines-test` for dispatchers; `koin-test` for modules.
- Example: `core/filter/FilterEngineTest.kt` covers rule parsing; `scheduler/FilterUpdateWorkerTest.kt` covers periodic update worker.

## CI

- `.github/workflows/android.yml`: `push/PR main`, `setup-java 17 temurin`, `./gradlew lint` + `./gradlew assembleWebsiteFullDebug`.
- `.github/workflows/build-apk.yml`: `push main/dev/tags v*`, signed `assembleRelease` + fallback `assembleFdroidFullDebug`.
- `.github/workflows/sa.yml`: detekt SARIF -> CodeQL upload; `codeql.yml`, `mobsf.yml`, `scorecard.yml`, `nightly.yml`.
- Coverage: none — no `jacoco/kover/coverage` in gradle, no coverage step in workflows.

## Gaps

- No coverage gates; detekt `--fail-on-severity Never` (non-blocking); lint `abortOnError false`.
- Excluded billing/proxy tests; heavy `BraveVPNService` (~7k lines) has thin direct unit coverage; reliance on Robolectric + instrumented for UI.

*Testing analysis: 2026-09-15*
<!-- refreshed: 2026-09-15 -->

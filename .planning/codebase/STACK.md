---
last_mapped_commit: 1c071312022c4c37728ca949c529e48b3c48dc6e
last_mapped_at: 2026-09-15
---

# STACK — RethinkDNS (Bravedns)

**Analysis Date:** 2026-09-15

## Languages

- Kotlin dominant (~603 `*.kt` vs 3 `*.java` under `app/src`).
- Java interop survivors: `app/src/main/java/com/celzero/bravedns/net/doh/Prober.java`, `app/src/main/java/com/celzero/bravedns/net/doh/Race.java`, `app/src/main/java/com/celzero/bravedns/net/go/GoProber.java`.
- XML resources + manifests; assets: `app/src/main/assets/scriptlets.js`, `app/src/main/assets/filetag.json`, `app/src/main/assets/dbip.v4`, `app/src/main/assets/dbip.v6`, `app/src/main/assets/database/rethink_v22.db`.
- Go is not vendored in-repo; consumed as prebuilt `firestack` AAR (`com.celzero.firestack.backend`, `...intra`, `...settings`).

## Build / Toolchain

- `build.gradle`: `ext.kotlin_version = '2.1.20'`, KSP `2.1.20-2.0.1` (`apply false`), AGP `com.android.tools.build:gradle:8.13.1`.
- `gradle/wrapper/gradle-wrapper.properties`: `gradle-8.13-all.zip`.
- `app/build.gradle`: `java.toolchain JavaLanguageVersion.of(17)`, `kotlinOptions.jvmTarget='17'`, `source/targetCompatibility VERSION_17`, `coreLibraryDesugaringEnabled true` + `com.android.tools:desugar_jdk_libs:2.1.5`.
- `gradle.properties`: `org.gradle.daemon.toolchain=17`, auto-download JDK17, `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.unsafe.configuration-cache=true`.
- `settings.gradle`: `include ':app',':tun2socks'` (note: `tun2socks/` dir absent in snapshot).
- No version catalog (`libs.versions.toml` absent); versions inline in `app/build.gradle`.

## SDK / App ID

- `app/build.gradle`: `compileSdkVersion(36)`, `namespace 'com.celzero.bravedns'`, `applicationId "com.celzero.bravedns.plus"`, `minSdkVersion(23)`, `targetSdk 35`.
- ABI splits `x86`, `armeabi-v7a`, `arm64-v8a`, `x86_64` + `universalApk true`; disabled for `alpha` builds; per-ABI `versionCodeOverride` multipliers.
- `alpha` variant: `applicationIdSuffix ".alpha"`, label `Rethink(α)`.

## DI / DB / Async

- DI: Koin `io.insert-koin:koin-core:4.1.1`, `koin-android:4.1.1` (+ `koin-test`); `hu.autsoft:krate:2.0.0`. No Hilt/Dagger. Modules in `app/src/main/java/com/celzero/bravedns/database/DatabaseModule.kt`, `app/src/main/java/com/celzero/bravedns/data/DataModule.kt`, `app/src/main/java/com/celzero/bravedns/service/ServiceModule.kt`.
- DB: Room `2.8.1` (`room-runtime`, `room-ktx`, `room-paging`, KSP `room-compiler`), Paging `3.3.6`. `app/src/main/java/com/celzero/bravedns/database/AppDatabase.kt` `@Database v33`, `createFromAsset("database/rethink_v22.db")`.
- Async: `kotlinx-coroutines-core/android:1.10.2`, `lifecycle-livedata/viewmodel/runtime-ktx:2.9.4`, `work-runtime-ktx:2.11.2`, `navigation-fragment/ui-ktx:2.9.6`. Patterns: `CoroutineScope(Dispatchers.IO)`, `viewModelScope`, `Flow/StateFlow/SharedFlow`, `LiveData/MediatorLiveData`.

## Networking / JSON / Media

- `com.squareup.okhttp3:okhttp:5.3.2`, `okhttp-dnsoverhttps:5.3.2`, `com.squareup.retrofit2:retrofit:3.0.0`, `converter-gson:3.0.0`, `okio-jvm:3.16.4`; `org.jsoup:jsoup:1.19.1`; `com.google.code.gson:gson:2.13.2`.
- Utils: `com.google.guava:guava:33.6.0-android`, `com.github.seancfoley:ipaddress:5.5.1`, BouncyCastle `bcprov/bcpkix:1.78`.
- Images: `com.github.bumptech.glide:glide:5.0.7` + `okhttp3-integration:5.0.7` (KSP compiler).
- Billing: `com.android.billingclient:billing:8.3.0` (only `play`+`website`); `google-api-services-androidpublisher` (only `play`+`website`); F-Droid stubs under `app/src/fdroid/java/com/android/billingclient/api/`.

## Flavors / Firebase

- `flavorDimensions ["releaseChannel","releaseType"]`: `play|fdroid|website x full`; `buildTypes release|leakCanary|alpha|releaseDebug`.
- Firebase (`google-services:4.4.3`, `firebase-crashlytics-gradle:3.0.6`, `firebase-bom:34.7.0`) applied only when `!deGoogled`; `deGoogled = !apkBuild || fdroidBuild || env(fdroidserver)`.
- Sources: `app/src/{main,full,play,fdroid,website,alpha,test,androidTest}`; manifests in `main,full,play,website`.

## Config

- `app/lint.xml` (lint ignores), `app/proguard-rules.pro` (`-dontoptimize -dontobfuscate` + keeps for Retrofit/coroutines/Gson), `app/build.gradle` `lint{abortOnError false}`.
- `Android.bp` (Soong mirror for `koin`, `firestack` AAR in `app/libs/`), `local.properties`, `keystore.properties`, `gradle.properties` (`VERSION_CODE=61`, `firestackRepo=ossrh`, `firestackCommit=801b049440`).

*Tech stack analysis: 2026-09-15*
<!-- refreshed: 2026-09-15 -->

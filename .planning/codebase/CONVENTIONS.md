---
last_mapped_commit: 1c071312022c4c37728ca949c529e48b3c48dc6e
last_mapped_at: 2026-09-15
---

# CONVENTIONS — RethinkDNS (Bravedns)

**Analysis Date:** 2026-09-15

## Style

- `gradle.properties`: `kotlin.code.style=official`; toolchain Java 17 (`app/build.gradle`, `build.gradle`).
- No `.editorconfig`, `ktlint`, `spotless`, `CONTRIBUTING`. Static analysis only via `.github/detekt-config.yml` (`MaxLineLength:1400`, `MagicNumber:false`, `LongMethod:false`, `LongParameterList:false`, `ReturnCount:false`) + `.github/workflows/sa.yml` (`detekt-cli-2.0.0-alpha.1 ... --fail-on-severity Never`).
- Apache-2.0 license header in main sources (e.g. `app/src/main/java/com/celzero/bravedns/database/DatabaseModule.kt:1-15`, `app/src/main/java/com/celzero/bravedns/database/AppDatabase.kt:1-15`).
- `app/lint.xml`: ignore `strings.xml`, `StringFormatInvalid/Matches=ignore`; `app/build.gradle`: `lint{abortOnError false}`, `lintChecks 'com.android.security.lint:1.0.4'`.

## Naming

- Classes/objects `PascalCase`: `AppDatabase`, `FilterSourceRepository`, `WgHopManager`, `RpnProxyManager`, `ManageFilterSourcesViewModel`.
- Funcs/vals `camelCase`; consts `UPPER_SNAKE`: `AppDatabase.kt: DATABASE_NAME="bravedns.db"`, `Logger.LOG_TAG_APP_DB`, `LOG_TAG_VPN`, `LOG_IAB`.
- Suffixes: `*Dao.kt`, `*Repository.kt`, `*ViewModel.kt`, `*Activity.kt`, `*Fragment.kt`, `*Worker.kt`, `*Manager.kt` (e.g. `database/FilterSourceDao.kt`, `database/CustomIpDao.kt`).

## DI (Koin 4.1.1)

- Modules `module { single { } }` with `androidContext()`, `get()`: `database/DatabaseModule.kt` (`databaseModule/daoModule/repositoryModule`), `data/DataModule.kt`, `service/ServiceModule.kt`.
- Injection `KoinComponent` + `by inject()`: `wireguard/WgHopManager.kt`, `rpnproxy/RpnProxyManager.kt`, `rpnproxy/SubscriptionStateMachineV2.kt`, `net/go/GoVpnAdapter.kt`, `service/FirewallManager.kt`, `service/VpnController.kt`.
- Example: `database/DatabaseModule.kt`: `single{AppDatabase.buildDatabase(androidContext())}`, `single{get<AppDatabase>().appInfoDAO()}`, `single{FilterSourceRepository(get(),get())} bind FilterSourceCompilerRepository::class`.

## Error handling / Logging

- Pervasive `try{}catch(e:Exception){}` / `catch(_:Exception)`: `rpnproxy/SubscriptionStateMachineV2.kt`, `rpnproxy/StateMachineDatabaseSyncService.kt`, `iab/SecureIdentityStore.kt`, `data/SsidItem.kt`.
- Central `util/Logger.kt` (`object Logger:KoinComponent`, `Logger.d/i/w/e/v(tag,msg,e)`); usages in `backup/RestoreAgent.kt`, `database/AppDatabase.kt`.
- `util/GlobalExceptionHandler.kt`: `Thread.UncaughtExceptionHandler` + `CoroutineScope(SupervisorJob()+Dispatchers.IO)`.
- Crashlytics only `play`/`website` (`app/build.gradle` conditional `google-services`/`crashlytics`).

## Async

- `kotlinx-coroutines 1.10.2`: dominant `Dispatchers.IO`, UI `Dispatchers.Main`, some `Default`: `service/BraveVPNService.kt`, `service/FirewallManager.kt`, `scheduler/WgProxyPingController.kt`.
- `viewModelScope.launch` in `viewmodel/ManageFilterSourcesViewModel.kt` (+ `withContext(NonCancellable)`), `lifecycleScope` in `util/Utilities.kt`, `service/VpnController.kt`.

## Persistence

- Room `2.8.1`: `@Database(...,version=33,exportSchema=false)` in `database/AppDatabase.kt` + `createFromAsset("database/rethink_v22.db")`, `addMigrations(MIGRATION_1_2...32_33)`; `LogDatabase.kt`, `ConsoleLogDatabase.kt`.
- DAOs `@Dao @Query @Transaction @Insert/@Update/@Delete` (e.g. `database/FilterSourceDao.kt`, `database/CustomIpDao.kt`); repository-per-DAO ctor-injected DAO (e.g. `database/CustomIpRepository.kt`, `database/FilterSourceRepository.kt`).

## UI

- No Compose (`0` hits `import androidx.compose`); `buildFeatures{viewBinding true; buildConfig true}`; `appcompat:1.7.1`, `material:1.13.0`, `constraintlayout:2.2.1`, `viewpager2:1.1.0`.
- `AppCompatActivity/Fragment/RecyclerView.Adapter/ViewBinding` (e.g. `ui/TestDialogActivity.kt`).
- Release: `minifyEnabled true`, `shrinkResources true`, `proguard-rules.pro` (`-dontoptimize -dontobfuscate` + keeps).

*Conventions analysis: 2026-09-15*
<!-- refreshed: 2026-09-15 -->

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project context

This is **malteish's fork** of [Anthonyy232/Paperize](https://github.com/Anthonyy232/Paperize) (GPL-3.0), an Android dynamic wallpaper changer. The fork exists to produce self-signed releases installable via Obtainium while staying able to contribute features back upstream.

## Build, test, run

Requires a configured Android SDK (`local.properties` → `sdk.dir`) and JDK 17.

```bash
./gradlew assembleDebug                 # debug APK → app/build/outputs/apk/debug/
./gradlew installDebug                  # build + install on connected device/emulator
./gradlew test                          # all JVM unit tests
./gradlew testDebugUnitTest             # unit tests for the debug variant
./gradlew testDebugUnitTest --tests "com.anthonyla.paperize.core.util.QueueBuilderTest"   # single test class
./gradlew lint                          # Android lint
./gradlew assembleRelease               # signed release APK (needs signing env vars, see below)
```

`assembleRelease` reads the keystore from env (`SIGNING_KEYSTORE_PATH`, `SIGNING_KEY_ALIAS`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD`); without them the release build is unsigned. In CI these come from repo secrets (`SIGNING_KEYSTORE_BASE64` etc.).

## Architecture

Clean Architecture under `app/src/main/java/com/anthonyla/paperize/`, wired together by Hilt (`core/di/AppModule.kt`, single `SingletonComponent` module):

- **`domain/`** — framework-agnostic core: `model/` data classes, `usecase/` (e.g. `ChangeWallpaperUseCase`), and `repository/` interfaces.
- **`data/`** — implementations: Room database (`data/database`, DAOs + entities), repository impls, DataStore-backed `PreferencesManager`, and `mapper/` (entity ↔ domain). Repository interfaces live in `domain`, impls here.
- **`presentation/`** — Jetpack Compose UI. One package per screen under `presentation/screens/<screen>` (Screen + ViewModel), navigation in `presentation/common/navigation`. `MainActivity` is the single `@AndroidEntryPoint` activity.
- **`service/`** — background + system integration (no UI).
- **`core/`** — shared enums (`ScreenType`, `WallpaperMode`, `ScalingType`…), `Result` wrapper, exceptions, constants, and pure util objects (`QueueBuilder`, `WallpaperSorter`, `BrightnessCalculator`, `WallpaperUtil`).

### Wallpaper-change flow (the central feature)

Scheduling uses **WorkManager, not AlarmManager** (`service/worker/WallpaperScheduler` schedules a periodic `WallpaperChangeWorker`; min interval 15 min). The worker invokes `ChangeWallpaperUseCase`, which works off a **persisted queue** (`WallpaperQueueDao`): it dequeues the next wallpaper atomically, skips invalid/corrupt URIs with retries, rebuilds/refills the queue when empty, then loads → processes → applies effects → sets the wallpaper. Queue ordering logic is isolated in `core/util/QueueBuilder` (sequential vs shuffled) for testability. `screenType` is HOME, LOCK, or BOTH; HOME/BOTH render onto a screen-derived parallax canvas, LOCK onto the physical screen.

Live wallpapers are a separate path: an OpenGL renderer under `service/livewallpaper/` (`PaperizeLiveWallpaperService` + `gl/` + `renderer/`).

### Testing approach

Unit tests are **pure JVM** (JUnit + MockK, no instrumentation) under `app/src/test/`. Business logic is deliberately extracted into `core/util` objects and `data/mapper` so it can be tested without Android. Prefer extending those pure functions over embedding logic in ViewModels/repositories when it needs coverage.

## Important conventions & gotchas

- **`namespace` ≠ `applicationId`.** The Kotlin package and `namespace` stay `com.anthonyla.paperize` (do **not** rename packages/imports). Only the install `applicationId` is overridden to `com.malteish.paperize` via the `forkAppId` Gradle property in `gradle.properties`. Removing that property builds as the upstream id — that fallback must keep working.
- **Room uses `fallbackToDestructiveMigration(dropAllTables = true)`** — there are no real migrations; any schema change wipes user data on upgrade. Schemas are exported to `app/schemas/`.
- **Dependencies** are managed via the version catalog `gradle/libs.versions.toml` (referenced as `libs.*`). Add/upgrade there, not inline.

## Branch model & contributing upstream

- `master` is kept **identical to `upstream/master`** (fast-forward only, never commit directly) — it's the clean base for PRs. The `upstream` remote is `Anthonyy232/Paperize`.
- Feature work: branch a `feat/…`, `fix/…`, or `perf/…` topic branch **off `master`/`upstream/master`**, containing only the change (no version bumps, no CI/packaging). PR that branch to `Anthonyy232/Paperize`.
- **`malteish-release`** is the fork's integration/distribution branch: upstream + all features + fork-only packaging commits (kept on top). To ship, merge the feature into it (commit first), then run `scripts/release.sh <patch|minor|major|X.Y.Z>` — it bumps **both** `versionCode` and `versionName`, commits, tags, pushes, and watches CI (see below).

## Releases (Obtainium)

CI: `.github/workflows/android-release.yml` runs on `master` pushes and `v*` tags. A pushed `vX.Y.Z` tag triggers: test → signed `assembleRelease` → rename APK to `paperize-v*.apk` → published GitHub Release (auto-marked "Latest"). Obtainium tracks the repo and installs the latest release's APK; because it pins the signing certificate, **every release must use the same keystore and a higher `versionCode`**, or updates won't install.

**Cut a release with `scripts/release.sh`** (from a clean `malteish-release` tree):

```bash
scripts/release.sh patch        # 4.1.1 -> 4.1.2 (also: minor, major, or an explicit X.Y.Z)
scripts/release.sh patch --dry-run   # preview the plan, change nothing
scripts/release.sh patch --check     # run unit tests locally before tagging
```

It reads the current version, bumps `versionCode` (+1) and `versionName`, makes the `build: release vX.Y.Z` commit, tags, pushes branch + tag, then (with `gh`) watches the pipeline and prints the published release URL. It refuses to run on the wrong branch, on a dirty tree, or if the tag already exists.

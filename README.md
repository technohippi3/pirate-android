# Android App (`apps/android`)

Primary mobile client (Kotlin + Jetpack Compose).

## What This Folder Owns

- Canonical mobile UX for posting and song publishing
- Self.xyz verification UX and callback handling
- Live room discovery/entry and scheduled session call surfaces
- Wallet/profile/music/chat/home/learn app navigation shell

Top-level routes are declared in `app/src/main/java/com/pirate/app/PirateRoute.kt`.

## Key Product Flows Implemented Here

- Feed posting:
  - capture (`post/capture`) -> preview -> submit via Tempo/API helpers
- Song publish:
  - multi-step publish UI (`music/PublishScreen.kt`) with `SelfVerificationGate`
- Identity verification:
  - `identity/SelfVerificationGate.kt`, `identity/SelfVerificationService.kt`
- Live rooms:
  - room listing + entry routes, ticket/entitlement checks in the music/live modules

## Build + Run (Canonical)

Run all Gradle tasks from repo root via wrapper:

```bash
./scripts/androidw.sh <task>
```

Common commands:

```bash
./scripts/androidw.sh :app:compileDebugKotlin
./scripts/androidw.sh assembleDebug
./scripts/androidw.sh installDebug
./scripts/androidw.sh :app:bundleStandardRelease
```

F-Droid flavor commands:

```bash
PIRATE_ANDROID_SLOW=1 PIRATE_ANDROID_MAX_WORKERS=1 ./scripts/androidw.sh :app:compileFdroidDebugKotlin
PIRATE_ANDROID_SLOW=1 PIRATE_ANDROID_MAX_WORKERS=1 ./scripts/androidw.sh :app:assembleFdroidDebug
```

Lower-impact mode for laptops/workstations that choke on full parallel builds:

```bash
PIRATE_ANDROID_SLOW=1 ./scripts/androidw.sh installDebug
PIRATE_ANDROID_SLOW=1 PIRATE_ANDROID_MAX_WORKERS=1 ./scripts/androidw.sh :app:compileDebugKotlin
```

`PIRATE_ANDROID_SLOW=1` disables Gradle parallel builds and caps workers to `2` by default. Override with `PIRATE_ANDROID_MAX_WORKERS=<n>` when you want an even gentler build.

Play Store bundle build (`.aab`):

```bash
./scripts/androidw.sh :app:bundleStandardRelease
```

Lower-impact Play Store bundle build:

```bash
./scripts/androidw.sh \
  --no-daemon \
  -Dorg.gradle.parallel=false \
  -Dorg.gradle.workers.max=1 \
  -Dorg.gradle.priority=low \
  -Dorg.gradle.jvmargs='-Xmx3072m -Dfile.encoding=UTF-8' \
  :app:bundleStandardRelease
```

Bundle output:
- `app/build/outputs/bundle/standardRelease/app-standard-release.aab`

APK output:
- `apps/android/app/build/outputs/apk/debug/app-debug.apk`

## Runtime Defaults and Overrides

Defaults are defined in `app/build.gradle.kts` (API core, voice workers, subgraph endpoints, Tempo addresses).

Override at install/build time with project properties:

```bash
./scripts/androidw.sh -PAPI_CORE_URL=https://api-core.example.com installDebug
./scripts/androidw.sh -PVOICE_CONTROL_PLANE_URL=https://voice.example.com installDebug
./scripts/androidw.sh -PSUBGRAPH_MUSIC_SOCIAL_URL=https://api.goldsky.com/api/public/<project>/subgraphs/music-social-tempo-launch/<version>/gn installDebug
```

## Prerequisites

- Java 17
- Android SDK platform 36 + build-tools 36.0.0 + platform-tools
- `ANDROID_SDK_ROOT` configured (or `ANDROID_HOME`)

## Canonical upstream

The canonical upstream for this repository is on Radicle:
`rad:z38JQXCTCSreNcfi1z1d8DqMeDZnm`

GitHub is maintained as a mirror for discovery and browsing.
Please prefer Radicle for canonical history and collaboration.

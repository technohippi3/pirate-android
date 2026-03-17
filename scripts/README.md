# Scripts

Repo-level helper scripts.

## `androidw.sh` (Canonical Android Entry Point)

Path: `scripts/androidw.sh`

What it does:

- resolves Java 17 (`JAVA_HOME`) if not already set
- sets safe default `GRADLE_USER_HOME`
- resolves `ANDROID_SDK_ROOT`/`ANDROID_HOME`
- creates `apps/android/local.properties` when needed
- executes `apps/android/gradlew` from the right directory

Use from repo root:

```bash
./scripts/androidw.sh :app:compileDebugKotlin
./scripts/androidw.sh installDebug
./scripts/androidw.sh -PAPI_CORE_URL=https://api.example.com installDebug
```

Do not call `apps/android/gradlew` directly in normal repo workflows.

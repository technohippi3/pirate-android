# Android App (Kotlin)

## Build & Install

Both `JAVA_HOME` and `GRADLE_USER_HOME` must be set for gradlew for all tasks (build, install, compile, test).
Do not run `./gradlew` without both prefixes inline.

Use this path in this environment:

```bash
JAVA_HOME=/home/x42/.local/share/jdks/jdk-17.0.18+8 GRADLE_USER_HOME=/tmp/gradle-x42
```

In restricted/sandboxed runners, execute Android Gradle commands unsandboxed/escalated.
If Gradle startup fails with `Could not determine a usable wildcard IP for this machine` or
`java.net.SocketException: Operation not permitted (Socket creation failed)`, rerun unsandboxed immediately.

Build/install example:

```bash
JAVA_HOME=/home/x42/.local/share/jdks/jdk-17.0.18+8 GRADLE_USER_HOME=/tmp/gradle-x42 ./gradlew installDebug
```

Compile check example:

```bash
JAVA_HOME=/home/x42/.local/share/jdks/jdk-17.0.18+8 GRADLE_USER_HOME=/tmp/gradle-x42 ./gradlew :app:compileDebugKotlin
```

Run from `pirate-android/`:

```bash
JAVA_HOME=/home/x42/.local/share/jdks/jdk-17.0.18+8 GRADLE_USER_HOME=/tmp/gradle-x42 ./scripts/androidw.sh installDebug
```

## Key Directories

- `app/src/main/java/sc/pirate/app/` — Main app code
- `app/src/main/java/sc/pirate/app/onboarding/` — Onboarding flow
- `app/src/main/java/sc/pirate/app/crypto/` — Device-local content crypto helpers
- `app/src/main/java/sc/pirate/app/profile/` — Profile screen
- `app/src/main/java/sc/pirate/app/assistant/` — AI chat (Assistant)
- `app/src/main/java/sc/pirate/app/music/` — Music / content access

# AGENTS (`apps/android`)

## Scope

- Applies to `apps/android/`.

## Build Path (Required)

- Use repo wrapper only:

```bash
./scripts/androidw.sh <task>
```

- Do not run `./gradlew` directly in agent tasks.
- Default to low-impact Android builds because full-parallel Gradle runs can freeze this machine.
- For agent-driven Android builds/install flows, prefer:

```bash
PIRATE_ANDROID_SLOW=1 PIRATE_ANDROID_MAX_WORKERS=1 ./scripts/androidw.sh <task>
```

- Only raise `PIRATE_ANDROID_MAX_WORKERS` above `1` if the user asks for a faster build or explicitly accepts higher machine load.

## Reliability Notes

- If Gradle startup fails with wildcard-IP/socket creation errors, rerun unsandboxed immediately.

## Code/Doc Alignment

- Route-level product claims must match `PirateRoute.kt` + registered nav hosts.
- Publish/verify/post docs must reflect the actual Compose flow in `music/PublishScreen.kt` and `identity/SelfVerificationGate.kt`.

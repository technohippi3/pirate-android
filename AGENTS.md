# AGENTS (`apps/android`)

## Scope

- Applies to `apps/android/`.

## Build Path (Required)

- Use repo wrapper only:

```bash
./scripts/androidw.sh <task>
```

- Do not run `./gradlew` directly in agent tasks.

## Reliability Notes

- If Gradle startup fails with wildcard-IP/socket creation errors, rerun unsandboxed immediately.

## Code/Doc Alignment

- Route-level product claims must match `PirateRoute.kt` + registered nav hosts.
- Publish/verify/post docs must reflect the actual Compose flow in `music/PublishScreen.kt` and `identity/SelfVerificationGate.kt`.

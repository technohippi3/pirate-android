# AGENTS (`scripts/`)

## Scope

- Applies to files under `scripts/`.

## Rules

- Keep script interfaces stable for repo operators.
- `androidw.sh` is the canonical Android entrypoint; preserve its contract unless task explicitly changes Android workflow.
- Document new script args/env expectations in `scripts/README.md` when adding scripts.
- When invoking `androidw.sh` from an agent, default to `PIRATE_ANDROID_SLOW=1 PIRATE_ANDROID_MAX_WORKERS=1` unless the user explicitly wants a faster, heavier build.

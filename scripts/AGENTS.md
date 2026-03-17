# AGENTS (`scripts/`)

## Scope

- Applies to files under `scripts/`.

## Rules

- Keep script interfaces stable for repo operators.
- `androidw.sh` is the canonical Android entrypoint; preserve its contract unless task explicitly changes Android workflow.
- Document new script args/env expectations in `scripts/README.md` when adding scripts.

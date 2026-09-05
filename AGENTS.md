# Repository Instructions

## Development verification

- Match verification to the change's scope and risk; avoid full builds or test matrices for every small edit.
- Reuse passing results for unaffected scope; rerun checks when relevant changes or failures warrant it.
- Complete necessary broader verification once edits settle, preserving required acceptance checks.

## Release changes

- Use Conventional Commits for release-facing changes (`feat:`, `fix:`, and `!` / `BREAKING CHANGE`
  for incompatible contracts). Describe consumer-visible behavior, including migration requirements.
- Release Please owns version promotion and the release changelog. Review its Release PR before
  merging; do not create prepare commits or manually bump SDK versions during implementation.
- Preserve exact-commit CI evidence and the original signed candidate across publication retries.

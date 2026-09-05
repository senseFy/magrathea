# Repository Instructions

## Development verification

- Match verification to the change's scope and risk; avoid full builds or test matrices for every small edit.
- Reuse passing results for unaffected scope; rerun checks when relevant changes or failures warrant it.
- Complete necessary broader verification once edits settle, preserving required acceptance checks.

## Changelog discipline

- Every user-visible behavior or contract change must update `CHANGELOG.md` under `Unreleased` in
  the same change or pull request.
- Put each entry under the appropriate `Added`, `Changed`, `Fixed`, or `Security` subsection and
  describe the consumer-visible outcome rather than the implementation steps.
- Internal refactors, tests, build-only maintenance, and documentation-only corrections may omit an
  entry when they do not change shipped behavior.
- Implementation changes must not create a dated release section or change `magrathea.version`.
  `scripts/prepare-release` owns version promotion and release-note generation.
- Release notes are generated only from the reviewed `Unreleased` section; do not reconstruct them
  from commit subjects at release time.

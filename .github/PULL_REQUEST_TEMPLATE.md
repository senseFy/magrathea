## Summary

Describe the user-visible or contract-level outcome.

## Boundary

- Affected modules and platforms:
- Architecture layer:
- Public API, wire protocol, persistence, or distribution impact:

## Verification

List exact commands and results. Explain any evidence that could not be produced locally.

- [ ] Added or updated the nearest meaningful regression/contract test.
- [ ] Ran the focused module tests.
- [ ] Ran the smallest applicable repository gate.

## Contract and documentation review

- [ ] Public API changes include an intentionally reviewed ABI diff.
- [ ] Serialization or protocol changes include a version/fixture decision and fail-closed tests.
- [ ] Credentials, private content, endpoints, and raw Provider failures remain outside persisted and diagnostic state.
- [ ] The squash title follows Conventional Commits; incompatible contracts and migration steps are described.
- [ ] Samples, Known Issues, and other owned documentation are updated where applicable.
- [ ] The change does not introduce generated build output, credentials, or unrelated formatting.

Delete no checklist item merely because it does not apply; mark it as not applicable and explain why.

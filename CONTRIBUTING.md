# Contributing to Magrathea

Magrathea is an Alpha Kotlin Multiplatform agent runtime. Contributions should preserve its focused,
Provider-neutral runtime boundary and add tests that demonstrate behavior at the nearest meaningful
integration boundary.

By participating, you agree to follow the project [Code of Conduct](CODE_OF_CONDUCT.md).
Unless stated otherwise, contributions submitted for inclusion in Magrathea are licensed under the
[MIT License](LICENSE).

## Before you start

Read:

- [Architecture](docs/architecture.md)
- [Behavior Contracts](docs/behavior-contracts.md)
- [Known Issues and External Gates](docs/known-issues.md)
- the relevant decision record under [`docs/adr`](docs/adr)

Base requirements are JDK 17 and the checked-in Gradle wrapper. Repository verification scripts are
exercised on macOS and Ubuntu and additionally require Bash, Ruby, and Python 3.9 or newer; native
Windows is not currently a verified host environment. Android work additionally requires the
Android SDK; Apple work requires macOS/Xcode; browser work uses the toolchains provisioned by the
Gradle tasks.

## Development workflow

1. Keep a change within one documented layer or explain any new dependency direction.
2. Implement the behavior and add the smallest reasonable contract or regression test that would
   fail without it.
3. Add a consumer-visible summary under `CHANGELOG.md`'s `Unreleased` section when the change
   affects shipped behavior or a public contract.
4. Run the narrow module test while iterating, then the smallest relevant repository gate.
5. Update public documentation, serialization fixtures, or ABI baselines only when the contract
   intentionally changes.
6. Before requesting review, ensure the worktree contains no credentials, generated build output,
   or unrelated formatting.

Useful gates:

```bash
./gradlew verifySdkQuick
./gradlew verifySdkLinux
./gradlew verifySdkApple
./gradlew verifySdkWeb
./gradlew clean verifySdkRelease
```

`verifySdkApple` requires an appropriate macOS/Xcode environment, and its Simulator app consumer
requires a booted iOS Simulator. The complete local release gate is intentionally expensive; use
focused module tests during normal edit/compile cycles.

## Public contracts

- Provider adapters emit canonical events through `magrathea-provider-api`; do not introduce a
  second chunk representation or silent protocol fallback.
- Credentials must remain outside sessions, checkpoints, provider options, persisted diagnostics,
  and browser code.
- Persistence uses strict versioned envelopes. Unknown or corrupt input fails closed; schema upgrade
  behavior requires an accepted decision record, fixtures, and cross-platform tests.
- A public API change must update the applicable ABI baseline and pass `verifySdkCompatibility`.
- Browser vendor access remains Gateway-only.

## Documentation

- Put one topic on each page and lead with the behavior or decision a reader needs.
- Link to the authoritative topic instead of repeating protocol, release, or verification details.
- Keep examples executable in shape and separate secret values from configuration.
- Record release evidence only in the verification, known-issues, and release documents that own it.

### Release changes

Use Conventional Commits in main-branch commits or squash PR titles: `feat:` for features,
`fix:` for fixes, and `!` / `BREAKING CHANGE` for incompatible contracts. Describe the consumer
impact and any migration steps. Release Please maintains the version and changelog in a Release PR;
review that PR before merging. See [Release Process](docs/release-process.md).

## Pull requests and issues

Describe the user-visible behavior, affected platforms, tests executed, and any evidence that could
not be produced locally. Keep unrelated changes separate. For defects, include a minimal
reproduction without credentials or private conversation data.

Use the repository issue forms for reproducible defects and scoped feature proposals. Questions
that do not yet contain a concrete defect or proposal may use a GitHub Discussion if Discussions
are enabled; otherwise, open an issue and clearly label it as a question. Pull requests should
complete the checked-in template rather than deleting evidence or compatibility sections.

Security-sensitive reports do not belong in public issues. Follow [SECURITY.md](SECURITY.md).

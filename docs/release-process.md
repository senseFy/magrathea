# Release Process

This is the maintainer runbook for an immutable Magrathea release. Consumer repository configuration
is in [Publishing](publishing.md).

## Version

The release version is `magrathea.version` in `gradle.properties`.

```bash
scripts/prepare-release 0.1.0-alpha.6
```

This is the maintainer's single preparation entry point. Start with reviewed release-facing changes
under `CHANGELOG.md`'s `Unreleased` section, then run the command from a clean worktree. It validates
that the new SemVer advances the current version, promotes `Unreleased` under the supplied date,
generates release notes, updates controlled current-version references, previews every Maven
coordinate, and runs `verifySdkQuick`. Use `--date YYYY-MM-DD` to override today's date or
`--dry-run` to inspect its file plan without changing the worktree.

The command deliberately does not commit, push, create a tag, or publish. Review the generated
release notes and known-issue wording before committing. Its final output prints the exact files to
stage and the next command. The release tag remains
`v<magrathea.version>`; its matching changelog entry must have a date and
`docs/releases/v<tag>.md` must exist.

## Release command

After the reviewed preparation commit is on local `main`, finish the release through one command:

```bash
scripts/release 0.1.0-alpha.6
```

The command shows one plan and asks for one `[y/N]` confirmation. It fast-forwards `origin/main` to
the exact local commit, waits for that commit's push-triggered `Verify Magrathea SDK` run, dispatches
the version-and-commit-bound `Release Magrathea SDK` workflow, and waits for the GitHub Release. It
requires a clean worktree, local `main`, an authenticated GitHub CLI, matching prepared version
metadata, dated changelog notes, and generated release notes. If `origin/main` advances while CI is
running, the command stops before dispatch; the workflow independently rejects any dispatch whose
checkout does not match the explicitly authorized commit.

Use `--dry-run` to validate and inspect the plan without pushing, dispatching, or waiting. Use
`--yes` for an explicitly authorized non-interactive invocation; without it, non-interactive input
is rejected. Active Verify and Release status displays refresh every ten seconds to limit
authenticated GitHub API traffic. There is no CI or release-gate bypass.

The command is safe to repeat. It reuses successful or running exact-SHA Verify and
version-and-commit-bound Release runs. A concurrently submitted duplicate authorization is rejected
by the workflow before Candidate construction or any publication side effect. If the Release run
failed, the command stops and points to that run's **Re-run failed jobs** action; the maintainer
reruns the command afterward to resume watching it.

## Verification gates

| Gate | Purpose |
|---|---|
| `./gradlew verifySdkQuick` | Fast contracts for normal pull requests |
| `./gradlew verifySdkCompatibility` | Published JVM ABI, serialization fixtures, and Web TypeScript shape |
| `./gradlew verifySdkSupplyChain verifySdkSupplyChainContract verifyCiContract` | Wrapper, dependency, SBOM/license, and workflow-pin policy |
| `./gradlew clean verifySdkRelease` | Full Android, JVM, Apple, Web, publication, consumer, sample, and release-bundle gate |
| `./gradlew clean prepareSdkRelease` | Assemble signed release evidence after the exact commit passed the full CI gate |

The full CI gate validates the publication graph and isolated consumers. Candidate preparation
does not repeat platform tests; it assembles the signed artifacts for that exact successful commit.
The workflow runs the full matrix for every pull request. On `main`, its push trigger is limited to
`gradle.properties`, so ordinary PR merges do not repeat the matrix while every prepared release
version still receives an exact-SHA gate. Remote publication adds registry resolution and
provenance evidence.

### Intentional API changes

Review the public API change before refreshing ABI baselines:

```bash
./gradlew apiDump
git diff -- '*/api/**/*.api' '*/api/*.api'
```

Do not run `apiDump` merely to make a failing check green. Serialization fixtures change only with
the associated format-version decision, strict-decoding policy, release notes, and cross-platform
tests.

## Release evidence

Supply-chain tasks write:

```text
build/reports/supply-chain/magrathea-sbom.cdx.json
build/reports/supply-chain/third-party-licenses.tsv
```

The SBOM is a production dependency inventory, not a claim that a remote advisory scan ran or that
the release is vulnerability-free. The nightly workflow scans the normalized SBOM with the pinned
OSV-Scanner version and retains its own report.

Build and inspect the release bundle:

```bash
./gradlew verifySdkReleaseBundle
```

The release candidate contains:

- the release bundle and checksum;
- the complete signed Maven repository;
- the standalone Web archive with exact runtime notices and full third-party license texts;
- the coordinate inventory and SHA-256 file manifest;
- the SBOM, license report, and verification receipt.

The receipt binds the candidate to its source commit, successful CI run, version, repository,
module inventory, release key, coordinates, manifest, and bundle digest.

The exact-SHA workflow run, candidate receipt, Candidate attestation, and GitHub Release assets are
the per-release verification record; no parallel manual run log is maintained.

## Remote publication rules

All remote Maven releases must be:

- built once from a clean commit whose exact SHA passed CI;
- signed with an in-memory PGP key;
- promoted from the attested release candidate without rebuilding;
- byte-checked against the candidate manifest before and after upload;
- written to a repository that enforces immutable versions.

The `release-candidate` job owns signing and the verification receipt; later jobs never receive the
signing key. The candidate is checked against the authorized source and every PGP signature is
verified with the committed public key before the workflow creates its annotated tag. The workflow
then publishes four disjoint logical-module shards on Linux, uploads each POM last, and resolves an
isolated JVM/Android consumer before creating the GitHub Release.

For a wholly new version, one global POM-absence preflight allows the shards to avoid hundreds of
initial read requests. A rerun probes every staged file, accepts only byte-identical remote content,
and fills missing files. Repository-side immutability remains mandatory.

Candidate preparation and Maven publication emit a heartbeat every five minutes and run under
bounded workflow and step timeouts.

## GitHub release

Repository secrets:

- `MAGRATHEA_SIGNING_KEY`: ASCII-armored private release key
- `MAGRATHEA_SIGNING_PASSWORD`: release-key passphrase

`GITHUB_TOKEN` supplies repository-scoped package and release access. Each job receives only the
permissions it needs for artifacts, provenance, tagging, package publication, or release creation.

Release sequence:

1. Summarize the release under `CHANGELOG.md`'s `Unreleased` section, then run
   `scripts/prepare-release <version>`. Review its generated release notes and known-issue wording.
2. Commit or merge the prepared files onto local `main`. The preparation command has already
   inspected `scripts/publish-sdk --print` and run `./gradlew verifySdkQuick`.
3. Authorize and follow the remaining release once:

   ```bash
   scripts/release 0.1.0-alpha.6
   ```

   The command pushes the exact commit, waits for exact-SHA CI, and follows `Release Magrathea SDK`
   while it builds and verifies one Candidate, attests it, creates the annotated tag, publishes its
   exact signed Maven bytes, reads back every coordinate, verifies an isolated JVM/Android consumer,
   and creates the GitHub Release.
4. Confirm the GitHub Release contains the bundle, checksum, coordinate inventory, Maven manifest,
   SBOM, license report, and receipt. The command prints its URL when the workflow completes.

If the run is interrupted after Candidate preparation, `scripts/release` identifies the existing
failed run and directs the maintainer to GitHub's **Re-run failed jobs** on that same run. Successful
jobs are retained, so publication reuses the same Candidate and uploads only missing bytes after
comparing existing remote files. Do not start a new dispatch or choose **Re-run all jobs** after a
tag or any package bytes exist. A digest mismatch, invalid signature, failed gate, or missing
provenance requires a new version.

The workflow rejects a version outside `origin/main`, a version mismatch, an undated changelog,
missing release notes, mismatched Candidate evidence, conflicting remote bytes, or a coordinate that
cannot be read back. Existing tags must be annotated and point to the exact authorized commit.
SemVer prereleases become GitHub Pre-releases.

Tags and published coordinates are immutable. Fix a failed release under a new version; do not move
or force-push a published tag.

## Rollback

Published versions are immutable. Never delete, replace, or re-upload a Maven coordinate, Web
archive, checksum, or SBOM under the same version.

For a bad release:

1. Stop promotion and record the impact.
2. Pin consumers to the most recent verified previous version.
3. Leave both versions intact for auditability.
4. Fix forward under a new patch version.
5. Re-run the complete release gate and application smoke before promotion.

`./gradlew verifySdkRollbackContract` rehearses this policy with synthetic current and previous
inventories. It does not mutate a real repository.

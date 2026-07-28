# Release Process

This is the maintainer runbook for an immutable Magrathea release. Consumer repository configuration
is in [Publishing](publishing.md).

## Version

The release version is `magrathea.version` in `gradle.properties`.

```bash
scripts/publish-sdk --print --version 0.1.0-alpha.1
```

The release tag is `v<magrathea.version>`. Its matching changelog entry must have a date and
`docs/releases/v<tag>.md` must exist.

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
Remote publication adds registry resolution and provenance evidence.

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
- the coordinate inventory and SHA-256 file manifest;
- the SBOM, license report, and verification receipt.

The receipt binds the candidate to its source commit, successful CI run, candidate run, version,
repository, module inventory, release key, coordinates, manifest, and bundle digest.

## Remote publication rules

All remote Maven releases must be:

- built once from a clean commit whose exact SHA passed CI;
- signed with an in-memory PGP key;
- promoted from the attested release candidate without rebuilding;
- byte-checked against the candidate manifest before and after upload;
- written to a repository that enforces immutable versions.

Candidate preparation owns signing and the verification receipt. The tag workflow never receives
the signing key. Before upload, the candidate is checked against the tagged source and every PGP
signature is verified with the committed public key. The tag workflow publishes four disjoint
logical-module shards on Linux, uploads each POM last, and resolves an isolated JVM/Android consumer
before creating the GitHub Release.

For a wholly new version, one global POM-absence preflight allows the shards to avoid hundreds of
initial read requests. A rerun probes every staged file, accepts only byte-identical remote content,
and fills missing files. Repository-side immutability remains mandatory.

Candidate preparation and Maven publication emit a heartbeat every five minutes and run under
bounded workflow and step timeouts.

## GitHub release

Repository secrets:

- `MAGRATHEA_SIGNING_KEY`: ASCII-armored private release key
- `MAGRATHEA_SIGNING_PASSWORD`: release-key passphrase

`GITHUB_TOKEN` supplies repository-scoped package and release access. The workflow grants only
`packages: write`, `contents: write`, and the permissions required for provenance attestation.

Release sequence:

1. Update the version, changelog date, release notes, verification status, known issues, and
   supported-version wording.
2. Inspect `scripts/publish-sdk --print`, run `./gradlew verifySdkQuick`, and push the exact release
   commit to `main`.
3. Require the `Verify Magrathea SDK` workflow to pass.
4. Run `Build Magrathea Release Candidate` for the exact commit. Inspect the candidate, bundle,
   receipt, and attestations.
5. Create and push one annotated tag:

   ```bash
   git tag -a v0.1.0-alpha.1 -m "Magrathea 0.1.0-alpha.1"
   git push origin v0.1.0-alpha.1
   ```

6. Require `Publish Magrathea Release` to pass. It promotes the oldest successful candidate for the
   tag commit, verifies both attestations, publishes the exact signed Maven bytes, reads back every
   coordinate, and resolves an isolated JVM/Android consumer.
7. Confirm the GitHub Release contains the bundle, checksum, coordinate inventory, Maven manifest,
   SBOM, license report, and receipt.

If publication is interrupted, rerun the same `Publish Magrathea Release` workflow. The rerun
revalidates the candidate and remote files, then uploads only missing bytes. A digest mismatch,
invalid signature, failed gate, or missing provenance requires a new version.

The tag workflow rejects a lightweight tag, a tag outside `origin/main`, a version mismatch, an
undated changelog, missing release notes, missing or mismatched candidate evidence, conflicting
remote bytes, or a coordinate that cannot be read back. It marks SemVer prereleases as GitHub
Pre-releases.

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

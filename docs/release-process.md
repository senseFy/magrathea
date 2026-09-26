# Release Process

## Publish

1. Merge changes into `main` using Conventional Commits (`feat:`, `fix:`, and `!` or
   `BREAKING CHANGE` for incompatible contracts). Release Please maintains one Release PR for the SDK.
2. Review its version, `CHANGELOG.md`, and compatibility notes. If GitHub shows
   **Approve workflows to run**, approve the bot PR checks. After CI passes, merge the Release PR;
   this authorizes publication. If editing the changelog manually, review it again after any bot
   refresh, which may regenerate the entry.
3. Follow **Verify Magrathea SDK**, then **Release Magrathea SDK**, in GitHub Actions. The GitHub
   Release appears after signed Maven artifacts and an isolated remote consumer are verified.

All SDK modules share `magrathea.version` in `gradle.properties`. Release Please updates that value,
its manifest, and marked documentation/consumer references together. Release notes in the bundle
and GitHub Release are generated from the committed changelog. Historical note files remain intact.
There is no local prepare commit or second normal publishing command.

To choose a specific next version, include a `Release-As: <version>` footer in a Conventional Commit
on `main`. The current configuration advances alpha versions. For a stable release, set
`prerelease` to `false` in `release-please-config.json` and select the stable version in that change.

## Verify locally

```bash
make release-check
```

This checks the real pinned Release Please configuration, metadata, workflow wiring, and recovery
with local fixtures; it does not build SDK artifacts or write to GitHub. Use the relevant Gradle
platform gates for SDK changes; complete broader verification once changes settle.
`scripts/verify-ci-cache-contract` checks cache transfer with isolated Java builds: a version-only
change reuses compilation across workspaces, while a source change recompiles.

PRs run the complete platform matrix. After a Release PR merges, CI reuses its latest successful
run only when the recorded checkout and final commit have identical Git file trees. This includes
version files, dependencies, tests, workflow definitions, and declared toolchains. Missing or expired
evidence, a newer unsuccessful run, or changed content triggers the complete matrix on `main`.
The persistence schema baseline is checked against the merge's parent even when tests are reused.

Gradle task outputs are shared through optional per-platform snapshots from successful Verify jobs.
A PR restores snapshots from the verified PR merged at its base commit; candidate preparation uses
the pinned release CI run. Each snapshot keeps up to 256 MiB of recent task outputs for seven days,
excluding dependency caches, configuration state, and locks. Restores check the source job/attempt
and file checksums; Gradle decides reuse from task inputs, including version-dependent inputs.
Missing, expired, or invalid snapshots fall back to ordinary builds and never authorize publication.
Dependency downloads continue to use `setup-gradle` caching. Platform gates produce new snapshots
even when compilation hits the cache, so subsequent PRs do not need a main build to refresh them.

Publication pins the source CI run and attempt. Its receipt retains the tested commit and tree
alongside the final release commit. Partial reruns can retain successful jobs and checkout records
from earlier attempts of that same run. Candidate preparation assembles signed artifacts without
repeating the platform matrix; upload shards and retries use the same candidate bytes. Restoring
an existing candidate does not depend on the source CI artifact still being available.

Maven shards upload and verify candidate files with two concurrent requests by default.
Set `MAGRATHEA_MAVEN_WORKERS=1` for serial transfers. Exact-file transfers require Python 3
and curl 7.75 or newer; logs report phase timings, connection counts, and retries.

## Recover

```bash
make release-status RELEASE_VERSION=<version>
make release-retry RELEASE_VERSION=<version>
```

Retry runs only failed jobs of the original publication. Existing Maven bytes must match the
candidate; only missing files are uploaded, with POMs last. A repeated candidate job restores its
existing artifact. Do not delete tags, artifacts, or package versions to force a retry. Candidates
are retained for 90 days; missing evidence after publication requires a new version.

For a source fix after a Release PR merged, prefer a higher version. If the failed candidate has
no GitHub Release, run `scripts/release supersede <PR-number>` to unblock Release Please.
Commit the fix with a higher `Release-As` footer and carry the superseded version's reviewed changes
into the next Release PR's changelog. Existing tags and package bytes remain immutable. Consumers
can pin a previous verified version while a forward fix is prepared.

## Repository setup

Enable Actions to create pull requests, and require `linux-gate`, `web-gate`, and both `apple-gate`
checks before merging. The bot uses `GITHUB_TOKEN`; GitHub may require a maintainer to approve
its PR workflows. No additional long-lived token is needed. Keep `MAGRATHEA_SIGNING_KEY` and
`MAGRATHEA_SIGNING_PASSWORD` as repository secrets. Only candidate preparation receives them.

The release retains the bundle/checksum, coordinate inventory, Maven manifest, SBOM, license report,
receipt, and candidate provenance. Nightly advisory scans, physical-device checks, and Gateway
operations remain separate evidence. Consumer setup and local/custom publication are in
[Publishing](publishing.md).

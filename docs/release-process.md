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

CI verifies PRs and the exact release merge commit. Candidate preparation reuses that successful
CI result instead of repeating the platform test matrix. It still assembles signed release artifacts.
All upload shards and retries use the same candidate files without rebuilding the SDK.

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

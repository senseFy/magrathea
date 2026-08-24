# Publishing

Magrathea stages signed Maven artifacts with Gradle `maven-publish`; the release workflow promotes
those exact files to GitHub Packages. Released artifacts are available from that registry.

The current release signing key uses fingerprint
`E868 39BB 6660 EB87 A440 ADA7 AA11 3A8A 65F1 AFD3`; the
[public key](release-signing-key.asc) is committed with the source.
Each release note identifies its exact signing key.

## Coordinates

The group and version come from `gradle.properties`:

```text
group: saien.magrathea
version: 0.1.0-alpha.6
artifact: logical module name
```

Examples:

```text
saien.magrathea:magrathea-core:0.1.0-alpha.6
saien.magrathea:magrathea-runtime:0.1.0-alpha.6
saien.magrathea:magrathea-chatbot:0.1.0-alpha.6
saien.magrathea:magrathea-provider-openai:0.1.0-alpha.6
```

Kotlin Multiplatform generates target variants from the 16 logical modules. Consumers declare the
logical module, not a target-specific publication.

Inspect every generated coordinate:

```bash
scripts/publish-sdk --print
```

`--print --version <version>` previews a different version without editing files.

## Local publish

Publish the complete verified SDK to `~/.m2/repository`:

```bash
scripts/publish-sdk
```

Equivalent Gradle command:

```bash
./gradlew clean verifySdkRelease publishSdkToMavenLocal
```

Publish one logical module:

```bash
scripts/publish-sdk --module :magrathea-core
```

Then consume it from another Gradle build:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("saien.magrathea:magrathea-runtime:0.1.0-alpha.6")
    implementation("saien.magrathea:magrathea-chatbot:0.1.0-alpha.6")
    implementation("saien.magrathea:magrathea-provider-openai:0.1.0-alpha.6")
}
```

Use `mavenLocal()` only for local development. Published consumers should resolve from the
registry that owns the immutable release.

## GitHub Packages

The configured GitHub Packages repository is:

```text
https://maven.pkg.github.com/sensefy/magrathea
```

Publishing requires a clean verified commit, repository credentials, and an in-memory PGP key:

```bash
MAGRATHEA_SIGNING_KEY="$(cat private-key.asc)" \
MAGRATHEA_SIGNING_PASSWORD=key-password \
GITHUB_SAIEN_MAVEN_USERNAME=your-user \
GITHUB_SAIEN_MAVEN_TOKEN=your-token \
scripts/publish-sdk --target github
```

Direct remote Gradle publish tasks fail closed. `scripts/publish-sdk` creates the signed release
candidate; the authorized release workflow publishes the candidate's manifest-bound files without
rebuilding them.

### Consume

GitHub Packages requires authentication for Gradle packages. A local consumer needs a personal
access token (classic) with `read:packages`; a GitHub Actions consumer may use `GITHUB_TOKEN` after
the workflow repository has access to the package. See GitHub's
[Gradle registry documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry).

Store local credentials in user-level `~/.gradle/gradle.properties`, never in the project:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_CLASSIC_PAT_WITH_READ_PACKAGES
```

Configure the consuming build:

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "MagratheaGitHubPackages"
        url = uri("https://maven.pkg.github.com/sensefy/magrathea")
        credentials {
            username = providers.gradleProperty("gpr.user")
                .orElse(providers.environmentVariable("GITHUB_PACKAGES_USERNAME"))
                .orNull
            password = providers.gradleProperty("gpr.key")
                .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
                .orNull
        }
        content {
            includeGroup("saien.magrathea")
        }
    }
}
```

For GitHub Actions, map the repository-authorized token and actor to
`GITHUB_PACKAGES_TOKEN` and `GITHUB_PACKAGES_USERNAME`. Never put credentials in source, command-line
arguments, checked-in properties, or dependency metadata.

GitHub Packages does not currently provide anonymous Gradle package reads for public repositories.
The token is a registry requirement, not a Magrathea runtime credential.

## Custom Maven repository

```bash
MAGRATHEA_PUBLISH_USERNAME="$MAVEN_USERNAME" \
MAGRATHEA_PUBLISH_PASSWORD="$MAVEN_PASSWORD" \
MAGRATHEA_SIGNING_KEY="$(cat private-key.asc)" \
MAGRATHEA_SIGNING_PASSWORD="$PGP_PASSWORD" \
scripts/publish-sdk \
  --target custom \
  --repo-url https://repo.example.com/releases \
  --repo-name Releases
```

Repository and signing credentials are environment-only. The publisher rejects non-HTTPS remote
repositories, dirty worktrees, skipped release tests, unsigned publications, and coordinates that
already exist. The repository itself must also enforce immutable release versions.

Maintainers publish through one guarded local command backed by the manually authorized
`Release Magrathea SDK` workflow. The workflow requires successful CI for the exact `main` commit,
builds and verifies the signed Candidate once, creates the annotated tag, publishes four exact-byte
Maven shards, validates all remote coordinates, resolves an isolated JVM/Android consumer, and
creates the GitHub Release.

Prepare the reviewed version metadata and local quick gate through the single maintainer entry
point:

```bash
scripts/prepare-release 0.1.0-alpha.6
```

It performs no commit, push, tag, or remote publication. Review and commit the prepared files onto
local `main`, then run:

```bash
scripts/release 0.1.0-alpha.6
```

That command presents one confirmation, pushes the exact commit, waits for exact-SHA CI, and starts
or resumes the version-and-commit-bound release workflow. It stops before dispatch if `main` moves,
and the workflow rejects a mismatched commit or a second authorization for the same version before
publication side effects. `--dry-run` only validates and prints the plan; `--yes` is required for
non-interactive use. [Release Process](release-process.md) is the full runbook.

With signing material in the environment, prepare a candidate without remote side effects:

```bash
scripts/publish-sdk --target github --prepare-only
```

If publication is interrupted, rerun `scripts/release <version>`. It refuses a duplicate dispatch
and points to **Re-run failed jobs** on the same workflow run. Existing bytes must match the
Candidate manifest; only absent files are uploaded, and each POM is uploaded last. Do not start
another dispatch after the tag or package bytes exist.

Inspect a remote Gradle task graph without publishing:

```bash
scripts/publish-sdk --target github --dry-run
```

## Web artifacts

Build the browser JS/TypeScript package and Wasm preview:

```bash
./gradlew verifyWebSdkPackage verifyWebTypeScriptConsumer
```

The staged package is under `build/web-package/magrathea-web-client`; its archive is under
`build/distributions`. The archive includes `THIRD_PARTY_NOTICES.txt` and full reviewed license
texts under `LICENSES/`; the package gate exact-matches those notices to the resolved Gradle and
bundled npm runtime. No repository task publishes it to npm.

## Rollback

Published artifacts are immutable. Never delete, replace, or re-upload a Maven coordinate, Web
archive, checksum, or SBOM under the same version. Pin consumers to the most recent verified
version, record the impact, and fix forward under a new version.

The rehearsal task and operational checklist are in
[Release Process](release-process.md#rollback).

## Maven Central

The generated artifacts include sources, Dokka javadoc, POMs, checksums, and optional PGP
signatures. Magrathea is not currently published to Maven Central. Namespace approval, credentials,
Portal upload, policy review, and promotion remain external release work.

Maintainer gates, evidence, versioning, rollback, and the release checklist are documented in
[Release Process](release-process.md).

# ADR-011: Distribution and Release Evidence

- Status: Accepted
- Date: 2026-07-12

## Decision

- Every publishable module produces complete Maven metadata, sources, Dokka documentation, and the
  artifacts for its supported targets. Every archive carries exactly one byte-identical copy of the
  canonical project `LICENSE`.
- Build-local repositories are consumed by isolated JVM, Android, Apple, JS, Wasm, and TypeScript
  builds. Samples compile against published artifacts instead of project dependencies.
- Public JVM-bearing APIs are checked against committed ABI dumps; stored and wire formats are
  checked against committed fixtures.
- The release gate validates fixed dependency versions, the Gradle wrapper, pinned GitHub Actions,
  a production CycloneDX SBOM, third-party licenses, and negative supply-chain mutations. The
  aggregate SBOM and license report include npm/generated runtime code present in the Web bundle.
- The standalone Web archive exact-matches its resolved Gradle runtime and bundled npm/generated
  runtime against a reviewed ledger. It carries complete vendored license texts, preserves any
  upstream `NOTICE` files found in resolved artifacts, and rejects source maps with local absolute
  paths.
- Remote Maven publication requires in-memory PGP signing and an immediate immutable-coordinate
  preflight. Repository credentials and signing material are environment-only.
- The exact commit passes the complete CI gate before release authorization. One manually
  dispatched workflow builds and signs the Candidate once, verifies its provenance, receipt,
  signatures, coordinate inventory, and file manifest, then creates the annotated tag and promotes
  those exact bytes without rebuilding.
- Maven promotion uses disjoint logical-module shards. New versions receive one global absence
  preflight; reruns compare every existing file with the candidate and upload only absent bytes.
  POMs are uploaded last so a partial coordinate is not advertised as complete.
- Versions are immutable. Rollback means pinning a consumer to a previous verified version or
  publishing a forward fix, never overwriting an existing coordinate.
- CI may build, test, package, upload a Candidate, and attest provenance. One explicit manual
  dispatch authorizes Candidate preparation, tagging, and promotion for a named version; Gateway
  deployment remains separately authorized.

## Evidence boundary

Deterministic fixtures, simulators, loopback servers, SBOMs, and browser engines establish local and
CI evidence only. Physical-device breadth, deployed-Gateway operations and penetration review,
remote advisory scans, signed repository candidates, and real application validation remain
separate release evidence.

## Consequences

Distribution and verification tooling increases repository maintenance cost but does not enter the
runtime dependency graph. Contributors can run platform-specific gates without invoking remote
Providers or publication side effects.

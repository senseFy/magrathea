# ADR-002: Namespace and Publication Coordinates

- Status: Accepted
- Date: 2026-07-11

## Decision

- Public Kotlin packages use the `saien.magrathea` namespace.
- Maven publications use the `saien.magrathea` group.
- Artifact IDs match logical module names; Kotlin Multiplatform target variants use Gradle's
  conventional target suffixes.
- Serialized polymorphic types use explicit stable names instead of JVM class names.
- Every published JVM-bearing module has a committed ABI baseline. Versioned wire and persistence
  formats have committed serialization fixtures.

## Consequences

Package names, publication metadata, ABI dumps, serialization names, samples, and documentation
must change together when a public boundary changes. Platform variants remain implementation
details of a logical module rather than separately designed SDK products.

# ADR-001: Supported Platforms and Toolchain

- Status: Accepted
- Date: 2026-07-11

## Context

Magrathea needs one portable runtime model while preserving platform-specific persistence,
credential, networking, and packaging behavior. Browser code also requires a different credential
boundary from non-browser applications.

## Decision

- Android, JVM/Desktop, `iosArm64`, and `iosSimulatorArm64` are supported Alpha targets.
- Browser JS is supported through the Gateway path; browser Wasm is an experimental target on the
  same path.
- Desktop means JVM embedding. No Kotlin/Native macOS, Windows, or Linux target is published.
- The build uses Kotlin 2.4.0, Android Gradle Plugin 9.1.1, Gradle 9.3.1, and JDK 17.
- Android uses the Kotlin Multiplatform Android library plugin and has `minSdk` 24.

## Consequences

Portable contracts live in `commonMain`; target source sets contain only platform implementations.
Aggregate published-consumer Apple framework linkage, Android host compilation, JVM artifacts, and
browser execution are release boundaries. Hosts provide their own UI, application lifecycle,
packaging, and update mechanism.

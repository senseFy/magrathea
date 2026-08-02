# Verification Status

This document records the evidence used to qualify `0.1.0-alpha.2`. It separates reproduced SDK
evidence from platform assumptions and external validation.

## Current local evidence

| Area | Evidence |
|---|---|
| Core and Runtime | JVM, Android host, iOS Simulator, JS, and Wasm contracts for schema-v5 persistence, typed Tool content/audiences/origin, media references, Provider interruptions, retry, reattachment, terminal cleanup, portable Web/Image/X Search, cancellation, resume, limits, telemetry, and context management |
| MCP | Official Kotlin SDK linked-transport contracts for initialization, bounded discovery, fail-closed dynamic list changes, stale-executor rejection, policy, Tasks compatibility, typed text/image/audience projection, bounded Tool calls, origin mapping, names, and transport security; a real child-process stdio initialize/discovery/call test with an isolated environment; official conformance `initialize` and `tools_call` scenarios passed over real loopback Streamable HTTP |
| Provider adapters | Contract, request/codec, transport, semantic-completion, typed Tool image mapping, failure classification, and JVM controlled-live mock-server coverage for Gemini, OpenAI Responses, OpenAI Chat Completions, and Anthropic; controlled remote Gemini plus OpenRouter Responses-compatible and Messages-compatible non-streaming, SSE, tool-loop, and PDF attachment evidence, including a Grok 4.5 compacted-reasoning two-turn three-tool loop on 2026-07-17 |
| Android | Current published-consumer/instrumentation compilation plus a recorded 2026-07-12 SM-S9180/API 36 fixture run covering Keystore, process restart, Room corruption/deletion, Android HTTP/Gemini parsing, socket cancellation, and a 1,000-message baseline |
| JVM/Desktop | Runtime, chatbot facade, real Room database, published-artifact consumer, controlled Gemini and OpenRouter-compatible Provider live validation, and deterministic Provider-neutral sample executed locally on macOS JVM |
| iOS | Build-local Maven resolution followed by aggregate device/Simulator consumer-framework linkage, plus Simulator tests that directly compose the public chatbot facade, Keychain, Room, Runtime, and Gemini adapter |
| Browser | Gateway exact-v2 protocol, authenticated real-HTTP/SSE JS/Wasm sample, durable replay/abandonment contracts, IndexedDB contracts, TypeScript consumer, production bundles, and Playwright Chromium/Firefox/WebKit-engine execution |
| Distribution | 16 published logical modules, 88 build-local Maven coordinates, aggregate device/Simulator Apple consumer frameworks, source/javadoc/POM/metadata checks, and release-bundle checksum validation |
| API/schema and supply chain | 14 JVM ABI baselines, strict schema-v5 serialization fixtures, Gateway exact-v2 contracts, fixed dependency versions, CycloneDX SBOM/license validation, mutation tests, remote-signing guards, and an immutable-coordinate rollback rehearsal |

The `0.1.0-alpha.2` release-preparation quick gate passed on 2026-08-02:

```text
BUILD SUCCESSFUL in 56s
317 actionable tasks: 44 executed, 273 up-to-date
```

The separate Web package gate produced a 1,394,588-byte minified entry point within its explicit
1,420,000-byte ceiling. JS and Wasm browser contracts passed.

The most recent complete clean release gate passed on 2026-07-26 for `0.1.0-alpha.1`:

```text
BUILD SUCCESSFUL in 18m 39s
1575 actionable tasks: 1513 executed, 62 up-to-date
```

The gate included release-bundle checksum validation; the JVM sample ended with
`providerNeutralFacade=passed`; and the cross-browser matrix passed on Chromium 149, Firefox 151,
and WebKit 26.5 for both JS and Wasm. The MCP conformance `initialize` and `tools_call` scenarios
were then rerun separately against version `0.1.16` of the harness and both passed with zero
warnings. A subsequent formatting-only ABI-baseline preservation commit passed
`./gradlew verifySdkQuick` on the exact pushed source revision. Documentation-only release
preparation changes must pass that same quick gate before they are committed; the complete clean
gate remains required again for the exact release commit.

The consumer-boundary Apple graph was then clean-built on 2026-07-28:

```text
BUILD SUCCESSFUL in 2m 11s
1174 actionable tasks: 957 executed, 140 from cache, 77 up-to-date
```

This gate compiled every mobile KMP publication for device and Simulator, ran the Simulator
contracts, resolved the complete graph through the build-local Maven repository, and linked the two
aggregate static consumer frameworks.

## What the evidence does not prove

- Linux and Windows are JVM-compatible targets, but the candidate has no recorded Windows host run.
  The committed Ubuntu workflow is the Linux verification path.
- The aggregate iOS device consumer framework builds, but no iOS physical-device application matrix
  has run.
- One Android fixture is not a broad API/OEM/reboot/backup/device-performance matrix.
- WebKit browser-engine automation is not execution of the Safari application; real mobile-browser
  and deployed-Gateway evidence remains external.
- OpenAI and Anthropic compatibility was validated through OpenRouter, not directly against
  `api.openai.com` or `api.anthropic.com`; no live smoke proves every endpoint, account policy,
  quota behavior, or long-running traffic.
- OpenAI Chat Completions is covered by deterministic contracts and has not yet been exercised by a
  controlled remote smoke test.
- MCP conformance covers client initialization and synchronous Tool calls. Resources,
  Prompts, Sampling, Roots, Elicitation, experimental Tasks, HTTP OAuth discovery/flows, and a broad
  third-party server matrix remain outside the MCP Tool adapter boundary.
- SBOM, credential-isolation, and negative-contract gates are not a penetration test or
  production security certification.
- The rollback check is a build-local loopback rehearsal, not recovery from a real published
  repository version.
- No Maven Central, npm, deployed Gateway, or production application validation has occurred.
- GitHub Packages publication proves the tagged artifacts were signed, uploaded, and resolved; it
  is not equivalent to validation through every supported consumer platform or network policy.

## Gate selection

```bash
./gradlew verifySdkQuick       # fast JVM/Android-host contracts and compatibility checks
./gradlew verifySdkLinux       # Linux-compatible JVM/Android SDK graph
./gradlew verifySdkApple       # published Apple graph linkage and Simulator tests
./gradlew verifySdkWeb         # JS/Wasm tests, packages, samples and browser engines
./gradlew clean verifySdkRelease # complete clean release gate and bundle
```

With an authorized Android device:

```bash
MAGRATHEA_ANDROID_SERIAL=YOUR_ADB_SERIAL ./gradlew verifyAndroidDevice
```

Provider live validation requires an explicitly supplied credential and model; see the
[live harness guide](../tooling/provider-live-harness/README.md) and
[Provider capability matrix](provider-capability-matrix.md). Publishing and remote-signing gates are
documented in the [Release Process](release-process.md).

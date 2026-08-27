# Changelog

Notable changes to Magrathea are documented here. The project follows
[Semantic Versioning](https://semver.org/) for published releases.

## Unreleased

### Added

- Core tracing primitives, coroutine context propagation, and Runtime spans for executions, turns,
  context preparation, Provider requests, Tools, and persistence.
- A default-disabled `MagratheaDebugRecorder` port for bounded, content-free Runtime diagnostics
  with session, run, turn, Provider-attempt, and optional trace correlation.

### Changed

- The Web package gate reports the production bundle size and uses a 2,000,000-byte Alpha ceiling
  so normal runtime growth remains visible without requiring per-feature budget changes.
- The Alpha telemetry API and `DefaultAgentRunner.telemetry` parameter are replaced by one
  content-free `MagratheaTracer` integration; no compatibility or dual-write layer is retained.
- Debug diagnostics are removed from `AgentEvent`; hosts receive them only through the dedicated
  recorder port.

## 0.1.0-alpha.6 — 2026-08-24

### Added

- Hosts can share request-aware execution permits across Tools and Web Search backends to cap
  concurrent calls while keeping admission queue time outside each Tool's execution timeout.

### Changed

- Release status watching now refreshes every ten seconds to reduce authenticated GitHub API
  traffic without changing the guarded release sequence.

## 0.1.0-alpha.5 — 2026-08-22

### Added

- Maintainers can finish a prepared release through one guarded command that pushes the exact
  commit, waits for its CI result, and starts or resumes the version-and-commit-bound release
  workflow.

### Changed

- Ordinary pull-request merges no longer repeat the full cross-platform CI matrix on `main`, while
  version-changing release commits retain exact-SHA verification before publication.

## 0.1.0-alpha.4 — 2026-08-19

### Added

- Provider failures now distinguish accepted credentials with denied resource access from
  authentication failures across HTTP transport, reference adapters, Runtime failure codes, and
  the Chatbot facade.

### Fixed

- Interrupted OpenAI Responses and Anthropic recovery replays now omit non-portable reasoning
  blocks while preserving replayable answer text and Tool calls instead of rejecting the request.

## 0.1.0-alpha.3 — 2026-08-09

### Added

- Provider-neutral reasoning preferences and model-declared reasoning capabilities across Core,
  Chatbot, Gateway, the reference Provider adapters, and the JavaScript/TypeScript facade.
- SDK-owned logical storage schema evolution with payload-free failure classification, an
  append-only schema ledger, frozen schema-v6 adapters and fixtures, and atomic Room/IndexedDB
  rewrite boundaries for validated migrations.

### Changed

- Gateway protocol and HTTP paths advance to exact-v3 so browsers send only neutral reasoning
  intent and servers resolve trusted model capabilities and Provider wire values.
- Session and checkpoint envelopes advance to schema v6. Shipped schema-v5 data remains an
  intentional Alpha clean break; future readable revisions require contiguous adjacent migrations.
- The browser facade exposes reasoning capabilities and per-session preferences; its model
  constructor and generated TypeScript surface change accordingly.
- Published archives now carry the canonical project license, while the aggregate SBOM and license
  report include npm and generated runtime code bundled into the standalone Web SDK.

## 0.1.0-alpha.2 — 2026-08-02

### Added

- Typed multimodal Tool results with explicit model/user audiences, Provider-native image mapping,
  MCP image mapping, Chatbot image projection, and strict Runtime/Gateway limits.
- Typed, bounded Tool origin projected through Core, MCP, and the Chatbot facade without exposing
  protocol metadata.
- A Provider-neutral Image Search Tool with an injectable host backend, bounded HTTPS results,
  attribution, license metadata, Safe Search, and stable failure codes.
- Typed, durable Provider interruptions with stable failure codes, stream phase, and optional
  `Retry-After` recovery timing across Runtime and the Chatbot facade.
- Durable Provider invocation identities with explicit create, reattach, interruption, and
  terminal-abandonment contracts.

### Changed

- Gateway protocol and HTTP paths advance to v2 for typed Tool attachment references.
- Session and checkpoint envelopes advance to schema v5 for typed Tool result content, Tool origin,
  and interruption metadata.
- Runtime retries only transient failures before the first canonical event, enforces bounded
  error-aware backoff and `Retry-After`, and resumes post-output failures from a safe checkpoint.
- Direct Provider adapters classify a clean stream end before semantic completion as recoverable;
  a semantic `Completed` remains authoritative over a later transport disconnect.
- Telemetry distinguishes interrupted sessions and records whether a Provider event was observed.
- Runtime commits terminal state before bounded remote abandonment; failed commits preserve the
  recovery checkpoint and pending invocation.

## 0.1.0-alpha.1 — 2026-07-26

### Added

- Provider-neutral Kotlin Multiplatform Core and Runtime for Android, JVM/Desktop, iOS, browser JS,
  and experimental browser Wasm.
- Streaming Agent turns, canonical Provider events, tools, retry, cancellation, checkpoints,
  resume, history, hard resource limits, and optional content-free telemetry.
- Token-aware semantic context management with full-history preservation, persisted incremental
  summaries, Provider-usage anchoring, Tool-boundary safety, and one typed overflow-recovery retry.
- Layered connection, first-event, stream-idle, Provider-call, Tool-execution, and whole-run
  timeouts with typed failures and portable defaults.
- A Provider-neutral Web Search Tool with an injectable host backend, run-level call limits,
  bounded/domain-filtered HTTPS sources, structured policy, stable failure codes, and canonical
  citation metadata.
- An optional Model Context Protocol Tool adapter based on the official Kotlin SDK, with portable
  Streamable HTTP, JVM stdio, dynamic discovery/list-change handling, policy-controlled Tool
  exposure, secure credential injection, and official client conformance coverage.
- Gemini Interactions, OpenAI Responses, and Anthropic Messages reference adapters with one explicit
  wire contract each, canonical defaults, and explicit compatible-endpoint configuration.
- A Provider-neutral Chatbot facade with immutable snapshots, text and attachment input,
  regeneration, cancellation, resume, history, deletion, and deterministic resource ownership.
- Explicit session-level Provider/model selection, persisted configuration switching between runs,
  model-aware history and resume, and multi-Provider routing through one browser Gateway client.
- Room persistence on Android/JVM/iOS, IndexedDB persistence in browsers, Android Keystore and iOS
  Keychain credential stores, and explicit JVM credential injection.
- A strict Gateway v1 protocol, JVM Gateway server ports, bounded SSE replay, quota/audit hooks, and
  a Gateway-only browser Provider adapter.
- A browser Chatbot composition, JavaScript/TypeScript archive, Wasm preview, and authenticated
  real-HTTP sample.
- Isolated Android, JVM, Apple, JS, Wasm, and TypeScript consumers that resolve build-local published
  artifacts.
- ABI baselines, serialization fixtures, provider transcripts, platform contract tests, and an
  explicitly invoked remote Provider live harness.
- Gradle Wrapper validation, fixed dependency versions, pinned CI actions, CycloneDX SBOM and
  license checks, supply-chain mutation tests, PGP signing guards, immutable-coordinate preflight,
  and release-bundle checksums.
- Verification workflows for JVM/Android, Apple, Web, dependency review, nightly advisory scanning,
  and manually authorized release-candidate assembly.

Current limitations and uncompleted external validation are maintained in
[Known Issues and External Gates](docs/known-issues.md).

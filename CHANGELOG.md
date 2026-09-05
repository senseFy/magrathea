# Changelog

Notable changes to Magrathea are documented here. The project follows
[Semantic Versioning](https://semver.org/) for published releases.

## Unreleased

## 0.1.0-alpha.10 — 2026-09-05

### Changed

- Trace recording now bounds active spans, admission rate, attributes, events and retained text at
  construction. Completed handles release their event/attribute graphs; sink cancellation and
  direct or wrapped fatal errors propagate.
- Removed the debug recorder API and per-chunk debug path. Provider spans contain fixed incremental
  request summaries, first-text timing and typed failures without retaining message histories.
  Execution outcomes distinguish completed, failed, cancelled, interrupted and recovery blocked.

- Runtime propagates direct and wrapped fatal errors to the host instead of converting them into
  Agent failures, retries, or fail-open results. Ordinary exceptions retain their typed failure
  behavior, while cancellation remains cooperative control flow, including in tracing
  integrations.

### Fixed

- Managed sessions no longer remain falsely active after a fatal error or cancellation ends their
  collector without a terminal event. Late `ACTIVE` recovery observations cannot revive a settled
  execution or prevent its cleanup.
- Unavailable or inconsistent managed-session recovery reads block fresh execution instead of
  treating an existing checkpoint as absent. Hosts can retry `inspectRecovery` or explicitly cancel;
  failed cancellation commits also keep pending recovery fenced until it can be resolved.
- Presentation-only session events no longer discard otherwise valid recovery observations;
  changes to execution results still invalidate older observations even if their values later match.
- Tool permit release and Runtime cleanup failures no longer mask an earlier fatal error or
  cancellation. Independent cleanup steps are attempted, with fatal failures taking priority and
  other failures preserved as suppressed context.
- Failed managed-session shutdown prevents destructive persistence deletion or clearing; manager
  close still attempts every independent cleanup and replays the same result to later callers.

## 0.1.0-alpha.9 — 2026-09-04

### Added

- Models can declare a catalog-provided maximum output-token capability, including through the
  browser facade, while an explicit request budget can replace stale or unknown catalog metadata.
- `AgentSessionManager` and independently releasable session leases provide process-local canonical
  execution ownership, replay-one live state, restore-only attachment, and fenced catalog mutation.
- Managed-session suspend APIs declare typed manager and cancellation failures for Kotlin/Native
  callers.
- Chatbot hosts can restore persisted or manager-owned live sessions without implicitly starting
  Provider work; repeated restores create independent facades over the canonical runtime.

### Changed

- Runtime now resolves one output-token bound for both normal and context-summary Provider calls,
  clamps known bounds to the final Provider projection's remaining context, and preserves an
  unknown bound when neither request policy nor model metadata supplies one.
- Stored sessions and checkpoints advance to schema 7 with an additive migration from schema 6;
  existing models migrate with an unknown output-token capability and are rewritten atomically
  after successful validation.
- Chatbot controllers are state projections over managed Agent leases. Closing a session detaches
  without stopping work; closing an owning client interrupts the manager root, while a borrowed
  client leaves that root running.
- Chatbot facade delivery is fenced against concurrent client close, and concurrent close callers
  now await one cleanup result instead of returning early or hiding the owning caller's failure.

### Fixed

- Managed lease and Chatbot facade handoffs now remain inside destructive-operation fences through
  cancellation-safe delivery, so concurrent delete, clear, or close cannot overtake an admitted
  attachment.
- Managed cancellation now preserves a completed or failed result that won the same-run terminal
  race, including when persistence committed before the terminal event reached the manager.
- Historical storage migrations no longer inherit behavior from live serializers, so later schema
  revisions cannot invalidate the supported schema-6 migration baseline.
- Default Runtime tracing preserves synchronous run admission, so a managed session can be
  interrupted or cancelled immediately after `start` or `resume`, before any business event or
  dispatcher advance. Successful control also drains the manager-owned collector before a later
  command is admitted.
- Failed managed-session delete or clear operations now report whether canonical runtimes were
  already invalidated, and Chatbot clients close the affected facades even when persistence
  removal fails.

## 0.1.0-alpha.8 — 2026-08-29

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

### Security

- The Web artifact pins patched `ws` and Webpack releases for known advisory fixes, and Nightly
  failures now list affected packages and advisories directly in the job log and summary.

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

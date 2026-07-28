# Changelog

Notable changes to Magrathea are documented here. The project follows
[Semantic Versioning](https://semver.org/) for published releases.

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

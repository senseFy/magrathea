# Behavior Contracts

This ledger summarizes the invariants enforced by executable tests. It is a navigation aid, not a
substitute for the public API or the tests themselves.

## Core and Provider

| ID | Invariant | Nearest verification boundary |
|---|---|---|
| C-001 | `ProviderChunk` contains canonical events; `Completed` is unique and last | Provider API common contracts |
| C-002 | Runtime resolves credential references at call time; reference adapters receive only the transient value, while persisted and diagnostic state contains none | Runtime, adapter-auth, serialization, and secret-canary tests |
| C-003 | HTTP/SSE applies body, line, and event limits, requires HTTPS except on exact loopback hosts, rejects URL userinfo/fragments, and distinguishes protocol terminal state from transport completion | Transport common tests and JVM loopback server |
| C-004 | HTTP, authentication, rate-limit, context-limit, server, network, and protocol errors map to typed failures without exposing Provider payloads | Transport and Provider codec contracts |
| C-005 | Gemini, OpenAI-family, and Anthropic preserve authoritative request/replay metadata; OpenAI-family profiles bind Provider identity, wire protocol, endpoint defaults, and dialect | Provider profile, request/codec, and adapter transport fixtures |
| C-006 | A custom Provider needs only the public `ProviderAdapter` boundary | Isolated JVM sample and consumer |
| C-007 | Each adapter's declared attachment MIME capability is an encodable wire upper bound; unsupported or mismatched MIME/data-URL input fails closed without exposing payload data | Provider capability and request-codec contracts |
| C-008 | Visible reasoning is classified as Provider-defined, summary, or explicitly exposed text and follows independent start/delta/end block lifecycles; signatures, encrypted content, and redacted state remain opaque and never enter product-visible text | Provider codec, canonical assembler, Chatbot projection, and serialization contracts |

## Runtime and tools

| ID | Invariant | Nearest verification boundary |
|---|---|---|
| R-001 | Tools execute only after finalization and once per call identity | Provider → Runtime → Tool → follow-up request |
| R-002 | Unknown tools, permission denial, and approval denial fail closed | Runtime and Policy contracts |
| R-003 | Cancellation remains cancellation and is not converted to retry or a generic failure | Runtime, socket, and Chatbot contracts |
| R-004 | Before the first canonical event, retry handles only stable transient Provider failures and is bounded by attempts, error-aware backoff, and `Retry-After`; after the first event Runtime checkpoints instead of starting a fresh attempt | Retry and recovery contracts |
| R-005 | Snapshot and checkpoint commit atomically with matching session/run identity; resume starts from an exact phase, creating a new direct-Provider attempt or reattaching through an adapter that explicitly supports durable replay | Persistence, checkpoint, and resume contracts |
| R-006 | Turns, messages, queues, attachments, tool results, and backpressure have hard limits | Limit and stress contracts |
| R-007 | Telemetry is disabled by default and accepts no prompt, message, reasoning, tool payload, credential, or endpoint | Telemetry canary contracts |
| R-008 | Per-turn Tool-call limits use the stricter registered/request definition; independent per-run counters persist across turns, injected user messages, checkpoints, and resume; excess parallel or sequential calls are rejected without execution | Runtime Tool-limit contracts |
| R-009 | Portable Web Search validates structured policy and queries, bounds and domain-filters HTTPS sources, emits citations, redacts failures, and preserves cancellation | Web Search common contracts |
| R-010 | Provider-neutral X Search prevents host-policy widening, bounds untrusted evidence and citations, and keeps hosted `x_search_call` activity out of the local Tool executor path | X Search Runtime and OpenAI Responses contracts |
| R-011 | MCP initialization, paginated Tool discovery, list-change refresh, and Tool calls use the official Kotlin SDK while remaining behind the ordinary Core `ToolRegistry`; public operation failures never retain raw server/transport messages | MCP linked-transport and official conformance harness |
| R-012 | MCP Tool names are deterministic and Provider-portable; duplicate remote names/cursors, stale contracts after list-change, configured size/count limits, and incompatible required-Task Tools fail closed | MCP naming, pagination, refresh, result-limit, and compatibility contracts |
| R-013 | MCP annotation values remain untrusted hints; host policy controls enablement, permissions, approval, timeout, and call budgets | MCP policy contracts |
| R-014 | Remote MCP endpoints require HTTPS except for loopback, reject URL userinfo credentials and transport-owned headers, and resolve credential headers only when connecting | MCP transport security contracts |
| R-015 | JVM MCP stdio starts only an explicitly configured process shape, passes only explicitly supplied environment entries, and completes initialization/discovery/call over real child-process streams | MCP stdio process and integration contracts |
| R-016 | Connection, first Provider event, canonical stream idle, Provider call, Tool execution, and complete Agent run have independent deadlines; Provider timeout is a typed recoverable interruption, while Tool and whole-run deadlines retain their own terminal semantics | Transport and Runtime timeout contracts |
| R-017 | Provider, host, and orphaned interruptions retain a replay-safe checkpoint; Tool recovery reuses completed results, executes pending calls, and blocks unknown started side effects unless the executor declares replay safety | Runtime recovery contracts |
| R-018 | Full history remains authoritative while the Provider receives a token-budgeted cumulative summary plus recent raw messages; Provider usage is preferred over estimation, Tool call/result boundaries are atomic, history edits invalidate stale state, and only a pre-output context-limit failure may force one retry | Context-management unit and Runtime integration contracts |
| R-019 | Typed Tool result text/images carry explicit model/user audiences; user-only content never reaches a Provider, model images require declared input capability, inline media is bounded by decoded bytes, and external Tool identity reaches products only through typed origin | Core, Runtime, Provider request, MCP, and Chatbot contracts |
| R-020 | Portable Image Search validates policy, HTTPS media/source URLs, attribution, MIME types, deduplication, result limits, failure redaction, replay-stable media references, and user-only image projection | Image Search common and Runtime contracts |
| R-021 | Runtime detaches Provider collection locally; terminal cancellation or failure removes the checkpoint before bounded remote abandonment, while a failed terminal commit preserves the pending invocation for recovery | Runtime recovery and Gateway contracts |

## Chatbot, storage, and credentials

| ID | Invariant | Nearest verification boundary |
|---|---|---|
| S-001 | Chatbot snapshots are reduced from Agent events and terminal state matches the persisted session | Chatbot contracts |
| S-002 | Session and checkpoint codecs accept only the committed strict schema-v5 envelope, including run identity, exact resume cursor, interruption metadata, context state, Tool journal, typed Tool content, and Tool origin | Serialization fixtures |
| S-003 | A corrupt Room row is isolated and produces a content-free report | Room JVM, Android, and iOS contracts |
| S-004 | Store handles and facade close operations are idempotent; closed resources reject further use | Ownership contracts |
| S-005 | Android credentials use Keystore AES-GCM and no-backup ciphertext | Android host and device fixture |
| S-006 | iOS credentials use device-only Keychain items | iOS Simulator consumer |
| S-007 | JVM credentials are explicitly supplied by the host | JVM facade and sample |
| S-008 | IndexedDB stores strict envelopes and no Gateway or vendor credential | JS/Wasm browser contracts |
| S-009 | Session Provider profile/model selection drives requests, history, persistence, switching, and resume; profile Provider identity must match the model, and active generation rejects switching without cancellation | Chatbot facade and browser contracts |

## Gateway and Web

| ID | Invariant | Nearest verification boundary |
|---|---|---|
| W-001 | Browser requests cannot carry vendor credentials, upstream endpoints, or arbitrary Provider headers | Gateway Provider negative contracts |
| W-002 | Gateway `exact-v2` validates version, owner, tenant, request, session, stream, idempotency identity, and model-directed Tool attachment references | Protocol and server contracts |
| W-003 | SSE sequence is continuous, replay is bounded, disconnect and Runtime resume reattach by stable invocation identity, and terminal streams are immutable | Server, Provider, and browser E2E |
| W-004 | Client cancellation performs best-effort remote cancellation while preserving local cancellation | Real-HTTP JS/Wasm sample |
| W-005 | Attachments use pre-uploaded, re-authorized references | Gateway attachment contracts |
| W-006 | Cookie/bearer authentication, CSRF, quota, and audit execute through injected server ports | Gateway server security contracts |
| W-007 | A Provider context-limit failure remains typed across Gateway server, exact protocol, browser Provider, Runtime, and Chatbot facade boundaries | Gateway server and Provider contracts |

## Distribution and release

| ID | Invariant | Nearest verification boundary |
|---|---|---|
| D-001 | 16 logical SDK modules generate 88 build-local Maven coordinates | Distribution verifier |
| D-002 | An isolated consumer resolves all 13 mobile KMP modules and links the graph into device and Simulator frameworks | Published-consumer Apple gate |
| D-003 | Public JVM-bearing ABI and serialization fixtures are release gates | API/schema verifier |
| D-004 | Isolated consumers resolve published JVM, Android, iOS, JS, and Wasm variants | Published-consumer gates |
| D-005 | Remote Maven publication requires in-memory PGP signing, publication-isolated signature outputs, and immutable coordinates | Publisher and rollback contracts |
| D-006 | The production SBOM has the exact internal-module set, recognized licenses, and no test dependencies | Supply-chain mutation gate |
| D-007 | The release bundle contains Maven, Web, release notes/key, SBOM/license, and SHA-256 artifacts | Release-bundle verifier |
| D-008 | Only a dated, version-matched annotated tag can promote an attested candidate for the exact successful CI commit | Tag, candidate, and workflow contracts |
| D-009 | Remote publication uses the candidate's signed, manifest-bound bytes; reruns reject mismatches and fill only absent files | Exact-publication contract |
| D-010 | Every remote coordinate and an isolated JVM/Android consumer are verified before the GitHub Release | Remote-resolution contract |

## Repository gates

| Command | Coverage |
|---|---|
| `./gradlew verifySdkQuick` | Core, Provider, Runtime, Chatbot, ABI, Android host, and focused JS/Wasm contracts |
| `./gradlew verifySdkLinux` | JVM/Android graph, published consumers, samples, and Maven distribution |
| `./gradlew verifySdkApple` | Published Apple graph linkage and Simulator composition tests |
| `./gradlew verifySdkWeb` | JS/Wasm tests, Gateway real HTTP, TypeScript, and browser engines |
| `./gradlew clean verifySdkRelease` | Complete clean release verification graph and bundle |
| `./gradlew clean prepareSdkRelease` | Signed evidence assembly after the exact commit passed CI |
| `./gradlew verifyAndroidDevice` | Keystore/no-backup, Room process/corruption, HTTP/cancel, and device baseline |
| `make verify-mcp-conformance` | Official MCP client initialize and Tool-call scenarios over real loopback Streamable HTTP |

Remote credentials, physical devices, deployed infrastructure, and publication permissions are
always explicit external inputs; normal deterministic gates do not contact a paid Provider.

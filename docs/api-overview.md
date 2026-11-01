# Public API Overview

Magrathea is assembled from small contracts. Applications can use Runtime directly or add the
Chatbot, Provider, Tool, persistence, and browser layers they need.

## Core

`magrathea-core` contains portable models and host-owned ports.

| Area | Primary APIs |
|---|---|
| Requests and events | `AgentRequest`, `AgentMessage`, `MessagePart`, typed Tool result content and origin, `ModelDescriptor`, `ReasoningPreference`, `AgentEvent` |
| Execution | `AgentRunner`, `ToolRegistry`, approval and permission gateways |
| State | `AgentPersistence`, strict snapshot and checkpoint codecs |
| Credentials | `CredentialRef`, `CredentialProvider`, transient `ProviderCredential` |
| Infrastructure | Injectable IDs and clocks, tracing primitives, context propagation |
| Replay | `ReplayPolicy`, optional `ReplayResult` exclusions, and the latest `ReplayReport` |

The application composition root selects the Provider, network engine, database, credential store,
and UI.

## Session Catalog

`magrathea-session-catalog` is an optional Core-only module. `SessionCatalog` exposes bounded keyset
pages, primary-key organization lookup, flat collections, conditional title writes, and candidate
attachment-reference lookup. It never restores or executes a session. `SessionCatalogProjector`
provides deterministic scalar projection and normalization; the default supports headless sessions.

`InMemorySessionCatalogStore` is an atomic reference backend with separate `persistence`, `catalog`,
`maintenance` and `migration` capabilities. Room and IndexedDB expose these from their store handles
as well. Structural upgrades leave old history at `INDEXING`; `maintenance.runBatch` advances a
persisted cursor, processing at most 50 source records per call. Projection runs outside write
transactions; writeback checks the source identity/version and current generation, then merges
current organization and recovery facts.
Unreadable sources remain intact and yield `PARTIAL` coverage. A completed pass is not repeated on
ordinary reopen. Hosts own scheduling, cancellation and joining maintenance before closing stores.

`maintenance.rebuild` checks the expected policy/generation and clears derived indexes while retaining
canonical data, organization and accepted activity order. Same-policy writers may continue; a policy
replacement requires fencing and draining old execution writers first. Collection search normalization
is rebuilt in bounded steps too. A malformed collection or a failing collection normalizer stops that
maintenance phase with an explicit failure; it is not silently counted as complete.

`migration` imports a frozen host organization source through typed batches of at most 50 collections
and assignments. The host supplies a source ID and whole-source fingerprint, retains its source backup,
and gates organization commands until the receipt is complete. Assignments bind only physical legacy
incarnations, including unreadable canonical records. Batch receipts and organization writes are atomic;
completed sources are not reimported, and clear invalidates unfinished imports.

Attachment-name migration fills missing `AttachmentPart.fileName` in both snapshot and checkpoint.
Targets include incarnation, rowVersion and a URI continuation; each URI page and patch contains at most
50 names. Callers must fence/drain the session and restore the patched record before admitting writers.
The first original envelopes are retained with canonical authority for explicit CAS recovery, survive
normal commits and index rebuilds, and keep their attachment references protected until session deletion.
`beginAttachmentNameScan` resumes a fixed typed receipt for the host metadata source and algorithm
version. Its bounded pages include opaque physical cursors and unreadable sources; advance each source
only after its name attempt, using the rowVersion returned by a successful patch. Managed sessions may
use `SKIPPED_MANAGED` only after the host's restore gate has attempted migration and owns that same
incarnation. Finishing checks the remaining physical tail atomically. Completed receipts survive reopen
and catalog rebuild, including their failure count, so ordinary cold starts never repeat the historical
scan. Explicit restart resets the scan attempt; restart and clear invalidate old progress tokens.
Room also indexes independent background request/result references and known-empty origin contexts;
unreadable background sources preserve their bytes and keep reference coverage incomplete. Reference
lookups never decode these envelopes. Hosts still protect live, draft and submitting references before GC.

`pageRecoveryCandidates` reads lightweight checkpoint, persisted-state and background-result facts;
it does not restore sessions or authorize execution. Snapshot commits and independent background writes
both maintain those facts. A completed snapshot can remain a candidate while background work is pending
or its result is unacknowledged. Regenerating history can revoke an acknowledgement. Unknown sources
remain conservative candidates, with coverage separate from ordinary header coverage.

Room upgrades its physical database from version 2 to 3; IndexedDB upgrades from version 1 to 2.
These structural upgrades retain canonical data and do not change envelope schema 9. Earlier SDK
versions cannot reopen the upgraded databases; downgrading requires a compatible pre-upgrade backup.

The Web adapter retains opaque payload strings and maintains
eight stores. Metadata key cursors answer identity queries without cloning canonical payloads. All Long
authority and cursor values cross its JS bridge as strings. Search uses bounded scan transactions:
short or empty pages can still have a continuation cursor, so callers must inspect the cursor and
`exhausted` rather than infer completion from entry count. Structurally invalid physical keys are
preserved and reported with a null diagnostic session ID. Recovery has a bounded key-only fallback
while some sources remain unindexed; complete indexes use the recovery index directly.

Applications that require acceptance order can prepare an activity ticket before execution and bind
it to a stable canonical input identity. Canonical-time consumers need no preparation calls. Initial
organization and activity context belong to the actual commit handle and must survive until commit
or a fenced, drained abandonment; a send returning does not prove persistence has completed.

`ChatbotSession.sessionId` is available immediately after creation. `prepareMessage` prepares only
an immutable input with a stable message ID; `sendPrepared` appends it to the current session history.
A prepared input belongs to one facade lifetime and allows one sending attempt. It is not a durable
idempotency key and must not be retried after an unknown execution outcome.

## Providers

`magrathea-provider-api` defines:

- `ProviderAdapter` and `ProviderRegistry`;
- Provider invocation restart and durable-stream reattachment semantics;
- canonical `ProviderEvent` values and typed failures;
- `HttpTransport` and portable transport configuration;
- strict typed options for Gemini, OpenAI, and Anthropic protocol families.

Reference adapters live in their own modules. Applications can register a custom adapter without
changing Runtime. OpenAI-family integrations use `OpenAiProviderProfile` to keep Provider identity,
wire protocol, endpoint defaults, and documented dialect behavior separate. The host retains
credential ownership and resolves credentials immediately before each call.

See [Providers](providers.md) for endpoint and authentication configuration, and the
[Provider Capability Matrix](provider-capability-matrix.md) for protocol coverage.

## Runtime

`DefaultAgentRunner` from `magrathea-runtime` coordinates:

- Provider turns and canonical streaming;
- Tool execution and approval;
- retry, explicit cancellation, recoverable interruption, checkpoints, and resume;
- semantic context compaction and context-limit recovery;
- Provider, Tool, and whole-run deadlines;
- hard limits and tracing.

Its constructor accepts Core ports explicitly. `InMemoryAgentPersistence` and
`InMemoryToolRegistry` are suitable for tests, previews, and intentionally ephemeral hosts;
persistent products should use a platform `AgentPersistence`.

`DefaultAgentSessionManager` composes an `AgentRunner` and its matching persistence into a
process-local ownership root. `create` and `acquire` return independently releasable
`AgentSessionLease` values for one canonical runtime. The lease `StateFlow` is authoritative for
late attachment; its edge-event flow is best-effort and non-replay. See
[Managed Agent sessions](session-management.md).

`delete` and `clear` commit canonical invalidation before persistence mutation. A failure after
that point exposes `AgentSessionException.invalidationScope`, while any retained record may be
restored only as a new runtime generation.

Capability contracts:

- [Context Management](context-management.md)
- [Timeouts](timeouts.md)
- [Web Search](web-search.md)
- [Image Search](image-search.md)
- [X Search](x-search.md)
- [Background Tools](background-tools.md)

Session-owned background execution (ADR-019) adds `BackgroundExecutionConfig` on
`RuntimeConfig.background`, `ToolBackgroundPolicy`/`ToolRecoveryPolicy` on `ToolExecutor`,
a `BackgroundToolExecutor` (backed by a `BackgroundExecutionStore`) shared by
`DefaultAgentRunner` and `DefaultAgentSessionManager`, `BackgroundSessionSnapshot` on the lease
snapshot, the lease's
`startContinuation`/`stopAll`/`cancelBackground`/`suspendBackground`/`resumeBackground` commands,
and the `ToolHandedOff`/`BackgroundExecutionSettled` events. Chatbot facades expose the same
projection through `ChatbotSnapshot.background` and
`ChatbotSession.continueWithBackgroundResults()`/`stopAll()`/`cancelBackground(...)`.

## MCP

`magrathea-mcp` adapts MCP Tools to the existing Core `ToolRegistry`. `McpServerConnection` owns one
protocol connection, `McpToolRegistry` aggregates connections, and `McpToolPolicyProvider` controls
enablement, approval, timeouts, and limits.

Portable Streamable HTTP is available on published KMP targets. JVM/Desktop also supports approved
local stdio processes. See [MCP](mcp.md).

## Chatbot

`magrathea-chatbot` provides a UI-neutral product facade:

| API | Purpose |
|---|---|
| `ChatbotClient` | Create or attach facades, resume, list, delete, and apply owning/borrowed close semantics |
| `ChatbotSession` | Observe, send, regenerate, cancel, interrupt, resume, and update configuration |
| `ChatbotSessionConfiguration` | Conversation-owned Provider, model, reasoning preference, and non-secret credential profile |
| `ChatbotSnapshot` | Immutable product-facing messages, status, run stop reason, usage, context, failures, and Tool activity with typed origin |
| `ChatbotRequestFactory` | Map session state to an `AgentRequest` and apply host defaults |

Create an owning root with `createChatbotClient(runner, ...)`, using the same `AgentPersistence` as
the Runner. Pass `closeResources` when the composition owns Provider transports or platform stores.
Use `createChatbotClient(manager, ...)` to borrow an existing managed-session root; closing that
client releases only its own Chatbot facades.

`ChatbotClient.history()` lists persisted sessions as compact items whose status reflects recovery
of orphaned runs. Hosts that derive their own summaries from stored messages use
`historyRecords()`, which pairs each complete stored snapshot with that status in one storage read.

`ChatbotException.invalidationScope` distinguishes an ordinary operation failure from a failed
destructive operation that already closed one or all registered facades.

`ChatbotSnapshot.toolActivities` derives Tool lifecycle from canonical messages, the active
execution's Tool journal, and live events. A call is `PENDING` until execution is admitted (for
example while waiting on a shared permit or approval), `RUNNING` once started, and terminal as
soon as that call completes. Typed user-audience image results and their stable `MediaReference`
values are available through `ChatbotToolResult.images`.

`ChatbotSnapshot.stopReason` exposes the canonical run-level reason, separately from each
message's stop reason. A `COMPLETED` status may carry `MAX_TURNS` when the runtime exhausted
its turn budget without a final answer. The reason survives session restoration and is cleared
when a new run starts; it does not change runtime stopping behavior.

`ChatbotSnapshot.replay` exposes the managed session's latest replay exclusion report. It lets a
product decide whether to display omitted partial messages without deleting history or implementing
Provider replay rules. Null means reporting is unavailable, while an empty exclusion list is a
known result. See [History replay and exclusions](replay.md).

## Persistence and credentials

| Module | Boundary |
|---|---|
| `magrathea-storage-room` | Atomic Room persistence for Android, JVM, and iOS |
| `magrathea-storage-web` | Atomic IndexedDB persistence, catalog queries and bounded maintenance for browser JS/Wasm |
| `magrathea-credentials` | Android Keystore and iOS Keychain adapters |

JVM credentials remain host-owned. Store handles and clients close idempotently and reject
subsequent operations once closed.

Logical session and checkpoint envelopes use schema 9, with schema 6 as the minimum readable
migration baseline.
Malformed payloads, unsupported older schemas, unsupported newer schemas, and migration failures
are classified separately. Room and IndexedDB adapters surface incompatible schemas rather than
silently hiding them as corrupt history; hosts may then present an upgrade or explicit reset flow.

## Browser and Gateway

`magrathea-web-client` composes Chatbot over IndexedDB and a `GatewayProviderAdapter`. Browser
sessions carry server-authorized Provider/model references and application authentication, while
vendor credentials and upstream endpoints remain behind the Gateway.

Gateway deployments compose `magrathea-gateway-server` with host implementations of
authentication, model resolution, attachment authorization, quota, audit, account storage, and
deployment topology.

See [Architecture](architecture.md#provider-neutral-runtime-and-chatbot-facade) for a complete
construction example.

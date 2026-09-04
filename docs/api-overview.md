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
| Infrastructure | Injectable IDs and clocks, tracing primitives, context propagation, debug recorder |

The application composition root selects the Provider, network engine, database, credential store,
and UI.

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
- hard limits, tracing, and bounded debug recording.

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
| `ChatbotSnapshot` | Immutable product-facing messages, status, usage, context, failures, and Tool activity with typed origin |
| `ChatbotRequestFactory` | Map session state to an `AgentRequest` and apply host defaults |

Create an owning root with `createChatbotClient(runner, ...)`, using the same `AgentPersistence` as
the Runner. Pass `closeResources` when the composition owns Provider transports or platform stores.
Use `createChatbotClient(manager, ...)` to borrow an existing managed-session root; closing that
client releases only its own Chatbot facades.

`ChatbotException.invalidationScope` distinguishes an ordinary operation failure from a failed
destructive operation that already closed one or all registered facades.

`ChatbotSnapshot.toolActivities` derives Tool lifecycle from canonical messages and live events
while using the canonical persisted state. Typed user-audience image results and their stable
`MediaReference` values are available through `ChatbotToolResult.images`.

## Persistence and credentials

| Module | Boundary |
|---|---|
| `magrathea-storage-room` | Atomic Room persistence for Android, JVM, and iOS |
| `magrathea-storage-web` | Atomic IndexedDB persistence for browser JS/Wasm |
| `magrathea-credentials` | Android Keystore and iOS Keychain adapters |

JVM credentials remain host-owned. Store handles and clients close idempotently and reject
subsequent operations once closed.

Logical session and checkpoint envelopes use schema 7, with schema 6 as the minimum readable
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

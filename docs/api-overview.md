# Public API Overview

Magrathea is assembled from small contracts. Applications can use Runtime directly or add the
Chatbot, Provider, Tool, persistence, and browser layers they need.

## Core

`magrathea-core` contains portable models and host-owned ports.

| Area | Primary APIs |
|---|---|
| Requests and events | `AgentRequest`, `AgentMessage`, `MessagePart`, `ModelDescriptor`, `AgentEvent` |
| Execution | `AgentRunner`, `ToolRegistry`, approval and permission gateways |
| State | `AgentPersistence`, strict snapshot and checkpoint codecs |
| Credentials | `CredentialRef`, `CredentialProvider`, transient `ProviderCredential` |
| Infrastructure | Injectable IDs, epoch and monotonic clocks, telemetry |

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
- hard limits and telemetry.

Its constructor accepts Core ports explicitly. `InMemoryAgentPersistence` and
`InMemoryToolRegistry` are suitable for tests, previews, and intentionally ephemeral hosts;
persistent products should use a platform `AgentPersistence`.

Capability contracts:

- [Context Management](context-management.md)
- [Timeouts](timeouts.md)
- [Web Search](web-search.md)
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
| `ChatbotClient` | Create, resume, list, delete, and close sessions |
| `ChatbotSession` | Observe, send, regenerate, cancel, interrupt, resume, and update configuration |
| `ChatbotSessionConfiguration` | Conversation-owned Provider, model, and non-secret credential profile |
| `ChatbotSnapshot` | Immutable product-facing messages, status, usage, context, failures, and Tool activity |
| `ChatbotRequestFactory` | Map session state to an `AgentRequest` and apply host defaults |

Create it with `createChatbotClient`, using the same `AgentPersistence` as the Runner. Pass
`closeResources` when the composition owns Provider transports or platform stores.

`ChatbotSnapshot.toolActivities` derives Tool lifecycle from canonical messages and live events
while using the canonical persisted state.

## Persistence and credentials

| Module | Boundary |
|---|---|
| `magrathea-storage-room` | Atomic Room persistence for Android, JVM, and iOS |
| `magrathea-storage-web` | Atomic IndexedDB persistence for browser JS/Wasm |
| `magrathea-credentials` | Android Keystore and iOS Keychain adapters |

JVM credentials remain host-owned. Store handles and clients close idempotently and reject
subsequent operations once closed.

## Browser and Gateway

`magrathea-web-client` composes Chatbot over IndexedDB and a `GatewayProviderAdapter`. Browser
sessions carry server-authorized Provider/model references and application authentication, while
vendor credentials and upstream endpoints remain behind the Gateway.

Gateway deployments compose `magrathea-gateway-server` with host implementations of
authentication, model resolution, attachment authorization, quota, audit, account storage, and
deployment topology.

See [Architecture](architecture.md#provider-neutral-runtime-and-chatbot-facade) for a complete
construction example.

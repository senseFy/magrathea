# Architecture

Magrathea is a Kotlin Multiplatform agent runtime. Its stable center is a Provider-neutral model,
Runtime, and set of host-owned ports; Provider adapters, product facades, platform storage, and the
browser Gateway are optional layers around that center.

The normative decision is [ADR-012](adr/ADR-012-project-positioning-and-layers.md).

## Layers

| Layer | Modules | Responsibility |
|---|---|---|
| Kernel | `magrathea-core`, `magrathea-provider-api` | Agent models, stores, credentials, Provider events, transports, and serialization |
| Runtime | `magrathea-runtime` | Turns, Tools, retry, checkpoints, managed session ownership, resume, context management, limits, and tracing |
| Capabilities | Provider adapters, `magrathea-mcp`, `magrathea-policy` | Reference protocols, MCP Tools, and reusable approval policy |
| Platform | `magrathea-storage-room`, `magrathea-storage-web`, `magrathea-credentials` | Persistence and protected mobile credentials |
| Product | `magrathea-chatbot`, `magrathea-web-client` | UI-neutral chatbot lifecycle and browser composition |
| Gateway | `magrathea-gateway-protocol`, `magrathea-provider-gateway`, `magrathea-gateway-server` | Browser-safe Provider access, replay, quota, and audit ports |

Dependencies point inward:

- Kernel contracts remain independent of UI toolkits, databases, product facades, and application
  lifecycles.
- Runtime coordinates ports whose credentials and product presentation remain host-owned.
- Optional adapters translate external protocols into canonical Core contracts.
- The application composition root selects implementations and owns their lifecycle.

## Runtime failure classification

Runtime boundaries classify failures by semantics rather than by catch breadth:

- A `CancellationException` with no fatal cause remains cooperative control flow and is rethrown
  unchanged.
- An ordinary `Exception` may be translated into the boundary's typed failure code, retry, or
  recovery result.
- An `Error`, including one wrapped anywhere in an exception cause chain, is fatal. Runtime does
  not convert it into an `AgentEvent.Failed`, interruption, retry, or session exception; the exact
  `Error` escapes to the host's coroutine failure boundary.

Mandatory cleanup runs before a failure leaves an owning boundary. Independent cleanup steps are
all attempted. If cleanup discovers an `Error`, that fatal failure takes precedence; other
failures are retained as suppressed context. This rule keeps ownership settlement deterministic
without presenting a process-level failure as Agent output.

## Provider-neutral Runtime and chatbot facade

Applications that need conversation state can compose Runtime with the optional Chatbot facade and
one or more Provider adapters:

```kotlin
dependencies {
    implementation("saien.magrathea:magrathea-runtime:0.1.0-alpha.10")
    implementation("saien.magrathea:magrathea-chatbot:0.1.0-alpha.10")
    implementation("saien.magrathea:magrathea-provider-openai:0.1.0-alpha.10")
}
```

```kotlin
val credentialRef = CredentialRef(provider = "openai", profile = "default")
val credentials = CredentialProvider { ref ->
    require(ref == credentialRef)
    ProviderCredential(loadApiKey())
}
val persistence = InMemoryAgentPersistence()
val provider = OpenAiProviderAdapter(OpenAiProviderProfile.openAi())
val runner = DefaultAgentRunner(
    providerRegistry = InMemoryProviderRegistry(listOf(provider)),
    toolRegistry = InMemoryToolRegistry(),
    persistence = persistence,
    credentialProvider = credentials,
)
val client = createChatbotClient(
    runner = runner,
    requestFactory = DefaultChatbotRequestFactory(),
    persistence = persistence,
    closeResources = { provider.close() },
)
val session = client.createSession(
    ChatbotSessionConfiguration(
        model = ModelDescriptor(
            provider = "openai",
            model = "your-model",
            supportsStreaming = true,
        ),
        credentialRef = credentialRef,
    ),
)
```

The Runner and Chatbot client must share the same `AgentPersistence` instance. A
`ChatbotSessionConfiguration` owns the conversation's Provider, model, and non-secret
`CredentialRef`; the host resolves the secret only when a request runs. Headless compositions can
use `DefaultAgentRunner` directly.

The owning Chatbot composition creates one process-local `AgentSessionManager`. The manager owns
execution collectors and canonicalizes all leases for a session ID. Chatbot sessions are product
projections over those leases; closing a session detaches it without stopping manager-owned work.
Applications that need a longer-lived root can construct `DefaultAgentSessionManager` themselves
and pass it to the borrowed `createChatbotClient(manager, ...)` overload. See
[Managed Agent sessions](session-management.md).

Provider identity, wire protocols, dialect profiles, endpoints, and authentication modes are
covered in [Providers](providers.md).

## Deployment paths

| Host | Composition |
|---|---|
| Android, iOS, JVM/Desktop | Compose Runtime or Chatbot directly with selected Providers, stores, Tools, and credentials |
| Browser JS/Wasm | Use `magrathea-web-client` with the Backend Gateway; vendor credentials never enter browser code |
| Server or headless process | Use `DefaultAgentRunner` directly or add the Chatbot facade when session-facing DTOs are useful |

Desktop support uses JVM embedding, with UI, packaging, updates, and credential integration
supplied by the host.

Browser support uses a Gateway that keeps vendor credentials and upstream endpoints behind the
backend boundary. Deployments provide authentication, model resolution, attachment authorization,
quota, and audit implementations.

## Host responsibilities

Magrathea supplies runtime and composition boundaries. Applications build the surrounding product
by providing:

- product UI, navigation, packaging, and distribution;
- product-specific orchestration composed from Runner, Tool, interceptor, and Provider extension
  points;
- JVM credential storage and platform policy;
- browser Gateway deployment, account, quota, and audit implementations;
- release qualification for the application's target environments.

## Capability guides

- [Public API](api-overview.md)
- [Providers](providers.md)
- [Context management](context-management.md)
- [MCP](mcp.md)
- [Web Search](web-search.md)
- [Image Search](image-search.md)
- [X Search](x-search.md)
- [Behavior contracts](behavior-contracts.md)

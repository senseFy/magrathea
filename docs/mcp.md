# MCP Tool Adapter

`magrathea-mcp` connects Model Context Protocol servers to Magrathea's existing Core
`ToolRegistry`. It uses the official Kotlin MCP client SDK and currently targets protocol version
`2025-11-25`.

Every mapped Tool result carries bounded Magrathea-owned identity fields for the server and Tool.
Presentation layers can read those fields through `JsonObject.mcpToolIdentityOrNull()`; the parser
does not expose endpoints, authentication values, transport details, arbitrary MCP metadata, or
stdio output. The Chatbot facade preserves this metadata while deriving the generic Tool activity
lifecycle used by UI-neutral consumers.

The lifecycle reports only evidence Magrathea actually observes at the Agent/Tool boundary. It
does not synthesize percentages, intermediate steps, or MCP progress notifications that the server
did not send. Protocol-native progress can be added later without changing the generic activity
model.

## Scope

The adapter supports:

- initialization and server capability discovery;
- bounded, paginated `tools/list`;
- dynamic `notifications/tools/list_changed` refresh;
- synchronous `tools/call`;
- Streamable HTTP on Android, JVM, iOS, JS, and Wasm;
- local stdio processes on JVM/Desktop;
- deterministic names for multiple servers;
- host-controlled enablement, permission, approval, timeout, and call limits;
- structured Tool results plus canonical MCP content metadata.

It deliberately does not auto-inject MCP Resources, Prompts, or server instructions into Agent
context. Sampling, Roots, Elicitation, completion, logging, and experimental Tasks are not enabled
by the Agent composition. A Tool whose server contract requires Tasks remains visible for
configuration diagnostics but is not advertised to a model.

## Dependency

```kotlin
implementation("saien.magrathea:magrathea-mcp:0.1.0-alpha.1")
```

## Streamable HTTP

The host owns the Ktor engine and client lifecycle:

```kotlin
val http = HttpClient(OkHttp) {
    install(SSE)
    followRedirects = false
}
val connection = McpServerConnection(
    server = McpServer(id = "docs", displayName = "Documentation"),
    transportFactory = streamableHttpMcpTransportFactory(
        client = http,
        endpoint = "https://mcp.example.com/mcp",
        headersProvider = McpRequestHeadersProvider {
            mapOf("Authorization" to "Bearer ${loadToken()}")
        },
    ),
    policyProvider = McpToolPolicyProvider { tool ->
        McpToolPolicy(
            enabled = isEnabled(tool.server.id, tool.remoteName),
            requiresPermission = "external-tools",
            requiresApproval = true,
            timeoutMs = 60_000,
            maxCallsPerTurn = 4,
            maxCallsPerRun = 12,
        )
    },
    options = McpConnectionOptions(
        initializeTimeoutMs = 30_000,
        listToolsTimeoutMs = 30_000,
        maxTools = 256,
        maxToolResultChars = 1_048_576,
    ),
)

connection.connect()
```

Credential values are resolved once for each new connection. Do not put them in `McpServer`,
Tool descriptions, Agent requests, sessions, checkpoints, logs, or diagnostics. Remote endpoints
must use HTTPS. Plain HTTP is accepted only for `localhost`, `127.0.0.1`, and `::1`; URL userinfo
credentials and headers owned by the transport are rejected. Put secrets in the credential header
provider, not in endpoint query parameters.

The adapter accepts static bearer/custom headers as a transport primitive. It does not implement
the MCP HTTP OAuth 2.1 discovery and authorization flow. Applications connecting to an
OAuth-protected server must perform that flow in a host authorization component and provide a
current access token, including refresh/reconnect behavior. A host can alternatively install its
own Ktor authentication plugin on the supplied client. Disable redirects or independently validate
every redirect target so credentials cannot cross origins.

## JVM stdio

```kotlin
val connection = McpServerConnection(
    server = McpServer("filesystem", "Filesystem"),
    transportFactory = jvmStdioMcpTransportFactory(
        JvmMcpStdioProcess(
            command = approvedAbsoluteExecutable,
            arguments = listOf("-y", "@modelcontextprotocol/server-filesystem", approvedRoot),
            workingDirectory = approvedWorkingDirectory,
            environment = mapOf(
                "LOG_LEVEL" to "warn",
                "PATH" to approvedExecutableSearchPath,
            ),
        ),
    ),
)
```

A local server runs with the user's account permissions. Before invoking the factory, present the
exact executable, arguments, working directory, and environment variable names and obtain explicit
approval. Keep secret environment values in a platform credential store and inject them only when
creating the process. The child receives only the explicitly supplied environment map and does not
inherit the host process environment; use an absolute executable path or explicitly provide any
required `PATH`. The SDK does not download, approve, sandbox, or update executables.

## Runtime composition

`McpServerConnection` is itself a dynamic `ToolRegistry`. Multiple connections can be aggregated:

```kotlin
val mcpRegistry = McpToolRegistry { activeConnections }
val registry = CompositeToolRegistry {
    listOf(localToolRegistry, mcpRegistry)
}

val runner = DefaultAgentRunner(
    providerRegistry = providers,
    toolRegistry = registry,
    persistence = persistence,
    credentialProvider = credentials,
)
```

Advertise `registry.definitions()` in the `AgentRequest`. Runtime then resolves and executes the
same registry after the Provider emits a Tool call. Names use the form `mcp__server__tool` when
portable and a deterministic hash suffix when sanitization or truncation is required. They remain
within the common 64-character Provider limit.

Policy is evaluated whenever definitions are advertised or an executor is resolved, so a host can
disable a Tool without reconnecting. MCP annotations such as read-only, destructive, idempotent, or
open-world are untrusted hints and never bypass permission or approval gates. Tool names,
descriptions, schemas, server instructions, arguments, results, links, and embedded resources are
also untrusted remote input; connect only trusted servers and keep application authorization
independent from server-authored text.

`McpConnectionOptions` bounds initialization and Tool-list time, pagination, Tool count, individual
and aggregate Tool definitions, server instructions, and Tool results. A
`notifications/tools/list_changed` event immediately removes the old advertised contracts while a
conflated refresh runs; a failed refresh leaves the registry empty and reports a sanitized failure.
An executor captured before a contract refresh cannot execute after the descriptor or host policy
has changed. These checks are in addition to Runtime timeout, result-size, permission, approval, and
call-budget enforcement.

`connect`, `refreshTools`, and Tool-call transport/protocol failures expose only
`McpOperationException` with stable operation and failure categories. The original exception is not
retained as a cause because SDK/server messages may contain response bodies, endpoints, or
authentication material. Coroutine cancellation still propagates unchanged.

The owner must call `disconnect` or `close` on each connection and close its HTTP client.
Connection ownership remains with the host; `McpToolRegistry` only aggregates them.

## Result mapping

`structuredContent`, when present, becomes the primary Magrathea Tool result. Otherwise the
canonical MCP content array is wrapped as JSON. Human-readable text is derived only from real MCP
content blocks. Images, audio, embedded resources, links, errors, and MCP metadata remain
identifiable in the result metadata. All presented content comes from the server response.

## Verification

Run deterministic adapter contracts:

```bash
./gradlew :magrathea-mcp:jvmTest
```

Run the pinned official client conformance scenarios:

```bash
make verify-mcp-conformance
```

The conformance command starts the official loopback server and exercises real Streamable HTTP for
initialization and Tool calls. The repository also keeps a small executable client under
`tooling/mcp-conformance-client`; it is tooling, not a published SDK module.

The JVM suite separately launches a dependency-free child MCP process and verifies initialization,
Tool discovery, and Tool invocation over the actual stdio streams, in addition to checking that the
child environment contains only explicitly configured entries.

Use the official MCP Inspector for manual diagnosis of a particular third-party server. Inspector
output is useful interoperability evidence but does not replace deterministic contracts,
conformance tests, security review, or an application-level smoke test against the exact server and
authorization configuration being shipped.

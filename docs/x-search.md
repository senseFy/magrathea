# X Search Contract

## Scope

Magrathea exposes X Search as a Provider-neutral, client-executed function Tool. The main agent
model may be Gemini, OpenAI, Anthropic, or another adapter that supports ordinary function calling.
The Runtime executes a host-supplied `XSearchBackend`; the backend owns the xAI credential and the
model used to perform the actual hosted search.

This keeps the primary agent loop independent from xAI:

```text
main model -> function call: x_search -> Runtime -> XSearchBackend
           <- bounded evidence + citations <- xAI Responses hosted x_search
main model -> grounded final answer
```

`magrathea-provider-openai` also implements the lower-level xAI Responses dialect through
`OpenAiProviderProfile.xAi()`. An `OpenAiXSearchToolConfig` is serialized as a server-side
`{"type":"x_search"}` Tool.
Returned `x_search_call` items are represented as Provider-owned activity rather than client
`ToolCallPart` values.

## Runtime execution model

1. The host constructs `XSearchTool` with an `XSearchPolicy` and `XSearchBackend`.
2. The Tool executor is registered with Runtime and its definition is advertised to the selected
   main model.
3. The main model decides whether to call `x_search`, supplying a query and optional date/handle
   filters.
4. Runtime applies normal Tool authorization, finalization, per-turn budgets, cancellation, and
   timeout behavior.
5. `XSearchTool` validates that model-supplied filters only narrow host policy, then invokes the
   backend.
6. The backend returns grounded text and citations. The Tool bounds and normalizes them as
   untrusted external evidence before the normal agent loop continues.

Omitting the Tool from the request disables X Search. The main model and the backend search model
are independent selections.

## Policy

`XSearchPolicy` contains only non-secret host policy:

| Setting | Default | Contract |
|---|---:|---|
| `maxSearchCallsPerRun` | `1` | `1..20`; total outer `x_search` calls allowed for one user-request Agent run |
| `maxQueryChars` | `512` | `1..2048`; invalid or control-bearing queries fail before backend execution |
| `maxHandlesPerRequest` | `20` | Maximum allowed or excluded handles in one request |
| `maxCitationsInContext` | `12` | `1..100`; bounds evidence returned to the main model |
| `allowedHandles` / `excludedHandles` | empty | Mutually exclusive host policy; a model may narrow but cannot widen it |
| `enableImageUnderstanding` | `false` | Backend request flag for images encountered in X posts |
| `enableVideoUnderstanding` | `false` | Backend request flag for videos encountered in X posts |
| `timeoutMs` | `45000` | `100..120000`; complete backend execution timeout |

Model arguments support `query`, inclusive ISO `from_date` / `to_date`, and mutually exclusive
`allowed_handles` / `excluded_handles`. Handles are normalized names without an `@` prefix.

The backend-specific hosted-agent turn limit is deliberately separate. For an xAI Responses
backend, configure `OpenAiTransportConfig.maxToolTurns`; it serializes as `max_turns` and bounds
assistant/server-side Tool turns inside that one nested Provider request.

The run budget spans all main-model turns for one user request. Once exhausted, Runtime stops
advertising the Tool on later turns, including after injected follow-up or steering messages and
checkpoint resume. The same numeric ceiling is also applied per model response as defense in depth
against an oversized parallel Tool-call batch; it does not reset or increase the run budget.
`RuntimeConfig.maxTurns` independently bounds the number of main-model/tool cycles in one Agent
run.

`XSearchTool` defaults to `ToolRecoveryPolicy.REPLAY_SAFE`. A host whose backend must not repeat an
invocation with an unknown outcome, including a backend with non-repeatable billing semantics,
sets `recoveryPolicy = ToolRecoveryPolicy.FAIL_CLOSED` when constructing the Tool. Recovery policy
describes backend execution semantics and therefore remains separate from `XSearchPolicy`.

## OpenAI Responses hosted wire

`OpenAiTransportConfig.hostedTools` accepts `OpenAiXSearchToolConfig` only for an xAI Provider
profile using `OpenAiWireProtocol.RESPONSES`. The request builder emits:

```json
{
  "tools": [
    {
      "type": "x_search",
      "allowed_x_handles": ["kotlin"],
      "from_date": "2026-07-01",
      "enable_image_understanding": true
    }
  ],
  "max_turns": 3
}
```

The xAI Responses profile decodes `x_search_call` output in synchronous and streaming responses as
Provider-owned activity. Top-level response citations and URL annotations use the canonical
Provider metadata key `citations`. Hosted calls use `id` or `call_id`; optional status values follow
their lifecycle position.

## Security and failure behavior

- xAI credentials belong to the backend and never enter `XSearchPolicy`, Tool definitions,
  sessions, checkpoints, Tool results, or diagnostics.
- Search queries, grounded text, and citations are conversation data and follow the host's normal
  retention policy.
- X posts and linked content are explicitly marked as untrusted external evidence. They are never
  instructions for Runtime or the main model.
- Invalid queries, invalid dates, conflicting filters, and attempts to widen host policy fail
  before backend execution.
- Authentication, rate-limit, network, timeout, output-limit, unsupported-policy,
  malformed-response, and unavailable failures use stable content-free codes.
- Cancellation propagates unchanged.
- A browser application must invoke an application-owned backend or Gateway; it must not embed an
  xAI credential.

## Composition

```kotlin
val xSearch = XSearchTool(
    backend = applicationXSearchBackend,
    policy = XSearchPolicy(
        maxSearchCallsPerRun = 1,
        maxCitationsInContext = 12,
        allowedHandles = listOf("kotlin"),
    ),
)

val runner = DefaultAgentRunner(
    providerRegistry = mainModelProviders,
    toolRegistry = InMemoryToolRegistry(listOf(xSearch)),
    persistence = persistence,
    credentialProvider = mainModelCredentials,
)
```

The backend may call xAI directly through `magrathea-provider-openai`, or call an
application-controlled service that implements the same search boundary. Backend lifecycle and
credential storage remain host-owned.

## Integration boundary

X Search remains an optional cross-model Tool whose backend, credentials, account, billing policy,
and settings UI are host-owned. Provider-native hosted `x_search_call` stays in its Provider
protocol layer. Canonical progress and results reflect only backend-reported data, while X-specific
handle and media controls remain in `XSearchPolicy`.

# Providers

A Magrathea Provider adapter translates one wire protocol into canonical Runtime events:

- `ProviderAdapter.key` is the Runtime routing key and credential namespace;
- `ProviderAdapter.optionsFamily` selects the typed options schema;
- `ProviderAdapter.invocationResumeMode` selects restart or durable-stream reattachment;
- a Provider profile owns protocol defaults, endpoints, and documented dialect behavior.

## Reference adapters

| Module/profile | Provider key | Default wire contract | Default endpoint | Authentication |
|---|---|---|---|---|
| `magrathea-provider-gemini` | `gemini` | Gemini Interactions v1 | `https://generativelanguage.googleapis.com/v1/interactions` | `x-goog-api-key` |
| `OpenAiProviderProfile.openAi()` | `openai` | OpenAI Responses | `https://api.openai.com/v1/responses` | Bearer |
| `OpenAiProviderProfile.openRouter()` | `openrouter` | OpenAI Chat Completions | `https://openrouter.ai/api/v1/chat/completions` | Bearer |
| `OpenAiProviderProfile.xAi()` | `xai` | OpenAI Responses | `https://api.x.ai/v1/responses` | Bearer |
| `magrathea-provider-anthropic` | `anthropic` | Anthropic Messages | `https://api.anthropic.com/v1/messages` | `x-api-key` |

The capability and verification details for each contract are in the
[Provider Capability Matrix](provider-capability-matrix.md).

## Session identity and credentials

The session stores a Provider/model selection and a non-secret credential reference:

```kotlin
val configuration = ChatbotSessionConfiguration(
    model = ModelDescriptor(
        provider = "openai",
        model = "your-model",
        supportsStreaming = true,
    ),
    credentialRef = CredentialRef(
        provider = "openai",
        profile = "default",
    ),
)
```

The host resolves that reference immediately before a Provider call:

```kotlin
val credentials = CredentialProvider { ref ->
    require(ref.provider == "openai")
    ProviderCredential(loadApiKey(ref.profile))
}
```

Distinct credential profiles can represent multiple accounts for one Provider.

## OpenAI-family profiles

Register each service under its real identity:

```kotlin
val providers = InMemoryProviderRegistry(
    listOf(
        OpenAiProviderAdapter(OpenAiProviderProfile.openAi()),
        OpenAiProviderAdapter(OpenAiProviderProfile.openRouter()),
        OpenAiProviderAdapter(OpenAiProviderProfile.xAi()),
    ),
)
```

An OpenRouter session consequently uses `openrouter` in both its model and credential reference:

```kotlin
ChatbotSessionConfiguration(
    model = ModelDescriptor("openrouter", "openai/gpt-4o-mini"),
    credentialRef = CredentialRef("openrouter"),
)
```

OpenAI defaults to Responses, OpenRouter to Chat Completions, and xAI to Responses. Set
`protocol` when using another wire contract supported by the selected service and model:

```kotlin
OpenAiTransportConfig(
    protocol = OpenAiWireProtocol.RESPONSES,
    authentication = OpenAiAuthentication.BEARER,
).toProviderOptions()
```

A custom service that implements an OpenAI wire protocol receives its own Provider identity:

```kotlin
val profile = OpenAiProviderProfile.compatible(
    providerId = "acme-ai",
    defaultProtocol = OpenAiWireProtocol.CHAT_COMPLETIONS,
    chatCompletionsEndpoint = "https://api.acme.example/v1/chat/completions",
)
val provider = OpenAiProviderAdapter(profile)
```

The host may instead bind the full endpoint and non-authentication headers to the credential
profile. This keeps a persisted session resumable across process restarts:

```kotlin
val credentials = CredentialProvider { ref ->
    require(ref == CredentialRef(provider = "acme-ai", profile = "default"))
    ProviderCredential(
        value = loadCompatibleApiKey(),
        endpoint = "https://api.acme.example/v1/chat/completions",
        headers = mapOf("X-Application" to "my-app"),
    )
}
```

The profile supplies the default protocol. The `COMPATIBLE` dialect uses the shared codec without
Provider-specific normalization.

OpenAI-family profiles support `BEARER` and `API_KEY`. Anthropic Messages supports `X_API_KEY` and
`BEARER`:

```kotlin
AnthropicTransportConfig(
    authentication = AnthropicAuthentication.BEARER,
).toProviderOptions()
```

The endpoint is the full request URL. Direct remote endpoints must use HTTPS; plain HTTP is
accepted only for exact loopback hosts used by local development and controlled tests. URL
userinfo and fragments are rejected.

`ProviderConfig.endpoint` and `ProviderConfig.headers` are per-run overrides. Use the credential
profile for durable endpoint configuration.

## Custom adapters

Implement `ProviderAdapter` for a service with a distinct wire protocol. A custom adapter may reuse
`HttpTransport`, canonical `ProviderEvent` values, typed failures, and the Runtime without changing
the Agent loop.

An adapter must:

- declare a unique `key`;
- encode requests and decode streaming and non-streaming responses strictly;
- emit one valid canonical lifecycle;
- declare only attachment MIME types its encoder can represent;
- map errors to stable Provider failure types without leaking credentials;
- close any transport it owns.

Adapters default to a new physical Provider attempt when Runtime resumes an interrupted model
response. Use `ProviderInvocationResumeMode.REATTACH` only when the remote service supports
idempotent creation and durable stream replay under the same invocation identity.

Register it through a `ProviderRegistry` like a reference adapter. Runtime routes every adapter
uniformly through the registry.

## Invocation identity and cancellation

Runtime assigns each physical Provider attempt a `ProviderInvocation` and an explicit
`ProviderInvocationIntent`:

- `CREATE` permits the adapter to start work, or idempotently resolve work already created under
  that identity;
- `REATTACH` permits only resolving and replaying that existing invocation. It must never create
  replacement work when the identity is unknown, expired, or invalidated.

An adapter that cannot reattach must report `ProviderInvocationResumeMode.NEW_ATTEMPT`. A durable
adapter may report `REATTACH`; Runtime then keeps the same identity across recoverable
interruptions and sends `REATTACH` on the next collection. If the remote invocation can no longer
be resolved, the adapter throws `ProviderInvocationInvalidatedException`. Runtime clears that
recovery anchor and starts a fresh `CREATE` only when the failure is retryable and retry policy
allows it. An unknown identity fails closed.

Cancellation intent is available through `providerCancellationIntent()` while a Provider flow is
being collected:

- Runtime-owned collection always uses `INTERRUPT`: it stops local collection but leaves durable
  work available until the Runtime commits its next authoritative state;
- direct adapter collection has no Runtime signal and defaults to `CANCEL`, which permits
  best-effort cleanup by that direct consumer.

When cancellation or failure terminally discards a pending invocation, Runtime removes its
checkpoint before calling `ProviderAdapter.abandon(invocation)`. Direct Providers normally keep the
default no-op; durable adapters use it to release retained remote work. A failed terminal commit is
not followed by abandonment, and cleanup failure does not make a committed terminal run resumable.

## Model and attachment capabilities

`ProviderAdapter.inputCapabilities(config)` is the effective protocol encoder's upper bound. For
example, one OpenAI-family profile can report different attachment support for Responses and Chat
Completions. Per-model support comes from trusted model metadata and product policy, intersected
with adapter capabilities; missing model metadata means unknown rather than text-only.

The same rule applies to streaming, Tool use, and reasoning: configure `ModelDescriptor` from
trusted model metadata instead of inferring capability from a Provider name.

## Reasoning preferences

`ReasoningPreference` is a Provider-neutral request intent:

- `Auto` omits the neutral control and preserves the Provider or model default;
- `Disabled` requires explicit model support and a real Provider off value;
- `Effort` carries one of `MINIMAL`, `LOW`, `MEDIUM`, `HIGH`, `XHIGH`, or `MAX`.

`ModelDescriptor.reasoningCapabilities` declares canonical supported efforts and whether explicit
disable is available. It contains no Provider wire values. A missing capability means that only
`Auto` is valid.

Reference adapters resolve the neutral intent at their wire boundary. OpenAI and xAI use
`reasoning.effort` for Responses and `reasoning_effort` for Chat Completions. OpenRouter Chat
Completions uses `reasoning: { effort }`. Gemini Interactions uses `thinking_level`. Anthropic uses
adaptive thinking with `output_config.effort`; explicit disable uses disabled thinking only on
models that declare it.

Provider capability metadata remains model-specific. Anthropic
[`xhigh`](https://platform.claude.com/docs/en/build-with-claude/effort) is available only on the
models that declare it, while Anthropic does not expose a portable `minimal` effort. Current
[xAI reasoning models](https://docs.x.ai/developers/model-capabilities/text/reasoning) do not
support explicit disable, so the built-in xAI profile rejects `Disabled` before transport.

Explicit unsupported choices fail before transport. A neutral choice also fails when combined
with a Provider-native option for the same control dimension. `Auto` leaves typed Provider options
untouched, so Provider-specific budgets, summaries, and other native controls remain available.
For a compatible Chat Completions endpoint, set
`OpenAiProviderProfile.chatCompletionsReasoningFormat` before using neutral reasoning controls. If
the endpoint supports explicit effort, declare every supported semantic level's exact wire value
with `OpenAiProviderProfile.reasoningEffortMapping`. Compatible mappings live at the Provider
boundary; Runtime never guesses from a model id and never silently falls back. If the endpoint
supports disabling reasoning, also declare its exact
`OpenAiProviderProfile.disabledReasoningValue`; the adapter does not guess a compatible dialect's
off value.

## Verification

Deterministic protocol tests are part of the normal SDK gates. Controlled remote checks live in the
[Provider live harness](../tooling/provider-live-harness) and run only when explicitly invoked.
See [ADR-007](adr/ADR-007-reference-provider-contracts.md) for the Provider contract.

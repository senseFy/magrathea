# Providers

A Magrathea Provider adapter implements one exact wire protocol and emits canonical Runtime events.
Provider keys identify protocol families and can target canonical or compatible service endpoints.

## Reference adapters

| Module | Provider key | Wire contract | Default endpoint | Default authentication |
|---|---|---|---|---|
| `magrathea-provider-gemini` | `gemini` | Gemini Interactions v1 | `https://generativelanguage.googleapis.com/v1/interactions` | `x-goog-api-key` |
| `magrathea-provider-openai` | `openai` | OpenAI Responses | `https://api.openai.com/v1/responses` | Bearer |
| `magrathea-provider-openai` | `openai` | OpenAI Chat Completions | `https://api.openai.com/v1/chat/completions` | Bearer |
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

Secrets never belong in a session, checkpoint, Provider options, diagnostic, or model descriptor.
Use distinct profiles when an application has multiple accounts or endpoints for the same
protocol.

## Compatible services

A compatible service must implement the selected wire contract exactly. The host selects the full
endpoint and protocol variant; the adapter uses that contract without probing or fallback between
Responses, Chat Completions, and Messages.

Bind a custom endpoint and any required non-authentication headers to the credential profile so a
persisted session can resume after a process restart:

```kotlin
val credentials = CredentialProvider { ref ->
    require(ref == CredentialRef(provider = "openai", profile = "compatible"))
    ProviderCredential(
        value = loadCompatibleApiKey(),
        endpoint = "https://compatible.example/v1/chat/completions",
        headers = mapOf("X-Application" to "my-app"),
    )
}
```

Select the matching OpenAI contract and authentication mode explicitly:

```kotlin
val requestFactory = DefaultChatbotRequestFactory(
    configure = {
        copy(
            engine = engine.copy(
                provider = engine.provider.copy(
                    options = OpenAiTransportConfig(
                        api = OpenAiApi.CHAT_COMPLETIONS,
                        authentication = OpenAiAuthentication.BEARER,
                    ).toProviderOptions(),
                ),
            ),
        )
    },
)
```

OpenAI-family adapters support `BEARER` and `API_KEY`. Anthropic Messages supports `X_API_KEY` and
`BEARER`:

```kotlin
AnthropicTransportConfig(
    authentication = AnthropicAuthentication.BEARER,
).toProviderOptions()
```

The endpoint is the full request URL. Direct remote endpoints must use HTTPS; plain HTTP is
accepted only for exact loopback hosts used by local development and controlled tests. URL
userinfo and fragments are rejected.

`ProviderConfig.endpoint` and `ProviderConfig.headers` are transient per-run overrides. They are
deliberately excluded from serialized sessions and checkpoints; use the credential profile for
durable endpoint configuration.

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

Register it through a `ProviderRegistry` like a reference adapter. Runtime routes every adapter
uniformly through the registry.

## Model and attachment capabilities

`ProviderAdapter.inputCapabilities` is the protocol encoder's upper bound. Per-model support comes
from trusted model metadata and product policy, intersected with adapter capabilities; missing
model metadata means unknown rather than text-only.

The same rule applies to streaming, Tool use, and reasoning: configure `ModelDescriptor` from
trusted model metadata instead of inferring capability from a Provider name.

## Verification

Deterministic protocol tests are part of the normal SDK gates. Controlled remote checks live in the
[Provider live harness](../tooling/provider-live-harness) and run only when explicitly invoked.

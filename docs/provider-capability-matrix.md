# Provider Capability Matrix

> Scope: Android/JVM/iOS reference Providers and the Gateway-backed browser path

## Reference Providers

| Provider profile / protocol | Wire contract | Streaming | Tools | Reasoning | Replay | Attachments | Authentication and endpoint |
|---|---|---|---|---|---|---|---|
| `gemini` | Interactions API stable v1, `store=false` | SSE; semantic terminal event required | Streaming function call/result | Model-declared neutral effort maps to `thinking_level`; thought summaries are visible and signatures are opaque | Authoritative interaction steps for the same model | Image, audio, video, PDF, CSV; typed data URL or URI | `x-goog-api-key`; default `/v1/interactions`, explicit endpoint override |
| OpenAI-family / Responses | Responses API, `store=false` | SSE; terminal response required; optional nested boundaries and extensions | Function calls use `call_id` and terminal arguments; xAI hosted X Search remains Provider-owned | Model-declared neutral effort maps to `reasoning.effort`; distinct summary and exposed-text blocks finalized from output items; opaque continuation retained | Authoritative top-level output items for the same model; canonical text/tool reconstruction across models | Images use `input_image`; supported documents use `input_file` over HTTPS or a valid base64 data URL, with optional filename | `OpenAiWireProtocol.RESPONSES`; profile endpoint; Bearer default or explicit `api-key` |
| OpenAI-family / Chat Completions | Portable Chat Completions subset | Data-only SSE chunks terminated by `[DONE]`; non-streaming choices normalize through the same lifecycle | Streaming `tool_calls` are assembled by choice/tool index and finalized before execution | OpenAI/xAI use `reasoning_effort`; OpenRouter uses `reasoning.effort`; normalized reasoning blocks retain portable visible output | Exact reasoning-detail sequence for the same model; portable text/tool reconstruction across models | HTTPS or valid base64 `image_url` for GIF, JPEG, PNG, and WebP; no portable document block | `OpenAiWireProtocol.CHAT_COMPLETIONS`; profile endpoint; Bearer default or explicit `api-key` |
| `anthropic` | Messages API | Named SSE with block-index lifecycle; one post-`message_stop` `[DONE]` is accepted | `content_block_stop` finalizes a call | Neutral effort uses adaptive thinking plus `output_config.effort`; native budget/display and signature/redacted-block retention remain typed | Authoritative content blocks for the same model; canonical text/tool reconstruction across models | Image and PDF via HTTPS or valid base64 data URL | `x-api-key` default or explicit Bearer, plus `anthropic-version: 2023-06-01`; default `https://api.anthropic.com/v1/messages`, credential-bound endpoint with transient request override |

The built-in OpenAI-family profiles are `openai` (Responses), `openrouter` (Chat Completions), and
`xai` (Responses). Each keeps its Provider identity while sharing the `openai` options family.

All three adapter modules and all four selected wire contracts support streaming and non-streaming responses and emit only canonical
`ProviderEvent` values. Shared transport maps authentication, client, rate-limit/`Retry-After`,
server, network, and protocol failures to stable types. Credentials are excluded from errors,
diagnostics, request `toString()`, and metadata.

All four contracts can advertise portable Web Search and Image Search function Tools from
`magrathea-runtime`. They execute through injected host backends rather than Provider-hosted search
features. Image Search projects normalized URL metadata and stable media references to the model,
while its `USER`-audience image content remains product-only. A Tool may deliberately return a
`MODEL`-audience image; adapters encode it as native multimodal input only when the selected model
and wire contract accept that image type. The xAI profile additionally supports its hosted X
Search wire lifecycle.

`ProviderAdapter.inputCapabilities(config)` publishes the attachment MIME types understood by the
effective protocol encoder. Hosts combine these values with product policy and model metadata.

`OpenAiProviderProfile` binds Provider identity, default protocol, endpoints, and dialect. A custom
wire protocol implements `ProviderAdapter` and can reuse the shared transport, canonical events,
typed failures, and Runtime contracts.

## Gateway-backed browsers

Browser clients send validated model references, messages, tools, generation options, and
pre-uploaded attachment references through `magrathea-provider-gateway`. Server-side resolvers own
the actual Provider, model, endpoint, and credential. Gateway `exact-v3` covers identity,
idempotency, SSE sequence/replay, cancellation, typed Tool attachment references, and stable failure
mapping.

## Verification status

| Evidence | Gemini | OpenAI-family | Anthropic |
|---|---:|---:|---:|
| Android/JVM/iOS KMP publication | Yes | Yes | Yes |
| JVM, Android host, and iOS Simulator contracts | Yes | Yes | Yes |
| Adapter → Runtime → Tool → follow-up request exactly-once contract | Yes | Yes | Yes |
| JVM loopback server with default Ktor engine/SSE framing | Transport contract | Yes | Yes |
| Android physical-device default transport fixture | SM-S9180/API 36 request/codec/SSE/cancel | Not run | Not run |
| Controlled remote API evidence | Stable-v1 JVM streaming | OpenRouter Responses: non-streaming, SSE, tool loops including compacted reasoning, and PDF input; Chat Completions deterministic-only | OpenRouter Messages: non-streaming, SSE, tool loops, and PDF input |
| Android/iOS physical-device remote API | Not run | Not run | Not run |

Android host and iOS Simulator suites exercise shared codecs, request mapping, and transport
boundaries. Browser production bundles run through Playwright Chromium, Firefox, and WebKit-engine
automation. Broader validation gaps are tracked in [Known Issues](known-issues.md).

See [ADR-007](adr/ADR-007-reference-provider-contracts.md) for the Provider contract.

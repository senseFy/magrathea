# Provider Capability Matrix

> Version: `0.1.0-alpha.1`
> Scope: Android/JVM/iOS reference Providers and the Gateway-backed browser path

## Reference Providers

| Provider key | Wire contract | Streaming | Tools | Reasoning | Replay | Attachments | Authentication and endpoint |
|---|---|---|---|---|---|---|---|
| `gemini` | Interactions API stable v1, `store=false` | SSE; protocol terminal and transport completion are both required | Streaming function call/result | Thought summaries are visible; thought signatures are opaque | Authoritative interaction steps for the same model | Image, audio, video, PDF, CSV; typed data URL or URI | `x-goog-api-key`; default `/v1/interactions`, explicit endpoint override |
| `openai` / Responses | Responses API, `store=false` | SSE payload type is authoritative; an explicit event name must match; one post-terminal `[DONE]` is accepted; compatible reasoning may reconcile missing nested done events against an exact authoritative `output_item.done` | Core call identity uses `call_id`; output item `id` remains separate | Summary and exposed-text blocks remain distinct; encrypted content is opaque | Authoritative top-level output items for the same model; canonical text/tool reconstruction across models | Images use `input_image`; supported documents use `input_file` over HTTPS or a valid base64 data URL, with optional filename | `OpenAiApi.RESPONSES`; Bearer default or explicit `api-key`; default `https://api.openai.com/v1/responses`, credential-bound endpoint with transient request override |
| `openai` / Chat Completions | Portable Chat Completions subset | Data-only SSE chunks terminated by `[DONE]`; non-streaming choices normalize through the same lifecycle | Streaming `tool_calls` are assembled by choice/tool index and finalized before execution | Normalized `reasoning_details` summary/text/encrypted blocks plus legacy `reasoning` aliases | Exact reasoning-detail sequence for the same model; portable text/tool reconstruction across models | HTTPS or valid base64 `image_url` for GIF, JPEG, PNG, and WebP; no portable document block | `OpenAiApi.CHAT_COMPLETIONS`; Bearer default or explicit `api-key`; default `https://api.openai.com/v1/chat/completions`, credential-bound endpoint with transient request override |
| `anthropic` | Messages API | Named SSE with block-index lifecycle; one post-`message_stop` `[DONE]` is accepted | `content_block_stop` finalizes a call | Adaptive/enabled/disabled thinking, budget, display, effort, signature and redacted-block retention | Authoritative content blocks for the same model; canonical text/tool reconstruction across models | Image and PDF via HTTPS or valid base64 data URL | `x-api-key` default or explicit Bearer, plus `anthropic-version: 2023-06-01`; default `https://api.anthropic.com/v1/messages`, credential-bound endpoint with transient request override |

All three adapter modules and all four selected wire contracts support streaming and non-streaming responses and emit only canonical
`ProviderEvent` values. Shared transport maps authentication, client, rate-limit/`Retry-After`,
server, network, and protocol failures to stable types. Credentials are excluded from errors,
diagnostics, request `toString()`, and metadata.

All four contracts can advertise the portable `web_search` function Tool from `magrathea-runtime`.
The Tool is executed by the client Runtime through an injected `WebSearchBackend`; it is not the
Provider's hosted web-search/grounding feature. Hosted search remains outside this matrix until its
distinct calls, results, citations, continuation state, and limits have a canonical contract.

`ProviderAdapter.inputCapabilities` publishes the attachment MIME types understood by that
adapter's encoder. These values define protocol/wire capability only. Hosts remain responsible for
intersecting them with product policy and model-specific metadata; absent model metadata is
unknown, not evidence that a model is text-only. The reference request-contract suites verify that
every declared exact MIME type reaches a valid wire block and that aliases are normalized without
mislabeling payloads.

The `openai` and `anthropic` keys identify strict protocol families. OpenAI services additionally
select `OpenAiApi.RESPONSES` or `OpenAiApi.CHAT_COMPLETIONS`; compatible services use the matching
mode with an explicit endpoint and authentication setting. Selecting one does not imply
compatibility with the other or with another similarly named schema. A custom protocol implements
`ProviderAdapter` and may reuse `HttpTransport`, canonical events, typed failures, and Runtime
contracts.

## Gateway-backed browsers

Browser clients send validated model references, messages, tools, generation options, and
pre-uploaded attachment references through `magrathea-provider-gateway`. Server-side resolvers own
the actual Provider, model, endpoint, and credential. Gateway `exact-v1` covers identity,
idempotency, SSE sequence/replay, cancellation, and stable failure mapping.

## Verification status

| Evidence | Gemini | OpenAI | Anthropic |
|---|---:|---:|---:|
| Android/JVM/iOS KMP publication | Yes | Yes | Yes |
| JVM, Android host, and iOS Simulator contracts | Yes | Yes | Yes |
| Adapter → Runtime → Tool → follow-up request exactly-once contract | Yes | Yes | Yes |
| JVM loopback server with default Ktor engine/SSE framing | Transport contract | Yes | Yes |
| Android physical-device default transport fixture | SM-S9180/API 36 request/codec/SSE/cancel | Not run | Not run |
| Controlled remote API evidence | Stable-v1 JVM streaming | OpenRouter Responses-compatible JVM non-streaming, SSE, three-tool loop (2026-07-13), PDF `input_file` (2026-07-14; 84 input/10 output tokens), and Grok 4.5 compacted-reasoning two-turn three-tool loop (2026-07-17; 1,415 input/218 output/85 reasoning tokens); Chat Completions remains deterministic-only | OpenRouter Messages-compatible JVM non-streaming, SSE, three-tool loop (2026-07-13), and PDF document input (2026-07-14; 1,650 input/10 output tokens) |
| Android/iOS physical-device remote API | Not run | Not run | Not run |

Android host and iOS Simulator suites exercise shared codecs, request mapping, and transport
boundaries. Browser production bundles run through Playwright Chromium, Firefox, and WebKit-engine
automation. Evidence limitations are tracked in [Verification Status](verification-status.md).

Provider protocol rules are recorded in
[ADR-007](adr/ADR-007-reference-provider-contracts.md).

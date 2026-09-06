# ADR-007: Reference Provider Contracts

- Status: Accepted
- Date: 2026-07-11

## Context

A Provider-neutral runtime needs multiple distinct wire protocols to constrain its abstraction.
Supporting every vendor variant would instead turn the repository into a catalog of aliases and
increase the long-term protocol matrix.

## Decision

Magrathea maintains three reference adapter modules covering four explicit wire contracts:

- Gemini Interactions API stable v1 with `store=false`;
- OpenAI Responses API with `store=false`;
- the portable subset of OpenAI Chat Completions;
- Anthropic Messages API.

Provider adapters expose four related values:

- `ProviderAdapter.key` identifies the Provider for Runtime routing, models, and credentials;
- `ProviderAdapter.optionsFamily` selects its typed configuration schema;
- the wire protocol selects the request builder and codec;
- a dialect describes Provider-specific behavior within that protocol.

For OpenAI-family services, `OpenAiProviderProfile` binds Provider identity, default
`OpenAiWireProtocol`, endpoints, and dialect. The built-in profiles are OpenAI with Responses,
OpenRouter with Chat Completions, and xAI with Responses. All use the `openai` options family.
`OpenAiTransportConfig.protocol` can override a profile default for a request.

Each selected contract owns its request model, codec, stream state machine, failure mapping, and
replay metadata. Adapters emit canonical `ProviderEvent` values consumed by Runtime and Chatbot.

Each adapter also exposes `inputCapabilities(config)` as an upper bound for attachment MIME
envelopes the effective protocol can encode. This is protocol metadata, not model discovery:
products intersect it with their own policy and model-specific metadata when available. Custom
adapters default to no attachment support and opt in explicitly. Request codecs validate the
attachment input they encode.

Reference codecs translate the content they consume into canonical events, validating the fields
needed for that translation. Optional wire details do not impose additional Runtime invariants.
Responses and Chat Completions share a module but use separate codecs; distinct protocols integrate
through `ProviderAdapter`. xAI hosted X Search remains Provider-owned.

Provider-authoritative metadata is retained for replay with the same Provider and model. A
cross-Provider or cross-model replay rebuilds only portable text and tool semantics. Tool calls
become executable only after the response completes, their arguments are finalized JSON objects,
and Runtime checks the advertised tool and execution permissions. Incomplete, failed, and cancelled
responses do not trigger tool execution.

Reasoning separates usage, request intent, visible content, and continuation state:

- token usage reports how much internal reasoning a model consumed;
- request intent selects `Auto`, explicit disable, or a semantic effort supported by trusted model
  metadata;
- visible reasoning is only the representation a Provider deliberately returned, classified as a
  Provider-defined view, a summary, or explicitly exposed reasoning text;
- signatures, encrypted content, redacted blocks, and Provider-specific reasoning details are
  opaque continuation state.

Every visible reasoning block has an independent canonical start/delta/end lifecycle and reaches
`FINAL` only after its wire boundary completes. A response may contain both summaries and exposed
reasoning text; adapters preserve them as distinct blocks rather than guessing that one excludes
the other. Empty or omitted visible reasoning is valid and never replaced with fabricated progress.
Chatbot projections expose visible text, classification, redaction state, and lifecycle only. They
never expose opaque continuation values. Traces, errors, and diagnostics accept neither form.

Opaque state is retained only inside authoritative Provider history and is replayed unmodified only
to the same Provider and model. The sequence is never reordered, flattened into text, parsed, or
transferred across models. This follows the continuation requirements documented for
[OpenAI Responses](https://platform.openai.com/docs/api-reference/responses-streaming),
[Anthropic extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking),
[Gemini thought signatures](https://ai.google.dev/gemini-api/docs/thought-signatures), and
[OpenRouter reasoning details](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens).

Neutral request intent is resolved only at the adapter boundary. `Auto` emits no neutral override.
Explicit choices require a model-level capability and exact mapping; unsupported choices and
same-dimension Provider-native controls fail before transport. Typed native configuration remains
available for controls that do not have a portable meaning.

Gemini uses client-managed history. Initial and follow-up requests preserve interaction step order,
tool results, reasoning metadata, and terminal state without relying on server-side conversation
storage. OpenAI and Anthropic apply the equivalent rule to their authoritative output/content
structures.

Responses uses the payload event type for dispatch. Deltas are provisional; `output_item.done`
finalizes visible text and reasoning without requiring nested done events or matching streamed
prefixes. The terminal response supplies any unfinished items and owns final tool arguments and
replay metadata. Already finalized visible blocks are not revalidated against replay metadata.
Interleaved items and additional reasoning parts may wait for item completion to keep canonical
blocks separate. Unknown events, output extensions, and annotations are ignored when not consumed;
duplicate boundaries and optional `[DONE]` sentinels do not produce extra canonical events.
Malformed consumed fields, ambiguous tool identity, explicit errors, and missing terminal responses
remain failures. Responses profiles share these completion rules.

Chat Completions assembles indexed deltas and requires its `[DONE]` terminal sentinel. Anthropic
Messages retains its block-index lifecycle and accepts one post-`message_stop` `[DONE]`. Reference
adapters validate transport completion; late transport failures follow [Runtime recovery](../recovery.md).

Custom integrations implement the public `ProviderAdapter` and may reuse `HttpTransport`, canonical
events, typed failures, and Runtime contracts. Name similarity is not treated as protocol
compatibility.

## Verification

Every reference adapter has request/codec fixtures, malformed lifecycle cases, streaming and
non-streaming equivalence tests, compatible endpoint/authentication contracts, Runtime/tool round
trips, and default-engine loopback tests. Remote smoke tests are isolated in the explicitly invoked
Provider live harness.

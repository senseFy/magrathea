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

Each selected contract owns its request model, codec, stream state machine, failure mapping, and
replay metadata. The OpenAI module selects Responses or Chat Completions explicitly through
`OpenAiTransportConfig.api`; it never infers one from an endpoint. The adapter key identifies a
strict wire-protocol family, not an exclusive vendor hostname. Every contract has a canonical
endpoint and authentication default, while an application may supply a full compatible endpoint
and one of the adapter's explicit authentication modes. Adapters emit only canonical
`ProviderEvent` values. Runtime and Chatbot code never read Provider wire JSON.

Each adapter also exposes an `inputCapabilities` upper bound for attachment MIME envelopes its
codec can encode. This is protocol metadata, not model discovery: products intersect it with their
own policy and model-specific metadata when available. Custom adapters default to no attachment
support and opt in explicitly. Request codecs remain authoritative and reject unsupported or
internally inconsistent attachment input even when a host omitted preflight validation.

Reference adapters model exactly the selected wire contract and are not subclassing bases for
service-specific variants. Pointing an adapter at another service asserts that the service implements
that exact request, streaming, tool, response, and error contract; a compatibility label alone is not
such evidence. Responses and Chat Completions share one module but not a codec. A distinct protocol
integrates through `ProviderAdapter` composition.

Provider-authoritative metadata is retained for replay with the same Provider and model. A
cross-Provider or cross-model replay rebuilds only portable text and tool semantics. Tool calls
become executable only after their protocol lifecycle is finalized and their arguments validate.

Reasoning has three separate meanings that must not be collapsed:

- token usage reports how much internal reasoning a model consumed;
- visible reasoning is only the representation a Provider deliberately returned, classified as a
  Provider-defined view, a summary, or explicitly exposed reasoning text;
- signatures, encrypted content, redacted blocks, and Provider-specific reasoning details are
  opaque continuation state.

Every visible reasoning block has an independent canonical start/delta/end lifecycle and reaches
`FINAL` only after its wire boundary completes. A response may contain both summaries and exposed
reasoning text; adapters preserve them as distinct blocks rather than guessing that one excludes
the other. Empty or omitted visible reasoning is valid and never replaced with fabricated progress.
Chatbot projections expose visible text, classification, redaction state, and lifecycle only. They
never expose opaque continuation values. Telemetry, errors, and diagnostics accept neither form.

Opaque state is retained only inside authoritative Provider history and is replayed unmodified only
to the same Provider and model. The sequence is never reordered, flattened into text, parsed, or
transferred across models. This follows the continuation requirements documented for
[OpenAI Responses](https://platform.openai.com/docs/api-reference/responses-streaming),
[Anthropic extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking),
[Gemini thought signatures](https://ai.google.dev/gemini-api/docs/thought-signatures), and
[OpenRouter reasoning details](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens).

Gemini uses client-managed history. Initial and follow-up requests preserve interaction step order,
tool results, reasoning metadata, and terminal state without relying on server-side conversation
storage. OpenAI and Anthropic apply the equivalent rule to their authoritative output/content
structures.

Streaming codecs preserve strict lifecycle validation while accepting standard transport-envelope
variants. Responses events may omit the optional SSE event name; when present it must match the
payload type. Responses keeps `summary_text` and `reasoning_text` content-part lifecycles distinct.
Some compatible Responses streams omit a reasoning part's nested text/part completion events while
still supplying the complete reasoning item at `response.output_item.done`. That authoritative outer
boundary may finalize an already-started visible reasoning block only when item identity, kind,
part count, index order, and the streamed-text prefix all agree. Any authoritative suffix is emitted
once before the canonical reasoning end; an explicitly completed part may not change. This
reconciliation is reasoning-specific and does not weaken message or Tool finalization.
Chat Completions assembles indexed text, normalized `reasoning_details`, legacy reasoning text, and
tool-call deltas and requires
its `[DONE]` terminal sentinel. Responses and Messages accept a single post-terminal `[DONE]`.
Missing, premature, duplicate, or post-sentinel data still fails closed.

Custom integrations implement the public `ProviderAdapter` and may reuse `HttpTransport`, canonical
events, typed failures, and Runtime contracts. Name similarity is not treated as protocol
compatibility.

## Verification

Every reference adapter has request/codec fixtures, malformed lifecycle cases, streaming and
non-streaming equivalence tests, compatible endpoint/authentication contracts, Runtime/tool round
trips, and default-engine loopback tests. Remote smoke tests are isolated in the explicitly invoked
Provider live harness.

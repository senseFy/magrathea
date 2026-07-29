# ADR-014: Token-Aware Context Management

- Status: Accepted
- Date: 2026-07-24

## Context

A fixed message-count tail neither reflects Provider context windows nor preserves semantic and
Tool boundaries. It can discard a short but important decision while retaining a large payload,
split a Tool call from its result, and make long-session behavior dependent on message shape.

Magrathea needs long-session continuity across Android, JVM/Desktop, iOS, and Web without making a
product facade or a single Provider responsible for history policy.

## Decision

- Complete session messages are the authoritative history and are never replaced by a summary.
- Runtime persists a separate Provider-context projection containing one cumulative semantic
  summary plus recent raw messages.
- Provider-reported input usage drives the budget when it can be proven to describe the current
  immutable history prefix. A portable estimator is the fallback.
- The context window comes from `ModelDescriptor` or an explicit runtime override. Unknown windows
  use reactive typed-overflow recovery instead of an arbitrary message-count limit.
- Compaction is incremental and preserves Tool call/result atomicity. Pending Tool calls block
  compaction.
- The default summary call uses the active Provider/model without Tools or reasoning options.
  Summary usage contributes to total session usage.
- Summary input excludes reasoning, opaque Provider state, credentials, inline attachment data,
  and unbounded Tool results.
- Proactive summary failure fails open. A context-limit response before output may force one
  compaction retry; post-output failure is not retried.
- Direct Providers and the browser Gateway preserve a distinct context-limit failure end to end.

## Consequences

Long sessions are bounded by token budget rather than message count, while full history remains
available for UI, audit, regeneration, and future re-compaction. Semantic compaction has an explicit
Provider cost and may fail; its usage and typed failure are therefore public runtime state.

Persistent context state participates in the same strict fixtures, version checks, and unknown-field
validation as the rest of the stored envelope.

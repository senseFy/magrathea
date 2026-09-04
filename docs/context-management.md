# Context Management

Magrathea manages long conversations with a token-budgeted Provider projection. It does not delete
or rewrite the authoritative session history.

## Model

The persisted `AgentStateSnapshot.messages` list remains the complete conversation. When the
Provider input approaches its context budget, Runtime builds a separate projection:

```text
system prompt
+ cumulative semantic summary of older history
+ recent raw messages
+ current Tool definitions
```

`ContextManagementState` persists the summary boundary, a digest of the summarized prefix, and the
latest Provider-reported prompt usage. Editing or regenerating any summarized message invalidates
the stale projection automatically.

The summary is an ordinary historical user-context message. It never becomes a system message and
cannot override the application's system prompt.

## Trigger and budget

`ContextManagementConfig` controls the policy:

| Setting | Default | Meaning |
|---|---:|---|
| `enabled` | `true` | Enables proactive and overflow-recovery compaction |
| `reserveTokens` | `16,384` | Output and safety space withheld from Provider input |
| `keepRecentTokens` | `20,000` | Target raw-message suffix retained after compaction; message/tool atomicity may keep slightly more |
| `summaryMaxTokens` | `4,096` | Maximum output budget for one summary update |
| `charsPerTokenEstimate` | `4` | Portable fallback estimate when actual usage is unavailable |
| `toolResultSummaryMaxChars` | `2,000` | Per-result bound in summarizer input |
| `contextWindowTokensOverride` | `null` | Explicit window for compatible/dynamic models |
| `overflowRetryLimit` | `1` | Maximum forced compaction retries for one Provider invocation |

The effective input limit is:

```text
context window - max(reserveTokens, ProviderConfig.maxTokens)
```

`ModelDescriptor.maxOutputTokens` is catalog/provider model metadata, not a product request policy.
Runtime resolves the Provider output bound immediately before each invocation:

```text
requested output = ProviderConfig.maxTokens ?: ModelDescriptor.maxOutputTokens
effective output = min(requested output, max(1, context window - final Provider input estimate))
```

An explicit `ProviderConfig.maxTokens` replaces catalog metadata, which lets a host correct stale
or incomplete discovery data. When both values are unknown, Runtime keeps the Provider request
unbounded instead of inventing a model capability. The remaining-context clamp is applied only
when both the context window and final Provider input estimate are known.

The clamp is computed after system-prompt insertion, host context transformation, replay
transformation, and Provider-boundary projection, so content added or removed by those stages is
included. The context manager's earlier estimate remains scoped to projection and compaction
decisions.

The input projection intentionally reserves `reserveTokens` and an explicit request override, but
does not reserve the model's entire catalog maximum. A physical capability can be as large as the
whole context window and does not mean the product intends every response to consume it. Products
that require a predictable answer budget should therefore set `ProviderConfig.maxTokens` for the
request; Runtime then uses that same value for input reservation and output generation. Context
summary calls use `summaryMaxTokens` as their explicit request budget and pass through the same
remaining-context resolver.

Provider-reported input usage is authoritative when it is anchored to the same model, request
shape, Provider options, compaction generation, and immutable history prefix. Otherwise Runtime
estimates the input from message text, structured parts, attachments, system instructions, Tool
schemas, and Provider-specific options that can add instructions or hosted Tools.

Set `ModelDescriptor.contextWindowTokens` and `ModelDescriptor.maxOutputTokens` from trusted model
metadata. For a compatible endpoint whose model discovery does not expose a reliable window, set
`contextWindowTokensOverride`.
Without a model context window or an explicit override, proactive compaction is skipped; a typed
Provider context-limit response can still trigger one recovery attempt.

The browser facade exposes both values as `MagratheaWebChatModel.contextWindowTokens` and
`MagratheaWebChatModel.maxOutputTokens`. JavaScript callers pass positive integers or `null` when a
capability is unknown.

## Semantic compaction

The default runner updates the summary with the active Provider and model through a separate,
Tool-free Provider request. Hosted Provider Tools and reasoning options are disabled for that
request. Summary usage is added to total session usage, but not to `latestRequestUsage`.

Compaction is incremental. A later pass supplies the previous summary plus only the newly eligible
raw history. The retained suffix never begins with a Tool result, and a Tool call/result pair is
never split across the summary boundary. Runtime refuses to compact while Tool calls are pending.

Summarizer input deliberately excludes:

- visible and redacted reasoning;
- signatures, encrypted continuity state, and Provider metadata;
- inline attachment bytes;
- unbounded Tool results.

The summarizer treats all conversation content as untrusted data and returns plain summary text.
Applications may inject a custom `ContextManager`, or compose `TokenAwareContextManager` with a
custom `ContextSummarizer`, to use a dedicated model or a local summarizer.

## Failure behavior

- A proactive summary failure fails open: Runtime keeps the existing projection and proceeds.
- A Provider context-limit response is recoverable only before any output chunk is observed.
- Overflow recovery forces semantic compaction and retries the Provider invocation at most once by
  default, with a distinct invocation identity.
- If no safe cut exists, summary generation fails, or the retry is exhausted, Runtime returns
  `AgentFailureCode.CONTEXT_LIMIT` (`ChatbotFailure.CONTEXT_LIMIT` through the facade).
- OpenAI Responses, OpenAI Chat Completions, Anthropic Messages, direct HTTP adapters, and the Web
  Gateway preserve the typed context-limit failure.

This behavior prevents duplicate partial answers and avoids silently discarding recent or
structurally required messages.

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

Provider-reported input usage is authoritative when it is anchored to the same model, request
shape, Provider options, compaction generation, and immutable history prefix. Otherwise Runtime
estimates the input from message text, structured parts, attachments, system instructions, Tool
schemas, and Provider-specific options that can add instructions or hosted Tools.

Set `ModelDescriptor.contextWindowTokens` from trusted model metadata. For a compatible endpoint
whose model discovery does not expose a reliable window, set `contextWindowTokensOverride`.
Without either value, proactive compaction is skipped; a typed Provider context-limit response can
still trigger one recovery attempt.

The browser facade exposes the same value as the final `MagratheaWebChatModel.contextWindowTokens`
constructor argument. JavaScript callers pass a positive integer or `null` when the window is
unknown.

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

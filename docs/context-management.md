# Context Management

Magrathea manages long conversations with a token-budgeted Provider projection. It does not delete
or rewrite the authoritative session history.

## Model

The persisted `AgentStateSnapshot.messages` list remains the complete conversation. Before each
Provider call, Runtime builds a separate projection in three stages, cheapest first
([ADR-018](adr/ADR-018-tool-result-context-lifecycle.md)):

1. **Result budget:** every model-visible Tool result is bounded to a per-result budget.
2. **Result clearing:** stale Tool results are replaced by restorable stubs, without a model call.
3. **Semantic compaction:** older history is replaced by a cumulative summary.

```text
system prompt
+ cumulative semantic summary of older history
+ recent raw messages, with bounded or cleared Tool results
+ current Tool definitions
```

`ContextManagementState` persists the summary boundary, the clearing watermark, digests of the
prefixes they cover, and the latest Provider-reported prompt usage. Editing or regenerating a
summarized or cleared message invalidates the stale projection automatically.

The summary is an ordinary historical user-context message. It never becomes a system message and
cannot override the application's system prompt.

## Trigger and budget

`ContextManagementConfig` controls the policy:

| Setting | Default | Meaning |
|---|---:|---|
| `enabled` | `true` | Enables all three stages and overflow recovery |
| `reserveTokens` | `16,384` | Output and safety space withheld from Provider input |
| `keepRecentTokens` | `20,000` | Target raw-message suffix retained after compaction; message/tool atomicity may keep slightly more |
| `summaryMaxTokens` | `4,096` | Maximum output budget for one summary update |
| `charsPerTokenEstimate` | `4` | Units per token for the portable estimate when actual usage is unavailable |
| `estimationUnit` | `UTF8_BYTES` | Unit the portable estimate counts; requests persisted before schema 8 keep `UTF16_CHARS` |
| `toolResultSummaryMaxChars` | `2,000` | Per-result bound in summarizer input |
| `contextWindowTokensOverride` | `null` | Explicit window for compatible/dynamic models |
| `overflowRetryLimit` | `1` | Maximum forced compaction retries for one Provider invocation |
| `toolResults` | see below | Tool-result budget and clearing |

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
shape, Provider options, compaction generation, clearing generation, result budget, and immutable
history prefix. Otherwise Runtime estimates the input from message text, structured parts,
attachments, system instructions, Tool schemas, and Provider-specific options that can add
instructions or hosted Tools. The estimate counts UTF-8 bytes by default, which approximates tokens
for CJK and Latin text alike; UTF-16 characters undercount Chinese text roughly threefold.

Set `ModelDescriptor.contextWindowTokens` and `ModelDescriptor.maxOutputTokens` from trusted model
metadata. For a compatible endpoint whose model discovery does not expose a reliable window, set
`contextWindowTokensOverride`.
Without a model context window or an explicit override, proactive compaction is skipped; a typed
Provider context-limit response can still trigger one recovery attempt.

The browser facade exposes both values as `MagratheaWebChatModel.contextWindowTokens` and
`MagratheaWebChatModel.maxOutputTokens`. JavaScript callers pass positive integers or `null` when a
capability is unknown.

## Tool results

`ContextManagementConfig.toolResults` holds both Tool-result stages. A `ContextTokenThreshold`
resolves to the smaller of `inputPercent` of the input limit and `maxTokens`; with an unknown input
limit it resolves to `maxTokens`. A null `budget` or `clearing` disables that stage, and
`ToolResultContextConfig.DISABLED` disables both.

| Setting | Default | Meaning |
|---|---:|---|
| `budget` | 20% of the input limit, at most `12,000` | Maximum model-visible size of one Tool result |
| `clearing.trigger` | 50%, at most `100,000` | Projected input that starts a clearing pass |
| `clearing.keepRecentResults` | `3` | Most recent clearable results that always stay intact |
| `clearing.clearAtLeast` | 10%, at most `10,000` | A pass that would free less is skipped |
| `clearing.idleAfterMillis` | `null` | Also clear after this much inactivity, when the prompt cache is likely cold |
| `clearing.protectActiveRun` | `true` | Never clear results of the current run |
| `maxStubChars` | `2,000` | Larger Tool-supplied stubs fall back to the generic stub |

### Result budget

Runtime passes the resolved budget to each Tool as `ToolExecutionRequest.resultBudgetTokens`, so a
Tool can shape its output, for example by admitting fewer whole search results. Ignoring it is
valid. In the projection, a result whose model-visible text exceeds the budget is middle-truncated
at code-point boundaries behind a notice that states its original size; model images are kept.
Reduction is deterministic, so a result keeps the same projected form while the budget is
unchanged.

### Result clearing

A result is a candidate when it is in the raw projection, has `STANDARD` retention, and, while
`protectActiveRun` is set, precedes the current run's first message. When the projection exceeds
`trigger`, during overflow recovery, or after `idleAfterMillis` without a new assistant message,
every candidate except the most recent `keepRecentResults` is cleared. Clearing as much as possible
per pass amortizes each prompt-cache restart. The outcome is a monotonic watermark that never
splits the results of one message.

A cleared result keeps its Tool call ID, name, and error flag. Its model-visible result becomes a
`tool_result_cleared` object naming the Tool, with the Tool's stub under `retained` and a note that
the Tool can be called again. A stub replaces a result only when it is smaller.

Tools and hosts describe results with `ToolResultContextHints`:

- `retention = PINNED` keeps a result intact, for instructions, memory, or outcomes of irreversible
  actions.
- `stub` is a small, restorable summary such as a query and the URLs it found.

Hints are runtime-only and never reach a Provider. A host attaches them to third-party or MCP
Tools in `AgentInterceptor.afterToolCall`. The built-in search Tools supply stubs themselves.

A clearing pass commits a checkpoint. `ContextPreparationResult.toolResults` reports reduced and
newly cleared results, freed tokens, the clearing generation, and the trigger; the
`magrathea.context.prepare` span records the same counts without content.

## Semantic compaction

The default runner updates the summary with the active Provider and model through a separate,
Tool-free Provider request. Hosted Provider Tools and reasoning options are disabled for that
request. Summary usage is added to total session usage, but not to `latestRequestUsage`.

Compaction runs only when the bounded and cleared projection still exceeds the input limit, or
during overflow recovery after a clearing pass. Compaction is incremental. A later pass supplies the
previous summary plus only the newly eligible raw history. The retained suffix never begins with a
Tool result, and a Tool call/result pair is never split across the summary boundary. Runtime
refuses to compact while Tool calls are pending.

Summarizer input deliberately excludes:

- visible and redacted reasoning;
- signatures, encrypted continuity state, and Provider metadata;
- inline attachment bytes;
- unbounded Tool results; a cleared result contributes only its stub.

The summarizer treats all conversation content as untrusted data and returns plain summary text.
Applications may inject a custom `ContextManager`, or compose `TokenAwareContextManager` with a
custom `ContextSummarizer`, to use a dedicated model or a local summarizer.

## Provider continuity

With the default `protectActiveRun`, the current run is never edited, so in-flight Tool loops keep
their reasoning continuity. Earlier input changes only when a clearing or compaction pass runs.
Current Claude models reject replayed thinking whose prefix changed, so the Anthropic adapter opts
into dropping such blocks for adaptive-thinking requests to the official API; see
[Providers](providers.md#replay-continuity).

## Failure behavior

- A proactive summary failure fails open: Runtime keeps the existing projection and proceeds. A
  clearing pass that already ran is kept.
- A Provider context-limit response is recoverable only before any output chunk is observed.
- Overflow recovery clears stale Tool results, forces semantic compaction, and retries the
  Provider invocation at most once by default, with a distinct invocation identity.
- If no safe cut exists, summary generation fails, or the retry is exhausted, Runtime returns
  `AgentFailureCode.CONTEXT_LIMIT` (`ChatbotFailure.CONTEXT_LIMIT` through the facade).
- OpenAI Responses, OpenAI Chat Completions, Anthropic Messages, direct HTTP adapters, and the Web
  Gateway preserve the typed context-limit failure.

This behavior prevents duplicate partial answers and avoids silently discarding recent or
structurally required messages.

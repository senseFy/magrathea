# ADR-018: Layered Tool-Result Context Management

- Status: Accepted
- Date: 2026-10-18
- Extends: [ADR-014](ADR-014-token-aware-context-management.md)

## Context

Tool results are the fastest-growing part of an agent conversation. One web search, MCP call, or
page fetch can add tens of thousands of characters, and every later Provider request replays that
result until semantic compaction removes it.

Magrathea currently has two boundaries:

- `RuntimeConfig.maxToolResultChars` (1,048,576 characters by default) is a storage and memory
  safety limit. A larger result is rejected as a Tool failure; anything smaller enters model
  context unchanged.
- ADR-014 semantic compaction summarizes older history when the input approaches the context
  window. It costs a Provider call, is lossy, and is the only way an old Tool result leaves the
  Provider projection.

Nothing between these boundaries bounds one result for the model or removes stale results
cheaply. Products compensate with Tool-specific limits, such as fewer search results. That
protects one Tool, not MCP or fetch Tools, and does not scale with the model window.

### Prior art

Mature agent products converge on the same progressive structure: bound each result, clear stale
results without a model call, and summarize only when that is not enough.

| Product | Bound each result | Clear stale results | Summarize | Tool-side shaping |
|---|---|---|---|---|
| Codex (`openai/codex` at `8c07694`) | Per-model truncation policy, overridable with `tool_output_token_limit`; middle truncation behind an original-size notice; full payloads stay in the rollout | None | Automatic at `model_auto_compact_token_limit`: a summary plus up to 20,000 tokens of recent user messages; optional server-side compaction | Web search runs as a sub-request bounded by the same output budget; the result list goes only to the UI |
| Claude Code (community source analysis) | Results over 50,000 characters are persisted and replaced by a 2 KB preview | Microcompact keeps the most recent results; a placeholder when the prompt cache is cold, server-side cache edits when it is warm | Autocompact | WebSearch returns titles and URLs; WebFetch returns a small model's answer about the page |
| Anthropic Messages API | None | `clear_tool_uses`: trigger 100,000 input tokens, keep 3, `clear_at_least`, `exclude_tools` | Server-side compaction | Dynamic filtering in code execution |
| OpenAI Responses API | `search_context_size` and `return_token_budget` for hosted search | None | `context_management.compact_threshold` and `/responses/compact` | Hosted |
| LangChain v1 | None | `ContextEditingMiddleware` with `ClearToolUsesEdit`, mirroring Anthropic's 100,000-token trigger | Summarization middleware | Deep Agents offload large results to a backend store |
| OpenCode | Tools bound their own output (read: 2,000 lines or 50 KB; shell: full output saved, preview returned) | V1 pruning, removed in V2 | Compaction keeping 15,000 recent tokens | None |

Manus states the rule that makes clearing safe: compression must be restorable. A page's content
can leave the context as long as its URL stays.

### Provider constraints

- Prompt caches are prefix-based. Any change to earlier input restarts the cache at that point.
  Anthropic therefore recommends clearing in batches (`clear_at_least`).
- On current Claude models, a thinking block stays valid only while its whole prefix (system
  prompt, tools, and earlier messages) is unchanged. Accounts created on or after 2026-08-31
  receive a 400 error for an invalid block unless the request sets
  `thinking.block_binding.prefix_mismatch_behavior` to `drop_block`. Magrathea already edits the
  prefix today through compaction and between-turn Tool-set changes.

## Decision

### Principles

1. Authoritative history is never changed (ADR-014). Every mechanism here is a deterministic
   function of history, persisted projection state, and request configuration, and affects only
   the Provider projection.
2. Cheapest first: bound each result, then clear stale results without a model call, then
   summarize.
3. Restorable: a cleared result leaves a stub that names the Tool and what it found, so the model
   can call the Tool again.
4. Append-friendly: by default the active run is never edited, and earlier input changes only in
   monotonic, batched steps.
5. Tools own semantics; Runtime owns mechanism. Runtime never branches on a Tool name. Tools and
   hosts describe results with typed hints.
6. Policy is data; behavior is code. Thresholds are serializable configuration persisted with the
   request. A host that needs a different algorithm replaces the `ContextManager`.

### Pipeline

`TokenAwareContextManager.prepare` builds the projection in three ordered stages:

1. **Result budget:** bound every model-visible Tool result.
2. **Result clearing:** replace stale results with stubs.
3. **Semantic compaction:** the ADR-014 behavior, evaluated on the output of stages 1 and 2.

Token estimates, usage anchoring, summarizer input, and
`ContextPreparationResult.estimatedInputTokens` all describe the projected form. The host
`ContextTransformer`, `ReplayPolicy`, and Provider projection still run afterwards, unchanged.

New configuration lives in `ContextManagementConfig.toolResults`:

```kotlin
@Serializable
data class ContextTokenThreshold(val inputPercent: Int? = null, val maxTokens: Long? = null)

@Serializable
data class ToolResultContextConfig(
    val budget: ContextTokenThreshold? = ContextTokenThreshold(inputPercent = 20, maxTokens = 12_000),
    val clearing: ToolResultClearingConfig? = ToolResultClearingConfig(),
    val maxStubChars: Int = 2_000,
)

@Serializable
data class ToolResultClearingConfig(
    val trigger: ContextTokenThreshold = ContextTokenThreshold(inputPercent = 50, maxTokens = 100_000),
    val keepRecentResults: Int = 3,
    val clearAtLeast: ContextTokenThreshold = ContextTokenThreshold(inputPercent = 10, maxTokens = 10_000),
    val idleAfterMillis: Long? = null,
    val protectActiveRun: Boolean = true,
)
```

A threshold resolves to the smaller of `inputPercent` of the input limit and `maxTokens`. When the
input limit is unknown it resolves to `maxTokens`, or to nothing when `maxTokens` is null. A null
`budget` or `clearing` disables that stage. `ContextManagementConfig.enabled = false` disables all
three stages.

### Stage 1: Result budget

- Runtime passes the resolved budget to the executor as `ToolExecutionRequest.resultBudgetTokens`.
  A Tool may shape its output to fit; ignoring the value is valid. Codex passes the same budget to
  its search sub-request.
- At projection time, a result whose model projection exceeds the budget is reduced generically.
  Its text representations (canonical result and model-audience text content) are joined and
  middle-truncated at code-point boundaries, keeping equal head and tail, behind a fixed notice
  that states the estimated original size. Image content is kept; item and byte limits already
  bound it.
- Reduction is deterministic. A result keeps the same projected form while the budget is
  unchanged, so this stage never edits the active run after the model has seen it.
- `maxToolResultChars` keeps its meaning as a storage and memory safety limit.

### Stage 2: Result clearing

**Eligibility.** A result is a candidate when it is in the raw projection (after any compaction
cut), has `STANDARD` retention, and, while `protectActiveRun` is true, precedes
`ContextPreparationRequest.activeRunStartMessageId`. Runtime sets that ID to the last user message
of the current run's request. The most recent `keepRecentResults` candidates are always protected.
A host whose single runs make many Tool calls, such as a coding agent, sets `protectActiveRun` to
false so clearing also applies inside the run, as Claude Code's microcompact does; Provider
continuity then relies on the Anthropic behavior below.

**Triggers.**

- *Pressure:* the stage-1 projection exceeds `trigger`.
- *Idle:* `idleAfterMillis` is set and the newest assistant message is older than that. The
  Provider cache is then likely cold, so an edit forfeits no cached prefix. This is Claude Code's
  cold path.
- *Overflow recovery:* always, before forced compaction.

**Selection.** When triggered, every candidate except the protected ones is cleared. Clearing as
much as possible per pass amortizes each cache restart. A pass that would free less than
`clearAtLeast` is skipped, except during overflow recovery. The outcome is a monotonic watermark:
all eligible parts in messages up to `clearedThroughMessageId` are cleared. A message is included
only when all its candidate parts are clearable, so results of parallel calls are never split.

**Stub.** A cleared part keeps its Tool call ID, name, and error flag. Typed content is removed.
Its model-visible result becomes:

```json
{
  "type": "tool_result_cleared",
  "tool": "web_search",
  "retained": { "query": "…", "sources": [{ "title": "…", "url": "https://…" }] },
  "note": "Earlier result removed from context to save space. Call the tool again if you need its details."
}
```

`retained` is the Tool's stub when present. A stub longer than `maxStubChars` is ignored, and the
trace records that.

**State.** `ContextManagementState.toolResultClearing` persists:

```kotlin
@Serializable
data class ToolResultClearing(
    val clearedThroughMessageId: String,
    val sourcePrefixDigest: String,
    val generation: Long,
    val clearedResults: Int,
    val freedTokens: Long,
    val trigger: ToolResultClearingTrigger, // PRESSURE, IDLE, OVERFLOW_RECOVERY
    val createdAtEpochMs: Long,
)
```

As with compaction, editing or regenerating a message at or before the watermark changes the
digest and discards the state; the next pass recomputes it. A watermark before the compaction
cut is harmless and is kept.

### Stage 3: Semantic compaction

Compaction runs only when stages 1 and 2 leave the projection above the input limit, or during
overflow recovery. Its summarizer input uses the projected forms, so a cleared result contributes
only its stub.

### Extension points

| Need | Mechanism | Owner |
|---|---|---|
| Never clear a result (instructions, memory, outcomes of irreversible actions) | `ToolResultContextHints.retention = PINNED` | Tool, or host interceptor |
| Keep identifiers after clearing | `ToolResultContextHints.stub` | Tool, or host interceptor |
| Fit output to the budget | `ToolExecutionRequest.resultBudgetTokens` | Tool |
| Tune thresholds per product, model, or Provider | `ContextManagementConfig.toolResults` on each request | Host |
| Use a different algorithm | Replace the `ContextManager` | Host |

```kotlin
@Serializable
enum class ToolResultRetention { STANDARD, PINNED }

@Serializable
data class ToolResultContextHints(
    val retention: ToolResultRetention = ToolResultRetention.STANDARD,
    val stub: JsonElement? = null,
)
```

Hints are a field of `ToolExecutionResult` that `toMessagePart` copies to `ToolResultPart`.
`sanitizedForModelBoundary` removes them, so they never reach a Provider. A host attaches hints to
third-party or MCP Tools in `AgentInterceptor.afterToolCall`. The stage implementations stay
internal until a second implementation justifies a public strategy interface.

### Provider continuity and caching

- With the default `protectActiveRun`, in-flight Tool loops keep their reasoning continuity:
  Anthropic thinking, Gemini thought signatures, and Chat Completions `reasoning_content`.
- **Prerequisite:** the Anthropic adapter sends the `thinking-binding-controls-2026-08-01` beta
  header with `thinking.block_binding.prefix_mismatch_behavior = "drop_block"` when three
  conditions hold: the request uses adaptive thinking, it replays at least one thinking block, and
  it targets the official Claude API. The field requires that beta header, Claude Sonnet 5.5 and
  Haiku 5.5 accept it only with adaptive thinking, and compatible endpoints may reject it. The
  codec already ignores the reported `input_transformations`. Manually budgeted `enabled`
  thinking keeps today's behavior. This also fixes the existing compaction and Tool-set cases,
  and can ship independently.
- OpenAI Responses replay is stateless (`store = false`, encrypted reasoning); no change is needed.
- Provider-native clearing and compaction are not used. They would split authority over the
  projection, estimates, and persisted state. A later capability-gated strategy may delegate to
  them when the Provider reports the edits it applied.

### Estimation

A token budget is meaningless if CJK text is undercounted. The portable estimator divides UTF-16
characters by four, which undercounts Chinese text roughly threefold. A new
`ContextManagementConfig.estimationUnit` selects `UTF8_BYTES` (the Codex approximation) for new
requests; migrated requests keep `UTF16_CHARS`. Provider-reported usage remains authoritative
whenever ADR-014 can anchor it.

### Observability

- `ContextPreparationResult.toolResults: ToolResultContextReport` reports reduced results and
  tokens, newly cleared results, freed tokens, the clearing generation, and the trigger.
- The `magrathea.context.prepare` span adds `magrathea.context.tool_results_reduced`,
  `magrathea.context.tool_results_cleared`, `magrathea.context.tool_result_tokens_freed`, and
  `magrathea.context.clearing_trigger`.
- `ChatbotContextManagementSnapshot` adds `clearingGeneration` and `clearedToolResults`.
- A change to the clearing state is committed with a checkpoint, like a compaction.
- No Tool content, stub, or query enters traces or debug recordings.

### Persistence

Storage schema 8 adds one adjacent migration from schema 7:

- `ContextManagementConfig.toolResults` and `estimationUnit`. Migrated requests receive an explicit
  disabled policy and `UTF16_CHARS`, so an interrupted v7 run resumes with its original
  projection. New requests use the defaults.
- `ContextManagementState.toolResultClearing`, migrated as `null`.
- `ContextUsageObservation.clearingGeneration` and `resultBudgetTokens`. Migrated observations are
  dropped, so the first prepared input falls back to estimation once.
- `ToolResultPart.contextHints` in request and state messages, and
  `ToolExecutionResult.contextHints` in checkpoint Tool journals, migrated as `null`.

`ToolExecutionRequest` is not persisted, so `resultBudgetTokens` needs no migration. A new
`StorageSchemaV8Adapter`, frozen fixtures, descriptor fingerprint, and ledger entries are added,
and both storage adapters exercise the migration rewrite path. Schema 6 remains readable through
the migration chain. New public fields are appended with defaults, which keeps callers
source-compatible; the JVM ABI baselines record the change.

### Built-in Tools

- `WebSearchTool` admits sources in rank order until `maxSourcesInContext` or the result budget is
  reached, and sets `truncated` accordingly. Its stub keeps the query plus source titles and URLs,
  without snippets.
- `ImageSearchTool` keeps the query plus image titles and source URLs, without image content.
- X Search keeps the query plus post URLs.

## Alternatives considered

- **Tool-specific count limits only.** They protect one Tool, not MCP or fetch Tools, and do not
  scale with the model window.
- **Destructive truncation at execution time.** This contradicts ADR-014 and loses full results for
  the UI and later compaction. Codex keeps the same separation by preserving full payloads in its
  rollout.
- **Semantic compaction only (status quo).** It has a Provider cost, is lossy, and rewrites more
  of the prefix than clearing does.
- **Clearing at every run boundary.** It restarts the prompt cache on every turn.
- **Provider-native clearing or compaction.** Rejected for now, as described above.
- **Sub-agent isolation.** This is product orchestration. Tools can already isolate work
  themselves, as Claude Code's WebFetch and Codex's search sub-request do.

## Consequences

- Large or stale Tool results no longer drive semantic compaction, so compaction runs less often.
- The projection depends on configuration. Changing a budget or the model restarts Provider caches,
  as any prefix change does.
- The model loses raw access to cleared results and must call the Tool again to restore them.
  Tools with side effects should pin their results or provide a stub that records the outcome.
- Several public types gain trailing fields with defaults. Source compatibility is kept, and the JVM
  ABI baselines are updated deliberately.

## Verification

- Threshold resolution with known and unknown windows, and with either field missing.
- Stage 1: deterministic reduction at code-point boundaries, a stable notice, preserved images,
  estimates based on the projected form, the budget passed to Tool requests, and small results
  left untouched.
- Stage 2: candidates limited to the raw projection before the active run (and inside it when
  `protectActiveRun` is false), `PINNED` exclusion,
  `keepRecentResults`, a monotonic watermark, unsplit parallel results, skipped passes below
  `clearAtLeast`, the idle trigger with an injected clock, and overflow recovery that clears before
  compaction.
- Stub wrapping, `maxStubChars` fallback, preserved error flag, and removed typed content.
- State invalidation after edit and regeneration, invalidated usage observations, and identical
  projections after restoring a persisted session.
- Compaction only after stages 1 and 2, with stubs as summarizer input.
- Runner checkpoints, trace attributes, chatbot snapshot fields, and an unchanged
  `ContextTransformed` contract.
- Anthropic requests with adaptive thinking that replay thinking blocks on the official endpoint
  carry the beta header and `drop_block`; other requests are unchanged.
- Schema 7 to 8 golden migrations for sessions and checkpoints, the schema 6 chain, and strict
  unknown-field validation.
- JVM binary-compatibility contracts and a projection-cost check against the performance baseline
  for long sessions.
- Built-in Tool stubs and budget-aware source admission.

## Implementation sequence

1. Anthropic block-binding behavior (independent fix).
2. Core types, configuration, schema 8, and fixtures.
3. Stages 1 and 2 in `TokenAwareContextManager`, and runner wiring: active-run ID, budget
   handshake, checkpoint, tracing, and chatbot snapshot.
4. Built-in Tool stubs and budget-aware normalization.
5. Documentation: [context management](../context-management.md), the
   [Web Search](../web-search.md), [Image Search](../image-search.md), and
   [X Search](../x-search.md) contracts, [Providers](../providers.md#replay-continuity),
   [behavior contracts](../behavior-contracts.md), ADR-005, ADR-015, and the ADR index.

## Future work

- An optional `recall_tool_result` Tool that returns a cleared result from authoritative history
  without re-executing it. Claude Code reads its persisted output in the same way, and Deep Agents
  read offloaded results.
- Capability-gated delegation to Provider-native clearing and compaction.
- Clearing large Tool-call arguments, as Anthropic's `clear_tool_inputs` does.

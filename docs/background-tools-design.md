# Background Tools: Technical Design

- Status: Implemented for the first SDK release after 0.1.0-alpha.18; see section 11
- Date: 2026-10-24
- Implements: [ADR-019](adr/ADR-019-session-owned-background-tools.md) (Accepted)
- Consumer integration: Enjoy `docs/architecture/background-tools-integration-design.md`

This document turns ADR-019 into concrete SDK seams: components, data shapes, persistence, flows,
public API impact, and an implementation order. Type and member names match the implementation.
ADR-019 remains the behavioral contract; where this design narrows it, the ADR is updated in the
same change.

## 1. Scope

v1 includes:

- opt-in background execution for Tools that declare it, in sessions owned by `AgentSessionManager`;
- receipt, Runtime notice, and model read, plus Run-local await, cancel, and finish controls;
- host-triggered continuation Runs and delivery into active Runs at existing turn boundaries;
- Send preemption, Session Stop, targeted cancellation, and destructive fencing;
- persistence and restore through `magrathea-storage-room`.

v1 excludes:

- durable waits that survive a Run (ADR-019 O-01);
- background execution for direct `AgentRunner` consumers without a manager;
- `magrathea-storage-web` and the browser Gateway path, which keep blocking execution;
- remote reattachment, cross-process coordination, and exactly-once external effects.

## 2. Decisions

| ID | Decision | Rationale |
|---|---|---|
| D1 | The manager never starts a result-triggered Run by itself. It exposes a pending activation in the session snapshot; the host starts a continuation with a request it builds | Keeps the rule that the manager neither starts Provider work nor chooses credentials. Hosts already gate recovery on execution-host availability |
| D2 | A call becomes background work only after validation, limits, permission, approval, and `beforeToolCall` complete in its originating Run. The record captures the post-interceptor `ToolExecutionRequest` | Tool execution depends on Run-scoped request state, and a terminal commit removes the Run checkpoint |
| D3 | Background execution requires `RuntimeConfig.background`, `ToolExecutor.backgroundPolicy == ELIGIBLE`, a manager-owned session, and an attached `BackgroundExecutionStore`. Any missing condition keeps the blocking path | Opt-in at every layer, as ADR-019 requires |
| D4 | A receipt is an ordinary Tool result with a typed marker. A notice is a new `MessageRole.RUNTIME` message | `USER` would break hosts and ADR-018 anchors keyed to the last user message; `SYSTEM` is hoisted by the Anthropic and Gemini adapters |
| D5 | Background state lives in a separate optional store of per-execution records. Delivery and acknowledgement are derived from canonical history | No second whole-snapshot writer, and delivery state matches replay exclusion by construction |
| D6 | Results reach an active Run only at turn start, where steering messages are appended today. A Run that ends while results wait is not extended; the host starts a continuation | Turn start is a model-input boundary and never mutates an in-flight request. Every automatic supplement then passes host admission and scheduling, activation limits, and the business-Tool restriction |
| D7 | Chatbot keeps an activity `RUNNING` while its execution is background work, hides notices and control calls, and attaches retrieved payloads to the originating activity | Chatbot public enums stay unchanged for facade consumers |
| D8 | `lease.cancel()` keeps its meaning (stop the Run). New commands cover Session Stop, targeted cancellation, continuation, and background suspend/resume | ADR-019 forbids silently broadening Run cancellation |
| D9 | v1 storage support is Room only. Other adapters do not provide the store, so their sessions stay blocking | Enjoy's browser build uses storage-web and the Gateway |

## 3. Components

```text
Application composition root
├─ BackgroundExecutionStore   new optional port; storage-room implements it
├─ BackgroundToolExecutor     new; owns Session-scoped jobs and records
├─ DefaultAgentRunner(..., background = executor)
│    hands off eligible calls, executes controls, closes calls on preemption
├─ AgentSessionManager(runner, persistence, background = executor)
│    commands, fences, snapshots, restore, delete and clear
└─ Chatbot facade
     builds requests, including continuations, and projects background facts
```

`BackgroundToolExecutor` is created once per runtime root and passed to both the Runner and the
manager. It owns a `SupervisorJob`; manager `close` interrupts live Runs and then closes the
executor. A Run never owns background jobs, so Run cancellation cancels only Run-scoped coroutines.

The manager registers each installed runtime with the executor and unregisters it on release,
delete, and close. The Runner hands off only for registered sessions, so a direct `AgentRunner`
consumer always stays blocking. Manager commands keep their per-session command mutex; record
writes for one session are serialized by a per-session mutex inside the executor. Budget accounting
uses an injectable `TimeSource` so deterministic tests can drive it from the coroutine test clock.

## 4. Data model

### 4.1 Configuration and eligibility

```kotlin
// magrathea-core
enum class ToolBackgroundPolicy { BLOCKING, ELIGIBLE }

interface ToolExecutor {
    // existing members
    val backgroundPolicy: ToolBackgroundPolicy get() = ToolBackgroundPolicy.BLOCKING
}

enum class BackgroundConsumptionPolicy { LATEST_CONTEXT, ORIGIN_DIRECTED, PASSIVE }

@Serializable
data class BackgroundExecutionConfig(
    val handoffWaitMillis: Long,
    val explicitWaitAllowanceMillis: Long,
    val maxAutomaticActivationsPerSource: Int,
    val maxQueuedLifetimeMillis: Long,
    val consumptionPolicy: BackgroundConsumptionPolicy = BackgroundConsumptionPolicy.LATEST_CONTEXT,
    val allowBusinessToolsInContinuations: Boolean = false,
)

// RuntimeConfig gains: val background: BackgroundExecutionConfig? = null
```

Durations and counts have no SDK defaults; hosts set them explicitly (ADR-019 O-03). `RuntimeConfig`
is persisted inside `AgentRequest`, so the new field follows the existing ABI-preserving
constructor and `copy` overloads.

### 4.2 Receipt

```kotlin
@Serializable
data class ToolBackgroundReceipt(
    val handle: String,
    val executionId: String,
    val state: BackgroundExecutionState, // QUEUED or RUNNING at handoff
)

// ToolResultPart and ToolExecutionResult gain: val backgroundReceipt: ToolBackgroundReceipt? = null
```

The receipt's model-visible `result` is fixed JSON such as
`{"status":"running","handle":"bg_7f3kq9","retrieve":"read_background_result"}`. It is never an
error result. Its `contextHints.retention` is `PINNED`, so ADR-018 clearing never removes the
mapping from handle to call.

### 4.3 Runtime notice

```kotlin
// MessageRole gains RUNTIME

@Serializable
@SerialName("runtime_notice")
data class RuntimeNoticePart(
    val entries: List<RuntimeNoticeEntry>,
    val delivery: RuntimeNoticeDelivery, // COALESCED or CONTINUATION
    val text: String,                    // rendered once from the fixed template
    override val providerMetadata: JsonObject? = null,
) : MessagePart

@Serializable
data class RuntimeNoticeEntry(
    val handle: String,
    val toolName: String,
    val callId: String,
    val outcome: BackgroundOutcome, // SUCCEEDED, ERROR, TIMEOUT, or INTERRUPTED
    val resultVersion: Int,
)
```

`text` is rendered when the notice is created and stored, so every replay sends identical bytes.
The template uses only runtime-owned values. `CANCELLED` outcomes never produce a notice.

The four reference request builders project a `RUNTIME` message as a user-input text block in its
chronological position, using the wire fragments in ADR-019. `DefaultReplayPolicy` orders `RUNTIME`
like `USER`, including orphan repair; the Runtime appends notices only after every call is closed,
and tests assert that invariant. The rendered text never contains call IDs: the receipt already maps
each handle to its call in history, and call IDs can be renormalized after a model switch. Entries
keep the call ID as structured runtime data. `magrathea-chatbot` excludes `RUNTIME` messages from
`ChatbotSnapshot.messages`.

### 4.4 Run activation and state

```kotlin
@Serializable
data class RunActivation(
    val source: RunActivationSource, // USER_INPUT or BACKGROUND_RESULTS
    val anchorMessageId: String? = null,
    val sourceIds: List<String> = emptyList(), // originating user message IDs
)

// AgentRequest gains: val activation: RunActivation? = null (null means USER_INPUT)
// AgentStateSnapshot gains: foregroundToolWaitMillis and explicitWaitMillis
```

For `BACKGROUND_RESULTS`, the anchor is the notice that starts the Run, and
`ContextPreparationRequest.activeRunStartMessageId` uses it instead of the last user message. Both
wait counters are cumulative and persisted in checkpoints, so recovery cannot renew them.

### 4.5 Background records

```kotlin
@Serializable
data class BackgroundExecutionRecord(
    val sessionId: AgentSessionId,
    val executionId: String,        // same ID as the Run journal record
    val handle: String,             // random and non-sequential
    val originRunId: AgentRunId,
    val originCallId: String,
    val toolName: String,
    val sourceId: String,           // originating user message ID
    val state: BackgroundExecutionState,    // QUEUED, RUNNING, or TERMINAL
    val outcome: BackgroundOutcome? = null, // SUCCEEDED, ERROR, TIMEOUT, CANCELLED, INTERRUPTED
    val result: ToolExecutionResult? = null,
    val resultVersion: Int = 0,
    val executionRequest: ToolExecutionRequest,
    val effectiveTimeoutMillis: Long,
    val activationSuppressed: Boolean = false,
    val queuedAtEpochMs: Long,
    val startedAtEpochMs: Long? = null,
    val settledAtEpochMs: Long? = null,
)

@Serializable
data class BackgroundOriginContext(
    val sessionId: AgentSessionId,
    val runId: AgentRunId,
    val request: AgentRequest, // stored with an empty message list
)
```

An origin context is written once for each Run that hands off work and is deleted when no record
references it. Late settlement uses it for `afterToolCall` interceptors and runtime result limits.
Interceptors therefore see the originating request without its message history for background
settlements (decision R1). `afterToolCall` runs once, at settlement; reads and awaits return the
stored post-interceptor result and never run it again.

### 4.6 Store port

```kotlin
interface BackgroundExecutionStore {
    suspend fun upsert(record: BackgroundExecutionRecord)
    suspend fun putOrigin(origin: BackgroundOriginContext)
    suspend fun load(sessionId: AgentSessionId): BackgroundSessionRecords
    suspend fun deleteSession(sessionId: AgentSessionId)
    suspend fun clear()
}
```

Each upsert replaces one record, so there is no competing whole-snapshot writer. The existing
`AgentPersistence` port is unchanged.

### 4.7 Storage and schema

- Storage envelopes move from schema v8 to v9 additively (`MessageRole.RUNTIME`,
  `RuntimeNoticePart`, receipt markers, configuration, activation, wait counters), with an adjacent
  migration and refrozen descriptors under ADR-005 and `persistence/schema-ledger.json`.
- Records and origin contexts are two further payload kinds, `background_execution` and
  `background_origin`, in the existing `agent-storage-envelopes` domain at schema 9. Each envelope
  carries an explicit kind field, so later migrations never infer a kind from its shape; the ledger
  notes that every later migration must handle both kinds.
- `MagratheaDatabase` moves from version 1 to 2 and adds `agent_background_executions` and
  `agent_background_origins`; `agent_sessions` and `agent_checkpoints` are unchanged.
- `magrathea-storage-web` does not implement the port in v1.

## 5. Flows

### 5.1 Admission and handoff

In `DefaultAgentRunner.executeToolCalls`, a call that passes D2's checks is either:

- **eligible**: the work starts in the executor's Session scope right after admission, and the Run
  awaits it with the remaining handoff allowance; or
- **blocking**: it executes in the Run's scope as it does today.

The batch waits until every blocking call has settled and either every eligible call has settled or
the handoff allowance is exhausted. Elapsed waiting is counted once per batch. Eligible calls still
pending at that point are handed off: the origin context is stored first, then the record (`QUEUED`
or `RUNNING`), and only then the receipt is committed and `AgentEvent.ToolHandedOff` emitted instead
of `ToolCompleted`. Calls that settle in the foreground never get a record. A handed-off call's Run
journal record becomes `COMPLETED` with the receipt as its result, so Run recovery reuses the
receipt and never re-executes the call.

There is no Runtime-side admission queue: the Runner acquires a blocking call's permit directly,
and the executor acquires an eligible execution's permit when its job starts. A shared permit is a
FIFO-fair `SharedToolExecutionPermit` (`Semaphore`), so waiters are admitted in acquisition order
and a held permit is never preempted. Foreground-first admission with bounded aging is deferred
until contention is measured (section 11).

### 5.2 Execution and settlement

The executor acquires the Tool's permit when the job starts; `SharedToolExecutionPermit` admits
waiters FIFO and never preempts a held permit. It writes `RUNNING`, and runs `execute` under `effectiveTimeoutMillis`. Settlement applies `afterToolCall` interceptors with the
origin context, normalization, and runtime limits, then writes `TERMINAL` with the result and emits
`AgentEvent.BackgroundExecutionSettled` on the manager's event stream. A late callback for a record
that is already terminal, fenced, or deleted is discarded.

### 5.3 Delivery into an active Run

At `TURN_PREPARING`, where steering messages are appended today, the Runner asks the executor for
unacknowledged, unsuppressed terminal results of the session and appends a `COALESCED` notice. This
applies under every consumption policy, because that model invocation happens anyway. Delivery is
idempotent: an entry already present in the Run's messages is not noticed again.

A Run is never extended for a waiting result. If a result settles while the Run produces its final
answer, the Run ends normally and derived state shows a pending activation (5.4). Every automatic
supplement therefore passes host admission and scheduling, activation limits, and the
business-Tool restriction (decision R4).

### 5.4 Continuation Runs

`AgentSessionRuntimeSnapshot` gains:

```kotlin
data class BackgroundSessionSnapshot(
    val executions: List<BackgroundExecutionView>, // handle, toolName, originCallId, state, outcome
    val pendingActivation: PendingActivation?,     // sourceIds, result count, earliest settledAt
    val requiresExecutionHost: Boolean,            // any QUEUED or RUNNING record
)
```

`pendingActivation` is non-null only when the policy is not `PASSIVE`, the session is not `ACTIVE`,
and some terminal result is unacknowledged, unsuppressed, and within its source's activation
allowance.

The host calls `lease.startContinuation(request)` with an ordinary request: no activation, the
authoritative messages, and its current configuration. The manager accepts it only when a pending
activation still exists, the session is not executing, the messages equal the authoritative
messages, and `request.engine.runtime.background` is set. Otherwise it rejects the request and the
results stay passive, for example after the user switched to a model that has not passed the gates.
The manager then appends a `CONTINUATION` notice for the pending records, sets
`activation = RunActivation(BACKGROUND_RESULTS, anchorMessageId = notice.id, sourceIds)`, and starts
the Run, which uses the notice as its activation anchor. Business Tool calls in that Run receive a
paired policy error unless `allowBusinessToolsInContinuations` is true.

`magrathea-chatbot` adds `ChatbotSession.continueWithBackgroundResults()`, which builds the
request through the existing `ChatbotRequestFactory` from the current session configuration.
A new send preempts a continuation like any other Run.

### 5.5 Acknowledgement and activation counts

A terminal result is acknowledged when its latest notice is followed in canonical history by a
replay-included assistant message with stop reason `COMPLETED`, or by a successful
`finish_without_reply` result. Cancelled and failed assistant output is excluded from replay, so it
never acknowledges. A completed user-triggered Run that received the notice therefore consumes it.
A source's automatic activation count is the number of `CONTINUATION` notices in history that name
it. No separate delivery record is written.

### 5.6 Controls

In a background-enabled Run (configuration set, executor attached, session managed), the Runtime
appends four control definitions after the host Tools in the Provider projection, and appends
`BackgroundToolInstructions.SYSTEM_PROMPT_SECTION` to the projected system prompt. The canonical
request is unchanged, and both additions are identical for user-triggered and continuation Runs. The
Runner handles the controls before registry lookup; they need no business permit and do not count
toward business call limits. Hosts therefore add no background prompt text of their own.

| Control | Behavior |
|---|---|
| `read_background_result(handle)` | Returns the stored outcome through the normal result pipeline under the new call's identity, keeping origin, citations, and media. An unknown, cross-Session, or invalidated handle returns an error without content |
| `await_background_results(handles, max_wait_ms)` | Waits as ADR-019 specifies and draws on `explicitWaitMillis`; returns settled payloads like a read, plus statuses for pending executions |
| `cancel_background_executions(handles)` | Cancels only the referenced executions; their `CANCELLED` outcomes never produce notices |
| `finish_without_reply()` | Accepted only in a Run that received a notice. Commits its paired result and completes the Run without another model invocation |

### 5.7 Send preemption

`lease.cancel()` keeps today's meaning. In the Runner's `StopIntent.CANCEL` path, before
`commitTerminalStateWithAbandon`, and in `markPersistedCancelled`, every finalized unanswered call
in the last assistant Tool-call message is closed with exactly one result: its real outcome, a
receipt for admitted eligible work, `cancelled` for started blocking work, or `not_executed` for a
call that never completed D2's checks or never started. The assistant message keeps stop reason
`TOOL_CALLS`, so replay keeps it. `ChatbotSession.send` then appends the new user message
as it does today.

### 5.8 Session Stop, targeted cancellation, and host loss

- `lease.stopAll()` first cancels and suppresses every record of the session in the executor. It
  then cancels the Run, so closure writes real `cancelled` outcomes instead of receipts, and finally
  suppresses unacknowledged terminal results. Suppression is persisted on records, so restore keeps
  the fence.
- `lease.cancelBackground(handles)` cancels only the referenced records.
- `lease.suspendBackground()` handles host loss. Running jobs stop locally; `REPLAY_SAFE` records
  return to `QUEUED`, and the others become `INTERRUPTED`. `lease.resumeBackground()` admits
  `QUEUED` records again under host policy.

### 5.9 Restore, deletion, and regeneration

- `acquire` loads records. A `RUNNING` record without a live job is handled as host loss. Nothing
  executes until the host calls `resumeBackground()` or `startContinuation()`, so restore stays
  attach-only.
- `delete` and `clear` keep the existing fence. The executor then cancels the session's jobs and
  the store deletes its records together with the persisted state. Records without a session are
  deleted at the next acquisition or listing.
- `start` with a request that drops history, including regeneration, cancels, suppresses, and
  deletes every record whose `originCallId` is absent from `request.messages`.
- A `QUEUED` record older than `maxQueuedLifetimeMillis` becomes `INTERRUPTED` without executing.

### 5.10 Crash windows

| Window | Result |
|---|---|
| Record written at handoff, receipt not yet committed | The Run is nonterminal. Recovery matches the journal record to the background record by `executionId`, then waits or hands off without executing twice |
| `RUNNING` written, process dies | Restore applies host-loss rules |
| `TERMINAL` written, no notice yet | Derived state shows a pending activation after restore |
| Notice committed, Run dies before an answer | Not acknowledged. The next completed invocation acknowledges it, or a continuation notices it again |

## 6. Public API impact

All additions follow the existing ABI-preserving constructor and `copy` overloads, and API dumps are
updated. The release is `feat!`: `MessageRole` gains a value, so consumers with an exhaustive `when`
over it must add a branch.

| Module | Change |
|---|---|
| `magrathea-core` | `ToolBackgroundPolicy` and `ToolExecutor.backgroundPolicy`, `BackgroundExecutionConfig` and `RuntimeConfig.background`, receipt markers, `MessageRole.RUNTIME`, `RuntimeNoticePart`, `RunActivation`, wait counters, record types, `BackgroundExecutionStore`, and the `ToolHandedOff` and `BackgroundExecutionSettled` events |
| `magrathea-runtime` | `BackgroundToolExecutor`, Runner and manager constructor parameters, lease commands, `AgentSessionRuntimeSnapshot.background` |
| `magrathea-provider-*` | `RUNTIME` projection in four request builders and replay-policy handling |
| `magrathea-chatbot` | Receipt-aware activity projection, hidden notices and control calls, `continueWithBackgroundResults`, `stopAll`, `cancelBackground` |
| `magrathea-storage-room` | Store implementation and the database version 2 migration |

## 7. Implementation order

Each slice ends with deterministic tests for its scenarios and a local Enjoy build.

| Slice | Content | Exit |
|---|---|---|
| S0 | Offline wire fixtures for receipt, notice, and read on the four builders, plus one live call per intended Provider | O-02 protocol evidence; may revise D4 |
| S1 | In-memory store, executor, handoff, receipts, turn-start delivery, read, host-triggered continuation, minimal Chatbot projection | One end-to-end search supplement in Enjoy |
| S2 | Preemption closure, Stop, targeted cancellation, continuation restriction, await, finish | BG-002, BG-003, BG-005, BG-007, BG-010, BG-011, BG-016 |
| S3 | Room store, schema v9, migrations, restore, host suspend and resume | BG-004, BG-013, BG-014 |
| S4 | Admission priority, remaining intended Providers, limits, evaluation harness | BG-008, BG-009, BG-012, BG-015, O-03, O-04 |

## 8. Decided review points

Decided on 2026-10-24. Implementation evidence, especially from S0, can reopen any of them.

| ID | Question | Decision |
|---|---|---|
| R1 | Late settlements run `afterToolCall` with the origin request but no message history | Accept. Store the origin request without messages and document this in the interceptor contract; keeping full history per Run would duplicate the conversation for every handoff |
| R2 | A new `RUNTIME` role versus a typed part on an existing role | `RUNTIME`. `USER` breaks consumers keyed to the last user message, and `SYSTEM` is hoisted. Chatbot hides the role, so facade consumers see no new enum value |
| R3 | Acknowledgement by a later completed assistant message | Accept. It needs no extra writes and matches replay exclusion; revisit only if evaluation shows duplicate notices |
| R4 | Extend a Run that ends while results wait | No. Turn-start delivery plus host-triggered continuation keeps every automatic supplement behind host admission and scheduling, activation limits, and the business-Tool restriction, at the cost of one Run boundary |
| R5 | A separate store versus extending `AgentPersistence` atomically | Separate optional store. The existing port stays unchanged, adapters without the store stay blocking, and section 5.10 covers the crash windows |

## 9. S0 protocol evidence

Recorded on 2026-10-24 with the `background-pull` scenario of the
[Provider live harness](../tooling/provider-live-harness/README.md). Each run spans two Runs: a
pending receipt and an initial answer, then a notice in the user-input envelope and a real read.
Every passing run made exactly one business call, read the correct handle, never guessed one, and
included the late result in its final answer.

| Wire path | Model | Variants | Result |
|---|---|---|---|
| OpenAI Chat Completions (OpenRouter) | `openai/gpt-4o-mini` | continuation, coalesced, tool-loop | 3/3 pass |
| OpenAI Responses (xAI) | `grok-4.5` | continuation, coalesced, tool-loop | 3/3 pass |
| Anthropic Messages (OpenRouter compatible endpoint) | `anthropic/claude-haiku-5.5` | continuation, coalesced, tool-loop | 3/3 pass |
| Anthropic Messages with adaptive thinking (`reasoning=medium`) | `anthropic/claude-haiku-5.5` | continuation, coalesced, tool-loop | 3/3 pass |
| Gemini Interactions | none | none | Not run: the Gemini API rejects this network location. Offline fixtures cover the request shape until a supported network is available |

The spike sent each notice as a `USER` message, which is its proposed wire form; S1 verifies offline
that a `RUNTIME` message projects to the same request. The Anthropic path used OpenRouter's
compatible Messages endpoint rather than the first-party API. D4 stands.

## 10. Live end-to-end evidence through Enjoy

Recorded on 2026-10-24 with Enjoy's `BackgroundToolsLiveIntegrationTest`, which runs the real Enjoy
runtime stack (request factory, execution coordinator, host gating), real Free Search, and real
models through OpenRouter's Responses endpoint, with an immediate handoff.

| Scenario | `openai/gpt-4o-mini` | `anthropic/claude-haiku-4.5` | `google/gemini-2.5-flash` |
|---|---|---|---|
| 12 s wait allowance, normal search | Pass: awaits, then answers with sources | Pass: awaits, then answers | Pass: answers first, then supplements |
| 2 s wait allowance, search settled 6 s late | Pass: answers first, then supplements | Pass: answers first, then supplements | Pass: answers first, then supplements |
| No wait allowance, search settled 6 s late (stress) | Answers first, then supplements | Polls `read_background_result` until the result settles | Answers first, then supplements |

Fixes made because of these runs:

- A terminal `read_background_result` or `await_background_results` result counts as delivery, so a
  Run that already retrieved a result gets no redundant notice and needs no continuation.
- The Session's background projection is re-derived on every relevant state change; previously a
  result that settled during a Run's final answer never produced a pending activation.
- A single-handle await carries the same origin, citations, and metadata as a read.
- Receipts and not-ready results tell the model to answer with what it has; a repeated read of the
  same unfinished handle, or another await after the allowance is exhausted, is refused.
- With no wait allowance, `await_background_results` and its prompt guidance are not declared.

Recommendation for hosts: keep a non-zero wait allowance. It gives models a bounded, legitimate way
to wait, and every model above then answers first once the allowance runs out. A zero allowance is
a stress configuration in which some models poll; per ADR-019, such model configurations do not pass
the evaluation gate.

### DeepSeek and X Search (2026-10-24)

- **Real Enjoy desktop app**, DeepSeek's direct API (deepseek-chat, served as V4.1 Flash, and
  Deepseek V4 Pro in thinking mode), with DeepSeek Search and Free Search. Exercised:
  - completion within the handoff;
  - handoff followed by await;
  - answer first, then one or two supplements, the second correcting the first;
  - a new user message while results were pending;
  - per-search cancel;
  - Stop cancelling running and queued work;
  - Send preemption, after which the model cancelled the obsolete searches and started a new one;
  - V4 Pro reasoning across multiple sub-requests.
- **OpenRouter harness**: `deepseek/deepseek-v4.1-flash-1017` passed 4/4.
  `deepseek/deepseek-v4-pro` passed 3/4: one run hit a Free Search outage, and on rerun the model
  re-issued the search after an exhausted await instead of answering first.
- **X Search through Enjoy** (real xAI, `deepseek/deepseek-v4.1-flash-1017`):
  - 12 s allowance: the model awaited and answered with 9 citations;
  - zero allowance: it answered first, then supplemented;
  - each X search took about 12 s.
- **Fixes from these runs**:
  - a multi-handle await records per-handle result slices, so Chatbot attaches each search's own
    citations;
  - overlapping awaits are charged once, and the tool batch's shared counters and pending list are
    race-free;
  - an await that uses up the allowance reports exhaustion.

## 11. Implementation status and remaining gates

As of 2026-10-24 the design is implemented on `feat/background-tools` in Magrathea and Enjoy, with
deterministic tests for every slice and the live evidence in sections 9 and 10.

| Slice | Status |
|---|---|
| S0 protocol spike | Done for Chat Completions, Responses, and Anthropic Messages; Gemini Interactions is covered by offline fixtures only |
| S1 core types, projection, executor, handoff, controls, continuation, Chatbot facade | Done |
| S2 preemption closure, Stop, targeted cancellation, host suspend and resume, fences | Done |
| S3 Room persistence (database version 2) and restore after restart | Done |
| S4 admission priority | Deferred by decision: v1 admits background work FIFO; priority ordering waits for contention measurements, because it can only reorder queued work and never preempts a held permit |

Decisions made during implementation, beyond sections 2 and 8:

- A terminal read or await result counts as delivery, alongside a Runtime notice.
- Preemption closure applies only in background-enabled Runs; other cancellation is unchanged.
- Repeated reads of an unfinished handle and awaits after the allowance is exhausted are refused
  within a Run, and await is not declared when the allowance is zero.
- A Run's failure or interrupt exit cancels and releases every admitted-but-not-handed-off
  execution; such an admission owns no record and must not linger or block re-admission.
- A `lateFinalize` failure (store read or interceptor) still settles the record TERMINAL with
  outcome ERROR and a runtime-authored result, and always frees the admission.
- A business-Tool result with `isError` settles the record as ERROR; the stored result stays
  readable through the retrieval controls.
- The four control names are reserved while a request enables background execution: a business
  Tool definition using one is rejected at validation, and Chatbot hides control traffic by a
  runtime-authored result marker rather than by name, so feature-off Tools keep the names free.
- Activity binding uses the call identity (message and call ordinal), not the bare `toolCallId`,
  because Providers may reuse call IDs across turns.

Gates that remain before enabling the feature for users (ADR-019 promotion):

- O-03: replace the placeholder limits with values from a Free Search latency baseline.
- O-04: agree on the evaluation matrix and thresholds; section 10 is initial evidence, not a gate.
- Gemini Interactions live evidence from a supported network location.
- Browser builds stay blocking until `magrathea-storage-web` implements the store.

### Known follow-ups (non-breaking)

- **Record retention.** Terminal records stay for the Session's lifetime with their full stored
  result, and the snapshot and wait paths load every record of a Session. Follow-up: prune
  delivered records and derive terminal state from canonical history.
- **Coalescing duplicates.** An identical pending call (same Tool, same canonical arguments,
  REPLAY_SAFE) could join the existing execution instead of starting a duplicate.
- **Runtime instruction tuning from O-04 observations:** a duplicate search after an exhausted
  await; an await on an already delivered handle; supplements that tell the user about
  continuation Tool limits.

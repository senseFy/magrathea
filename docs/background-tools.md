# Background Tools

## What it is

Session-owned background Tools let an eligible Tool keep executing after its originating Run
would end: the model receives a receipt, the execution completes under the Session's ownership,
and its result reaches the model later through a Runtime notice, a model-initiated read, or a
Run-local await. The behavioral contract is
[ADR-019](adr/ADR-019-session-owned-background-tools.md); the implementation map is the
[Background Tools technical design](background-tools-design.md).

## Enabling it

All four conditions are required. Missing any one keeps Tools blocking, and direct `AgentRunner`
use always stays blocking because there is no managed Session to own the work.

1. `RuntimeConfig.background` carries a `BackgroundExecutionConfig` on the `AgentRequest`.
2. The Tool's executor declares `backgroundPolicy = ToolBackgroundPolicy.ELIGIBLE` (default is
   `BLOCKING`); its `recoveryPolicy` governs crash semantics (see Tool authors below).
3. The Session is managed, and the same `BackgroundToolExecutor` instance is passed to both
   `DefaultAgentRunner(background = …)` and `DefaultAgentSessionManager(background = …)`
   (Chatbot hosts pass it to `createChatbotClient(background = …)`).
4. The executor is constructed with a `BackgroundExecutionStore` —
   `InMemoryBackgroundExecutionStore` or a durable store such as the Room
   `MagratheaRoomStoreHandle.backgroundStore`.

## Configuration reference

`BackgroundExecutionConfig` has no SDK defaults; the host supplies every bound:

| Field | Semantics |
|---|---|
| `handoffWaitMillis` | Cumulative per-Run budget for waiting on eligible calls before they receive a receipt. Within it a call answers synchronously; at expiry it hands off and continues in the background |
| `explicitWaitAllowanceMillis` | Cumulative per-Run budget for the model's `await_background_results` control. Overlapping awaits count once; `0` means the control is not declared at all |
| `maxAutomaticActivationsPerSource` | Per-source bound on automatic continuation activations across Runs |
| `maxQueuedLifetimeMillis` | Maximum lifetime of an unstarted queued execution before it settles as interrupted |
| `consumptionPolicy` | How stored results may reach the model: `LATEST_CONTEXT` (supplement Runs see the latest conversation), `ORIGIN_DIRECTED` (anchored to the originating context), or `PASSIVE` (no automatic activation) |
| `allowBusinessToolsInContinuations` | Whether continuation Runs may call business Tools; `false` by default, so continuations can only read and answer |

## Tool authors

- Declare `ELIGIBLE` only when the call can run after its Run ends: the executor must depend
  only on its `ToolExecutionRequest` and injected services, and its result must be a faithful
  `ToolExecutionResult` (content, citations, origin) because it becomes the stored result.
- `ToolRecoveryPolicy.REPLAY_SAFE` means an execution lost to process exit or host loss is
  requeued and re-executed, but only after the host calls `resumeBackground()`. `FAIL_CLOSED`
  means the record settles as interrupted instead.
- `afterToolCall` interceptors run on late settlements too; they receive the original
  `ToolRuntimeContext` request without the Run's later message history.

## Model-facing protocol

- The original call's result is either the real result or a receipt carrying a non-guessable
  `ToolBackgroundReceipt.handle`.
- When the execution settles, a Runtime notice appears at a model-input boundary (or opens a
  continuation Run). It carries structured facts only — never external payload.
- The controls `read_background_result`, `cancel_background_executions`, and
  `finish_without_reply` are declared, plus `await_background_results` when the explicit-wait
  allowance is non-zero.
- Runtime-owned instruction text is appended automatically when the feature is enabled; hosts
  add no prompt text.
- Waiting allowances and repeated-poll checks belong to one Run: a new Run resets them, while
  resuming an interrupted Run retains them.
- The four names are reserved while a request enables background execution: declaring a business
  Tool under one fails request validation. A stored `isError` result settles the record as failed
  and keeps its error code on retrieval.
- The Chatbot hides control traffic by a runtime-authored result marker
  (`magrathea.background.control`), never by name, so a feature-off Session may freely use Tools
  with the same names.

## Host responsibilities

- Schedule a continuation when the lease snapshot's `background?.pendingActivation` is
  non-null: `AgentSessionLease.startContinuation(request)` builds the Run from the Session's
  authoritative messages. Chatbot hosts call
  `ChatbotSession.continueWithBackgroundResults()`, which does the same check internally.
- `stopAll()` cancels every live background execution and suppresses unacknowledged records;
  `cancelBackground(handles)` targets specific executions, the host form of the model's cancel
  control.
- `suspendBackground()` stops live jobs on host loss (REPLAY_SAFE records return to QUEUED,
  others settle as interrupted); `resumeBackground()` re-admits them.
- A new Send preempts the Run: surviving background work keeps running, each surviving call
  closes with exactly one result, and any settlement lands in the next Run's notice flow.

## Chatbot projection

`ChatbotSnapshot` projects background work without exposing control calls:

- Each Tool activity carries an optional `background` (`ChatbotBackgroundExecution` with the
  handle, Tool name, origin call, and a `ChatbotBackgroundState` of queued, running, succeeded,
  failed, cancelled, or interrupted).
- Runtime notices and the four control calls never appear as activities or messages.
- When the model reads or awaits a result, its content, citations, and origin attach to the
  originating search activity — a multi-handle await attributes each result to its own call.
- `ChatbotBackgroundSnapshot` exposes `executions`, `hasPendingActivation`, and
  `requiresExecutionHost` for scheduling and keep-alive decisions.

## Events and persistence

- `AgentEvent.ToolHandedOff` fires when a call receives its receipt;
  `AgentEvent.BackgroundExecutionSettled` fires when a background execution reaches a terminal
  state.
- Stores: `InMemoryBackgroundExecutionStore` for tests and non-durable hosts, the Room store
  (`MagratheaRoomStoreHandle.backgroundStore`, database version 2) for durable hosts, and no
  background store in `magrathea-storage-web` — browser sessions stay blocking.
- Records are persisted under storage schema 9, a one-way migration: earlier SDK versions
  cannot read a Session or background record written under it.

## Limitations

See [Known issues](known-issues.md) for the current gaps, including record retention, FIFO
admission, the browser store, and the missing Gemini Interactions live evidence.

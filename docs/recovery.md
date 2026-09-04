# Interruption and Recovery

Magrathea distinguishes a user ending a run from a host losing execution time.

| Operation | Meaning | Result |
|---|---|---|
| `cancel` | The user ends the run | Terminal `CANCELLED`; observed partial output may remain |
| `interrupt` | The host pauses recoverable work | `INTERRUPTED`; state returns to the last durable checkpoint |
| `resume` | Continue the same logical run | Same `AgentRunId` and exact checkpoint phase |

Provider stream output is provisional until a stable checkpoint is committed. Network failures,
timeouts, rate limits, server failures, and streams that end before semantic completion produce a
typed Provider interruption. `ProviderInterruption` records the stable failure code, whether the
failure occurred before or after the first canonical event, and an optional absolute retry time.

Runtime retries only before the first canonical event and only through the configured
`RetryPolicy`. After output begins it never starts a fresh attempt inside the same invocation;
instead it preserves the provisional state for presentation and commits the replay-safe checkpoint.
The application may then resume that checkpoint according to its own foreground and retry policy.
`maxProviderRetries = N` permits at most `N` retries after the initial request. `RetryPolicy`
receives a one-based retry ordinal, and Runtime treats the Provider's `Retry-After` value as a
minimum delay. That ordinal resets for each Provider invocation; `AgentStateSnapshot.retryCount`
separately accumulates transient pre-output retries actually started by `RetryPolicy` across the
logical Agent run.

A canonical `Completed` event is the semantic terminal signal. A later transport disconnect does
not invalidate the completed response, while any later canonical event is a protocol violation.

On resume, the partial assistant message is not treated as authoritative history. Direct adapters
start a new Provider attempt. An adapter backed by a durable remote stream may declare
`ProviderInvocationResumeMode.REATTACH`, causing Runtime to reuse the invocation identity; the
Gateway uses this mode for idempotent stream reattachment.

Before either a model call or context-summary call begins, Runtime persists its request ID,
purpose, input identity, and next physical-attempt ordinal. Reattachment therefore uses the exact
pending request only while its input is unchanged; a changed or invalidated request claims and
persists a new physical identity first.

A transport disconnect keeps that identity. An explicit remote invalidation advances the physical
attempt identity: if it is known before replay, the configured retry policy may continue in the
same `resume`; if it arrives after replay has begun, Runtime checkpoints the transition and returns
`INTERRUPTED` before the next `resume`. Observed usage from the invalidated attempt is retained,
while successful replay replaces the rolled-back attempt accounting rather than adding it twice.

## Host lifecycle

Keep lifecycle policy in the application:

- Let an active request continue while the platform still grants execution time.
- Closing a `ChatbotSession` releases only its lease. It does not interrupt or cancel execution.
- Call `ChatbotSession.interrupt()` when the application runtime root must relinquish execution.
- A successful managed `interrupt` or `cancel` returns after its collector is settled; the same
  session may be resumed or started again immediately without a scheduling delay. A terminal event
  that linearizes first remains authoritative.
- An owning `ChatbotClient.close()` interrupts manager-owned live sessions before closing supplied
  resources. A client created from an existing `AgentSessionManager` closes only its own facades.
- After restart, call `history()`. Use `restoreSession(sessionId)` to open canonical state without
  starting work, then apply application lifecycle policy before calling `session.resume()`.
- Repeated restore calls return independent Chatbot facades backed by leases to the same canonical
  runtime. Closing one facade does not invalidate another.
- `restoreSession` attaches to active work already owned by the same manager. It fails `BUSY` for an
  active raw runner outside that manager, and `NOT_FOUND` when no stable persisted/recoverable
  record exists.
- Use `resumeSession(sessionId)` only when the host intentionally wants restore and execution to be
  one operation. It fails `BUSY` when that canonical runtime is already active.
- Resume a still-open interrupted session with `session.resume()`.
- Use the interruption phase and retry hint to choose an application-level automatic-resume policy.
- `lease.inspectRecovery()` reports the disposition and latest authoritative state without
  starting work.
- Use `cancel()` only for an explicit user stop; it also terminally abandons a persisted
  interruption.

A terminal cancellation or failure that discards a pending Provider invocation atomically removes
the recovery checkpoint before making a bounded, best-effort `ProviderAdapter.abandon` call for the
captured invocation. Runtime-owned Provider collection only detaches locally; a failed terminal
commit leaves the invocation resumable and is never followed by remote abandonment. The Gateway
adapter implements abandonment as an idempotent, authenticated request-ID cancellation.

The process may disappear without a lifecycle callback. Atomic persistence leaves either the
previous checkpoint or the next complete checkpoint, and the next runtime recognizes the saved
running state as orphaned.

`ACTIVE` is manager/runner-instance ownership, not a cross-process attachment protocol. Hosts that
handoff execution in one process share the same `AgentSessionManager` and transfer their
application-owned host capability. They do not compose a second runner over the same persistence.

```kotlin
// Called when the host must release active work.
session.interrupt()

// Called after the application recreates its composition root.
client.history()
    .filter { it.status == ChatbotStatus.INTERRUPTED }
    .map { client.restoreSession(it.sessionId) }
    .forEach { session ->
        if (applicationExecutionHostAvailable()) session.resume()
    }
```

## Tool recovery

Tool calls carry a durable execution journal:

| State | Resume behavior |
|---|---|
| `PENDING` | Execute |
| `COMPLETED` | Reuse the stored result |
| `STARTED` | Block because the external outcome is unknown |

A read-only executor, or one that deduplicates the stable `ToolExecutionRequest.executionId`, may
opt into replay:

```kotlin
class ReadOnlyTool : ToolExecutor {
    override val recoveryPolicy = ToolRecoveryPolicy.REPLAY_SAFE
    // ...
}
```

The built-in Web Search, Image Search, and X Search tools default to `REPLAY_SAFE`. Runtime reuses
their durable completed results after interruption instead of executing the same search again.
Other tools default to `FAIL_CLOSED`.

## Persistence

`DefaultAgentRunner` and its `DefaultAgentSessionManager` must use the same `AgentPersistence`.
Its `commit` operation stores the session snapshot and checkpoint atomically; a terminal commit
atomically removes the checkpoint.

Use `InMemoryAgentPersistence` for ephemeral runs, Room on Android/JVM/iOS, and IndexedDB in the
browser.

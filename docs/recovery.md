# Interruption and Recovery

Magrathea distinguishes a user ending a run from a host losing execution time.

| Operation | Meaning | Result |
|---|---|---|
| `cancel` | The user ends the run | Terminal `CANCELLED`; observed partial output may remain |
| `interrupt` | The host pauses recoverable work | `INTERRUPTED`; state returns to the last durable checkpoint |
| `resume` | Continue the same logical run | Same `AgentRunId` and exact checkpoint phase |

Provider stream output is provisional until a stable checkpoint is committed. If a Provider
network failure, Provider timeout, lifecycle interruption, or process loss occurs mid-stream,
resume does not treat the partial assistant message as authoritative history. Direct adapters start
a new Provider attempt. An adapter backed by a durable remote stream may declare
`ProviderInvocationResumeMode.REATTACH`, causing Runtime to reuse the invocation identity; the
Gateway uses this mode for idempotent stream reattachment.

## Host lifecycle

Keep lifecycle policy in the application:

- Let an active request continue while the platform still grants execution time.
- Call `ChatbotSession.interrupt()` before intentionally releasing a live session or closing its
  execution owner.
- `ChatbotClient.close()` interrupts its live sessions before closing owned resources.
- After restart, call `history()`. A persisted run without a live owner appears as `INTERRUPTED`;
  resume it with `resumeSession(sessionId)`.
- Resume a still-open interrupted session with `session.resume()`.
- `inspectRecovery(sessionId)` reports the disposition and latest authoritative state without
  starting work.
- Use `cancel()` only for an explicit user stop; it also terminally abandons a persisted
  interruption.

The process may disappear without a lifecycle callback. Atomic persistence leaves either the
previous checkpoint or the next complete checkpoint, and the next runtime recognizes the saved
running state as orphaned.

```kotlin
// Called when the host must release active work.
session.interrupt()

// Called after the application recreates its composition root.
client.history()
    .filter { it.status == ChatbotStatus.INTERRUPTED }
    .forEach { client.resumeSession(it.sessionId) }
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

The built-in Web Search and X Search tools are replay-safe. Other tools fail closed by default.

## Persistence

`DefaultAgentRunner` and `ChatbotClient` must share one `AgentPersistence`. Its `commit` operation
stores the session snapshot and checkpoint atomically; a terminal commit atomically removes the
checkpoint.

Use `InMemoryAgentPersistence` for ephemeral runs, Room on Android/JVM/iOS, and IndexedDB in the
browser.

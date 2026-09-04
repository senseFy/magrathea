# Managed Agent sessions

`AgentSessionManager` is the process-local ownership boundary for live Agent execution. One
application runtime root owns one manager. Calls that address the same `AgentSessionId` attach to
one canonical in-memory runtime.

## Contracts

| Type | Responsibility |
|---|---|
| `AgentRunner` | Execute one cold run Flow and implement control/recovery primitives |
| `AgentSessionManager` | Canonicalize live sessions, serialize per-session commands, fence destructive mutations, and own collector jobs |
| `AgentSessionLease` | Attach one consumer to a canonical runtime and expose its full `StateFlow` projection |
| Application host | Choose retention, foreground/background execution, retry, credentials, and platform lifecycle policy |

`create` is strict and fails when persisted or recoverable state already exists. `acquire` is
restore-only and never starts Provider work. Concurrent acquisition of one ID coalesces; operations
for different IDs remain independent.

Each acquired lease is independently releasable. `release` only detaches that lease. It does not
cancel or interrupt a run. An active runtime remains owned by the manager after its final lease is
released and can be attached again. An unleased stable runtime is removed from memory and can be
restored from persistence later. Release revokes that lease's command capability and retention; it
does not invalidate `StateFlow` or `SharedFlow` references that a caller already captured.

## State and commands

`AgentSessionLease.state` is the replay-one, late-attach-safe source of truth. Its request, run ID,
Agent state, phase, recovery metadata, and last event form one revisioned projection. `events` is a
best-effort edge stream for diagnostics or transient presentation; it is non-replay and may drop
events rather than block execution.

Commands are serialized per session:

- `start` admits a fresh run only from new, inactive, or terminal state;
- `resume` admits work only from resumable state;
- successful `interrupt` and `cancel` return only after the manager-owned collector has settled;
- resumable or recovery-blocked state must be resumed or cancelled before a new run;
- `replaceIdleRequest` preserves canonical messages and cannot replace recoverable state;
- `delete` and `clear` first revoke old canonical lifetimes, then attempt persisted-state removal;
- manager `close` rejects new admission immediately, interrupts live execution, and joins owned
  collectors.

Destructive invalidation is not rolled back. If shutdown or persistence removal fails after the
fence is committed, old leases stay deleted and the in-memory runtime is removed. Persistence may
still contain a record that a later `acquire` restores into a new generation. The resulting
`AgentSessionException.invalidationScope` is `SESSION` for `delete` and `ALL_SESSIONS` for `clear`,
so hosts do not infer lifetime state from `STORAGE` or `INVALID_STATE` alone.

The manager owns its `SupervisorJob`. Caller cancellation cannot transfer ownership of an admitted
run or strand an opening/deleting gate. A lease released from a cancelled caller still detaches.

Collection admission is synchronous at the `AgentRunner` boundary. Once `start` or `resume`
returns, `interrupt` and `cancel` can address that run even when no business event has been emitted
and the host dispatcher has not advanced. Runner decorators must preserve this property; a
cross-context or buffered decorator starts upstream collection undispatched before yielding its
downstream event bridge. A successful stop command drains that bridge before releasing the
per-session command boundary, so an immediate resume or fresh start cannot observe stale `BUSY`.

Runner and persistence adapters are non-reentrant with respect to their owning manager. An adapter
must return before requesting manager lifecycle or catalog work; reverse actions are scheduled on
an independent host scope. Synchronously awaiting `acquire`, `delete`, `clear`, `close`, or
`listSessions` from a manager-invoked adapter would wait on the operation fence that owns the
adapter call. State/event observers run in independent coroutines and do not have this restriction.

## Recovery

Acquisition reads a complete persistence record, inspects runner recovery, and verifies the record
again before installing a runtime. `ACTIVE` outside the manager fails as busy. Recovery remains an
explicit mechanism: the manager does not auto-resume, retry, or choose credentials.

An admitted acquisition remains part of the runtime's operation fence through cancellation-safe
lease delivery. A delete, clear, or manager close that linearizes afterward waits for that handoff;
a caller that cannot receive the lease releases its attachment.

Cancellation resolves only after the active collector settles. A completed or failed state for the
same run remains authoritative when it reached the manager or persistence before cancellation won;
the manager does not replace that terminal result with `CANCELLED`.

## Chatbot facade

`magrathea-chatbot` projects managed Agent state into `ChatbotSnapshot`; it does not collect
`AgentRunner.run` itself. Multiple Chatbot facades may attach to the same canonical Agent runtime.
Closing one facade releases only its lease.

`createChatbotClient(runner, ...)` creates an owning manager root. Closing that client interrupts
its managed live sessions and then closes supplied resources. `createChatbotClient(manager, ...)`
borrows an existing root; closing it releases only its own facades.

Client and facade close operations publish one shared completion. Concurrent or later callers wait
for the same cleanup and observe the same failure. An admitted create, restore, or resume remains
pinned until its cancellation-safe facade delivery has either completed or transferred cleanup;
client close cannot overtake that admitted delivery.

An admitted `deleteSession` or `clearHistory` owns manager mutation and facade cleanup as one
cancellation-safe operation. When the manager reports committed invalidation despite a storage
failure, the client closes the affected registered facades before returning that failure.
`ChatbotException.invalidationScope` reports `SESSION` or `ALL_SESSIONS`; it does not imply that the
persisted record was removed.

Create, restore, and resume handoffs are fenced from before lease acquisition through facade
delivery or failed-handoff cleanup. A session delete waits only for preceding handoffs of that
session. A history clear waits for every preceding handoff and delete, and prevents new handoffs
until manager clearing and facade cleanup finish. Handoffs for unrelated sessions remain
independent of a session delete. Facade delivery is the final cancellation-aware ownership
transfer: all potentially suspending client bookkeeping precedes it, while invalidation and close
gates are published only after the continuation is resumed.

The manager intentionally has no Android `Activity`, `Service`, iOS task, desktop window, or browser
visibility dependency. A product may move its own host capability between those platform owners
without replacing the canonical Agent runtime.

# ADR-017: Managed Session Ownership

- Status: Accepted
- Date: 2026-09-02

## Decision

Magrathea Runtime provides a process-local `AgentSessionManager` above `AgentRunner`.

- One manager canonicalizes each live `AgentSessionId` and owns its collector job.
- Consumers receive independently releasable leases. Lease release is detach-only and never a
  stop signal.
- A revisioned `StateFlow` is the late-attach-safe source of truth. Edge events are non-replay,
  best-effort, and cannot backpressure execution.
- Commands serialize per session; different session IDs do not share an execution lock.
- Restore is attach-only. Starting, resuming, cancelling, interrupting, and replacing an idle
  request remain explicit commands with state-dependent admission.
- Delete and clear commit canonical invalidation before awaiting admitted work, shutdown, and
  persistence removal. That invalidation is not rolled back by a later failure and is reported as
  typed exception metadata.
- Manager close fences new commands before awaiting admitted work and cleanup.
- Manager close interrupts active work. It does not define Android, iOS, desktop, or browser host
  policy.
- Runner collection admission is synchronous: `start` and `resume` return only after the run is
  addressable by `interrupt` and `cancel`. Runner decorators preserve that admission boundary.
- Lease acquisition remains fenced through cancellation-safe delivery; destructive operations
  admitted afterward wait for the handoff, and rejected delivery releases the attachment.
- Successful interrupt and cancel commands join the manager-owned collector before returning, so
  the next serialized command observes a stable runtime rather than transient collector teardown.
- A completed or failed result for the same run wins over cancellation when its terminal state was
  already observed or persisted before cancellation settled.
- Internally, execution ownership, result knowledge, and lifecycle fences are separate facts in
  one state model. Public phases and command admission derive from those facts; revisions are
  observation versions, not execution authority.
- Result knowledge includes an explicit unknown state. Public snapshots are one-way projections;
  pure transitions own state decisions and cancellation plans, while the manager owns their I/O.
- Semantic result versions fence asynchronous observations independently of diagnostic events and
  object allocation. Domain failure data, not the last displayed event, determines failure outcomes.
- Execution settlement always releases its owner. An unavailable recovery read is not absence:
  an unconfirmed result blocks new work until inspection or explicit cancellation resolves it.
- Recovery observations carry generation/result provenance. A delayed `ACTIVE` observation cannot
  resurrect an owner, and a historical terminal result cannot win a current execution's control race.
- Runner and persistence adapters do not synchronously re-enter their owning manager. Reverse
  lifecycle actions are scheduled after the adapter call returns.

`magrathea-chatbot` depends on this runtime boundary. A Chatbot controller projects a managed
session and does not own `AgentRunner.run`. An owning Chatbot client owns its manager; a borrowed
client releases only its own facades. Destructive Chatbot operations close facades covered by a
committed manager invalidation even when persistence removal fails.

## Application boundary

Applications own selection, retention, foreground/background host capability, automatic recovery,
credential availability, retry scheduling, and platform component handoff. Those policies address
sessions by ID and may move between UI and service owners without replacing the manager.

The manager does not provide cross-process coordination, distributed fencing, remote attachment,
shared/exclusive host roles, or an event log.

## Consequences

UI recreation and navigation cannot restart manager-owned execution. Multiple product facades can
observe one canonical runtime without sharing mutable selection state. Background execution can be
added as an application host policy instead of a second Agent ownership system.

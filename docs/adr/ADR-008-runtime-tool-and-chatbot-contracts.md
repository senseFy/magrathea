# ADR-008: Runtime, Tool, Policy, and Chatbot Contracts

- Status: Accepted
- Date: 2026-07-11

## Runtime

- Interceptor changes to model configuration, tools, and state are authoritative, but an interceptor
  cannot change session identity or provide conflicting message histories.
- `onModelChunk` observes each assembled chunk. `afterModelCall` runs once after a Provider flow
  completes successfully.
- An empty Provider flow is a protocol failure. A valid usage-only terminal flow may complete.
- Follow-up messages drive another turn and remain bounded by `RuntimeConfig`.
- Retry count, semantic context budget, inline attachments, tool results, turns, and downstream
  buffering have hard limits. Complete history stays authoritative; the bounded Provider projection
  follows ADR-014. Cancellation is never converted into retry or a generic failure.
- `cancel` is terminal and may preserve output already shown to the caller. `interrupt` is
  recoverable and restores the last durable checkpoint, so provisional Provider output is not
  replayed as authoritative history.
- A resumed Provider call keeps the logical run identity. Direct adapters advance the physical
  attempt identity; adapters with durable remote replay may explicitly reattach with the previous
  invocation identity. Persisted running state without a live owner is recoverable as an orphaned
  run.

## Tools and policy

- A tool executes only when it is advertised, registered, finalized, identity-consistent, and
  authorized.
- A tool interceptor may change arguments but not session, call ID, or tool name.
- Ordinary executor failures and timeouts become typed error results; external cancellation
  propagates unchanged.
- Parallel execution preserves request order in public Tool events.
- A Tool may declare a positive per-turn call limit. Runtime counts calls by Tool name in model
  order, applies the stricter registered/request limit, and returns an error result without executing
  excess calls.
- A Tool may independently declare a positive per-run call limit. Runtime persists those counters
  across model turns, injected steering/follow-up messages, checkpoints, and resume; only a new
  `AgentRunner.run` request starts a fresh logical-run budget.
- Unknown tools and permissions fail closed. An installed approval gateway is consulted for every
  tool call, and `ASK_ONCE_PER_SESSION` grants are scoped by session, tool, and policy version.
- Tool execution uses a durable `PENDING` / `STARTED` / `COMPLETED` journal. Completed results are
  reused, pending calls may start, and a started call blocks recovery unless its executor declares
  `REPLAY_SAFE`.
- Portable Web Search is an ordinary client-executed Tool with an injected backend. Search policy
  is structured and non-secret; source content is bounded, HTTPS-only, untrusted, and accompanied
  by canonical citation metadata. Provider-hosted search requires a separate protocol contract.

## Chatbot facade

- Chatbot state is reduced from `AgentEvent` values and stops consuming upstream events after the
  first terminal event.
- Public Chatbot DTOs preserve text phases, redacted reasoning markers, citations, attachments,
  tool calls/results, timestamps, normalized stop reasons, and usage without exposing raw
  `AgentMessage` values or Provider metadata.
- Every Chatbot session owns an explicit Provider profile/model configuration. The optional
  `CredentialRef` is non-secret and must match the model Provider. It drives request creation,
  appears in snapshots and history, and is persisted and restored with the authoritative request.
  Configuration changes are accepted between runs and fail with `BUSY` during active generation.
- Send, regenerate, cancel, interrupt, resume, history, delete, clear, and close use stable session
  identity and deterministic resource ownership. Closing a live session interrupts rather than
  terminally cancelling it.
- In-memory stores synchronize concurrent access; registries are immutable after construction and
  reject duplicate keys.

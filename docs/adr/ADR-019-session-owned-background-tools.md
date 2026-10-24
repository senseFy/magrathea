# ADR-019: Session-Owned Background Tools and Model-Directed Continuation

- Status: Accepted
- Date: 2026-10-23 (accepted 2026-10-24)
- Implementation: SDK v1 implemented for the first release after 0.1.0-alpha.18. Host enablement
  remains gated by O-03, O-04, and Gemini live evidence (technical design, section 11)
- Technical design: [Background Tools: Technical Design](../background-tools-design.md)
- Extends: [ADR-005](ADR-005-persistence-contracts.md),
  [ADR-008](ADR-008-runtime-tool-and-chatbot-contracts.md),
  [ADR-017](ADR-017-managed-session-ownership.md), and
  [ADR-018](ADR-018-tool-result-context-lifecycle.md)

## Scope and settled principles

These architectural boundaries define the delivery mechanism implemented in SDK v1. Provider
validation evidence and the remaining host-enablement gates are recorded in the technical design:

- The model judges answer sufficiency, relevance, user-request satisfaction, and obsolete work.
  Runtime does not infer those judgments from prose or add a business Task/Goal completion layer.
- A Run is a bounded Agent execution, potentially with several model/Tool rounds. A Session owns
  background Tool executions beyond their originating Run. At most one main model execution is
  active per Session. A terminal Run is never reopened; a subsequent activation gets a new Run.
- Source identities support accounting, cancellation, and replay. They are execution facts, not
  semantic user-task completion states. Interrupted nonterminal Runs retain ordinary recovery.
- Hard limits, permission checks, explicit Stop, and host admission constrain every activation.
- The same background execution mechanism supports latest-context judgment, origin-directed
  follow-up, and passive availability. Enjoy selects the first as its default.

An earlier draft called the design Accepted while deferring result projection, so the contract
stayed Proposed until its scope and protocol gates closed. O-01 was decided on 2026-10-23: v1
replaces the earlier durable-wait design with a Run-local await whose limit is independent of the
foreground handoff allowance. O-02 closed with the S0 protocol evidence and the S1 implementation,
and the contract was accepted on 2026-10-24. O-03 and O-04 now gate enabling the feature in a
host, not the SDK contract.

## Baseline before background Tools

Before this feature, `DefaultAgentRunner` waited for the full Tool batch. Execution timeout
cancelled the executor and returned a Tool error without retaining a result channel.
`FollowUpMessageProvider` operated only inside a live Run. The managed Tool journal belonged to
the current Run/checkpoint, and `ChatbotSession.send` stopped pending execution before admitting a
new request. This remains the baseline for blocking consumers.

`DefaultReplayPolicy` repairs a missing result with `No result provided` and `isError = true`.
It also excludes cancelled/failed assistant messages. These are important constraints on foreground
preemption and delivery accounting, not just presentation details. The background path must close real
Tool calls before new input and cannot assume that sending a result to a cancelled invocation made
it durable model context.

## v1 result protocol: receipt, notice, pull

A background execution has a Session-scoped, non-guessable opaque handle such as `bg_7f3kq9`.
Handles are never sequential, so text injected into Tool content cannot address other executions by
guessing. The original, actual model Tool call receives exactly one ordinary Tool result: either
its final result, or a receipt with the handle and runtime state (`queued` or `running`). A receipt
acknowledges execution ownership, not success, failure, or result evidence. Foreground wait expiry
and Send preemption both use this same handoff path.

When execution settles, Runtime stores the result and can append a typed **Runtime notice** at a
model-input boundary. Its rendered text comes from a fixed template and runtime-owned values:
opaque handles, the registered Tool name, enumerated terminal states, and the activation mode. The
handle relates a notice to its earlier call, because that call's receipt carries the same handle.
The originating call ID and result version stay structured runtime data and are never rendered,
since call IDs can be renormalized after a model switch. A notice includes no external result text,
titles, URLs, citations, or raw error messages, and no model-authored arguments such as queries,
because arguments can carry text copied from earlier external results. A typical notice says that a
`web_search` call has a result available under its handle, names the allowed retrieval control,
and states the current execution restriction. It is not itself evidence for an answer.

The model retrieves the payload through an actual call to `read_background_result(id)`, or through
an explicit await that returns settled outcomes. The returned content uses that new call's identity
and the normal Tool-result pipeline, preserving original execution/source provenance, citations,
media references, audiences, and ADR-018 budgets.
Retrieval never repeats the business operation; reading a retained result again is allowed and
bounded. An unknown, cross-Session, or invalidated handle cannot disclose content.

Canonical notices carry explicit Runtime authorship. They are not stored as user-authored messages
or synthesized assistant Tool calls. Their projection uses a chronological user-input envelope for
protocols without a Runtime role; only trusted runtime metadata enters that envelope. External
payloads appear exclusively in real Tool-result blocks. Notices never use `MessageRole.SYSTEM`,
which current Anthropic/Gemini adapters hoist into their top-level instructions.

The reference request builders implement this projection; offline wire fixtures cover all four
of them (technical design, section 9).

### Provider wire fragments

These are illustrative text-only request fragments in the adapter wire formats, not complete
runnable requests. `R` is the serialized pending receipt,
`N` the fixed Runtime notice, and `D` the bounded external payload. `c1` and `r1` are IDs from
actual model calls to the business Tool and retrieval control respectively. Every historical
assistant output is replayed from its authoritative response, including required reasoning,
signatures, encrypted content, or Gemini interaction steps; none is manufactured.

| Provider | Receipt answering `c1` | Runtime notice after the initial answer | Result answering real retrieval call `r1` |
|---|---|---|---|
| Anthropic Messages | `{"role":"user","content":[{"type":"tool_result","tool_use_id":"c1","content":"R"}]}` | `{"role":"user","content":[{"type":"text","text":"N"}]}` | `{"role":"user","content":[{"type":"tool_result","tool_use_id":"r1","content":"D"}]}` |
| OpenAI Responses | `{"type":"function_call_output","call_id":"c1","output":"R"}` | `{"role":"user","content":[{"type":"input_text","text":"N"}]}` | `{"type":"function_call_output","call_id":"r1","output":"D"}` |
| OpenAI Chat Completions | `{"role":"tool","tool_call_id":"c1","content":"R"}` | `{"role":"user","content":"N"}` | `{"role":"tool","tool_call_id":"r1","content":"D"}` |
| Gemini Interactions | `{"type":"function_result","call_id":"c1","name":"web_search","is_error":false,"result":[{"type":"text","text":"R"}]}` | `{"type":"user_input","content":[{"type":"text","text":"N"}]}` | `{"type":"function_result","call_id":"r1","name":"read_background_result","is_error":false,"result":[{"type":"text","text":"D"}]}` |

For Anthropic, each real `tool_use` is immediately followed by its result; a notice is placed only
after outstanding calls have been closed. Gemini replay must retain actual interaction steps,
not just a fabricated assistant role. OpenAI-family reasoning continuity remains provider/model
specific. Multiple reads can be generated in one model turn and share a result round trip.

### Three chronological examples

The wire fragments above substitute for `receipt`, `notice`, and `read result` in each example.

| Step | A: normal late completion | B: Send while waiting | C: cancelled supplement |
|---|---|---|---|
| 1 | Model emits real `web_search(c1)` | Model emits real `web_search(c1)`; work is queued/running | Initial call already has its receipt; result is stored |
| 2 | Foreground budget expires; commit receipt for `c1` | Send transaction commits receipt for `c1` before the new user message | Project notice; model emits real read call `r1` |
| 3 | Model emits initial answer; Run ends | Next invocation sees preserved call, receipt, then new user message | Read returns stored content; model begins a supplement |
| 4 | Completion produces a metadata-only notice | Result arrives; notice is coalesced into a later input or activates a new Run | New user input cancels the supplement before a replay-safe outcome |
| 5 | Model emits actual read call `r1`; Runtime returns its result | Model may read with a new actual call or decline to use the result | Retain result and unacknowledged activation; preserve any replay-safe read pair |
| 6 | Next model invocation supplements, corrects, or finishes silently | Latest instructions govern the answer; no orphan-result error or fabricated old-call result | New Run can reuse valid context or genuinely read again; cancellation cannot consume the only delivery opportunity |

The partial cancelled answer in C remains visible as cancelled output where product policy permits,
but is not silently promoted into valid model history. Ordinary replay exclusion still applies.
The UI and evaluation must account for that visible-history/model-context difference.

## Execution and delivery state

Execution and delivery are separate axes; notification/retrieval are not new executor terminal states.

```text
QUEUED --admit and persist start--> RUNNING --settle--> TERMINAL
   |                                                  outcome:
   +--cancel before start--------------------------->   success / error / timeout /
                                                       cancelled / interrupted

Stored terminal result
  -> notice projected (optional in passive mode)
  -> model read or await in flight
  -> retrieval present in replay-safe history
  -> activation acknowledged by a replay-safe normal or explicit-silent outcome
```

Notice and read steps are independently recorded per result version, invocation, and Session
lifetime. A result may be read before a notice, or re-read without another business execution.
A projected notice is not a fetched payload; a fetched payload is not automatically a completed
activation. Failed or cancelled attempts do not irreversibly advance acknowledgement. If a read
pair survives replay, reuse it; if it was excluded, do not claim the model will still see its data.
A successful explicit-silent outcome may acknowledge an activation without reading every result;
Runtime does not judge the usefulness of that choice.

`QUEUED` means the executor has not started and owns no execution permit. Its durable admission can
be re-enqueued without replaying external effects. `RUNNING` has acquired its permit and committed
start evidence; after process loss its outcome is unknown unless an adapter can reattach or safely
replay. Never conflate the two when recovering, prioritizing, or cancelling.

## Model controls and waiting

v1 exposes a stable control capability set while background support is enabled:

| Control | Semantics |
|---|---|
| `read_background_result(handle)` | Fetch a retained outcome under a new genuine model call; no business re-execution |
| `await_background_results(handles, max_wait_ms)` | Wait inside the current Run until the referenced executions settle or the wait limit is reached; return settled outcomes through the read pipeline and runtime statuses for executions still pending |
| `cancel_background_executions(handles)` | Stop only the referenced executions and return runtime-owned outcomes |
| `finish_without_reply()` | Explicitly end this activation without inventing assistant prose |

These are the implemented v1 controls; `await_background_results` is declared only when the
explicit-wait allowance is non-zero. The contractual distinctions are between control operations
and business Tools, between waiting and cancellation, and between returning a stored outcome and
executing business work again. v1 await returns settled payloads through the read pipeline, saving
a model invocation on the explicit-wait path; an await that returns a payload advances retrieval
state exactly as a read does. When background is enabled for a request, the four control names are
reserved and a request whose business Tools use one is rejected. Controls remain usable when new
business Tools are prohibited. They must not need the business executor's scarce permit or provide
a bypass for launching prohibited operations.

The foreground Tool-wait allowance decides only when Runtime returns control to the model. An
explicit await is the model's decision after that handoff and does not draw from that allowance:
within one Run the model sees receipts only after the allowance is exhausted, so a shared
allowance would make every await after a receipt return immediately. Await ends when every
referenced execution is terminal, or at the earliest of the requested duration, remaining Run time,
applicable source-associated deadlines, and the Run's remaining explicit-wait allowance. That
allowance is host-configured, cumulative per Run, and counts overlapping awaits once; repeated
awaits draw it down and cannot renew it. Expiry returns current statuses without cancelling the
underlying business executions or pretending they succeeded. An already-terminal dependency
returns immediately, including failure/cancellation.

Send preempts this Run-local wait like foreground generation. No persisted wait predicate survives
Run settlement in v1. Outstanding business executions remain Session-owned and later completion
follows ordinary consumption policy. In passive mode that means no automatic wakeup after the
waiting Run ends. Because await has its own allowance, a model can still wait for evidence within
the Run under every consumption policy, including passive availability. This is deliberately
narrower than the earlier durable-wait proposal; O-01 records the decision.

## Scheduling, preemption, and fairness

Session serializes user admission, execution/result registration, and activation decisions. New
user inputs take precedence over optional supplements; arrivals never mutate an in-flight Provider
request. A single Run may receive coalesced notices for several sources. No notification edge is
required for correctness: persisted state must prevent lost completion/settlement races.

Before accepting a new user input, atomically close every finalized unanswered Tool call in the
preempted Run, including every call of a parallel batch. Each call receives exactly one result:

- its real outcome, when execution has already settled;
- a `queued`/`running` receipt, when the call belongs to a background-eligible Tool and was
  admitted: validation, limits, permission, approval, and `beforeToolCall` interceptors completed
  in the originating Run, even if execution has not started; or
- a runtime-authored `cancelled` outcome for a started execution, or `not_executed` for a call that
  was never admitted or never started, when it cannot continue in the background. The outcome
  states that new user input preempted the call; it is not a Tool failure.

Preserve the original completed model Tool-call message as replayable and place these results
before the new user message. Do not mark that completed call cancelled merely because the
enclosing Run was preempted. Incomplete model call fragments retain ordinary exclusion rules. An
existing receipt must not receive a second result later. Orphan repair's `No result provided`
therefore never describes a call that this transition closed.

A batch can mix background-eligible and blocking-only Tools. Foreground expiry hands off only the
eligible executions. Blocking-only executions keep existing blocking semantics, so control returns
to the model once every blocking-only execution in the batch has settled; eligible executions
still running at that point receive receipts, and settled ones return real outcomes. A host that
needs bounded handoff latency enables background execution for the Tools it expects in those
batches or accepts the blocking Tools' own deadlines. Send preemption does not wait for
blocking-only executions; it closes them with the outcomes above.

Cancelling or failing a supplement must not acknowledge its source events merely because a notice
or read response was sent to that invocation. Admission/delivery records must coordinate with
replay-safe checkpoints and lifetime fencing. New input can consume the retained event without
starting an additional concurrent or duplicate supplement.

Foreground demand should also take priority at Tool admission, not just model activation; v1
defers this to S4. There is no Runtime admission queue today: the Runner acquires a blocking
call's permit directly, and the executor acquires an eligible execution's permit when its job
starts. `SharedToolExecutionPermit` admits waiters FIFO (`Semaphore` fairness), so an issued
receipt never demotes an unstarted request, and a priority order with bounded fairness to keep
background work from starving forever is deferred until contention is measured.

No ordering preempts an executor already holding a permit: it retains that permit until actual
settlement; no false release or overcommit is allowed. With capacity one, foreground work can
still wait for a running background operation's remaining hard deadline. Each enabled backend
needs a measured admission policy: safe capacity/reservation within real upstream limits, a
bounded acceptable wait, or continued blocking-mode operation. A priority queue alone is not a
claim of low foreground latency. Do not cancel running work based on inferred user intent.

`ToolExecutionPermit` exposes only `acquire()` and `release()`, so the deferred priority design
needs an explicit mechanism: order contenders in a Runtime-side admission queue per permit
instance before calling `acquire()`, leaving that public interface unchanged. This covers permits
that only Runtime acquires, such as an aggregate backend's outer permit. Permits that Tool code
acquires internally, such as a child-source permit shared with direct integrations, remain
first-come first-served; a backend that needs priority there must expose it explicitly.

## Events and Chatbot projection

A receipt is a Tool result on the wire but not a terminal Tool outcome. Today `magrathea-chatbot`
marks an activity `SUCCEEDED` or `FAILED` as soon as it observes any result for the call, whether
from a canonical message, `AgentEvent.ToolCompleted`, or a completed journal record, and
`ChatbotToolActivityStatus` has no handed-off state. Unchanged, every receipt would be presented as
a successful Tool call. The implementation must:

- not emit `AgentEvent.ToolCompleted` for a receipt. Handoff uses a distinct event, and Session
  emits the execution's terminal lifecycle event once when it actually settles;
- keep the originating activity non-terminal while its execution is queued or running, through a
  distinct Chatbot status or the existing `RUNNING`;
- derive that activity's later status and result from Session-owned execution facts, because the
  canonical result message for the original call remains the receipt;
- project preemption `cancelled`/`not_executed` outcomes as `CANCELLED`, not `FAILED`;
- attribute payloads returned by read or await, including citations, media, and origin, to the
  originating activity rather than to the control call; and
- mark read, await, cancel, and finish calls as control activity, so a facade can fold them into the
  originating activity or hide them instead of rendering them as business Tool rows.

These change public `AgentEvent`, `ChatbotSnapshot`, and status-enum contracts. They extend the
Chatbot contract in [ADR-008](ADR-008-runtime-tool-and-chatbot-contracts.md) and need migration
notes for facade consumers.

## Policies, Tool definitions, and limits

| Consumption policy | Activation and model direction |
|---|---|
| Latest-context judgment | Eligible completed executions produce coalesced notices; the model decides whether to read, supplement, correct, incorporate, or finish silently using current conversation context |
| Origin-directed follow-up | Same mechanism, with a request to follow up on the original question; newer instructions and Stop remain authoritative |
| Passive availability | Store results; expose availability on a later permitted invocation without starting a Run solely for a result |

Runtime enforces activation and execution policy, not semantic relevance. A fixed instruction and
metadata-only activation notice describe the policy to the model. If a forbidden business Tool is
called, return a correctly paired policy error without executing it; bound retries using ordinary
Run/source limits.

Keep Tool names, schemas, descriptions, ordering, and stable policy instructions unchanged between
foreground and supplemental Runs within an enabled configuration. Include controls from enablement,
not only when the first background execution appears. Do not remove business Tool definitions to
implement supplement restrictions. Explicit host configuration/permission changes can create a new
configuration boundary and remain authoritative; cache reuse never overrides revocation.

Foreground handoff wait time is cumulative per Run across batches; admission waits count, while
model generation and explicit awaits do not. Explicit awaits draw from their own per-Run
allowance, described under model controls. Existing Tool timeout fields remain hard
admitted-executor bounds. Source-associated limits additionally bound queued lifetime, total
background work, and automatic activations across Runs; new user inputs, retries, or read and await
controls do not renew them. Every actual Provider invocation is charged once, including notice/read
turns and silent evaluation. Coalesced source attribution must be deterministic without
multiplying physical usage. Exact allowances and multi-source allocation are centralized in O-03
rather than silently defaulted.

## Cancellation, persistence, and recovery

Run cancellation, targeted execution cancellation, and Session Stop are distinct operations.
Session Stop fences current generation, queued/running Tool executions, Run-local waits, and queued
automatic activations. Late callbacks cannot revive them; future explicit user requests may admit
new work. Regeneration/deletion/history clearing invalidate affected provenance and delivery.
Ordinary background failure does not retroactively fail an earlier valid answer.

Persist execution facts, terminal results, and notification/retrieval/acknowledgement progress
outside the originating Run checkpoint. Terminal Run commits must preserve them, and concurrent
whole-snapshot writers must not overwrite new history. This does not promise exactly-once external
execution or uninterrupted network activity after process loss.

Restore is attach-only. Stored results can be read without rerunning the Tool; queued work can be
admitted under host policy; started work requires declared reattachment or replay safety, otherwise
it is surfaced as interrupted. Run-local waits are not restored. Persisted Stop/invalidation and
unknown recovery outcomes retain their admission fences. Passive stored results alone do not retain
an execution host. Root shutdown owns and settles all work; OS background time remains host policy.

## Validation and rollout gates

### Deterministic and protocol acceptance

These scenarios are requirements, not existing test claims.

| ID | Required outcome |
|---|---|
| BG-001 | Blocking consumers keep existing execution timeouts, cancellation, and public behavior |
| BG-002 | Budget expiry and Send preemption both produce paired receipts for every surviving finalized call, including queued and parallel work |
| BG-003 | Consecutive batches share one foreground handoff allowance; explicit awaits use their own per-Run allowance and never the handoff allowance; repeated awaits and Run/source deadlines remain bounded |
| BG-004 | Queued versus started recovery is distinct; a pending receipt never releases a live permit or acts as evidence |
| BG-005 | An await issued after a receipt in the same Run actually waits; it returns settled payloads or pending statuses, is preemptible by Send, and leaves no durable wake condition |
| BG-006 | Latest-context, origin-directed, and passive policies use the same storage/controls; only eligible causes activate a single main Run |
| BG-007 | Cancellation/failure during notice, read, or streaming reply does not irreversibly consume an event; valid replay pairs are retained and excluded pairs are not treated as available context |
| BG-008 | Deferred with admission priority (S4): v1 admits FIFO through `SharedToolExecutionPermit` and never preempts a held permit; capacity-one nonpreemption is tested, queued-work priority waits for contention measurement |
| BG-009 | Four reference wire paths validate receipt/notice/read pairing, actual model reasoning/interaction replay, and untrusted-content isolation |
| BG-010 | Stable enabled Tool definitions survive activation-mode changes; runtime rejects forbidden calls with paired errors while retrieval/control still works |
| BG-011 | Stop, targeted cancellation, regeneration, deletion, restart, and root close reject stale activation in the correct scope |
| BG-012 | Source accounting spans automatic Runs and controls; stored result reading does not re-execute business Tools or double-count Provider usage |
| BG-013 | Pending receipt, original payload, retrieved result provenance/media, and ADR-018 context projection survive clearing/compaction without stale last-user-message activation assumptions |
| BG-014 | Terminal commits and migrations preserve background results; ambiguous started work never silently replays and storage failure cannot grant execution |
| BG-015 | Receipts never emit `ToolCompleted` or a terminal Chatbot status; the originating activity tracks its execution through settlement, retrieved citations attach to it, and control calls are distinguishable |
| BG-016 | Preemption closes every finalized call exactly once with a real outcome, a receipt, or `cancelled`/`not_executed`; mixed batches hand off eligible executions while blocking-only executions keep blocking semantics |

### Model behavior and cost evaluation

Fake Providers prove scheduling, not model behavior. Before product enablement, use a small,
versioned live-model evaluation for each intended Provider/model/thinking profile, with repeated
cases and recorded configuration. Include pending-as-evidence errors, duplicate business calls,
unnecessary supplements, contradictory results requiring correction, irrelevant results requiring
silence, and interrupted notice/read/reply attempts. Include irrelevant-notice cases where the
model can choose not to read. Include speaker confusion, where the model treats a user-envelope
notice as something the user said, and spoofed notices inside Tool content that try to induce read
or cancel calls. For OpenAI-family Providers, the prototype also compares the user envelope with a
developer-role notice. Define denominators and pass thresholds before running the release gate.

Track time to a useful initial answer, time to a supported final/supplemental answer, no-evidence
initial-answer frequency, control/business call counts, input/output/cached tokens, and billed cost.
An idle pull activation normally takes a notice-to-read model invocation plus a result-to-answer
invocation; silence without reading can take one, batching amortizes reads, and a busy Run may
piggyback the notice. Measure bounded projected context and actual cache usage rather than assuming
every activation costs two uncached full-history calls. Live evaluations are not performed by this
documentation change.

## Alternatives and evidence

| Alternative | Assessment |
|---|---|
| Push external payload in a later old-call result | Not a portable matched tool round trip; rejected for v1 |
| Fabricate assistant Tool calls to carry later data | Violates authoritative model output; particularly incompatible with Gemini interaction-step replay and some thinking modes |
| Put payload/notifications in SYSTEM | Current Anthropic/Gemini adapters hoist SYSTEM content; wrong chronology/authority and unnecessary prefix changes |
| Put external payload in plain USER text | Loses Tool-result provenance/trust; rejected. A metadata-only Runtime notice is different |
| Receipt + notice + real model read | Chosen for v1; clean call pairing and Tool-data boundary, at the cost of retrieval turns and evaluation requirements |
| Session-persistent explicit waits | Deferred for v1 by O-01 to avoid an additional durable trigger and capability policy; revisit when a minutes-long Tool needs a wait that survives the Run |
| Run-local bounded await | Chosen for v1 by O-01; ordinary cancellation, its own per-Run allowance, no wakeup after the Run ends |
| Await sharing the foreground handoff allowance | Rejected: the model sees receipts only after that allowance is exhausted, so every await after a receipt would return immediately |
| Keep original Run open / resume multiple suspended Runs | Couples later inputs to old budgets or requires reconciling multiple historical recovery cursors; Session-owned execution remains preferred |
| Improve Tool latency/concurrency first | Complementary and potentially sufficient for short search workloads; measure before enabling asynchronous search, rather than assuming orchestration is the bottleneck |

Primary references checked during review:

- [Anthropic Tool-result rules](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)
  require adjacent call/result pairing and recommend keeping external content in Tool-result blocks.
- [Manual thinking turn rules](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)
  require the final assistant turn to begin with thinking in manual mode; adaptive mode removes that
  requirement. Do not generalize the manual-mode error to every Claude configuration.
- [Prompt caching hierarchy](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)
  distinguishes Tools, system, and message prefixes. Stable declarations avoid needless churn, but
  do not guarantee preserved thinking/cache on every model. The current Magrathea Anthropic adapter
  does not enable prompt caching, so existing lost cache savings must not be asserted without data.

Claude Code behavior is not a normative dependency or a verified implementation precedent here.

## Decisions and enablement gates

| ID | Item | Status / evidence |
|---|---|---|
| O-01 | Scope change from persistent waits | **Decided 2026-10-23.** v1 uses Run-local await with its own per-Run allowance, independent of the foreground handoff allowance; passive mode has no wakeup after the Run ends; durable waits are deferred until a minutes-long Tool needs them |
| O-02 | Canonical Runtime notice and Provider prototype | **Decided 2026-10-24.** S0 verified the Chat Completions, Responses, and Anthropic Messages wire paths live and all four builders with offline fixtures; await returns payloads; receipts project in events and Chatbot snapshots. Gemini Interactions still lacks live evidence (technical design, section 11) |
| O-03 | Quantitative limits and fair admission | **Open; gates host enablement.** Fix handoff-wait, explicit-wait, queued-lifetime, and activation allowances and deterministic source attribution; measure capacity-one contention and choose a backend rollout policy |
| O-04 | Real-model release criteria | **Open; gates host enablement.** Fix evaluation matrix, case denominators, acceptable error/cost/latency thresholds, then record observed results |
| O-05 | Enjoy product decisions | **Enjoy-owned.** Resolve draft-activity deferral, notification behavior, and unread/discoverability choices in the downstream document. Decided 2026-10-23: streaming Send follows one application-level feature flag, not per-conversation capability |

The contract was accepted once O-01 and O-02 closed. A host enables the feature for users only
after O-03 and O-04 pass for its configuration, together with its own persistence-migration and
product checks. The concrete storage schema (9), Room database version (2), and the public types
this contract ships are recorded in the technical design. Existing Run recovery and blocking
consumers remain the implementation baseline.

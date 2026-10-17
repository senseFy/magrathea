# History replay and exclusions

Runtime retains canonical conversation history, including observed partial output. Before each
conversational Provider call, it prepares context, applies the host `ContextTransformer`, applies
`ReplayPolicy`, and projects Tool results for the model. These steps prepare a separate input;
they do not replace stored messages or decide what an application displays.

## Replay contract

`ReplayPolicy.transform(messages, model)` remains the message-only compatibility entry point.
`transformWithReport(messages, model)` returns a `ReplayResult` with the prepared messages and
optional `exclusions`. The default implementation calls `transform` and leaves exclusions unknown,
so existing custom policies and lambdas continue to work without claiming that every input survived.
Policies that report decisions override `transformWithReport`.
Their message-only `transform` can delegate to that result's `messages`, as the default policy does.

`DefaultReplayPolicy` reports each whole-message exclusion:

| Reason | Meaning |
|---|---|
| `EMPTY_AFTER_TRANSFORMATION` | An assistant has no remaining parts after replay transformation, including a stream interrupted during reasoning without Provider/model identity |
| `UNFINISHED_TOOL_CALLS` | An output-limited assistant contains unfinished Tool calls |
| `CANCELLED_OR_FAILED` | The assistant's own stop reason is cancellation or failure |

An exclusion contains only the original message ID and reason. Reasoning can be dropped during
cross-model replay; an assistant whose text or Tool calls survive is retained. Existing orphaned
Tool-result repair and ID normalization remain unchanged. Raw history is never mutated.

The report describes only whole messages excluded by replay. It is not a block-level diff, a
compaction report, or proof of what a Provider accepted. Host transformations and later adapter
encoding can make additional changes. Custom policies can leave reporting unknown rather than
infer exclusions from renamed or synthesized message IDs.

## Observable result

`AgentEvent.ContextTransformed.replay` carries a `ReplayReport` when the policy reports exclusions.
The report identifies the logical run, zero-based turn, target Provider and model, and exclusions.
An empty exclusion list means replay reported no whole-message omissions. A null report means no
report is available; it does not mean all history was sent.

Managed sessions retain only the latest report in `AgentSessionRuntimeSnapshot.replay`.
`ChatbotSnapshot.replay` exposes the same metadata without duplicating replay rules. Late consumers
of a live session use the snapshot; the best-effort event stream is not required to rebuild it.

- Every context preparation replaces the preceding report, including replacement with null when
  a custom policy does not report.
- Starting or resuming execution and replacing an idle request clear the preceding report.
- Later streaming, Tool, and terminal events retain the latest report for that execution.
- Reports do not affect admission, retry, recovery fences, checkpoints, or persistence. They are
  process-local presentation data. A restored session has no report until input is prepared again.

There is no report history or duplicate message payload. The default policy emits at most one
exclusion per input message. Snapshot and event compatibility entry points retain their previous
constructor and copy signatures; no stored-session schema change is needed.

## Application presentation

Incomplete output and replay exclusion are separate facts. A product may display partial output,
mark it as omitted from the latest prepared input, or hide it in its UI projection. Hiding messages
does not require deleting history. An application that only hides incomplete output can use the
message/block completion state independently of whether another request has been prepared.

```kotlin
val snapshot = session.state.value
val omittedIds = snapshot.replay?.exclusions?.mapTo(mutableSetOf()) { it.messageId }
val displayed = snapshot.messages.filterNot { it.id in omittedIds.orEmpty() }
```

This example deliberately chooses to hide all reported exclusions. Products can select reasons
instead. A null report should be treated as unknown, and a report's run/turn/model identifies the
input it describes rather than a permanent validity label on a message.

## Verification

Common replay tests cover interrupted reasoning, retained text and Tool semantics, and exclusion
reasons. Adapter tests exercise replay followed by both Chat Completions and Responses request
construction. Runtime and Chatbot tests cover late attachment, report replacement/reset, unchanged
raw history and persistence, and reporting policies versus legacy custom policies. JVM binary
contracts protect published constructor/copy signatures and the original replay-policy entry point.

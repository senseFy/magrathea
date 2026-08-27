# ADR-015: Runtime Tracing Contract

- Status: Accepted
- Date: 2026-08-25
- Implementation: Implemented

## Decision

Tracing replaces telemetry as Magrathea's only structured observability path.

- Core defines generic tracing primitives and coroutine context propagation.
- Runtime defines Agent span topology and semantic attributes.
- Hosts provide buffering, persistence, export, retention, and lifecycle.
- Debug recording remains separate.
- The implementation removes the telemetry API without a compatibility layer or dual writes.

The tracer provides in-process parent/child tracing. It does not provide storage, transport, a
viewer, metric aggregation, or detailed logs.

## Boundary

| Owner | Responsibility |
|---|---|
| `magrathea-core` | Tracer, span, context, values, completed spans, sink, no-op, context propagation |
| `magrathea-runtime` | Agent instrumentation, names, attributes, events, status mapping |
| Provider and Tool adapters | Optional children under the active Runtime span |
| Host | Composition, queueing, I/O, retention, privacy policy, user controls |

Core tracing types do not depend on Agent messages, Provider payloads, Chatbot DTOs, platform log
APIs, databases, or observability vendors. No separate tracing module is required.

The first version excludes:

- raw requests and responses, prompts, reasoning, Tool arguments and results;
- exception messages and stack traces;
- OpenTelemetry dependencies and W3C propagation;
- baggage, links, sampling policy, exporter retry, flush, and shutdown;
- an SDK-owned trace store, file format, upload service, or viewer;
- per-function, per-chunk, or per-token instrumentation.

## Core API

The public contract has this shape:

```kotlin
data class TraceContext(
    val traceId: String,
    val spanId: String,
)

enum class TraceStatus {
    UNSET,
    OK,
    ERROR,
}

sealed interface TraceValue {
    data class StringValue(val value: String) : TraceValue
    data class LongValue(val value: Long) : TraceValue
    data class DoubleValue(val value: Double) : TraceValue
    data class BooleanValue(val value: Boolean) : TraceValue
}

data class TraceEvent(
    val name: String,
    val offsetMillis: Long,
    val attributes: Map<String, TraceValue>,
)

data class TraceSpanData(
    val name: String,
    val context: TraceContext,
    val parentSpanId: String?,
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
    val status: TraceStatus,
    val attributes: Map<String, TraceValue>,
    val events: List<TraceEvent>,
)

fun interface MagratheaTraceSink {
    fun export(span: TraceSpanData)
}

interface MagratheaTracer {
    fun startSpan(
        name: String,
        parent: TraceContext?,
        attributes: Map<String, TraceValue> = emptyMap(),
    ): MagratheaTraceSpan
}

interface MagratheaTraceSpan {
    /** Null only for the no-op span. */
    val context: TraceContext?

    fun addEvent(
        name: String,
        attributes: Map<String, TraceValue> = emptyMap(),
    )

    fun end(
        status: TraceStatus = TraceStatus.UNSET,
        attributes: Map<String, TraceValue> = emptyMap(),
    )
}
```

Core supplies `NoopMagratheaTracer` and a sink-backed `DefaultMagratheaTracer`.
`DefaultAgentRunner` accepts a tracer and defaults to no-op. The host owns the tracer and sink.

Contract rules:

- IDs are opaque and non-blank. A child keeps the trace ID and receives a new span ID.
- `TraceSpanData` is an in-process DTO, not a durable wire format.
- Events are ordered within a span. Their offsets and span duration use a monotonic clock.
- `startedAtEpochMillis` is for correlation; it does not determine duration.
- End attributes override start attributes with the same key.
- `end` is idempotent and first-end-wins. Events after end are ignored.
- Spans and sinks are thread-safe. Parallel children may finish concurrently.
- Children may reach the sink before their parent.
- Tracer calls are non-suspending. A sink must perform only a prompt, non-blocking handoff.
- Runtime ignores tracer and sink failures. Tracing cannot change Agent state, events, errors,
  deadlines, retries, or cancellation.

The API does not include `Any` attributes, mutable `setAttribute`, baggage, links, exporter
lifecycle, or background I/O.

## Context propagation

Core provides coroutine helpers:

```kotlin
suspend fun currentMagratheaTraceContext(): TraceContext?

suspend fun <T> withMagratheaTraceContext(
    context: TraceContext?,
    block: suspend () -> T,
): T
```

Runtime runs each owned operation inside its span context. Structured child coroutines inherit it.
Adapters composed with the same tracer can therefore create children without trace fields in
`AgentRequest`, `ProviderRequest`, Tool payloads, checkpoints, or persisted state.

The `run` and `resume` root spans start when their cold flows are collected. A host joins an
existing trace by collecting inside `withMagratheaTraceContext`. Detached work and process or
network boundaries require explicit host propagation.

## Execution and recovery

Each collected `run` or `resume` flow creates one `magrathea.agent.execution` span.

- With no parent, it starts a trace. With a parent, it joins that trace.
- `AgentSessionId` correlates a conversation.
- `AgentRunId` correlates a logical run across recovery.
- Resume creates a new execution span with `resumed=true`.
- Trace context is not persisted. Resume correlation uses session and run IDs.

This avoids reopening ended spans or retaining a trace across process loss and long pauses.

`interrupt`, `cancel`, and `inspectRecovery` use short `magrathea.agent.control` spans. They join a
host parent only when one is active.

## Runtime topology

```text
magrathea.agent.execution
├── magrathea.store.operation                 load / terminal commit
└── magrathea.agent.turn
    ├── magrathea.context.prepare
    │   └── magrathea.provider.request        purpose=context_summary
    ├── magrathea.store.operation             checkpoint commit
    ├── magrathea.provider.request            purpose=model
    ├── magrathea.tool.call                   zero or more, possibly parallel
    └── magrathea.store.operation             checkpoint commit
```

- A turn span covers one Runtime turn.
- Each physical Provider collection has its own span. A retry creates another span.
- Context summaries use a Provider span with `purpose=context_summary`.
- Parallel Tool calls are sibling spans.
- A Tool span covers resolution, policy, permit wait, execution or replay decision, and result
  classification. It contains no arguments or result payload.
- Persistence spans cover calls through the persistence port, not in-memory state changes.
- Interceptors, state reduction, chunks, and token deltas do not create spans.

Adapters may add children but cannot rename or end Runtime-owned spans.

## Semantic conventions

### Attributes

| Scope | Key | Type | Meaning |
|---|---|---|---|
| Root | `magrathea.trace.schema_version` | Long | Convention version, initially `1` |
| Root | `magrathea.sdk.version` | String | SDK version |
| Agent | `magrathea.agent.session_id` | String | Session identity |
| Agent | `magrathea.agent.run_id` | String | Logical run identity, when known |
| Agent | `magrathea.agent.operation` | String | `run`, `resume`, `interrupt`, `cancel`, `inspect_recovery` |
| Agent | `magrathea.agent.resumed` | Boolean | Whether the execution is resumed |
| Turn | `magrathea.agent.turn` | Long | Zero-based turn |
| Common | `magrathea.outcome` | String | `success`, `failure`, `cancelled`, `interrupted` |
| Common | `magrathea.error.code` | String | Stable `AgentFailureCode`, when applicable |
| Common | `magrathea.error.phase` | String | Safest known failure phase |
| Provider | `magrathea.provider.key` | String | Adapter identity |
| Provider | `magrathea.provider.model` | String | Model identity |
| Provider | `magrathea.provider.request_id` | String | Physical invocation identity |
| Provider | `magrathea.provider.attempt` | Long | Initial request `0`, then physical retries |
| Provider | `magrathea.provider.purpose` | String | `model` or `context_summary` |
| Provider | `magrathea.provider.invocation_intent` | String | `new_attempt` or `reattach` |
| Provider | `magrathea.provider.event_observed` | Boolean | Any canonical event observed |
| Usage | `magrathea.usage.input_tokens` | Long | Known input tokens |
| Usage | `magrathea.usage.output_tokens` | Long | Known output tokens |
| Usage | `magrathea.usage.reasoning_tokens` | Long | Known reasoning tokens |
| Context | `magrathea.context.reason` | String | `proactive` or `overflow_recovery` |
| Context | `magrathea.context.action` | String | `unchanged`, `reused`, `compacted`, `failed_open` |
| Context | `magrathea.context.failure` | String | Typed preparation failure |
| Context | `magrathea.context.estimated_input_tokens` | Long | Estimate, when known |
| Context | `magrathea.context.input_limit_tokens` | Long | Limit, when known |
| Tool | `magrathea.tool.name` | String | Registered name |
| Tool | `magrathea.tool.call_id` | String | Model call identity |
| Tool | `magrathea.tool.execution_id` | String | Durable execution identity |
| Tool | `magrathea.tool.call_ordinal` | Long | One-based turn ordinal |
| Tool | `magrathea.tool.executor_started` | Boolean | Executor boundary entered |
| Tool | `magrathea.tool.result_error` | Boolean | Result is an error result |
| Tool | `magrathea.tool.replayed` | Boolean | Durable work reused or replayed |
| Store | `magrathea.store.operation` | String | `load` or `commit` in Runtime |

`magrathea.error.phase` uses bounded SDK values such as `persistence.load`, `context.prepare`,
`provider.resolve`, `provider.transport`, `provider.decode`, `provider.canonicalize`, `tool.policy`,
`tool.execute`, `persistence.commit`, and `runtime`. Instrumentation uses a coarser phase when it
cannot identify a narrower one safely.

Unknown usage is omitted, not recorded as zero. Provider usage belongs to that physical request.
Execution usage is the amount newly observed during that collection, including summary calls; it is
not the persisted session total.

### Events

| Owner | Event | Meaning |
|---|---|---|
| Provider span | `magrathea.provider.first_event` | First canonical Provider event; offset is first-event latency |
| Provider span | `magrathea.provider.terminal_event` | Canonical terminal event observed |
| Turn span | `magrathea.provider.retry_scheduled` | Next attempt, failure code, and delay |
| Tool span | `magrathea.tool.result_reused` | Durable completed result reused without fresh execution |

The failed Provider span ends before retry backoff. No event is emitted per chunk.

### Status

| Runtime result | Status | Outcome |
|---|---|---|
| Success | `OK` | `success` |
| Terminal failure | `ERROR` | `failure` |
| Cancellation | `UNSET` | `cancelled` |
| Recoverable interruption | `UNSET` | `interrupted` |

A failed Provider attempt remains `ERROR` when a later retry succeeds. Its parent may finish `OK`.
Every Runtime span ends on success, failure, timeout, cancellation, and interruption.

## Data policy

SDK instrumentation never records:

- prompts, messages, reasoning, citations, attachments, or generated content;
- Provider bodies, SSE lines, headers, endpoints, or metadata payloads;
- credentials, credential references, or authorization data;
- Tool arguments, results, approval reasons, or external source content;
- exception messages, stack traces, object dumps, or application metadata.

Diagnostic IDs and Tool names are high-cardinality and potentially sensitive. Hosts set retention
and may remove them from metric exports. Detailed debug data uses a separate recorder; it may carry
trace and span IDs for correlation but cannot enter a trace sink.

## Telemetry removal

The implementation removes:

- `MagratheaTelemetry` and `NoopMagratheaTelemetry`;
- `TelemetryEvent`, `TelemetryOutcome`, and `TelemetryStoreOperation`;
- the `telemetry` constructor parameter;
- telemetry emission and telemetry-specific tests.

There is no deprecated facade, bridge, dual writer, or second metric event stream. Metrics are
derived from completed spans.

`ProviderRequestPurpose` moves to its Core Agent/Provider owner. `MonotonicClock` remains a generic
platform or tracing primitive. Existing telemetry tests are replaced by tracing contracts, keeping
their outcome, timing, usage, cancellation, recovery, privacy, and observer-failure coverage.

## Verification

Implementation requires tests for:

1. New-root and host-parented traces, with correct IDs and parentage.
2. Flow-collection lifetime and closure on every terminal path.
3. Separate resume execution spans with stable session/run correlation and no persisted context.
4. Required topology for turns, summaries, physical Provider attempts, parallel Tools, and Store
   calls.
5. Retry, new-attempt, reattach, first-event, terminal-event, and usage semantics.
6. Fresh Tool execution versus durable result reuse.
7. Status and outcome for success, failure, timeout, cancellation, interruption, and collector
   cancellation.
8. Correct usage across retry, summary, interruption, and resume without double counting.
9. Thread safety and idempotent span end.
10. Tracer/sink failure isolation and content/secret canaries across all exported fields.

`RuntimeTelemetryContractTest` is replaced rather than retained. The implementation change also
updates the API dump, behavior-contract ledger, architecture docs, samples, and changelog.

## Evolution

Adding an optional bounded attribute or event is compatible. Changing a span name, parentage, key
type or meaning, or the content-free boundary requires an ADR update. Sinks ignore unknown keys and
events. Durable formats, remote propagation, sampling, and debug bundles require separate versioned
contracts.

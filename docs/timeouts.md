# Timeout Contract

Magrathea separates timeouts by failure domain. A long reasoning or Tool-using run is not bounded by
the same deadline used to establish a network connection.

## Defaults

| Scope | Configuration | Default | Meaning |
|---|---|---:|---|
| Provider connection | `ProviderTimeoutConfig.connectTimeoutMillis` | 15 s | Establish the Provider transport connection |
| Provider first event | `ProviderTimeoutConfig.firstEventTimeoutMillis` | 120 s | Receive the first canonical `ProviderChunk` |
| Provider stream idle | `ProviderTimeoutConfig.streamIdleTimeoutMillis` | 90 s | Maximum gap between canonical chunks |
| Provider call | `ProviderTimeoutConfig.callTimeoutMillis` | 10 min | Complete one Provider attempt, including its stream |
| Tool execution | `RuntimeConfig.defaultToolTimeoutMillis` | 2 min | Execute a Tool whose `ToolDefinition.timeoutMs` is absent |
| Agent run | `RuntimeConfig.runTimeoutMillis` | 30 min | Complete one user-initiated run across Provider and Tool turns |

`ToolDefinition.timeoutMs` overrides the Runtime Tool default for that Tool. `ProviderConfig.timeouts`
is copied into every `ProviderRequest`, so a custom adapter receives the same contract as the
reference adapters. OpenAI, Anthropic, Gemini, and Gateway adapters translate it into per-request
HTTP connection, socket, and total-call settings.

The Runtime independently enforces first-event, canonical stream-idle, Provider-call, Tool, and
whole-run deadlines. This keeps custom adapters and non-HTTP implementations bounded as well. The
effective deadline is always the first enclosing deadline to expire.

Ktor engine support for transport-level controls differs by platform. In particular, the browser
engine supports total request timeout but cannot expose separate connection or socket timers, and
Darwin does not expose a separate connection timer. On those engines, the total HTTP request timer
plus Runtime first-event, stream-idle, Provider-call, and whole-run deadlines remain authoritative;
`connectTimeoutMillis` is applied precisely only when the selected engine supports it. See Ktor's
[timeout engine matrix](https://ktor.io/docs/client-timeout.html#limitations).

## Failure and cancellation

A deadline produces `AgentFailureCode.TIMEOUT`, projected as `ChatbotFailure.TIMEOUT`. It is distinct
from `PROVIDER_NETWORK`. User or host cancellation remains cancellation and is never rewritten as a
timeout. A Tool execution timeout becomes a normal typed Tool error result so the model can recover
on a later turn; the whole-run deadline remains authoritative over all Tool activity.

Timeouts preserve streaming backpressure: a Provider cannot advance past a chunk until Runtime has
validated, reduced, emitted, and recorded that chunk. Partial output therefore remains available if
a later idle or call deadline expires.

## Configuration

```kotlin
val engine = AgentEngineConfig(
    provider = ProviderConfig(
        timeouts = ProviderTimeoutConfig(
            connectTimeoutMillis = 15_000,
            firstEventTimeoutMillis = 120_000,
            streamIdleTimeoutMillis = 90_000,
            callTimeoutMillis = 600_000,
        ),
    ),
    runtime = RuntimeConfig(
        defaultToolTimeoutMillis = 120_000,
        runTimeoutMillis = 1_800_000,
    ),
)
```

All values are positive milliseconds. Provider connection, first-event, and idle deadlines must not
exceed the Provider-call deadline.

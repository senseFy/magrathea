# ADR-016: Debug Recording Contract

- Status: Accepted
- Date: 2026-08-26

## Decision

Debug recording is separate from agent events and tracing. Magrathea exposes
`MagratheaDebugRecorder`; hosts decide whether records are enabled, stored, retained, or exported.
The default recorder is disabled.

`MagratheaDebugRecord` contains a level, component, stable event name, optional session and trace
identity, and typed scalar attributes. It has no message or arbitrary payload field.

Runtime checks `enabled` before constructing attributes. Recorder failures never affect an agent
run. `enabled` and `record` must be thread-safe, prompt, non-blocking handoffs; queueing and I/O are
host work. `AgentEvent.Debug` is removed; Magrathea does not maintain two diagnostic paths.

## Data boundary

Records may contain stable identifiers, enum values, counts, sizes, durations, booleans, Provider
and model identity, failure type, and status code. They must not contain prompts, responses,
reasoning, Tool input/output, request or response bodies, headers, credentials, endpoint URLs,
exception messages, or local paths.

String attributes are bounded. Integrators must apply their own storage, retention, and export
policy.

## Correlation

Provider records include `run_id`, `turn`, `provider_request_id`, `provider_attempt`, and
`provider_purpose`. These fields do not depend on tracing being enabled. When tracing is enabled,
the record also carries the physical Provider span context.

## Runtime events

The initial stable events are:

- `provider.request.messages`
- `provider.request.config`
- `provider.selected`
- `provider.chunk`
- `provider.message.merged`
- `provider.failed`
- `agent.state.after_chunk`

Runtime emits the Provider events for every physical model and context-summary attempt. A
recoverable failure that will retry is `WARN`; a terminal attempt is `ERROR`. `provider.failed`
uses stable `failure_type`, failure code, HTTP status, retryability, and protocol classification;
it does not expose implementation class names.

Provider failures record only typed classification facts. Missing facts remain missing; consumers
must not infer a response body or parser state that was not recorded.

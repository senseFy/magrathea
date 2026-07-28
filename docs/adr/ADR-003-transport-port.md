# ADR-003: Provider HTTP Transport Port

- Status: Accepted
- Date: 2026-07-11

## Context

Provider adapters need consistent HTTP, SSE, and JSONL behavior on Android, JVM, iOS, JS, and Wasm
without exposing a networking library in the Provider SPI.

## Decision

Provider code depends on Magrathea's `HttpTransport`, `HttpRequestSpec`, `HttpResponseSpec`, and
`HttpStreamFrame` contracts. Ktor supplies target implementations but does not appear in the public
Provider API.

The transport contract covers:

- JSON requests and bounded response bodies;
- SSE and JSONL framing with line, event, and diagnostic limits;
- status codes, response headers, and `Retry-After`;
- timeout and structured cancellation;
- sanitized, typed transport failures;
- deterministic fake and loopback-server testing.

## Consequences

Provider codecs and request builders remain common code. A custom Provider can reuse the transport
port without depending on Ktor engine types, while hosts can inject a different implementation when
needed.

# ADR-012: Project Positioning and Layer Model

- Status: Accepted
- Date: 2026-07-12

## Decision

Magrathea is a **Kotlin Multiplatform agent runtime** for secure, stateful chatbot and agent
applications. Its primary public boundary is Provider-neutral Core and Runtime APIs.

The repository uses these layers:

1. Kernel contracts: Core models, stores, credentials, Provider events, and transport ports.
2. Runtime: turns, tools, retry, cancellation, checkpoints, resume, limits, and telemetry.
3. Optional capabilities: reference Provider adapters, the MCP Tool adapter, and reusable Policy
   behavior.
4. Platform adapters: Room, IndexedDB, Keystore, and Keychain integrations.
5. Product facades: Provider-neutral Chatbot lifecycle and browser composition.
6. Gateway: browser-safe protocol, Provider bridge, and server ports.
7. Tooling: samples, isolated consumers, live harnesses, and release verification.

Dependencies point toward Runtime and kernel contracts. Reference Providers, product facades,
platform adapters, and Gateway server code remain optional layers around the minimal Core
dependency closure.

Desktop support uses JVM embedding, with the application shell supplied by the host. Browser
support uses Gateway-backed JS and experimental Wasm, keeping vendor access behind the backend
boundary.

## Product boundary

Magrathea owns the portable runtime contracts and reusable integrations. Host applications own
product UI and packaging, product-specific orchestration, JVM credential storage, browser
deployment topology, and production qualification.

## Consequences

Applications may use `DefaultAgentRunner` directly or compose the optional Chatbot facade with any
conforming Provider. Gateway and release tooling remain in the repository because they establish
browser security and published-SDK quality while remaining optional to other consumers.

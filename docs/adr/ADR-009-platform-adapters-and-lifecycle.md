# ADR-009: Platform Adapters and Resource Lifecycle

- Status: Accepted
- Date: 2026-07-11

## Decision

Platform behavior is supplied through narrow adapters and explicitly owned handles:

- `magrathea-storage-room` contains common entities, mappings, and stores; target source sets select
  database builders and paths.
- `magrathea-storage-web` owns IndexedDB connections and strict record mapping.
- `magrathea-credentials` exposes writable Android Keystore and iOS Keychain stores while Core keeps
  the read-only `CredentialProvider` boundary.
- Room entities, DAOs, generated database classes, Android `Context`, and platform security types do
  not enter Core or the Chatbot DTO surface.
- Opening a store returns one owner for its atomic `AgentPersistence`. Close is idempotent and
  prevents continued use.
- One application composition root owns its `AgentSessionManager`. Closing that root interrupts
  live sessions before Provider transport and persistence handles close. Closing a borrowed
  Chatbot facade only releases its leases. Resources created by a composition root close once.

## Composition

Android, JVM, and iOS hosts construct the Provider, Runtime, stores, tools, credentials, and Chatbot
facade directly. Android and iOS may use the supplied secure credential stores; JVM hosts inject
their own credential source. Platform paths and application lifecycle integration remain host
decisions.

## Consequences

The shared runtime has no Android application lifecycle dependency or hidden global singleton.
Applications may transfer their execution-host capability between UI and background components
without replacing the process-local manager or its canonical sessions.
Platform adapters can be tested against real Room, Keychain/Keystore, and IndexedDB boundaries
without creating platform-specific product facades.

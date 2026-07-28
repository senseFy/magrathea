# ADR-010: Gateway and Web Boundary

- Status: Accepted
- Date: 2026-07-12

## Context

Browser applications cannot safely hold vendor API credentials. Exposing internal Kotlin
serialization directly as a public network protocol would also couple SDK refactoring to deployed
servers.

## Decision

- Browsers access Providers only through a Magrathea Gateway.
- Gateway `exact-v1` is a dedicated, strict wire protocol with its own DTOs and codec.
- Stream creation and cancellation use an authenticated HTTP control plane. Events use sequenced
  Server-Sent Events with bounded replay, reconnect, idempotency, and terminal-state validation.
- The server resolves a browser-supplied model reference to server-owned Provider configuration,
  endpoint, and credential. Browser requests cannot set an upstream endpoint, credential, or
  arbitrary Provider header.
- The browser composition routes each session's Provider/model reference through the same Gateway
  boundary, so changing Provider does not require a new browser client or expose a direct adapter.
- Owner, tenant, request, session, stream, and idempotency identities are validated at every public
  boundary. Cookie sessions require CSRF protection; bearer authorization remains an explicit host
  option.
- Attachments are uploaded separately and enter generation only as owner/tenant-authorized
  references.
- Quota, audit, authentication, model resolution, and attachment authorization are injected server
  ports. The SDK does not define an account system.
- `magrathea-storage-web` persists strict session/checkpoint envelopes in IndexedDB and never stores
  Gateway or vendor credentials.
- Browser JS is the primary Web artifact. Wasm is experimental and follows the same Gateway and
  storage contracts.

## Consequences

The Gateway is optional for non-browser applications but mandatory for the supported browser path. Web
verification executes production JS and Wasm bundles through real loopback HTTP/SSE in Chromium,
Firefox, and WebKit-engine automation; engine automation is not presented as evidence for every
browser application or mobile device.

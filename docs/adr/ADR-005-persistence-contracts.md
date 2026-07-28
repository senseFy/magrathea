# ADR-005: Persistence Contracts

- Status: Accepted
- Date: 2026-07-11

## Decision

- Core depends only on `SessionStore` and `CheckpointStore`.
- Android, JVM, and iOS use the Room KMP adapter with a bundled SQLite driver. Browsers use the
  IndexedDB adapter.
- Stored sessions and checkpoints use versioned, strict envelopes. Missing or unknown fields,
  unsupported versions, corrupt JSON, and identity/index mismatches fail closed.
- A single corrupt record must not hide healthy history entries. Stores report corruption with
  record kind, identity, and stable reason only; payload and exception text remain private.
- Delete and clear operations are idempotent and operate without first decoding every record.
- Database and IndexedDB handles have explicit, idempotent ownership and close semantics.

## Schema evolution

The Alpha supports the committed current schema. A future schema change must update the format
version, exported Room schema, serialization fixtures, upgrade policy, and cross-platform tests as
one reviewed change. Stable releases must not silently reinterpret an older format.

## Consequences

Platform storage types do not enter Core. Hosts may choose their data location and backup policy,
while Magrathea controls envelope validation, corruption isolation, and lifecycle behavior.

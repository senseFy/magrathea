# ADR-004: Credential Isolation

- Status: Accepted
- Date: 2026-07-11

## Decision

- Agent requests, sessions, checkpoints, and Provider options may contain a `CredentialRef`, but
  never a credential value. Traces and diagnostics contain neither.
- `CredentialProvider` resolves the reference immediately before a model call.
- Runtime passes the resolved credential transiently to the selected Provider adapter; reference
  adapters do not own a credential resolver or store.
- Android credentials use a non-exportable Keystore key and AES-GCM ciphertext under
  `noBackupFilesDir`.
- iOS credentials use device-only Keychain items.
- JVM applications provide an explicit credential source; the SDK does not create an implicit disk
  credential store.
- Browser code never receives a vendor credential. Authentication terminates at a Magrathea
  Gateway, which owns Provider credentials and model routing.
- Public failures and `toString()` output expose stable categories, not credential values, endpoint
  details, response bodies, or platform exception messages.

## Consequences

Credential adapters are platform integrations, not session state. Backup or device transfer may
restore chat data only when allowed by the host policy; credentials must be re-established through
the destination platform's secure boundary.

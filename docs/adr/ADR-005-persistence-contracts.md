# ADR-005: Persistence Contracts

- Status: Accepted
- Date: 2026-07-11

## Decision

- Core depends on one `AgentPersistence` boundary that atomically commits the authoritative
  session snapshot and its latest recovery checkpoint.
- Android, JVM, and iOS use the Room KMP adapter with a bundled SQLite driver. Browsers use the
  IndexedDB adapter.
- Stored sessions and checkpoints use versioned, strict envelopes. Decoders inspect the envelope
  version before decoding the current payload shape. Missing or unknown fields, corrupt JSON, and
  identity/index mismatches fail closed.
- Unsupported older and newer schemas are distinct from corrupt records. Storage adapters surface
  them to the host and never silently filter, rewrite, or report them as per-record corruption.
- A single corrupt record must not hide healthy history entries. Stores report corruption with
  record kind, identity, and stable reason only; payload and exception text remain private.
- Delete and clear operations are idempotent and operate without first decoding every record.
- Database and IndexedDB handles have explicit, idempotent ownership and close semantics.
- Every logical run has a stable `AgentRunId`. A checkpoint belongs to the same session and run as
  its snapshot and records the exact resume phase.
- Terminal commits remove the checkpoint in the same transaction.

## Schema evolution

Schema 6 is the first SDK-owned migration baseline. Schemas 5 and older remain the intentional
alpha clean break; they are retained as golden fixtures and rejected as unsupported older data.

The SDK-owned machine-readable ledger is `persistence/schema-ledger.json`. It freezes schema 6 as
the permanent migration baseline, each shipped session/checkpoint fixture by SHA-256, the current
source version and production adapter, and every adjacent transition. Each migration transition
must name one dedicated `AdjacentStorageSchemaMigration` object, which owns both strict source
validation and transformation, and freeze its commonMain source path, symbol, and complete file
SHA-256. The verifier also binds that migration ID, source version, and symbol to the runtime
registry, so retaining an ID while editing or redirecting its behavior fails closed. Neither the
baseline nor `minimumReadableVersion` may advance; every transition
starting at schema 6 or later must be a migration rather than a new clean break. Local
`verifySdkCompatibility` checks the current ledger; pull-request and main-push CI additionally
compares it with the base commit so frozen fixtures, adapters, transitions, and migration source
digests cannot be rewritten together.

Every future logical schema revision must register exactly one adjacent transformation for each
version from the supported baseline to the new current version. Registry construction rejects
gaps, duplicates, and skipped versions. A decoder parses the JSON object and reads only
`schemaVersion`, applies the contiguous transformations in memory, then performs the full strict
current-schema decode, identity checks, and canonical validation. It never enables permissive
unknown-field decoding as a substitute for migration.

A migration failure, malformed document, or unsupported version must leave the stored bytes
unchanged. A storage adapter may atomically rewrite the canonical current envelope only after all
transformations and validation succeed. Every release that adds an actual migration must exercise
the adapter rewrite path and its failure-preservation tests in the same change.

Before each adjacent transformation, the registry strictly decodes and validates the document with
that transition's frozen source-schema adapter. A transformer never receives an invalid source
document, and every intermediate schema is validated before the next step. The raw JSON scanner also
rejects duplicate object keys at every depth before parsing, because map-based JSON trees otherwise
discard one value and make version dispatch or nested payload semantics ambiguous.

The JSON wire configuration is SDK-owned; a host-supplied `Json` instance cannot change field names,
class discriminators, defaults, or strictness under the same schema version. `listSessions()` may
migrate a session in memory for display, but remains read-only because it does not load and validate
the paired checkpoint. Only a full record load may rewrite session/checkpoint payloads, after their
index, session, and run identities pass together, using one Room transaction or one IndexedDB CAS.

Each schema revision updates the logical format version, immutable old and current fixtures,
migration registry, ABI where applicable, upgrade policy, and cross-platform tests as one reviewed
contract change. The Room/IndexedDB physical version changes only when their table/object-store
shape changes; a JSON-envelope migration alone does not require a physical database migration.

Schema 6 now has an explicit `StorageSchemaV6Adapter` and private envelope DTOs, so production codecs
cannot silently switch to a generic envelope serializer. Its payload mapper still delegates the
large nested Agent graph to generated domain serializers. A frozen recursive serializer-descriptor
fingerprint covers serial names, kinds, nullability, optionality, inline types, fields, enums, and
sealed subtype descriptors in addition to the golden payloads. The append-only fixture, descriptor,
and adapter gates make ordinary drift fail closed, but they do not provide the same source-level
isolation as frozen leaf DTOs for every nested subtype. New schema work should replace those mapper
internals with immutable leaf DTOs incrementally; any observable wire change still requires a new
adjacent schema.

## Additive enum evolution within a frozen schema

The descriptor fingerprint may be refrozen without a new adjacent schema only when a change is
additive and provably unobservable in persisted payloads. The one accepted case so far is adding
`AgentFailureCode.PROVIDER_PERMISSION`: the only persisted reference to that enum is
`ProviderInterruption.code`, whose constructor restricts values to the recoverable set
(`PROVIDER_NETWORK`, `TIMEOUT`, `PROVIDER_RATE_LIMIT`, `PROVIDER_SERVER`), so the new value can
never be written to storage and older releases can decode every payload a newer release produces.
`CoreContractsTest.providerInterruptionRejectsNonRecoverableFailureCodes` pins that guarantee;
widening the recoverable set is an observable wire change and requires a new adjacent schema.

## Historical clean breaks

Schemas 3→4, 4→5, and 5→6 were alpha clean breaks rather than migrations. Recovery-state, typed Tool
content, and structured reasoning changes respectively lacked enough information to reconstruct the
new invariants without inventing user or Provider state. Their original fixtures remain frozen as
evidence and are never decoded or rewritten by the schema-6 runtime.

## Consequences

Platform storage types do not enter Core. Hosts may choose their data location and backup policy,
while Magrathea controls envelope validation, corruption isolation, and lifecycle behavior.

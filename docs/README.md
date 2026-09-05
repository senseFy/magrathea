# Documentation

## Start here

| Document | Purpose |
|---|---|
| [Architecture](architecture.md) | Layers, dependency direction, composition, and deployment paths |
| [Public API overview](api-overview.md) | Core ports and primary public entry points |
| [Providers](providers.md) | Reference protocols, compatible endpoints, credentials, and custom adapters |
| [Samples](../samples) | Android, JVM, and Web consumer examples |
| [Provider capability matrix](provider-capability-matrix.md) | Wire contracts, capabilities, and validation status |

## Runtime capabilities

| Document | Purpose |
|---|---|
| [Context management](context-management.md) | Token budgets, semantic compaction, and overflow recovery |
| [Managed Agent sessions](session-management.md) | Canonical runtimes, leases, ownership, and recovery |
| [Timeouts](timeouts.md) | Provider, Tool, and whole-run deadlines |
| [Interruption and recovery](recovery.md) | Backgrounding, process loss, resume, and Tool replay safety |
| [Tracing contract](adr/ADR-015-runtime-tracing-contract.md) | Chain tracing, host integration, privacy, and telemetry replacement |
| [Debug recording contract](adr/ADR-016-debug-recording-contract.md) | Typed, default-disabled host diagnostics outside business events |
| [MCP](mcp.md) | Tool discovery, transports, policy, security, and lifecycle |
| [Web Search](web-search.md) | Portable search Tool contract and citation boundary |
| [Image Search](image-search.md) | Portable image discovery, typed media results, and chatbot projection |
| [X Search](x-search.md) | Cross-model X Search Tool and xAI hosted wire |
| [Behavior contracts](behavior-contracts.md) | Executable invariants and their nearest verification boundary |

## Platforms and release

| Document | Purpose |
|---|---|
| [Known issues](known-issues.md) | Alpha limitations and external gates |
| [Publishing](publishing.md) | Coordinates, local publication, and registry configuration |
| [Release process](release-process.md) | Maintainer gates, evidence, immutable release, and rollback |
| [Release notes](https://github.com/senseFy/magrathea/releases/tag/v0.1.0-alpha.9) | Current Alpha release notes | <!-- x-release-please-version -->
| [Android device baseline](android-device-baseline.md) | Recorded physical-device evidence |
| [Performance baseline](performance-baseline.md) | Recorded Alpha.1 deterministic measurements |
| [Architecture decisions](adr/README.md) | Accepted design decisions |

Security reports follow [SECURITY.md](../SECURITY.md). Contributions follow
[CONTRIBUTING.md](../CONTRIBUTING.md).

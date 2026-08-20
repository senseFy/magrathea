# Architecture Decision Records

These records define the architectural decisions that shape the current Magrathea SDK. They are
normative where they describe a security boundary, public protocol, persistence format, or module
dependency direction. Platform limitations and external validation gaps are tracked separately in
[Known Issues](../known-issues.md).

| ADR | Decision |
|---|---|
| [001](ADR-001-toolchain-and-platforms.md) | Supported platforms and toolchain |
| [002](ADR-002-package-and-publication-coordinates.md) | Namespace and publication coordinates |
| [003](ADR-003-transport-port.md) | Provider HTTP transport port |
| [004](ADR-004-credential-isolation.md) | Credential isolation |
| [005](ADR-005-persistence-contracts.md) | Persistence contracts |
| [006](ADR-006-ui-boundary.md) | UI boundary |
| [007](ADR-007-reference-provider-contracts.md) | Reference Provider contracts |
| [008](ADR-008-runtime-tool-and-chatbot-contracts.md) | Runtime, Tool, Policy, and Chatbot contracts |
| [009](ADR-009-platform-adapters-and-lifecycle.md) | Platform adapters and resource lifecycle |
| [010](ADR-010-gateway-and-web-boundary.md) | Gateway and Web boundary |
| [011](ADR-011-distribution-and-release-evidence.md) | Distribution and release evidence |
| [012](ADR-012-project-positioning-and-layers.md) | Project positioning and layer model |
| [013](ADR-013-mcp-tool-adapter.md) | Model Context Protocol Tool adapter boundary |
| [014](ADR-014-token-aware-context-management.md) | Token-aware semantic context management |

Changes to an accepted decision require an explicit update to the affected record, public docs,
and executable contract tests. A new record is preferred when a decision introduces a distinct
security boundary or public protocol.

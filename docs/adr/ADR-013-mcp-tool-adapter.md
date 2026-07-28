# ADR-013: Model Context Protocol Tool Adapter

- Status: Accepted
- Date: 2026-07-17

## Context

Magrathea already has a Provider-neutral `ToolRegistry`, policy gates, timeouts, call budgets, and
canonical Tool results. MCP is a protocol for discovering and invoking external capabilities, but
its complete surface also includes Resources, Prompts, Sampling, Roots, Elicitation, logging,
completion, and experimental Tasks. Importing all server capabilities into Runtime would let a
remote integration expand Agent context or authority outside the existing host composition.

## Decision

MCP support is an optional `magrathea-mcp` client adapter. It depends on Core and the official
Kotlin MCP client SDK; Core and Runtime do not depend on MCP.

The Alpha adapter maps MCP Tools onto the existing `ToolRegistry`:

- initialization and capability negotiation are delegated to the official SDK;
- `tools/list` is consumed with bounded pagination and `notifications/tools/list_changed` refreshes
  the dynamic registry; stale contracts are removed before refresh and remain unavailable after a
  failed refresh;
- remote names receive deterministic, Provider-portable runtime names;
- `McpToolPolicyProvider` remains authoritative for enablement, permission, approval, timeout, and
  call budgets;
- Tool annotations are treated only as untrusted presentation hints;
- Tools that require experimental MCP Tasks are reported as incompatible and are not advertised;
- Tool results preserve structured content and canonical MCP content in bounded Magrathea result
  fields.

Streamable HTTP is the portable transport. Remote plaintext HTTP is rejected; loopback HTTP remains
available for local development and conformance tests. Credentials are supplied lazily as headers
and are not part of server identities or Agent state. JVM/Desktop may also launch stdio servers,
but the application must display and approve the exact executable, arguments, working directory,
and environment names before process creation. A stdio child receives only explicitly supplied
environment entries, never the host's complete inherited environment.

Every connection has hard defaults for initialization and listing time, page and Tool count,
individual and aggregate Tool definitions, server instructions, and Tool result size. Hosts may
tighten or deliberately raise these limits with `McpConnectionOptions`; Runtime limits remain an
independent outer boundary. Public connection, refresh, and Tool-call failures carry stable
operation/category values without retaining raw transport or server exceptions.

Resources, Prompts, server instructions, Sampling, Roots, Elicitation, and Tasks are not
automatically injected or enabled. A future feature may expose an explicit host API for one of
these capabilities without changing this authority boundary.

The repository uses three complementary verification layers:

1. deterministic linked transports from the official SDK for protocol and mapping contracts;
2. the official MCP conformance harness over real loopback Streamable HTTP;
3. the official Inspector for manual interoperability diagnosis, not as a release assertion.

## Consequences

Any Magrathea-compatible model can call an enabled MCP Tool because the Runtime sees an ordinary
Tool definition and executor. Applications can combine local and MCP registries without changing
Provider adapters. Applications remain responsible for credential acquisition, full HTTP OAuth
authorization where required, server trust, user consent, process sandboxing, and lifecycle
ownership.

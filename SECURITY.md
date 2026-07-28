# Security Policy

## Supported versions

`0.1.0-alpha.1` receives best-effort fixes while it is the latest Alpha. It does not carry a
security SLA or production certification. Reports are evaluated against both the latest release
and the current `main` branch.

## Reporting a vulnerability

Do not place vulnerability details, credentials, tokens, private prompts, conversation content, or
exploit material in a public issue.

If enabled for this repository, use GitHub's private vulnerability reporting flow:

<https://github.com/senseFy/magrathea/security/advisories/new>

If that flow is unavailable, open a public issue containing only a request to establish a private
contact channel. Do not include technical details until a private channel has been confirmed.

Include, through the private channel:

- the affected module, platform, and revision;
- a minimal reproduction using synthetic data and non-production credentials;
- the expected and observed security boundary;
- known impact and any safe mitigation;
- whether the issue is already public or under active exploitation.

There is currently no guaranteed response or remediation timeline. Please allow maintainers time to
reproduce the report before public disclosure.

## Security boundaries

Reports are especially relevant when they involve credential exposure, cross-owner Gateway access,
authorization or quota bypass, unsafe persistence recovery, session/checkpoint data leakage,
provider endpoint/header injection, dependency integrity, or failure to close/cancel owned
resources. Product-level security outside the SDK boundary should be reported to the owning host
application instead.

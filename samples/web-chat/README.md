# Web chatbot sample

This standalone Kotlin JS/Wasm application consumes the build-local published
`magrathea-web-client` coordinate. It talks only to a Magrathea Gateway, stores the strict Core
session envelope in IndexedDB, and never accepts or embeds an upstream provider key.

The host may set `MAGRATHEA_GATEWAY_BASE_URL`, `MAGRATHEA_GATEWAY_PROVIDER`, and
`MAGRATHEA_GATEWAY_MODEL` before loading the generated script. Authentication defaults to a
same-origin HttpOnly session cookie. A non-cookie development host may inject ephemeral
`MAGRATHEA_GATEWAY_AUTHORIZATION` and `MAGRATHEA_GATEWAY_CSRF_TOKEN` globals before the bundle
loads; they are evaluated per request and never persisted. The checked-in page uses a strict CSP and
deliberately does not cancel a detached stream on `pagehide`; Send, explicit Cancel, observation,
persistence, and close are compiled into both production bundles.

The CSP grants `wasm-unsafe-eval` only so the Wasm preview can instantiate its module; it does not
grant general `unsafe-eval`. A JS-only deployment may remove that source expression.

From the repository root, `./gradlew verifyWebChatSample` starts the local authenticated Gateway
harness and runs the JS/Wasm browser E2E plus both production webpack builds. It does not deploy or
publish anything.

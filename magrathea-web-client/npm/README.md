# @saien/magrathea-web-client

Browser-only Magrathea chatbot composition. The package communicates with a version-matched
Magrathea Gateway and persists session/checkpoint envelopes in IndexedDB. It does not include a
direct provider adapter, Room, a vendor credential store, or an API-key setter.

This artifact requires a browser document/runtime and is not a Node.js server package. The
CommonJS-compatible entry exists for browser bundlers and TypeScript resolution; loading it directly
with Node is unsupported.

The generated TypeScript API is under `saien.magrathea.web.client`. Use
`createMagratheaWebChatbot(...)`; same-origin HttpOnly cookies are the default authentication mode.
Optional authorization/CSRF callbacks are evaluated per request and remain in memory.
Each session is created with an explicit `MagratheaWebChatModel`; snapshots and history retain that
selection, including its optional context-window size and maximum output-token capability, and an
idle session may switch Provider/model without rebuilding the browser client. Pass a positive
32-bit integer as `maxOutputTokens`, or `null` when the capability is unknown. This value is trusted
model metadata rather than a per-request product policy; Runtime may clamp it to the space remaining
in a known context window.
Models declare configurable reasoning with `reasoningEfforts` (`minimal`, `low`, `medium`, `high`,
`xhigh`, or `max`) and `supportsDisabledReasoning`. Pass `auto`, `disabled`, or one declared effort
to `createSession(...)` or `updateReasoningPreference(...)`; unsupported preferences fail before a
Gateway request is sent. Switching to a model that cannot honor the current explicit preference
resets that preference to `auto`.
After loading `history()`, use `restoreSession(sessionId)` to open the canonical session state or
attach to work already owned by this browser client without starting new Provider work. Use
`resumeSession(sessionId)` only when restore and execution should be one operation. Each returned
session facade must be closed independently.
Snapshots preserve text phases, redacted reasoning markers, attachment references, tool calls,
tool results, citations, stop reasons, and token usage; hosts remain responsible for safely rendering
URLs and model-provided content.

JS is the primary browser artifact. Kotlin/Wasm remains an experimental Maven/sample variant. The
host must provide CSP, origin, authentication, and Gateway deployment policy appropriate to its
application.

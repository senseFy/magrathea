# Known Issues and External Gates

`0.1.0-alpha.1` is an Alpha release. Its APIs and persisted formats may change before the stable
line.

## Product scope

- Android, JVM, and iOS applications compose the public Provider-neutral `createChatbotClient` path
  with a chosen Provider adapter, stores, tools, and credential boundary. The Web composition uses
  model identities authorized by the Gateway server.
- The direct KMP facade supports text, attachments, metadata, and regenerate. The JavaScript/
  TypeScript convenience wrapper currently exposes text send only; attachment upload UX and host
  tool registration remain composition-level work.
- The minified browser entry point is 1,285,759 bytes and exceeds Webpack's default performance
  recommendation. The package gate enforces a 1,300,000-byte ceiling; browser hosts should load the
  client on demand, and the budget should be reduced before a production release.
- Thirteen mobile KMP modules publish Android, JVM, `iosArm64`, and `iosSimulatorArm64`; eight also
  publish JS/Wasm, and two additional modules are Web-only. Wasm is experimental. macOS Native,
  watchOS, and tvOS are not published targets.
- Desktop means the JVM artifact, not a Kotlin/Native desktop target or an official UI/application
  shell. Recorded evidence includes a macOS-hosted JVM run and a defined Ubuntu CI gate, but no
  Windows host gate; Linux/Windows remain JVM-compatible rather than equally validated environments.
- Backend Gateway v1 is defined and build-locally verified, but no Gateway has been deployed or
  subjected to a production penetration/operations review.
- Portable Web Search requires a host-provided `WebSearchBackend`; Magrathea does not ship a search
  account or default vendor. OpenAI, Gemini, and Anthropic hosted search/grounding formats are not
  implemented as aliases for the portable function Tool. Browser hosts must execute credentialed
  search behind an application server boundary.
- MCP support is an optional Tool adapter, not an automatic import of every server capability.
  Resources, Prompts, Sampling, Roots, Elicitation, and experimental Tasks are not enabled by the
  Agent composition. Streamable HTTP supports caller-supplied static headers, but the SDK does not
  yet implement the MCP HTTP OAuth 2.1 discovery/authorization flow. JVM stdio is available only
  when the host explicitly approves and launches the local process.

## Validation gaps

- Gemini has controlled JVM live evidence. OpenAI Responses and Anthropic Messages have controlled
  compatible-service JVM evidence through OpenRouter, including Responses `input_file` and Messages
  PDF document runs; neither protocol has been exercised directly against its vendor endpoint.
  OpenAI Chat Completions currently has deterministic request, codec, transport, and Runtime
  coverage but no controlled remote run.
- The recorded 2026-07-12 SM-S9180/API 36 Android physical-device fixture covers Keystore,
  force-stop/new-process Room restore and corruption, default Android HTTP, Gemini official-shape
  fixture parsing, and socket-level cancellation. Current instrumentation compilation was
  reverified on 2026-07-26, but this tree has not been rerun on the device. The fixture is not a
  live-provider request and does not replace
  a broader OEM/API, cold-reboot/backup, network-transition, or production sign-off matrix. iOS
  physical-device Keychain/ARC/Instruments evidence remains external.
- Automated browser evidence uses Playwright Chromium, Firefox, and WebKit. WebKit is a useful engine
  proxy but is not the Safari application; actual Chrome/Safari application and mobile-browser runs
  remain manual/external gates.
- The production SBOM, fixed-version policy, license/mutation gates, wrapper checksum, action pins,
  and a local OSV scan have build-local evidence. The committed
  workflows do not by themselves prove a successful remote run; a local advisory result is not a
  vulnerability certification.
- Deterministic JVM and one-run Android baselines exist in `docs/performance-baseline.md` and
  `docs/android-device-baseline.md`. The Android run records force-stop/new-process restore,
  socket-observed cancellation, Room latency, PSS, and heap on one device. OS-initiated process death,
  reboot/backup, iOS ARC, device breadth, statistically controlled performance, penetration review,
  and production security sign-off remain external gates.

## Distribution gaps

- Signed Maven artifacts are published to GitHub Packages. GitHub requires authenticated package
  reads, including for public repositories; anonymous Maven consumption remains a Maven Central
  release concern.
- The 88-coordinate rollback contract is a loopback rehearsal. An actual rollback still requires a
  release owner and a verified previous application pin.
- The JS/TypeScript Web archive is build-local and no task publishes it to npm.
- An annotated version tag triggers signed GitHub Packages publication and a GitHub Release only
  after the complete release gate and remote coordinate verification pass. Tag creation and Gateway
  deployment remain explicit maintainer actions.

## Alpha stability policy

Alpha releases may change public APIs, persistence formats, or Gateway contracts. Every intentional
change must update the relevant ABI dump, format version and fixtures, release notes, samples, and
cross-platform tests. Upgrade guarantees apply only when they are explicitly documented for a
published version.

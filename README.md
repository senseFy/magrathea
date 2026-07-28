# Magrathea

English | [简体中文](README.zh-CN.md)

Magrathea is a Kotlin Multiplatform agent runtime for building stateful chatbot and agent
applications on Android, JVM/Desktop, iOS, and Web.

It provides the agent loop, Provider contracts, tools, persistence, and lifecycle APIs. Applications
bring their own UI and choose only the integrations they need.

> [!NOTE]
> `0.1.0-alpha.1` is the first public Alpha. Its APIs and persisted formats may evolve before the
> stable line.

## Modules

| Layer | Modules |
|---|---|
| Kernel | `magrathea-core`, `magrathea-provider-api` |
| Runtime | `magrathea-runtime` |
| Agent APIs | `magrathea-chatbot`, `magrathea-policy`, `magrathea-mcp` |
| Providers | `magrathea-provider-gemini`, `magrathea-provider-openai`, `magrathea-provider-anthropic` |
| Data and credentials | `magrathea-storage-room`, `magrathea-storage-web`, `magrathea-credentials` |
| Browser Gateway | `magrathea-gateway-protocol`, `magrathea-provider-gateway`, `magrathea-gateway-server`, `magrathea-web-client` |

These are 16 logical modules. Kotlin Multiplatform publication variants are generated from them,
and consumers declare the logical module coordinates they need.

## Capabilities

- Streaming agent turns with tools, retry, cancellation, checkpoints, resume, and hard limits.
- Token-aware semantic context compaction while preserving authoritative full history.
- Gemini Interactions, OpenAI Responses/Chat Completions, and Anthropic Messages reference
  adapters, plus a public custom Provider SPI.
- Provider-neutral chatbot sessions with per-session Provider/model selection and attachments.
- Optional MCP, portable Web Search, and cross-model X Search tools.
- Room and IndexedDB persistence, protected mobile credentials, and a browser-safe Backend Gateway.

Public protocols and persisted formats are strict, versioned contracts. Unsupported shapes fail
closed.

## Try it

Run the deterministic, fully offline JVM sample with JDK 17:

```bash
git clone https://github.com/senseFy/magrathea.git
cd magrathea
./gradlew verifyJvmChatSample
```

Configure the authenticated [GitHub Packages repository](docs/publishing.md#consume), then add only
the modules your application needs:

```kotlin
dependencies {
    implementation("saien.magrathea:magrathea-runtime:0.1.0-alpha.1")
    implementation("saien.magrathea:magrathea-chatbot:0.1.0-alpha.1")
    implementation("saien.magrathea:magrathea-provider-openai:0.1.0-alpha.1")
}
```

See the [Provider-neutral composition guide](docs/architecture.md#provider-neutral-runtime-and-chatbot-facade)
and the [samples](samples). The complete signed bundle and supply-chain evidence are attached to the
[`v0.1.0-alpha.1` release](https://github.com/senseFy/magrathea/releases/tag/v0.1.0-alpha.1).

## Platforms

| Platform | Support |
|---|---|
| Android | KMP artifacts, minSdk 24 |
| JVM/Desktop | JVM embedding; the host owns UI, packaging, updates, and credentials |
| iOS | Device and Simulator KMP artifacts |
| Browser JS | Gateway-backed client |
| Browser Wasm | Experimental Gateway-backed preview |

Desktop support is delivered through JVM embedding. Detailed evidence and limitations are tracked
in [Verification Status](docs/verification-status.md) and [Known Issues](docs/known-issues.md).

## Documentation

- [Architecture](docs/architecture.md)
- [Providers](docs/providers.md)
- [Public API overview](docs/api-overview.md)
- [Samples](samples)
- [Documentation index](docs/README.md)

## Development

```bash
make help
./gradlew verifySdkQuick
./gradlew clean verifySdkRelease
```

Platform-specific gates and contribution requirements are documented in
[CONTRIBUTING.md](CONTRIBUTING.md).

## Security and license

Never report credentials, private conversation data, or vulnerability details in a public issue.
Follow [SECURITY.md](SECURITY.md) and the [Code of Conduct](CODE_OF_CONDUCT.md).

Magrathea is licensed under the [MIT License](LICENSE).

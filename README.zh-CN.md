# Magrathea

[English](README.md) | 简体中文

Magrathea 是一个 Kotlin Multiplatform Agent Runtime，用于在 Android、JVM/Desktop、iOS 和 Web
上构建有状态的 chatbot 与 Agent 应用。

它提供 Agent loop、Provider 契约、Tool、持久化及生命周期 API。应用自行提供 UI，并只组合需要的
集成能力。

> [!NOTE]
> 当前代码版本为 `0.1.0-alpha.11`；可用发行版以 GitHub Releases 为准。在进入 <!-- x-release-please-version -->
> 稳定版本前，API 与持久化格式仍可能演进。

## 模块

| 层级 | 模块 |
|---|---|
| Kernel | `magrathea-core`、`magrathea-provider-api` |
| Runtime | `magrathea-runtime` |
| Agent API | `magrathea-chatbot`、`magrathea-policy`、`magrathea-mcp` |
| Provider | `magrathea-provider-gemini`、`magrathea-provider-openai`、`magrathea-provider-anthropic` |
| 数据与凭证 | `magrathea-storage-room`、`magrathea-storage-web`、`magrathea-credentials` |
| Browser Gateway | `magrathea-gateway-protocol`、`magrathea-provider-gateway`、`magrathea-gateway-server`、`magrathea-web-client` |

以上是 16 个逻辑模块。Kotlin Multiplatform 会从中生成目标平台变体，consumer 只需声明所需的
逻辑模块坐标。

## 能力

- 支持 Tool、retry、cancel、checkpoint、resume 与硬性资源限制的流式 Agent turn。
- 支持应用切后台与进程丢失后的可恢复中断，并对 Tool 重放默认采用 fail-closed 策略。
- 保留权威完整历史的 token-aware 语义上下文压缩。
- Gemini Interactions、OpenAI Responses/Chat Completions、Anthropic Messages 参考适配器，以及
  公开的自定义 Provider SPI。
- Provider-neutral reasoning 偏好会根据模型声明的能力进行校验，并仅在适配器边界
  映射为确切的 Provider 控制。
- 支持会话级 Provider/model 选择与附件的 Provider-neutral chatbot session。
- 可选的 MCP、通用 Web/Image Search 与跨模型 X Search Tool。
- Room 与 IndexedDB 持久化、移动端安全凭证，以及面向浏览器的 Backend Gateway。

公共协议与持久化格式是严格的版本化契约；不受支持的数据形态会直接失败。

## 快速体验

使用 JDK 17 运行确定性的、完全离线的 JVM sample：

```bash
git clone https://github.com/senseFy/magrathea.git
cd magrathea
./gradlew verifyJvmChatSample
```

配置需要认证的 [GitHub Packages 仓库](docs/publishing.md#consume)后，按需添加模块：

```kotlin
dependencies {
    implementation("saien.magrathea:magrathea-runtime:0.1.0-alpha.11") // x-release-please-version
    implementation("saien.magrathea:magrathea-chatbot:0.1.0-alpha.11") // x-release-please-version
    implementation("saien.magrathea:magrathea-provider-openai:0.1.0-alpha.11") // x-release-please-version
}
```

完整组合方式参见 [Provider-neutral 构建指南](docs/architecture.md#provider-neutral-runtime-and-chatbot-facade)
与 [samples](samples)。Release 产物与供应链材料：
[Release](https://github.com/senseFy/magrathea/releases/tag/v0.1.0-alpha.11)。 <!-- x-release-please-version -->

## 平台

| 平台 | 支持情况 |
|---|---|
| Android | KMP 产物，minSdk 24 |
| JVM/Desktop | JVM embed；UI、打包、更新与凭证由宿主负责 |
| iOS | Device 与 Simulator KMP 产物 |
| Browser JS | Gateway-backed client |
| Browser Wasm | 实验性的 Gateway-backed preview |

Desktop 支持通过 JVM embedding 提供。平台限制与外部验证缺口维护在
[已知问题](docs/known-issues.md)中。

## 文档

- [架构](docs/architecture.md)
- [Provider 配置](docs/providers.md)
- [公共 API 概览](docs/api-overview.md)
- [中断与恢复](docs/recovery.md)
- [Samples](samples)
- [文档索引](docs/README.md)

## 开发

```bash
make help
./gradlew verifySdkQuick
./gradlew clean verifySdkRelease
```

平台 Gate 与贡献要求参见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 安全与许可证

不要在公开 issue 中提交凭证、私人对话数据或漏洞细节。请遵循
[SECURITY.md](SECURITY.md)与[行为准则](CODE_OF_CONDUCT.md)。

Magrathea 使用 [MIT License](LICENSE) 授权。

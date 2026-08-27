package saien.magrathea.tooling.provider.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import saien.magrathea.chatbot.ChatbotSessionConfiguration
import saien.magrathea.chatbot.DefaultChatbotRequestFactory
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.CredentialProvider
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.ReasoningCapabilities
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.TypedTool
import saien.magrathea.chatbot.ChatbotSnapshot
import saien.magrathea.chatbot.ChatbotStateObserver
import saien.magrathea.chatbot.ChatbotStatus
import saien.magrathea.chatbot.createChatbotClient
import saien.magrathea.provider.api.AnthropicAuthentication
import saien.magrathea.provider.api.AnthropicTransportConfig
import saien.magrathea.provider.anthropic.AnthropicProviderAdapter
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.GeminiTransportConfig
import saien.magrathea.provider.api.OpenAiAuthentication
import saien.magrathea.provider.api.OpenAiTransportConfig
import saien.magrathea.provider.api.OpenAiWireProtocol
import saien.magrathea.provider.api.OpenAiXSearchToolConfig
import saien.magrathea.provider.api.toProviderOptions
import saien.magrathea.provider.gemini.GeminiProviderAdapter
import saien.magrathea.provider.openai.OpenAiProviderAdapter
import saien.magrathea.provider.openai.OpenAiProviderProfile
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryAgentPersistence
import saien.magrathea.runtime.InMemoryToolRegistry
import saien.magrathea.storage.room.JvmMagratheaRoom
import saien.magrathea.storage.room.StoredRecordCorruptionReporter
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.time.Instant
import java.util.Base64

fun main(args: Array<String>) = runBlocking {
    val config = ProviderLiveHarnessConfig.from(args, System.getenv())
    val providerKey = config.provider
    val apiKey = config.apiKeyFor(providerKey)
    if (apiKey.isNullOrBlank()) {
        printMissingKey(providerKey)
        return@runBlocking
    }

    val credentialProvider = CredentialProvider { ref ->
        val value = config.apiKeyFor(ref.provider)
            ?: error("No credential configured for ${ref.provider}/${ref.profile}")
        ProviderCredential(value = value, endpoint = config.endpoint)
    }
    if (providerKey == "gemini" && config.scenario == "chat") {
        runFacadeChat(config, credentialProvider)
        return@runBlocking
    }
    val providerRegistry = InMemoryProviderRegistry(listOf(config.createProviderAdapter()))
    val tools = listOf(EchoTextTool(), ClockNowTool(), ReadFileSummaryTool(), FetchUrlTextTool())
    val persistence = InMemoryAgentPersistence()
    val runner = DefaultAgentRunner(
        providerRegistry = providerRegistry,
        toolRegistry = InMemoryToolRegistry(tools),
        persistence = persistence,
        credentialProvider = credentialProvider,
    )

    try {
        println(
            "[provider-live-harness] scenario=${config.scenario} provider=$providerKey model=${config.model} " +
                "maxTokens=${config.maxTokens} maxProviderRetries=${config.maxProviderRetries}",
        )
        when (config.scenario) {
            "chat" -> runScenario(runner, scenarioRequest(config, providerKey, chatPrompt(config)))
            "file" -> runScenario(
                runner,
                scenarioRequest(
                    config = config,
                    providerKey = providerKey,
                    prompt = filePrompt(config),
                    attachments = listOf(config.fileAttachment()),
                ),
            )
            "mixed-tools" -> runScenario(runner, scenarioRequest(config, providerKey, mixedToolsPrompt(config), toolDefinitions(tools)))
            "resume" -> runResumeScenario(runner, persistence, config, providerKey, tools)
            "x-search" -> runScenario(runner, scenarioRequest(config, providerKey, xSearchPrompt(config)))
            else -> error("Unknown scenario: ${config.scenario}")
        }
    } finally {
        providerRegistry.all().forEach { provider -> runCatching(provider::close) }
    }
}

private suspend fun runFacadeChat(
    config: ProviderLiveHarnessConfig,
    credentialProvider: CredentialProvider,
) {
    val databaseDirectory = Files.createTempDirectory("magrathea-provider-live-harness-").toFile()
    val stores = JvmMagratheaRoom.open(
        databasePath = databaseDirectory.resolve("chatbot.db").absolutePath,
        reporter = StoredRecordCorruptionReporter { },
    )
    val credentialRef = CredentialRef("gemini")
    val provider = GeminiProviderAdapter()
    val runner = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(),
        persistence = stores.persistence,
        credentialProvider = credentialProvider,
    )
    val client = createChatbotClient(
        runner = runner,
        requestFactory = DefaultChatbotRequestFactory(
            systemPrompt = "You are validating the Magrathea Core-driven multiplatform chatbot. Keep outputs concise.",
            configure = {
                copy(
                    engine = AgentEngineConfig(
                        provider = ProviderConfig(
                            maxTokens = config.maxTokens,
                        ),
                    ),
                )
            },
        ),
        persistence = stores.persistence,
        closeResources = {
            try {
                provider.close()
            } finally {
                stores.close()
            }
        },
    )
    try {
        println("[provider-live-harness] scenario=chat provider=gemini model=${config.model} facade=ChatbotClient")
        val session = client.createSession(
            ChatbotSessionConfiguration(
                model = ModelDescriptor("gemini", config.model, supportsStreaming = config.streaming),
                credentialRef = credentialRef,
            ),
        )
        val terminal = CompletableDeferred<ChatbotSnapshot>()
        val observation = session.observe(ChatbotStateObserver { snapshot ->
            if (snapshot.status.isTerminal()) terminal.complete(snapshot)
        })
        try {
            session.send(chatPrompt(config))
            val snapshot = withTimeout(HARNESS_TIMEOUT_MS) { terminal.await() }
            printFacadeSnapshot(snapshot)
            check(snapshot.status == ChatbotStatus.COMPLETED) {
                "Chatbot facade ended with ${snapshot.status.name.lowercase()}"
            }
            val history = client.history()
            check(history.size == 1 && history.single().sessionId == snapshot.sessionId) {
                "Chatbot facade did not persist the completed session"
            }
            println("[facade] history=${history.size} persisted=true")
        } finally {
            observation.cancel()
        }
    } finally {
        try {
            client.close()
        } finally {
            databaseDirectory.deleteRecursively()
        }
    }
}

private fun ChatbotStatus.isTerminal(): Boolean = when (this) {
    ChatbotStatus.COMPLETED,
    ChatbotStatus.FAILED,
    ChatbotStatus.CANCELLED,
    ChatbotStatus.INTERRUPTED,
    ChatbotStatus.RECOVERY_BLOCKED,
    -> true
    ChatbotStatus.IDLE, ChatbotStatus.RUNNING, ChatbotStatus.WAITING_FOR_TOOL -> false
}

private fun printFacadeSnapshot(snapshot: ChatbotSnapshot) {
    val textChars = snapshot.messages.sumOf { it.text.length }
    val reasoningChars = snapshot.messages.sumOf { message -> message.reasoning.sumOf { it.text.length } }
    val toolCalls = snapshot.messages.sumOf { it.toolCalls.size }
    val toolResults = snapshot.messages.sumOf { it.toolResults.size }
    println(
        "[facade] status=${snapshot.status.name.lowercase()} messages=${snapshot.messages.size} " +
            "textChars=$textChars reasoningChars=$reasoningChars toolCalls=$toolCalls toolResults=$toolResults",
    )
}

private suspend fun runScenario(runner: DefaultAgentRunner, request: AgentRequest) {
    var completionSeen = false
    runner.run(request).collect { event ->
        printEvent(event)
        requireSuccessfulProviderLiveEvent(event)
        if (event is AgentEvent.Completed) completionSeen = true
    }
    check(completionSeen) { "Provider live scenario ended without a completed event" }
}

private suspend fun runResumeScenario(
    runner: DefaultAgentRunner,
    persistence: InMemoryAgentPersistence,
    config: ProviderLiveHarnessConfig,
    providerKey: String,
    tools: List<saien.magrathea.core.ToolExecutor>,
) {
    val request = scenarioRequest(config, providerKey, resumePrompt(config), toolDefinitions(tools))
    var sessionId = request.sessionId
    var completionSeen = false
    runner.run(request).collect { event ->
        printEvent(event)
        requireSuccessfulProviderLiveEvent(event)
        if (event is AgentEvent.Started) sessionId = event.sessionId
        if (event is AgentEvent.Completed) completionSeen = true
    }
    val record = persistence.load(sessionId)
    val session = record?.snapshot
    val checkpoint = record?.checkpoint
    println("[resume] storedSession=${session != null} storedCheckpoint=${checkpoint != null} turn=${checkpoint?.turn}")
    if (!completionSeen) {
        runner.resume(sessionId).collect { event ->
            printEvent(event)
            requireSuccessfulProviderLiveEvent(event)
        }
    }
}

internal fun requireSuccessfulProviderLiveEvent(event: AgentEvent) {
    when (event) {
        is AgentEvent.Failed -> error("Provider live scenario failed (${event.code.name})")
        is AgentEvent.Cancelled -> error("Provider live scenario was cancelled")
        is AgentEvent.Interrupted ->
            error("Provider live scenario was interrupted (${event.interruption.reason.name})")
        is AgentEvent.RecoveryBlocked ->
            error("Provider live scenario recovery was blocked (${event.reason.name})")
        else -> Unit
    }
}

private fun scenarioRequest(
    config: ProviderLiveHarnessConfig,
    providerKey: String,
    prompt: String,
    tools: List<ToolDefinition> = emptyList(),
    attachments: List<AttachmentPart> = emptyList(),
): AgentRequest {
    return AgentRequest(
        systemPrompt = "You are validating the Magrathea Kotlin Multiplatform agent runtime. Follow tool schemas exactly and keep outputs concise.",
        messages = listOf(
            AgentMessage(
                role = MessageRole.USER,
                parts = listOf(TextPart(prompt)) + attachments,
            ),
        ),
        model = ModelDescriptor(
            provider = providerKey,
            model = config.model,
            reasoningCapabilities = ReasoningCapabilities().takeIf { config.scenario == "x-search" },
            supportsStreaming = config.streaming,
        ),
        tools = tools,
        metadata = buildJsonObject {
            put("scenario", JsonPrimitive(config.scenario))
            put("createdAt", JsonPrimitive(Instant.now().toString()))
        },
        engine = AgentEngineConfig(
            provider = ProviderConfig(
                maxTokens = config.maxTokens,
                credentialRef = CredentialRef(provider = providerKey),
                options = providerOptions(config),
            ),
            runtime = RuntimeConfig(
                maxTurns = 6,
                maxProviderRetries = config.maxProviderRetries,
            ),
        ),
    )
}

private fun ProviderLiveHarnessConfig.createProviderAdapter() = when (provider) {
    "gemini" -> GeminiProviderAdapter()
    "anthropic" -> AnthropicProviderAdapter()
    "openai" -> OpenAiProviderAdapter(profile = OpenAiProviderProfile.openAi())
    "openrouter" -> OpenAiProviderAdapter(profile = OpenAiProviderProfile.openRouter())
    "xai" -> OpenAiProviderAdapter(profile = OpenAiProviderProfile.xAi())
    else -> error("Unknown provider key: $provider")
}

private fun providerOptions(config: ProviderLiveHarnessConfig) = when (config.provider) {
    "gemini" -> GeminiTransportConfig(thinkingSummaries = "auto").toProviderOptions()
    "openai", "openrouter", "xai" -> OpenAiTransportConfig(
        protocol = requireNotNull(config.openAiProtocol).wireProtocol,
        authentication = when (config.authentication) {
            null, ProviderLiveAuthentication.BEARER -> OpenAiAuthentication.BEARER
            ProviderLiveAuthentication.API_KEY -> OpenAiAuthentication.API_KEY
            ProviderLiveAuthentication.X_API_KEY ->
                error("OpenAI-family providers do not support x-api-key authentication")
        },
        hostedTools = if (config.scenario == "x-search") {
            listOf(OpenAiXSearchToolConfig())
        } else {
            emptyList()
        },
    ).toProviderOptions()
    "anthropic" -> AnthropicTransportConfig(
        authentication = when (config.authentication) {
            null, ProviderLiveAuthentication.X_API_KEY -> AnthropicAuthentication.X_API_KEY
            ProviderLiveAuthentication.BEARER -> AnthropicAuthentication.BEARER
            ProviderLiveAuthentication.API_KEY -> error("Anthropic does not support api-key authentication")
        },
    ).toProviderOptions()
    else -> error("Unknown provider key: ${config.provider}")
}

private fun toolDefinitions(tools: List<saien.magrathea.core.ToolExecutor>) = tools.map { it.definition }

private fun printEvent(event: AgentEvent) {
    formatProviderLiveEvent(event).forEach(::println)
}

internal fun formatProviderLiveEvent(event: AgentEvent): List<String> = when (event) {
    is AgentEvent.Started -> listOf(
        "[event] started session=${event.sessionId.value} run=${event.runId.value}",
    )
    is AgentEvent.TurnStarted -> listOf("[event] turn=${event.turn}")
    is AgentEvent.ContextTransformed -> listOf("[event] context messages=${event.messageCount}")
    is AgentEvent.MessageEmitted -> buildList {
            val text = event.message.parts.filterIsInstance<TextPart>().joinToString(" | ") { it.text }
            val reasoning = event.message.parts.filterIsInstance<saien.magrathea.core.ReasoningPart>().joinToString(" | ") { it.text }
            val toolCalls = event.message.parts.filterIsInstance<saien.magrathea.core.ToolCallPart>()
            val toolResults = event.message.parts.filterIsInstance<saien.magrathea.core.ToolResultPart>()
            add("[event] message role=${event.message.role} stop=${event.message.stopReason} parts=${event.message.parts.size} textChars=${text.length} reasoningChars=${reasoning.length} toolCalls=${toolCalls.size} toolResults=${toolResults.size}")
            if (toolCalls.isNotEmpty()) {
                toolCalls.forEach { toolCall ->
                    add("[event] message-tool-call id=${toolCall.toolCallId} name=${toolCall.toolName} partial=${toolCall.partial} thoughtSig=${toolCall.thoughtSignature != null} argsChars=${toolCall.arguments.toString().length}")
                }
            }
            if (toolResults.isNotEmpty()) {
                toolResults.forEach { toolResult ->
                    add("[event] message-tool-result id=${toolResult.toolCallId} name=${toolResult.toolName} error=${toolResult.isError} resultChars=${toolResult.result.toString().length}")
                }
            }
            if (event.message.metadata.isNotEmpty()) {
                add("[event] message-metadata-keys=${event.message.metadata.keys.sorted()}")
            }
        }
    is AgentEvent.ToolRequested -> listOf(
        "[event] tool-request name=${event.toolCall.toolName} argsChars=${event.toolCall.arguments.toString().length}",
    )
    is AgentEvent.ToolCompleted -> listOf(
        "[event] tool-complete name=${event.result.toolName} error=${event.result.isError} resultChars=${event.result.result.toString().length}",
    )
    is AgentEvent.RetryScheduled -> listOf("[event] retry attempt=${event.attempt} code=${event.code.name}")
    is AgentEvent.CheckpointSaved -> listOf(
        "[event] checkpoint turn=${event.checkpoint.turn} stop=${event.checkpoint.state.stopReason} pendingToolCalls=${event.checkpoint.state.pendingToolCalls.size} messages=${event.checkpoint.state.messages.size}",
    )
    is AgentEvent.Completed -> buildList {
            add(
                "[event] completed stop=${event.state.stopReason} messages=${event.state.messages.size} " +
                    "pendingToolCalls=${event.state.pendingToolCalls.size} " +
                    "inputTokens=${event.state.usage.inputTokens ?: "unknown"} " +
                    "outputTokens=${event.state.usage.outputTokens ?: "unknown"} " +
                    "reasoningTokens=${event.state.usage.reasoningTokens ?: "unknown"}",
            )
            event.state.messages.forEachIndexed { index, message ->
                val textChars = message.parts.filterIsInstance<TextPart>().sumOf { it.text.length }
                val reasoningChars = message.parts.filterIsInstance<saien.magrathea.core.ReasoningPart>().sumOf { it.text.length }
                val toolCalls = message.parts.count { it is saien.magrathea.core.ToolCallPart }
                val toolResults = message.parts.count { it is saien.magrathea.core.ToolResultPart }
                add("[event] final-message index=$index role=${message.role} stop=${message.stopReason} parts=${message.parts.size} textChars=$textChars reasoningChars=$reasoningChars toolCalls=$toolCalls toolResults=$toolResults metadataKeys=${message.metadata.keys.sorted()}")
            }
        }
    is AgentEvent.Failed -> listOf("[event] failed code=${event.code.name}")
    is AgentEvent.Cancelled -> listOf("[event] cancelled")
    is AgentEvent.Interrupted -> listOf(
        "[event] interrupted reason=${event.interruption.reason.name} " +
            "turn=${event.state.turn} status=${event.state.status.name}",
    )
    is AgentEvent.RecoveryBlocked -> listOf(
        "[event] recovery-blocked reason=${event.reason.name}",
    )
}

private fun chatPrompt(config: ProviderLiveHarnessConfig): String =
    config.prompt ?: "Give a short acknowledgement that the Magrathea Provider live harness is working."

private fun filePrompt(config: ProviderLiveHarnessConfig): String =
    config.prompt ?: "Read the attached file and reply with only its short identifying sentence."

private fun mixedToolsPrompt(config: ProviderLiveHarnessConfig): String =
    config.prompt ?: "Use tools when helpful. 1) Call clock_now. 2) Call echo_text with a short phrase. 3) Call fetch_url_text for https://example.com. 4) Summarize the findings in under 120 words."

private fun resumePrompt(config: ProviderLiveHarnessConfig): String =
    config.prompt ?: "Use clock_now, then echo_text with the current stage name, then summarize what was done so the resume scenario can verify stored checkpoint data."

private fun xSearchPrompt(config: ProviderLiveHarnessConfig): String =
    config.prompt ?: "Search X for Kimi 3.0 and answer in one concise sentence with citations."

private fun printMissingKey(provider: String) {
    val env = requireNotNull(HARNESS_PROVIDER_SPECS[provider]?.environmentVariable)
    println("[provider-live-harness] Missing API key for provider '$provider'. Set $env and rerun.")
}

data class ProviderLiveHarnessConfig(
    val scenario: String,
    val provider: String,
    val model: String,
    val streaming: Boolean,
    val prompt: String?,
    val maxTokens: Int,
    val maxProviderRetries: Int,
    val endpoint: String?,
    val authentication: ProviderLiveAuthentication?,
    val openAiProtocol: ProviderLiveOpenAiProtocol?,
    val filePath: String?,
    val env: Map<String, String>,
) {
    fun apiKeyFor(provider: String): String? =
        HARNESS_PROVIDER_SPECS[provider.lowercase()]?.let { spec -> env[spec.environmentVariable] }

    override fun toString(): String =
        "ProviderLiveHarnessConfig(scenario=$scenario, provider=$provider, model=$model, " +
            "streaming=$streaming, promptConfigured=${prompt != null}, endpointConfigured=${endpoint != null}, " +
            "fileConfigured=${filePath != null}, " +
            "maxTokens=$maxTokens, maxProviderRetries=$maxProviderRetries, authentication=$authentication, " +
            "openAiProtocol=$openAiProtocol, " +
            "environmentKeys=${env.keys.sorted()})"

    companion object {
        fun from(args: Array<String>, env: Map<String, String>): ProviderLiveHarnessConfig {
            val values = parseArgs(args)
            val provider = (values["provider"] ?: env["MAGRATHEA_PROVIDER"] ?: "gemini").lowercase()
            val providerSpec = requireNotNull(HARNESS_PROVIDER_SPECS[provider]) {
                "Unknown provider key: $provider"
            }
            val scenario = values["scenario"] ?: env["MAGRATHEA_SCENARIO"] ?: "chat"
            require(scenario in HARNESS_SCENARIOS) {
                "Unknown scenario: $scenario"
            }
            val maxTokens = parseNonNegativeInt(
                name = "maxTokens",
                value = values["maxTokens"] ?: env["MAGRATHEA_MAX_TOKENS"],
                default = when (scenario) {
                    "chat" -> 128
                    "x-search" -> 8_192
                    else -> 256
                },
                allowZero = false,
            )
            val maxProviderRetries = parseNonNegativeInt(
                name = "maxProviderRetries",
                value = values["maxProviderRetries"] ?: env["MAGRATHEA_MAX_PROVIDER_RETRIES"],
                default = 0,
                allowZero = true,
            )
            val endpoint = (values["endpoint"] ?: env["MAGRATHEA_ENDPOINT"])
                ?.also(::requireSecureEndpoint)
            val authentication = (values["authentication"] ?: env["MAGRATHEA_AUTHENTICATION"])
                ?.let(ProviderLiveAuthentication::parse)
            val requestedOpenAiProtocol = (values["protocol"] ?: env["MAGRATHEA_OPENAI_PROTOCOL"])
                ?.let(ProviderLiveOpenAiProtocol::parse)
            require(provider in OPENAI_FAMILY_PROVIDERS || requestedOpenAiProtocol == null) {
                "The protocol setting is available only for OpenAI-family providers"
            }
            val openAiProtocol = if (provider in OPENAI_FAMILY_PROVIDERS) {
                requestedOpenAiProtocol ?: when (provider) {
                    "openrouter" -> ProviderLiveOpenAiProtocol.CHAT_COMPLETIONS
                    else -> ProviderLiveOpenAiProtocol.RESPONSES
                }
            } else {
                null
            }
            if (scenario == "x-search") {
                require(provider == "xai" && openAiProtocol == ProviderLiveOpenAiProtocol.RESPONSES) {
                    "The x-search scenario requires the xAI Provider profile with the Responses protocol"
                }
            }
            val filePath = values["file"] ?: env["MAGRATHEA_FILE"]
            if (scenario == "file") require(!filePath.isNullOrBlank()) {
                "The file scenario requires file=/absolute/path"
            }
            validateAuthentication(provider, endpoint, authentication)
            return ProviderLiveHarnessConfig(
                scenario = scenario,
                provider = provider,
                model = values["model"] ?: env["MAGRATHEA_MODEL"] ?: providerSpec.defaultModel,
                streaming = (values["streaming"] ?: env["MAGRATHEA_STREAMING"] ?: "false").equals("true", ignoreCase = true),
                prompt = values["prompt"] ?: env["MAGRATHEA_PROMPT"],
                maxTokens = maxTokens,
                maxProviderRetries = maxProviderRetries,
                endpoint = endpoint,
                authentication = authentication,
                openAiProtocol = openAiProtocol,
                filePath = filePath,
                env = env,
            )
        }

        private fun parseArgs(args: Array<String>): Map<String, String> {
            return args.mapNotNull { arg ->
                val index = arg.indexOf('=')
                if (index <= 0) null else arg.substring(0, index) to arg.substring(index + 1)
            }.toMap()
        }
    }
}

private fun ProviderLiveHarnessConfig.fileAttachment(): AttachmentPart {
    val file = File(requireNotNull(filePath))
    require(file.isAbsolute && file.isFile && file.length() in 1..MAX_LIVE_FILE_BYTES) {
        "The live harness file must be an absolute, non-empty file within the size limit"
    }
    val mimeType = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "txt" -> "text/plain"
        else -> error("The live harness file type is not supported")
    }
    val encoded = Base64.getEncoder().encodeToString(file.readBytes())
    return AttachmentPart(
        uri = "data:$mimeType;base64,$encoded",
        mimeType = mimeType,
        fileName = file.name,
    )
}

private fun parseNonNegativeInt(
    name: String,
    value: String?,
    default: Int,
    allowZero: Boolean,
): Int {
    val parsed = value?.toIntOrNull() ?: if (value == null) default else {
        throw IllegalArgumentException("$name must be an integer")
    }
    require(if (allowZero) parsed >= 0 else parsed > 0) {
        "$name must be ${if (allowZero) "non-negative" else "greater than zero"}"
    }
    return parsed
}

enum class ProviderLiveAuthentication(val argument: String) {
    BEARER("bearer"),
    API_KEY("api-key"),
    X_API_KEY("x-api-key");

    companion object {
        fun parse(value: String): ProviderLiveAuthentication = entries.singleOrNull {
            it.argument == value.lowercase()
        } ?: throw IllegalArgumentException("Unknown Provider authentication: $value")
    }
}

enum class ProviderLiveOpenAiProtocol(
    val argument: String,
    val wireProtocol: OpenAiWireProtocol,
) {
    RESPONSES("responses", OpenAiWireProtocol.RESPONSES),
    CHAT_COMPLETIONS("chat-completions", OpenAiWireProtocol.CHAT_COMPLETIONS);

    companion object {
        fun parse(value: String): ProviderLiveOpenAiProtocol = entries.singleOrNull {
            it.argument == value.lowercase()
        } ?: throw IllegalArgumentException("Unknown OpenAI wire protocol: $value")
    }
}

private fun requireSecureEndpoint(value: String) {
    val uri = runCatching { URI(value) }.getOrElse {
        throw IllegalArgumentException("Provider live endpoint must be an absolute HTTPS URL")
    }
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "Provider live endpoint must be an absolute HTTPS URL"
    }
    require(uri.userInfo == null) { "Provider live endpoint must not contain user info" }
}

private fun validateAuthentication(
    provider: String,
    endpoint: String?,
    authentication: ProviderLiveAuthentication?,
) {
    if (authentication == null) return
    require(endpoint != null) { "Explicit Provider authentication requires an explicit endpoint" }
    when (provider) {
        in OPENAI_FAMILY_PROVIDERS ->
            require(
                authentication in
                    setOf(
                        ProviderLiveAuthentication.BEARER,
                        ProviderLiveAuthentication.API_KEY,
                    ),
            ) {
                "OpenAI-family authentication must be bearer or api-key"
            }
        "anthropic" -> require(authentication in setOf(ProviderLiveAuthentication.X_API_KEY, ProviderLiveAuthentication.BEARER)) {
            "Anthropic authentication must be x-api-key or bearer"
        }
        else -> throw IllegalArgumentException("Provider $provider does not expose authentication overrides")
    }
}

private data class ProviderLiveSpec(
    val environmentVariable: String,
    val defaultModel: String,
)

private val HARNESS_PROVIDER_SPECS = mapOf(
    "gemini" to ProviderLiveSpec("MAGRATHEA_GEMINI_API_KEY", "gemini-2.5-flash"),
    "openai" to ProviderLiveSpec("MAGRATHEA_OPENAI_API_KEY", "gpt-4o-mini"),
    "openrouter" to ProviderLiveSpec("MAGRATHEA_OPENROUTER_API_KEY", "openai/gpt-4o-mini"),
    "xai" to ProviderLiveSpec("MAGRATHEA_XAI_API_KEY", "grok-4.5"),
    "anthropic" to ProviderLiveSpec("MAGRATHEA_ANTHROPIC_API_KEY", "claude-3-5-sonnet-latest"),
)
private val OPENAI_FAMILY_PROVIDERS = setOf("openai", "openrouter", "xai")
private val HARNESS_SCENARIOS = setOf("chat", "file", "mixed-tools", "resume", "x-search")
private const val HARNESS_TIMEOUT_MS = 120_000L
private const val MAX_LIVE_FILE_BYTES = 10L * 1_024L * 1_024L

@Serializable
private data class EchoArgs(val text: String)

private class EchoTextTool : TypedTool<EchoArgs>(EchoArgs.serializer()) {
    override val definition: ToolDefinition = ToolDefinition(
        name = "echo_text",
        description = "Echoes the provided text for deterministic tool-call validation.",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("text", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("text")) })
        },
    )

    override suspend fun executeTyped(request: ToolExecutionRequest, args: EchoArgs): ToolExecutionResult {
        return ToolExecutionResult(request.toolCall.toolCallId, definition.name, JsonPrimitive(args.text))
    }
}

@Serializable
private data class ClockArgs(val timezone: String = "UTC")

private class ClockNowTool : TypedTool<ClockArgs>(ClockArgs.serializer()) {
    override val definition: ToolDefinition = ToolDefinition(
        name = "clock_now",
        description = "Returns the current timestamp in ISO-8601 format.",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("timezone", buildJsonObject { put("type", JsonPrimitive("string")) })
            })
        },
    )

    override suspend fun executeTyped(request: ToolExecutionRequest, args: ClockArgs): ToolExecutionResult {
        return ToolExecutionResult(request.toolCall.toolCallId, definition.name, JsonPrimitive(Instant.now().toString()))
    }
}

@Serializable
private data class FileArgs(val path: String)

private class ReadFileSummaryTool : TypedTool<FileArgs>(FileArgs.serializer()) {
    override val definition: ToolDefinition = ToolDefinition(
        name = "read_file_summary",
        description = "Reads a local file and returns a short preview for harness validation.",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("path", buildJsonObject { put("type", JsonPrimitive("string")) })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("path")) })
        },
    )

    override suspend fun executeTyped(request: ToolExecutionRequest, args: FileArgs): ToolExecutionResult {
        val file = File(args.path)
        val summary = if (!file.exists()) {
            "missing:${args.path}"
        } else {
            file.readLines().take(12).joinToString("\n").take(1_200)
        }
        return ToolExecutionResult(request.toolCall.toolCallId, definition.name, JsonPrimitive(summary))
    }
}

@Serializable
private data class UrlArgs(val url: String)

private class FetchUrlTextTool : TypedTool<UrlArgs>(UrlArgs.serializer()) {
    private val client = OkHttpClient()

    override val definition: ToolDefinition = ToolDefinition(
        name = "fetch_url_text",
        description = "Fetches a URL and returns the first part of the response body as plain text.",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("url", buildJsonObject { put("type", JsonPrimitive("string")) })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("url")) })
        },
        timeoutMs = 15_000,
    )

    override suspend fun executeTyped(request: ToolExecutionRequest, args: UrlArgs): ToolExecutionResult {
        return try {
            val body = client.newCall(Request.Builder().url(args.url).get().build()).execute().use { response ->
                response.body?.string().orEmpty().replace(Regex("\\s+"), " ").take(1_200)
            }
            ToolExecutionResult(request.toolCall.toolCallId, definition.name, JsonPrimitive(body))
        } catch (t: Throwable) {
            ToolExecutionResult(request.toolCall.toolCallId, definition.name, JsonPrimitive(t.message ?: t.javaClass.name), true)
        }
    }
}

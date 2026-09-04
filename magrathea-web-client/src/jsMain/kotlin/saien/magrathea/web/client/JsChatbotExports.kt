@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package saien.magrathea.web.client

import kotlin.js.JsExport
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.promise
import saien.magrathea.chatbot.ChatbotClient
import saien.magrathea.chatbot.ChatbotHistoryItem
import saien.magrathea.chatbot.ChatbotObservation
import saien.magrathea.chatbot.ChatbotSession
import saien.magrathea.chatbot.ChatbotSessionConfiguration
import saien.magrathea.chatbot.ChatbotSnapshot
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ReasoningCapabilities
import saien.magrathea.core.ReasoningEffort
import saien.magrathea.core.ReasoningPreference
import saien.magrathea.provider.gateway.GatewaySessionHeaders
import saien.magrathea.provider.gateway.GatewaySessionHeadersProvider

@JsExport
fun createMagratheaWebChatbot(
    gatewayBaseUrl: String,
    databaseName: String,
    authorizationProvider: (() -> String?)? = null,
    csrfTokenProvider: (() -> String?)? = null,
): MagratheaWebChatbot {
    val client = createWebChatbotClient(
        configuration = WebChatbotConfiguration(
            gatewayBaseUrl = gatewayBaseUrl,
            databaseName = databaseName,
        ),
        sessionHeadersProvider = GatewaySessionHeadersProvider {
            GatewaySessionHeaders(
                authorization = authorizationProvider?.invoke(),
                csrfToken = csrfTokenProvider?.invoke(),
            )
        },
    )
    return MagratheaWebChatbot(client)
}

@JsExport
class MagratheaWebChatbot @JsExport.Ignore internal constructor(
    private val client: ChatbotClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var closePromise: Promise<Unit>? = null

    fun createSession(
        model: MagratheaWebChatModel,
        reasoningPreference: String = "auto",
    ): Promise<MagratheaWebChatSession> = scope.promise {
        MagratheaWebChatSession(
            client.createSession(model.toConfiguration(reasoningPreference)),
            scope,
        )
    }

    fun resumeSession(sessionId: String): Promise<MagratheaWebChatSession> = scope.promise {
        MagratheaWebChatSession(client.resumeSession(sessionId), scope)
    }

    fun restoreSession(sessionId: String): Promise<MagratheaWebChatSession> = scope.promise {
        MagratheaWebChatSession(client.restoreSession(sessionId), scope)
    }

    fun history(): Promise<Array<MagratheaWebChatHistoryItem>> = scope.promise {
        client.history().map(ChatbotHistoryItem::toJsHistoryItem).toTypedArray()
    }

    fun close(): Promise<Unit> {
        closePromise?.let { return it }
        return CoroutineScope(Dispatchers.Default).promise {
            try {
                client.close()
            } finally {
                scope.cancel()
            }
        }.also { closePromise = it }
    }
}

@JsExport
class MagratheaWebChatSession @JsExport.Ignore internal constructor(
    private val session: ChatbotSession,
    private val scope: CoroutineScope,
) {
    fun snapshot(): MagratheaWebChatSnapshot = session.snapshot().toJsSnapshot()

    fun observe(observer: (MagratheaWebChatSnapshot) -> Unit): MagratheaWebChatObservation =
        MagratheaWebChatObservation(
            session.observe { observer(it.toJsSnapshot()) },
        )

    fun send(text: String): Promise<Unit> = scope.promise { session.send(text) }

    fun updateModel(model: MagratheaWebChatModel): Promise<Unit> = scope.promise {
        val currentPreference = session.snapshot().configuration.reasoningPreference
        val descriptor = model.toDescriptor()
        session.updateConfiguration(
            ChatbotSessionConfiguration(
                model = descriptor,
                reasoningPreference = normalizeReasoningPreferenceForModel(
                    currentPreference,
                    descriptor,
                ),
            ),
        )
    }

    fun updateReasoningPreference(reasoningPreference: String): Promise<Unit> = scope.promise {
        val configuration = session.snapshot().configuration
        session.updateConfiguration(
            configuration.copy(
                reasoningPreference = reasoningPreference.toReasoningPreference(),
            ),
        )
    }

    fun cancel(): Promise<Unit> = scope.promise { session.cancel() }

    fun close(): Promise<Unit> = scope.promise { session.close() }
}

@JsExport
class MagratheaWebChatObservation @JsExport.Ignore internal constructor(
    private val observation: ChatbotObservation,
) {
    fun cancel() = observation.cancel()
}

@JsExport
class MagratheaWebChatSnapshot(
    val model: MagratheaWebChatModel,
    val reasoningPreference: String,
    val sessionId: String?,
    val status: String,
    val failure: String?,
    val isRunning: Boolean,
    val messages: Array<MagratheaWebChatMessage>,
    val inputTokens: Double?,
    val outputTokens: Double?,
    val reasoningTokens: Double?,
)

@JsExport
class MagratheaWebChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val textBlocks: Array<MagratheaWebChatTextBlock>,
    val reasoning: Array<MagratheaWebChatReasoningBlock>,
    val attachments: Array<MagratheaWebChatAttachment>,
    val toolCalls: Array<MagratheaWebChatToolCall>,
    val toolResults: Array<MagratheaWebChatToolResult>,
    val createdAtEpochMs: Double,
    val stopReason: String?,
)

@JsExport
class MagratheaWebChatTextBlock(
    val text: String,
    val phase: String?,
)

@JsExport
class MagratheaWebChatReasoningBlock(
    val text: String,
    val redacted: Boolean,
    val kind: String,
    val phase: String?,
)

@JsExport
class MagratheaWebChatAttachment(
    val uri: String,
    val mimeType: String,
)

@JsExport
class MagratheaWebChatToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val partial: Boolean,
)

@JsExport
class MagratheaWebChatToolResult(
    val id: String,
    val name: String,
    val text: String,
    val isError: Boolean,
    val citations: Array<MagratheaWebChatCitation>,
)

@JsExport
class MagratheaWebChatCitation(
    val title: String,
    val url: String,
    val snippet: String,
)

@JsExport
class MagratheaWebChatHistoryItem(
    val sessionId: String,
    val model: MagratheaWebChatModel,
    val reasoningPreference: String,
    val updatedAtEpochMs: Double,
    val status: String,
    val lastMessageText: String,
)

private fun ChatbotSnapshot.toJsSnapshot(): MagratheaWebChatSnapshot = MagratheaWebChatSnapshot(
    model = configuration.model.toJsModel(),
    reasoningPreference = configuration.reasoningPreference.toJsValue(),
    sessionId = sessionId,
    status = status.name.lowercase(),
    failure = failure?.name?.lowercase(),
    isRunning = isRunning,
    messages = messages.map { message ->
        MagratheaWebChatMessage(
            id = message.id,
            role = message.role.name.lowercase(),
            text = message.text,
            textBlocks = message.textBlocks.map { block ->
                MagratheaWebChatTextBlock(block.text, block.phase?.name?.lowercase())
            }.toTypedArray(),
            reasoning = message.reasoning.map { block ->
                MagratheaWebChatReasoningBlock(
                    block.text,
                    block.redacted,
                    block.kind.name.lowercase(),
                    block.phase?.name?.lowercase(),
                )
            }.toTypedArray(),
            attachments = message.attachments.map { attachment ->
                MagratheaWebChatAttachment(attachment.uri, attachment.mimeType)
            }.toTypedArray(),
            toolCalls = message.toolCalls.map { call ->
                MagratheaWebChatToolCall(call.id, call.name, call.arguments, call.partial)
            }.toTypedArray(),
            toolResults = message.toolResults.map { result ->
                MagratheaWebChatToolResult(
                    id = result.id,
                    name = result.name,
                    text = result.text,
                    isError = result.isError,
                    citations = result.citations.map { citation ->
                        MagratheaWebChatCitation(citation.title, citation.url, citation.snippet)
                    }.toTypedArray(),
                )
            }.toTypedArray(),
            createdAtEpochMs = message.createdAtEpochMs.toDouble(),
            stopReason = message.stopReason?.name?.lowercase(),
        )
    }.toTypedArray(),
    inputTokens = usage.inputTokens?.toDouble(),
    outputTokens = usage.outputTokens?.toDouble(),
    reasoningTokens = usage.reasoningTokens?.toDouble(),
)

private fun ChatbotHistoryItem.toJsHistoryItem(): MagratheaWebChatHistoryItem = MagratheaWebChatHistoryItem(
    sessionId = sessionId,
    model = configuration.model.toJsModel(),
    reasoningPreference = configuration.reasoningPreference.toJsValue(),
    updatedAtEpochMs = updatedAtEpochMs.toDouble(),
    status = status.name.lowercase(),
    lastMessageText = lastMessageText,
)

@JsExport
class MagratheaWebChatModel(
    val provider: String,
    val model: String,
    val displayName: String,
    val supportsToolCalls: Boolean,
    val reasoningEfforts: Array<String>?,
    val supportsDisabledReasoning: Boolean,
    val supportsStreaming: Boolean,
    val contextWindowTokens: Double?,
    val maxOutputTokens: Double? = null,
) {
    init {
        require(reasoningEfforts != null || !supportsDisabledReasoning) {
            "Disabled reasoning support requires reasoning capabilities"
        }
    }
}

private fun MagratheaWebChatModel.toConfiguration(
    reasoningPreference: String,
): ChatbotSessionConfiguration =
    ChatbotSessionConfiguration(
        model = toDescriptor(),
        reasoningPreference = reasoningPreference.toReasoningPreference(),
    )

private fun MagratheaWebChatModel.toDescriptor(): ModelDescriptor {
    val efforts = reasoningEfforts?.map(String::toReasoningEffort)
    require(efforts == null || efforts.distinct().size == efforts.size) {
        "Reasoning capabilities must use unique effort levels"
    }
    return ModelDescriptor(
        provider = provider,
        model = model,
        displayName = displayName,
        supportsToolCalls = supportsToolCalls,
        reasoningCapabilities = efforts?.let { levels ->
            ReasoningCapabilities(
                supportedEfforts = levels.toSet(),
                supportsDisabled = supportsDisabledReasoning,
            )
        },
        supportsStreaming = supportsStreaming,
        contextWindowTokens = contextWindowTokens?.toContextWindowTokens(),
        maxOutputTokens = maxOutputTokens?.toMaxOutputTokens(),
    )
}

private fun ModelDescriptor.toJsModel(): MagratheaWebChatModel = MagratheaWebChatModel(
    provider = provider,
    model = model,
    displayName = displayName,
    supportsToolCalls = supportsToolCalls,
    reasoningEfforts = reasoningCapabilities?.supportedEfforts
        ?.map { level -> level.name.lowercase() }
        ?.toTypedArray(),
    supportsDisabledReasoning = reasoningCapabilities?.supportsDisabled ?: false,
    supportsStreaming = supportsStreaming,
    contextWindowTokens = contextWindowTokens?.toDouble(),
    maxOutputTokens = maxOutputTokens?.toDouble(),
)

private fun String.toReasoningPreference(): ReasoningPreference = when (lowercase()) {
    "auto" -> ReasoningPreference.Auto
    "disabled" -> ReasoningPreference.Disabled
    else -> ReasoningPreference.Effort(toReasoningEffort())
}

private fun String.toReasoningEffort(): ReasoningEffort =
    ReasoningEffort.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
        ?: throw IllegalArgumentException("Unknown reasoning effort $this")

private fun ReasoningPreference.toJsValue(): String = when (this) {
    ReasoningPreference.Auto -> "auto"
    ReasoningPreference.Disabled -> "disabled"
    is ReasoningPreference.Effort -> level.name.lowercase()
}

internal fun normalizeReasoningPreferenceForModel(
    preference: ReasoningPreference,
    model: ModelDescriptor,
): ReasoningPreference = preference.takeIf { current ->
    current == ReasoningPreference.Auto ||
        model.reasoningCapabilities?.supports(current) == true
} ?: ReasoningPreference.Auto

private fun Double.toContextWindowTokens(): Long {
    require(isFinite() && this > 0.0 && this % 1.0 == 0.0 && this <= MAX_SAFE_INTEGER) {
        "contextWindowTokens must be a positive safe integer"
    }
    return toLong()
}

private fun Double.toMaxOutputTokens(): Int {
    require(isFinite() && this > 0.0 && this % 1.0 == 0.0 && this <= Int.MAX_VALUE.toDouble()) {
        "maxOutputTokens must be a positive 32-bit integer"
    }
    return toInt()
}

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0

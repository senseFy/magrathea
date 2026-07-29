package saien.magrathea.samples.jvm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.magrathea.chatbot.ChatbotSessionConfiguration
import saien.magrathea.chatbot.DefaultChatbotRequestFactory
import saien.magrathea.chatbot.ChatbotSnapshot
import saien.magrathea.chatbot.ChatbotStatus
import saien.magrathea.chatbot.createChatbotClient
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryAgentPersistence
import saien.magrathea.runtime.InMemoryToolRegistry

data class SampleReport(
    val streamedText: String,
    val toolExecutions: Int,
    val providerCalls: Int,
    val resumedWithoutProviderCall: Boolean,
    val cancelledStatePersisted: Boolean,
    val historySessionIds: List<String>,
)

data class ProviderNeutralFacadeReport(
    val text: String,
    val toolExecutions: Int,
    val providerCalls: Int,
    val historySize: Int,
    val resourceCloses: Int,
)

suspend fun runDeterministicSample(): SampleReport {
    val weatherTool = WeatherTool()
    val scriptedProvider = ScriptedWeatherProvider()
    val blockingProvider = BlockingProvider()
    val persistence = InMemoryAgentPersistence()
    val runner = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(scriptedProvider, blockingProvider)),
        toolRegistry = InMemoryToolRegistry(listOf(weatherTool)),
        persistence = persistence,
    )

    val chatSessionId = AgentSessionId("jvm-sample-chat")
    val request = AgentRequest(
        sessionId = chatSessionId,
        messages = listOf(
            AgentMessage(
                role = MessageRole.USER,
                parts = listOf(TextPart("What is the weather in Shanghai?")),
            ),
        ),
        model = ModelDescriptor(
            provider = scriptedProvider.key,
            model = "offline-protocol-mock",
            supportsToolCalls = true,
            supportsStreaming = true,
        ),
        tools = listOf(weatherTool.definition),
    )
    val events = runner.run(request).toList()
    val completed = events.filterIsInstance<AgentEvent.Completed>().single()
    check(events.filterIsInstance<AgentEvent.ToolRequested>().size == 1)
    check(events.filterIsInstance<AgentEvent.ToolCompleted>().size == 1)
    check(weatherTool.executionCount == 1)
    check(completed.state.stopReason == StopReason.COMPLETED)
    val streamedText = completed.state.messages
        .last { it.role == MessageRole.ASSISTANT }
        .parts
        .filterIsInstance<TextPart>()
        .joinToString(separator = "") { it.text }
    check(streamedText == "Shanghai weather: 21 C, clear")

    val providerCallsBeforeResume = scriptedProvider.callCount
    val resumedEvents = runner.resume(chatSessionId).toList()
    check(resumedEvents.single() is AgentEvent.Completed)
    val resumedWithoutProviderCall = scriptedProvider.callCount == providerCallsBeforeResume

    val cancelSessionId = AgentSessionId("jvm-sample-cancel")
    val cancelRequest = request.copy(
        sessionId = cancelSessionId,
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("stream forever")))),
        model = request.model.copy(provider = blockingProvider.key, supportsToolCalls = false),
        tools = emptyList(),
    )
    val cancelEvents = mutableListOf<AgentEvent>()
    val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
        try {
            runner.run(cancelRequest).collect(cancelEvents::add)
        } catch (_: CancellationException) {
            // Cancellation is the behavior under demonstration.
        }
    }
    blockingProvider.started.await()
    runner.cancel(cancelSessionId)
    joinAll(collector)
    val cancelledStatePersisted =
        persistence.load(cancelSessionId)?.snapshot?.state?.status == AgentStatus.CANCELLED
    check(cancelledStatePersisted)

    val historySessionIds = persistence.listSessions().map { it.sessionId.value }.sorted()
    check(historySessionIds == listOf(cancelSessionId.value, chatSessionId.value).sorted())

    return SampleReport(
        streamedText = streamedText,
        toolExecutions = weatherTool.executionCount,
        providerCalls = scriptedProvider.callCount,
        resumedWithoutProviderCall = resumedWithoutProviderCall,
        cancelledStatePersisted = cancelledStatePersisted,
        historySessionIds = historySessionIds,
    )
}

suspend fun runProviderNeutralFacadeSample(): ProviderNeutralFacadeReport {
    val weatherTool = WeatherTool()
    val provider = ScriptedWeatherProvider()
    val persistence = InMemoryAgentPersistence()
    val runner = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(listOf(weatherTool)),
        persistence = persistence,
    )
    val client = createChatbotClient(
        runner = runner,
        requestFactory = DefaultChatbotRequestFactory(
            tools = listOf(weatherTool.definition),
        ),
        persistence = persistence,
        closeResources = { provider.close() },
    )
    val session = client.createSession(
        ChatbotSessionConfiguration(
            ModelDescriptor(
                provider = provider.key,
                model = "offline-protocol-mock",
                supportsToolCalls = true,
                supportsStreaming = true,
            ),
        ),
    )
    val terminal = CompletableDeferred<ChatbotSnapshot>()
    val observation = session.observe { snapshot ->
        if (snapshot.status in setOf(
                ChatbotStatus.COMPLETED,
                ChatbotStatus.FAILED,
                ChatbotStatus.CANCELLED,
            )
        ) {
            terminal.complete(snapshot)
        }
    }

    val snapshot: ChatbotSnapshot
    val historySize: Int
    try {
        session.send("What is the weather in Shanghai?")
        snapshot = terminal.await()
        check(snapshot.status == ChatbotStatus.COMPLETED)
        historySize = client.history().size
    } finally {
        observation.cancel()
        client.close()
    }
    check(provider.closeCount == 1)

    return ProviderNeutralFacadeReport(
        text = snapshot.messages.last().text,
        toolExecutions = weatherTool.executionCount,
        providerCalls = provider.callCount,
        historySize = historySize,
        resourceCloses = provider.closeCount,
    )
}

fun main() = runBlocking {
    val report = runDeterministicSample()
    val facadeReport = runProviderNeutralFacadeSample()
    check(report.toolExecutions == 1)
    check(report.providerCalls == 2)
    check(report.resumedWithoutProviderCall)
    check(report.cancelledStatePersisted)
    check(facadeReport.text == "Shanghai weather: 21 C, clear")
    check(facadeReport.toolExecutions == 1)
    check(facadeReport.providerCalls == 2)
    check(facadeReport.historySize == 1)
    check(facadeReport.resourceCloses == 1)
    println(
        "MAGRATHEA_JVM_SAMPLE_PASS " +
            "toolExecutions=${report.toolExecutions} providerCalls=${report.providerCalls} " +
            "history=${report.historySessionIds.size} providerNeutralFacade=passed",
    )
}

private class WeatherTool : ToolExecutor {
    override val definition = ToolDefinition(
        name = "current_weather",
        description = "Return deterministic sample weather for a city",
        schema = buildJsonObject {
            put("type", "object")
            put(
                "required",
                kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("city")) },
            )
        },
    )
    var executionCount: Int = 0
        private set

    override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
        executionCount += 1
        return ToolExecutionResult(
            toolCallId = request.toolCall.toolCallId,
            toolName = request.toolCall.toolName,
            result = JsonPrimitive("21 C, clear"),
            displayText = "21 C, clear",
        )
    }
}

private class ScriptedWeatherProvider : ProviderAdapter {
    override val key: String = "sample-scripted"
    var callCount: Int = 0
        private set
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount += 1
    }

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
        callCount += 1
        if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
            emit(
                ProviderChunk(
                    events = listOf(
                        ProviderEvent.TextStart(),
                        ProviderEvent.TextDelta("Shanghai weather: "),
                    ),
                ),
            )
            emit(
                ProviderChunk(
                    events = listOf(
                        ProviderEvent.TextDelta("21 C, clear"),
                        ProviderEvent.TextEnd(),
                        ProviderEvent.Completed(finishReason = "stop", stopReason = StopReason.COMPLETED),
                    ),
                ),
            )
        } else {
            val started = ToolCallPart(
                toolCallId = "weather-call-1",
                toolName = "current_weather",
                arguments = buildJsonObject { },
                partial = true,
            )
            val finalized = started.copy(
                arguments = buildJsonObject { put("city", "Shanghai") },
                partial = false,
            )
            emit(
                ProviderChunk(
                    events = listOf(
                        ProviderEvent.ToolCallStart(started),
                        ProviderEvent.ToolCallDelta("weather-call-1", "{\"city\":\"Shanghai\"}"),
                        ProviderEvent.ToolCallEnd(finalized),
                        ProviderEvent.Completed(
                            finishReason = "tool_calls",
                            stopReason = StopReason.TOOL_CALLS,
                        ),
                    ),
                ),
            )
        }
    }
}

private class BlockingProvider : ProviderAdapter {
    override val key: String = "sample-blocking"
    val started = CompletableDeferred<Unit>()

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
        emit(
            ProviderChunk(
                events = listOf(
                    ProviderEvent.TextStart(),
                    ProviderEvent.TextDelta("partial"),
                ),
            ),
        )
        started.complete(Unit)
        awaitCancellation()
    }
}

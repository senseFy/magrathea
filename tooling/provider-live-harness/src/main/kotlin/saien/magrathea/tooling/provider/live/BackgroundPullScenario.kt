package saien.magrathea.tooling.provider.live

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.CredentialProvider
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.ReasoningCapabilities
import saien.magrathea.core.ReasoningPreference
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.SteeringMessageProvider
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.TypedTool
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryAgentPersistence
import saien.magrathea.runtime.InMemoryToolRegistry
import kotlin.random.Random

/**
 * ADR-019 S0 protocol spike: receipt -> answer -> Runtime notice -> real read -> answer.
 *
 * The notice is sent as a user-role text envelope, which is its proposed wire form on every
 * reference protocol. Output is limited to counts and booleans, like the rest of the harness.
 */
internal suspend fun runBackgroundPullScenario(
    config: ProviderLiveHarnessConfig,
    providerKey: String,
    providerRegistry: InMemoryProviderRegistry,
    credentialProvider: CredentialProvider,
) {
    val ledger = BackgroundLedger(Random.Default)
    val tools: List<ToolExecutor> = listOf(
        SlowLookupTool(ledger),
        ReadBackgroundResultTool(ledger),
        QuickLookupTool(ledger),
    )
    val steering = SteeringMessageProvider { context ->
        if (
            config.variant == VARIANT_TOOL_LOOP &&
            ledger.phase == 2 &&
            !ledger.noticeDelivered &&
            context.turn >= 1
        ) {
            ledger.noticeDelivered = true
            listOf(ledger.notice(restricted = false))
        } else {
            emptyList()
        }
    }
    val runner = DefaultAgentRunner(
        providerRegistry = providerRegistry,
        toolRegistry = InMemoryToolRegistry(tools),
        persistence = InMemoryAgentPersistence(),
        credentialProvider = credentialProvider,
        steeringMessageProvider = steering,
    )

    ledger.phase = 1
    val firstMessages = listOf(userMessage(PHASE_ONE_PROMPT))
    val first = runPhase(runner, backgroundPullRequest(config, providerKey, firstMessages, tools))
    val firstCounts = ledger.snapshotAndReset()
    val firstAnswer = first.messages.drop(firstMessages.size).finalAnswerText()
    println(
        "[background-pull] variant=${config.variant} phase=1 slowLookupCalls=${firstCounts.slowLookups} " +
            "earlyReads=${firstCounts.reads} answerChars=${firstAnswer.length} " +
            "answerHasSentinel=${firstAnswer.contains(ledger.sentinel)}",
    )

    ledger.phase = 2
    ledger.resultAvailable = true
    val secondNew = when (config.variant) {
        VARIANT_CONTINUATION -> listOf(ledger.notice(restricted = true)).also { ledger.noticeDelivered = true }
        VARIANT_COALESCED -> listOf(userMessage(COALESCED_PROMPT), ledger.notice(restricted = false))
            .also { ledger.noticeDelivered = true }
        VARIANT_TOOL_LOOP -> listOf(userMessage(TOOL_LOOP_PROMPT))
        else -> error("Unknown background-pull variant: ${config.variant}")
    }
    val secondMessages = first.messages + secondNew
    val second = runPhase(runner, backgroundPullRequest(config, providerKey, secondMessages, tools))
    val secondCounts = ledger.snapshotAndReset()
    val secondAnswer = second.messages.drop(secondMessages.size).finalAnswerText()
    val answerHasSentinel = secondAnswer.contains(ledger.sentinel)
    val answerHasSecondary = when (config.variant) {
        VARIANT_COALESCED -> secondAnswer.contains(COALESCED_EXPECTED)
        VARIANT_TOOL_LOOP -> secondAnswer.contains(ledger.quickSentinel)
        else -> true
    }
    println(
        "[background-pull] variant=${config.variant} phase=2 noticeDelivered=${ledger.noticeDelivered} " +
            "reads=${secondCounts.reads} readsWithHandle=${secondCounts.correctReads} " +
            "unknownHandleReads=${secondCounts.unknownReads} duplicateSlowLookups=${secondCounts.slowLookups} " +
            "quickLookups=${secondCounts.quickLookups} answerChars=${secondAnswer.length} " +
            "answerHasSentinel=$answerHasSentinel answerHasSecondary=$answerHasSecondary",
    )

    val failures = buildList {
        if (firstCounts.slowLookups == 0) add("phase1-no-business-call")
        if (firstCounts.slowLookups > 1) add("phase1-duplicate-business-call")
        if (firstAnswer.isBlank()) add("phase1-empty-answer")
        if (!ledger.noticeDelivered) add("phase2-notice-not-delivered")
        if (secondCounts.correctReads == 0) add("phase2-no-read")
        if (secondCounts.unknownReads > 0) add("phase2-guessed-handle")
        if (secondCounts.slowLookups > 0) add("phase2-duplicate-business-call")
        if (config.variant == VARIANT_TOOL_LOOP && secondCounts.quickLookups == 0) add("phase2-no-quick-lookup")
        if (!answerHasSentinel) add("phase2-answer-missing-result")
        if (!answerHasSecondary) add("phase2-answer-missing-secondary")
    }
    println(
        "[background-pull] variant=${config.variant} verdict=${if (failures.isEmpty()) "PASS" else "FAIL"} " +
            "failures=$failures",
    )
    check(failures.isEmpty()) { "background-pull variant ${config.variant} failed: $failures" }
}

internal const val VARIANT_CONTINUATION = "continuation"
internal const val VARIANT_COALESCED = "coalesced"
internal const val VARIANT_TOOL_LOOP = "tool-loop"
internal val BACKGROUND_PULL_VARIANTS = setOf(VARIANT_CONTINUATION, VARIANT_COALESCED, VARIANT_TOOL_LOOP)

private const val BACKGROUND_PULL_TIMEOUT_MS = 180_000L

internal val BACKGROUND_PULL_SYSTEM_PROMPT = """
    You are a concise assistant used to validate background Tool handling.

    Some Tool results report "status": "running". That work continues in the background and you
    do not have its result yet. Never state, guess, or invent a pending result. Answer with what
    you already have and say briefly that the rest is still being looked up. Do not call the same
    Tool again for the same request just because it is still running.

    A message wrapped in <runtime_notice> comes from the runtime, not from the user. It reports
    that a background result is available. To use it, call read_background_result with the handle
    from the notice; never guess a handle. Then answer from the latest conversation: supplement or
    correct your earlier answer, and also answer any newer user request.
""".trimIndent()

private const val RECEIPT_NOTE =
    "This lookup is still running in the background. You do not have its result yet."

private const val PHASE_ONE_PROMPT =
    "Look up the verification code for project Atlas with slow_lookup, then reply."

private const val COALESCED_PROMPT = "Also, what is 17 times 3?"
private const val COALESCED_EXPECTED = "51"

private const val TOOL_LOOP_PROMPT =
    "Now use quick_lookup to find the public launch label for project Atlas."

private class BackgroundLedger(random: Random) {
    val handle: String = "bg_" + token(random, 8)
    val sentinel: String = "ATLAS-" + token(random, 6).uppercase()
    val quickSentinel: String = "LAUNCH-" + token(random, 6).uppercase()
    var phase: Int = 0
    var resultAvailable: Boolean = false
    var noticeDelivered: Boolean = false
    var slowCallId: String? = null
    private var counts = PhaseCounts()

    fun recordSlowLookup(callId: String) {
        counts = counts.copy(slowLookups = counts.slowLookups + 1)
        if (slowCallId == null) slowCallId = callId
    }

    fun recordRead(correctHandle: Boolean) {
        counts = counts.copy(
            reads = counts.reads + 1,
            correctReads = counts.correctReads + if (correctHandle && resultAvailable) 1 else 0,
            unknownReads = counts.unknownReads + if (correctHandle) 0 else 1,
        )
    }

    fun recordQuickLookup() {
        counts = counts.copy(quickLookups = counts.quickLookups + 1)
    }

    fun snapshotAndReset(): PhaseCounts = counts.also { counts = PhaseCounts() }

    fun notice(restricted: Boolean): AgentMessage {
        val restriction = if (restricted) {
            "\nIn this follow-up, do not call tools other than read_background_result."
        } else {
            ""
        }
        val text = """
            <runtime_notice>
            A background result is available. This notice comes from the runtime, not from the user.
            - handle: $handle
            - tool: slow_lookup
            - call: ${slowCallId ?: "unknown"}
            - outcome: succeeded
            Retrieve it with read_background_result.$restriction
            </runtime_notice>
        """.trimIndent()
        return userMessage(text)
    }
}

private data class PhaseCounts(
    val slowLookups: Int = 0,
    val reads: Int = 0,
    val correctReads: Int = 0,
    val unknownReads: Int = 0,
    val quickLookups: Int = 0,
)

@Serializable
private data class TopicArgs(val topic: String)

@Serializable
private data class HandleArgs(val handle: String)

private class SlowLookupTool(
    private val ledger: BackgroundLedger,
) : TypedTool<TopicArgs>(TopicArgs.serializer()) {
    override val definition: ToolDefinition = ToolDefinition(
        name = "slow_lookup",
        description = "Looks up internal project records. It is slow and usually finishes in the background.",
        schema = stringObjectSchema("topic"),
    )

    override suspend fun executeTyped(request: ToolExecutionRequest, args: TopicArgs): ToolExecutionResult {
        ledger.recordSlowLookup(request.toolCall.toolCallId)
        return ToolExecutionResult(
            toolCallId = request.toolCall.toolCallId,
            toolName = definition.name,
            result = buildJsonObject {
                put("status", "running")
                put("handle", ledger.handle)
                put("retrieve", "read_background_result")
                put("note", RECEIPT_NOTE)
            },
        )
    }
}

private class ReadBackgroundResultTool(
    private val ledger: BackgroundLedger,
) : TypedTool<HandleArgs>(HandleArgs.serializer()) {
    override val definition: ToolDefinition = ToolDefinition(
        name = "read_background_result",
        description = "Returns the stored result of a background Tool execution by handle. It never runs the work again.",
        schema = stringObjectSchema("handle"),
    )

    override suspend fun executeTyped(request: ToolExecutionRequest, args: HandleArgs): ToolExecutionResult {
        val id = request.toolCall.toolCallId
        val correctHandle = args.handle.trim() == ledger.handle
        ledger.recordRead(correctHandle)
        if (!correctHandle) {
            return ToolExecutionResult(id, definition.name, buildJsonObject { put("error", "unknown handle") }, true)
        }
        val result: JsonObject = if (ledger.resultAvailable) {
            buildJsonObject {
                put("handle", ledger.handle)
                put("status", "succeeded")
                put("result", buildJsonObject {
                    put("topic", "project Atlas")
                    put("finding", "The verification code for project Atlas is ${ledger.sentinel}.")
                })
            }
        } else {
            buildJsonObject {
                put("handle", ledger.handle)
                put("status", "running")
            }
        }
        return ToolExecutionResult(id, definition.name, result)
    }
}

private class QuickLookupTool(
    private val ledger: BackgroundLedger,
) : TypedTool<TopicArgs>(TopicArgs.serializer()) {
    override val definition: ToolDefinition = ToolDefinition(
        name = "quick_lookup",
        description = "Looks up public project facts and returns immediately.",
        schema = stringObjectSchema("topic"),
    )

    override suspend fun executeTyped(request: ToolExecutionRequest, args: TopicArgs): ToolExecutionResult {
        ledger.recordQuickLookup()
        return ToolExecutionResult(
            toolCallId = request.toolCall.toolCallId,
            toolName = definition.name,
            result = buildJsonObject {
                put("topic", args.topic)
                put("finding", "The public launch label for project Atlas is ${ledger.quickSentinel}.")
            },
        )
    }
}

private suspend fun runPhase(runner: DefaultAgentRunner, request: AgentRequest): AgentStateSnapshot {
    var completed: AgentStateSnapshot? = null
    withTimeout(BACKGROUND_PULL_TIMEOUT_MS) {
        runner.run(request).collect { event ->
            formatProviderLiveEvent(event).forEach(::println)
            requireSuccessfulProviderLiveEvent(event)
            if (event is AgentEvent.Completed) completed = event.state
        }
    }
    return checkNotNull(completed) { "background-pull phase ended without a completed event" }
}

private fun backgroundPullRequest(
    config: ProviderLiveHarnessConfig,
    providerKey: String,
    messages: List<AgentMessage>,
    tools: List<ToolExecutor>,
): AgentRequest = AgentRequest(
    sessionId = AgentSessionId.create(),
    systemPrompt = BACKGROUND_PULL_SYSTEM_PROMPT,
    messages = messages,
    model = ModelDescriptor(
        provider = providerKey,
        model = config.model,
        reasoningCapabilities = config.reasoning?.let { effort ->
            ReasoningCapabilities(supportedEfforts = setOf(effort))
        },
        supportsStreaming = config.streaming,
    ),
    reasoningPreference = config.reasoning?.let(ReasoningPreference::Effort) ?: ReasoningPreference.Auto,
    tools = tools.map { it.definition },
    metadata = buildJsonObject {
        put("scenario", "background-pull")
        put("variant", config.variant)
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

private fun userMessage(text: String) = AgentMessage(role = MessageRole.USER, parts = listOf(TextPart(text)))

private fun List<AgentMessage>.finalAnswerText(): String =
    lastOrNull { message ->
        message.role == MessageRole.ASSISTANT &&
            message.parts.any { part -> part is TextPart && part.text.isNotBlank() }
    }?.parts?.filterIsInstance<TextPart>()?.joinToString(" ") { it.text }.orEmpty()

private fun stringObjectSchema(field: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("properties", buildJsonObject {
        put(field, buildJsonObject { put("type", JsonPrimitive("string")) })
    })
    put("required", buildJsonArray { add(JsonPrimitive(field)) })
}

private fun token(random: Random, length: Int): String {
    val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
    return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
}

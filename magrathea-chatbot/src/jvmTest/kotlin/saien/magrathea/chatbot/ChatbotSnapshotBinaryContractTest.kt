package saien.magrathea.chatbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.ReplayReport

class ChatbotSnapshotBinaryContractTest {
    private val snapshotClass = ChatbotSnapshot::class.java
    private val legacyParameterTypes = arrayOf(
        ChatbotSessionConfiguration::class.java,
        String::class.java,
        List::class.java,
        ChatbotStatus::class.java,
        ChatbotFailure::class.java,
        ChatbotInterruption::class.java,
        ChatbotUsage::class.java,
        ChatbotUsage::class.java,
        ChatbotContextManagementSnapshot::class.java,
        List::class.java,
    )

    @Test
    fun originalTenParameterConstructorRemainsCallable() {
        val expected = snapshot()
        val actual = snapshotClass.getConstructor(*legacyParameterTypes)
            .newInstance(*legacyArguments(expected))

        assertEquals(expected, actual)
        assertNull(actual.stopReason)
    }

    @Test
    fun originalDefaultConstructorRetainsItsMaskAndDefaults() {
        val configuration = configuration()
        val actual = snapshotClass.getConstructor(
            *legacyParameterTypes,
            Int::class.javaPrimitiveType,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
        ).newInstance(configuration, *arrayOfNulls<Any?>(9), 1022, null)

        assertEquals(configuration, actual.configuration)
        assertNull(actual.sessionId)
        assertEquals(emptyList(), actual.messages)
        assertEquals(ChatbotStatus.IDLE, actual.status)
        assertNull(actual.failure)
        assertNull(actual.interruption)
        assertEquals(ChatbotUsage(), actual.usage)
        assertEquals(ChatbotUsage(), actual.latestRequestUsage)
        assertEquals(ChatbotContextManagementSnapshot(), actual.contextManagement)
        assertEquals(emptyList(), actual.toolActivities)
        assertNull(actual.stopReason)
    }

    @Test
    fun originalCopyAndDefaultCopyPreserveTheStopReason() {
        val original = snapshot(stopReason = ChatbotStopReason.MAX_TURNS).copy(
            replay = ReplayReport(AgentRunId("run"), 0, "test-provider", "test-model", emptyList()),
        )
        val replacement = snapshot(sessionId = "copied-session")
        val copied = snapshotClass.getMethod("copy", *legacyParameterTypes)
            .invoke(original, *legacyArguments(replacement)) as ChatbotSnapshot

        assertEquals(replacement.copy(stopReason = ChatbotStopReason.MAX_TURNS, replay = original.replay), copied)
        assertEquals(ChatbotStopReason.MAX_TURNS, copied.stopReason)

        val defaultCopy = snapshotClass.getDeclaredMethod(
            "copy\$default",
            snapshotClass,
            *legacyParameterTypes,
            Int::class.javaPrimitiveType,
            Any::class.java,
        ).invoke(null, original, *arrayOfNulls<Any?>(10), 1023, null) as ChatbotSnapshot

        assertEquals(original, defaultCopy)
        assertEquals(ChatbotStopReason.MAX_TURNS, defaultCopy.stopReason)
    }

    @Test
    fun copyCanExplicitlyReplaceOrClearTheStopReason() {
        val original = snapshot(stopReason = ChatbotStopReason.MAX_TURNS)
        val updated = original.copy(stopReason = ChatbotStopReason.MAX_TOKENS)

        assertEquals(ChatbotStopReason.MAX_TOKENS, updated.stopReason)
        assertEquals(original, updated.copy(stopReason = ChatbotStopReason.MAX_TURNS))
        assertEquals(snapshot(), updated.copy(stopReason = null))
    }

    @Test
    fun stopReasonAwareSignaturesRemainCallableAndPreserveReplay() {
        val parameters = legacyParameterTypes + ChatbotStopReason::class.java
        val report = ReplayReport(AgentRunId("run"), 0, "test-provider", "test-model", emptyList())
        val original = snapshot(stopReason = ChatbotStopReason.MAX_TURNS).copy(replay = report)
        val arguments = legacyArguments(original).plus(elements = arrayOf<Any?>(original.stopReason))
        val constructed = snapshotClass.getConstructor(*parameters).newInstance(*arguments) as ChatbotSnapshot
        assertEquals(original.stopReason, constructed.stopReason)
        assertNull(constructed.replay)
        assertEquals(original, snapshotClass.getMethod("copy", *parameters).invoke(original, *arguments))
        val defaults = snapshotClass.getDeclaredMethod(
            "copy\$default", snapshotClass, *parameters, Int::class.javaPrimitiveType, Any::class.java,
        ).invoke(null, original, *arrayOfNulls<Any?>(11), 2047, null)
        assertEquals(original, defaults)
    }

    @Test
    fun contextManagementSnapshotRetainsItsPublishedSignatures() {
        val type = ChatbotContextManagementSnapshot::class.java
        val parameters = arrayOf(Long::class.javaPrimitiveType, Long::class.javaObjectType)
        assertEquals(
            ChatbotContextManagementSnapshot(compactionGeneration = 2, tokensBeforeLastCompaction = 500),
            type.getConstructor(*parameters).newInstance(2L, 500L),
        )
        assertEquals(
            ChatbotContextManagementSnapshot(),
            type.getConstructor(
                *parameters,
                Int::class.javaPrimitiveType,
                Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
            ).newInstance(0L, null, 0b11, null),
        )

        val cleared = ChatbotContextManagementSnapshot(
            compactionGeneration = 1,
            clearingGeneration = 3,
            clearedToolResults = 4,
        )
        val copied = type.getMethod("copy", *parameters)
            .invoke(cleared, 2L, null) as ChatbotContextManagementSnapshot
        assertEquals(2, copied.compactionGeneration)
        assertEquals(3, copied.clearingGeneration)
        assertEquals(4, copied.clearedToolResults)
        val defaults = type.getDeclaredMethod(
            "copy\$default", type, *parameters, Int::class.javaPrimitiveType, Any::class.java,
        ).invoke(null, cleared, 0L, null, 0b11, null)
        assertEquals(cleared, defaults)
    }

    @Test
    fun replayAwareSignaturesRemainCallableAndPreserveBackground() {
        val replayParameters = legacyParameterTypes +
            ChatbotStopReason::class.java + ReplayReport::class.java
        val report = ReplayReport(AgentRunId("run"), 0, "test-provider", "test-model", emptyList())
        val background = ChatbotBackgroundSnapshot(
            executions = listOf(
                ChatbotBackgroundExecution(
                    handle = "bg_handle",
                    toolName = "deep_lookup",
                    originCallId = "call-1",
                    state = ChatbotBackgroundState.RUNNING,
                ),
            ),
            hasPendingActivation = true,
            requiresExecutionHost = true,
        )
        val original = snapshot(stopReason = ChatbotStopReason.MAX_TURNS)
            .copy(replay = report, background = background)
        val arguments = legacyArguments(original).plus(
            elements = arrayOf<Any?>(original.stopReason, report),
        )
        val constructed = snapshotClass.getConstructor(*replayParameters)
            .newInstance(*arguments) as ChatbotSnapshot
        assertEquals(original.stopReason, constructed.stopReason)
        assertEquals(report, constructed.replay)
        assertNull(constructed.background)
        val copied = snapshotClass.getMethod("copy", *replayParameters)
            .invoke(original, *arguments) as ChatbotSnapshot
        assertEquals(original, copied)
        assertEquals(background, copied.background)
    }

    @Test
    fun backgroundAwareConstructorAndCopyProjectTheExecutionView() {
        val allParameters = legacyParameterTypes +
            ChatbotStopReason::class.java + ReplayReport::class.java +
            ChatbotBackgroundSnapshot::class.java
        val background = ChatbotBackgroundSnapshot(
            executions = listOf(
                ChatbotBackgroundExecution(
                    handle = "bg_handle",
                    toolName = "deep_lookup",
                    originCallId = "call-1",
                    state = ChatbotBackgroundState.SUCCEEDED,
                ),
            ),
            hasPendingActivation = true,
            requiresExecutionHost = false,
        )
        val replacement = ChatbotBackgroundSnapshot(
            executions = emptyList(),
            hasPendingActivation = false,
            requiresExecutionHost = false,
        )
        val base = snapshot()
        val arguments = legacyArguments(base).plus(
            elements = arrayOf<Any?>(null, null, background),
        )
        val constructed = snapshotClass.getConstructor(*allParameters)
            .newInstance(*arguments) as ChatbotSnapshot
        assertEquals(background, constructed.background)
        val copied = snapshotClass.getMethod("copy", *allParameters)
            .invoke(constructed, *legacyArguments(base.copy(background = replacement)),
                null, null, replacement) as ChatbotSnapshot
        assertEquals(replacement, copied.background)
    }

    @Test
    fun toolActivityRetainsItsLegacySignatureAndProjectsBackground() {
        val type = ChatbotToolActivitySnapshot::class.java
        val legacyParameters = arrayOf(
            ChatbotToolActivityKey::class.java,
            ChatbotToolCall::class.java,
            ChatbotToolActivityStatus::class.java,
            String::class.java,
            ChatbotToolResult::class.java,
        )
        val backgroundParameters = legacyParameters + ChatbotBackgroundExecution::class.java
        val key = ChatbotToolActivityKey(messageId = "message", callOrdinal = 0)
        val call = ChatbotToolCall(id = "call-1", name = "deep_lookup", arguments = "{}", partial = false)
        val result = ChatbotToolResult(id = "call-1", name = "deep_lookup", text = "done", isError = false)
        val background = ChatbotBackgroundExecution(
            handle = "bg_handle",
            toolName = "deep_lookup",
            originCallId = "call-1",
            state = ChatbotBackgroundState.RUNNING,
        )
        val legacy = type.getConstructor(*legacyParameters)
            .newInstance(key, call, ChatbotToolActivityStatus.PENDING, "result-message", result)
            as ChatbotToolActivitySnapshot
        assertNull(legacy.background)
        val copied = type.getMethod("copy", *legacyParameters).invoke(
            legacy, key, call, ChatbotToolActivityStatus.RUNNING, "result-message", result,
        ) as ChatbotToolActivitySnapshot
        assertNull(copied.background)
        assertEquals(ChatbotToolActivityStatus.RUNNING, copied.status)
        val projected = type.getConstructor(*backgroundParameters)
            .newInstance(key, call, ChatbotToolActivityStatus.RUNNING, "result-message", result, background)
            as ChatbotToolActivitySnapshot
        assertEquals(background, projected.background)
        val replaced = type.getMethod("copy", *backgroundParameters).invoke(
            projected, key, call, ChatbotToolActivityStatus.SUCCEEDED, "result-message", result,
            background,
        ) as ChatbotToolActivitySnapshot
        assertEquals(background, replaced.background)
        assertEquals(ChatbotToolActivityStatus.SUCCEEDED, replaced.status)
    }

    @Test
    fun backgroundCancellationRetainsItsPublishedShape() {
        val cancellation = ChatbotBackgroundCancellation(
            cancelled = listOf("bg_a"),
            alreadyFinished = listOf("bg_b"),
            unknown = listOf("bg_c"),
        )
        assertEquals(listOf("bg_a"), cancellation.cancelled)
        assertEquals(listOf("bg_b"), cancellation.alreadyFinished)
        assertEquals(listOf("bg_c"), cancellation.unknown)
    }

    private fun configuration() = ChatbotSessionConfiguration(
        model = ModelDescriptor(provider = "test-provider", model = "test-model"),
    )

    private fun snapshot(
        sessionId: String = "original-session",
        stopReason: ChatbotStopReason? = null,
    ) = ChatbotSnapshot(
        configuration = configuration(),
        sessionId = sessionId,
        messages = listOf(
            ChatbotMessageSnapshot(
                id = "message-id",
                role = ChatbotMessageRole.ASSISTANT,
                text = "Search completed.",
                createdAtEpochMs = 123,
            ),
        ),
        status = ChatbotStatus.COMPLETED,
        usage = ChatbotUsage(inputTokens = 101, outputTokens = 37, reasoningTokens = 11),
        latestRequestUsage = ChatbotUsage(inputTokens = 41, outputTokens = 17, reasoningTokens = 5),
        contextManagement = ChatbotContextManagementSnapshot(
            compactionGeneration = 2,
            tokensBeforeLastCompaction = 500,
        ),
        stopReason = stopReason,
    )

    private fun legacyArguments(snapshot: ChatbotSnapshot): Array<Any?> = arrayOf(
        snapshot.configuration,
        snapshot.sessionId,
        snapshot.messages,
        snapshot.status,
        snapshot.failure,
        snapshot.interruption,
        snapshot.usage,
        snapshot.latestRequestUsage,
        snapshot.contextManagement,
        snapshot.toolActivities,
    )
}

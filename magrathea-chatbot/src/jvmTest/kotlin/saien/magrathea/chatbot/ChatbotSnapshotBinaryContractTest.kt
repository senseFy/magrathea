package saien.magrathea.chatbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import saien.magrathea.core.ModelDescriptor

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
        val original = snapshot(stopReason = ChatbotStopReason.MAX_TURNS)
        val replacement = snapshot(sessionId = "copied-session")
        val copied = snapshotClass.getMethod("copy", *legacyParameterTypes)
            .invoke(original, *legacyArguments(replacement)) as ChatbotSnapshot

        assertEquals(replacement.copy(stopReason = ChatbotStopReason.MAX_TURNS), copied)
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

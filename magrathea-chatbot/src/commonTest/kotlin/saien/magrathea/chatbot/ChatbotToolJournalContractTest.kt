@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.chatbot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.SharedToolExecutionPermit
import saien.magrathea.core.StopReason
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionPermit
import saien.magrathea.core.ToolExecutionRecord
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutionState
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.UnlimitedToolExecutionPermit
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryAgentPersistence
import saien.magrathea.runtime.InMemoryToolRegistry

class ChatbotToolJournalContractTest {
    @Test
    fun journalStatesProjectOntoTheActiveToolBatch() {
        val batch = assistantMessage(
            "assistant-1",
            listOf(
                chatCall("call-queued", "queued"),
                chatCall("call-running", "running"),
                chatCall("call-done", "done"),
                chatCall("call-failed", "failed"),
                chatCall("call-partial", "partial_tool", partial = true),
            ),
        )
        val activities = batch.toolCalls.mapIndexed { ordinal, call ->
            activity(batch.id, ordinal, call, ChatbotToolActivityStatus.RUNNING)
        }
        val journal = listOf(
            record("call-queued", "queued", ToolExecutionState.PENDING),
            record("call-running", "running", ToolExecutionState.STARTED),
            record("call-done", "done", ToolExecutionState.COMPLETED, executionResult("call-done", "done")),
            record(
                "call-failed",
                "failed",
                ToolExecutionState.COMPLETED,
                executionResult("call-failed", "failed", isError = true),
            ),
            record("call-partial", "partial_tool", ToolExecutionState.PENDING),
        )

        val projected = activities.withToolExecutions(listOf(batch), journal)

        assertEquals(ChatbotToolActivityStatus.PENDING, projected[0].status)
        assertNull(projected[0].result)
        assertEquals(ChatbotToolActivityStatus.RUNNING, projected[1].status)
        assertNull(projected[1].result)
        assertEquals(ChatbotToolActivityStatus.SUCCEEDED, projected[2].status)
        assertEquals("done-result", projected[2].result?.text)
        assertEquals(ChatbotToolActivityStatus.FAILED, projected[3].status)
        assertTrue(projected[3].result?.isError == true)
        assertEquals(ChatbotToolActivityStatus.PREPARING, projected[4].status)
        assertNull(projected[4].result)
    }

    @Test
    fun canonicalToolResultsAreNeverOverriddenByTheJournal() {
        val batch = assistantMessage("assistant-1", listOf(chatCall("call-1", "lookup")))
        val canonical = ChatbotToolResult("call-1", "lookup", "canonical", isError = false)
        val settled = activity(
            batch.id,
            0,
            batch.toolCalls.single(),
            ChatbotToolActivityStatus.SUCCEEDED,
            resultMessageId = "tool-1",
            result = canonical,
        )

        val projected = listOf(settled).withToolExecutions(
            listOf(batch),
            listOf(record("call-1", "lookup", ToolExecutionState.PENDING)),
        )

        assertEquals(settled, projected.single())
    }

    @Test
    fun anOlderAssistantMessageReusingTheCallIdIsUntouched() {
        val older = assistantMessage("assistant-1", listOf(chatCall("reused", "lookup")))
        val batch = assistantMessage("assistant-2", listOf(chatCall("reused", "lookup")))
        val stale = activity(
            older.id,
            0,
            older.toolCalls.single(),
            ChatbotToolActivityStatus.INTERRUPTED,
        )
        val pending = activity(
            batch.id,
            0,
            batch.toolCalls.single(),
            ChatbotToolActivityStatus.PENDING,
        )

        val projected = listOf(stale, pending).withToolExecutions(
            listOf(older, batch),
            listOf(
                record(
                    "reused",
                    "lookup",
                    ToolExecutionState.COMPLETED,
                    executionResult("reused", "lookup"),
                ),
            ),
        )

        assertEquals(stale, projected[0])
        assertEquals(ChatbotToolActivityStatus.SUCCEEDED, projected[1].status)
        assertEquals("lookup-result", projected[1].result?.text)
    }

    @Test
    fun theJournalOverridesInterruptedLeftoversFromAnEarlierAttempt() {
        val batch = assistantMessage("assistant-1", listOf(chatCall("call-1", "lookup")))
        val interrupted = activity(
            batch.id,
            0,
            batch.toolCalls.single(),
            ChatbotToolActivityStatus.INTERRUPTED,
        )

        val projected = listOf(interrupted).withToolExecutions(
            listOf(batch),
            listOf(record("call-1", "lookup", ToolExecutionState.STARTED)),
        )

        assertEquals(ChatbotToolActivityStatus.RUNNING, projected.single().status)
        assertNull(projected.single().result)
    }

    @Test
    fun anEmptyJournalIsANoOp() {
        val batch = assistantMessage("assistant-1", listOf(chatCall("call-1", "lookup")))
        val activities = listOf(
            activity(batch.id, 0, batch.toolCalls.single(), ChatbotToolActivityStatus.PENDING),
        )

        assertSame(activities, activities.withToolExecutions(listOf(batch), emptyList()))
    }

    @Test
    fun completedCallsReportImmediatelyWhileOthersRun() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = TwoCallProvider()
        val firstGate = CompletableDeferred<Unit>()
        val first = gatedTool("first", firstGate)
        val second = gatedTool("second", CompletableDeferred<Unit>().apply { complete(Unit) })
        val persistence = InMemoryAgentPersistence()
        val client = createChatbotClient(
            runner = DefaultAgentRunner(
                providerRegistry = InMemoryProviderRegistry(listOf(provider)),
                toolRegistry = InMemoryToolRegistry(listOf(first, second)),
                persistence = persistence,
                dispatcher = dispatcher,
            ),
            requestFactory = DefaultChatbotRequestFactory(
                tools = listOf(first.definition, second.definition),
                configure = {
                    copy(
                        engine = AgentEngineConfig(
                            runtime = RuntimeConfig(
                                maxTurns = 3,
                                defaultToolTimeoutMillis = Long.MAX_VALUE,
                            ),
                        ),
                    )
                },
            ),
            persistence = persistence,
            closeResources = {},
            sessionDispatcher = dispatcher,
        )
        val session = client.createSession(
            ChatbotSessionConfiguration(
                ModelDescriptor(provider.key, "model", supportsToolCalls = true),
            ),
        )

        session.send("run both tools")
        testScheduler.runCurrent()

        val running = session.snapshot().toolActivities.associateBy { it.call.name }
        assertEquals(ChatbotToolActivityStatus.RUNNING, running["first"]?.status)
        assertEquals(ChatbotToolActivityStatus.SUCCEEDED, running["second"]?.status)
        assertEquals("second-result", running["second"]?.result?.text)

        firstGate.complete(Unit)
        advanceUntilIdle()

        val finished = session.snapshot()
        assertEquals(ChatbotStatus.COMPLETED, finished.status)
        assertTrue(
            finished.toolActivities.all { it.status == ChatbotToolActivityStatus.SUCCEEDED },
        )
        client.close()
    }

    @Test
    fun queuedCallsStayPendingWhileASharedPermitIsHeld() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = TwoCallProvider()
        val permit = SharedToolExecutionPermit(1)
        val firstGate = CompletableDeferred<Unit>()
        val first = gatedTool("first", firstGate, permit)
        val second = gatedTool("second", CompletableDeferred<Unit>().apply { complete(Unit) }, permit)
        val persistence = InMemoryAgentPersistence()
        val client = createChatbotClient(
            runner = DefaultAgentRunner(
                providerRegistry = InMemoryProviderRegistry(listOf(provider)),
                toolRegistry = InMemoryToolRegistry(listOf(first, second)),
                persistence = persistence,
                dispatcher = dispatcher,
            ),
            requestFactory = DefaultChatbotRequestFactory(
                tools = listOf(first.definition, second.definition),
                configure = {
                    copy(
                        engine = AgentEngineConfig(
                            runtime = RuntimeConfig(
                                maxTurns = 3,
                                defaultToolTimeoutMillis = Long.MAX_VALUE,
                            ),
                        ),
                    )
                },
            ),
            persistence = persistence,
            closeResources = {},
            sessionDispatcher = dispatcher,
        )
        val session = client.createSession(
            ChatbotSessionConfiguration(
                ModelDescriptor(provider.key, "model", supportsToolCalls = true),
            ),
        )

        session.send("run both tools")
        testScheduler.runCurrent()

        val queued = session.snapshot().toolActivities.associateBy { it.call.name }
        assertEquals(ChatbotToolActivityStatus.RUNNING, queued["first"]?.status)
        assertEquals(ChatbotToolActivityStatus.PENDING, queued["second"]?.status)

        firstGate.complete(Unit)
        advanceUntilIdle()

        val finished = session.snapshot()
        assertEquals(ChatbotStatus.COMPLETED, finished.status)
        assertTrue(
            finished.toolActivities.all { it.status == ChatbotToolActivityStatus.SUCCEEDED },
        )
        client.close()
    }

    @Test
    fun aLoneCallWaitingOnAHeldSharedPermitStaysPending() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = TwoCallProvider(
            calls = listOf(ToolCallPart("call-first", "first", buildJsonObject { })),
        )
        val permit = SharedToolExecutionPermit(1)
        val first = gatedTool(
            "first",
            CompletableDeferred<Unit>().apply { complete(Unit) },
            permit,
        )
        val persistence = InMemoryAgentPersistence()
        val client = createChatbotClient(
            runner = DefaultAgentRunner(
                providerRegistry = InMemoryProviderRegistry(listOf(provider)),
                toolRegistry = InMemoryToolRegistry(listOf(first)),
                persistence = persistence,
                dispatcher = dispatcher,
            ),
            requestFactory = DefaultChatbotRequestFactory(
                tools = listOf(first.definition),
                configure = {
                    copy(
                        engine = AgentEngineConfig(
                            runtime = RuntimeConfig(
                                maxTurns = 3,
                                defaultToolTimeoutMillis = Long.MAX_VALUE,
                            ),
                        ),
                    )
                },
            ),
            persistence = persistence,
            closeResources = {},
            sessionDispatcher = dispatcher,
        )
        val session = client.createSession(
            ChatbotSessionConfiguration(
                ModelDescriptor(provider.key, "model", supportsToolCalls = true),
            ),
        )

        permit.acquire()
        session.send("run the tool")
        testScheduler.runCurrent()

        val queued = session.snapshot()
        assertEquals(ChatbotStatus.WAITING_FOR_TOOL, queued.status)
        assertEquals(ChatbotToolActivityStatus.PENDING, queued.toolActivities.single().status)
        assertNull(queued.toolActivities.single().result)

        permit.release()
        advanceUntilIdle()

        val finished = session.snapshot()
        assertEquals(ChatbotStatus.COMPLETED, finished.status)
        assertEquals(
            ChatbotToolActivityStatus.SUCCEEDED,
            finished.toolActivities.single().status,
        )
        assertEquals("first-result", finished.toolActivities.single().result?.text)
        client.close()
    }

    private fun chatCall(
        id: String,
        name: String,
        partial: Boolean = false,
    ) = ChatbotToolCall(id = id, name = name, arguments = "{}", partial = partial)

    private fun assistantMessage(
        id: String,
        calls: List<ChatbotToolCall>,
    ) = ChatbotMessageSnapshot(
        id = id,
        role = ChatbotMessageRole.ASSISTANT,
        text = "",
        toolCalls = calls,
        createdAtEpochMs = 0,
    )

    private fun activity(
        messageId: String,
        callOrdinal: Int,
        call: ChatbotToolCall,
        status: ChatbotToolActivityStatus,
        resultMessageId: String? = null,
        result: ChatbotToolResult? = null,
    ) = ChatbotToolActivitySnapshot(
        key = ChatbotToolActivityKey(messageId, callOrdinal),
        call = call,
        status = status,
        resultMessageId = resultMessageId,
        result = result,
    )

    private fun record(
        callId: String,
        name: String,
        state: ToolExecutionState,
        result: ToolExecutionResult? = null,
    ) = ToolExecutionRecord(
        executionId = "exec-$callId",
        toolCallId = callId,
        toolName = name,
        callOrdinal = 1,
        state = state,
        result = result,
    )

    private fun executionResult(
        callId: String,
        name: String,
        isError: Boolean = false,
    ) = ToolExecutionResult(
        toolCallId = callId,
        toolName = name,
        result = JsonPrimitive("$name-value"),
        isError = isError,
        displayText = "$name-result",
    )

    private fun gatedTool(
        name: String,
        gate: CompletableDeferred<Unit>,
        permit: ToolExecutionPermit? = null,
    ): ToolExecutor = object : ToolExecutor {
        override val definition = ToolDefinition(
            name = name,
            description = "Journal contract tool $name",
            schema = buildJsonObject { },
        )

        override fun executionPermit(request: ToolExecutionRequest): ToolExecutionPermit =
            permit ?: UnlimitedToolExecutionPermit

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            gate.await()
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("$name-value"),
                displayText = "$name-result",
            )
        }
    }

    private class TwoCallProvider(
        private val calls: List<ToolCallPart> = listOf(
            ToolCallPart("call-first", "first", buildJsonObject { }),
            ToolCallPart("call-second", "second", buildJsonObject { }),
        ),
    ) : ProviderAdapter {
        override val key = "tool-journal-provider"

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                emit(
                    ProviderChunk(
                        events = listOf(
                            ProviderEvent.TextStart(),
                            ProviderEvent.TextDelta("done"),
                            ProviderEvent.TextEnd(),
                            ProviderEvent.Completed(stopReason = StopReason.COMPLETED),
                        ),
                    ),
                )
            } else {
                emit(
                    ProviderChunk(
                        events = calls.flatMap { call ->
                            listOf(
                                ProviderEvent.ToolCallStart(call.copy(partial = true)),
                                ProviderEvent.ToolCallEnd(call),
                            )
                        } + ProviderEvent.Completed(stopReason = StopReason.TOOL_CALLS),
                    ),
                )
            }
        }
    }
}

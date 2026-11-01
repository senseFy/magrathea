package saien.magrathea.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundResultDeliveryFactsTest {
    @Test
    fun emptyAndUnrelatedHistoryHasNoDelivery() {
        assertFacts(emptyList(), delivered = false, acknowledged = false)
        assertFacts(
            listOf(
                notice(handle = "other"),
                notice(version = VERSION - 1),
                notice().copy(role = MessageRole.USER),
                read(handle = "other", status = "succeeded"),
                completedReply(),
            ),
            delivered = false,
            acknowledged = false,
        )
    }

    @Test
    fun matchingNoticeNeedsALaterCompletedReply() {
        assertFacts(listOf(notice()), delivered = true, acknowledged = false)
        StopReason.entries.filter { it != StopReason.COMPLETED }.forEach { reason ->
            assertFacts(
                listOf(notice(), completedReply().copy(stopReason = reason)),
                delivered = true,
                acknowledged = false,
            )
        }
        assertFacts(listOf(notice(), completedReply()), delivered = true, acknowledged = true)
    }

    @Test
    fun acknowledgementBeforeTheLatestDeliveryDoesNotCoverIt() {
        assertFacts(
            listOf(completedReply(), notice()),
            delivered = true,
            acknowledged = false,
        )
        val redelivered = listOf(notice(), completedReply(), notice())
        assertFacts(redelivered, delivered = true, acknowledged = false)
        assertFacts(redelivered + completedReply(), delivered = true, acknowledged = true)
    }

    @Test
    fun noticesForAnOlderResultVersionDoNotAcknowledgeTheCurrentVersion() {
        val older = listOf(notice(version = VERSION - 1), completedReply())
        assertFacts(older, delivered = false, acknowledged = false)
        assertFacts(older + notice(), delivered = true, acknowledged = false)
        assertFacts(older + notice() + completedReply(), delivered = true, acknowledged = true)
    }

    @Test
    fun terminalReadsDeliverWithoutANotice() {
        listOf("succeeded", "failed", "timed_out", "interrupted", "cancelled").forEach { status ->
            // Existing persisted retrievals are addressed by handle, without a result version
            // or a required runtime-control metadata marker.
            val read = read(status = status)
            assertFacts(listOf(read), delivered = true, acknowledged = false)
            assertFacts(listOf(read, completedReply()), delivered = true, acknowledged = true)
        }
        assertFacts(
            listOf(read(status = "succeeded", isError = true), completedReply()),
            delivered = false,
            acknowledged = false,
        )
    }

    @Test
    fun awaitMatchesTheTerminalResultForTheRequestedHandle() {
        val unrelatedTerminal = listOf(
            resultEntry(handle = "other", status = "succeeded"),
            resultEntry(handle = HANDLE, status = "running"),
        )
        assertFacts(
            listOf(await(unrelatedTerminal), completedReply()),
            delivered = false,
            acknowledged = false,
        )
        val terminal = unrelatedTerminal + resultEntry(handle = HANDLE, status = "failed")
        assertFacts(listOf(await(terminal)), delivered = true, acknowledged = false)
        assertFacts(
            listOf(await(terminal), completedReply()),
            delivered = true,
            acknowledged = true,
        )
        assertFacts(
            listOf(await(terminal, isError = true), completedReply()),
            delivered = false,
            acknowledged = false,
        )
    }

    @Test
    fun pendingReadsCannotBeAcknowledgedByACompletedReply() {
        listOf("queued", "running").forEach { status ->
            assertFacts(
                listOf(read(status = status), completedReply()),
                delivered = false,
                acknowledged = false,
            )
        }
    }

    @Test
    fun malformedReadFieldsDoNotDeliverOrDisplaceAnAcknowledgedDelivery() {
        val valid = read(status = "succeeded").parts.single() as ToolResultPart
        val invalidShapes = listOf(JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()))
        val malformed = invalidShapes.flatMap { value ->
            listOf(
                valid.copy(metadata = buildJsonObject { put(BackgroundToolProtocol.HANDLE_METADATA_KEY, value) }),
                valid.copy(result = buildJsonObject { put("status", value) }),
            )
        } + listOf(JsonNull, JsonPrimitive("succeeded"), JsonArray(emptyList())).map { value ->
            valid.copy(result = value)
        }
        malformed.forEach { part ->
            val message = message(MessageRole.TOOL, part)
            assertFacts(listOf(message, completedReply()), delivered = false, acknowledged = false)
            assertFacts(listOf(notice(), completedReply(), message), delivered = true, acknowledged = true)
        }
    }

    @Test
    fun malformedAwaitContainersAndEntriesAreIgnoredWhileValidEntriesStillDeliver() {
        val valid = await(emptyList()).parts.single() as ToolResultPart
        val invalidShapes = listOf(JsonNull, JsonPrimitive("succeeded"), JsonObject(emptyMap()))
        val malformedContainers = invalidShapes.map { value ->
            valid.copy(result = buildJsonObject { put("results", value) })
        } + listOf(JsonNull, JsonPrimitive("succeeded"), JsonArray(emptyList())).map { value ->
            valid.copy(result = value)
        }
        val malformedEntries = mutableListOf<JsonElement>(JsonNull, JsonPrimitive("entry"), JsonArray(emptyList()))
        listOf(JsonNull, JsonArray(emptyList()), JsonObject(emptyMap())).forEach { value ->
            malformedEntries += buildJsonObject {
                put("handle", value)
                put("status", "succeeded")
            }
            malformedEntries += buildJsonObject {
                put("handle", HANDLE)
                put("status", value)
            }
        }
        val malformedArray = valid.copy(result = buildJsonObject { put("results", JsonArray(malformedEntries)) })
        (malformedContainers + malformedArray).forEach { part ->
            val message = message(MessageRole.TOOL, part)
            assertFacts(listOf(message, completedReply()), delivered = false, acknowledged = false)
            assertFacts(listOf(notice(), completedReply(), message), delivered = true, acknowledged = true)
        }

        val mixed = valid.copy(result = buildJsonObject {
            put("results", JsonArray(malformedEntries + resultEntry(HANDLE, "succeeded")))
        })
        val message = message(MessageRole.TOOL, mixed)
        assertFacts(listOf(message), delivered = true, acknowledged = false)
        assertFacts(listOf(message, completedReply()), delivered = true, acknowledged = true)
    }

    @Test
    fun finishAcknowledgesOnlyWhenSuccessfulInALaterToolMessage() {
        assertFacts(listOf(finish(), notice()), delivered = true, acknowledged = false)
        assertFacts(listOf(notice(), finish(isError = true)), delivered = true, acknowledged = false)
        assertFacts(
            listOf(notice(), finish().copy(role = MessageRole.USER)),
            delivered = true,
            acknowledged = false,
        )
        val sameMessage = read(status = "succeeded").let { read ->
            read.copy(parts = read.parts + finish().parts)
        }
        assertFacts(listOf(sameMessage), delivered = true, acknowledged = false)
        assertFacts(listOf(notice(), finish()), delivered = true, acknowledged = true)
    }

    @Test
    fun regeneratedHistoryWithoutTheDeliveryHasNoAcknowledgedResult() {
        val deliveredHistory = listOf(notice(), completedReply())
        assertFacts(deliveredHistory, delivered = true, acknowledged = true)
        assertFacts(deliveredHistory.drop(1), delivered = false, acknowledged = false)
    }

    private fun assertFacts(
        messages: List<AgentMessage>,
        delivered: Boolean,
        acknowledged: Boolean,
    ) {
        assertEquals(
            BackgroundResultDeliveryFacts(delivered, acknowledged),
            backgroundResultDeliveryFacts(HANDLE, VERSION, messages),
        )
    }

    private fun notice(handle: String = HANDLE, version: Int = VERSION): AgentMessage = message(
        MessageRole.RUNTIME,
        RuntimeNoticePart(
            entries = listOf(
                RuntimeNoticeEntry(
                    handle = handle,
                    toolName = "lookup",
                    callId = "call-1",
                    outcome = BackgroundOutcome.SUCCEEDED,
                    resultVersion = version,
                ),
            ),
            delivery = RuntimeNoticeDelivery.COALESCED,
            text = "<runtime_notice>Result available.</runtime_notice>",
        ),
    )

    private fun read(
        status: String,
        handle: String = HANDLE,
        isError: Boolean = false,
    ): AgentMessage = message(
        MessageRole.TOOL,
        ToolResultPart(
            toolCallId = "read-1",
            toolName = "read_background_result",
            result = buildJsonObject { put("status", status) },
            isError = isError,
            metadata = buildJsonObject { put("magrathea.background.handle", handle) },
        ),
    )

    private fun await(results: List<JsonObject>, isError: Boolean = false): AgentMessage = message(
        MessageRole.TOOL,
        ToolResultPart(
            toolCallId = "await-1",
            toolName = "await_background_results",
            result = buildJsonObject { put("results", JsonArray(results)) },
            isError = isError,
        ),
    )

    private fun resultEntry(handle: String, status: String): JsonObject = buildJsonObject {
        put("handle", handle)
        put("status", status)
    }

    private fun finish(isError: Boolean = false): AgentMessage = message(
        MessageRole.TOOL,
        ToolResultPart(
            toolCallId = "finish-1",
            toolName = "finish_without_reply",
            result = JsonPrimitive("finished"),
            isError = isError,
        ),
    )

    private fun completedReply(): AgentMessage = message(MessageRole.ASSISTANT, TextPart("Done"))
        .copy(stopReason = StopReason.COMPLETED)

    private fun message(role: MessageRole, vararg parts: MessagePart): AgentMessage = AgentMessage(
        id = "fixture-message",
        role = role,
        parts = parts.toList(),
        createdAtEpochMs = 1,
    )

    private companion object {
        const val HANDLE = "bg-1"
        const val VERSION = 3
    }
}

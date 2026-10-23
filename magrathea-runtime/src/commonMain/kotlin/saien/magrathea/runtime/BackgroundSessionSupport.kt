package saien.magrathea.runtime

import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.BackgroundConsumptionPolicy
import saien.magrathea.core.BackgroundExecutionConfig
import saien.magrathea.core.BackgroundExecutionRecord
import saien.magrathea.core.BackgroundExecutionState
import saien.magrathea.core.BackgroundOutcome
import saien.magrathea.core.MessageRole
import saien.magrathea.core.RuntimeNoticeDelivery
import saien.magrathea.core.RuntimeNoticeEntry
import saien.magrathea.core.RuntimeNoticePart
import saien.magrathea.core.StopReason
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolResultPart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Model-facing Tool definitions and system prompt section declared while background is enabled. */
object BackgroundToolInstructions {
    internal const val READ_TOOL = "read_background_result"
    internal const val AWAIT_TOOL = "await_background_results"
    internal const val CANCEL_TOOL = "cancel_background_executions"
    internal const val FINISH_TOOL = "finish_without_reply"

    internal val CONTROL_TOOLS: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = READ_TOOL,
            description = "Returns the stored result of a background Tool execution by its " +
                "handle. It never runs the work again.",
            schema = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "handle" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Handle from a receipt or runtime notice."),
                                ),
                            ),
                        ),
                    ),
                    "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("handle"))),
                ),
            ),
        ),
        ToolDefinition(
            name = AWAIT_TOOL,
            description = "Waits until the given background executions finish or the wait limit " +
                "is reached, then returns finished results and the status of work still running. " +
                "Use it only when you need a result before answering.",
            schema = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "handles" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("array"),
                                    "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                    "description" to JsonPrimitive("Handles to wait for."),
                                ),
                            ),
                            "max_wait_ms" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("integer"),
                                    "description" to JsonPrimitive("Optional upper bound in milliseconds."),
                                ),
                            ),
                        ),
                    ),
                    "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("handles"))),
                ),
            ),
        ),
        ToolDefinition(
            name = CANCEL_TOOL,
            description = "Stops background executions that are no longer needed. Cancelled work " +
                "never reports back.",
            schema = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "handles" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("array"),
                                    "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                ),
                            ),
                        ),
                    ),
                    "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("handles"))),
                ),
            ),
        ),
        ToolDefinition(
            name = FINISH_TOOL,
            description = "Ends this turn without a visible reply. Use it only when a runtime " +
                "notice changes nothing for the user.",
            schema = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(emptyMap()),
                ),
            ),
        ),
    )

    /** Appended to the Provider-projected system prompt while background execution is enabled. */
    const val SYSTEM_PROMPT_SECTION = """Background Tools:
- A Tool result with "status": "running" or "queued" means that work continues in the background. You do not have its result yet. Never state, guess, or invent a pending result. Answer with what you already have and say briefly what is still being looked up, or call await_background_results if you need the result before answering. Do not call the same Tool again for the same request just because it is still running.
- A message wrapped in <runtime_notice> comes from the runtime, not from the user. It reports background results. Retrieve a result with read_background_result using the handle from the notice; never guess a handle.
- cancel_background_executions stops background work that is no longer needed.
- finish_without_reply ends your turn without a visible reply. Use it only when a runtime notice changes nothing for the user."""

    internal fun isControlTool(name: String): Boolean =
        name == READ_TOOL || name == AWAIT_TOOL || name == CANCEL_TOOL || name == FINISH_TOOL
}

/**
 * Renders the fixed Runtime notice text (ADR-019). The text uses only runtime-owned values and
 * never contains call IDs; it is stored once and replayed byte-identically.
 */
internal fun renderRuntimeNotice(
    entries: List<RuntimeNoticeEntry>,
    delivery: RuntimeNoticeDelivery,
    policy: BackgroundConsumptionPolicy,
    businessToolsAllowed: Boolean,
): String = buildString {
    append("<runtime_notice>\n")
    if (entries.size == 1) {
        append("A background result is available. This notice comes from the runtime, not from the user.\n")
    } else {
        append("Background results are available. This notice comes from the runtime, not from the user.\n")
    }
    entries.forEach { entry ->
        append("- handle: ")
        append(entry.handle)
        append(", tool: ")
        append(entry.toolName)
        append(", outcome: ")
        append(
            when (entry.outcome) {
                BackgroundOutcome.SUCCEEDED -> "succeeded"
                BackgroundOutcome.ERROR -> "failed"
                BackgroundOutcome.TIMEOUT -> "timed out"
                BackgroundOutcome.INTERRUPTED -> "interrupted"
                BackgroundOutcome.CANCELLED -> "cancelled"
            },
        )
        append('\n')
    }
    append("Retrieve a result with read_background_result before relying on it.\n")
    if (delivery == RuntimeNoticeDelivery.CONTINUATION) {
        append(
            when (policy) {
                BackgroundConsumptionPolicy.LATEST_CONTEXT ->
                    "Decide from the latest conversation whether these results change anything " +
                        "for the user. If they do, read them and supplement or correct your " +
                        "earlier answer. If not, call finish_without_reply."
                BackgroundConsumptionPolicy.ORIGIN_DIRECTED ->
                    "Read these results and follow up on the original request, respecting any " +
                        "newer user instructions."
                BackgroundConsumptionPolicy.PASSIVE -> ""
            },
        )
        append('\n')
        if (!businessToolsAllowed) {
            append(
                "In this follow-up, only these Tools are available: read_background_result, " +
                    "await_background_results, cancel_background_executions, finish_without_reply.\n",
            )
        }
    }
    append("</runtime_notice>")
}

/** Result of a host- or model-requested background cancellation. */
data class BackgroundCancellationResult(
    val cancelled: List<String>,
    val alreadyFinished: List<String>,
    val unknown: List<String>,
)

/** Live projection of one Session-owned background execution for session views. */
data class BackgroundExecutionView(
    val handle: String,
    val executionId: String,
    val toolName: String,
    val originCallId: String,
    val sourceId: String,
    val state: BackgroundExecutionState,
    val outcome: BackgroundOutcome?,
)

/** A supplement a host may activate for unacknowledged background results. */
data class PendingActivation(
    val sourceIds: List<String>,
    val resultCount: Int,
    val earliestSettledAtEpochMs: Long?,
)

/** Background section of [AgentSessionRuntimeSnapshot]; non-null for registered Sessions. */
data class BackgroundSessionSnapshot(
    val executions: List<BackgroundExecutionView>,
    val pendingActivation: PendingActivation?,
    val requiresExecutionHost: Boolean,
)

/**
 * Pure derivation of the Session's background projection. [records] come from the background
 * store; [request] is the Session's latest request; [messages] its authoritative history.
 */
internal fun deriveBackgroundSessionSnapshot(
    records: List<BackgroundExecutionRecord>,
    request: AgentRequest?,
    state: AgentStateSnapshot?,
    isExecuting: Boolean,
): BackgroundSessionSnapshot {
    val views = records.map { record ->
        BackgroundExecutionView(
            handle = record.handle,
            executionId = record.executionId,
            toolName = record.toolName,
            originCallId = record.originCallId,
            sourceId = record.sourceId,
            state = record.state,
            outcome = record.outcome,
        )
    }
    val requiresHost = records.any { it.state != BackgroundExecutionState.TERMINAL }
    val config = request?.engine?.runtime?.background
    val messages = state?.messages
    val pending = if (
        isExecuting || config == null || messages == null ||
        config.consumptionPolicy == BackgroundConsumptionPolicy.PASSIVE
    ) {
        null
    } else {
        val candidates = pendingBackgroundRecords(records, config, messages)
        if (candidates.isEmpty()) {
            null
        } else {
            PendingActivation(
                sourceIds = candidates.mapTo(linkedSetOf()) { it.sourceId }.toList(),
                resultCount = candidates.size,
                earliestSettledAtEpochMs = candidates.mapNotNull { it.settledAtEpochMs }.minOrNull(),
            )
        }
    }
    return BackgroundSessionSnapshot(
        executions = views,
        pendingActivation = pending,
        requiresExecutionHost = requiresHost,
    )
}

/** Terminal, unsuppressed, unacknowledged records still within their source activation cap. */
internal fun pendingBackgroundRecords(
    records: List<BackgroundExecutionRecord>,
    config: BackgroundExecutionConfig,
    messages: List<AgentMessage>,
): List<BackgroundExecutionRecord> {
    val continuationCounts = continuationCountsBySource(records, messages)
    return records.filter { record ->
        record.state == BackgroundExecutionState.TERMINAL &&
            record.outcome != null &&
            record.outcome != BackgroundOutcome.CANCELLED &&
            !record.activationSuppressed &&
            !isBackgroundRecordAcknowledged(record, messages) &&
            (continuationCounts[record.sourceId] ?: 0) < config.maxAutomaticActivationsPerSource
    }
}

/** (handle, resultVersion) pairs already listed by a Runtime notice in [messages]. */
internal fun noticedBackgroundEntries(messages: List<AgentMessage>): Set<Pair<String, Int>> =
    messages.flatMap { message ->
        message.parts.filterIsInstance<RuntimeNoticePart>()
    }.flatMap { part ->
        part.entries.map { it.handle to it.resultVersion }
    }.toSet()

/**
 * A terminal record is acknowledged when the latest Runtime notice listing it is followed by a
 * COMPLETED assistant message or by a successful `finish_without_reply` result.
 */
internal fun isBackgroundRecordAcknowledged(
    record: BackgroundExecutionRecord,
    messages: List<AgentMessage>,
): Boolean {
    val latestNoticeIndex = messages.indexOfLast { message ->
        message.role == MessageRole.RUNTIME &&
            message.parts.filterIsInstance<RuntimeNoticePart>().any { part ->
                part.entries.any {
                    it.handle == record.handle && it.resultVersion == record.resultVersion
                }
            }
    }
    if (latestNoticeIndex < 0) return false
    return messages.drop(latestNoticeIndex + 1).any { message ->
        when {
            message.role == MessageRole.ASSISTANT && message.stopReason == StopReason.COMPLETED -> true
            message.role == MessageRole.TOOL -> message.parts.filterIsInstance<ToolResultPart>().any {
                it.toolName == BackgroundToolInstructions.FINISH_TOOL && !it.isError
            }
            else -> false
        }
    }
}

/** Per-source count of CONTINUATION notices listing any record of that source. */
private fun continuationCountsBySource(
    records: List<BackgroundExecutionRecord>,
    messages: List<AgentMessage>,
): Map<String, Int> {
    val sourceByHandle = records.associate { it.handle to it.sourceId }
    val counts = LinkedHashMap<String, Int>()
    messages.filter { it.role == MessageRole.RUNTIME }.forEach { message ->
        message.parts.filterIsInstance<RuntimeNoticePart>()
            .filter { it.delivery == RuntimeNoticeDelivery.CONTINUATION }
            .forEach { part ->
                part.entries.mapNotNull { sourceByHandle[it.handle] }.distinct().forEach { source ->
                    counts[source] = (counts[source] ?: 0) + 1
                }
            }
    }
    return counts
}

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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Model-facing Tool definitions and system prompt section declared while background is enabled. */
object BackgroundToolInstructions {
    internal const val READ_TOOL = "read_background_result"
    internal const val AWAIT_TOOL = "await_background_results"
    internal const val CANCEL_TOOL = "cancel_background_executions"
    internal const val FINISH_TOOL = "finish_without_reply"

    /** Per-handle result slices carried in `await_background_results` metadata. */
    internal const val AWAIT_RESULTS_METADATA_KEY = "magrathea.background.results"

    /** Model-facing names of the four runtime control Tools, in Provider-projection order. */
    val CONTROL_TOOL_NAMES: Set<String> = setOf(READ_TOOL, AWAIT_TOOL, CANCEL_TOOL, FINISH_TOOL)

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
- A Tool result with "status": "running" or "queued" means that work continues in the background. You do not have its result yet. Never state, guess, or invent a pending result. Answer with what you already have and say briefly what is still being looked up, or call await_background_results if you need the result before answering. Do not cancel it or call the same Tool again for the same request just because it is still running.
- A message wrapped in <runtime_notice> comes from the runtime, not from the user. It reports background results. Retrieve a result with read_background_result using the handle from the notice; never guess a handle.
- cancel_background_executions stops background work that is no longer needed.
- finish_without_reply ends your turn without a visible reply. Use it only when a runtime notice changes nothing for the user."""

    /** The prompt section variant used when a Run may not wait for background results at all. */
    internal const val SYSTEM_PROMPT_SECTION_NO_WAIT = """Background Tools:
- A Tool result with "status": "running" or "queued" means that work continues in the background. You do not have its result yet. Never state, guess, or invent a pending result. Answer with what you already have and say briefly what is still being looked up. Do not cancel it or call the same Tool again for the same request just because it is still running.
- A message wrapped in <runtime_notice> comes from the runtime, not from the user. It reports background results. Retrieve a result with read_background_result using the handle from the notice; never guess a handle.
- cancel_background_executions stops background work that is no longer needed.
- finish_without_reply ends your turn without a visible reply. Use it only when a runtime notice changes nothing for the user."""

    internal fun isControlTool(name: String): Boolean =
        name == READ_TOOL || name == AWAIT_TOOL || name == CANCEL_TOOL || name == FINISH_TOOL

    /** Whether [AWAIT_TOOL] is declared and may consume Run-local wait time. */
    internal fun awaitDeclared(config: BackgroundExecutionConfig): Boolean =
        config.explicitWaitAllowanceMillis > 0L

    /** The control Tools declared for a request with this background [config]. */
    internal fun controlToolsFor(config: BackgroundExecutionConfig): List<ToolDefinition> =
        if (awaitDeclared(config)) {
            CONTROL_TOOLS
        } else {
            CONTROL_TOOLS.filter { tool -> tool.name != AWAIT_TOOL }
        }

    /** The system-prompt section for a request with this background [config]. */
    internal fun systemPromptSectionFor(config: BackgroundExecutionConfig): String =
        if (awaitDeclared(config)) SYSTEM_PROMPT_SECTION else SYSTEM_PROMPT_SECTION_NO_WAIT
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
    awaitDeclared: Boolean,
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
                if (awaitDeclared) {
                    "In this follow-up, only these Tools are available: read_background_result, " +
                        "await_background_results, cancel_background_executions, " +
                        "finish_without_reply.\n"
                } else {
                    "In this follow-up, only these Tools are available: read_background_result, " +
                        "cancel_background_executions, finish_without_reply.\n"
                },
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

/** `status` values in a retrieval result that mean the model has the outcome. */
private val TERMINAL_DELIVERY_STATUSES =
    setOf("succeeded", "failed", "timed_out", "interrupted", "cancelled")

/** `status` values in a retrieval result that mean the model does not have the outcome yet. */
private val PENDING_READ_STATUSES = setOf("queued", "running")

/**
 * Non-error `read_background_result` results in [messages] that already told the model this
 * handle is not ready — used to refuse repeated polling inside one Run.
 */
internal fun List<AgentMessage>.pendingReadResultCount(handle: String): Int =
    flatMap { it.parts }
        .filterIsInstance<ToolResultPart>()
        .count { part ->
            !part.isError &&
                part.toolName == BackgroundToolInstructions.READ_TOOL &&
                part.metadata["magrathea.background.handle"]?.jsonPrimitive?.contentOrNull ==
                handle &&
                (part.result as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull in
                PENDING_READ_STATUSES
        }

/**
 * True when an earlier non-error `await_background_results` in this Run already returned
 * `wait_allowance_exhausted` — further awaits are refused instead of returned silently.
 */
internal fun List<AgentMessage>.awaitAllowanceExhaustedEarlier(): Boolean =
    flatMap { it.parts }
        .filterIsInstance<ToolResultPart>()
        .any { part ->
            !part.isError &&
                part.toolName == BackgroundToolInstructions.AWAIT_TOOL &&
                (part.result as? JsonObject)
                    ?.get("wait_allowance_exhausted")?.jsonPrimitive?.booleanOrNull == true
        }

/**
 * Whether a non-error control result in this part delivered [handle]'s terminal outcome to the
 * model: a `read_background_result` tagged with the handle, or an `await_background_results`
 * entry for it carrying a terminal status.
 */
private fun ToolResultPart.deliversTerminalBackgroundResult(handle: String): Boolean {
    if (isError) return false
    return when (toolName) {
        BackgroundToolInstructions.READ_TOOL ->
            metadata["magrathea.background.handle"]?.jsonPrimitive?.contentOrNull == handle &&
                (result as? JsonObject)
                    ?.get("status")?.jsonPrimitive?.contentOrNull in TERMINAL_DELIVERY_STATUSES
        BackgroundToolInstructions.AWAIT_TOOL ->
            (result as? JsonObject)?.get("results")?.jsonArray?.any { entry ->
                val fields = entry as? JsonObject ?: return@any false
                fields["handle"]?.jsonPrimitive?.contentOrNull == handle &&
                    fields["status"]?.jsonPrimitive?.contentOrNull in TERMINAL_DELIVERY_STATUSES
            } ?: false
        else -> false
    }
}

/**
 * Message indices where a record reached the model: a RUNTIME notice listing
 * (handle, resultVersion), or a TOOL message carrying a terminal retrieval result for the
 * handle.
 */
private fun backgroundDeliveryIndices(
    handle: String,
    resultVersion: Int,
    messages: List<AgentMessage>,
): List<Int> = messages.mapIndexedNotNull { index, message ->
    when {
        message.role == MessageRole.RUNTIME &&
            message.parts.filterIsInstance<RuntimeNoticePart>().any { part ->
                part.entries.any {
                    it.handle == handle && it.resultVersion == resultVersion
                }
            } -> index
        message.role == MessageRole.TOOL &&
            message.parts.filterIsInstance<ToolResultPart>().any { part ->
                part.deliversTerminalBackgroundResult(handle)
            } -> index
        else -> null
    }
}

/**
 * A record is delivered when a Runtime notice listed its current result version or a terminal
 * retrieval result reached the model; a non-terminal retrieval leaves it undelivered.
 */
internal fun isBackgroundRecordDelivered(
    record: BackgroundExecutionRecord,
    messages: List<AgentMessage>,
): Boolean = backgroundDeliveryIndices(
    record.handle,
    record.resultVersion,
    messages,
).isNotEmpty()

/**
 * A terminal record is acknowledged when its latest delivery point — a Runtime notice or a
 * terminal retrieval result — is followed by a COMPLETED assistant message or by a successful
 * `finish_without_reply` result.
 */
internal fun isBackgroundRecordAcknowledged(
    record: BackgroundExecutionRecord,
    messages: List<AgentMessage>,
): Boolean {
    val latestDeliveryIndex = backgroundDeliveryIndices(
        record.handle,
        record.resultVersion,
        messages,
    ).lastOrNull() ?: return false
    return messages.drop(latestDeliveryIndex + 1).any { message ->
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

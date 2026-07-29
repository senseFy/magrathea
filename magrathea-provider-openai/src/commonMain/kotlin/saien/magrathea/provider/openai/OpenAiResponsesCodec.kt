package saien.magrathea.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.StopReason
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.ToolCallPart
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderUsage
import saien.magrathea.provider.api.PROVIDER_CITATIONS_METADATA_KEY
import saien.magrathea.provider.api.validateSemantics

internal class OpenAiResponsesCodec(
    private val providerKey: String,
    private val model: String,
    private val json: Json = Json,
    private val dialectPolicy: OpenAiResponsesDialectPolicy = OpenAiResponsesDialectPolicy(
        reconcileReasoningAtItemBoundary = false,
        allowXSearchOutput = false,
        allowServerManagedCustomToolCalls = false,
    ),
) {
    private var responseId: String? = null
    private var created = false
    private var terminal = false
    private var doneSentinelSeen = false
    private val activeItems = linkedMapOf<Int, ActiveOutputItem>()
    private val completedItems = linkedMapOf<Int, JsonObject>()

    fun decodeNonStreaming(payload: String): ProviderChunk {
        ensurePristine()
        val response = parseObject(payload, "OpenAI response")
        if (response.optionalString("status") == "failed") {
            decodeFailure(response)
        }
        responseId = response.requiredString("id")
        created = true
        val output = response.requiredArray("output")
        val events = buildList {
            output.forEachIndexed { index, element ->
                val item = element as? JsonObject
                    ?: protocolFailure("OpenAI output item $index must be an object")
                addAll(decodeCompleteItem(item))
                completedItems[index] = item
            }
            add(completedEvent(response, output))
        }
        terminal = true
        return ProviderChunk(events = events).also(ProviderChunk::validateSemantics)
    }

    fun decodeServerSentEvent(eventName: String?, payload: String): ProviderChunk? {
        if (payload == "[DONE]") {
            if (eventName != null && eventName != "data") {
                protocolFailure("OpenAI [DONE] has an invalid SSE event name")
            }
            if (!terminal) protocolFailure("OpenAI emitted [DONE] before a terminal response event")
            if (doneSentinelSeen) protocolFailure("OpenAI emitted duplicate [DONE]")
            doneSentinelSeen = true
            return null
        }
        if (doneSentinelSeen) protocolFailure("OpenAI emitted data after [DONE]")
        if (terminal) protocolFailure("OpenAI emitted data after terminal response event")
        val root = parseObject(payload, "OpenAI streaming event")
        val type = root.requiredString("type")
        if (eventName != null && eventName != type) {
            protocolFailure("OpenAI SSE event name does not match payload type")
        }
        val events = when (type) {
            "response.created" -> decodeCreated(root)
            "response.queued", "response.in_progress" -> decodeProgress(root)
            "response.output_item.added" -> decodeItemAdded(root)
            "response.content_part.added" -> decodeContentPartAdded(root)
            "response.output_text.delta" -> decodeTextDelta(root)
            "response.output_text.done" -> decodeTextDone(root)
            "response.content_part.done" -> decodeContentPartDone(root)
            "response.reasoning_summary_part.added" -> decodeReasoningSummaryPartAdded(root)
            "response.reasoning_summary_part.done" -> decodeReasoningSummaryPartDone(root)
            "response.reasoning_text.done" -> decodeReasoningTextDone(root, ReasoningWireKind.TEXT)
            "response.reasoning_summary_text.done" -> decodeReasoningTextDone(root, ReasoningWireKind.SUMMARY)
            "response.reasoning_text.delta" -> decodeReasoningDelta(root, ReasoningWireKind.TEXT)
            "response.reasoning_summary_text.delta" -> decodeReasoningDelta(root, ReasoningWireKind.SUMMARY)
            "response.function_call_arguments.delta" -> decodeFunctionArgumentsDelta(root)
            "response.function_call_arguments.done" -> decodeFunctionArgumentsDone(root)
            "response.custom_tool_call_input.delta" -> decodeCustomToolInputDelta(root)
            "response.custom_tool_call_input.done" -> decodeCustomToolInputDone(root)
            "response.output_item.done" -> decodeItemDone(root)
            "response.output_text.annotation.added" -> emptyList()
            "response.completed", "response.incomplete" -> decodeTerminal(root)
            "response.failed", "error" -> decodeFailure(root)
            else -> when {
                type in X_SEARCH_PROGRESS_EVENTS -> decodeXSearchProgress(root)
                else -> protocolFailure("Unsupported OpenAI streaming event type $type")
            }
        }
        return events.takeIf(List<ProviderEvent>::isNotEmpty)
            ?.let { ProviderChunk(events = it).also(ProviderChunk::validateSemantics) }
    }

    fun finish() {
        if (!terminal) protocolFailure("OpenAI stream ended before a terminal response event")
        if (activeItems.isNotEmpty()) protocolFailure("OpenAI stream ended with active output items")
    }

    private fun decodeCreated(root: JsonObject): List<ProviderEvent> {
        if (created) protocolFailure("OpenAI emitted duplicate response.created")
        val response = root.requiredObject("response")
        responseId = response.requiredString("id")
        if (response.requiredString("status") !in setOf("queued", "in_progress")) {
            protocolFailure("OpenAI response.created has an invalid status")
        }
        created = true
        return emptyList()
    }

    private fun decodeProgress(root: JsonObject): List<ProviderEvent> {
        requireCreated()
        val response = root.requiredObject("response")
        requireResponseId(response.requiredString("id"))
        if (response.requiredString("status") !in setOf("queued", "in_progress")) {
            protocolFailure("OpenAI progress event has an invalid status")
        }
        return emptyList()
    }

    private fun decodeItemAdded(root: JsonObject): List<ProviderEvent> {
        requireCreated()
        val index = root.requiredIndex("output_index")
        if (activeItems.containsKey(index) || completedItems.containsKey(index)) {
            protocolFailure("OpenAI output item index was reused")
        }
        val item = root.requiredObject("item")
        val type = item.requiredString("type")
        val id = item.requiredString("id")
        val active = ActiveOutputItem(index = index, id = id, type = type, start = item)
        activeItems[index] = active
        return when (type) {
            "message" -> {
                if (item.requiredString("role") != "assistant") protocolFailure("OpenAI output message role must be assistant")
                if (item.requiredArray("content").isNotEmpty()) protocolFailure("OpenAI added message must start with empty content")
                emptyList<ProviderEvent>()
            }
            "reasoning" -> {
                item.optionalArray("summary")?.takeIf(JsonArray::isNotEmpty)?.let {
                    protocolFailure("OpenAI added reasoning item must start with an empty summary")
                }
                item.optionalArray("content")?.takeIf(JsonArray::isNotEmpty)?.let {
                    protocolFailure("OpenAI added reasoning item must start with empty content")
                }
                emptyList()
            }
            "function_call" -> {
                active.callId = item.requiredString("call_id")
                active.functionName = item.requiredString("name")
                if (item.requiredString("arguments", allowEmpty = true).isNotEmpty()) {
                    protocolFailure("OpenAI added function call must start with empty arguments")
                }
                listOf(ProviderEvent.ToolCallStart(active.partialToolCall()))
            }
            "x_search_call" -> {
                requireXSearchOutput()
                item.validateXSearchCall(final = false)
                emptyList()
            }
            "custom_tool_call" -> {
                requireServerManagedCustomToolCalls()
                item.validateCustomToolCall(final = false)
                active.callId = item.requiredString("call_id")
                active.functionName = item.requiredString("name")
                item.optionalString("input")?.let(active.customInput::append)
                emptyList()
            }
            else -> protocolFailure("Unsupported OpenAI output item type $type")
        }
    }

    private fun decodeXSearchProgress(root: JsonObject): List<ProviderEvent> {
        requireXSearchOutput()
        val active = root.activeItem()
        if (active.type != "x_search_call") {
            protocolFailure("OpenAI X Search progress belongs to a non-X-Search item")
        }
        return emptyList()
    }

    private fun decodeCustomToolInputDelta(root: JsonObject): List<ProviderEvent> {
        requireServerManagedCustomToolCalls()
        val active = root.activeItem()
        if (active.type != "custom_tool_call") {
            protocolFailure("OpenAI custom Tool input belongs to a non-custom Tool item")
        }
        if (active.customInputFinalized) {
            protocolFailure("OpenAI emitted custom Tool input after completion")
        }
        active.customInput.append(root.requiredString("delta", allowEmpty = true))
        return emptyList()
    }

    private fun decodeCustomToolInputDone(root: JsonObject): List<ProviderEvent> {
        requireServerManagedCustomToolCalls()
        val active = root.activeItem()
        if (active.type != "custom_tool_call") {
            protocolFailure("OpenAI custom Tool input belongs to a non-custom Tool item")
        }
        if (active.customInputFinalized) {
            protocolFailure("OpenAI emitted duplicate custom Tool input completion")
        }
        val input = root.requiredString("input", allowEmpty = true)
        if (active.customInput.isEmpty()) {
            active.customInput.append(input)
        } else if (input != active.customInput.toString()) {
            protocolFailure("OpenAI final custom Tool input does not match its deltas")
        }
        active.customInputFinalized = true
        return emptyList()
    }

    private fun decodeContentPartAdded(root: JsonObject): List<ProviderEvent> {
        val active = root.activeItem()
        val contentIndex = root.requiredIndex("content_index")
        val part = root.requiredObject("part")
        return when (active.type) {
            "message" -> {
                if (active.textParts.containsKey(contentIndex)) protocolFailure("OpenAI content index was reused")
                if (part.requiredString("type") != "output_text") {
                    protocolFailure("Unsupported OpenAI message content type")
                }
                if (part.requiredString("text", allowEmpty = true).isNotEmpty()) {
                    protocolFailure("OpenAI output text part must start empty")
                }
                active.textParts[contentIndex] = ActiveTextPart()
                listOf(ProviderEvent.TextStart())
            }
            "reasoning" -> {
                if (part.requiredString("type") != "reasoning_text") {
                    protocolFailure("Unsupported OpenAI reasoning content type")
                }
                if (part.requiredString("text", allowEmpty = true).isNotEmpty()) {
                    protocolFailure("OpenAI reasoning text part must start empty")
                }
                active.startReasoningPart(ReasoningWireKind.TEXT, contentIndex)
                listOf(ProviderEvent.ReasoningStart(kind = ReasoningContentKind.TEXT))
            }
            else -> protocolFailure("OpenAI content part belongs to an unsupported output item")
        }
    }

    private fun decodeTextDelta(root: JsonObject): List<ProviderEvent> {
        val active = root.activeItem()
        val part = active.textPart(root.requiredIndex("content_index"))
        if (part.done) protocolFailure("OpenAI emitted text delta after text done")
        val delta = root.requiredString("delta", allowEmpty = true)
        part.text.append(delta)
        return listOf(ProviderEvent.TextDelta(delta))
    }

    private fun decodeTextDone(root: JsonObject): List<ProviderEvent> {
        val active = root.activeItem()
        val part = active.textPart(root.requiredIndex("content_index"))
        if (part.done) protocolFailure("OpenAI emitted duplicate output_text.done")
        val text = root.requiredString("text", allowEmpty = true)
        if (text != part.text.toString()) protocolFailure("OpenAI final output text does not match its deltas")
        part.done = true
        return listOf(ProviderEvent.TextEnd(text = text))
    }

    private fun decodeContentPartDone(root: JsonObject): List<ProviderEvent> {
        val active = root.activeItem()
        val contentIndex = root.requiredIndex("content_index")
        val finalPart = root.requiredObject("part")
        return when (active.type) {
            "message" -> {
                val part = active.textPart(contentIndex)
                if (!part.done) protocolFailure("OpenAI content part ended before output_text.done")
                if (finalPart.requiredString("type") != "output_text" ||
                    finalPart.requiredString("text", allowEmpty = true) != part.text.toString()
                ) {
                    protocolFailure("OpenAI final content part does not match streamed text")
                }
                if (part.boundaryDone) protocolFailure("OpenAI emitted duplicate content part completion")
                part.boundaryDone = true
                emptyList()
            }
            "reasoning" -> {
                val part = active.reasoningPart(ReasoningWireKind.TEXT, contentIndex)
                if (!part.textDone) protocolFailure("OpenAI reasoning part ended before reasoning_text.done")
                if (finalPart.requiredString("type") != "reasoning_text" ||
                    finalPart.requiredString("text", allowEmpty = true) != part.text.toString()
                ) {
                    protocolFailure("OpenAI final reasoning content part does not match streamed text")
                }
                if (part.boundaryDone) protocolFailure("OpenAI emitted duplicate reasoning content part completion")
                part.boundaryDone = true
                listOf(
                    ProviderEvent.ReasoningEnd(text = part.text.toString()),
                )
            }
            else -> protocolFailure("OpenAI content part belongs to an unsupported output item")
        }
    }

    private fun decodeReasoningSummaryPartAdded(root: JsonObject): List<ProviderEvent> {
        val active = root.activeItem()
        if (active.type != "reasoning") {
            protocolFailure("OpenAI reasoning summary belongs to a non-reasoning item")
        }
        val summaryIndex = root.requiredIndex("summary_index")
        val part = root.requiredObject("part")
        if (part.requiredString("type") != "summary_text") {
            protocolFailure("Unsupported OpenAI reasoning summary type")
        }
        if (part.requiredString("text", allowEmpty = true).isNotEmpty()) {
            protocolFailure("OpenAI reasoning summary part must start empty")
        }
        active.startReasoningPart(ReasoningWireKind.SUMMARY, summaryIndex)
        return listOf(ProviderEvent.ReasoningStart(kind = ReasoningContentKind.SUMMARY))
    }

    private fun decodeReasoningSummaryPartDone(root: JsonObject): List<ProviderEvent> {
        val active = root.activeItem()
        if (active.type != "reasoning") {
            protocolFailure("OpenAI reasoning summary belongs to a non-reasoning item")
        }
        val part = active.reasoningPart(
            ReasoningWireKind.SUMMARY,
            root.requiredIndex("summary_index"),
        )
        if (!part.textDone) {
            protocolFailure("OpenAI reasoning summary part ended before summary text completion")
        }
        val finalPart = root.requiredObject("part")
        if (finalPart.requiredString("type") != "summary_text" ||
            finalPart.requiredString("text", allowEmpty = true) != part.text.toString()
        ) {
            protocolFailure("OpenAI final reasoning summary part does not match streamed text")
        }
        if (part.boundaryDone) protocolFailure("OpenAI emitted duplicate reasoning summary part completion")
        part.boundaryDone = true
        return listOf(
            ProviderEvent.ReasoningEnd(text = part.text.toString()),
        )
    }

    private fun decodeReasoningDelta(
        root: JsonObject,
        kind: ReasoningWireKind,
    ): List<ProviderEvent> {
        val active = root.activeItem()
        if (active.type != "reasoning") protocolFailure("OpenAI reasoning delta belongs to a non-reasoning item")
        val part = active.reasoningPart(kind, root.reasoningIndex(kind))
        if (part.textDone) protocolFailure("OpenAI emitted reasoning text after completion")
        val delta = root.requiredString("delta", allowEmpty = true)
        part.text.append(delta)
        return listOf(ProviderEvent.ReasoningDelta(delta))
    }

    private fun decodeReasoningTextDone(
        root: JsonObject,
        kind: ReasoningWireKind,
    ): List<ProviderEvent> {
        val active = root.activeItem()
        if (active.type != "reasoning") protocolFailure("OpenAI reasoning event belongs to a non-reasoning item")
        val part = active.reasoningPart(kind, root.reasoningIndex(kind))
        if (part.textDone) protocolFailure("OpenAI emitted duplicate reasoning text completion")
        val text = root.requiredString("text", allowEmpty = true)
        if (text != part.text.toString()) {
            protocolFailure("OpenAI final reasoning text does not match its deltas")
        }
        part.textDone = true
        return emptyList()
    }

    private fun decodeFunctionArgumentsDelta(root: JsonObject): List<ProviderEvent> {
        val active = root.activeItem()
        if (active.type != "function_call") protocolFailure("OpenAI function arguments belong to a non-function item")
        if (active.toolFinalized) protocolFailure("OpenAI emitted function arguments after finalization")
        val delta = root.requiredString("delta", allowEmpty = true)
        active.arguments.append(delta)
        return listOf(ProviderEvent.ToolCallDelta(active.callId!!, delta))
    }

    private fun decodeFunctionArgumentsDone(root: JsonObject): List<ProviderEvent> {
        val active = root.activeItem()
        if (active.type != "function_call") protocolFailure("OpenAI function arguments belong to a non-function item")
        if (active.toolFinalized) protocolFailure("OpenAI emitted duplicate function arguments done")
        val arguments = root.requiredString("arguments", allowEmpty = true)
        if (arguments != active.arguments.toString()) {
            protocolFailure("OpenAI final function arguments do not match their deltas")
        }
        val parsed = parseArguments(arguments, "OpenAI function-call arguments")
        active.finalArguments = parsed
        active.toolFinalized = true
        return listOf(ProviderEvent.ToolCallEnd(active.finalToolCall(parsed)))
    }

    private fun decodeItemDone(root: JsonObject): List<ProviderEvent> {
        val active = root.activeItem()
        val item = root.requiredObject("item")
        if (item.requiredString("id") != active.id || item.requiredString("type") != active.type) {
            protocolFailure("OpenAI final output item identity changed")
        }
        val events = when (active.type) {
            "message" -> {
                if (active.textParts.values.any { !it.done || !it.boundaryDone }) {
                    protocolFailure("OpenAI message item ended before all text content completed")
                }
                val finalText = item.requiredArray("content").map { content ->
                    val part = content as? JsonObject ?: protocolFailure("OpenAI message content must be an object")
                    if (part.requiredString("type") != "output_text") protocolFailure("Unsupported OpenAI message content type")
                    part.requiredString("text", allowEmpty = true)
                }
                if (finalText != active.textParts.entries.sortedBy { it.key }.map { it.value.text.toString() }) {
                    protocolFailure("OpenAI final message item does not match streamed content")
                }
                emptyList()
            }
            "reasoning" -> {
                val finalContent = item.reasoningContent()
                val hasVisible = finalContent.summary.any(String::isNotEmpty) ||
                    finalContent.content.any(String::isNotEmpty)
                val redacted = !hasVisible && item.optionalString("encrypted_content") != null
                buildList<ProviderEvent> {
                    addAll(
                        active.reconcileReasoningParts(
                            wireKind = ReasoningWireKind.SUMMARY,
                            contentKind = ReasoningContentKind.SUMMARY,
                            finalParts = finalContent.summary,
                            allowItemBoundaryReconciliation = dialectPolicy.reconcileReasoningAtItemBoundary,
                        ),
                    )
                    addAll(
                        active.reconcileReasoningParts(
                            wireKind = ReasoningWireKind.TEXT,
                            contentKind = ReasoningContentKind.TEXT,
                            finalParts = finalContent.content,
                            allowItemBoundaryReconciliation = dialectPolicy.reconcileReasoningAtItemBoundary,
                        ),
                    )
                    if (redacted) {
                        add(ProviderEvent.ReasoningStart(redacted = true))
                        add(ProviderEvent.ReasoningEnd(redacted = true))
                    }
                }
            }
            "function_call" -> {
                if (!active.toolFinalized) protocolFailure("OpenAI function item ended before arguments done")
                if (item.requiredString("call_id") != active.callId || item.requiredString("name") != active.functionName) {
                    protocolFailure("OpenAI final function call identity changed")
                }
                if (parseArguments(item.requiredString("arguments", allowEmpty = true), "OpenAI final function-call arguments") != active.finalArguments) {
                    protocolFailure("OpenAI final function-call arguments changed")
                }
                emptyList<ProviderEvent>()
            }
            "x_search_call" -> {
                requireXSearchOutput()
                item.validateXSearchCall(final = true)
                emptyList()
            }
            "custom_tool_call" -> {
                requireServerManagedCustomToolCalls()
                item.validateCustomToolCall(final = true)
                if (
                    item.requiredString("call_id") != active.callId ||
                    item.requiredString("name") != active.functionName
                ) {
                    protocolFailure("OpenAI final custom Tool call identity changed")
                }
                val input = item.optionalString("input").orEmpty()
                if (active.customInput.isNotEmpty() && input != active.customInput.toString()) {
                    protocolFailure("OpenAI final custom Tool input changed")
                }
                emptyList()
            }
            else -> protocolFailure("Unsupported OpenAI output item type ${active.type}")
        }
        completedItems[active.index] = item
        activeItems.remove(active.index)
        return events
    }

    private fun decodeTerminal(root: JsonObject): List<ProviderEvent> {
        requireCreated()
        if (activeItems.isNotEmpty()) protocolFailure("OpenAI response terminated with active output items")
        val response = root.requiredObject("response")
        requireResponseId(response.requiredString("id"))
        val output = response.requiredArray("output")
        val completed = completedItems.entries.sortedBy { it.key }.map { it.value }
        if (output.toList() != completed) protocolFailure("OpenAI terminal output does not match completed items")
        val event = completedEvent(response, output)
        terminal = true
        return listOf(event)
    }

    private fun decodeFailure(root: JsonObject): List<ProviderEvent> {
        val response = root["response"] as? JsonObject
        response?.optionalString("id")?.let(::requireResponseId)
        terminal = true
        val envelope = response ?: root
        val error = (envelope["error"] as? JsonObject) ?: (root["error"] as? JsonObject)
        val metadata = error?.get("metadata") as? JsonObject
        throwOpenAiInBandFailure(
            label = "OpenAI Responses",
            code = error?.optionalString("code") ?: error?.optionalString("type"),
            errorType = envelope.optionalString("error_type") ?: metadata?.optionalString("error_type"),
            providerMessage = error?.optionalString("message"),
        )
    }

    private fun decodeCompleteItem(item: JsonObject): List<ProviderEvent> = when (val type = item.requiredString("type")) {
        "message" -> buildList {
            if (item.requiredString("role") != "assistant") protocolFailure("OpenAI output message role must be assistant")
            item.requiredArray("content").forEach { element ->
                val content = element as? JsonObject ?: protocolFailure("OpenAI message content must be an object")
                if (content.requiredString("type") != "output_text") protocolFailure("Unsupported OpenAI message content type")
                val text = content.requiredString("text", allowEmpty = true)
                add(ProviderEvent.TextStart())
                add(ProviderEvent.TextDelta(text))
                add(ProviderEvent.TextEnd(text))
            }
        }
        "reasoning" -> {
            val content = item.reasoningContent()
            val hasVisible = content.summary.any(String::isNotEmpty) || content.content.any(String::isNotEmpty)
            val redacted = !hasVisible && item.optionalString("encrypted_content") != null
            buildList {
                addAll(content.summary.completeReasoningEvents(ReasoningContentKind.SUMMARY))
                addAll(content.content.completeReasoningEvents(ReasoningContentKind.TEXT))
                if (redacted) {
                    add(ProviderEvent.ReasoningStart(redacted = true))
                    add(ProviderEvent.ReasoningEnd(redacted = true))
                }
            }
        }
        "function_call" -> {
            val callId = item.requiredString("call_id")
            val itemId = item.requiredString("id")
            val name = item.requiredString("name")
            val arguments = parseArguments(item.requiredString("arguments", allowEmpty = true), "OpenAI function-call arguments")
            val call = ToolCallPart(
                toolCallId = callId,
                toolName = name,
                arguments = arguments,
                partial = false,
                providerCallId = itemId,
                providerMetadata = item,
            )
            listOf(
                ProviderEvent.ToolCallStart(call.copy(arguments = JsonObject(emptyMap()), partial = true)),
                ProviderEvent.ToolCallEnd(call),
            )
        }
        "x_search_call" -> {
            requireXSearchOutput()
            item.validateXSearchCall(final = true)
            emptyList()
        }
        "custom_tool_call" -> {
            requireServerManagedCustomToolCalls()
            item.validateCustomToolCall(final = true)
            emptyList()
        }
        else -> protocolFailure("Unsupported OpenAI output item type $type")
    }

    private fun completedEvent(response: JsonObject, output: JsonArray): ProviderEvent.Completed {
        val status = response.requiredString("status")
        if (status !in setOf("completed", "incomplete")) {
            protocolFailure("OpenAI response ended with unsupported status $status")
        }
        val hasToolCall = output.any { (it as? JsonObject)?.optionalString("type") == "function_call" }
        val stopReason = when {
            hasToolCall -> StopReason.TOOL_CALLS
            status == "incomplete" &&
                (response["incomplete_details"] as? JsonObject)?.optionalString("reason") == "max_output_tokens" -> StopReason.MAX_TOKENS
            status == "incomplete" -> StopReason.ERROR
            else -> StopReason.COMPLETED
        }
        return ProviderEvent.Completed(
            finishReason = status,
            stopReason = stopReason,
            usage = (response["usage"] as? JsonObject)?.toUsage(),
            providerMetadata = buildJsonObject {
                put("provider", providerKey)
                put("model", model)
                put(OPENAI_RESPONSE_ID_METADATA, response.requiredString("id"))
                put(OPENAI_RESPONSE_STATUS_METADATA, status)
                put(OPENAI_RESPONSE_OUTPUT_METADATA, output)
                response.normalizedCitations(output).takeIf(JsonArray::isNotEmpty)?.let { citations ->
                    put(PROVIDER_CITATIONS_METADATA_KEY, citations)
                }
            },
        )
    }

    private fun JsonObject.activeItem(): ActiveOutputItem {
        requireCreated()
        val index = requiredIndex("output_index")
        val active = activeItems[index] ?: protocolFailure("OpenAI event references an inactive output item")
        optionalString("item_id")?.let { if (it != active.id) protocolFailure("OpenAI event item ID changed") }
        return active
    }

    private fun ensurePristine() {
        if (created || terminal || activeItems.isNotEmpty() || completedItems.isNotEmpty()) {
            protocolFailure("OpenAI codec instance can decode only one response")
        }
    }

    private fun requireCreated() {
        if (!created) protocolFailure("OpenAI event arrived before response.created")
    }

    private fun requireResponseId(id: String) {
        if (id != responseId) protocolFailure("OpenAI response ID changed")
    }

    private fun requireServerManagedCustomToolCalls() {
        if (!dialectPolicy.allowServerManagedCustomToolCalls) {
            protocolFailure("Unsupported OpenAI output item type custom_tool_call")
        }
    }

    private fun requireXSearchOutput() {
        if (!dialectPolicy.allowXSearchOutput) {
            protocolFailure("Unsupported OpenAI output item type x_search_call")
        }
    }
}

private fun JsonObject.validateXSearchCall(final: Boolean) {
    val id = if ("id" in this) requiredString("id") else null
    val callId = if ("call_id" in this) requiredString("call_id") else null
    if (id == null && callId == null) {
        protocolFailure("OpenAI X Search output item is missing an identity")
    }
    if ("arguments" in this) requiredString("arguments", allowEmpty = true)
    if ("name" in this) requiredString("name")
    if ("status" in this) {
        val status = requiredString("status")
        val validStatuses = if (final) {
            setOf("completed", "incomplete")
        } else {
            setOf("queued", "in_progress")
        }
        if (status !in validStatuses) {
            protocolFailure("OpenAI X Search output item has an invalid status")
        }
    }
}

private fun JsonObject.validateCustomToolCall(final: Boolean) {
    requiredString("id")
    requiredString("call_id")
    requiredString("name")
    if ("input" in this) requiredString("input", allowEmpty = true)
    if ("status" in this) {
        val status = requiredString("status")
        val validStatuses = if (final) {
            setOf("completed", "incomplete")
        } else {
            setOf("queued", "in_progress")
        }
        if (status !in validStatuses) {
            protocolFailure("OpenAI custom Tool call has an invalid status")
        }
    }
}

private fun JsonObject.normalizedCitations(output: JsonArray): JsonArray {
    val annotationTitles = linkedMapOf<String, String>()
    output.forEach { outputItem ->
        val item = outputItem as? JsonObject ?: return@forEach
        if (item.optionalString("type") != "message") return@forEach
        (item["content"] as? JsonArray).orEmpty().forEach { contentItem ->
            val content = contentItem as? JsonObject ?: return@forEach
            if (content.optionalString("type") != "output_text") return@forEach
            val annotations = content["annotations"] ?: return@forEach
            val array = annotations as? JsonArray
                ?: protocolFailure("OpenAI output text annotations must be an array")
            array.forEach { annotationItem ->
                val annotation = annotationItem as? JsonObject
                    ?: protocolFailure("OpenAI output text annotation must be an object")
                if (annotation.requiredString("type") != "url_citation") {
                    protocolFailure("Unsupported OpenAI output text annotation type")
                }
                val url = annotation.requiredString("url")
                if (url !in annotationTitles) {
                    annotationTitles[url] = annotation.optionalString("title").orEmpty()
                }
            }
        }
    }
    val urls = linkedSetOf<String>()
    val allCitations = this["citations"]
    when (allCitations) {
        null -> Unit
        is JsonArray -> allCitations.forEach { citation ->
            val url = (citation as? JsonPrimitive)?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: protocolFailure("OpenAI response citation must be a non-blank URL string")
            urls += url
        }
        else -> protocolFailure("OpenAI response citations must be an array")
    }
    urls += annotationTitles.keys
    return buildJsonArray {
        urls.forEach { url ->
            add(buildJsonObject {
                val annotationTitle = annotationTitles[url].orEmpty()
                put(
                    "title",
                    annotationTitle.takeUnless { title -> title.all(Char::isDigit) }.orEmpty().ifBlank { url },
                )
                put("url", url)
                put("snippet", "")
            })
        }
    }
}

private data class ActiveOutputItem(
    val index: Int,
    val id: String,
    val type: String,
    val start: JsonObject,
    val textParts: MutableMap<Int, ActiveTextPart> = linkedMapOf(),
    val reasoningParts: MutableMap<ReasoningPartKey, ActiveReasoningPart> = linkedMapOf(),
    val arguments: StringBuilder = StringBuilder(),
    val customInput: StringBuilder = StringBuilder(),
    var callId: String? = null,
    var functionName: String? = null,
    var finalArguments: JsonObject? = null,
    var toolFinalized: Boolean = false,
    var customInputFinalized: Boolean = false,
) {
    fun textPart(index: Int): ActiveTextPart = textParts[index]
        ?: protocolFailure("OpenAI text event references an inactive content part")

    fun startReasoningPart(kind: ReasoningWireKind, index: Int) {
        val parts = reasoningPartsFor(kind)
        val key = ReasoningPartKey(kind, index)
        if (index != parts.size || reasoningParts.containsKey(key)) {
            protocolFailure("OpenAI reasoning content index is out of order")
        }
        reasoningParts[key] = ActiveReasoningPart(kind)
    }

    fun reasoningPart(kind: ReasoningWireKind, index: Int): ActiveReasoningPart {
        val part = reasoningParts[ReasoningPartKey(kind, index)]
            ?: protocolFailure("OpenAI reasoning event references an inactive content part")
        if (part.kind != kind) protocolFailure("OpenAI reasoning event changed content kind")
        return part
    }

    fun reasoningPartsFor(kind: ReasoningWireKind): List<ActiveReasoningPart> = reasoningParts.entries
        .filter { (key, _) -> key.kind == kind }
        .sortedBy { (key, _) -> key.index }
        .map(Map.Entry<ReasoningPartKey, ActiveReasoningPart>::value)

    fun reconcileReasoningParts(
        wireKind: ReasoningWireKind,
        contentKind: ReasoningContentKind,
        finalParts: List<String>,
        allowItemBoundaryReconciliation: Boolean,
    ): List<ProviderEvent> {
        val streamedParts = reasoningPartsFor(wireKind)
        if (streamedParts.isEmpty()) return finalParts.completeReasoningEvents(contentKind)
        if (streamedParts.size != finalParts.size) {
            protocolFailure("OpenAI final reasoning item changed its content-part count")
        }
        if (!allowItemBoundaryReconciliation && streamedParts.any { part -> !part.boundaryDone }) {
            protocolFailure("OpenAI reasoning item ended before its nested content lifecycle completed")
        }

        return buildList {
            streamedParts.zip(finalParts).forEach { (part, finalText) ->
                val streamedText = part.text.toString()
                when {
                    part.boundaryDone -> {
                        if (!part.textDone || finalText != streamedText) {
                            protocolFailure("OpenAI final reasoning item changed completed reasoning")
                        }
                    }
                    part.textDone -> {
                        if (finalText != streamedText) {
                            protocolFailure("OpenAI final reasoning item changed completed reasoning text")
                        }
                        part.boundaryDone = true
                        add(ProviderEvent.ReasoningEnd(text = finalText))
                    }
                    !finalText.startsWith(streamedText) -> {
                        protocolFailure("OpenAI final reasoning item does not extend streamed reasoning")
                    }
                    else -> {
                        val authoritativeSuffix = finalText.substring(streamedText.length)
                        if (authoritativeSuffix.isNotEmpty()) {
                            part.text.append(authoritativeSuffix)
                            add(ProviderEvent.ReasoningDelta(authoritativeSuffix))
                        }
                        part.textDone = true
                        part.boundaryDone = true
                        add(ProviderEvent.ReasoningEnd(text = finalText))
                    }
                }
            }
        }
    }

    fun partialToolCall(): ToolCallPart = ToolCallPart(
        toolCallId = callId!!,
        toolName = functionName!!,
        arguments = JsonObject(emptyMap()),
        partial = true,
        providerCallId = id,
        providerMetadata = start,
    )

    fun finalToolCall(arguments: JsonObject): ToolCallPart = ToolCallPart(
        toolCallId = callId!!,
        toolName = functionName!!,
        arguments = arguments,
        partial = false,
        providerCallId = id,
        providerMetadata = start,
    )
}

private data class ActiveTextPart(
    val text: StringBuilder = StringBuilder(),
    var done: Boolean = false,
    var boundaryDone: Boolean = false,
)

private data class ActiveReasoningPart(
    val kind: ReasoningWireKind,
    val text: StringBuilder = StringBuilder(),
    var textDone: Boolean = false,
    var boundaryDone: Boolean = false,
)

private data class ReasoningPartKey(
    val kind: ReasoningWireKind,
    val index: Int,
)

private enum class ReasoningWireKind {
    SUMMARY,
    TEXT,
}

private data class FinalReasoningContent(
    val summary: List<String>,
    val content: List<String>,
)

private fun JsonObject.reasoningContent(): FinalReasoningContent {
    val summary = optionalArray("summary").orEmpty()
    val content = optionalArray("content").orEmpty()
    return FinalReasoningContent(
        summary = summary.reasoningStrings("summary_text"),
        content = content.reasoningStrings("reasoning_text"),
    )
}

private fun List<JsonElement>.reasoningStrings(expectedType: String): List<String> = map { element ->
        val item = element as? JsonObject ?: protocolFailure("OpenAI reasoning content must be an object")
        val type = item.requiredString("type")
        if (type != expectedType) protocolFailure("Unsupported OpenAI reasoning content type $type")
        item.requiredString("text", allowEmpty = true)
    }

private fun List<String>.completeReasoningEvents(kind: ReasoningContentKind): List<ProviderEvent> =
    flatMap { text ->
        listOf(
            ProviderEvent.ReasoningStart(kind = kind),
            ProviderEvent.ReasoningDelta(text),
            ProviderEvent.ReasoningEnd(text = text),
        )
    }

private fun JsonObject.reasoningIndex(kind: ReasoningWireKind): Int = requiredIndex(
    when (kind) {
        ReasoningWireKind.SUMMARY -> "summary_index"
        ReasoningWireKind.TEXT -> "content_index"
    },
)

private fun JsonObject.toUsage(): ProviderUsage = ProviderUsage(
    inputTokens = (this["input_tokens"] as? JsonPrimitive)?.intOrNull,
    outputTokens = (this["output_tokens"] as? JsonPrimitive)?.intOrNull,
    reasoningTokens = ((this["output_tokens_details"] as? JsonObject)?.get("reasoning_tokens") as? JsonPrimitive)?.intOrNull,
)

private fun parseArguments(value: String, label: String): JsonObject {
    if (value.isBlank()) return JsonObject(emptyMap())
    val parsed = try {
        Json.parseToJsonElement(value)
    } catch (failure: Throwable) {
        throw ProviderProtocolException("Malformed $label", failure)
    }
    return parsed as? JsonObject ?: protocolFailure("$label must decode to an object")
}

private fun parseObject(payload: String, label: String): JsonObject = try {
    Json.parseToJsonElement(payload) as? JsonObject ?: protocolFailure("$label must be a JSON object")
} catch (failure: ProviderProtocolException) {
    throw failure
} catch (failure: Throwable) {
    throw ProviderProtocolException("Malformed $label", failure)
}

private fun JsonObject.requiredObject(key: String): JsonObject =
    this[key] as? JsonObject ?: protocolFailure("OpenAI payload is missing object field $key")

private fun JsonObject.requiredArray(key: String): JsonArray =
    this[key] as? JsonArray ?: protocolFailure("OpenAI payload is missing array field $key")

private fun JsonObject.optionalArray(key: String): JsonArray? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonArray -> value
    else -> protocolFailure("OpenAI payload field $key must be an array")
}

private fun JsonObject.requiredString(key: String, allowEmpty: Boolean = false): String {
    val value = (this[key] as? JsonPrimitive)?.contentOrNull
        ?: protocolFailure("OpenAI payload is missing string field $key")
    if (!allowEmpty && value.isBlank()) protocolFailure("OpenAI payload field $key must not be blank")
    return value
}

private fun JsonObject.optionalString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.requiredIndex(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
        ?: protocolFailure("OpenAI payload is missing non-negative integer field $key")

private fun protocolFailure(message: String): Nothing = throw ProviderProtocolException(message)

private val X_SEARCH_PROGRESS_EVENTS = setOf(
    "response.x_search_call.in_progress",
    "response.x_search_call.searching",
    "response.x_search_call.completed",
)

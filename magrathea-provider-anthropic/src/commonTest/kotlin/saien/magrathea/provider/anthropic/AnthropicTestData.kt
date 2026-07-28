package saien.magrathea.provider.anthropic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import saien.magrathea.provider.api.HttpRequestSpec
import saien.magrathea.provider.api.HttpResponseSpec
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpStreamFrame
import saien.magrathea.provider.api.HttpTransport

/*
 * Fixture provenance (reviewed 2026-07-11): shapes are adapted from the official Anthropic
 * Messages streaming/extended-thinking documentation linked by ADR-007. IDs, model names,
 * prompts, tool data, text, usage values, and response bodies are synthetic; no live payload or
 * credential is retained. The transcript intentionally exercises named SSE and block indexes.
 */
internal const val ANTHROPIC_TOOL_CONTENT =
    """[{"type":"text","text":"I'll check."},{"type":"tool_use","id":"toolu_weather_1","name":"get_weather","input":{"city":"Shanghai"}}]"""
internal const val ANTHROPIC_TOOL_RESPONSE =
    """{"id":"msg_tool_1","role":"assistant","model":"claude-contract","content":$ANTHROPIC_TOOL_CONTENT,"stop_reason":"tool_use","usage":{"input_tokens":12,"output_tokens":8,"output_tokens_details":{"thinking_tokens":2}}}"""
internal const val ANTHROPIC_TEXT_RESPONSE =
    """{"id":"msg_final_1","role":"assistant","model":"claude-contract","content":[{"type":"text","text":"Shanghai is sunny."}],"stop_reason":"end_turn","usage":{"input_tokens":20,"output_tokens":5,"output_tokens_details":{"thinking_tokens":0}}}"""

internal val ANTHROPIC_TOOL_STREAM = listOf(
    "message_start" to """{"type":"message_start","message":{"id":"msg_tool_1","role":"assistant","model":"claude-contract","content":[],"stop_reason":null,"usage":{"input_tokens":12,"output_tokens":1}}}""",
    "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
    "ping" to """{"type":"ping"}""",
    "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"I'll check."}}""",
    "content_block_stop" to """{"type":"content_block_stop","index":0}""",
    "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_weather_1","name":"get_weather","input":{}}}""",
    "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":""}}""",
    "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}""",
    "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"Shanghai\"}"}}""",
    "content_block_stop" to """{"type":"content_block_stop","index":1}""",
    "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":8,"output_tokens_details":{"thinking_tokens":2}}}""",
    "message_stop" to """{"type":"message_stop"}""",
)

internal val ANTHROPIC_TEXT_STREAM = listOf(
    "message_start" to """{"type":"message_start","message":{"id":"msg_final_1","role":"assistant","model":"claude-contract","content":[],"stop_reason":null,"usage":{"input_tokens":20,"output_tokens":1}}}""",
    "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
    "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Shanghai is "}}""",
    "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"sunny."}}""",
    "content_block_stop" to """{"type":"content_block_stop","index":0}""",
    "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5,"output_tokens_details":{"thinking_tokens":0}}}""",
    "message_stop" to """{"type":"message_stop"}""",
)

internal fun anthropicSseFrames(events: List<Pair<String, String>>): List<HttpStreamFrame> = buildList {
    add(HttpStreamFrame.ResponseStarted(200, emptyList()))
    events.forEach { (event, data) -> add(HttpStreamFrame.ServerSentEvent(event, data, null)) }
    add(HttpStreamFrame.Completed)
}

internal class ScriptedAnthropicTransport(
    executeResponses: List<HttpResponseSpec> = emptyList(),
    streamResponses: List<List<HttpStreamFrame>> = emptyList(),
) : HttpTransport {
    private val executeQueue = ArrayDeque(executeResponses)
    private val streamQueue = ArrayDeque(streamResponses)
    val requests = mutableListOf<Pair<HttpRequestSpec, HttpStreamFormat?>>()
    var closed = false
        private set

    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        requests += request to null
        return executeQueue.removeFirstOrNull() ?: error("No scripted Anthropic response")
    }

    override fun stream(request: HttpRequestSpec, format: HttpStreamFormat): Flow<HttpStreamFrame> = flow {
        requests += request to format
        (streamQueue.removeFirstOrNull() ?: error("No scripted Anthropic stream")).forEach { emit(it) }
    }

    override fun close() {
        closed = true
    }
}

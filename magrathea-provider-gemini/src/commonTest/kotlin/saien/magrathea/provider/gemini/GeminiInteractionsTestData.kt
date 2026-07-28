package saien.magrathea.provider.gemini

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import saien.magrathea.provider.api.HttpHeader
import saien.magrathea.provider.api.HttpRequestSpec
import saien.magrathea.provider.api.HttpResponseSpec
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpStreamFrame
import saien.magrathea.provider.api.HttpStreamFramer
import saien.magrathea.provider.api.HttpTransport

internal const val TOOL_INTERACTION_SSE = """event: interaction.created
data: {"interaction":{"id":"v1_tool","model":"gemini-contract-model","status":"in_progress","object":"interaction"},"event_type":"interaction.created"}

event: interaction.status_update
data: {"interaction_id":"v1_tool","status":"in_progress","event_type":"interaction.status_update"}

event: step.start
data: {"index":0,"step":{"type":"thought"},"event_type":"step.start"}

event: step.delta
data: {"index":0,"delta":{"type":"thought_summary","content":{"type":"text","text":"I should verify the weather."}},"event_type":"step.delta"}

event: step.delta
data: {"index":0,"delta":{"type":"thought_signature","signature":"thought-sig-1"},"event_type":"step.delta"}

event: step.stop
data: {"index":0,"event_type":"step.stop"}

event: step.start
data: {"index":1,"step":{"type":"model_output"},"event_type":"step.start"}

event: step.delta
data: {"index":1,"delta":{"type":"text","text":"I'll check "},"event_type":"step.delta"}

event: step.delta
data: {"index":1,"delta":{"type":"text","text":"the live weather. "},"event_type":"step.delta"}

event: step.stop
data: {"index":1,"event_type":"step.stop"}

event: step.start
data: {"index":2,"step":{"type":"function_call","id":"call-weather-1","name":"get_weather","arguments":{}},"event_type":"step.start"}

event: step.delta
data: {"index":2,"delta":{"type":"arguments_delta","arguments":"{\"city\":\"Shang"},"event_type":"step.delta"}

event: step.delta
data: {"index":2,"delta":{"type":"arguments_delta","arguments":"hai\"}"},"event_type":"step.delta"}

event: step.stop
data: {"index":2,"event_type":"step.stop"}

event: interaction.completed
data: {"interaction":{"id":"v1_tool","status":"requires_action","usage":{"total_input_tokens":12,"total_output_tokens":8,"total_thought_tokens":2}},"event_type":"interaction.completed"}

event: done
data: [DONE]
"""

internal const val FINAL_INTERACTION_SSE = """event: interaction.created
data: {"interaction":{"id":"v1_final","model":"gemini-contract-model","status":"in_progress","object":"interaction"},"event_type":"interaction.created"}

event: step.start
data: {"index":0,"step":{"type":"model_output"},"event_type":"step.start"}

event: step.delta
data: {"index":0,"delta":{"type":"text","text":"Shanghai is sunny "},"event_type":"step.delta"}

event: step.delta
data: {"index":0,"delta":{"type":"text","text":"and 27°C."},"event_type":"step.delta"}

event: step.stop
data: {"index":0,"event_type":"step.stop"}

event: interaction.completed
data: {"interaction":{"id":"v1_final","status":"completed","usage":{"total_input_tokens":24,"total_output_tokens":6,"total_thought_tokens":0}},"event_type":"interaction.completed"}

event: done
data: [DONE]
"""

internal const val TOOL_INTERACTION_JSON = """{
  "id":"v1_tool",
  "model":"gemini-contract-model",
  "status":"requires_action",
  "object":"interaction",
  "steps":[
    {"type":"thought","signature":"thought-sig-1","summary":[{"type":"text","text":"I should verify the weather."}]},
    {"type":"model_output","content":[{"type":"text","text":"I'll check the live weather. "}]},
    {"type":"function_call","id":"call-weather-1","name":"get_weather","arguments":{"city":"Shanghai"}}
  ],
  "usage":{"total_input_tokens":12,"total_output_tokens":8,"total_thought_tokens":2}
}"""

internal fun sseFrames(transcript: String): List<HttpStreamFrame> {
    val framer = HttpStreamFramer(HttpStreamFormat.SERVER_SENT_EVENTS)
    return buildList {
        add(HttpStreamFrame.ResponseStarted(200, listOf(HttpHeader("Content-Type", "text/event-stream"))))
        transcript.split('\n').forEach { addAll(framer.accept(it.removeSuffix("\r"))) }
        addAll(framer.finish())
    }
}

internal class ScriptedHttpTransport(
    streamScripts: List<List<HttpStreamFrame>> = emptyList(),
    executeScripts: List<HttpResponseSpec> = emptyList(),
) : HttpTransport {
    private val streams = ArrayDeque(streamScripts)
    private val responses = ArrayDeque(executeScripts)
    val requests = mutableListOf<HttpRequestSpec>()
    var closed: Boolean = false
        private set

    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        requests += request
        return responses.removeFirstOrNull() ?: error("No scripted HTTP response")
    }

    override fun stream(request: HttpRequestSpec, format: HttpStreamFormat): Flow<HttpStreamFrame> = flow {
        requests += request
        check(format == HttpStreamFormat.SERVER_SENT_EVENTS)
        val frames = streams.removeFirstOrNull() ?: error("No scripted HTTP stream")
        frames.forEach { emit(it) }
    }

    override fun close() {
        closed = true
    }
}

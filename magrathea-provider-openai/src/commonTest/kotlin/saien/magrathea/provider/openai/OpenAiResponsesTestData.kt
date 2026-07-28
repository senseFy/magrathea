package saien.magrathea.provider.openai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import saien.magrathea.provider.api.HttpRequestSpec
import saien.magrathea.provider.api.HttpResponseSpec
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpStreamFrame
import saien.magrathea.provider.api.HttpTransport

/*
 * Fixture provenance (reviewed 2026-07-17): shapes are adapted from the official OpenAI
 * Responses create/function-calling documentation linked by ADR-007 and the official xAI
 * Responses OpenAPI schema for hosted X Search. The compacted reasoning fixture retains only the
 * event structure observed in a controlled OpenRouter Responses compatibility run: visible
 * summary deltas followed by an authoritative reasoning output item without the two nested done
 * events. IDs, model names, prompts, arguments, text, usage values, and response bodies are
 * synthetic; no live payload or credential is retained. The transcript intentionally keeps
 * item_id and call_id distinct.
 */
internal const val OPENAI_TOOL_ITEM =
    """{"type":"function_call","id":"fc_weather_1","call_id":"call_weather_1","name":"get_weather","arguments":"{\"city\":\"Shanghai\"}","status":"completed"}"""

internal const val OPENAI_TOOL_RESPONSE =
    """{"id":"resp_tool_1","status":"completed","model":"gpt-contract","output":[$OPENAI_TOOL_ITEM],"usage":{"input_tokens":10,"output_tokens":4,"output_tokens_details":{"reasoning_tokens":1}}}"""

internal const val OPENAI_TEXT_ITEM =
    """{"id":"msg_final_1","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"Shanghai is sunny.","annotations":[]}]}"""

internal const val OPENAI_TEXT_RESPONSE =
    """{"id":"resp_final_1","status":"completed","model":"gpt-contract","output":[$OPENAI_TEXT_ITEM],"usage":{"input_tokens":20,"output_tokens":5,"output_tokens_details":{"reasoning_tokens":0}}}"""

internal const val OPENAI_REASONING_TEXT_ITEM =
    """{"id":"rs_text_1","type":"reasoning","status":"completed","content":[{"type":"reasoning_text","text":"I should answer directly."}]}"""

internal const val OPENAI_REASONING_TEXT_RESPONSE =
    """{"id":"resp_reasoning_text_1","status":"completed","model":"compatible-reasoning-model","output":[$OPENAI_REASONING_TEXT_ITEM,$OPENAI_TEXT_ITEM],"usage":{"input_tokens":20,"output_tokens":12,"output_tokens_details":{"reasoning_tokens":7}}}"""

internal const val OPENAI_REASONING_SUMMARY_ITEM =
    """{"id":"rs_summary_1","type":"reasoning","status":"completed","encrypted_content":"opaque-continuity-state","summary":[{"type":"summary_text","text":"I should answer directly."}]}"""

internal const val OPENAI_REASONING_SUMMARY_RESPONSE =
    """{"id":"resp_reasoning_summary_1","status":"completed","model":"openai-reasoning-model","output":[$OPENAI_REASONING_SUMMARY_ITEM,$OPENAI_TEXT_ITEM],"usage":{"input_tokens":20,"output_tokens":12,"output_tokens_details":{"reasoning_tokens":7}}}"""

internal const val OPENAI_MIXED_REASONING_ITEM =
    """{"id":"rs_mixed_1","type":"reasoning","status":"completed","summary":[{"type":"summary_text","text":"Checked the constraints."}],"content":[{"type":"reasoning_text","text":"Provider-visible reasoning."}],"encrypted_content":"opaque-continuity-state"}"""

internal const val OPENAI_MIXED_REASONING_RESPONSE =
    """{"id":"resp_mixed_reasoning_1","status":"completed","model":"mixed-reasoning-model","output":[$OPENAI_MIXED_REASONING_ITEM],"usage":{"input_tokens":5,"output_tokens":6,"output_tokens_details":{"reasoning_tokens":4}}}"""

internal const val OPENAI_X_SEARCH_ITEM =
    """{"id":"xs_kmp_1","call_id":"call_x_kmp_1","type":"x_search_call","status":"completed","name":"x_keyword_search","arguments":"{\"query\":\"Kotlin Multiplatform\"}"}"""

internal const val XAI_SCHEMA_X_SEARCH_ITEM =
    """{"call_id":"call_x_schema_1","type":"x_search_call","name":"x_keyword_search","arguments":"{\"query\":\"Kotlin Multiplatform\"}"}"""

internal const val OPENAI_X_SEARCH_TEXT_ITEM =
    """{"id":"msg_x_search_1","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"KMP is being discussed.[[1]](https://x.com/kotlin/status/1)","annotations":[{"type":"url_citation","url":"https://x.com/kotlin/status/1","start_index":23,"end_index":63,"title":"1"}]}]}"""

internal const val OPENAI_X_SEARCH_RESPONSE =
    """{"id":"resp_x_search_1","status":"completed","model":"grok-contract","output":[$OPENAI_X_SEARCH_ITEM,$OPENAI_X_SEARCH_TEXT_ITEM],"citations":["https://x.com/kotlin/status/1","https://x.com/gradle/status/2"],"usage":{"input_tokens":30,"output_tokens":12,"output_tokens_details":{"reasoning_tokens":2}}}"""

internal const val XAI_SCHEMA_X_SEARCH_RESPONSE =
    """{"id":"resp_x_schema_1","status":"completed","model":"grok-contract","output":[$XAI_SCHEMA_X_SEARCH_ITEM,$OPENAI_X_SEARCH_TEXT_ITEM],"citations":["https://x.com/kotlin/status/1"],"usage":{"input_tokens":30,"output_tokens":12,"output_tokens_details":{"reasoning_tokens":2}}}"""

internal const val XAI_HOSTED_X_SEARCH_CUSTOM_TOOL_ITEM =
    """{"id":"ctc_x_schema_1","call_id":"call_x_schema_1","type":"custom_tool_call","status":"completed","name":"x_keyword_search","input":"{\"query\":\"Kotlin Multiplatform\"}"}"""

internal const val XAI_HOSTED_X_SEARCH_RESPONSE =
    """{"id":"resp_x_hosted_1","status":"completed","model":"grok-contract","output":[$XAI_HOSTED_X_SEARCH_CUSTOM_TOOL_ITEM,$OPENAI_X_SEARCH_TEXT_ITEM],"usage":{"input_tokens":30,"output_tokens":12,"output_tokens_details":{"reasoning_tokens":2}}}"""

internal val OPENAI_TOOL_STREAM = listOf(
    "response.created" to """{"type":"response.created","response":{"id":"resp_tool_1","status":"in_progress"}}""",
    "response.in_progress" to """{"type":"response.in_progress","response":{"id":"resp_tool_1","status":"in_progress"}}""",
    "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_weather_1","call_id":"call_weather_1","name":"get_weather","arguments":"","status":"in_progress"}}""",
    "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","item_id":"fc_weather_1","output_index":0,"delta":"{\"city\":"}""",
    "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","item_id":"fc_weather_1","output_index":0,"delta":"\"Shanghai\"}"}""",
    "response.function_call_arguments.done" to """{"type":"response.function_call_arguments.done","item_id":"fc_weather_1","output_index":0,"arguments":"{\"city\":\"Shanghai\"}"}""",
    "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":$OPENAI_TOOL_ITEM}""",
    "response.completed" to """{"type":"response.completed","response":$OPENAI_TOOL_RESPONSE}""",
)

internal val OPENAI_TEXT_STREAM = listOf(
    "response.created" to """{"type":"response.created","response":{"id":"resp_final_1","status":"in_progress"}}""",
    "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"id":"msg_final_1","type":"message","status":"in_progress","role":"assistant","content":[]}}""",
    "response.content_part.added" to """{"type":"response.content_part.added","item_id":"msg_final_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}""",
    "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_final_1","output_index":0,"content_index":0,"delta":"Shanghai is "}""",
    "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_final_1","output_index":0,"content_index":0,"delta":"sunny."}""",
    "response.output_text.done" to """{"type":"response.output_text.done","item_id":"msg_final_1","output_index":0,"content_index":0,"text":"Shanghai is sunny."}""",
    "response.content_part.done" to """{"type":"response.content_part.done","item_id":"msg_final_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":"Shanghai is sunny.","annotations":[]}}""",
    "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":$OPENAI_TEXT_ITEM}""",
    "response.completed" to """{"type":"response.completed","response":$OPENAI_TEXT_RESPONSE}""",
)

internal val OPENAI_REASONING_TEXT_STREAM = listOf(
    "response.created" to """{"type":"response.created","response":{"id":"resp_reasoning_text_1","status":"in_progress"}}""",
    "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"id":"rs_text_1","type":"reasoning","status":"in_progress","content":[]}}""",
    "response.content_part.added" to """{"type":"response.content_part.added","item_id":"rs_text_1","output_index":0,"content_index":0,"part":{"type":"reasoning_text","text":""}}""",
    "response.reasoning_text.delta" to """{"type":"response.reasoning_text.delta","item_id":"rs_text_1","output_index":0,"content_index":0,"delta":"I should "}""",
    "response.reasoning_text.delta" to """{"type":"response.reasoning_text.delta","item_id":"rs_text_1","output_index":0,"content_index":0,"delta":"answer directly."}""",
    "response.reasoning_text.done" to """{"type":"response.reasoning_text.done","item_id":"rs_text_1","output_index":0,"content_index":0,"text":"I should answer directly."}""",
    "response.content_part.done" to """{"type":"response.content_part.done","item_id":"rs_text_1","output_index":0,"content_index":0,"part":{"type":"reasoning_text","text":"I should answer directly."}}""",
    "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":$OPENAI_REASONING_TEXT_ITEM}""",
    "response.output_item.added" to """{"type":"response.output_item.added","output_index":1,"item":{"id":"msg_final_1","type":"message","status":"in_progress","role":"assistant","content":[]}}""",
    "response.content_part.added" to """{"type":"response.content_part.added","item_id":"msg_final_1","output_index":1,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}""",
    "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_final_1","output_index":1,"content_index":0,"delta":"Shanghai is sunny."}""",
    "response.output_text.done" to """{"type":"response.output_text.done","item_id":"msg_final_1","output_index":1,"content_index":0,"text":"Shanghai is sunny."}""",
    "response.content_part.done" to """{"type":"response.content_part.done","item_id":"msg_final_1","output_index":1,"content_index":0,"part":{"type":"output_text","text":"Shanghai is sunny.","annotations":[]}}""",
    "response.output_item.done" to """{"type":"response.output_item.done","output_index":1,"item":$OPENAI_TEXT_ITEM}""",
    "response.completed" to """{"type":"response.completed","response":$OPENAI_REASONING_TEXT_RESPONSE}""",
)

internal val OPENAI_REASONING_SUMMARY_STREAM = listOf(
    "response.created" to """{"type":"response.created","response":{"id":"resp_reasoning_summary_1","status":"in_progress"}}""",
    "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"id":"rs_summary_1","type":"reasoning","status":"in_progress","summary":[]}}""",
    "response.reasoning_summary_part.added" to """{"type":"response.reasoning_summary_part.added","item_id":"rs_summary_1","output_index":0,"summary_index":0,"part":{"type":"summary_text","text":""}}""",
    "response.reasoning_summary_text.delta" to """{"type":"response.reasoning_summary_text.delta","item_id":"rs_summary_1","output_index":0,"summary_index":0,"delta":"I should "}""",
    "response.reasoning_summary_text.delta" to """{"type":"response.reasoning_summary_text.delta","item_id":"rs_summary_1","output_index":0,"summary_index":0,"delta":"answer directly."}""",
    "response.reasoning_summary_text.done" to """{"type":"response.reasoning_summary_text.done","item_id":"rs_summary_1","output_index":0,"summary_index":0,"text":"I should answer directly."}""",
    "response.reasoning_summary_part.done" to """{"type":"response.reasoning_summary_part.done","item_id":"rs_summary_1","output_index":0,"summary_index":0,"part":{"type":"summary_text","text":"I should answer directly."}}""",
    "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":$OPENAI_REASONING_SUMMARY_ITEM}""",
    "response.output_item.added" to """{"type":"response.output_item.added","output_index":1,"item":{"id":"msg_final_1","type":"message","status":"in_progress","role":"assistant","content":[]}}""",
    "response.content_part.added" to """{"type":"response.content_part.added","item_id":"msg_final_1","output_index":1,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}""",
    "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_final_1","output_index":1,"content_index":0,"delta":"Shanghai is sunny."}""",
    "response.output_text.done" to """{"type":"response.output_text.done","item_id":"msg_final_1","output_index":1,"content_index":0,"text":"Shanghai is sunny."}""",
    "response.content_part.done" to """{"type":"response.content_part.done","item_id":"msg_final_1","output_index":1,"content_index":0,"part":{"type":"output_text","text":"Shanghai is sunny.","annotations":[]}}""",
    "response.output_item.done" to """{"type":"response.output_item.done","output_index":1,"item":$OPENAI_TEXT_ITEM}""",
    "response.completed" to """{"type":"response.completed","response":$OPENAI_REASONING_SUMMARY_RESPONSE}""",
)

internal const val OPENROUTER_COMPACTED_REASONING_ITEM =
    """{"id":"rs_compacted_1","type":"reasoning","status":"completed","summary":[{"type":"summary_text","text":"Checked the constraints."}]}"""

internal const val OPENROUTER_COMPACTED_TOOL_ITEM =
    """{"id":"fc_compacted_1","type":"function_call","status":"completed","call_id":"call_compacted_1","name":"lookup","arguments":"{}"}"""

internal const val OPENROUTER_COMPACTED_REASONING_RESPONSE =
    """{"id":"resp_compacted_1","status":"completed","model":"compatible-reasoning-model","output":[$OPENROUTER_COMPACTED_REASONING_ITEM,$OPENROUTER_COMPACTED_TOOL_ITEM],"usage":{"input_tokens":12,"output_tokens":9,"output_tokens_details":{"reasoning_tokens":4}}}"""

internal val OPENROUTER_COMPACTED_REASONING_STREAM = listOf(
    "response.created" to """{"type":"response.created","response":{"id":"resp_compacted_1","status":"in_progress"}}""",
    "response.in_progress" to """{"type":"response.in_progress","response":{"id":"resp_compacted_1","status":"in_progress"}}""",
    "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"id":"rs_compacted_1","type":"reasoning","status":"in_progress","summary":[]}}""",
    "response.reasoning_summary_part.added" to """{"type":"response.reasoning_summary_part.added","item_id":"rs_compacted_1","output_index":0,"summary_index":0,"part":{"type":"summary_text","text":""}}""",
    "response.reasoning_summary_text.delta" to """{"type":"response.reasoning_summary_text.delta","item_id":"rs_compacted_1","output_index":0,"summary_index":0,"delta":"Checked "}""",
    "response.reasoning_summary_text.delta" to """{"type":"response.reasoning_summary_text.delta","item_id":"rs_compacted_1","output_index":0,"summary_index":0,"delta":"the constraints."}""",
    "response.output_item.added" to """{"type":"response.output_item.added","output_index":1,"item":{"id":"fc_compacted_1","type":"function_call","status":"in_progress","call_id":"call_compacted_1","name":"lookup","arguments":""}}""",
    "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","item_id":"fc_compacted_1","output_index":1,"delta":"{}"}""",
    "response.function_call_arguments.done" to """{"type":"response.function_call_arguments.done","item_id":"fc_compacted_1","output_index":1,"arguments":"{}"}""",
    "response.output_item.done" to """{"type":"response.output_item.done","output_index":1,"item":$OPENROUTER_COMPACTED_TOOL_ITEM}""",
    "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":$OPENROUTER_COMPACTED_REASONING_ITEM}""",
    "response.completed" to """{"type":"response.completed","response":$OPENROUTER_COMPACTED_REASONING_RESPONSE}""",
)

internal val OPENAI_MIXED_REASONING_STREAM = listOf(
    "response.created" to """{"type":"response.created","response":{"id":"resp_mixed_reasoning_1","status":"in_progress"}}""",
    "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"id":"rs_mixed_1","type":"reasoning","status":"in_progress","summary":[],"content":[]}}""",
    "response.reasoning_summary_part.added" to """{"type":"response.reasoning_summary_part.added","item_id":"rs_mixed_1","output_index":0,"summary_index":0,"part":{"type":"summary_text","text":""}}""",
    "response.reasoning_summary_text.delta" to """{"type":"response.reasoning_summary_text.delta","item_id":"rs_mixed_1","output_index":0,"summary_index":0,"delta":"Checked the constraints."}""",
    "response.reasoning_summary_text.done" to """{"type":"response.reasoning_summary_text.done","item_id":"rs_mixed_1","output_index":0,"summary_index":0,"text":"Checked the constraints."}""",
    "response.reasoning_summary_part.done" to """{"type":"response.reasoning_summary_part.done","item_id":"rs_mixed_1","output_index":0,"summary_index":0,"part":{"type":"summary_text","text":"Checked the constraints."}}""",
    "response.content_part.added" to """{"type":"response.content_part.added","item_id":"rs_mixed_1","output_index":0,"content_index":0,"part":{"type":"reasoning_text","text":""}}""",
    "response.reasoning_text.delta" to """{"type":"response.reasoning_text.delta","item_id":"rs_mixed_1","output_index":0,"content_index":0,"delta":"Provider-visible reasoning."}""",
    "response.reasoning_text.done" to """{"type":"response.reasoning_text.done","item_id":"rs_mixed_1","output_index":0,"content_index":0,"text":"Provider-visible reasoning."}""",
    "response.content_part.done" to """{"type":"response.content_part.done","item_id":"rs_mixed_1","output_index":0,"content_index":0,"part":{"type":"reasoning_text","text":"Provider-visible reasoning."}}""",
    "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":$OPENAI_MIXED_REASONING_ITEM}""",
    "response.completed" to """{"type":"response.completed","response":$OPENAI_MIXED_REASONING_RESPONSE}""",
)

internal val OPENAI_X_SEARCH_STREAM = listOf(
    "response.created" to """{"type":"response.created","response":{"id":"resp_x_search_1","status":"in_progress"}}""",
    "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"id":"xs_kmp_1","call_id":"call_x_kmp_1","type":"x_search_call","status":"in_progress","name":"x_keyword_search","arguments":"{\"query\":\"Kotlin Multiplatform\"}"}}""",
    "response.x_search_call.in_progress" to """{"type":"response.x_search_call.in_progress","item_id":"xs_kmp_1","output_index":0}""",
    "response.x_search_call.searching" to """{"type":"response.x_search_call.searching","item_id":"xs_kmp_1","output_index":0}""",
    "response.x_search_call.completed" to """{"type":"response.x_search_call.completed","item_id":"xs_kmp_1","output_index":0}""",
    "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":$OPENAI_X_SEARCH_ITEM}""",
    "response.output_item.added" to """{"type":"response.output_item.added","output_index":1,"item":{"id":"msg_x_search_1","type":"message","status":"in_progress","role":"assistant","content":[]}}""",
    "response.content_part.added" to """{"type":"response.content_part.added","item_id":"msg_x_search_1","output_index":1,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}""",
    "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_x_search_1","output_index":1,"content_index":0,"delta":"KMP is being discussed.[[1]](https://x.com/kotlin/status/1)"}""",
    "response.output_text.done" to """{"type":"response.output_text.done","item_id":"msg_x_search_1","output_index":1,"content_index":0,"text":"KMP is being discussed.[[1]](https://x.com/kotlin/status/1)"}""",
    "response.content_part.done" to """{"type":"response.content_part.done","item_id":"msg_x_search_1","output_index":1,"content_index":0,"part":{"type":"output_text","text":"KMP is being discussed.[[1]](https://x.com/kotlin/status/1)","annotations":[{"type":"url_citation","url":"https://x.com/kotlin/status/1","start_index":23,"end_index":63,"title":"1"}]}}""",
    "response.output_item.done" to """{"type":"response.output_item.done","output_index":1,"item":$OPENAI_X_SEARCH_TEXT_ITEM}""",
    "response.completed" to """{"type":"response.completed","response":$OPENAI_X_SEARCH_RESPONSE}""",
)

internal val XAI_HOSTED_X_SEARCH_STREAM = listOf(
    "response.created" to """{"type":"response.created","response":{"id":"resp_x_hosted_1","status":"in_progress"}}""",
    "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"id":"ctc_x_schema_1","call_id":"call_x_schema_1","type":"custom_tool_call","status":"in_progress","name":"x_keyword_search","input":""}}""",
    "response.custom_tool_call_input.delta" to """{"type":"response.custom_tool_call_input.delta","item_id":"ctc_x_schema_1","output_index":0,"delta":"{\"query\":\"Kotlin "}""",
    "response.custom_tool_call_input.delta" to """{"type":"response.custom_tool_call_input.delta","item_id":"ctc_x_schema_1","output_index":0,"delta":"Multiplatform\"}"}""",
    "response.custom_tool_call_input.done" to """{"type":"response.custom_tool_call_input.done","item_id":"ctc_x_schema_1","output_index":0,"input":"{\"query\":\"Kotlin Multiplatform\"}"}""",
    "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":$XAI_HOSTED_X_SEARCH_CUSTOM_TOOL_ITEM}""",
    "response.output_item.added" to """{"type":"response.output_item.added","output_index":1,"item":{"id":"msg_x_search_1","type":"message","status":"in_progress","role":"assistant","content":[]}}""",
    "response.content_part.added" to """{"type":"response.content_part.added","item_id":"msg_x_search_1","output_index":1,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}""",
    "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_x_search_1","output_index":1,"content_index":0,"delta":"KMP is being discussed.[[1]](https://x.com/kotlin/status/1)"}""",
    "response.output_text.done" to """{"type":"response.output_text.done","item_id":"msg_x_search_1","output_index":1,"content_index":0,"text":"KMP is being discussed.[[1]](https://x.com/kotlin/status/1)"}""",
    "response.content_part.done" to """{"type":"response.content_part.done","item_id":"msg_x_search_1","output_index":1,"content_index":0,"part":{"type":"output_text","text":"KMP is being discussed.[[1]](https://x.com/kotlin/status/1)","annotations":[{"type":"url_citation","url":"https://x.com/kotlin/status/1","start_index":23,"end_index":63,"title":"1"}]}}""",
    "response.output_item.done" to """{"type":"response.output_item.done","output_index":1,"item":$OPENAI_X_SEARCH_TEXT_ITEM}""",
    "response.completed" to """{"type":"response.completed","response":$XAI_HOSTED_X_SEARCH_RESPONSE}""",
)

internal const val OPENAI_CHAT_TOOL_RESPONSE =
    """{"id":"chatcmpl_tool_1","object":"chat.completion","created":1700000000,"model":"compatible-model","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_weather_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Shanghai\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":4,"completion_tokens_details":{"reasoning_tokens":1}}}"""

internal const val OPENAI_CHAT_TEXT_RESPONSE =
    """{"id":"chatcmpl_text_1","object":"chat.completion","created":1700000000,"model":"compatible-model","choices":[{"index":0,"message":{"role":"assistant","reasoning_content":"I checked the forecast.","content":"Shanghai is sunny."},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":8,"completion_tokens_details":{"reasoning_tokens":3}}}"""

internal val OPENAI_CHAT_TOOL_STREAM = listOf(
    """{"id":"chatcmpl_tool_1","object":"chat.completion.chunk","created":1700000000,"model":"compatible-model","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_weather_1","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}""",
    """{"id":"chatcmpl_tool_1","object":"chat.completion.chunk","created":1700000000,"model":"compatible-model","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]},"finish_reason":null}]}""",
    """{"id":"chatcmpl_tool_1","object":"chat.completion.chunk","created":1700000000,"model":"compatible-model","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Shanghai\"}"}}]},"finish_reason":null}]}""",
    """{"id":"chatcmpl_tool_1","object":"chat.completion.chunk","created":1700000000,"model":"compatible-model","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
    """{"id":"chatcmpl_tool_1","object":"chat.completion.chunk","created":1700000000,"model":"compatible-model","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4,"completion_tokens_details":{"reasoning_tokens":1}}}""",
    "[DONE]",
)

internal fun openAiSseFrames(events: List<Pair<String, String>>): List<HttpStreamFrame> = buildList {
    add(HttpStreamFrame.ResponseStarted(200, emptyList()))
    events.forEach { (event, data) -> add(HttpStreamFrame.ServerSentEvent(event, data, null)) }
    add(HttpStreamFrame.Completed)
}

internal fun openAiChatSseFrames(events: List<String>): List<HttpStreamFrame> = buildList {
    add(HttpStreamFrame.ResponseStarted(200, emptyList()))
    events.forEach { data -> add(HttpStreamFrame.ServerSentEvent(null, data, null)) }
    add(HttpStreamFrame.Completed)
}

internal class ScriptedOpenAiTransport(
    executeResponses: List<HttpResponseSpec> = emptyList(),
    streamResponses: List<List<HttpStreamFrame>> = emptyList(),
) : HttpTransport {
    private val executeQueue = ArrayDeque(executeResponses)
    private val streamQueue = ArrayDeque(streamResponses)
    val requests = mutableListOf<Pair<HttpRequestSpec, HttpStreamFormat?>>()
    var closed: Boolean = false
        private set

    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        requests += request to null
        return executeQueue.removeFirstOrNull() ?: error("No scripted OpenAI response")
    }

    override fun stream(request: HttpRequestSpec, format: HttpStreamFormat): Flow<HttpStreamFrame> = flow {
        requests += request to format
        (streamQueue.removeFirstOrNull() ?: error("No scripted OpenAI stream")).forEach { emit(it) }
    }

    override fun close() {
        closed = true
    }
}

package saien.magrathea.provider.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.MessagePart
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolResultPart

class DefaultReplayPolicyTest {
    private val model = ModelDescriptor(provider = "openai", model = "gpt-5")

    @Test
    fun `cross model replay drops provider reasoning and strips thought signature`() = runBlocking {
        val policy = DefaultReplayPolicy()
        val source = assistantMessage(
            provider = "gemini",
            model = "gemini-2.5-pro",
            parts = listOf(
                ReasoningPart(text = "secret plan", signature = "sig-1"),
                ToolCallPart(
                    toolCallId = "call-1",
                    toolName = "search",
                    arguments = JsonPrimitive("{}"),
                    thoughtSignature = "thought-1",
                ),
            ),
        )

        val transformed = policy.transform(listOf(source), model)
        val assistant = transformed.single()

        assertEquals(1, assistant.parts.size)
        val toolCall = assistant.parts.single() as ToolCallPart
        assertNull(toolCall.thoughtSignature)
    }

    @Test
    fun `same model replay keeps signatures and redacted reasoning`() = runBlocking {
        val policy = DefaultReplayPolicy()
        val source = assistantMessage(
            provider = "openai",
            model = "gpt-5",
            parts = listOf(
                ReasoningPart(text = "", signature = "sig-1", redacted = true),
                ToolCallPart(
                    toolCallId = "call-1",
                    toolName = "search",
                    arguments = JsonPrimitive("{}"),
                    thoughtSignature = "thought-1",
                ),
            ),
        )

        val transformed = policy.transform(listOf(source), model)
        val assistant = transformed.single()

        assertTrue(assistant.parts[0] is ReasoningPart)
        assertEquals("sig-1", (assistant.parts[0] as ReasoningPart).signature)
        assertEquals("thought-1", (assistant.parts[1] as ToolCallPart).thoughtSignature)
    }

    @Test
    fun `replay inserts orphaned synthetic tool result and normalizes result ids`() = runBlocking {
        val policy = DefaultReplayPolicy { id, _, _ -> "normalized-$id" }
        val assistant = assistantMessage(
            provider = "gemini",
            model = "gemini-2.5-pro",
            parts = listOf(
                ToolCallPart(
                    toolCallId = "call-1",
                    toolName = "search",
                    arguments = JsonPrimitive("{}"),
                ),
            ),
        )
        val toolResult = AgentMessage(
            role = MessageRole.TOOL,
            parts = listOf(ToolResultPart(toolCallId = "call-1", toolName = "search", result = JsonPrimitive("ok"))),
        )
        val user = AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("next")))

        val transformed = policy.transform(listOf(assistant, toolResult, user), model)

        val transformedAssistant = transformed[0]
        assertEquals("normalized-call-1", (transformedAssistant.parts[0] as ToolCallPart).toolCallId)
        val transformedToolResult = transformed[1]
        assertEquals("normalized-call-1", (transformedToolResult.parts[0] as ToolResultPart).toolCallId)

        val orphaned = policy.transform(listOf(assistant, user), model)
        assertEquals(3, orphaned.size)
        assertTrue(orphaned[1].parts[0] is ToolResultPart)
        assertTrue((orphaned[1].parts[0] as ToolResultPart).isError)
    }

    @Test
    fun `same model replay can be inferred from part provider metadata`() = runBlocking {
        val policy = DefaultReplayPolicy()
        val source = AgentMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                ReasoningPart(
                    text = "",
                    signature = "sig-1",
                    redacted = true,
                    providerMetadata = buildJsonObject {
                        put("provider", "openai")
                        put("model", "gpt-5")
                    },
                ),
                ToolCallPart(
                    toolCallId = "call-1",
                    toolName = "search",
                    arguments = JsonPrimitive("{}"),
                    thoughtSignature = "thought-1",
                    providerMetadata = buildJsonObject {
                        put("provider", "openai")
                        put("model", "gpt-5")
                    },
                ),
            ),
        )

        val transformed = policy.transform(listOf(source), model)
        val assistant = transformed.single()

        assertTrue(assistant.parts[0] is ReasoningPart)
        assertEquals("sig-1", (assistant.parts[0] as ReasoningPart).signature)
        assertEquals("thought-1", (assistant.parts[1] as ToolCallPart).thoughtSignature)
    }

    @Test
    fun `anthropic replay normalizes tool ids and drops cross model redacted reasoning`() = runBlocking {
        val policy = DefaultReplayPolicy()
        val anthropicModel = ModelDescriptor(provider = "anthropic", model = "claude-sonnet-4")
        val source = assistantMessage(
            provider = "openai",
            model = "gpt-5",
            parts = listOf(
                ReasoningPart(text = "", signature = "sig-1", redacted = true),
                ToolCallPart(
                    toolCallId = "call|with:bad/chars and very long id 1234567890123456789012345678901234567890",
                    toolName = "search",
                    arguments = JsonPrimitive("{}"),
                    thoughtSignature = "thought-1",
                ),
            ),
        )

        val transformed = policy.transform(listOf(source), anthropicModel)
        val assistant = transformed.single()

        assertEquals(1, assistant.parts.size)
        val toolCall = assistant.parts.single() as ToolCallPart
        assertTrue(toolCall.toolCallId.length <= 64)
        assertTrue(toolCall.toolCallId.none { !(it.isLetterOrDigit() || it == '_' || it == '-') })
        assertNull(toolCall.thoughtSignature)
    }

    @Test
    fun `metadata on message wins over conflicting part provider metadata`() = runBlocking {
        val policy = DefaultReplayPolicy()
        val source = AgentMessage(
            role = MessageRole.ASSISTANT,
            metadata = buildJsonObject {
                put("provider", "openai")
                put("model", "gpt-5")
            },
            parts = listOf(
                ReasoningPart(
                    text = "",
                    signature = "sig-1",
                    redacted = true,
                    providerMetadata = buildJsonObject {
                        put("provider", "gemini")
                        put("model", "gemini-2.5-pro")
                    },
                ),
            ),
        )

        val transformed = policy.transform(listOf(source), model)
        val assistant = transformed.single()

        assertTrue(assistant.parts.single() is ReasoningPart)
        assertEquals("sig-1", (assistant.parts.single() as ReasoningPart).signature)
    }

    @Test
    fun `replay output preserves assistant tool result ordering contract`() = runBlocking {
        val policy = DefaultReplayPolicy()
        val assistant = assistantMessage(
            provider = "openai",
            model = "gpt-5",
            parts = listOf(
                ToolCallPart(
                    toolCallId = "call-1",
                    toolName = "search",
                    arguments = JsonPrimitive("{}"),
                    partial = true,
                ),
            ),
        )
        val result = AgentMessage(
            role = MessageRole.TOOL,
            parts = listOf(ToolResultPart(toolCallId = "call-1", toolName = "search", result = JsonPrimitive("ok"))),
        )
        val user = AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("continue")))

        val transformed = policy.transform(listOf(assistant, result, user), model)

        assertEquals(MessageRole.ASSISTANT, transformed[0].role)
        assertEquals(MessageRole.TOOL, transformed[1].role)
        assertEquals(MessageRole.USER, transformed[2].role)
        assertTrue((transformed[0].parts.single() as ToolCallPart).partial)
    }

    private fun assistantMessage(provider: String, model: String, parts: List<MessagePart>): AgentMessage {
        return AgentMessage(
            role = MessageRole.ASSISTANT,
            parts = parts,
            metadata = buildJsonObject {
                put("provider", provider)
                put("model", model)
            },
        )
    }
}

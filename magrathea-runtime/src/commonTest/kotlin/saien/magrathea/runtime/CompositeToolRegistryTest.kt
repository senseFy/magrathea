package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor

class CompositeToolRegistryTest {
    @Test
    fun observesRegistrySourcesDynamically() {
        val first = fixtureExecutor("first")
        val second = fixtureExecutor("second")
        var registries = listOf(InMemoryToolRegistry(listOf(first)))
        val composite = CompositeToolRegistry { registries }

        assertEquals(listOf("first"), composite.definitions().map { it.name })
        assertNotNull(composite.find("first"))

        registries = listOf(
            InMemoryToolRegistry(listOf(first)),
            InMemoryToolRegistry(listOf(second)),
        )
        assertEquals(listOf("first", "second"), composite.definitions().map { it.name })
        assertNotNull(composite.find("second"))
    }

    @Test
    fun rejectsAmbiguousToolNames() {
        val duplicate = fixtureExecutor("duplicate")
        val composite = CompositeToolRegistry {
            listOf(
                InMemoryToolRegistry(listOf(duplicate)),
                InMemoryToolRegistry(listOf(duplicate)),
            )
        }

        assertFailsWith<IllegalArgumentException> { composite.definitions() }
        assertFailsWith<IllegalStateException> { composite.find("duplicate") }
    }

    private fun fixtureExecutor(name: String) = object : ToolExecutor {
        override val definition = ToolDefinition(
            name = name,
            description = name,
            schema = buildJsonObject { put("type", "object") },
        )

        override suspend fun execute(request: ToolExecutionRequest) = ToolExecutionResult(
            toolCallId = request.toolCall.toolCallId,
            toolName = name,
            result = buildJsonObject { },
        )
    }
}

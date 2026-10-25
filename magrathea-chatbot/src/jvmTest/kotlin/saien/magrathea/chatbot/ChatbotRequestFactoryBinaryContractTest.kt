package saien.magrathea.chatbot

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatbotRequestFactoryBinaryContractTest {
    @Test
    fun continuationHookIsAJvmDefaultForExistingFactoryImplementations() {
        val type = ChatbotRequestFactory::class.java
        assertTrue(type.getMethod("createContinuation", ChatbotRequestContext::class.java).isDefault)
        assertEquals(
            listOf("create"),
            type.methods.filter { Modifier.isAbstract(it.modifiers) }.map { it.name },
        )
    }
}

package saien.magrathea.core

import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayBinaryContractTest {
    @Test
    fun oldPolicyImplementationsCanInheritTheReportingDefault() {
        val method = ReplayPolicy::class.java.getMethod(
            "transformWithReport", List::class.java, ModelDescriptor::class.java, Continuation::class.java,
        )
        assertTrue(method.isDefault)
    }

    @Test
    fun contextEventPreservesItsPublishedConstructorAndCopySignatures() {
        val type = AgentEvent.ContextTransformed::class.java
        val parameters = arrayOf(AgentSessionId::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val sessionId = AgentSessionId("session")
        assertEquals(AgentEvent.ContextTransformed(sessionId, 0, 1), type.getConstructor(*parameters).newInstance(sessionId, 0, 1))

        val report = ReplayReport(AgentRunId("run"), 0, "openai", "model", emptyList())
        val original = AgentEvent.ContextTransformed(sessionId, 0, 1, report)
        val copied = type.getMethod("copy", *parameters).invoke(original, sessionId, 1, 2) as AgentEvent.ContextTransformed
        assertEquals(report, copied.replay)
        assertEquals(2, copied.messageCount)
        val defaults = type.getDeclaredMethod("copy\$default", type, *parameters, Int::class.javaPrimitiveType, Any::class.java)
            .invoke(null, original, null, 0, 0, 7, null)
        assertEquals(original, defaults)
    }
}

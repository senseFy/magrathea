package saien.magrathea.runtime.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import saien.magrathea.core.ToolOrigin

class WebSearchBackendResponseBinaryContractTest {
    @Test
    fun originalConstructorCopyAndDefaultCopyDescriptorsRemainCallable() {
        val responseClass = WebSearchBackendResponse::class.java
        val results = listOf(WebSearchSource("Result", "https://example.com"))
        val legacy = responseClass.getConstructor(List::class.java).newInstance(results)
        assertEquals(results, legacy.results)
        assertNull(legacy.origin)

        val origin = ToolOrigin("actual-service", "Actual service", "web_search", "Web search")
        val tagged = WebSearchBackendResponse(results, origin)
        val copy = responseClass.getMethod("copy", List::class.java)
            .invoke(tagged, emptyList<WebSearchSource>()) as WebSearchBackendResponse
        assertEquals(emptyList(), copy.results)
        assertSame(origin, copy.origin)

        val defaultCopy = responseClass.getDeclaredMethod(
            "copy\$default", responseClass, List::class.java, Int::class.javaPrimitiveType, Any::class.java,
        ).invoke(null, tagged, null, 1, null) as WebSearchBackendResponse
        assertEquals(results, defaultCopy.results)
        assertSame(origin, defaultCopy.origin)
    }
}

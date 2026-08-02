package saien.magrathea.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MediaReferenceContractTest {
    @Test
    fun toolResultReferenceIsStableDistinctAndUriSafe() {
        val first = MediaReference.forToolResult("run:1/call ü", 0)
        val repeated = MediaReference.forToolResult("run:1/call ü", 0)
        val second = MediaReference.forToolResult("run:1/call ü", 1)

        assertEquals(first, repeated)
        assertNotEquals(first, second)
        assertEquals(first, MediaReference.parseUri(first.toUri()))
        assertEquals(
            "magrathea://media/tool-result%3Arun%3A1%2Fcall%20%C3%BC%3A0",
            first.toUri(),
        )
    }

    @Test
    fun parserRejectsExternalMalformedAndNonCanonicalUris() {
        assertNull(MediaReference.parseUri("https://example.com/image.jpg"))
        assertNull(MediaReference.parseUri("magrathea://media/"))
        assertNull(MediaReference.parseUri("magrathea://media/%"))
        assertNull(MediaReference.parseUri("magrathea://media/%c3%bc"))
        assertNull(MediaReference.parseUri("magrathea://media/unescaped:value"))
    }
}

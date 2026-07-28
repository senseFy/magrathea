package saien.magrathea.samples.jvm

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmChatSampleTest {
    @Test
    fun sampleExercisesCanonicalStreamingToolCancellationResumeAndHistory() = runBlocking {
        val report = runDeterministicSample()

        assertEquals("Shanghai weather: 21 C, clear", report.streamedText)
        assertEquals(1, report.toolExecutions)
        assertEquals(2, report.providerCalls)
        assertTrue(report.resumedWithoutProviderCall)
        assertTrue(report.cancelledStatePersisted)
        assertEquals(2, report.historySessionIds.size)
    }

    @Test
    fun publicProviderNeutralFacadeRunsFromPublishedArtifactsAndClosesResources() = runBlocking {
        val report = runProviderNeutralFacadeSample()

        assertEquals("Shanghai weather: 21 C, clear", report.text)
        assertEquals(1, report.toolExecutions)
        assertEquals(2, report.providerCalls)
        assertEquals(1, report.historySize)
        assertEquals(1, report.resourceCloses)
    }
}

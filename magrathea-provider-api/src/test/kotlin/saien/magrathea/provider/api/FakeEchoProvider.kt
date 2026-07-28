package saien.magrathea.provider.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import saien.magrathea.core.StopReason

internal class FakeEchoProvider : ProviderAdapter {
    override val key: String = "fake"

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
        emit(
            ProviderChunk(
                events = listOf(
                    ProviderEvent.TextStart(),
                    ProviderEvent.TextDelta("echo"),
                    ProviderEvent.TextEnd(),
                    ProviderEvent.Completed(stopReason = StopReason.COMPLETED),
                ),
            ),
        )
    }
}

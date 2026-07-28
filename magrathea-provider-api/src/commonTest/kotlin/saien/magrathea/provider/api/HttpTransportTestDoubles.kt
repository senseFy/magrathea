package saien.magrathea.provider.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class FakeHttpTransport(
    private val executeHandler: suspend (HttpRequestSpec) -> HttpResponseSpec,
) : HttpTransport {
    val requests = mutableListOf<HttpRequestSpec>()
    var closed = false
        private set

    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        requests += request
        return executeHandler(request)
    }

    override fun stream(request: HttpRequestSpec, format: HttpStreamFormat): Flow<HttpStreamFrame> {
        error("No stream script configured")
    }

    override fun close() {
        closed = true
    }
}

internal class ScriptedStreamTransport(
    private val frames: List<HttpStreamFrame>,
) : HttpTransport {
    val requests = mutableListOf<Pair<HttpRequestSpec, HttpStreamFormat>>()

    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        error("No execute script configured")
    }

    override fun stream(request: HttpRequestSpec, format: HttpStreamFormat): Flow<HttpStreamFrame> = flow {
        requests += request to format
        frames.forEach { emit(it) }
    }

    override fun close() = Unit
}

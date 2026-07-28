package saien.magrathea.provider.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

actual fun createDefaultHttpTransport(config: DefaultHttpTransportConfig): HttpTransport {
    val client = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = config.followRedirects
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMillis
            connectTimeoutMillis = config.connectTimeoutMillis
            socketTimeoutMillis = config.socketTimeoutMillis
        }
        engine {
            config {
                retryOnConnectionFailure(false)
            }
        }
    }
    return KtorHttpTransport(client, config.limits)
}

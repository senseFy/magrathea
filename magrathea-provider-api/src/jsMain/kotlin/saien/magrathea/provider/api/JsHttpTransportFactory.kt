package saien.magrathea.provider.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout

actual fun createDefaultHttpTransport(config: DefaultHttpTransportConfig): HttpTransport {
    val client = HttpClient(Js) {
        expectSuccess = false
        followRedirects = config.followRedirects
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMillis
            connectTimeoutMillis = config.connectTimeoutMillis
            socketTimeoutMillis = config.socketTimeoutMillis
        }
    }
    return KtorHttpTransport(client, config.limits)
}

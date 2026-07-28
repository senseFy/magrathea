@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")

package saien.magrathea.web.client

import kotlinx.coroutines.delay
import saien.magrathea.provider.gateway.GatewayReconnectGate

enum class WebNetworkState {
    ONLINE,
    OFFLINE,
}

fun currentWebNetworkState(): WebNetworkState =
    if (browserIsOnline()) WebNetworkState.ONLINE else WebNetworkState.OFFLINE

internal object BrowserGatewayReconnectGate : GatewayReconnectGate {
    override suspend fun awaitReconnectPermission() {
        while (!browserIsOnline()) {
            // Polling keeps cancellation structured and avoids retaining a browser event listener
            // when an offline turn/session is explicitly cancelled.
            delay(OFFLINE_POLL_MILLIS)
        }
    }
}

private fun browserIsOnline(): Boolean = js(
    "typeof globalThis.navigator !== 'undefined' && globalThis.navigator.onLine !== false",
)

private const val OFFLINE_POLL_MILLIS: Long = 250

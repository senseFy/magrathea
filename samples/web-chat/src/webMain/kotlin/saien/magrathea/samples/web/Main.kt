@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package saien.magrathea.samples.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import saien.magrathea.chatbot.ChatbotObservation
import saien.magrathea.chatbot.ChatbotSession
import saien.magrathea.chatbot.ChatbotSessionConfiguration
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.provider.gateway.GatewaySessionHeaders
import saien.magrathea.provider.gateway.GatewaySessionHeadersProvider
import saien.magrathea.web.client.WebChatbotConfiguration
import saien.magrathea.web.client.createWebChatbotClient

fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = createWebChatbotClient(
        configuration = WebChatbotConfiguration(
            gatewayBaseUrl = configuredGatewayBaseUrl(),
            databaseName = "magrathea-web-sample",
        ),
        sessionHeadersProvider = GatewaySessionHeadersProvider {
            GatewaySessionHeaders(
                authorization = configuredAuthorization(),
                csrfToken = configuredCsrfToken(),
            )
        },
    )
    var session: ChatbotSession? = null
    var observation: ChatbotObservation? = null

    installSampleUi(
        onSend = { text ->
            scope.launch {
                try {
                    val active = session ?: client.createSession(
                        ChatbotSessionConfiguration(
                            ModelDescriptor(
                                provider = configuredProvider(),
                                model = configuredModel(),
                                supportsStreaming = true,
                            ),
                        ),
                    ).also { created ->
                        session = created
                        observation = created.observe { snapshot ->
                            renderSampleState(
                                status = snapshot.status.name.lowercase(),
                                transcript = snapshot.messages.joinToString("\n") { "${it.role.name.lowercase()}: ${it.text}" },
                                error = snapshot.failure?.name?.lowercase().orEmpty(),
                            )
                        }
                    }
                    active.send(text)
                } catch (_: Throwable) {
                    renderSampleState("failed", "", "chatbot_operation_failed")
                }
            }
        },
        onCancel = {
            scope.launch {
                try {
                    session?.cancel()
                } catch (_: Throwable) {
                    renderSampleState("failed", "", "chatbot_operation_failed")
                }
            }
        },
        onClose = {
            scope.launch {
                observation?.cancel()
                client.close()
            }
        },
    )
}

private fun configuredGatewayBaseUrl(): String = js(
    "globalThis.MAGRATHEA_GATEWAY_BASE_URL || globalThis.location.origin",
)

private fun configuredProvider(): String = js(
    "globalThis.MAGRATHEA_GATEWAY_PROVIDER || 'gateway-e2e'",
)

private fun configuredModel(): String = js(
    "globalThis.MAGRATHEA_GATEWAY_MODEL || 'chat-model'",
)

private fun configuredAuthorization(): String? = js(
    "globalThis.MAGRATHEA_GATEWAY_AUTHORIZATION || null",
)

private fun configuredCsrfToken(): String? = js(
    "globalThis.MAGRATHEA_GATEWAY_CSRF_TOKEN || null",
)

private fun installSampleUi(
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
): Unit = js(
    """
    {
      const form = document.getElementById("chat-form");
      const input = document.getElementById("chat-input");
      const cancel = document.getElementById("chat-cancel");
      const close = document.getElementById("chat-close");
      form.addEventListener("submit", (event) => {
        event.preventDefault();
        const value = input.value.trim();
        if (value.length > 0) {
          input.value = "";
          onSend(value);
        }
      });
      cancel.addEventListener("click", () => onCancel());
      close.addEventListener("click", () => onClose());
      globalThis.addEventListener("pagehide", () => {
        // Deliberately do not cancel: the Gateway replay window owns refresh recovery.
      });
      input.disabled = false;
      cancel.disabled = false;
      close.disabled = false;
      form.querySelector('button[type="submit"]').disabled = false;
      document.documentElement.setAttribute("data-magrathea-runtime", "ready");
    }
    """,
)

private fun renderSampleState(
    status: String,
    transcript: String,
    error: String,
): Unit = js(
    """
    {
      document.getElementById("chat-status").textContent = status;
      document.getElementById("chat-transcript").textContent = transcript;
      document.getElementById("chat-error").textContent = error;
    }
    """,
)

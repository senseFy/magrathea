import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class DeviceHttpProbeServer {
    private static final byte[] HEALTH = "device-http-ok".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ERROR = "device-http-error-secret-5da2".getBytes(StandardCharsets.UTF_8);
    private static final String GEMINI_API_KEY = "device-gemini-key-canary-81ca";
    private static final byte[] GEMINI_SSE = (
        "event: interaction.created\n" +
        "data: {\"interaction\":{\"id\":\"v1_final\",\"model\":\"gemini-contract-model\",\"status\":\"in_progress\",\"object\":\"interaction\"},\"event_type\":\"interaction.created\"}\n\n" +
        "event: step.start\n" +
        "data: {\"index\":0,\"step\":{\"type\":\"model_output\"},\"event_type\":\"step.start\"}\n\n" +
        "event: step.delta\n" +
        "data: {\"index\":0,\"delta\":{\"type\":\"text\",\"text\":\"Shanghai is sunny \"},\"event_type\":\"step.delta\"}\n\n" +
        "event: step.delta\n" +
        "data: {\"index\":0,\"delta\":{\"type\":\"text\",\"text\":\"and 27°C.\"},\"event_type\":\"step.delta\"}\n\n" +
        "event: step.stop\n" +
        "data: {\"index\":0,\"event_type\":\"step.stop\"}\n\n" +
        "event: interaction.completed\n" +
        "data: {\"interaction\":{\"id\":\"v1_final\",\"status\":\"completed\",\"usage\":{\"total_input_tokens\":24,\"total_output_tokens\":6,\"total_thought_tokens\":0}},\"event_type\":\"interaction.completed\"}\n\n" +
        "event: done\n" +
        "data: [DONE]\n\n"
    ).getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected exactly one port argument");
        }
        int port = Integer.parseInt(args[0]);
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Port is outside the allowed test range");
        }

        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
            0
        );
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/health", exchange -> fixed(exchange, 200, "text/plain", HEALTH));
        server.createContext("/error", exchange -> fixed(exchange, 500, "text/plain", ERROR));
        server.createContext("/sse", DeviceHttpProbeServer::sse);
        server.createContext("/gemini", DeviceHttpProbeServer::gemini);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
        System.out.println("MAGRATHEA_ANDROID_HTTP_SERVER_READY port=" + port);
        System.out.flush();
    }

    private static void fixed(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        requireGet(exchange);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (exchange; OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void sse(HttpExchange exchange) throws IOException {
        requireGet(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (exchange; OutputStream output = exchange.getResponseBody()) {
            output.write("event: probe\nid: device-1\ndata: first\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.write("data: second\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static void gemini(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            reject(exchange, 405, "method-not-allowed");
            return;
        }
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String apiKey = exchange.getRequestHeaders().getFirst("x-goog-api-key");
        byte[] bodyBytes = exchange.getRequestBody().readNBytes(1_048_577);
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        boolean valid = bodyBytes.length <= 1_048_576 &&
            "text/event-stream".equals(accept) &&
            contentType != null && contentType.startsWith("application/json") &&
            GEMINI_API_KEY.equals(apiKey) &&
            body.contains("\"model\":\"gemini-contract-model\"") &&
            body.contains("\"stream\":true") &&
            body.contains("\"store\":false") &&
            body.contains("Weather in Shanghai?") &&
            !body.contains(GEMINI_API_KEY);
        if (!valid) {
            reject(exchange, 400, "invalid-gemini-request");
            return;
        }

        System.out.println("MAGRATHEA_ANDROID_GEMINI_REQUEST_OBSERVED bytes=" + bodyBytes.length);
        System.out.flush();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (exchange; OutputStream output = exchange.getResponseBody()) {
            output.write(GEMINI_SSE);
            output.flush();
        }
    }

    private static void reject(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (exchange; OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void requireGet(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            byte[] body = "method-not-allowed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(405, body.length);
            try (exchange; OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
            throw new IOException("Unexpected request method");
        }
    }
}

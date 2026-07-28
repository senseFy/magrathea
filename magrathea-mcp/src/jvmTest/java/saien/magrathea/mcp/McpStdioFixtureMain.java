package saien.magrathea.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal dependency-free MCP stdio process used only by the JVM transport integration test.
 */
public final class McpStdioFixtureMain {
    private static final Pattern ID =
        Pattern.compile("\"id\"\\s*:\\s*(\"(?:\\\\.|[^\"])*\"|-?\\d+)");

    private McpStdioFixtureMain() {
    }

    public static void main(String[] args) throws Exception {
        try (
            BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );
            PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8)
        ) {
            String message;
            while ((message = input.readLine()) != null) {
                if (message.contains("\"method\":\"initialize\"")) {
                    respond(
                        output,
                        message,
                        """
                        {"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"stdio-fixture","version":"1.0.0"}}
                        """
                    );
                } else if (message.contains("\"method\":\"tools/list\"")) {
                    respond(
                        output,
                        message,
                        """
                        {"tools":[{"name":"echo","description":"Echo through a child process","inputSchema":{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}}]}
                        """
                    );
                } else if (message.contains("\"method\":\"tools/call\"")) {
                    respond(
                        output,
                        message,
                        """
                        {"content":[{"type":"text","text":"Echo: stdio"}],"structuredContent":{"echo":"stdio"}}
                        """
                    );
                }
            }
        }
    }

    private static void respond(PrintWriter output, String request, String result) {
        Matcher matcher = ID.matcher(request);
        if (!matcher.find()) {
            throw new IllegalArgumentException("MCP request did not contain an ID");
        }
        output.println(
            "{\"jsonrpc\":\"2.0\",\"id\":" + matcher.group(1) + ",\"result\":" +
                result.trim() + "}"
        );
    }
}

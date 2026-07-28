package saien.magrathea.mcp

import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Explicit child-process configuration for a JVM-hosted MCP stdio server.
 *
 * Environment and argument values may contain credentials and are never included in [toString].
 */
data class JvmMcpStdioProcess(
    val command: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
) {
    init {
        require(
            command.isNotBlank() &&
                command == command.trim() &&
                command.length <= 4_096 &&
                '\u0000' !in command,
        ) {
            "MCP stdio command is invalid"
        }
        require(
            arguments.size <= 1_000 &&
                arguments.all { it.length <= 16_384 && '\u0000' !in it },
        ) {
            "MCP stdio arguments are invalid"
        }
        require(
            environment.size <= 1_000 &&
                environment.keys.all(String::isValidEnvironmentName),
        ) {
            "MCP stdio environment names are invalid"
        }
        require(environment.values.all { it.length <= 65_536 && '\u0000' !in it }) {
            "MCP stdio environment values are invalid"
        }
        require(
            workingDirectory == null ||
                workingDirectory.isNotBlank() &&
                workingDirectory == workingDirectory.trim() &&
                workingDirectory.length <= 4_096 &&
                '\u0000' !in workingDirectory,
        ) {
            "MCP stdio working directory is invalid"
        }
    }

    override fun toString(): String =
        "JvmMcpStdioProcess(" +
            "command=<configured>, " +
            "argumentCount=${arguments.size}, " +
            "workingDirectory=${if (workingDirectory == null) "default" else "<configured>"}, " +
            "environmentNames=${environment.keys.sorted()}" +
            ")"
}

/**
 * Starts a local MCP process and connects it over stdio.
 *
 * Hosts should display the exact command, arguments, working directory, and environment names and
 * obtain explicit user approval before invoking this factory. The child receives only the
 * explicitly supplied environment map; it does not inherit the host process environment. Use an
 * absolute executable path or explicitly supply any required `PATH`.
 */
fun jvmStdioMcpTransportFactory(
    process: JvmMcpStdioProcess,
): McpTransportFactory = McpTransportFactory {
    val child = withContext(Dispatchers.IO) {
        process.toProcessBuilder().start()
    }
    try {
        val transport = StdioClientTransport(
            input = child.inputStream.asSource().buffered(),
            output = child.outputStream.asSink().buffered(),
            error = child.errorStream.asSource().buffered(),
        )
        McpTransportHandle(
            transport = transport,
            releaseResources = { destroyMcpProcess(child) },
        )
    } catch (failure: Throwable) {
        destroyMcpProcess(child)
        throw failure
    }
}

internal fun JvmMcpStdioProcess.toProcessBuilder(): ProcessBuilder =
    ProcessBuilder(listOf(command) + arguments).apply {
        workingDirectory?.let { directory(File(it)) }
        environment().clear()
        environment().putAll(this@toProcessBuilder.environment)
    }

private fun String.isValidEnvironmentName(): Boolean =
    isNotBlank() &&
        length <= 128 &&
        none { it == '=' || it == '\u0000' || it == '\r' || it == '\n' }

private suspend fun destroyMcpProcess(process: Process) {
    withContext(Dispatchers.IO) {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }
}

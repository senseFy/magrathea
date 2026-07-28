package saien.magrathea.samples.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.TextPart
import saien.magrathea.credentials.AndroidKeystoreCredentialStore
import saien.magrathea.credentials.CredentialStoreFailure
import saien.magrathea.credentials.CredentialStoreException
import saien.magrathea.provider.api.DefaultHttpTransportConfig
import saien.magrathea.provider.api.HttpMethod
import saien.magrathea.provider.api.HttpRequestSpec
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpStreamFrame
import saien.magrathea.provider.api.HttpTimeoutConfig
import saien.magrathea.provider.api.HttpTransport
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderServerException
import saien.magrathea.provider.api.createDefaultHttpTransport
import saien.magrathea.provider.gemini.GeminiProviderAdapter
import saien.magrathea.storage.room.AndroidMagratheaRoom
import saien.magrathea.storage.room.StoredRecordCorruption
import saien.magrathea.storage.room.StoredRecordCorruptionException
import saien.magrathea.storage.room.StoredRecordCorruptionReason
import saien.magrathea.storage.room.StoredRecordCorruptionReporter

class MagratheaDeviceTestInstrumentation : Instrumentation() {
    private var arguments: Bundle = Bundle()

    override fun onCreate(arguments: Bundle?) {
        this.arguments = Bundle(arguments ?: Bundle())
        start()
    }

    override fun onStart() {
        val stage = arguments.getString(ARG_STAGE).orEmpty()
        val result = Bundle()
        try {
            val evidence = runBlocking {
                when (stage) {
                    STAGE_WRITE -> DeviceScenarios.write(targetContext)
                    STAGE_READ -> DeviceScenarios.readAfterProcessRestart(targetContext)
                    STAGE_NETWORK -> DeviceScenarios.network(
                        baseUrl = requireNotNull(arguments.getString(ARG_BASE_URL)) {
                            "Network stage requires a base URL"
                        },
                    )
                    STAGE_PERFORMANCE -> DeviceScenarios.performance(
                        context = targetContext,
                        holdMillis = arguments.getString(ARG_HOLD_MILLIS)
                            ?.toLongOrNull()
                            ?.coerceIn(MIN_HOLD_MILLIS, MAX_HOLD_MILLIS)
                            ?: DEFAULT_HOLD_MILLIS,
                    )
                    else -> error("Unknown device-test stage")
                }
            }
            val line = buildString {
                append("MAGRATHEA_ANDROID_DEVICE_STAGE_PASS stage=")
                append(stage)
                evidence.toSortedMap().forEach { (key, value) ->
                    append(' ')
                    append(key)
                    append('=')
                    append(value.requireSafeEvidenceValue())
                }
            }
            result.putString(REPORT_STREAM_KEY, "$line\n")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            val causeChain = generateSequence<Throwable>(failure) { it.cause }
                .map { it.javaClass.simpleName.ifBlank { "AnonymousThrowable" } }
                .distinct()
                .joinToString("-")
            val line = "MAGRATHEA_ANDROID_DEVICE_STAGE_FAIL stage=${stage.requireSafeEvidenceValue()} " +
                "type=${failure.javaClass.simpleName.requireSafeEvidenceValue()} " +
                "cause_chain=${causeChain.requireSafeEvidenceValue()}"
            result.putString(REPORT_STREAM_KEY, "$line\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun String.requireSafeEvidenceValue(): String {
        val safe = take(120).map { character ->
            if (character.isLetterOrDigit() || character in "._-:") character else '_'
        }.joinToString(separator = "")
        return safe.ifBlank { "none" }
    }

    private companion object {
        const val REPORT_STREAM_KEY = "stream"
        const val ARG_STAGE = "stage"
        const val ARG_HOLD_MILLIS = "holdMillis"
        const val ARG_BASE_URL = "baseUrl"
        const val STAGE_WRITE = "write"
        const val STAGE_READ = "read"
        const val STAGE_NETWORK = "network"
        const val STAGE_PERFORMANCE = "performance"
        const val MIN_HOLD_MILLIS = 5_000L
        const val MAX_HOLD_MILLIS = 30_000L
        const val DEFAULT_HOLD_MILLIS = 12_000L
    }
}

private object DeviceScenarios {
    private const val NAMESPACE = "device-verification"
    private const val DATABASE_NAME = "magrathea-device-verification.db"
    private const val PERFORMANCE_DATABASE_NAME = "magrathea-device-performance.db"
    private const val KEY_ALIAS = "saien.magrathea.credentials.$NAMESPACE.aes-gcm"
    private const val SECRET = "device-secret-canary-7f93"
    private const val REPLACEMENT_SECRET = "device-secret-replacement-91ac"
    private const val HEADER_SECRET = "device-header-canary-33e1"
    private const val CORRUPTION_SECRET = "device-corrupt-canary-42bd"
    private const val HTTP_ERROR_SECRET = "device-http-error-secret-5da2"
    private const val GEMINI_API_KEY = "device-gemini-key-canary-81ca"
    private const val READY_FILE_NAME = "magrathea-device-performance-ready"
    private const val PRIMARY_SESSION = "device-session-primary"
    private const val SECONDARY_SESSION = "device-session-secondary"
    private const val PERFORMANCE_SESSION = "device-session-performance"
    private const val STRESS_MESSAGE_COUNT = 1_000

    private val credentialRef = CredentialRef("device-provider", "device-profile")
    private val temporaryCredentialRef = CredentialRef("device-provider", "temporary-profile")

    suspend fun write(context: Context): Map<String, String> {
        val credentialStore = AndroidKeystoreCredentialStore(context, NAMESPACE)
        credentialStore.remove(credentialRef)
        credentialStore.remove(temporaryCredentialRef)

        credentialStore.put(
            credentialRef,
            ProviderCredential(
                value = SECRET,
                endpoint = "https://device.invalid/v1",
                headers = mapOf("X-Device-Probe" to HEADER_SECRET),
            ),
        )
        assertCredential(credentialStore.read(credentialRef), SECRET)

        credentialStore.put(
            credentialRef,
            ProviderCredential(
                value = REPLACEMENT_SECRET,
                endpoint = "https://device.invalid/v2",
                headers = mapOf("X-Device-Probe" to HEADER_SECRET),
            ),
        )
        assertCredential(credentialStore.read(credentialRef), REPLACEMENT_SECRET)

        credentialStore.put(temporaryCredentialRef, ProviderCredential("temporary-device-secret"))
        check(credentialStore.contains(temporaryCredentialRef))
        credentialStore.remove(temporaryCredentialRef)
        credentialStore.remove(temporaryCredentialRef)
        check(!credentialStore.contains(temporaryCredentialRef))

        val blob = requireCredentialBlob(context)
        val keyInfo = requireKeyInfo()

        val handle = AndroidMagratheaRoom.open(context, DATABASE_NAME, StoredRecordCorruptionReporter { })
        val primary = snapshot(PRIMARY_SESSION, messageCount = 2)
        val secondary = snapshot(SECONDARY_SESSION, messageCount = 3)
        handle.sessionStore.clear()
        handle.checkpointStore.clear()
        handle.sessionStore.saveSession(primary)
        handle.checkpointStore.saveCheckpoint(checkpoint(primary, turn = 2))
        handle.sessionStore.saveSession(secondary)
        handle.checkpointStore.saveCheckpoint(checkpoint(secondary, turn = 3))
        check(handle.sessionStore.listSessions().map { it.sessionId.value }.toSet() == setOf(PRIMARY_SESSION, SECONDARY_SESSION))
        handle.close()

        val database = context.getDatabasePath(DATABASE_NAME)
        check(database.isFile && database.length() > 0L)
        return mapOf(
            "api" to Build.VERSION.SDK_INT.toString(),
            "blob_bytes" to blob.length().toString(),
            "database_bytes" to database.length().toString(),
            "key_bits" to keyInfo.keySize.toString(),
            "key_security_level" to keySecurityLevel(keyInfo),
            "sessions" to "2",
        )
    }

    suspend fun readAfterProcessRestart(context: Context): Map<String, String> {
        val credentialStart = SystemClock.elapsedRealtimeNanos()
        val credentialStore = AndroidKeystoreCredentialStore(context, NAMESPACE)
        assertCredential(credentialStore.read(credentialRef), REPLACEMENT_SECRET)
        val credentialReadMillis = elapsedMillis(credentialStart)
        requireCredentialBlob(context)
        requireKeyInfo()

        val roomStart = SystemClock.elapsedRealtimeNanos()
        var handle = AndroidMagratheaRoom.open(context, DATABASE_NAME, StoredRecordCorruptionReporter { })
        val primaryId = AgentSessionId(PRIMARY_SESSION)
        val secondaryId = AgentSessionId(SECONDARY_SESSION)
        check(handle.sessionStore.loadSession(primaryId) == snapshot(PRIMARY_SESSION, messageCount = 2))
        check(handle.checkpointStore.loadLatestCheckpoint(primaryId) == checkpoint(snapshot(PRIMARY_SESSION, 2), 2))
        check(handle.sessionStore.loadSession(secondaryId) == snapshot(SECONDARY_SESSION, messageCount = 3))
        check(handle.checkpointStore.loadLatestCheckpoint(secondaryId) == checkpoint(snapshot(SECONDARY_SESSION, 3), 3))

        handle.sessionStore.deleteSession(primaryId)
        handle.checkpointStore.deleteSession(primaryId)
        handle.sessionStore.deleteSession(primaryId)
        handle.checkpointStore.deleteSession(primaryId)
        check(handle.sessionStore.loadSession(primaryId) == null)
        check(handle.checkpointStore.loadLatestCheckpoint(primaryId) == null)
        check(handle.sessionStore.listSessions().single().sessionId == secondaryId)
        handle.close()
        val roomReadMillis = elapsedMillis(roomStart)

        corruptSessionPayload(context, SECONDARY_SESSION)
        val corruptions = mutableListOf<StoredRecordCorruption>()
        handle = AndroidMagratheaRoom.open(
            context,
            DATABASE_NAME,
            StoredRecordCorruptionReporter(corruptions::add),
        )
        check(handle.sessionStore.listSessions().isEmpty())
        val corruption = try {
            handle.sessionStore.loadSession(secondaryId)
            error("Corrupt session was accepted")
        } catch (expected: StoredRecordCorruptionException) {
            expected
        }
        check(corruption.corruption.reason == StoredRecordCorruptionReason.INVALID_PAYLOAD)
        check(!corruption.toString().contains(CORRUPTION_SECRET))
        check(corruptions.isNotEmpty())
        check(!corruptions.toString().contains(CORRUPTION_SECRET))

        handle.sessionStore.clear()
        handle.checkpointStore.clear()
        handle.sessionStore.clear()
        handle.checkpointStore.clear()
        check(handle.sessionStore.listSessions().isEmpty())
        check(handle.checkpointStore.loadLatestCheckpoint(secondaryId) == null)
        handle.close()

        credentialStore.remove(credentialRef)
        credentialStore.remove(credentialRef)
        check(!credentialStore.contains(credentialRef))
        assertCredentialBlobRemoved(context)

        return mapOf(
            "corruption_reports" to corruptions.size.toString(),
            "credential_read_ms" to credentialReadMillis.toString(),
            "room_read_ms" to roomReadMillis.toString(),
            "restart_pid" to Process.myPid().toString(),
        )
    }

    suspend fun network(baseUrl: String): Map<String, String> {
        check(baseUrl.matches(Regex("http://127\\.0\\.0\\.1:[0-9]{2,5}")))
        val transport = createDefaultHttpTransport(
            DefaultHttpTransportConfig(
                requestTimeoutMillis = 10_000L,
                connectTimeoutMillis = 5_000L,
                socketTimeoutMillis = 10_000L,
                followRedirects = false,
            ),
        )
        return try {
            val response = transport.execute(
                HttpRequestSpec(
                    HttpMethod.GET,
                    "$baseUrl/health",
                    timeouts = HttpTimeoutConfig(requestTimeoutMillis = 5_000L),
                ),
            )
            check(response.statusCode == 200)
            check(response.body == "device-http-ok")

            val frames = transport.stream(
                HttpRequestSpec(
                    HttpMethod.GET,
                    "$baseUrl/sse",
                    timeouts = HttpTimeoutConfig(requestTimeoutMillis = 5_000L),
                ),
                HttpStreamFormat.SERVER_SENT_EVENTS,
            ).toList()
            check(frames.first() is HttpStreamFrame.ResponseStarted)
            check(frames.last() == HttpStreamFrame.Completed)
            val events = frames.filterIsInstance<HttpStreamFrame.ServerSentEvent>()
            check(events.map { it.data } == listOf("first", "second"))
            check(events.first().event == "probe")
            check(events.first().id == "device-1")

            val serverError = try {
                transport.execute(
                    HttpRequestSpec(
                        HttpMethod.GET,
                        "$baseUrl/error",
                        timeouts = HttpTimeoutConfig(requestTimeoutMillis = 5_000L),
                    ),
                )
                error("HTTP 500 was accepted")
            } catch (expected: ProviderServerException) {
                expected
            }
            check(serverError.statusCode == 500)
            check(!serverError.message.orEmpty().contains(HTTP_ERROR_SECRET))
            check(!serverError.toString().contains(HTTP_ERROR_SECRET))

            val geminiCredentialRef = CredentialRef("gemini", "device-fixture")
            val geminiAdapter = GeminiProviderAdapter()
            val geminiEvents = try {
                geminiAdapter.generate(
                    ProviderRequest(
                        model = ModelDescriptor(
                            provider = "gemini",
                            model = "gemini-contract-model",
                            supportsStreaming = true,
                        ),
                        messages = listOf(
                            AgentMessage(
                                role = MessageRole.USER,
                                parts = listOf(TextPart("Weather in Shanghai?")),
                            ),
                        ),
                        credentialRef = geminiCredentialRef,
                        credential = ProviderCredential(
                            value = GEMINI_API_KEY,
                            endpoint = "$baseUrl/gemini",
                        ),
                    ),
                ).toList().flatMap { it.events }
            } finally {
                geminiAdapter.close()
            }
            check(geminiEvents.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.delta } ==
                "Shanghai is sunny and 27°C.")
            val geminiCompleted = geminiEvents.filterIsInstance<ProviderEvent.Completed>().single()
            check(geminiEvents.last() == geminiCompleted)
            check(geminiCompleted.usage?.inputTokens == 24)
            check(geminiCompleted.usage?.outputTokens == 6)
            check(geminiCompleted.usage?.reasoningTokens == 0)

            val cancelMillis = verifyLocalSocketCancellation(transport)

            mapOf(
                "cancel_observed" to "1",
                "cancel_ms" to cancelMillis.toString(),
                "error_status" to serverError.statusCode.toString(),
                "gemini_completed" to "1",
                "gemini_events" to geminiEvents.size.toString(),
                "http_status" to response.statusCode.toString(),
                "sse_events" to events.size.toString(),
            )
        } finally {
            transport.close()
        }
    }

    private suspend fun verifyLocalSocketCancellation(transport: HttpTransport): Long {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val acceptedSocket = AtomicReference<Socket?>()
        val disconnectObserved = CompletableDeferred<Unit>()
        val serverJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                listener.accept().use { socket ->
                    acceptedSocket.set(socket)
                    socket.tcpNoDelay = true
                    socket.sendBufferSize = 16 * 1_024
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    check(reader.readLine() == "GET /cancel HTTP/1.1")
                    var headerLines = 1
                    while (true) {
                        val line = reader.readLine() ?: error("Cancellation probe request ended before its headers")
                        if (line.isEmpty()) break
                        headerLines += 1
                        check(headerLines <= 64)
                    }

                    val output = socket.getOutputStream()
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Transfer-Encoding: chunked\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                    )
                    writeHttpChunk(output, "data: ready\n\n".encodeToByteArray())
                    output.flush()
                    Thread.sleep(500L)

                    val event = "data: late\n\n".encodeToByteArray()
                    val payload = ByteArray(event.size * 1_024).also { bytes ->
                        repeat(1_024) { index -> event.copyInto(bytes, index * event.size) }
                    }
                    try {
                        repeat(4_096) {
                            writeHttpChunk(output, payload)
                            output.flush()
                        }
                        disconnectObserved.completeExceptionally(
                            IllegalStateException("Android transport cancellation did not close its socket"),
                        )
                    } catch (_: IOException) {
                        disconnectObserved.complete(Unit)
                    }
                }
            } catch (failure: Throwable) {
                disconnectObserved.completeExceptionally(failure)
            }
        }

        val streamReady = CompletableDeferred<Unit>()
        val clientJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                transport.stream(
                    HttpRequestSpec(
                        HttpMethod.GET,
                        "http://127.0.0.1:${listener.localPort}/cancel",
                        timeouts = HttpTimeoutConfig(requestTimeoutMillis = 30_000L),
                    ),
                    HttpStreamFormat.SERVER_SENT_EVENTS,
                ).collect { frame ->
                    if (frame is HttpStreamFrame.ServerSentEvent && frame.data == "ready") {
                        streamReady.complete(Unit)
                    }
                }
            } catch (failure: Throwable) {
                if (!streamReady.isCompleted) streamReady.completeExceptionally(failure)
            }
        }

        return try {
            withTimeout(5_000L) { streamReady.await() }
            check(clientJob.isActive)
            val cancelStarted = SystemClock.elapsedRealtimeNanos()
            clientJob.cancelAndJoin()
            val cancelMillis = elapsedMillis(cancelStarted)
            check(cancelMillis <= 2_000L)
            withTimeout(5_000L) { disconnectObserved.await() }
            cancelMillis
        } finally {
            clientJob.cancelAndJoin()
            acceptedSocket.get()?.close()
            listener.close()
            serverJob.cancelAndJoin()
        }
    }

    private fun writeHttpChunk(output: java.io.OutputStream, payload: ByteArray) {
        output.write("${payload.size.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
        output.write(payload)
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
    }

    suspend fun performance(context: Context, holdMillis: Long): Map<String, String> {
        val readyFile = File(context.filesDir, READY_FILE_NAME)
        readyFile.delete()
        val handle = AndroidMagratheaRoom.open(context, PERFORMANCE_DATABASE_NAME, StoredRecordCorruptionReporter { })
        handle.sessionStore.clear()
        handle.checkpointStore.clear()
        val stress = snapshot(PERFORMANCE_SESSION, STRESS_MESSAGE_COUNT)
        val pssBeforeKb = Debug.getPss().coerceAtLeast(0)

        val saveStarted = SystemClock.elapsedRealtimeNanos()
        handle.sessionStore.saveSession(stress)
        val saveMillis = elapsedMillis(saveStarted)
        val loadStarted = SystemClock.elapsedRealtimeNanos()
        val loaded = requireNotNull(handle.sessionStore.loadSession(stress.sessionId))
        val loadMillis = elapsedMillis(loadStarted)
        check(loaded.state.messages.size == STRESS_MESSAGE_COUNT)
        check(loaded.request.messages.size == STRESS_MESSAGE_COUNT)
        val pssAfterKb = Debug.getPss().coerceAtLeast(0)
        val javaHeapKb = ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_024L)

        readyFile.writeText(
            "pid=${Process.myPid()} messages=$STRESS_MESSAGE_COUNT save_ms=$saveMillis " +
                "load_ms=$loadMillis pss_kb=$pssAfterKb java_heap_kb=$javaHeapKb\n",
        )
        SystemClock.sleep(holdMillis)

        handle.sessionStore.clear()
        handle.checkpointStore.clear()
        handle.close()
        context.deleteDatabase(PERFORMANCE_DATABASE_NAME)
        readyFile.delete()
        return mapOf(
            "hold_ms" to holdMillis.toString(),
            "java_heap_kb" to javaHeapKb.toString(),
            "load_ms" to loadMillis.toString(),
            "messages" to STRESS_MESSAGE_COUNT.toString(),
            "pss_delta_kb" to (pssAfterKb - pssBeforeKb).toString(),
            "pss_kb" to pssAfterKb.toString(),
            "save_ms" to saveMillis.toString(),
        )
    }

    private fun assertCredential(credential: ProviderCredential, expectedValue: String) {
        check(credential.value == expectedValue)
        check(credential.endpoint == if (expectedValue == REPLACEMENT_SECRET) "https://device.invalid/v2" else "https://device.invalid/v1")
        check(credential.headers == mapOf("X-Device-Probe" to HEADER_SECRET))
        check(!credential.toString().contains(expectedValue))
        check(!credential.toString().contains(HEADER_SECRET))
    }

    private fun requireCredentialBlob(context: Context): File {
        val directory = File(context.noBackupFilesDir, "saien.magrathea.credentials.$NAMESPACE")
        val blobs = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }
        check(blobs.size == 1)
        val blob = blobs.single()
        check(blob.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        check(blob.name.matches(Regex("credential-[0-9a-f]{64}\\.json")))
        val persisted = blob.readText()
        listOf(SECRET, REPLACEMENT_SECRET, HEADER_SECRET, credentialRef.provider, credentialRef.profile).forEach { canary ->
            check(!persisted.contains(canary))
            check(!blob.name.contains(canary))
        }
        return blob
    }

    private fun assertCredentialBlobRemoved(context: Context) {
        val directory = File(context.noBackupFilesDir, "saien.magrathea.credentials.$NAMESPACE")
        check(directory.listFiles().orEmpty().none { it.isFile && it.name.endsWith(".json") })
    }

    private fun requireKeyInfo(): KeyInfo {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: error("Device Keystore key is missing")
        check(key.encoded == null)
        val keyInfo = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        check(keyInfo.keySize == 256)
        check(keyInfo.purposes and KeyProperties.PURPOSE_ENCRYPT != 0)
        check(keyInfo.purposes and KeyProperties.PURPOSE_DECRYPT != 0)
        check(KeyProperties.BLOCK_MODE_GCM in keyInfo.blockModes)
        check(KeyProperties.ENCRYPTION_PADDING_NONE in keyInfo.encryptionPaddings)
        return keyInfo
    }

    @Suppress("DEPRECATION")
    private fun keySecurityLevel(keyInfo: KeyInfo): String = if (Build.VERSION.SDK_INT >= 31) {
        keyInfo.securityLevel.toString()
    } else if (keyInfo.isInsideSecureHardware) {
        "secure_hardware"
    } else {
        "software"
    }

    private fun snapshot(sessionIdValue: String, messageCount: Int): AgentSessionSnapshot {
        val sessionId = AgentSessionId(sessionIdValue)
        val messages = (0 until messageCount).map { index ->
            AgentMessage(
                id = "$sessionIdValue-message-$index",
                role = MessageRole.USER,
                parts = listOf(TextPart("device-message-$index")),
                createdAtEpochMs = 1_000L + index,
            )
        }
        val request = AgentRequest(
            sessionId = sessionId,
            systemPrompt = "device-probe",
            messages = messages,
            model = ModelDescriptor(
                provider = "device-provider",
                model = "device-model",
                supportsStreaming = true,
            ),
            engine = AgentEngineConfig(
                provider = ProviderConfig(credentialRef = credentialRef),
            ),
        )
        return AgentSessionSnapshot(
            sessionId = sessionId,
            request = request,
            state = AgentStateSnapshot(messages = messages),
            updatedAtEpochMs = 10_000L + messageCount,
        )
    }

    private fun checkpoint(snapshot: AgentSessionSnapshot, turn: Int): AgentCheckpoint = AgentCheckpoint(
        sessionId = snapshot.sessionId,
        turn = turn,
        state = snapshot.state.copy(turn = turn),
    )

    private fun corruptSessionPayload(context: Context, sessionId: String) {
        val database = context.getDatabasePath(DATABASE_NAME)
        SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            val statement = sqlite.compileStatement(
                "UPDATE agent_sessions SET payload = ? WHERE sessionId = ?",
            )
            statement.bindString(1, "not-json-$CORRUPTION_SECRET")
            statement.bindString(2, sessionId)
            check(statement.executeUpdateDelete() == 1)
        }
    }

    private fun elapsedMillis(startNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - startNanos)
}

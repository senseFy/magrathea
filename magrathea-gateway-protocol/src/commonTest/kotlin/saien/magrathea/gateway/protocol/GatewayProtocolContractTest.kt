package saien.magrathea.gateway.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.JsonPart
import saien.magrathea.core.MediaReference
import saien.magrathea.core.MessageRole
import saien.magrathea.core.RemoteToolImageSource
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolImageAttachmentReference
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderUsage

class GatewayProtocolContractTest {
    private val codec = GatewayProtocolCodec()
    private val descriptor = GatewayStreamDescriptor(
        streamId = "stream-1",
        requestId = "session-1:0",
        sessionId = "session-1",
        expiresAtEpochMs = 2_000,
    )

    @Test
    fun requestWireHasExactV2AndNoCredentialEndpointOrHeaderSurface() {
        val encoded = codec.encodeCreateRequest(request())

        assertEquals(
            "{\"protocolVersion\":2,\"requestId\":\"session-1:0\",\"sessionId\":\"session-1\"," +
                "\"turn\":0,\"model\":{\"provider\":\"gemini\",\"model\":\"gemini-test\"}," +
                "\"messages\":[{\"id\":\"message-1\",\"role\":\"USER\",\"parts\":[{\"type\":\"text\"," +
                "\"text\":\"hello\"}],\"createdAtEpochMs\":1,\"metadata\":{}}],\"tools\":[],\"options\":{}," +
                "\"attachments\":[]}",
            encoded,
        )
        assertFalse("credential" in encoded.lowercase())
        assertFalse("endpoint" in encoded.lowercase())
        assertFalse("headers" in encoded.lowercase())
        assertEquals(request(), codec.decodeCreateRequest(encoded))
    }

    @Test
    fun decoderRejectsUnknownVersionUnknownEventAndUnknownField() {
        val encoded = codec.encodeEnvelope(envelope(0, GatewayEvent.StreamOpened()))

        assertFailsWith<GatewayProtocolException> {
            codec.decodeEnvelope(encoded.replace("\"protocolVersion\":2", "\"protocolVersion\":3"))
        }
        assertFailsWith<GatewayProtocolException> {
            codec.decodeEnvelope(encoded.replace("\"stream_opened\"", "\"unknown_opened\""))
        }
        assertFailsWith<GatewayProtocolException> {
            codec.decodeEnvelope(encoded.dropLast(1) + ",\"unexpected\":true}")
        }
    }

    @Test
    fun validatorRequiresOpenedThenContiguousSequenceThenOneTerminal() {
        val validator = GatewayStreamValidator(descriptor)

        assertIs<GatewayEvent.StreamOpened>(validator.accept(envelope(0, GatewayEvent.StreamOpened())))
        assertIs<GatewayEvent.TextDelta>(validator.accept(envelope(1, GatewayEvent.TextDelta("hello"))))
        assertIs<GatewayEvent.Completed>(
            validator.accept(envelope(2, GatewayEvent.Completed(stopReason = StopReason.COMPLETED))),
        )
        assertTrue(validator.isTerminal)
        assertFailsWith<GatewayProtocolException> {
            validator.accept(envelope(3, GatewayEvent.TextDelta("late")))
        }
    }

    @Test
    fun validatorRejectsMissingOpenGapDuplicateAndIdentityChange() {
        assertFailsWith<GatewayProtocolException> {
            GatewayStreamValidator(descriptor).accept(envelope(0, GatewayEvent.TextDelta("not-open")))
        }
        val gap = GatewayStreamValidator(descriptor)
        gap.accept(envelope(0, GatewayEvent.StreamOpened()))
        assertFailsWith<GatewayProtocolException> { gap.accept(envelope(2, GatewayEvent.TextDelta("gap"))) }

        val duplicate = GatewayStreamValidator(descriptor)
        duplicate.accept(envelope(0, GatewayEvent.StreamOpened()))
        assertFailsWith<GatewayProtocolException> { duplicate.accept(envelope(0, GatewayEvent.StreamOpened())) }

        val changed = GatewayStreamValidator(descriptor)
        assertFailsWith<GatewayProtocolException> {
            changed.accept(envelope(0, GatewayEvent.StreamOpened()).copy(streamId = "other"))
        }
    }

    @Test
    fun everyCanonicalProviderEventRoundTripsThroughExplicitGatewayType() {
        val tool = ToolCallPart(
            toolCallId = "call-1",
            toolName = "weather",
            arguments = buildJsonObject { put("city", JsonPrimitive("Shanghai")) },
            thoughtSignature = "sig",
            providerCallId = "provider-call",
        )
        val events = listOf(
            ProviderEvent.TextStart("text-signature-0"),
            ProviderEvent.TextDelta("hello", "text-signature-1"),
            ProviderEvent.TextEnd("hello", "text-signature-2"),
            ProviderEvent.ReasoningStart("reasoning-signature-0", redacted = true),
            ProviderEvent.ReasoningDelta("think", "reasoning-signature-1"),
            ProviderEvent.ReasoningEnd("think", "reasoning-signature-2", redacted = true),
            ProviderEvent.ToolCallStart(tool.copy(partial = true)),
            ProviderEvent.ToolCallDelta("call-1", "{\"city\":"),
            ProviderEvent.ToolCallEnd(tool.copy(partial = false)),
            ProviderEvent.UsageDelta(ProviderUsage(inputTokens = 3)),
            ProviderEvent.Completed(
                finishReason = "stop",
                stopReason = StopReason.COMPLETED,
                usage = ProviderUsage(inputTokens = 3, outputTokens = 2),
                providerMetadata = JsonObject(mapOf("responseId" to JsonPrimitive("response-1"))),
            ),
        )

        events.forEach { event ->
            assertEquals(event, event.toGatewayEvent().toProviderEventOrNull())
        }
    }

    @Test
    fun requestRejectsSecretMetadataAndUnresolvedOrRemoteAttachments() {
        val secretMessage = request().copy(
            messages = listOf(
                message(metadata = buildJsonObject { put("api_key", JsonPrimitive("secret")) }),
            ),
        )
        assertFailsWith<GatewayProtocolException> { secretMessage.validate() }
        assertFailsWith<GatewayProtocolException> {
            request().copy(
                messages = listOf(
                    message(metadata = buildJsonObject { put("session_cookie", JsonPrimitive("secret")) }),
                ),
            ).validate()
        }

        val remote = request().copy(
            messages = listOf(
                message(parts = listOf(AttachmentPart("https://attacker.example/file", "image/png"))),
            ),
        )
        assertFailsWith<GatewayProtocolException> { remote.validate() }

        val unresolved = request().copy(
            messages = listOf(
                message(parts = listOf(AttachmentPart("magrathea-attachment:file-1", "image/png"))),
            ),
        )
        assertFailsWith<GatewayProtocolException> { unresolved.validate() }
    }

    @Test
    fun attachmentReferenceMustMatchMessageExactly() {
        val attachment = GatewayAttachmentReference(
            id = "file-1",
            mediaType = "image/png",
            sizeBytes = 42,
            sha256 = "a".repeat(64),
        )
        val request = request().copy(
            messages = listOf(
                message(parts = listOf(AttachmentPart("magrathea-attachment:file-1", "image/png"))),
            ),
            attachments = listOf(attachment),
        )

        request.validate()
        assertEquals(request, codec.decodeCreateRequest(codec.encodeCreateRequest(request)))
    }

    @Test
    fun toolResultImagesRequireModelOnlyUploadedAttachmentReferences() {
        val descriptor = GatewayAttachmentReference(
            id = "tool-image-1",
            mediaType = "image/png",
            sizeBytes = 42,
        )
        fun toolResult(content: ToolResultImageContent): AgentMessage = message(
            parts = listOf(
                ToolResultPart(
                    toolCallId = "tool-call-1",
                    toolName = "inspect_image",
                    result = JsonPrimitive("ok"),
                    content = listOf(content),
                ),
            ),
        ).copy(role = MessageRole.TOOL)

        request().copy(
            messages = listOf(
                toolResult(
                    ToolResultImageContent(
                        source = ToolImageAttachmentReference(
                            "magrathea-attachment:tool-image-1",
                        ),
                        mimeType = "image/png",
                        audiences = setOf(ToolResultAudience.USER),
                    ),
                ),
            ),
            attachments = listOf(descriptor),
        ).validate()
        assertFailsWith<GatewayProtocolException> {
            request().copy(
                messages = listOf(
                    toolResult(
                        ToolResultImageContent(
                            source = RemoteToolImageSource("https://cdn.example.com/image.png"),
                            mimeType = "image/png",
                            audiences = setOf(ToolResultAudience.MODEL),
                        ),
                    ),
                ),
            ).validate()
        }
        assertFailsWith<GatewayProtocolException> {
            request().copy(
                messages = listOf(
                    toolResult(
                        ToolResultImageContent(
                            source = ToolImageAttachmentReference(
                                "magrathea-attachment:tool-image-1",
                            ),
                            mimeType = "image/png",
                            audiences = setOf(ToolResultAudience.MODEL),
                            reference = MediaReference("tool-result:run:1:0"),
                        ),
                    ),
                ),
                attachments = listOf(descriptor),
            ).validate()
        }

        val valid = request().copy(
            messages = listOf(
                toolResult(
                    ToolResultImageContent(
                        source = ToolImageAttachmentReference(
                            "magrathea-attachment:tool-image-1",
                        ),
                        mimeType = "image/png",
                        audiences = setOf(ToolResultAudience.MODEL),
                    ),
                ),
            ),
            attachments = listOf(descriptor),
        )
        valid.validate()
        assertEquals(valid, codec.decodeCreateRequest(codec.encodeCreateRequest(valid)))
    }

    @Test
    fun nestedRequestFieldsAreBoundedAndAmbiguousIdentitiesFailClosed() {
        assertFailsWith<GatewayProtocolException> {
            request().copy(model = GatewayModelReference("gemini:direct", "model")).validate()
        }
        assertFailsWith<GatewayProtocolException> {
            request().copy(model = GatewayModelReference("gemini", "bad model")).validate()
        }
        assertFailsWith<GatewayProtocolException> {
            request().copy(messages = listOf(message(), message(parts = listOf(TextPart("again"))))).validate()
        }
        assertFailsWith<GatewayProtocolException> {
            request().copy(messages = listOf(message().copy(createdAtEpochMs = -1))).validate()
        }
        assertFailsWith<GatewayProtocolException> {
            request().copy(
                messages = listOf(
                    message(parts = listOf(AttachmentPart("magrathea-attachment:file-1", "text/plain"))),
                ),
                attachments = listOf(GatewayAttachmentReference("file-1", "image/png", 1)),
            ).validate()
        }
        assertFailsWith<GatewayProtocolException> {
            request().copy(messages = listOf(message(parts = listOf(JsonPart(JsonPrimitive("12345"))))))
                .validate(GatewayProtocolLimits(maxJsonChars = 4))
        }
        assertFailsWith<GatewayProtocolException> {
            request().copy(
                tools = listOf(ToolDefinition("tool", "long", JsonObject(emptyMap()))),
            ).validate(GatewayProtocolLimits(maxDescriptionChars = 3))
        }
        assertFailsWith<GatewayProtocolException> {
            request().copy(
                tools = listOf(
                    ToolDefinition(
                        name = "tool",
                        description = "safe",
                        schema = JsonObject(emptyMap()),
                        requiresPermission = "bad permission",
                    ),
                ),
            ).validate()
        }
        assertFailsWith<GatewayProtocolException> {
            request().copy(
                tools = listOf(
                    ToolDefinition("tool", "safe", JsonObject(emptyMap()), timeoutMs = 0),
                ),
            ).validate()
        }
        assertFailsWith<GatewayProtocolException> {
            request().copy(
                tools = listOf(
                    ToolDefinition("tool", "safe", JsonObject(emptyMap()), maxCallsPerTurn = 129),
                ),
            ).validate()
        }
    }

    @Test
    fun eventAndProblemOpaqueFieldsHaveStrictLimits() {
        assertFailsWith<GatewayProtocolException> {
            GatewayProtocolCodec(limits = GatewayProtocolLimits(maxSignatureChars = 3)).encodeEnvelope(
                envelope(0, GatewayEvent.TextStart(signature = "long")),
            )
        }
        assertFailsWith<GatewayProtocolException> {
            codec.encodeEnvelope(envelope(0, GatewayEvent.StreamOpened(replayFromSequence = 1)))
        }
        assertFailsWith<GatewayProtocolException> {
            codec.encodeProblem(GatewayProblem(code = "invalid code", message = "safe"))
        }
        assertFailsWith<GatewayProtocolException> {
            codec.decodeProblem(
                "{\"protocolVersion\":2,\"code\":\"invalid\",\"message\":\"safe\"," +
                    "\"retryAfterMillis\":-1}",
            )
        }
    }

    @Test
    fun stableFailureTaxonomyRoundTripsWithoutAProviderMessageSurface() {
        val failures = listOf(
            GatewayEvent.Failed(GatewayFailureCode.AUTHENTICATION_FAILURE),
            GatewayEvent.Failed(GatewayFailureCode.CLIENT_FAILURE),
            GatewayEvent.Failed(GatewayFailureCode.CONTEXT_LIMIT),
            GatewayEvent.Failed(
                GatewayFailureCode.RATE_LIMIT,
                retryable = true,
                retryAfterMillis = 1_000,
            ),
            GatewayEvent.Failed(GatewayFailureCode.NETWORK_FAILURE, retryable = true),
            GatewayEvent.Failed(GatewayFailureCode.TIMEOUT, retryable = true),
            GatewayEvent.Failed(
                GatewayFailureCode.SERVER_FAILURE,
                retryable = true,
                retryAfterMillis = 2_000,
            ),
            GatewayEvent.Failed(GatewayFailureCode.PROTOCOL_FAILURE),
            GatewayEvent.Failed(GatewayFailureCode.REPLAY_WINDOW_EXHAUSTED),
            GatewayEvent.Failed(GatewayFailureCode.INTERNAL_FAILURE),
        )

        failures.forEachIndexed { index, failure ->
            val encoded = codec.encodeEnvelope(envelope(index.toLong(), failure))

            assertEquals(failure, codec.decodeEnvelope(encoded).event)
            assertFalse(encoded.contains("\"message\""))
        }

        assertFailsWith<GatewayProtocolException> {
            codec.encodeEnvelope(
                envelope(
                    20,
                    GatewayEvent.Failed(
                        code = GatewayFailureCode.RATE_LIMIT,
                        retryAfterMillis = 1,
                    ),
                ),
            )
        }
        assertFailsWith<GatewayProtocolException> {
            codec.encodeEnvelope(
                envelope(
                    21,
                    GatewayEvent.Failed(
                        code = GatewayFailureCode.NETWORK_FAILURE,
                        retryable = true,
                        retryAfterMillis = 1,
                    ),
                ),
            )
        }
        assertFailsWith<GatewayProtocolException> {
            codec.encodeEnvelope(
                envelope(
                    22,
                    GatewayEvent.Failed(
                        code = GatewayFailureCode.CONTEXT_LIMIT,
                        retryable = true,
                    ),
                ),
            )
        }
    }

    private fun request() = GatewayCreateStreamRequest(
        requestId = "session-1:0",
        sessionId = "session-1",
        turn = 0,
        model = GatewayModelReference(provider = "gemini", model = "gemini-test"),
        messages = listOf(message()),
    )

    private fun message(
        parts: List<saien.magrathea.core.MessagePart> = listOf(TextPart("hello")),
        metadata: JsonObject = JsonObject(emptyMap()),
    ) = AgentMessage(
        id = "message-1",
        role = MessageRole.USER,
        parts = parts,
        createdAtEpochMs = 1,
        metadata = metadata,
    )

    private fun envelope(sequence: Long, event: GatewayEvent) = GatewayStreamEnvelope(
        streamId = descriptor.streamId,
        requestId = descriptor.requestId,
        sessionId = descriptor.sessionId,
        sequence = sequence,
        event = event,
    )
}

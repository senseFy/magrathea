package saien.magrathea.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.ContextCompaction
import saien.magrathea.core.ContextManagementConfig
import saien.magrathea.core.ContextManagementState
import saien.magrathea.core.ContextPreparationAction
import saien.magrathea.core.ContextPreparationFailure
import saien.magrathea.core.ContextPreparationReason
import saien.magrathea.core.ContextPreparationRequest
import saien.magrathea.core.ContextSummaryResult
import saien.magrathea.core.InlineToolImageSource
import saien.magrathea.core.MessageRole
import saien.magrathea.core.MediaReference
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ModelInputModality
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.ProviderOptions
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.TextPart
import saien.magrathea.core.TokenUsage
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolMediaAttribution
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultContent
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultTextContent
import saien.magrathea.provider.api.ProviderRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContextManagementTest {
    @Test
    fun providerRequestIdentityExcludesTransientCredentialAndTransportMaterial() {
        val logicalRequest = ProviderRequest(
            model = ModelDescriptor(provider = "test", model = "test-model"),
            messages = listOf(message("u1", MessageRole.USER, "same logical input")),
        )
        val firstTransport = logicalRequest.copy(
            credential = ProviderCredential(
                value = "first-secret",
                endpoint = "https://first.example.com/v1",
                headers = mapOf("X-Credential-Header" to "first-header-secret"),
            ),
            endpoint = "https://first.example.com/v1",
            headers = mapOf(
                "Authorization" to "Bearer first-secret",
                "X-Routing" to "first-route",
            ),
        )
        val secondTransport = logicalRequest.copy(
            credential = ProviderCredential(
                value = "second-secret",
                endpoint = "https://second.example.com/v1",
                headers = mapOf("X-Credential-Header" to "second-header-secret"),
            ),
            endpoint = "https://second.example.com/v1",
            headers = mapOf(
                "Authorization" to "Bearer second-secret",
                "X-Routing" to "second-route",
            ),
        )

        assertEquals(
            providerRequestInputIdentity(logicalRequest),
            providerRequestInputIdentity(firstTransport),
        )
        assertEquals(
            providerRequestInputIdentity(logicalRequest),
            providerRequestInputIdentity(secondTransport),
        )
        assertFalse(
            providerRequestInputIdentity(logicalRequest) ==
                providerRequestInputIdentity(
                    logicalRequest.copy(
                        messages = listOf(message("u1", MessageRole.USER, "different input")),
                    ),
                ),
        )
    }

    @Test
    fun belowTokenBudget_keepsCanonicalHistoryAndSkipsSummary() = runTest {
        var summaryCalls = 0
        val manager = TokenAwareContextManager {
            summaryCalls += 1
            ContextSummaryResult("unused")
        }
        val messages = listOf(message("u1", MessageRole.USER, "hello"))
        val request = request(messages = messages, contextWindowTokens = 8_000)

        val result = manager.prepare(preparation(request))

        assertEquals(ContextPreparationAction.UNCHANGED, result.action)
        assertEquals(messages, result.messages)
        assertEquals(0, summaryCalls)
        assertNull(result.state.compaction)
    }

    @Test
    fun overTokenBudget_buildsSummaryProjectionWithoutMutatingFullHistory() = runTest {
        val summaries = mutableListOf<String>()
        val manager = TokenAwareContextManager { summaryRequest ->
            summaries += summaryRequest.conversation
            ContextSummaryResult("Earlier requirements and decisions.", TokenUsage(outputTokens = 12))
        }
        val messages = (1..5).map { index ->
            message("u$index", MessageRole.USER, "turn-$index ${"x".repeat(220)}")
        }
        val request = request(messages)
        val state = AgentStateSnapshot(messages = messages)

        val result = manager.prepare(preparation(request, state))

        assertEquals(ContextPreparationAction.COMPACTED, result.action)
        assertEquals(TokenUsage(outputTokens = 12), result.summaryUsage)
        assertSame(messages, state.messages)
        assertEquals(messages, request.messages)
        assertNotNull(result.state.compaction)
        assertTrue(result.messages.size < messages.size)
        assertTrue(
            result.messages.first().parts.filterIsInstance<TextPart>().single().text
                .contains("Earlier requirements and decisions."),
        )
        assertEquals(1, summaries.size)
    }

    @Test
    fun providerObservedInputUsage_drivesTheNextCompactionDecision() = runTest {
        var summaryCalls = 0
        val manager = TokenAwareContextManager {
            summaryCalls += 1
            ContextSummaryResult("Observed history summary")
        }
        val messages = listOf(
            message("u1", MessageRole.USER, "small"),
            message("a1", MessageRole.ASSISTANT, "small"),
            message("u2", MessageRole.USER, "small"),
        )
        val request = request(messages)
        val observed = ContextManagementState().withUsageObservation(
            request = request,
            messages = messages,
            throughMessageId = "u2",
            inputTokens = 190,
        )

        val result = manager.prepare(
            preparation(
                request = request,
                state = AgentStateSnapshot(messages = messages, contextManagement = observed),
            ),
        )

        assertEquals(ContextPreparationAction.COMPACTED, result.action)
        assertEquals(1, summaryCalls)
    }

    @Test
    fun repeatedCompaction_updatesPreviousSummaryUsingOnlyNewlyEligibleHistory() = runTest {
        val summaryRequests = mutableListOf<saien.magrathea.core.ContextSummaryRequest>()
        val manager = TokenAwareContextManager { summaryRequest ->
            summaryRequests += summaryRequest
            ContextSummaryResult("summary-${summaryRequests.size}")
        }
        val firstMessages = (1..5).map { index ->
            message("u$index", MessageRole.USER, "first-$index ${"a".repeat(220)}")
        }
        val firstRequest = request(firstMessages)
        val first = manager.prepare(preparation(firstRequest))
        assertEquals(ContextPreparationAction.COMPACTED, first.action)

        val expandedMessages = firstMessages + (6..9).map { index ->
            message("u$index", MessageRole.USER, "second-$index ${"b".repeat(220)}")
        }
        val secondRequest = request(expandedMessages)
        val second = manager.prepare(
            preparation(
                request = secondRequest,
                state = AgentStateSnapshot(
                    messages = expandedMessages,
                    contextManagement = first.state,
                ),
            ),
        )

        assertEquals(ContextPreparationAction.COMPACTED, second.action)
        assertEquals("summary-1", summaryRequests[1].previousSummary)
        assertFalse(summaryRequests[1].conversation.contains("first-1"))
        assertTrue(summaryRequests[1].conversation.contains("second-"))
        assertEquals(2, second.state.compaction?.generation)
    }

    @Test
    fun safeCut_neverSeparatesToolCallFromItsResult() = runTest {
        val manager = TokenAwareContextManager { ContextSummaryResult("tool work completed") }
        val messages = listOf(
            message("u1", MessageRole.USER, "old ${"x".repeat(220)}"),
            AgentMessage(
                id = "a-tool",
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    ToolCallPart(
                        toolCallId = "call-1",
                        toolName = "lookup",
                        arguments = buildJsonObject { put("q", JsonPrimitive("value")) },
                    ),
                ),
            ),
            AgentMessage(
                id = "tool-1",
                role = MessageRole.TOOL,
                parts = listOf(
                    ToolResultPart(
                        toolCallId = "call-1",
                        toolName = "lookup",
                        result = JsonPrimitive("result ${"y".repeat(220)}"),
                    ),
                ),
            ),
            message("u2", MessageRole.USER, "new ${"z".repeat(220)}"),
        )

        val result = manager.prepare(preparation(request(messages)))

        assertEquals(ContextPreparationAction.COMPACTED, result.action)
        val retainedCalls = result.messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>()
        val retainedResults = result.messages.flatMap { it.parts }.filterIsInstance<ToolResultPart>()
        assertTrue(
            retainedResults.all { resultPart ->
                retainedCalls.any { callPart -> callPart.toolCallId == resultPart.toolCallId }
            },
        )
    }

    @Test
    fun safeCut_rejectsACloserSuffixThatExceedsTheAvailableInputBudget() = runTest {
        val manager = TokenAwareContextManager { ContextSummaryResult("bounded summary") }
        val messages = listOf(
            message("u1", MessageRole.USER, "old ${"x".repeat(2_000)}"),
            message("u2", MessageRole.USER, "middle ${"y".repeat(37)}"),
            message("u3", MessageRole.USER, "recent ${"z".repeat(136)}"),
        )

        val result = manager.prepare(
            preparation(
                request(
                    messages = messages,
                    contextWindowTokens = 178,
                ),
            ),
        )

        assertEquals(ContextPreparationAction.COMPACTED, result.action)
        assertEquals("u3", result.state.compaction?.firstKeptMessageId)
        assertEquals(listOf("u3"), result.messages.drop(1).map(AgentMessage::id))
    }

    @Test
    fun editedHistory_invalidatesPersistentCompactionAndObservedUsage() {
        val original = listOf(
            message("u1", MessageRole.USER, "original"),
            message("u2", MessageRole.USER, "kept"),
        )
        val request = request(original)
        val state = ContextManagementState(
            compaction = ContextCompaction(
                summary = "summary",
                firstKeptMessageId = "u2",
                summarizedThroughMessageId = "u1",
                sourcePrefixDigest = historyPrefixDigest(original.take(1)),
                tokensBefore = 100,
                generation = 1,
                summaryModel = request.model,
            ),
        ).withUsageObservation(request, original, "u2", 120)
        val edited = listOf(
            message("u1", MessageRole.USER, "edited"),
            original[1],
        )

        val normalized = normalizeContextState(
            state = state,
            messages = edited,
            request = preparation(request.copy(messages = edited)),
        )

        assertNull(normalized.compaction)
        assertNull(normalized.usageObservation)
    }

    @Test
    fun changedProviderInputOptions_invalidatesObservedUsage() {
        val messages = listOf(message("u1", MessageRole.USER, "hello"))
        val original = request(messages)
        val observed = ContextManagementState().withUsageObservation(
            request = original,
            messages = messages,
            throughMessageId = "u1",
            inputTokens = 80,
        )
        val changed = original.copy(
            engine = original.engine.copy(
                provider = original.engine.provider.copy(
                    options = ProviderOptions(
                        family = "test",
                        values = buildJsonObject {
                            put("instructions", JsonPrimitive("extra provider instruction"))
                        },
                    ),
                ),
            ),
        )

        val normalized = normalizeContextState(
            state = observed,
            messages = messages,
            request = preparation(changed),
        )

        assertNull(normalized.usageObservation)
    }

    @Test
    fun changedModelInputModalities_invalidatesObservedUsage() {
        val messages = listOf(message("u1", MessageRole.USER, "hello"))
        val original = request(messages)
        val observed = ContextManagementState().withUsageObservation(
            request = original,
            messages = messages,
            throughMessageId = "u1",
            inputTokens = 80,
        )
        val changed = original.copy(
            model = original.model.copy(
                inputModalities = setOf(ModelInputModality.TEXT, ModelInputModality.IMAGE),
            ),
        )

        val normalized = normalizeContextState(
            state = observed,
            messages = messages,
            request = preparation(changed),
        )

        assertNull(normalized.usageObservation)
    }

    @Test
    fun summaryInput_omitsReasoningSignaturesProviderMetadataAndInlineData() {
        val serialized = serializeContextConversation(
            messages = listOf(
                AgentMessage(
                    id = "a1",
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        ReasoningPart(text = "private reasoning", signature = "secret-signature"),
                        AttachmentPart(
                            uri = "data:image/png;base64,VERY_SECRET_BYTES",
                            mimeType = "image/png",
                            fileName = "diagram.png",
                        ),
                        ToolResultPart(
                            toolCallId = "call-1",
                            toolName = "lookup",
                            result = JsonPrimitive("0123456789abcdef"),
                            providerMetadata = buildJsonObject {
                                put("signature", JsonPrimitive("provider-secret"))
                            },
                        ),
                    ),
                ),
            ),
            maxToolResultChars = 8,
            modelInputModalities = setOf(ModelInputModality.TEXT),
        )

        assertFalse(serialized.contains("private reasoning"))
        assertFalse(serialized.contains("secret-signature"))
        assertFalse(serialized.contains("VERY_SECRET_BYTES"))
        assertFalse(serialized.contains("provider-secret"))
        assertTrue(serialized.contains("[inline data omitted]"))
        assertTrue(serialized.contains("01234567…"))
    }

    @Test
    fun summaryInputUsesOnlyModelAudienceToolContentAndNeverSerializesImageBytes() {
        val serialized = serializeContextConversation(
            messages = listOf(
                AgentMessage(
                    id = "tool-1",
                    role = MessageRole.TOOL,
                    parts = listOf(
                        ToolResultPart(
                            toolCallId = "call-1",
                            toolName = "inspect_image",
                            result = JsonPrimitive("canonical result"),
                            content = listOf(
                                ToolResultTextContent(
                                    "model-visible text",
                                    setOf(ToolResultAudience.MODEL),
                                ),
                                ToolResultImageContent(
                                    source = InlineToolImageSource("SECRET_MODEL_IMAGE_BYTES"),
                                    previewSource = InlineToolImageSource("SECRET_PREVIEW_BYTES"),
                                    previewMimeType = "image/png",
                                    mimeType = "image/png",
                                    title = "Model image",
                                    attribution = ToolMediaAttribution(
                                        "Example",
                                        "https://example.com/model-image",
                                    ),
                                    audiences = setOf(ToolResultAudience.MODEL),
                                    reference = MediaReference("PRIVATE_MEDIA_REFERENCE"),
                                ),
                                ToolResultImageContent(
                                    source = InlineToolImageSource("SECRET_USER_IMAGE_BYTES"),
                                    title = "User image",
                                    audiences = setOf(ToolResultAudience.USER),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            maxToolResultChars = 100,
            modelInputModalities = setOf(
                ModelInputModality.TEXT,
                ModelInputModality.IMAGE,
            ),
        )

        assertTrue(serialized.contains("model-visible text"))
        assertTrue(serialized.contains("Model image"))
        assertFalse(serialized.contains("https://example.com/model-image"))
        assertTrue(serialized.contains("canonical result"))
        assertFalse(serialized.contains("User image"))
        assertFalse(serialized.contains("SECRET_MODEL_IMAGE_BYTES"))
        assertFalse(serialized.contains("SECRET_PREVIEW_BYTES"))
        assertFalse(serialized.contains("PRIVATE_MEDIA_REFERENCE"))
        assertFalse(serialized.contains("SECRET_USER_IMAGE_BYTES"))
    }

    @Test
    fun tokenEstimateForUserOnlyToolContentUsesCanonicalResult() {
        val result = buildJsonObject {
            put("query", "city skyline")
            put("count", 6)
        }
        val userOnly = toolResultMessage(
            result = result,
            displayText = "Images ready",
            content = listOf(
                ToolResultImageContent(
                    source = InlineToolImageSource("USER_PREVIEW_BYTES"),
                    title = "City skyline",
                    audiences = setOf(ToolResultAudience.USER),
                ),
            ),
        )
        val canonicalOnly = toolResultMessage(result = result)

        assertEquals(
            estimateTokens(canonicalOnly, setOf(ModelInputModality.TEXT)),
            estimateTokens(userOnly, setOf(ModelInputModality.TEXT)),
        )
    }

    @Test
    fun tokenEstimateForTextOnlyModelUsesCanonicalResultWhenImageIsFilteredOut() {
        val result = buildJsonObject {
            put("source", "https://example.com/full-result")
            put("description", "structured result visible to a text-only model")
        }
        val modelImage = toolResultMessage(
            result = result,
            displayText = "Image ready",
            content = listOf(
                ToolResultImageContent(
                    source = InlineToolImageSource("MODEL_IMAGE_BYTES"),
                    mimeType = "image/png",
                    title = "Model image",
                    audiences = setOf(ToolResultAudience.MODEL),
                ),
            ),
        )
        val canonicalOnly = toolResultMessage(result = result)

        assertEquals(
            estimateTokens(canonicalOnly, setOf(ModelInputModality.TEXT)),
            estimateTokens(modelImage, setOf(ModelInputModality.TEXT)),
        )
    }

    @Test
    fun tokenEstimateForImageModelIncludesCanonicalAndTypedContent() {
        val imageContent = listOf(
            ToolResultImageContent(
                source = InlineToolImageSource("MODEL_IMAGE_BYTES"),
                mimeType = "image/png",
                title = "Model image",
                audiences = setOf(ToolResultAudience.MODEL),
            ),
        )
        val first = toolResultMessage(
            result = JsonPrimitive("short result"),
            content = imageContent,
        )
        val second = toolResultMessage(
            result = JsonPrimitive("different canonical result ${"x".repeat(2_000)}"),
            content = imageContent,
        )
        val imageModel = setOf(ModelInputModality.TEXT, ModelInputModality.IMAGE)

        assertTrue(estimateTokens(second, imageModel) > estimateTokens(first, imageModel))
    }

    @Test
    fun summaryInputForTextOnlyModelUsesCanonicalResultWhenModelImageIsFilteredOut() {
        val serialized = serializeContextConversation(
            messages = listOf(
                toolResultMessage(
                    result = JsonPrimitive("canonical result"),
                    displayText = "UI-only display text",
                    content = listOf(
                        ToolResultImageContent(
                            source = InlineToolImageSource("SECRET_MODEL_IMAGE_BYTES"),
                            title = "Model image",
                            audiences = setOf(ToolResultAudience.MODEL),
                        ),
                    ),
                ),
            ),
            maxToolResultChars = 100,
            modelInputModalities = setOf(ModelInputModality.TEXT),
        )

        assertTrue(serialized.contains("canonical result"))
        assertFalse(serialized.contains("UI-only display text"))
        assertFalse(serialized.contains("Model image"))
        assertFalse(serialized.contains("SECRET_MODEL_IMAGE_BYTES"))
    }

    @Test
    fun proactiveSummaryFailure_failsOpenWithOriginalProjection() = runTest {
        val manager = TokenAwareContextManager {
            error("summarizer unavailable")
        }
        val messages = (1..5).map { index ->
            message("u$index", MessageRole.USER, "turn-$index ${"x".repeat(220)}")
        }

        val result = manager.prepare(preparation(request(messages)))

        assertEquals(ContextPreparationAction.FAILED_OPEN, result.action)
        assertEquals(ContextPreparationFailure.SUMMARY_FAILED, result.failure)
        assertEquals(messages, result.messages)
    }

    @Test
    fun wrappedFatalSummaryFailureEscapesExactly() = runTest {
        val fatal = TestFatalError(Any())
        val manager = TokenAwareContextManager {
            throw TestRecoverableException(fatal)
        }
        val messages = (1..5).map { index ->
            message("u$index", MessageRole.USER, "turn-$index ${"x".repeat(220)}")
        }

        val escaped = runCatching {
            manager.prepare(preparation(request(messages)))
        }.exceptionOrNull()

        assertSame(fatal, escaped)
    }

    @Test
    fun overflowRecoveryWithoutSafeCut_reportsFailedOpen() = runTest {
        val manager = TokenAwareContextManager { ContextSummaryResult("unused") }
        val onlyMessage = listOf(message("u1", MessageRole.USER, "single"))
        val request = request(onlyMessage)

        val result = manager.prepare(
            preparation(
                request = request,
                reason = ContextPreparationReason.OVERFLOW_RECOVERY,
            ),
        )

        assertEquals(ContextPreparationAction.FAILED_OPEN, result.action)
        assertEquals(ContextPreparationFailure.NO_SAFE_CUT, result.failure)
    }

    private fun preparation(
        request: AgentRequest,
        state: AgentStateSnapshot = AgentStateSnapshot(messages = request.messages),
        reason: ContextPreparationReason = ContextPreparationReason.PROACTIVE,
    ) = ContextPreparationRequest(
        request = request,
        state = state,
        turn = 0,
        reason = reason,
    )

    private fun request(
        messages: List<AgentMessage>,
        contextWindowTokens: Long = 220,
    ) = AgentRequest(
        sessionId = AgentSessionId("context-test"),
        messages = messages,
        model = ModelDescriptor(
            provider = "test",
            model = "test-model",
            contextWindowTokens = contextWindowTokens,
        ),
        engine = AgentEngineConfig(
            provider = ProviderConfig(maxTokens = 20),
            runtime = RuntimeConfig(
                contextManagement = ContextManagementConfig(
                    reserveTokens = 40,
                    keepRecentTokens = 60,
                    summaryMaxTokens = 32,
                    charsPerTokenEstimate = 4,
                    overflowRetryLimit = 1,
                ),
            ),
        ),
    )

    private fun message(id: String, role: MessageRole, text: String) = AgentMessage(
        id = id,
        role = role,
        parts = listOf(TextPart(text)),
    )

    private fun toolResultMessage(
        result: JsonElement,
        displayText: String? = null,
        content: List<ToolResultContent> = emptyList(),
    ) = AgentMessage(
        id = "tool-result",
        role = MessageRole.TOOL,
        parts = listOf(
            ToolResultPart(
                toolCallId = "call-1",
                toolName = "image_search",
                result = result,
                displayText = displayText,
                content = content,
            ),
        ),
    )

    private fun estimateTokens(
        message: AgentMessage,
        modelInputModalities: Set<ModelInputModality>,
    ): Long = estimateInputTokens(
        messages = listOf(message),
        systemPrompt = "",
        tools = emptyList(),
        providerOptions = null,
        modelInputModalities = modelInputModalities,
        charsPerToken = 1,
    )
}

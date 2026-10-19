package saien.magrathea.runtime

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.ContextEstimationUnit
import saien.magrathea.core.ContextManagementConfig
import saien.magrathea.core.ContextManagementState
import saien.magrathea.core.ContextPreparationReason
import saien.magrathea.core.ContextPreparationRequest
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelInputModality
import saien.magrathea.core.ToolResultClearing
import saien.magrathea.core.ToolResultClearingTrigger
import saien.magrathea.core.ToolResultContextConfig
import saien.magrathea.core.ToolResultContextReport
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultRetention
import saien.magrathea.core.ToolResultTextContent
import saien.magrathea.provider.api.modelProjection
import saien.magrathea.provider.api.sanitizedForModelBoundary

/** Portable text-size estimate in the unit selected by [ContextManagementConfig.estimationUnit]. */
internal class ContextTextEstimator(
    private val unit: ContextEstimationUnit,
    private val unitsPerToken: Int,
) {
    init {
        require(unitsPerToken > 0) { "Units per token must be positive" }
    }

    fun units(text: String): Long = when (unit) {
        ContextEstimationUnit.UTF16_CHARS -> text.length.toLong()
        ContextEstimationUnit.UTF8_BYTES -> text.utf8Length()
    }

    fun tokens(text: String): Long = unitsToTokens(units(text))

    fun unitsToTokens(units: Long): Long = if (units <= 0) 0 else (units + unitsPerToken - 1) / unitsPerToken

    fun tokensToUnits(tokens: Long): Long = tokens * unitsPerToken

    fun codePointUnits(codePoint: Int): Int = when (unit) {
        ContextEstimationUnit.UTF16_CHARS -> if (codePoint >= SUPPLEMENTARY_PLANE_START) 2 else 1
        ContextEstimationUnit.UTF8_BYTES -> when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < SUPPLEMENTARY_PLANE_START -> 3
            else -> 4
        }
    }
}

internal fun ContextManagementConfig.textEstimator(): ContextTextEstimator =
    ContextTextEstimator(estimationUnit, charsPerTokenEstimate)

/** Provider projection of the authoritative history: same order and IDs, projected Tool results. */
internal class ProjectedHistory(
    val messages: List<AgentMessage>,
    private val reducedResults: Int,
    private val reducedTokens: Long,
    private val ignoredStubs: Int,
) {
    fun report(pass: ToolResultClearingPass?, clearing: ToolResultClearing?) = ToolResultContextReport(
        reducedResults = reducedResults,
        reducedTokens = reducedTokens,
        newlyClearedResults = pass?.newlyClearedResults ?: 0,
        freedTokens = pass?.freedTokens ?: 0,
        clearingGeneration = clearing?.generation ?: 0,
        clearingTrigger = pass?.clearing?.trigger,
        ignoredStubs = ignoredStubs,
    )
}

internal class ToolResultClearingPass(
    val clearing: ToolResultClearing,
    val newlyClearedResults: Int,
    val freedTokens: Long,
)

/**
 * Stage 1 bounds every model-visible Tool result to the resolved budget; stage 2 replaces stale
 * results with restorable stubs up to a monotonic watermark. Both are deterministic functions of
 * the authoritative messages, the persisted watermark, and the request configuration, so the
 * projected prefix stays stable between clearing passes.
 */
internal class ToolResultProjector(
    private val config: ToolResultContextConfig,
    inputLimitTokens: Long?,
    private val estimator: ContextTextEstimator,
    private val modelInputModalities: Set<ModelInputModality>,
) {
    private val budgetTokens: Long? = config.budget?.resolve(inputLimitTokens)
    private val clearingConfig = config.clearing
    private val triggerTokens: Long? = clearingConfig?.trigger?.resolve(inputLimitTokens)
    private val minimumFreedTokens: Long = clearingConfig?.clearAtLeast?.resolve(inputLimitTokens) ?: 0

    fun project(messages: List<AgentMessage>, clearing: ToolResultClearing?): ProjectedHistory {
        val watermark = clearing
            ?.let { state -> messages.indexOfFirst { it.id == state.clearedThroughMessageId } }
            ?: -1
        var reducedResults = 0
        var reducedTokens = 0L
        var ignoredStubs = 0
        val projected = messages.mapIndexed { index, message ->
            if (message.parts.none { it is ToolResultPart }) return@mapIndexed message
            var changed = false
            val parts = message.parts.map { part ->
                if (part !is ToolResultPart) return@map part
                val budgeted = budgeted(part)
                val projectedPart = if (index <= watermark && part.isClearable()) {
                    val stub = stubbed(part)
                    if (tokens(stub.part) < tokens(budgeted.part)) {
                        if (stub.ignoredSuppliedStub) ignoredStubs++
                        stub.part
                    } else {
                        budgeted.part
                    }
                } else {
                    budgeted.part
                }
                if (projectedPart === budgeted.part && budgeted.part !== part) {
                    reducedResults++
                    reducedTokens += budgeted.savedTokens
                }
                if (projectedPart !== part) changed = true
                projectedPart
            }
            if (changed) message.copy(parts = parts) else message
        }
        return ProjectedHistory(projected, reducedResults, reducedTokens, ignoredStubs)
    }

    /** Chooses the next watermark, or null when no clearing pass should run now. */
    fun selectClearing(
        request: ContextPreparationRequest,
        state: ContextManagementState,
        estimatedInputTokens: Long,
        nowEpochMs: Long,
    ): ToolResultClearingPass? {
        val clearing = clearingConfig ?: return null
        val messages = request.state.messages
        val overflow = request.reason == ContextPreparationReason.OVERFLOW_RECOVERY
        val trigger = when {
            overflow -> ToolResultClearingTrigger.OVERFLOW_RECOVERY
            triggerTokens != null && estimatedInputTokens > triggerTokens -> ToolResultClearingTrigger.PRESSURE
            clearing.idleAfterMillis?.let { idle ->
                messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                    ?.let { latest -> nowEpochMs - latest.createdAtEpochMs >= idle }
            } == true -> ToolResultClearingTrigger.IDLE
            else -> return null
        }
        val rawStart = state.compaction
            ?.let { compaction -> messages.indexOfFirst { it.id == compaction.firstKeptMessageId } }
            ?.takeIf { it > 0 }
            ?: 0
        val protectFrom = if (clearing.protectActiveRun) activeRunStartIndex(request) else messages.size
        val previous = state.toolResultClearing
        val currentWatermark = previous
            ?.let { current -> messages.indexOfFirst { it.id == current.clearedThroughMessageId } }
            ?: -1
        val candidates = (rawStart until protectFrom).flatMap { index ->
            messages[index].parts
                .filterIsInstance<ToolResultPart>()
                .filter { it.isClearable() }
                .map { index to it }
        }
        val keep = clearing.keepRecentResults
        if (candidates.size <= keep) return null
        val lastClearableIndex = candidates[candidates.size - keep - 1].first
        val firstProtectedIndex = candidates.getOrNull(candidates.size - keep)?.first
        // A watermark never splits one message, so results of parallel calls clear together. It
        // rests on the last message whose results it clears, so unrelated edits keep it valid.
        val boundary = if (firstProtectedIndex == lastClearableIndex) {
            lastClearableIndex - 1
        } else {
            lastClearableIndex
        }
        val watermark = candidates.lastOrNull { (index, _) -> index <= boundary }?.first ?: return null
        if (watermark <= currentWatermark) return null

        var newlyCleared = 0
        var freedTokens = 0L
        candidates
            .filter { (index, _) -> index in (currentWatermark + 1)..watermark }
            .forEach { (_, part) ->
                val budgeted = tokens(budgeted(part).part)
                val stubbed = tokens(stubbed(part).part)
                if (stubbed < budgeted) {
                    newlyCleared++
                    freedTokens += budgeted - stubbed
                }
            }
        if (newlyCleared == 0) return null
        if (!overflow && freedTokens < minimumFreedTokens) return null
        return ToolResultClearingPass(
            clearing = ToolResultClearing(
                clearedThroughMessageId = messages[watermark].id,
                sourcePrefixDigest = historyPrefixDigest(messages.take(watermark + 1)),
                generation = (previous?.generation ?: 0) + 1,
                clearedResults = (previous?.clearedResults ?: 0) + newlyCleared,
                freedTokens = (previous?.freedTokens ?: 0) + freedTokens,
                trigger = trigger,
                createdAtEpochMs = nowEpochMs,
            ),
            newlyClearedResults = newlyCleared,
            freedTokens = freedTokens,
        )
    }

    private fun activeRunStartIndex(request: ContextPreparationRequest): Int {
        val messages = request.state.messages
        return request.activeRunStartMessageId
            ?.let { id -> messages.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            ?: messages.indexOfLast { it.role == MessageRole.USER }.takeIf { it >= 0 }
            ?: messages.size
    }

    private fun tokens(part: ToolResultPart): Long =
        estimateToolResultTokens(part, modelInputModalities, estimator)

    private class Budgeted(val part: ToolResultPart, val savedTokens: Long)

    private fun budgeted(part: ToolResultPart): Budgeted {
        val budget = budgetTokens ?: return Budgeted(part, 0)
        val text = part.modelText() ?: return Budgeted(part, 0)
        val textTokens = estimator.tokens(text)
        if (textTokens <= budget) return Budgeted(part, 0)
        val reducedText = middleTruncated(text, budget, textTokens)
        val reduced = part.copy(
            result = JsonPrimitive(reducedText),
            content = part.content.filterNot { it is ToolResultTextContent },
            modelResultVisible = true,
        )
        return Budgeted(reduced, (textTokens - estimator.tokens(reducedText)).coerceAtLeast(0))
    }

    private class Stubbed(val part: ToolResultPart, val ignoredSuppliedStub: Boolean)

    private fun stubbed(part: ToolResultPart): Stubbed {
        val supplied = part.contextHints?.stub
        val retained = supplied?.takeIf { it.toString().length <= config.maxStubChars }
        val result = buildJsonObject {
            put("type", CLEARED_RESULT_TYPE)
            put("tool", part.toolName)
            retained?.let { put("retained", it) }
            put("note", CLEARED_RESULT_NOTE)
        }
        return Stubbed(
            part = part.copy(result = result, content = emptyList(), modelResultVisible = true),
            ignoredSuppliedStub = supplied != null && retained == null,
        )
    }

    private fun ToolResultPart.modelText(): String? {
        val projection = sanitizedForModelBoundary()
            .modelProjection(modelInputModalities, CONTEXT_PROJECTION_CAPABILITIES)
        val pieces = buildList {
            projection.canonicalResult?.let { add(it.providerFallbackText()) }
            projection.content.filterIsInstance<ToolResultTextContent>().forEach { add(it.text) }
        }
        return pieces.joinToString("\n").takeIf { it.isNotEmpty() }
    }

    private fun middleTruncated(text: String, budgetTokens: Long, originalTokens: Long): String {
        val notice = "[Tool result truncated to fit the model context: about $originalTokens tokens " +
            "originally. The middle part was omitted.]\n"
        val available = estimator.tokensToUnits(budgetTokens) -
            estimator.units(notice) -
            estimator.units(TRUNCATION_MARKER)
        if (available <= 0) return notice.trimEnd()
        val headUnits = available / 2
        return notice + text.prefixWithin(headUnits) + TRUNCATION_MARKER + text.suffixWithin(available - headUnits)
    }

    private fun String.prefixWithin(maxUnits: Long): String {
        var units = 0L
        var end = 0
        while (end < length) {
            val codePoint = codePointAtIndex(end)
            val next = units + estimator.codePointUnits(codePoint)
            if (next > maxUnits) break
            units = next
            end += charCountOf(codePoint)
        }
        return substring(0, end)
    }

    private fun String.suffixWithin(maxUnits: Long): String {
        var units = 0L
        var start = length
        while (start > 0) {
            val codePoint = codePointBeforeIndex(start)
            val next = units + estimator.codePointUnits(codePoint)
            if (next > maxUnits) break
            units = next
            start -= charCountOf(codePoint)
        }
        return substring(start)
    }
}

private fun ToolResultPart.isClearable(): Boolean = contextHints?.retention != ToolResultRetention.PINNED

private fun String.utf8Length(): Long {
    var bytes = 0L
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            character.code < 0x80 -> bytes += 1
            character.code < 0x800 -> bytes += 2
            character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                bytes += 4
                index++
            }
            else -> bytes += 3
        }
        index++
    }
    return bytes
}

private fun String.codePointAtIndex(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        return ((high.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00) + SUPPLEMENTARY_PLANE_START
    }
    return high.code
}

private fun String.codePointBeforeIndex(index: Int): Int {
    val low = this[index - 1]
    if (low.isLowSurrogate() && index - 2 >= 0 && this[index - 2].isHighSurrogate()) {
        return ((this[index - 2].code - 0xD800) shl 10) + (low.code - 0xDC00) + SUPPLEMENTARY_PLANE_START
    }
    return low.code
}

private fun charCountOf(codePoint: Int): Int = if (codePoint >= SUPPLEMENTARY_PLANE_START) 2 else 1

private const val SUPPLEMENTARY_PLANE_START = 0x10000
private const val CLEARED_RESULT_TYPE = "tool_result_cleared"
private const val CLEARED_RESULT_NOTE =
    "Earlier result removed from context to save space. Call the tool again if you need its details."
private const val TRUNCATION_MARKER = "\n[…]\n"

package saien.magrathea.runtime

import saien.magrathea.core.ModelDescriptor

/** Resolves the bound sent to a Provider without inventing a limit for unknown model metadata. */
internal fun resolveMaxOutputTokens(
    model: ModelDescriptor,
    explicitMaxTokens: Int?,
    estimatedInputTokens: Long?,
    contextWindowTokensOverride: Long? = null,
): Int? {
    val requested = explicitMaxTokens ?: model.maxOutputTokens ?: return null
    val estimatedInput = estimatedInputTokens?.takeIf { it >= 0 } ?: return requested
    val contextWindow = contextWindowTokensOverride ?: model.contextWindowTokens ?: return requested
    val remainingContext = if (estimatedInput >= contextWindow) 1L else contextWindow - estimatedInput
    return minOf(requested.toLong(), remainingContext).toInt()
}

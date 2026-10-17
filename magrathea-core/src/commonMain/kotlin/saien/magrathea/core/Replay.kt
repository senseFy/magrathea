package saien.magrathea.core

import kotlinx.serialization.Serializable

/** Why replay omitted a whole message; this does not describe individual content blocks. */
@Serializable
enum class ReplayExclusionReason {
    EMPTY_AFTER_TRANSFORMATION,
    UNFINISHED_TOOL_CALLS,
    CANCELLED_OR_FAILED,
}

@Serializable
data class ReplayExclusion(
    val messageId: String,
    val reason: ReplayExclusionReason,
)

/** Null exclusions mean the policy does not report decisions; an empty list is a known result. */
data class ReplayResult(
    val messages: List<AgentMessage>,
    val exclusions: List<ReplayExclusion>? = null,
)

/** Latest prepared replay input, not proof of transport or permanent message validity. */
@Serializable
data class ReplayReport(
    val runId: AgentRunId,
    val turn: Int,
    val provider: String,
    val model: String,
    val exclusions: List<ReplayExclusion>,
)

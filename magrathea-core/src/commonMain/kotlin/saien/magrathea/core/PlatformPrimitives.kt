package saien.magrathea.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

fun interface IdGenerator {
    fun nextId(): String
}

fun interface EpochClock {
    fun nowEpochMs(): Long
}

fun interface MonotonicClock {
    fun nowMillis(): Long
}

@OptIn(ExperimentalUuidApi::class)
object SystemIdGenerator : IdGenerator {
    override fun nextId(): String = Uuid.random().toString()
}

object SystemEpochClock : EpochClock {
    override fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()
}

object SystemMonotonicClock : MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()

    override fun nowMillis(): Long = origin.elapsedNow().inWholeMilliseconds
}

class AgentModelFactory(
    private val idGenerator: IdGenerator = SystemIdGenerator,
    private val clock: EpochClock = SystemEpochClock,
) {
    fun createSessionId(): AgentSessionId = AgentSessionId(idGenerator.nextId())

    fun createRunId(): AgentRunId = AgentRunId.create(idGenerator)

    fun createMessage(
        role: MessageRole,
        parts: List<MessagePart>,
        metadata: JsonObject = buildJsonObject { },
        stopReason: StopReason? = null,
    ): AgentMessage = AgentMessage(
        id = idGenerator.nextId(),
        role = role,
        parts = parts,
        createdAtEpochMs = clock.nowEpochMs(),
        metadata = metadata,
        stopReason = stopReason,
    )

    fun createSessionSnapshot(
        sessionId: AgentSessionId,
        runId: AgentRunId,
        request: AgentRequest,
        state: AgentStateSnapshot,
        interruption: AgentInterruption? = null,
    ): AgentSessionSnapshot = AgentSessionSnapshot(
        sessionId = sessionId,
        runId = runId,
        request = request,
        state = state,
        interruption = interruption,
        updatedAtEpochMs = clock.nowEpochMs(),
    )
}

package saien.magrathea.catalog

import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.BackgroundExecutionRecord
import saien.magrathea.core.BackgroundOriginContext
import saien.magrathea.core.ToolImageAttachmentReference
import saien.magrathea.core.ToolResultContent
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart

/** Shared upper bound for ordinary session, anchored-window and collection pages. */
const val MAX_CATALOG_PAGE_SIZE: Int = 200

fun validateCatalogPageLimit(limit: Int) {
    require(limit in 1..MAX_CATALOG_PAGE_SIZE) { "Catalog page limit must be between 1 and $MAX_CATALOG_PAGE_SIZE" }
}

/** Minimal headless policy: canonical update time and identifiers, with no message-role assumptions. */
class DefaultSessionCatalogProjector : SessionCatalogProjector {
    override val policy = SessionCatalogPolicy("magrathea.default", 1)

    override fun normalizeSearch(text: String): String = text.trim().lowercase()

    override fun project(snapshot: AgentSessionSnapshot): SessionCatalogProjection = SessionCatalogProjection(
        fallbackLabel = snapshot.sessionId.value,
        modelDisplayName = snapshot.request.model.displayName,
        activityAtEpochMs = snapshot.updatedAtEpochMs,
        normalizedFallbackLabel = normalizeSearch(snapshot.sessionId.value),
        normalizedModelName = normalizeSearch(snapshot.request.model.displayName),
    )
}

/**
 * ASCII encodings whose lexical ordering is identical in Kotlin, SQLite BINARY and IndexedDB.
 * Long values never pass through a JavaScript Number; string keys preserve UTF-16 code-unit order.
 */
object CatalogKeyEncoding {
    fun ascendingLong(value: Long): String = (value.toULong() xor (1uL shl 63)).toString(16).padStart(16, '0')

    fun descendingLong(value: Long): String = (value.toULong() xor (1uL shl 63)).inv().toString(16).padStart(16, '0')

    fun stringKey(value: String): String = buildString(value.length * 4) {
        value.forEach { append(it.code.toString(16).padStart(4, '0')) }
    }

    fun activityRank(order: SessionActivityOrder): String = when (order) {
        is SessionActivityOrder.Accepted -> "0" + descendingLong(order.sequence)
        is SessionActivityOrder.Legacy -> "1" + descendingLong(order.timestampEpochMs)
    }

    fun collectionKey(id: String?): String = id?.let { "1" + stringKey(it) } ?: "0"
}

fun sessionCatalogSortKey(header: SessionCatalogHeader, sort: SessionCatalogSort): SessionCatalogSortKey {
    val usePin = sort == SessionCatalogSort.PIN_THEN_ACTIVITY
    return SessionCatalogSortKey(
        pinBucket = if (usePin && header.pinnedAtEpochMs == null) 1 else 0,
        pinRank = if (usePin) header.pinnedAtEpochMs?.let(CatalogKeyEncoding::descendingLong).orEmpty() else "",
        activityRank = CatalogKeyEncoding.activityRank(header.activity.order),
        idSortKey = CatalogKeyEncoding.stringKey(header.identity.sessionId.value),
    )
}

/** ACTIVITY_DESC requires an equality pin bucket, matching the portable indexed query family. */
fun normalizeCatalogQuery(projector: SessionCatalogProjector, query: SessionCatalogQuery): SessionCatalogQuery {
    if (query.sort == SessionCatalogSort.ACTIVITY_DESC && query.pin == SessionPinFilter.ANY) {
        throw SessionCatalogException(SessionCatalogFailure.UNSUPPORTED_QUERY)
    }
    return query.copy(search = projector.normalizeSearch(query.search))
}

fun validateCatalogCursor(cursor: SessionCatalogCursor?, query: SessionCatalogQuery, state: SessionCatalogState) {
    if (cursor != null && (cursor.query != query || cursor.policy != state.policy || cursor.generation != state.generation)) {
        throw SessionCatalogException(SessionCatalogFailure.INVALID_CURSOR)
    }
}

/**
 * Resolves only the activity fact; the adapter additionally verifies the intent's session identity.
 * [allowLegacyInitialization] is for explicitly authorized migration, never an ordinary new commit.
 * Null Preserve targets match only a null projected identity, never an arbitrary activity.
 */
fun resolveSessionActivity(
    mode: SessionActivityMode,
    projection: SessionCatalogProjection,
    previous: SessionActivityFact?,
    intent: PreparedActivityIntent?,
    allowLegacyInitialization: Boolean = false,
): SessionActivityFact {
    if (mode == SessionActivityMode.CANONICAL_TIME) {
        return SessionActivityFact(projection.activityIdentity, null, SessionActivityOrder.Legacy(projection.activityAtEpochMs))
    }
    if (previous != null && previous.observedIdentity == projection.activityIdentity) return previous
    if (intent is PreparedActivityIntent.Advance && intent.ticket.activityIdentity == projection.activityIdentity) {
        val oldSequence = (previous?.order as? SessionActivityOrder.Accepted)?.sequence ?: 0
        // Re-preparing the last accepted identity after a Preserve is the same operation, not a
        // newly accepted activity. It can restore the observed identity without changing order.
        if (previous != null && intent.ticket.sequence == oldSequence && previous.lastAcceptedIdentity == projection.activityIdentity) {
            return previous.copy(observedIdentity = projection.activityIdentity)
        }
        if (intent.ticket.sequence <= oldSequence) throw SessionCatalogException(SessionCatalogFailure.STALE_ACTIVITY)
        return SessionActivityFact(projection.activityIdentity, projection.activityIdentity, SessionActivityOrder.Accepted(intent.ticket.sequence))
    }
    if (intent is PreparedActivityIntent.Preserve && intent.targetActivityIdentity == projection.activityIdentity && previous != null) {
        return previous.copy(observedIdentity = projection.activityIdentity)
    }
    if (previous == null && (projection.activityIdentity == null || allowLegacyInitialization)) {
        return SessionActivityFact(projection.activityIdentity, null, SessionActivityOrder.Legacy(projection.activityAtEpochMs))
    }
    throw SessionCatalogException(SessionCatalogFailure.ACTIVITY_NOT_PREPARED)
}

/** Derived storage row; only [header] crosses ordinary list APIs. Search fields remain separate. */
data class SessionCatalogEntry(
    val header: SessionCatalogHeader,
    val normalizedTitle: String,
    val normalizedPreview: String,
    val normalizedModelName: String,
) {
    fun matches(query: SessionCatalogQuery): Boolean {
        val scopeMatches = when (val scope = query.scope) {
            SessionCatalogScope.All -> true
            SessionCatalogScope.Unassigned -> header.collectionId == null
            is SessionCatalogScope.Collection -> header.collectionId == scope.id
        }
        val pinMatches = when (query.pin) {
            SessionPinFilter.ANY -> true
            SessionPinFilter.PINNED -> header.pinnedAtEpochMs != null
            SessionPinFilter.UNPINNED -> header.pinnedAtEpochMs == null
        }
        return scopeMatches && pinMatches && (query.search.isEmpty() ||
            normalizedTitle.contains(query.search) || normalizedPreview.contains(query.search) || normalizedModelName.contains(query.search))
    }
}

fun materializeCatalogEntry(
    identity: SessionIdentity,
    rowVersion: Long,
    snapshot: AgentSessionSnapshot,
    projection: SessionCatalogProjection,
    organization: SessionOrganization,
    activity: SessionActivityFact,
    projector: SessionCatalogProjector,
): SessionCatalogEntry = SessionCatalogEntry(
    header = SessionCatalogHeader(
        identity = identity,
        rowVersion = rowVersion,
        label = organization.title ?: projection.fallbackLabel,
        previewExcerpt = projection.previewExcerpt,
        providerId = snapshot.request.model.provider,
        modelId = snapshot.request.model.model,
        modelDisplayName = projection.modelDisplayName,
        status = snapshot.state.status,
        activityAtEpochMs = projection.activityAtEpochMs,
        activity = activity,
        collectionId = organization.collectionId,
        pinnedAtEpochMs = organization.pinnedAtEpochMs,
    ),
    normalizedTitle = organization.title?.let(projector::normalizeSearch) ?: projection.normalizedFallbackLabel,
    normalizedPreview = projection.normalizedPreview,
    normalizedModelName = projection.normalizedModelName,
)

/**
 * All explicitly typed attachment references in canonical request, state and checkpoint content.
 * Arbitrary JSON, text and opaque Provider metadata are not interpreted as URI references.
 * No canonical payload is read by the corresponding candidate lookup API.
 */
fun extractSessionAttachmentUris(snapshot: AgentSessionSnapshot, checkpoint: AgentCheckpoint? = null): Set<String> = buildSet {
    fun addContentReferences(content: List<ToolResultContent>) {
        content.filterIsInstance<ToolResultImageContent>().forEach { image ->
            (image.source as? ToolImageAttachmentReference)?.let { add(it.uri) }
            (image.previewSource as? ToolImageAttachmentReference)?.let { add(it.uri) }
        }
    }
    sequenceOf(snapshot.request.messages, snapshot.state.messages, checkpoint?.state?.messages.orEmpty()).flatten().forEach { message ->
        message.parts.forEach { part ->
            when (part) {
                is AttachmentPart -> add(part.uri)
                is ToolResultPart -> addContentReferences(part.content)
                else -> Unit
            }
        }
    }
    checkpoint?.toolExecutions?.forEach { execution -> addContentReferences(execution.result?.content.orEmpty()) }
}

/** Typed references retained by an execution before its result reaches canonical history. */
fun extractBackgroundExecutionAttachmentUris(record: BackgroundExecutionRecord): Set<String> = buildSet {
    fun content(values: List<ToolResultContent>) = values.filterIsInstance<ToolResultImageContent>().forEach { image ->
        (image.source as? ToolImageAttachmentReference)?.let { add(it.uri) }
        (image.previewSource as? ToolImageAttachmentReference)?.let { add(it.uri) }
    }
    record.executionRequest.assistantMessage.parts.forEach { part -> when (part) {
        is AttachmentPart -> add(part.uri)
        is ToolResultPart -> content(part.content)
        else -> Unit
    } }
    content(record.result?.content.orEmpty())
}

/** Current origin contexts enforce empty message history; inspect typed fields, never opaque JSON. */
fun extractBackgroundOriginAttachmentUris(origin: BackgroundOriginContext): Set<String> = buildSet {
    origin.request.messages.forEach { message -> message.parts.forEach { part -> when (part) {
        is AttachmentPart -> add(part.uri)
        is ToolResultPart -> part.content.filterIsInstance<ToolResultImageContent>().forEach { image ->
            (image.source as? ToolImageAttachmentReference)?.let { add(it.uri) }
            (image.previewSource as? ToolImageAttachmentReference)?.let { add(it.uri) }
        }
        else -> Unit
    } } }
}

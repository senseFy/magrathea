package saien.magrathea.catalog

import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStatus

/** Identifies one deterministic projection, independently of the canonical wire schema. */
data class SessionCatalogPolicy(val id: String, val version: Int) {
    init {
        require(id.isNotBlank())
        require(version > 0)
    }
}

/** Storage identity; a deleted ID can later name a different incarnation. */
data class SessionIdentity(val sessionId: AgentSessionId, val incarnation: String) {
    init {
        require(incarnation.isNotBlank())
    }
}

enum class SessionActivityMode {
    /** A plain AgentPersistence consumer needs no catalog preparation calls. */
    CANONICAL_TIME,

    /**
     * Strict opt-in acceptance ordering. The first non-null projected activity identity needs a
     * matching [SessionCatalog.prepareActivity] ticket. Later identity changes need a matching
     * ticket or [SessionCatalog.preparePreservedActivity] context on the actual commit handle. Missing
     * preparation fails the canonical commit atomically with
     * [SessionCatalogFailure.ACTIVITY_NOT_PREPARED]; it never falls back to timestamp ordering.
     * Unchanged identities need no new context. Legacy backfill can initialize timestamp order.
     */
    PREPARED,
}

sealed interface SessionActivityOrder {
    data class Legacy(val timestampEpochMs: Long) : SessionActivityOrder

    data class Accepted(val sequence: Long) : SessionActivityOrder {
        init {
            require(sequence > 0)
        }
    }
}

/** Authoritative sidecar facts. Rebuilding a projection must preserve this entire value. */
data class SessionActivityFact(
    val observedIdentity: String?,
    val lastAcceptedIdentity: String?,
    val order: SessionActivityOrder,
)

data class SessionActivityTicket(
    val identity: SessionIdentity,
    val activityIdentity: String,
    val sequence: Long,
) {
    init {
        require(activityIdentity.isNotBlank())
        require(sequence > 0)
    }
}

/** Handle-owned transient data; neither intent is part of AgentRequest or its snapshot. */
sealed interface PreparedActivityIntent {
    data class Advance(val ticket: SessionActivityTicket) : PreparedActivityIntent

    /** Explicitly permits a changed projection identity without inventing a new activity. */
    data class Preserve(val targetActivityIdentity: String?) : PreparedActivityIntent
}

data class PreparedSessionInitialization(
    val identity: SessionIdentity,
    val initialOrganization: SessionOrganization,
)

/** Product-independent scalar output. The complete normalized preview stays out of list rows. */
data class SessionCatalogProjection(
    val fallbackLabel: String,
    val previewExcerpt: String = "",
    val modelDisplayName: String = "",
    val activityIdentity: String? = null,
    val activityAtEpochMs: Long,
    val normalizedFallbackLabel: String,
    val normalizedPreview: String = "",
    val normalizedModelName: String = "",
)

/**
 * A synchronous, deterministic, side-effect-free projection. Implementations must not access a
 * provider, UI, live runtime or external storage. Adapters call [project] outside write transactions.
 * Calls may run concurrently; implementations must be safe to share between operations and handles.
 * Only the bounded override title needs [normalizeSearch] while merging current organization data.
 */
interface SessionCatalogProjector {
    val policy: SessionCatalogPolicy
    val activityMode: SessionActivityMode
        get() = SessionActivityMode.CANONICAL_TIME

    fun project(snapshot: AgentSessionSnapshot): SessionCatalogProjection

    fun normalizeSearch(text: String): String
}

/** Flat collections and zero-or-one membership; title/pin are independent of collection membership. */
data class SessionOrganization(
    val title: String? = null,
    val collectionId: String? = null,
    val pinnedAtEpochMs: Long? = null,
) {
    val isEmpty: Boolean
        get() = title == null && collectionId == null && pinnedAtEpochMs == null
}

/** Name policy belongs to the caller; sortKey must use a portable lexicographic encoding. */
data class SessionCollection(
    val id: String,
    val name: String,
    val nameSortKey: String,
    val nameUniqueKey: String,
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
        require(nameUniqueKey.isNotEmpty())
    }
}

/** Field mutations prevent concurrent operations on different fields from replacing one another. */
sealed interface SessionOrganizationMutation {
    data class Title(val value: String?) : SessionOrganizationMutation
    data class Collection(val id: String?) : SessionOrganizationMutation
    data class Pin(val pinnedAtEpochMs: Long?) : SessionOrganizationMutation
}

data class SessionCatalogHeader(
    val identity: SessionIdentity,
    val rowVersion: Long,
    val label: String,
    val previewExcerpt: String,
    val providerId: String,
    val modelId: String,
    val modelDisplayName: String,
    val status: AgentStatus,
    val activityAtEpochMs: Long,
    val activity: SessionActivityFact,
    val collectionId: String?,
    val pinnedAtEpochMs: Long?,
)

sealed interface SessionCatalogScope {
    data object All : SessionCatalogScope
    data object Unassigned : SessionCatalogScope
    data class Collection(val id: String) : SessionCatalogScope {
        init {
            require(id.isNotBlank())
        }
    }
}

enum class SessionPinFilter { ANY, PINNED, UNPINNED }

enum class SessionCatalogSort { ACTIVITY_DESC, PIN_THEN_ACTIVITY }

/** [search] is normalized by the configured policy before matching or binding a cursor. */
data class SessionCatalogQuery(
    val scope: SessionCatalogScope = SessionCatalogScope.All,
    val pin: SessionPinFilter = SessionPinFilter.ANY,
    val sort: SessionCatalogSort = SessionCatalogSort.PIN_THEN_ACTIVITY,
    val search: String = "",
)

enum class CatalogPageDirection { AFTER, BEFORE }

/** All components sort ascending. Activity and pin ranks encode their descending source values. */
data class SessionCatalogSortKey(
    val pinBucket: Int,
    val pinRank: String,
    val activityRank: String,
    val idSortKey: String,
) : Comparable<SessionCatalogSortKey> {
    override fun compareTo(other: SessionCatalogSortKey): Int =
        compareValuesBy(this, other,
            SessionCatalogSortKey::pinBucket,
            SessionCatalogSortKey::pinRank,
            SessionCatalogSortKey::activityRank,
            SessionCatalogSortKey::idSortKey,
        )
}

data class SessionCatalogCursor(
    val query: SessionCatalogQuery,
    val policy: SessionCatalogPolicy,
    val generation: Long,
    val key: SessionCatalogSortKey,
)

enum class CatalogCoverage { COMPLETE, INDEXING, PARTIAL }

data class SessionCatalogState(
    val policy: SessionCatalogPolicy,
    val revision: Long,
    val generation: Long,
    val coverage: CatalogCoverage,
    /**
     * Durable invalidation token for collection pages. Changes with collection definitions, their
     * search projection, or cursor validity (including clear/rebuild), independently of ordinary
     * session commits, deletions, and membership/title/pin changes. Compare for equality, not as a
     * count: adapters store the global revision of the latest relevant change, initially zero.
     */
    val collectionsRevision: Long = 0,
)

/**
 * Adapters may return a short or empty nonterminal page to bound storage scans. Continue with the
 * returned cursor; entry count alone never proves exhaustion. Such cursors can identify a scanned
 * nonmatching row rather than a displayed entry, and must still advance strictly in their direction.
 */
data class SessionCatalogPage(
    val entries: List<SessionCatalogHeader>,
    val previousCursor: SessionCatalogCursor?,
    val nextCursor: SessionCatalogCursor?,
    /** No further rows in the requested direction; for windowAround, neither direction has more. */
    val exhausted: Boolean,
    val state: SessionCatalogState,
)

data class SessionCollectionQuery(val search: String = "")

data class SessionCollectionCursor(
    val query: SessionCollectionQuery,
    val policy: SessionCatalogPolicy,
    val generation: Long,
    val nameSortKey: String,
    val idSortKey: String,
)

/** As with session pages, an empty scan page may still have a continuation cursor. */
data class SessionCollectionPage(
    val entries: List<SessionCollection>,
    val previousCursor: SessionCollectionCursor?,
    val nextCursor: SessionCollectionCursor?,
    val exhausted: Boolean,
    val state: SessionCatalogState,
)

/**
 * Persisted references among the requested candidates. [coverage] covers every attachment-bearing
 * source in this store, including independent background records; it may be less complete than the
 * session-page coverage. An incomplete answer cannot establish that an absent URI is unreferenced.
 */
data class SessionAttachmentReferences(val referencedUris: Set<String>, val coverage: CatalogCoverage)

enum class SessionCatalogFailure {
    CLOSED,
    POLICY_MISMATCH,
    SESSION_NOT_FOUND,
    STALE_IDENTITY,
    COLLECTION_NOT_FOUND,
    COLLECTION_CONFLICT,
    INVALID_CURSOR,
    STALE_MAINTENANCE,
    MAINTENANCE_PROJECTION_FAILED,
    UNSUPPORTED_QUERY,
    ACTIVITY_NOT_PREPARED,
    STALE_ACTIVITY,
    INITIALIZATION_CONFLICT,
    COUNTER_EXHAUSTED,
    MIGRATION_CONFLICT,
    STALE_MIGRATION,
    UNREADABLE_MIGRATION_SOURCE,
}

/** Contains stable failure categories, never a snapshot or provider payload. */
class SessionCatalogException(val failure: SessionCatalogFailure) :
    IllegalStateException("Session catalog operation failed (${failure.name.lowercase()})")

/** Optional storage capability; it never acquires a live Agent session or executes an Agent. */
interface SessionCatalog {
    suspend fun state(): SessionCatalogState
    /** Bounded primary-key traversal of durable recovery facts, without canonical payload reads. */
    suspend fun pageRecoveryCandidates(cursor: SessionRecoveryCursor? = null, limit: Int): SessionRecoveryPage
    /** Canonical primary-key lookup, available even before the derived header has been backfilled. */
    suspend fun sessionIdentity(id: AgentSessionId): SessionIdentity?
    suspend fun sessionHeader(id: AgentSessionId): SessionCatalogHeader?
    suspend fun exists(id: AgentSessionId): Boolean
    suspend fun organization(identity: SessionIdentity): SessionOrganization?
    suspend fun collection(id: String): SessionCollection?

    /** [limit] must be in 1..[MAX_CATALOG_PAGE_SIZE]; larger bulk reads are not a catalog capability. */
    suspend fun pageSessions(
        query: SessionCatalogQuery,
        cursor: SessionCatalogCursor? = null,
        direction: CatalogPageDirection = CatalogPageDirection.AFTER,
        limit: Int,
    ): SessionCatalogPage

    /**
     * Returns at most [limit] rows, where limit is in 1..[MAX_CATALOG_PAGE_SIZE]. A missing anchor
     * or one outside the query returns an empty page; the adapter need not fill both window sides.
     */
    suspend fun windowAround(id: AgentSessionId, query: SessionCatalogQuery, limit: Int): SessionCatalogPage

    /** [limit] must be in 1..[MAX_CATALOG_PAGE_SIZE]. */
    suspend fun pageCollections(
        query: SessionCollectionQuery = SessionCollectionQuery(),
        cursor: SessionCollectionCursor? = null,
        direction: CatalogPageDirection = CatalogPageDirection.AFTER,
        limit: Int,
    ): SessionCollectionPage

    /**
     * Returns an anchored collection page from one consistent collection view, with at most [limit]
     * entries (1..[MAX_CATALOG_PAGE_SIZE]). A missing anchor or one outside [query] returns an empty
     * terminal page. The adapter need not fill both sides; use its cursors to continue, including
     * short search pages. [SessionCollectionPage.exhausted] means neither direction has more.
     */
    suspend fun windowAroundCollection(
        id: String,
        query: SessionCollectionQuery = SessionCollectionQuery(),
        limit: Int,
    ): SessionCollectionPage

    suspend fun createCollection(collection: SessionCollection)
    suspend fun renameCollection(id: String, name: String, nameSortKey: String, nameUniqueKey: String)
    suspend fun deleteCollection(id: String)
    suspend fun updateOrganization(identity: SessionIdentity, mutation: SessionOrganizationMutation): SessionOrganization?
    suspend fun setTitleIfAbsent(identity: SessionIdentity, title: String): Boolean
    /**
     * Reads references and their coverage in one storage snapshot. Adapters that have not indexed
     * independent background envelopes must not report COMPLETE while any exist (an otherwise
     * complete canonical index is reported as PARTIAL).
     * File deletion also requires the owner's live, draft and in-flight submission protections.
     */
    suspend fun referencedUris(candidates: Set<String>): SessionAttachmentReferences

    /**
     * Registers a new lifetime on the actual commit handle; no session row is created. The caller
     * must supply a fresh creation token and fence/drain a lifetime before releasing its context.
     */
    suspend fun prepareNewSession(
        id: AgentSessionId,
        creationToken: String,
        initialOrganization: SessionOrganization = SessionOrganization(),
    ): SessionIdentity

    /** Allocates one ticket now; duplicate preparation for the same identity/activity is idempotent. */
    suspend fun prepareActivity(identity: SessionIdentity, activityIdentity: String): SessionActivityTicket

    suspend fun preparePreservedActivity(identity: SessionIdentity, targetActivityIdentity: String?)

    /**
     * Releases only this exact activity context (null is an exact target, not a wildcard). The
     * caller must prove admission never occurred, or fence/drain every writer for this activity.
     * Returning from a send call alone is not such proof. Other activity and initialization
     * contexts in the same session remain registered; allocated sequence gaps are retained.
     */
    suspend fun releaseActivity(identity: SessionIdentity, activityIdentity: String?)

    /** Requires the caller's lifetime fence and completion of every possible matching commit. */
    suspend fun releasePreparation(identity: SessionIdentity)
}

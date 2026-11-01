package saien.magrathea.catalog

import saien.magrathea.core.AgentSessionId

const val MAX_CATALOG_MIGRATION_BATCH_SIZE: Int = 50

/** A frozen host source. The fingerprint must cover the entire original source, not one batch. */
data class OrganizationImportToken(
    val storeId: String,
    val lifecycle: Long,
    val sourceId: String,
    val fingerprint: String,
)

data class OrganizationImportReceipt(
    val token: OrganizationImportToken,
    val nextBatch: Long = 0,
    val imported: Long = 0,
    val skipped: Long = 0,
    val complete: Boolean = false,
    val invalidated: Boolean = false,
)

/** Only an existing physical legacy incarnation is eligible; headers and readable bodies are not required. */
data class LegacySessionOrganization(val sessionId: AgentSessionId, val organization: SessionOrganization)

data class SessionAttachmentName(val uri: String, val fileName: String)

data class SessionAttachmentNameTarget(
    val identity: SessionIdentity,
    val rowVersion: Long,
    val missingUris: Set<String>,
    /** Pass as afterUri to read the next URI page, even if none of this page's names were available. */
    val nextUri: String? = null,
)

data class SessionAttachmentNamePage(
    val entries: List<SessionAttachmentNameTarget>,
    val nextSessionId: AgentSessionId?,
    val exhausted: Boolean,
    val issues: List<SessionCatalogMaintenanceIssue> = emptyList(),
    /** Durable scan pages include physical order, including unreadable sources without a target. */
    val sources: List<SessionAttachmentNameSource> = emptyList(),
)

/** Null identity/version denotes an invalid physical Web key, never an invented AgentSessionId. */
data class SessionAttachmentNameSource(val identity: SessionIdentity?, val rowVersion: Long?, val cursor: String)

data class SessionAttachmentNameScanToken(
    val storeId: String,
    val lifecycle: Long,
    /** Scan attempt, independent of the disposable catalog index generation. */
    val generation: Long,
    val sourceId: String,
    val sourceVersion: Int,
)

data class SessionAttachmentNameScanProgress(
    val token: SessionAttachmentNameScanToken,
    val afterSessionId: AgentSessionId? = null,
    /** Backend-owned physical position; hosts must pass it through without interpreting it. */
    val cursor: String? = null,
    val processed: Long = 0,
    val failed: Long = 0,
    /** The finite pass ended; failed or managed sources need not have all names filled. */
    val complete: Boolean = false,
)

enum class SessionAttachmentNameScanOutcome {
    PROCESSED,
    FAILED,
    /** The host already fenced/attempted names before restoring and now owns this incarnation. */
    SKIPPED_MANAGED,
}

data class SessionAttachmentNamePatchResult(val identity: SessionIdentity, val rowVersion: Long, val changed: Boolean)

/** Original codec envelopes, captured once before the first successful name patch of this incarnation. */
data class SessionAttachmentNameBackup(
    val identity: SessionIdentity,
    val rowVersion: Long,
    val snapshotPayload: String,
    val checkpointPayload: String?,
)

/**
 * Optional, bounded migrations of host-owned organization data and canonical attachment names.
 * The host retains its original organization source read-only, gates organization commands until
 * import completes, and checks that the source fingerprint did not change during import.
 * Only one legacy organization source may be imported into a store. Completed receipts survive
 * clear/rebuild; clear permanently invalidates an unfinished job and prevents starting an old import.
 * Batch indices start at zero. Retry the last batch after an unknown result; a completed receipt is
 * final. Collections must precede their member assignments (or be included in the same batch).
 *
 * Before patch/restore, the host must fence and drain this session's runtime writers. It must then
 * restore the resulting canonical record before admitting another writer. Projection is outside
 * the transaction; incarnation, rowVersion and the checkpoint envelope are compared at commit.
 * Patches only fill null/blank AttachmentPart.fileName in snapshot and checkpoint. They never
 * interpret provider metadata, change activity order, or overwrite an existing nonblank filename.
 */
interface SessionCatalogMigration {
    /**
     * Whether this store lifecycle still permits a legacy import. Clear makes this false even
     * if no import receipt was ever created. Check completed receipts first; false permits the
     * host to skip its obsolete source and confirmation gate without inventing a completion receipt.
     */
    suspend fun organizationImportAllowed(): Boolean
    suspend fun organizationImportStatus(): OrganizationImportReceipt?
    suspend fun beginOrganizationImport(sourceId: String, fingerprint: String): OrganizationImportReceipt
    suspend fun importOrganizationBatch(
        token: OrganizationImportToken,
        batchIndex: Long,
        collections: List<SessionCollection>,
        organizations: List<LegacySessionOrganization>,
        complete: Boolean = false,
    ): OrganizationImportReceipt

    /** Resumes one fixed typed pass; a changed source/version starts a new attempt. No source DTO is stored. */
    suspend fun beginAttachmentNameScan(sourceId: String, sourceVersion: Int): SessionAttachmentNameScanProgress
    suspend fun restartAttachmentNameScan(expected: SessionAttachmentNameScanProgress): SessionAttachmentNameScanProgress
    suspend fun attachmentNameScanPage(expected: SessionAttachmentNameScanProgress, limit: Int = 25): SessionAttachmentNamePage
    /**
     * Advance only after handling this source. PROCESSED/FAILED compare identity and rowVersion;
     * SKIPPED_MANAGED compares identity while allowing later versions. Deleted rows may advance;
     * a reused ID cannot. After a successful patch, pass its returned rowVersion in the source.
     * Unknown commit results are recovered by beginAttachmentNameScan.
     */
    suspend fun advanceAttachmentNameScan(
        expected: SessionAttachmentNameScanProgress,
        source: SessionAttachmentNameSource,
        outcome: SessionAttachmentNameScanOutcome = SessionAttachmentNameScanOutcome.PROCESSED,
    ): SessionAttachmentNameScanProgress

    /** Checks the physical tail in the same transaction as the completion receipt. */
    suspend fun finishAttachmentNameScan(expected: SessionAttachmentNameScanProgress): SessionAttachmentNameScanProgress

    /** At most 50 missing URIs in lexical order. Re-read subsequent pages after each successful patch. */
    suspend fun attachmentNameTarget(id: AgentSessionId, afterUri: String? = null): SessionAttachmentNameTarget?
    /** At most [limit] canonical rows are decoded, one at a time; damaged rows are retained and reported. */
    suspend fun attachmentNameTargets(afterSessionId: AgentSessionId? = null, limit: Int = 50): SessionAttachmentNamePage
    suspend fun patchAttachmentNames(
        target: SessionAttachmentNameTarget,
        names: List<SessionAttachmentName>,
    ): SessionAttachmentNamePatchResult
    suspend fun attachmentNameBackup(identity: SessionIdentity): SessionAttachmentNameBackup?
    /** Explicit recovery only: restores the first backup after the same fence/drain and CAS checks. */
    suspend fun restoreAttachmentNameBackup(target: SessionAttachmentNameTarget): SessionAttachmentNamePatchResult
}

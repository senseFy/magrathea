package saien.magrathea.storage.room

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import saien.magrathea.catalog.CatalogPageDirection
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCollection
import saien.magrathea.catalog.SessionOrganizationMutation

class JvmRoomCollectionCatalogTest {
    @Test
    fun collectionRevisionsFollowDurableChangesAcrossHandlesAndIgnoreSessionWrites() = runTest {
        val directory = Files.createTempDirectory("room-collection-revisions-")
        val path = directory.resolve("store.db").toString()
        val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption") }
        val first = JvmMagratheaRoom.open(path, reporter)
        val second = JvmMagratheaRoom.open(path, reporter)
        try {
            assertEquals(0L, first.catalog.state().collectionsRevision)
            assertEquals(0L, second.catalog.state().collectionsRevision)
            val alpha = SessionCollection("a", "Alpha", "alpha", "alpha")
            val beta = SessionCollection("b", "Beta", "beta", "beta")
            first.catalog.createCollection(alpha)
            val created = second.catalog.state()
            assertEquals(created.revision, created.collectionsRevision)
            assertTrue(created.collectionsRevision > 0L)

            second.catalog.createCollection(beta)
            val both = first.catalog.state()
            assertEquals(both.revision, both.collectionsRevision)
            assertTrue(both.collectionsRevision > created.collectionsRevision)
            val cursor = assertNotNull(first.catalog.pageCollections(limit = 1).nextCursor)
            first.catalog.renameCollection(alpha.id, alpha.name, alpha.nameSortKey, alpha.nameUniqueKey)
            second.catalog.deleteCollection("missing")
            assertEquals(both, first.catalog.state())

            val snapshot = roomTestSnapshot("conversation")
            first.persistence.commit(snapshot, null)
            val identity = assertNotNull(first.catalog.sessionIdentity(snapshot.sessionId))
            first.catalog.updateOrganization(identity, SessionOrganizationMutation.Collection(alpha.id))
            first.catalog.updateOrganization(identity, SessionOrganizationMutation.Title("Custom title"))
            first.catalog.updateOrganization(identity, SessionOrganizationMutation.Pin(8))
            first.persistence.commit(snapshot.copy(updatedAtEpochMs = 900), null)
            first.migration.beginAttachmentNameScan("fixture", 1)
            assertTrue(second.catalog.state().revision > both.revision)
            assertEquals(both.collectionsRevision, second.catalog.state().collectionsRevision)

            second.catalog.renameCollection(alpha.id, "Renamed", "renamed", "renamed")
            val renamed = first.catalog.state()
            assertEquals(renamed.revision, renamed.collectionsRevision)
            assertTrue(renamed.collectionsRevision > both.collectionsRevision)
            second.catalog.deleteCollection(alpha.id)
            val deleted = first.catalog.state()
            assertEquals(deleted.revision, deleted.collectionsRevision)
            assertTrue(deleted.collectionsRevision > renamed.collectionsRevision)
            assertEquals(null, first.catalog.organization(identity)?.collectionId)
            first.catalog.createCollection(alpha)
            val recreated = second.catalog.state()
            assertTrue(recreated.collectionsRevision > deleted.collectionsRevision)
            first.persistence.deleteSession(snapshot.sessionId)
            assertEquals(recreated.collectionsRevision, second.catalog.state().collectionsRevision)

            first.persistence.clear()
            val cleared = second.catalog.state()
            assertEquals(cleared.revision, cleared.collectionsRevision)
            assertTrue(cleared.collectionsRevision > recreated.collectionsRevision)
            assertEquals(alpha, second.catalog.collection(alpha.id))
            assertEquals(
                SessionCatalogFailure.INVALID_CURSOR,
                assertFailsWith<SessionCatalogException> {
                    second.catalog.pageCollections(cursor = cursor, direction = CatalogPageDirection.AFTER, limit = 1)
                }.failure,
            )

            second.maintenance.rebuild(cleared.policy, cleared.generation)
            val rebuilt = first.catalog.state()
            assertEquals(rebuilt.revision, rebuilt.collectionsRevision)
            assertTrue(rebuilt.collectionsRevision > cleared.collectionsRevision)
            repeat(10) {
                if (!second.maintenance.status().complete) second.maintenance.runBatch(2)
            }
            assertTrue(second.maintenance.status().complete)
            // Rebuilding unchanged collection search values advances maintenance, not this token.
            assertEquals(rebuilt.collectionsRevision, first.catalog.state().collectionsRevision)
            first.close()
            second.close()
            val reopened = JvmMagratheaRoom.open(path, reporter)
            try {
                assertEquals(rebuilt.collectionsRevision, reopened.catalog.state().collectionsRevision)
                assertEquals(listOf(alpha, beta), reopened.catalog.pageCollections(limit = 10).entries)
            } finally {
                reopened.close()
            }
        } finally {
            first.close()
            second.close()
            check(directory.toFile().deleteRecursively())
        }
    }
}

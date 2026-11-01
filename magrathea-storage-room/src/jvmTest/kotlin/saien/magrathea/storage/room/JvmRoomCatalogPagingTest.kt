package saien.magrathea.storage.room

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import saien.magrathea.catalog.CatalogKeyEncoding
import saien.magrathea.catalog.CatalogPageDirection
import saien.magrathea.catalog.SessionCatalogCursor
import saien.magrathea.catalog.SessionCatalogPolicy
import saien.magrathea.catalog.SessionCatalogProjection
import saien.magrathea.catalog.SessionCatalogProjector
import saien.magrathea.catalog.SessionCatalogQuery
import saien.magrathea.catalog.SessionCatalogScope
import saien.magrathea.catalog.SessionCatalogSort
import saien.magrathea.catalog.SessionCollection
import saien.magrathea.catalog.SessionOrganizationMutation
import saien.magrathea.catalog.SessionPinFilter
import saien.magrathea.catalog.sessionCatalogSortKey
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmRoomCatalogPagingTest {
    @Test
    fun indexedPagesTraverseEveryScopeBothWaysWithoutReadingCanonicalPayloads() = runTest {
        val directory = Files.createTempDirectory("magrathea-catalog-paging-")
        val path = directory.resolve("store.db").toString()
        val recorded = CopyOnWriteArrayList<String>()
        val driver = RecordingDriver(recorded)
        val database = Room.databaseBuilder<MagratheaDatabase>(name = path)
            .setDriver(driver)
            .setQueryCoroutineContext(Dispatchers.Default)
            .addMigrations(MAGRATHEA_ROOM_MIGRATION_1_2, MAGRATHEA_ROOM_MIGRATION_2_3)
            .build()
        val store = MagratheaRoomStoreHandle(database, StoredRecordCorruptionReporter { error("Page decoded a payload") })
        try {
            store.catalog.createCollection(SessionCollection("group", "Group", CatalogKeyEncoding.stringKey("group"), "group"))
            val ids = (0 until 65).map { "s-${it.toString().padStart(3, '0')}" } + listOf("😀", "\uE000", "中文")
            val headers = ids.mapIndexed { index, id ->
                store.persistence.commit(roomTestSnapshot(id, updatedAtEpochMs = (index % 4).toLong()), null)
                val identity = assertNotNull(store.catalog.sessionHeader(AgentSessionId(id))).identity
                if (index % 3 == 0) store.catalog.updateOrganization(identity, SessionOrganizationMutation.Collection("group"))
                if (index % 5 == 0) store.catalog.updateOrganization(identity, SessionOrganizationMutation.Pin((index % 2).toLong()))
                assertNotNull(store.catalog.sessionHeader(identity.sessionId))
            }
            // A directory read must remain valid even if canonical payloads cannot be decoded.
            BundledSQLiteDriver().open(path).use { connection ->
                // Room may still be settling an invalidation write on another connection.
                connection.prepare("PRAGMA busy_timeout=5000").use { it.step() }
                connection.prepare("UPDATE agent_sessions SET payload = 'unreadable canonical payload'").use { it.step() }
            }
            recorded.clear()
            val queries = listOf(
                SessionCatalogQuery(),
                SessionCatalogQuery(pin = SessionPinFilter.UNPINNED, sort = SessionCatalogSort.ACTIVITY_DESC),
                SessionCatalogQuery(scope = SessionCatalogScope.Unassigned, pin = SessionPinFilter.UNPINNED, sort = SessionCatalogSort.ACTIVITY_DESC),
                SessionCatalogQuery(scope = SessionCatalogScope.Collection("group")),
                SessionCatalogQuery(scope = SessionCatalogScope.Collection("group"), pin = SessionPinFilter.UNPINNED, sort = SessionCatalogSort.ACTIVITY_DESC),
                SessionCatalogQuery(pin = SessionPinFilter.PINNED),
            )
            queries.forEach { query ->
                val expected = headers.filter { header ->
                    val scoped = when (val scope = query.scope) {
                        SessionCatalogScope.All -> true
                        SessionCatalogScope.Unassigned -> header.collectionId == null
                        is SessionCatalogScope.Collection -> header.collectionId == scope.id
                    }
                    val pinned = when (query.pin) {
                        SessionPinFilter.ANY -> true
                        SessionPinFilter.PINNED -> header.pinnedAtEpochMs != null
                        SessionPinFilter.UNPINNED -> header.pinnedAtEpochMs == null
                    }
                    scoped && pinned
                }.sortedWith(compareBy { sessionCatalogSortKey(it, query.sort) }).map { it.identity.sessionId }
                for (direction in CatalogPageDirection.entries) {
                    val found = mutableListOf<AgentSessionId>()
                    var cursor: SessionCatalogCursor? = null
                    var pages = 0
                    do {
                        val page = store.catalog.pageSessions(query, cursor, direction, limit = 7)
                        if (direction == CatalogPageDirection.AFTER) found.addAll(page.entries.map { it.identity.sessionId })
                        else found.addAll(0, page.entries.map { it.identity.sessionId })
                        cursor = if (direction == CatalogPageDirection.AFTER) page.nextCursor else page.previousCursor
                        check(++pages < 30) { "Pagination did not terminate" }
                    } while (cursor != null)
                    assertEquals(expected, found, "$query / $direction")
                }
            }
            assertTrue(recorded.none { it.contains("agent_sessions", ignoreCase = true) })
            assertTrue(recorded.none { it.contains("session_search", ignoreCase = true) })
            val pageSql = recorded.filter { it.startsWith("SELECT c.* FROM session_catalog") }.distinct()
            assertTrue(pageSql.isNotEmpty())
            BundledSQLiteDriver().open(path).use { connection ->
                pageSql.forEach { sql ->
                    val plan = mutableListOf<String>()
                    connection.prepare("EXPLAIN QUERY PLAN $sql").use { statement ->
                        while (statement.step()) plan += statement.getText(3)
                    }
                    assertTrue(plan.any { it.contains("index_session_catalog_") }, "$sql: $plan")
                    assertTrue(plan.none { it.contains("TEMP B-TREE") }, "$sql: $plan")
                }
            }
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively())
        }
    }

    @Test
    fun literalFullPreviewSearchUsesOnlyTheEffectiveTitleAndKeepsFieldsSeparate() = runTest {
        val directory = Files.createTempDirectory("magrathea-catalog-search-")
        val projector = object : SessionCatalogProjector {
            override val policy = SessionCatalogPolicy("search-fixture", 1)
            override fun normalizeSearch(text: String) = text.trim().lowercase()
            override fun project(snapshot: AgentSessionSnapshot) = SessionCatalogProjection(
                fallbackLabel = "hidden fallback",
                previewExcerpt = "short preview",
                modelDisplayName = "Model",
                activityAtEpochMs = snapshot.updatedAtEpochMs,
                normalizedFallbackLabel = "hidden fallback",
                normalizedPreview = "x".repeat(1_000) + " needle_%[tail]",
                normalizedModelName = "model",
            )
        }
        val store = JvmMagratheaRoom.open(directory.resolve("store.db").toString(), StoredRecordCorruptionReporter { error("Unexpected corruption") }, projector)
        try {
            val snapshot = roomTestSnapshot("search")
            store.persistence.commit(snapshot, null)
            val identity = assertNotNull(store.catalog.sessionHeader(snapshot.sessionId)).identity
            store.catalog.updateOrganization(identity, SessionOrganizationMutation.Title("Visible title"))
            suspend fun matches(needle: String) = store.catalog.pageSessions(SessionCatalogQuery(search = needle), limit = 10).entries.size
            assertEquals(1, matches("needle_%[tail]"))
            assertEquals(1, matches("visible TITLE"))
            assertEquals(0, matches("hidden fallback"))
            assertEquals(0, matches("visible titlexxx"))
            assertEquals(0, matches("needleZZ[tail]"))
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively())
        }
    }

    private class RecordingDriver(private val queries: MutableList<String>) : SQLiteDriver {
        private val delegate = BundledSQLiteDriver()
        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    queries += sql
                    return connection.prepare(sql)
                }
            }
        }
    }
}

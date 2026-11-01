package saien.magrathea.storage.room

import androidx.room.Room
import saien.magrathea.catalog.DefaultSessionCatalogProjector
import saien.magrathea.catalog.SessionCatalogProjector

object JvmMagratheaRoom {
    fun open(
        databasePath: String,
        reporter: StoredRecordCorruptionReporter,
    ): MagratheaRoomStoreHandle = open(databasePath, reporter, DefaultSessionCatalogProjector())

    fun open(
        databasePath: String,
        reporter: StoredRecordCorruptionReporter,
        projector: SessionCatalogProjector,
    ): MagratheaRoomStoreHandle {
        require(databasePath.isNotBlank()) { "databasePath must not be blank" }
        return buildMagratheaRoomStore(
            builder = Room.databaseBuilder<MagratheaDatabase>(name = databasePath),
            reporter = reporter,
            projector = projector,
        )
    }
}

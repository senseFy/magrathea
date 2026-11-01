package saien.magrathea.storage.room

import android.content.Context
import androidx.room.Room
import saien.magrathea.catalog.DefaultSessionCatalogProjector
import saien.magrathea.catalog.SessionCatalogProjector

object AndroidMagratheaRoom {
    fun open(
        context: Context,
        databaseName: String,
        reporter: StoredRecordCorruptionReporter,
    ): MagratheaRoomStoreHandle = open(context, databaseName, reporter, DefaultSessionCatalogProjector())

    fun open(
        context: Context,
        databaseName: String,
        reporter: StoredRecordCorruptionReporter,
        projector: SessionCatalogProjector,
    ): MagratheaRoomStoreHandle {
        databaseName.requireSafeStorageComponent("databaseName")
        val appContext = context.applicationContext
        val databasePath = appContext.getDatabasePath(databaseName).absolutePath
        return buildMagratheaRoomStore(
            builder = Room.databaseBuilder<MagratheaDatabase>(
                context = appContext,
                name = databasePath,
            ),
            reporter = reporter,
            projector = projector,
        )
    }
}

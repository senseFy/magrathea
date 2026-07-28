package saien.magrathea.storage.room

import android.content.Context
import androidx.room.Room

object AndroidMagratheaRoom {
    fun open(
        context: Context,
        databaseName: String,
        reporter: StoredRecordCorruptionReporter,
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
        )
    }
}

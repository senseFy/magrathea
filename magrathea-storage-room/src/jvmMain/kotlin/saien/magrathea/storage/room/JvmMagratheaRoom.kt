package saien.magrathea.storage.room

import androidx.room.Room

object JvmMagratheaRoom {
    fun open(
        databasePath: String,
        reporter: StoredRecordCorruptionReporter,
    ): MagratheaRoomStoreHandle {
        require(databasePath.isNotBlank()) { "databasePath must not be blank" }
        return buildMagratheaRoomStore(
            builder = Room.databaseBuilder<MagratheaDatabase>(name = databasePath),
            reporter = reporter,
        )
    }
}

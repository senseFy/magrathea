@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package saien.magrathea.storage.room

import androidx.room.Room
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

object IosMagratheaRoom {
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

    fun applicationSupportDatabasePath(
        applicationDirectoryName: String,
        databaseName: String,
    ): String {
        applicationDirectoryName.requireSafeStorageComponent("applicationDirectoryName")
        databaseName.requireSafeStorageComponent("databaseName")
        val root = requireNotNull(
            NSFileManager.defaultManager.URLForDirectory(
                directory = NSApplicationSupportDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            ),
        ) { "Application Support directory is unavailable" }
        val applicationDirectory = root.URLByAppendingPathComponent(applicationDirectoryName, isDirectory = true)
        requireNotNull(applicationDirectory) { "Application Support path is unavailable" }
        NSFileManager.defaultManager.createDirectoryAtURL(
            url = applicationDirectory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return requireNotNull(applicationDirectory.URLByAppendingPathComponent(databaseName)?.path) {
            "Database path is unavailable"
        }
    }
}

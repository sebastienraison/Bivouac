package com.bivouac.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SavedTrackEntity::class,
        BankedTrackEntity::class,
        LoggedTrackEntity::class,
        LoggedTrackDayEntity::class,
        LoggedTrackTagEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class BivouacDatabase : RoomDatabase() {

    abstract fun savedTrackDao(): SavedTrackDao
    abstract fun bankedTrackDao(): BankedTrackDao
    abstract fun loggedTrackDao(): LoggedTrackDao

    companion object {
        @Volatile private var instance: BivouacDatabase? = null

        fun getInstance(context: Context): BivouacDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BivouacDatabase::class.java,
                    "bivouac.db",
                )
                    // No user base to migrate yet (still pre-release, cf. F-Droid submission in
                    // progress) — a real Migration isn't worth writing for a single added table.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}

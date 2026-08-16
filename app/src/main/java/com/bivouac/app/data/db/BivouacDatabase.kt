package com.bivouac.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SavedTrackEntity::class,
        BankedTrackEntity::class,
        LoggedTrackEntity::class,
        LoggedTrackDayEntity::class,
        LoggedTrackTagEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class BivouacDatabase : RoomDatabase() {

    abstract fun savedTrackDao(): SavedTrackDao
    abstract fun bankedTrackDao(): BankedTrackDao
    abstract fun loggedTrackDao(): LoggedTrackDao

    companion object {
        @Volatile private var instance: BivouacDatabase? = null

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `logged_track_tag` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`trackId` TEXT NOT NULL, " +
                        "`tag` TEXT NOT NULL, " +
                        "FOREIGN KEY(`trackId`) REFERENCES `logged_track`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_logged_track_tag_trackId_tag` ON `logged_track_tag` (`trackId`, `tag`)",
                )
                db.execSQL(
                    "ALTER TABLE `logged_track` ADD COLUMN `note` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        fun getInstance(context: Context): BivouacDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BivouacDatabase::class.java,
                    "bivouac.db",
                )
                    .addMigrations(MIGRATION_5_6)
                    .build()
                    .also { instance = it }
            }
    }
}

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
    version = BivouacDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class BivouacDatabase : RoomDatabase() {

    abstract fun savedTrackDao(): SavedTrackDao
    abstract fun bankedTrackDao(): BankedTrackDao
    abstract fun loggedTrackDao(): LoggedTrackDao

    companion object {
        // Single source of truth for both the @Database version above and the BIV-66
        // restore-time check ("this backup is newer than the app can open") — a real filename,
        // not a comment reference, so the two can never silently drift apart.
        const val SCHEMA_VERSION = 7
        const val DATABASE_NAME = "bivouac.db"

        @Volatile private var instance: BivouacDatabase? = null

        // v1.2.0 (schema v1) is tagged and pinned in the F-Droid MR — real installs may still be
        // on it. Adds banked_track (BIV-15, "banque de traces"), verbatim from schemas/2.json.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `banked_track` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `gpxContent` TEXT NOT NULL, " +
                        "`bivouacTrackPointIndices` TEXT NOT NULL, `distanceMeters` REAL NOT NULL, " +
                        "`elevationGainMeters` REAL NOT NULL, `elevationLossMeters` REAL NOT NULL, " +
                        "`pointCount` INTEGER NOT NULL, `estimatedDurationMinutes` INTEGER NOT NULL, " +
                        "`savedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        // v1.3.0 (schema v2) is likewise tagged. Versions 3-4 never existed — the schema number
        // jumped straight to 5 in an unreleased Journal dev commit, so v2 is the only other real
        // starting point. Adds logged_track and logged_track_day, verbatim from schemas/5.json.
        val MIGRATION_2_5 = object : Migration(2, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `logged_track` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `sourceFileName` TEXT, " +
                        "`startedAt` INTEGER NOT NULL, `contentHash` TEXT NOT NULL, " +
                        "`distanceMeters` REAL NOT NULL, `elevationGainMeters` REAL NOT NULL, " +
                        "`elevationLossMeters` REAL NOT NULL, `pointCount` INTEGER NOT NULL, " +
                        "`estimatedDurationMinutes` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `logged_track_day` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, " +
                        "`dayIndex` INTEGER NOT NULL, `rawGpxContent` TEXT NOT NULL, " +
                        "FOREIGN KEY(`trackId`) REFERENCES `logged_track`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_logged_track_day_trackId` ON `logged_track_day` (`trackId`)",
                )
            }
        }

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

        // RIC-95 : logged_track.sourceFileName n'était écrit qu'à l'import et jamais lu nulle
        // part ; la colonne part avec le champ. SQLite d'avant l'API 34 n'a pas d'ALTER TABLE
        // DROP COLUMN, d'où le schéma classique recréer-copier-basculer (cible : schemas/7.json).
        // Les enfants logged_track_day / logged_track_tag référencent la table par son nom et les
        // contraintes FK ne sont pas appliquées pendant une migration Room (le PRAGMA foreign_keys
        // n'est activé qu'à l'onOpen, après onUpgrade) : le DROP ne déclenche donc aucun CASCADE
        // et leurs lignes retrouvent leur parent une fois la nouvelle table renommée.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `logged_track_new` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `contentHash` TEXT NOT NULL, " +
                        "`distanceMeters` REAL NOT NULL, `elevationGainMeters` REAL NOT NULL, " +
                        "`elevationLossMeters` REAL NOT NULL, `pointCount` INTEGER NOT NULL, " +
                        "`estimatedDurationMinutes` INTEGER NOT NULL, " +
                        "`note` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "INSERT INTO `logged_track_new` (`id`, `name`, `startedAt`, `contentHash`, " +
                        "`distanceMeters`, `elevationGainMeters`, `elevationLossMeters`, " +
                        "`pointCount`, `estimatedDurationMinutes`, `note`) " +
                        "SELECT `id`, `name`, `startedAt`, `contentHash`, `distanceMeters`, " +
                        "`elevationGainMeters`, `elevationLossMeters`, `pointCount`, " +
                        "`estimatedDurationMinutes`, `note` FROM `logged_track`",
                )
                db.execSQL("DROP TABLE `logged_track`")
                db.execSQL("ALTER TABLE `logged_track_new` RENAME TO `logged_track`")
            }
        }

        fun getInstance(context: Context): BivouacDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BivouacDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { instance = it }
            }

        // BIV-66: backup/restore needs the on-disk file quiescent (no open connection) while it's
        // copied or replaced — closes the current instance and drops the singleton so the next
        // getInstance() transparently reopens it (running the migration chain above against
        // whatever schema version a just-restored file has, same as a normal app update would).
        fun closeAndReset() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}

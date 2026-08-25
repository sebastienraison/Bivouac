package com.bivouac.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

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
        const val SCHEMA_VERSION = 13
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

        // RIC-62 : logged_track_day.rawGpxContent (TEXT) sort de SQLite vers un fichier par jour
        // sous filesDir/gpx/ (voir LoggedTrackGpxStore), la colonne devient rawGpxFilePath.
        // Même schéma recréer-copier-basculer que MIGRATION_6_7 ci-dessus (cible : schemas/8.json),
        // plus l'écriture d'un fichier par ligne avant la bascule.
        //
        // Lecture par tranches via substr(), jamais la colonne entière : un db.query() dans une
        // migration passe par un SQLiteCursor ordinaire adossé à la même CursorWindow (~2 Mo/ligne)
        // que le reste de l'app — lire rawGpxContent d'un coup reproduirait exactement le
        // SQLiteBlobTooBigException que cette migration répare (vérifié empiriquement par
        // BivouacDatabaseMigrationTest sur un contenu > 2 Mo). Android n'exposant pas l'API blob
        // incrémentale de SQLite, substr() est le canal de lecture bornée disponible ; il compte en
        // points de code, et chaque tranche tient largement dans une fenêtre.
        //
        // Fabrique plutôt que val statique : contrairement aux migrations précédentes, celle-ci a
        // besoin d'un Context pour localiser filesDir.
        fun migration7To8(context: Context): Migration {
            val appContext = context.applicationContext
            return object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    LoggedTrackGpxStore.dir(appContext).mkdirs()

                    data class DayRow(val id: Long, val trackId: String, val dayIndex: Int)
                    val rows = mutableListOf<DayRow>()
                    db.query("SELECT id, trackId, dayIndex FROM logged_track_day").use { cursor ->
                        while (cursor.moveToNext()) {
                            rows += DayRow(cursor.getLong(0), cursor.getString(1), cursor.getInt(2))
                        }
                    }

                    // Le schéma n'impose pas l'unicité de (trackId, dayIndex) : en cas de doublon
                    // le rowid départage, plutôt que d'écraser silencieusement le fichier d'une
                    // autre ligne. Écraser un fichier resté d'une tentative de migration échouée
                    // (la transaction SQL est annulée, pas les fichiers) est en revanche voulu.
                    val claimedPaths = mutableSetOf<String>()
                    val pathByRowId = mutableMapOf<Long, String>()
                    for (row in rows) {
                        var relativePath = LoggedTrackGpxStore.relativePath(row.trackId, row.dayIndex)
                        if (!claimedPaths.add(relativePath)) {
                            relativePath = "${LoggedTrackGpxStore.DIR_NAME}/${row.trackId}-day${row.dayIndex}-${row.id}.gpx"
                            claimedPaths.add(relativePath)
                        }
                        writeRawGpxInChunks(db, row.id, File(appContext.filesDir, relativePath))
                        pathByRowId[row.id] = relativePath
                    }

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `logged_track_day_new` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, " +
                            "`dayIndex` INTEGER NOT NULL, `rawGpxFilePath` TEXT NOT NULL, " +
                            "FOREIGN KEY(`trackId`) REFERENCES `logged_track`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    for (row in rows) {
                        db.execSQL(
                            "INSERT INTO `logged_track_day_new` (`id`, `trackId`, `dayIndex`, `rawGpxFilePath`) " +
                                "VALUES (?, ?, ?, ?)",
                            arrayOf<Any>(row.id, row.trackId, row.dayIndex, pathByRowId.getValue(row.id)),
                        )
                    }
                    db.execSQL("DROP TABLE `logged_track_day`")
                    db.execSQL("ALTER TABLE `logged_track_day_new` RENAME TO `logged_track_day`")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "`index_logged_track_day_trackId` ON `logged_track_day` (`trackId`)",
                    )
                }
            }
        }

        // Dénormalise sur logged_track_day ce qu'on lisait jusqu'ici en reparsant le GPX de chaque
        // jour de chaque trace (cible : schemas/9.json). Voir LoggedTrackDayEntity pour ce que
        // portent les trois colonnes.
        //
        // Trois ALTER TABLE ADD COLUMN et rien d'autre, délibérément : ni recréation de table, ni
        // copie, ni remplissage. Le rattrapage des lignes existantes a besoin de lire et de parser
        // tous les fichiers de la banque, ce qui se compte en secondes sur une archive un peu
        // fournie — le faire ici figerait l'app à la première ouverture d'après mise à jour, sans
        // le moindre retour à l'écran. Il se fait donc en arrière-plan, une fois la base ouverte
        // (voir LoggedTrackBackfill), et les lecteurs savent retomber sur l'ancien chemin tant
        // qu'une ligne n'est pas rattrapée.
        //
        // Effet de bord recherché : cette migration ne peut pas perdre de données, ce qui compte
        // pour une archive de plusieurs années qu'on ne peut pas reconstituer.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `logged_track_day` ADD COLUMN `contentHash` TEXT")
                db.execSQL("ALTER TABLE `logged_track_day` ADD COLUMN `startedAtMillis` INTEGER")
                db.execSQL("ALTER TABLE `logged_track_day` ADD COLUMN `elapsedSeconds` INTEGER")
            }
        }

        // RIC-97 : même traitement que migration7To8 (RIC-62), pour banked_track.gpxContent et
        // saved_track.gpxContent cette fois — pas de crash constaté à ce jour sur ces deux tables
        // (mesure prod : ≈500 Ko max), mais aucune borne structurelle ne les protège de la limite
        // CursorWindow (~2 Mo/ligne) : un import GPS dense (≈1 point/s) sur une seule journée, ou
        // une trace dupliquée depuis un trek multi-jours du Journal (RIC-40, qui concatène tous les
        // jours en un seul HikeTrack avant réécriture), l'atteint déjà en ordre de grandeur — voir
        // CR_RIC97 pour le calcul complet.
        //
        // pointCount de banked_track part dans le même mouvement : colonne écrite mais jamais lue
        // (vérifié par grep, zéro usage de `.pointCount` sur du BankedTrack*), et la table est de
        // toute façon recréée pour sortir gpxContent — pas de migration à part rien que pour ça.
        //
        // Même schéma recréer-copier-basculer et lecture par tranches (substr()) que migration7To8
        // (cible : schemas/10.json). saved_track est un singleton (au plus une ligne, id fixe) mais
        // suit exactement le même chemin, sans cas particulier.
        fun migration9To10(context: Context): Migration {
            val appContext = context.applicationContext
            return object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    PlanificationGpxStore.dir(appContext).mkdirs()

                    val bankedIds = mutableListOf<String>()
                    db.query("SELECT id FROM banked_track").use { cursor ->
                        while (cursor.moveToNext()) bankedIds += cursor.getString(0)
                    }
                    val bankedPathById = bankedIds.associateWith { id ->
                        val relativePath = PlanificationGpxStore.bankedRelativePath(id)
                        writeGpxContentInChunks(
                            db,
                            table = "banked_track",
                            idColumn = "id",
                            idValue = id,
                            target = File(appContext.filesDir, relativePath),
                        )
                        relativePath
                    }

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `banked_track_new` (" +
                            "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `gpxFilePath` TEXT NOT NULL, " +
                            "`bivouacTrackPointIndices` TEXT NOT NULL, `distanceMeters` REAL NOT NULL, " +
                            "`elevationGainMeters` REAL NOT NULL, `elevationLossMeters` REAL NOT NULL, " +
                            "`estimatedDurationMinutes` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`))",
                    )
                    for (id in bankedIds) {
                        db.execSQL(
                            "INSERT INTO `banked_track_new` (`id`, `name`, `gpxFilePath`, " +
                                "`bivouacTrackPointIndices`, `distanceMeters`, `elevationGainMeters`, " +
                                "`elevationLossMeters`, `estimatedDurationMinutes`, `savedAt`) " +
                                "SELECT `id`, `name`, ?, `bivouacTrackPointIndices`, `distanceMeters`, " +
                                "`elevationGainMeters`, `elevationLossMeters`, `estimatedDurationMinutes`, " +
                                "`savedAt` FROM `banked_track` WHERE `id` = ?",
                            arrayOf(bankedPathById.getValue(id), id),
                        )
                    }
                    db.execSQL("DROP TABLE `banked_track`")
                    db.execSQL("ALTER TABLE `banked_track_new` RENAME TO `banked_track`")

                    val savedIds = mutableListOf<Int>()
                    db.query("SELECT id FROM saved_track").use { cursor ->
                        while (cursor.moveToNext()) savedIds += cursor.getInt(0)
                    }
                    val savedPathById = savedIds.associateWith { id ->
                        val relativePath = PlanificationGpxStore.savedRelativePath()
                        writeGpxContentInChunks(
                            db,
                            table = "saved_track",
                            idColumn = "id",
                            idValue = id,
                            target = File(appContext.filesDir, relativePath),
                        )
                        relativePath
                    }

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `saved_track_new` (" +
                            "`id` INTEGER NOT NULL, `trackName` TEXT, `gpxFilePath` TEXT NOT NULL, " +
                            "`bivouacTrackPointIndices` TEXT NOT NULL, PRIMARY KEY(`id`))",
                    )
                    for (id in savedIds) {
                        db.execSQL(
                            "INSERT INTO `saved_track_new` (`id`, `trackName`, `gpxFilePath`, " +
                                "`bivouacTrackPointIndices`) " +
                                "SELECT `id`, `trackName`, ?, `bivouacTrackPointIndices` " +
                                "FROM `saved_track` WHERE `id` = ?",
                            arrayOf<Any>(savedPathById.getValue(id), id),
                        )
                    }
                    db.execSQL("DROP TABLE `saved_track`")
                    db.execSQL("ALTER TABLE `saved_track_new` RENAME TO `saved_track`")
                }
            }
        }

        // RIC-109 : sept sommes par segment de 200 m (voir TrackSegmenter/DaySegmentAggregate), qui
        // remplacent le calcul par ligne-rando de SpeedCalibrationCalculator par un calcul par
        // segments à l'intérieur de chaque rando — stable même sur un petit Journal (voir
        // CR_CALIBRATION_SEGMENTS.md). Même schéma que MIGRATION_8_9 : sept ALTER TABLE ADD COLUMN,
        // colonnes nullables, aucune recréation de table. Le rattrapage des lignes déjà rattrapées
        // par RIC-98/99 (contentHash non nul) doit repasser une fois de plus — voir
        // LoggedTrackBackfill, dont la requête de sélection change de contentHash IS NULL à
        // flatCount IS NULL pour cette raison précise.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `logged_track_day` ADD COLUMN `flatCount` INTEGER")
                db.execSQL("ALTER TABLE `logged_track_day` ADD COLUMN `flatDistanceMeters` REAL")
                db.execSQL("ALTER TABLE `logged_track_day` ADD COLUMN `flatHours` REAL")
                db.execSQL("ALTER TABLE `logged_track_day` ADD COLUMN `steepCount` INTEGER")
                db.execSQL("ALTER TABLE `logged_track_day` ADD COLUMN `steepDistanceMeters` REAL")
                db.execSQL("ALTER TABLE `logged_track_day` ADD COLUMN `steepGainMeters` REAL")
                db.execSQL("ALTER TABLE `logged_track_day` ADD COLUMN `steepHours` REAL")
            }
        }

        // RIC-115 : huitième somme par segment de 200 m (voir DaySegmentAggregate.stoppedHours),
        // qui mesure automatiquement la provision de pause en mode Auto/Sélection à partir des
        // heures que le calcul par segments (RIC-109) écartait jusqu'ici sans les compter nulle
        // part. Même schéma que MIGRATION_10_11 : un seul ALTER TABLE ADD COLUMN, colonne
        // nullable, aucune recréation de table. Même relais de marqueur que RIC-109 avait déjà
        // appliqué (contentHash -> flatCount) : le rattrapage repasse une fois de plus sur les
        // lignes déjà rattrapées jusqu'à RIC-109 (flatCount non nul, stoppedHours encore nul après
        // ce simple ADD COLUMN) — voir LoggedTrackDao, dont la requête de sélection change de
        // flatCount IS NULL à stoppedHours IS NULL pour cette raison précise.
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `logged_track_day` ADD COLUMN `stoppedHours` REAL")
            }
        }

        // RIC-19 : même patron que MIGRATION_8_9 et MIGRATION_10_11 — trois ALTER TABLE ADD COLUMN,
        // colonnes nullables (ou par défaut pour le marqueur), aucune recréation de table, aucun
        // rattrapage ici. Cible : schemas/13.json.
        //
        // Différence avec les rattrapages précédents : celui-ci (LoggedTrackBackfill.runElevation)
        // n'est PAS lancé en tâche de fond silencieuse depuis JournalViewModel comme les autres —
        // préférence utilisateur documentée (RIC-132 : le rattrapage fire-and-forget existant est
        // annulable si l'utilisateur quitte l'écran Journal, jugé inadapté ici). Voir
        // ElevationBackfillGate côté UI : popup bloquant + spinner au premier lancement post-
        // migration, avant que la moindre navigation ne soit possible.
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `logged_track_day` ADD COLUMN `maxElevationMeters` REAL")
                db.execSQL("ALTER TABLE `logged_track_day` ADD COLUMN `lastPointElevationMeters` REAL")
                db.execSQL(
                    "ALTER TABLE `logged_track_day` ADD COLUMN `elevationBackfilled` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        // ~256K points de code par tranche : au pire quadruplé en UTF-8 ça reste sous la fenêtre de
        // 2 Mo, et un GPX réel (ASCII pour l'essentiel) en est très loin.
        private const val MIGRATION_CHUNK_CODE_POINTS = 256 * 1024

        private fun writeRawGpxInChunks(db: SupportSQLiteDatabase, rowId: Long, target: File) {
            target.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                var position = 1L
                while (true) {
                    val chunk = db.query(
                        "SELECT substr(rawGpxContent, ?, ?) FROM logged_track_day WHERE id = ?",
                        arrayOf<Any>(position, MIGRATION_CHUNK_CODE_POINTS, rowId),
                    ).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
                    }
                    if (chunk.isEmpty()) break
                    writer.write(chunk)
                    // substr() avance en points de code ; String.length compte en unités UTF-16,
                    // d'où le codePointCount pour comparer des grandeurs identiques.
                    val codePoints = chunk.codePointCount(0, chunk.length)
                    if (codePoints < MIGRATION_CHUNK_CODE_POINTS) break
                    position += codePoints
                }
            }
        }

        // RIC-97 : même lecture par tranches que writeRawGpxInChunks ci-dessus, généralisée sur la
        // table/colonne id puisque banked_track et saved_track partagent le même nom de colonne
        // (gpxContent) — table et idColumn sont toujours des littéraux fixes de ce fichier, jamais
        // une entrée externe, donc l'interpolation directe dans le SQL est sans risque ici.
        private fun writeGpxContentInChunks(
            db: SupportSQLiteDatabase,
            table: String,
            idColumn: String,
            idValue: Any,
            target: File,
        ) {
            target.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                var position = 1L
                while (true) {
                    val chunk = db.query(
                        "SELECT substr(gpxContent, ?, ?) FROM $table WHERE $idColumn = ?",
                        arrayOf(position, MIGRATION_CHUNK_CODE_POINTS, idValue),
                    ).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
                    }
                    if (chunk.isEmpty()) break
                    writer.write(chunk)
                    val codePoints = chunk.codePointCount(0, chunk.length)
                    if (codePoints < MIGRATION_CHUNK_CODE_POINTS) break
                    position += codePoints
                }
            }
        }

        fun getInstance(context: Context): BivouacDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BivouacDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        migration7To8(context),
                        MIGRATION_8_9,
                        migration9To10(context),
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                    )
                    .build()
                    .also { instance = it }
            }

        // BIV-66: backup/restore needs the on-disk file quiescent (no open connection) while it's
        // copied or replaced — closes the current instance and drops the singleton so the next
        // getInstance() transparently reopens it (running the migration chain above against
        // whatever schema version a just-restored file has, same as a normal app update would).
        //
        // Contrat induit (RIC-103) : personne ne doit capturer durablement l'instance ni un DAO —
        // les repositories résolvent le leur à chaque accès, précisément pour survivre à ce cycle.
        //
        // Contrat induit (RIC-128) : ce synchronized(this) et celui de getInstance() ci-dessus
        // partagent le même moniteur que BackupManager.backup() tient (synchronized(BivouacDatabase),
        // qui résout vers cette même instance de companion object) pendant toute sa fenêtre de
        // copie — sans quoi un appel concurrent ici rouvrirait silencieusement la base pendant que
        // backup() la copie, avec le risque de perdre une écriture de l'archive sans le signaler.
        fun closeAndReset() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}

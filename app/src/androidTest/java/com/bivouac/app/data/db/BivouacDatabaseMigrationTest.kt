package com.bivouac.app.data.db

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Seed content below is a minimal synthetic fixture built only to exercise the migration path —
// no real hike data is available in this repository or worktree to seed a v5 database with.
private const val TRACK_1_GPX = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="bivouac-migration-test">
  <trk><name>Trace test 1</name><trkseg>
    <trkpt lat="45.1885" lon="5.7245"><ele>1200.0</ele><time>2026-06-01T08:00:00Z</time></trkpt>
    <trkpt lat="45.1890" lon="5.7250"><ele>1215.0</ele><time>2026-06-01T08:05:00Z</time></trkpt>
    <trkpt lat="45.1895" lon="5.7260"><ele>1240.0</ele><time>2026-06-01T08:10:00Z</time></trkpt>
  </trkseg></trk>
</gpx>"""

private const val TRACK_2_DAY_1_GPX = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="bivouac-migration-test">
  <trk><name>Trace test 2 - jour 1</name><trkseg>
    <trkpt lat="45.9237" lon="6.8694"><ele>1850.0</ele><time>2026-06-10T07:00:00Z</time></trkpt>
    <trkpt lat="45.9250" lon="6.8710"><ele>1920.0</ele><time>2026-06-10T07:15:00Z</time></trkpt>
  </trkseg></trk>
</gpx>"""

private const val TRACK_2_DAY_2_GPX = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="bivouac-migration-test">
  <trk><name>Trace test 2 - jour 2</name><trkseg>
    <trkpt lat="45.9300" lon="6.8800"><ele>2100.0</ele><time>2026-06-11T07:00:00Z</time></trkpt>
    <trkpt lat="45.9320" lon="6.8830"><ele>2180.0</ele><time>2026-06-11T07:30:00Z</time></trkpt>
    <trkpt lat="45.9340" lon="6.8850"><ele>2250.0</ele><time>2026-06-11T08:00:00Z</time></trkpt>
  </trkseg></trk>
</gpx>"""

// RIC-62 : contenu dépassant la CursorWindow (~2 Mo/ligne), généré plutôt que réel — aucune trace
// réelle n'est embarquable dans les sources de test (données personnelles, hors dépôt public).
// Une répétition de points suffit : la migration copie des caractères, elle ne parse pas.
private fun buildOversizedGpx(): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<gpx version=\"1.1\" creator=\"bivouac-migration-test\">\n")
    append("  <trk><name>Trace géante</name><trkseg>\n")
    repeat(30_000) { i ->
        append("    <trkpt lat=\"45.${100000 + i}\" lon=\"5.${200000 + i}\">")
        append("<ele>${1200 + i % 800}.0</ele><time>2026-06-01T08:00:00Z</time></trkpt>\n")
    }
    append("  </trkseg></trk>\n</gpx>")
}

// RIC-97 : même principe, mais côté Planification (banked_track/saved_track) — un import GPS dense
// (≈1 point/s) sur une seule journée, ou une trace dupliquée depuis un trek multi-jours du Journal,
// suffit à s'en approcher en pratique (voir CR_RIC97) ; le test le pousse délibérément au-delà.
private fun buildOversizedBankedGpx(): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<gpx version=\"1.1\" creator=\"bivouac-migration-test\">\n")
    append("  <trk><name>Trace banquée géante</name><trkseg>\n")
    repeat(30_000) { i ->
        append("    <trkpt lat=\"45.${300000 + i}\" lon=\"6.${400000 + i}\">")
        append("<ele>${1800 + i % 600}.0</ele><time>2026-07-01T07:00:00Z</time></trkpt>\n")
    }
    append("  </trkseg></trk>\n</gpx>")
}

@RunWith(AndroidJUnit4::class)
class BivouacDatabaseMigrationTest {

    private val testDbName = "bivouac-migration-test.db"
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BivouacDatabase::class.java,
    )

    // Les fichiers écrits par migration7To8/migration9To10 atterrissent dans le vrai filesDir du
    // contexte d'instrumentation — nettoyés pour ne pas polluer les autres tests (ni les runs
    // suivants).
    @After
    fun cleanUpGpxFiles() {
        LoggedTrackGpxStore.dir(targetContext).deleteRecursively()
        PlanificationGpxStore.dir(targetContext).deleteRecursively()
    }

    @Test
    fun migrate1To2_preservesExistingDataAndAddsBankedTrackTable() {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                "INSERT INTO saved_track (id, trackName, gpxContent, bivouacTrackPointIndices) " +
                    "VALUES (1, 'Trace en cours', '${TRACK_1_GPX.escapeSql()}', '[0,2]')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            testDbName,
            2,
            true,
            BivouacDatabase.MIGRATION_1_2,
        )

        migrated.query("SELECT id, trackName, gpxContent, bivouacTrackPointIndices FROM saved_track").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals("Trace en cours", cursor.getString(1))
            assertEquals(TRACK_1_GPX, cursor.getString(2))
            assertEquals("[0,2]", cursor.getString(3))
        }

        migrated.query("SELECT COUNT(*) FROM banked_track").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        migrated.execSQL(
            "INSERT INTO banked_track (id, name, gpxContent, bivouacTrackPointIndices, " +
                "distanceMeters, elevationGainMeters, elevationLossMeters, pointCount, " +
                "estimatedDurationMinutes, savedAt) VALUES ('bank-1', 'Belledonne', " +
                "'${TRACK_1_GPX.escapeSql()}', '[]', 8200.0, 650.0, 300.0, 3, 240, 1780300800000)",
        )
        migrated.query("SELECT id, name FROM banked_track").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("bank-1", cursor.getString(0))
            assertEquals("Belledonne", cursor.getString(1))
        }

        migrated.close()
    }

    @Test
    fun migrate2To5_preservesExistingDataAndAddsLoggedTrackTables() {
        helper.createDatabase(testDbName, 2).apply {
            execSQL(
                "INSERT INTO saved_track (id, trackName, gpxContent, bivouacTrackPointIndices) " +
                    "VALUES (1, 'Trace en cours', '${TRACK_1_GPX.escapeSql()}', '[0,2]')",
            )
            execSQL(
                "INSERT INTO banked_track (id, name, gpxContent, bivouacTrackPointIndices, " +
                    "distanceMeters, elevationGainMeters, elevationLossMeters, pointCount, " +
                    "estimatedDurationMinutes, savedAt) VALUES ('bank-1', 'Belledonne', " +
                    "'${TRACK_1_GPX.escapeSql()}', '[]', 8200.0, 650.0, 300.0, 3, 240, 1780300800000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            testDbName,
            5,
            true,
            BivouacDatabase.MIGRATION_2_5,
        )

        migrated.query("SELECT id, trackName FROM saved_track").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals("Trace en cours", cursor.getString(1))
        }
        migrated.query("SELECT id, name FROM banked_track").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("bank-1", cursor.getString(0))
            assertEquals("Belledonne", cursor.getString(1))
        }

        migrated.query("SELECT COUNT(*) FROM logged_track").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM logged_track_day").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        migrated.execSQL(
            "INSERT INTO logged_track (id, name, sourceFileName, startedAt, contentHash, " +
                "distanceMeters, elevationGainMeters, elevationLossMeters, pointCount, " +
                "estimatedDurationMinutes) VALUES " +
                "('track-1', 'Randonnee Belledonne', 'belledonne.gpx', 1780300800000, " +
                "'hash-track-1', 8200.0, 650.0, 300.0, 3, 240)",
        )
        migrated.execSQL(
            "INSERT INTO logged_track_day (id, trackId, dayIndex, rawGpxContent) VALUES " +
                "(1, 'track-1', 0, '${TRACK_1_GPX.escapeSql()}')",
        )
        migrated.query("SELECT trackId, dayIndex FROM logged_track_day").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("track-1", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }

        migrated.close()
    }

    @Test
    fun migrateAllTheWayFrom1ToCurrent_preservesSavedTrackData() {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                "INSERT INTO saved_track (id, trackName, gpxContent, bivouacTrackPointIndices) " +
                    "VALUES (1, 'Trace en cours', '${TRACK_1_GPX.escapeSql()}', '[0,2]')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            testDbName,
            11,
            true,
            BivouacDatabase.MIGRATION_1_2,
            BivouacDatabase.MIGRATION_2_5,
            BivouacDatabase.MIGRATION_5_6,
            BivouacDatabase.MIGRATION_6_7,
            BivouacDatabase.migration7To8(targetContext),
            BivouacDatabase.MIGRATION_8_9,
            BivouacDatabase.migration9To10(targetContext),
            BivouacDatabase.MIGRATION_10_11,
        )

        // RIC-97 : la ligne saved_track née en v1 avec gpxContent en colonne doit ressortir avec
        // gpxFilePath pointant vers un fichier au contenu intact.
        migrated.query("SELECT id, trackName, gpxFilePath, bivouacTrackPointIndices FROM saved_track").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals("Trace en cours", cursor.getString(1))
            val file = File(targetContext.filesDir, cursor.getString(2))
            assertTrue("Fichier saved_track manquant : ${file.path}", file.exists())
            assertEquals(TRACK_1_GPX, file.readText(Charsets.UTF_8))
            assertEquals("[0,2]", cursor.getString(3))
        }
        migrated.query("SELECT * FROM saved_track LIMIT 0").use { cursor ->
            assertEquals(-1, cursor.getColumnIndex("gpxContent"))
        }

        // Tables added by every intermediate jump must all exist and be usable at the final version.
        migrated.query("SELECT COUNT(*) FROM banked_track").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.query("SELECT * FROM banked_track LIMIT 0").use { cursor ->
            assertEquals(-1, cursor.getColumnIndex("gpxContent"))
            assertEquals(-1, cursor.getColumnIndex("pointCount"))
        }
        migrated.query("SELECT COUNT(*) FROM logged_track").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.execSQL(
            "INSERT INTO logged_track (id, name, startedAt, contentHash, " +
                "distanceMeters, elevationGainMeters, elevationLossMeters, pointCount, " +
                "estimatedDurationMinutes) VALUES " +
                "('track-1', 'Randonnee Belledonne', 1780300800000, " +
                "'hash-track-1', 8200.0, 650.0, 300.0, 3, 240)",
        )
        migrated.execSQL(
            "INSERT INTO logged_track_tag (trackId, tag) VALUES ('track-1', 'solo')",
        )
        migrated.query("SELECT COUNT(*) FROM logged_track_tag").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT note FROM logged_track WHERE id = 'track-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
        }

        migrated.close()
    }

    @Test
    fun migrate5To6_preservesExistingDataAndAddsTagsTableAndNoteColumn() {
        helper.createDatabase(testDbName, 5).apply {
            execSQL(
                "INSERT INTO logged_track (id, name, sourceFileName, startedAt, contentHash, " +
                    "distanceMeters, elevationGainMeters, elevationLossMeters, pointCount, " +
                    "estimatedDurationMinutes) VALUES " +
                    "('track-1', 'Randonnee Belledonne', 'belledonne.gpx', 1780300800000, " +
                    "'hash-track-1', 8200.0, 650.0, 300.0, 3, 240)",
            )
            execSQL(
                "INSERT INTO logged_track (id, name, sourceFileName, startedAt, contentHash, " +
                    "distanceMeters, elevationGainMeters, elevationLossMeters, pointCount, " +
                    "estimatedDurationMinutes) VALUES " +
                    "('track-2', 'Traversee Vanoise', NULL, 1781078400000, 'hash-track-2', " +
                    "21500.0, 1400.0, 900.0, 5, 660)",
            )
            execSQL(
                "INSERT INTO logged_track_day (id, trackId, dayIndex, rawGpxContent) VALUES " +
                    "(1, 'track-1', 0, '${TRACK_1_GPX.escapeSql()}')",
            )
            execSQL(
                "INSERT INTO logged_track_day (id, trackId, dayIndex, rawGpxContent) VALUES " +
                    "(2, 'track-2', 0, '${TRACK_2_DAY_1_GPX.escapeSql()}')",
            )
            execSQL(
                "INSERT INTO logged_track_day (id, trackId, dayIndex, rawGpxContent) VALUES " +
                    "(3, 'track-2', 1, '${TRACK_2_DAY_2_GPX.escapeSql()}')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            testDbName,
            6,
            true,
            BivouacDatabase.MIGRATION_5_6,
        )

        migrated.query("SELECT id, name, note FROM logged_track ORDER BY id").use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("track-1", cursor.getString(0))
            assertEquals("Randonnee Belledonne", cursor.getString(1))
            assertEquals("", cursor.getString(2))
            assertTrue(cursor.moveToNext())
            assertEquals("track-2", cursor.getString(0))
            assertEquals("Traversee Vanoise", cursor.getString(1))
            assertEquals("", cursor.getString(2))
        }

        migrated.query(
            "SELECT trackId, dayIndex, rawGpxContent FROM logged_track_day ORDER BY id",
        ).use { cursor ->
            assertEquals(3, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("track-1", cursor.getString(0))
            assertEquals(TRACK_1_GPX, cursor.getString(2))
            assertTrue(cursor.moveToNext())
            assertEquals("track-2", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(TRACK_2_DAY_1_GPX, cursor.getString(2))
            assertTrue(cursor.moveToNext())
            assertEquals("track-2", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(TRACK_2_DAY_2_GPX, cursor.getString(2))
        }

        migrated.query("SELECT COUNT(*) FROM logged_track_tag").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        migrated.execSQL(
            "INSERT INTO logged_track_tag (trackId, tag) VALUES ('track-1', 'solo')",
        )
        migrated.query("SELECT trackId, tag FROM logged_track_tag").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("track-1", cursor.getString(0))
            assertEquals("solo", cursor.getString(1))
        }

        migrated.close()
    }

    // RIC-95 : la migration recrée logged_track sans sourceFileName. Les lignes des tables
    // filles (jours, tags) référencent le parent par id : elles doivent survivre au
    // drop/rename du parent, données intactes.
    @Test
    fun migrate6To7_dropsSourceFileNameAndKeepsChildRows() {
        helper.createDatabase(testDbName, 6).apply {
            execSQL(
                "INSERT INTO logged_track (id, name, sourceFileName, startedAt, contentHash, " +
                    "distanceMeters, elevationGainMeters, elevationLossMeters, pointCount, " +
                    "estimatedDurationMinutes, note) VALUES " +
                    "('track-1', 'Randonnee Belledonne', 'belledonne.gpx', 1780300800000, " +
                    "'hash-track-1', 8200.0, 650.0, 300.0, 3, 240, 'Superbe meteo')",
            )
            execSQL(
                "INSERT INTO logged_track_day (id, trackId, dayIndex, rawGpxContent) VALUES " +
                    "(1, 'track-1', 0, '${TRACK_1_GPX.escapeSql()}')",
            )
            execSQL(
                "INSERT INTO logged_track_tag (trackId, tag) VALUES ('track-1', 'solo')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            testDbName,
            7,
            true,
            BivouacDatabase.MIGRATION_6_7,
        )

        migrated.query("SELECT id, name, contentHash, note FROM logged_track").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("track-1", cursor.getString(0))
            assertEquals("Randonnee Belledonne", cursor.getString(1))
            assertEquals("hash-track-1", cursor.getString(2))
            assertEquals("Superbe meteo", cursor.getString(3))
        }
        migrated.query("SELECT * FROM logged_track LIMIT 1").use { cursor ->
            assertEquals(-1, cursor.getColumnIndex("sourceFileName"))
        }
        migrated.query("SELECT trackId, rawGpxContent FROM logged_track_day").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("track-1", cursor.getString(0))
            assertEquals(TRACK_1_GPX, cursor.getString(1))
        }
        migrated.query("SELECT trackId, tag FROM logged_track_tag").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("track-1", cursor.getString(0))
            assertEquals("solo", cursor.getString(1))
        }

        migrated.close()
    }

    // RIC-62 : le GPX brut sort de SQLite vers un fichier par jour. Base peuplée y compris d'un
    // contenu au-delà de la CursorWindow (~2 Mo/ligne) : le test commence par prouver la prémisse
    // du chantier (le lire d'un coup via un Cursor, comme le ferait une migration naïve, jette
    // SQLiteBlobTooBigException) puis vérifie que la lecture par tranches de migration7To8 sort
    // tous les contenus intacts, y compris celui-là.
    @Test
    fun migrate7To8_movesRawGpxToFiles_evenPastCursorWindowLimit() {
        val oversizedGpx = buildOversizedGpx()
        assertTrue(oversizedGpx.length > 2 * 1024 * 1024)

        helper.createDatabase(testDbName, 7).apply {
            execSQL(
                "INSERT INTO logged_track (id, name, startedAt, contentHash, distanceMeters, " +
                    "elevationGainMeters, elevationLossMeters, pointCount, " +
                    "estimatedDurationMinutes, note) VALUES " +
                    "('track-1', 'Randonnee Belledonne', 1780300800000, 'hash-track-1', " +
                    "8200.0, 650.0, 300.0, 3, 240, '')",
            )
            execSQL(
                "INSERT INTO logged_track (id, name, startedAt, contentHash, distanceMeters, " +
                    "elevationGainMeters, elevationLossMeters, pointCount, " +
                    "estimatedDurationMinutes, note) VALUES " +
                    "('track-2', 'Traversee Vanoise', 1781078400000, 'hash-track-2', " +
                    "21500.0, 1400.0, 900.0, 5, 660, '')",
            )
            execSQL(
                "INSERT INTO logged_track (id, name, startedAt, contentHash, distanceMeters, " +
                    "elevationGainMeters, elevationLossMeters, pointCount, " +
                    "estimatedDurationMinutes, note) VALUES " +
                    "('track-big', 'Trace geante', 1782000000000, 'hash-track-big', " +
                    "42000.0, 2800.0, 2800.0, 30000, 900, '')",
            )
            execSQL(
                "INSERT INTO logged_track_day (id, trackId, dayIndex, rawGpxContent) VALUES " +
                    "(1, 'track-1', 0, '${TRACK_1_GPX.escapeSql()}')",
            )
            execSQL(
                "INSERT INTO logged_track_day (id, trackId, dayIndex, rawGpxContent) VALUES " +
                    "(2, 'track-2', 0, '${TRACK_2_DAY_1_GPX.escapeSql()}')",
            )
            execSQL(
                "INSERT INTO logged_track_day (id, trackId, dayIndex, rawGpxContent) VALUES " +
                    "(3, 'track-2', 1, '${TRACK_2_DAY_2_GPX.escapeSql()}')",
            )
            execSQL(
                "INSERT INTO logged_track_day (id, trackId, dayIndex, rawGpxContent) VALUES " +
                    "(4, 'track-big', 0, ?)",
                arrayOf(oversizedGpx),
            )

            // Prémisse RIC-62, vérifiée sur place : une migration qui relirait la colonne entière
            // via un Cursor ordinaire hériterait telle quelle de la limite CursorWindow.
            var naiveReadFailed = false
            try {
                query("SELECT rawGpxContent FROM logged_track_day WHERE id = 4").use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)
                }
            } catch (expected: SQLiteBlobTooBigException) {
                naiveReadFailed = true
            }
            assertTrue("La lecture naïve aurait dû dépasser la CursorWindow", naiveReadFailed)

            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            testDbName,
            8,
            true,
            BivouacDatabase.migration7To8(targetContext),
        )

        val expectedContentByRowId = mapOf(
            1L to TRACK_1_GPX,
            2L to TRACK_2_DAY_1_GPX,
            3L to TRACK_2_DAY_2_GPX,
            4L to oversizedGpx,
        )
        migrated.query(
            "SELECT id, trackId, dayIndex, rawGpxFilePath FROM logged_track_day ORDER BY id",
        ).use { cursor ->
            assertEquals(4, cursor.count)
            while (cursor.moveToNext()) {
                val rowId = cursor.getLong(0)
                val path = cursor.getString(3)
                val file = File(targetContext.filesDir, path)
                assertTrue("Fichier manquant pour la ligne $rowId : $path", file.exists())
                assertEquals(
                    "Contenu altéré pour la ligne $rowId",
                    expectedContentByRowId.getValue(rowId),
                    file.readText(Charsets.UTF_8),
                )
            }
        }
        migrated.query("SELECT * FROM logged_track_day LIMIT 1").use { cursor ->
            assertEquals(-1, cursor.getColumnIndex("rawGpxContent"))
        }

        migrated.close()
    }

    // Les trois colonnes dénormalisées arrivent vides et nullables, le remplissage se faisant
    // après coup hors migration (LoggedTrackBackfill). Ce que ce test verrouille, c'est
    // justement qu'elle ne touche à rien d'autre : ni chemin de fichier, ni ligne, ni contenu.
    // C'est la propriété qui rend cette migration incapable de perdre une archive.
    @Test
    fun migrate8To9_addsEmptyDenormalizedColumnsWithoutTouchingExistingRows() {
        helper.createDatabase(testDbName, 8).apply {
            execSQL(
                "INSERT INTO logged_track (id, name, startedAt, contentHash, distanceMeters, " +
                    "elevationGainMeters, elevationLossMeters, pointCount, " +
                    "estimatedDurationMinutes, note) VALUES " +
                    "('track-2', 'Traversee Vanoise', 1781078400000, 'hash-track-2', " +
                    "21500.0, 1400.0, 900.0, 5, 660, 'Deux jours')",
            )
            execSQL(
                "INSERT INTO logged_track_day (id, trackId, dayIndex, rawGpxFilePath) VALUES " +
                    "(1, 'track-2', 0, 'gpx/track-2-day0.gpx')",
            )
            execSQL(
                "INSERT INTO logged_track_day (id, trackId, dayIndex, rawGpxFilePath) VALUES " +
                    "(2, 'track-2', 1, 'gpx/track-2-day1.gpx')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            testDbName,
            9,
            true,
            BivouacDatabase.MIGRATION_8_9,
        )

        migrated.query(
            "SELECT id, trackId, dayIndex, rawGpxFilePath, contentHash, startedAtMillis, " +
                "elapsedSeconds FROM logged_track_day ORDER BY id",
        ).use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("track-2", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals("gpx/track-2-day0.gpx", cursor.getString(3))
            assertTrue("contentHash doit arriver vide", cursor.isNull(4))
            assertTrue("startedAtMillis doit arriver vide", cursor.isNull(5))
            assertTrue("elapsedSeconds doit arriver vide", cursor.isNull(6))
            assertTrue(cursor.moveToNext())
            assertEquals(1, cursor.getInt(2))
            assertEquals("gpx/track-2-day1.gpx", cursor.getString(3))
            assertTrue(cursor.isNull(4))
        }

        migrated.query("SELECT name, contentHash, note FROM logged_track").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("Traversee Vanoise", cursor.getString(0))
            assertEquals("hash-track-2", cursor.getString(1))
            assertEquals("Deux jours", cursor.getString(2))
        }

        migrated.close()
    }

    // RIC-97 : même démonstration que migrate7To8 ci-dessus, mais pour banked_track/saved_track —
    // preuve que la lecture naïve d'une ligne dépassant la CursorWindow échoue, puis que la lecture
    // par tranches de migration9To10 en ressort le contenu intact malgré tout, pointCount disparaît
    // de banked_track, et le singleton saved_track suit le même chemin sans traitement particulier.
    @Test
    fun migrate9To10_movesGpxContentToFiles_evenPastCursorWindowLimit_andDropsBankedPointCount() {
        val oversizedGpx = buildOversizedBankedGpx()
        assertTrue(oversizedGpx.length > 2 * 1024 * 1024)

        helper.createDatabase(testDbName, 9).apply {
            execSQL(
                "INSERT INTO banked_track (id, name, gpxContent, bivouacTrackPointIndices, " +
                    "distanceMeters, elevationGainMeters, elevationLossMeters, pointCount, " +
                    "estimatedDurationMinutes, savedAt) VALUES ('bank-1', 'Belledonne', " +
                    "'${TRACK_1_GPX.escapeSql()}', '[]', 8200.0, 650.0, 300.0, 3, 240, 1780300800000)",
            )
            execSQL(
                "INSERT INTO banked_track (id, name, gpxContent, bivouacTrackPointIndices, " +
                    "distanceMeters, elevationGainMeters, elevationLossMeters, pointCount, " +
                    "estimatedDurationMinutes, savedAt) VALUES ('bank-geant', 'Trace géante', " +
                    "?, '[0,5]', 42000.0, 2800.0, 2800.0, 30000, 900, 1782000000000)",
                arrayOf(oversizedGpx),
            )
            execSQL(
                "INSERT INTO saved_track (id, trackName, gpxContent, bivouacTrackPointIndices) " +
                    "VALUES (1, 'Plan en cours', '${TRACK_2_DAY_1_GPX.escapeSql()}', '[1]')",
            )

            // Prémisse RIC-97, vérifiée sur place : une migration qui relirait la colonne entière
            // via un Cursor ordinaire hériterait telle quelle de la limite CursorWindow.
            var naiveReadFailed = false
            try {
                query("SELECT gpxContent FROM banked_track WHERE id = 'bank-geant'").use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)
                }
            } catch (expected: SQLiteBlobTooBigException) {
                naiveReadFailed = true
            }
            assertTrue("La lecture naïve aurait dû dépasser la CursorWindow", naiveReadFailed)

            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            testDbName,
            10,
            true,
            BivouacDatabase.migration9To10(targetContext),
        )

        val expectedBankedContentById = mapOf(
            "bank-1" to TRACK_1_GPX,
            "bank-geant" to oversizedGpx,
        )
        migrated.query(
            "SELECT id, name, gpxFilePath, bivouacTrackPointIndices, distanceMeters, savedAt " +
                "FROM banked_track ORDER BY id",
        ).use { cursor ->
            assertEquals(2, cursor.count)
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val path = cursor.getString(2)
                val file = File(targetContext.filesDir, path)
                assertTrue("Fichier manquant pour $id : $path", file.exists())
                assertEquals(
                    "Contenu altéré pour $id",
                    expectedBankedContentById.getValue(id),
                    file.readText(Charsets.UTF_8),
                )
            }
        }
        migrated.query("SELECT id, name FROM banked_track WHERE id = 'bank-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Belledonne", cursor.getString(1))
        }
        migrated.query("SELECT * FROM banked_track LIMIT 0").use { cursor ->
            assertEquals(-1, cursor.getColumnIndex("gpxContent"))
            assertEquals(-1, cursor.getColumnIndex("pointCount"))
        }

        migrated.query("SELECT id, trackName, gpxFilePath, bivouacTrackPointIndices FROM saved_track").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("Plan en cours", cursor.getString(1))
            val file = File(targetContext.filesDir, cursor.getString(2))
            assertTrue("Fichier saved_track manquant : ${file.path}", file.exists())
            assertEquals(TRACK_2_DAY_1_GPX, file.readText(Charsets.UTF_8))
            assertEquals("[1]", cursor.getString(3))
        }
        migrated.query("SELECT * FROM saved_track LIMIT 0").use { cursor ->
            assertEquals(-1, cursor.getColumnIndex("gpxContent"))
        }

        migrated.close()
    }

    // Base v9 sans aucune ligne dans banked_track/saved_track : le cas le plus courant en
    // production (mesure pilotage : 3 lignes banked_track, 1 ligne saved_track sur l'ensemble du
    // parc). La migration ne doit ni planter ni laisser une table dans un état incohérent.
    @Test
    fun migrate9To10_withNoRows_leavesEmptyTablesUsable() {
        helper.createDatabase(testDbName, 9).close()

        val migrated = helper.runMigrationsAndValidate(
            testDbName,
            10,
            true,
            BivouacDatabase.migration9To10(targetContext),
        )

        migrated.query("SELECT COUNT(*) FROM banked_track").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM saved_track").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.execSQL(
            "INSERT INTO banked_track (id, name, gpxFilePath, bivouacTrackPointIndices, " +
                "distanceMeters, elevationGainMeters, elevationLossMeters, " +
                "estimatedDurationMinutes, savedAt) VALUES ('bank-1', 'Belledonne', " +
                "'gpx-planif/banked-bank-1.gpx', '[]', 8200.0, 650.0, 300.0, 240, 1780300800000)",
        )
        migrated.query("SELECT id FROM banked_track").use { cursor ->
            assertEquals(1, cursor.count)
        }

        migrated.close()
    }

    // RIC-109 : sept colonnes de plus sur logged_track_day pour la calibration vitesse/pénalité D+
    // par segments (flatCount et consorts, voir DaySegmentAggregate). Même propriété que
    // migrate8To9_addsEmptyDenormalizedColumnsWithoutTouchingExistingRows ci-dessus : elles
    // arrivent vides et nullables, remplies après coup par LoggedTrackBackfill (étendu pour
    // l'occasion), et cette migration ne doit toucher à rien d'autre.
    @Test
    fun migrate10To11_addsEmptySegmentColumnsWithoutTouchingExistingRows() {
        helper.createDatabase(testDbName, 10).apply {
            execSQL(
                "INSERT INTO logged_track (id, name, startedAt, contentHash, distanceMeters, " +
                    "elevationGainMeters, elevationLossMeters, pointCount, " +
                    "estimatedDurationMinutes, note) VALUES " +
                    "('track-2', 'Traversee Vanoise', 1781078400000, 'hash-track-2', " +
                    "21500.0, 1400.0, 900.0, 5, 660, 'Deux jours')",
            )
            execSQL(
                "INSERT INTO logged_track_day (id, trackId, dayIndex, rawGpxFilePath, " +
                    "contentHash, startedAtMillis, elapsedSeconds) VALUES " +
                    "(1, 'track-2', 0, 'gpx/track-2-day0.gpx', 'day0-hash', 1781078400000, 3600)",
            )
            execSQL(
                "INSERT INTO logged_track_day (id, trackId, dayIndex, rawGpxFilePath) VALUES " +
                    "(2, 'track-2', 1, 'gpx/track-2-day1.gpx')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            testDbName,
            11,
            true,
            BivouacDatabase.MIGRATION_10_11,
        )

        migrated.query(
            "SELECT id, contentHash, startedAtMillis, elapsedSeconds, flatCount, " +
                "flatDistanceMeters, flatHours, steepCount, steepDistanceMeters, steepGainMeters, " +
                "steepHours FROM logged_track_day ORDER BY id",
        ).use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            // Colonnes RIC-98/99 déjà rattrapées avant cette migration : intouchées.
            assertEquals("day0-hash", cursor.getString(1))
            assertEquals(1781078400000L, cursor.getLong(2))
            assertEquals(3600L, cursor.getLong(3))
            // Les sept nouvelles colonnes arrivent vides, même sur une ligne déjà rattrapée par
            // RIC-98/99 : c'est justement ce que LoggedTrackBackfill doit combler après coup.
            for (columnIndex in 4..10) {
                assertTrue("colonne $columnIndex doit arriver vide", cursor.isNull(columnIndex))
            }
            assertTrue(cursor.moveToNext())
            assertEquals(2L, cursor.getLong(0))
            assertTrue("contentHash doit rester vide (jour jamais rattrapé)", cursor.isNull(1))
            assertTrue(cursor.isNull(4))
        }

        migrated.query("SELECT name FROM logged_track WHERE id = 'track-2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Traversee Vanoise", cursor.getString(0))
        }

        migrated.close()
    }

    // RIC-43 : table neuve pour les photos d'une sortie, même propriété que
    // migrate5To6_preservesExistingDataAndAddsTagsTableAndNoteColumn ci-dessus (existant intouché,
    // table fille utilisable dès la migration faite).
    //
    // Un seul test pour toute la table, parce qu'il n'y a qu'une seule migration : contentHash et
    // les trois colonnes de métadonnées d'origine, ajoutés au fil du développement de RIC-43, sont
    // consolidés dans ce même 14 -> 15 — aucune version intermédiaire n'a été publiée, donc aucune
    // base réelle n'a jamais vu de table logged_track_photo sans elles.
    @Test
    fun migrate14To15_addsEmptyPhotoTableWithoutTouchingExistingRows() {
        helper.createDatabase(testDbName, 14).apply {
            execSQL(
                "INSERT INTO logged_track (id, name, startedAt, contentHash, distanceMeters, " +
                    "elevationGainMeters, elevationLossMeters, pointCount, " +
                    "estimatedDurationMinutes, note) VALUES " +
                    "('track-1', 'Randonnee Belledonne', 1780300800000, 'hash-track-1', " +
                    "8200.0, 650.0, 300.0, 3, 240, '')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            testDbName,
            15,
            true,
            BivouacDatabase.MIGRATION_14_15,
        )

        migrated.query("SELECT name FROM logged_track WHERE id = 'track-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Randonnee Belledonne", cursor.getString(0))
        }

        migrated.query("SELECT COUNT(*) FROM logged_track_photo").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        // Insert complet : la table doit accepter d'emblée toutes les colonnes que l'entité
        // déclare, déduplication et métadonnées d'origine comprises.
        migrated.execSQL(
            "INSERT INTO logged_track_photo (trackId, filePath, addedAtMillis, takenAtMillis, " +
                "latitude, longitude, positionPointIndex, positionApproximate, contentHash, " +
                "sourceDisplayName, sourceRelativePath, sourceDateTakenMillis) VALUES " +
                "('track-1', 'photos/track-1-abc.jpg', 1780300900000, 1780300850000, " +
                "45.1885, 5.7245, 1, 0, 'abc123', 'IMG_0001.jpg', 'DCIM/Camera/', 1780300850000)",
        )
        migrated.query(
            "SELECT trackId, filePath, positionPointIndex, positionApproximate, contentHash, " +
                "sourceDisplayName, sourceRelativePath, sourceDateTakenMillis FROM logged_track_photo",
        ).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("track-1", cursor.getString(0))
            assertEquals("photos/track-1-abc.jpg", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals("abc123", cursor.getString(4))
            assertEquals("IMG_0001.jpg", cursor.getString(5))
            assertEquals("DCIM/Camera/", cursor.getString(6))
            assertEquals(1780300850000L, cursor.getLong(7))
        }

        // Les métadonnées d'origine sont un bonus, jamais une condition : une photo ajoutée depuis
        // une Uri caviardée par le Photo Picker n'en a aucune et doit entrer quand même.
        migrated.execSQL(
            "INSERT INTO logged_track_photo (trackId, filePath, addedAtMillis, " +
                "positionApproximate, contentHash) " +
                "VALUES ('track-1', 'photos/track-1-def.jpg', 1780301000000, 0, 'def456')",
        )
        migrated.query(
            "SELECT sourceDisplayName, sourceRelativePath, sourceDateTakenMillis " +
                "FROM logged_track_photo WHERE contentHash = 'def456'",
        ).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }

        // La cascade FK est bien en place dès la création de la table : supprimer la sortie doit
        // emporter ses photos (voir LoggedTrackRepository.delete, qui relève les chemins avant).
        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL("DELETE FROM logged_track WHERE id = 'track-1'")
        migrated.query("SELECT COUNT(*) FROM logged_track_photo").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        migrated.close()
    }
}

private fun String.escapeSql(): String = replace("'", "''")

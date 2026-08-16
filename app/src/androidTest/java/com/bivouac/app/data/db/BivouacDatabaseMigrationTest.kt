package com.bivouac.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

@RunWith(AndroidJUnit4::class)
class BivouacDatabaseMigrationTest {

    private val testDbName = "bivouac-migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BivouacDatabase::class.java,
    )

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
            6,
            true,
            BivouacDatabase.MIGRATION_1_2,
            BivouacDatabase.MIGRATION_2_5,
            BivouacDatabase.MIGRATION_5_6,
        )

        migrated.query("SELECT id, trackName, gpxContent, bivouacTrackPointIndices FROM saved_track").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals("Trace en cours", cursor.getString(1))
            assertEquals(TRACK_1_GPX, cursor.getString(2))
            assertEquals("[0,2]", cursor.getString(3))
        }

        // Tables added by every intermediate jump must all exist and be usable at the final version.
        migrated.query("SELECT COUNT(*) FROM banked_track").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM logged_track").use { cursor ->
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
}

private fun String.escapeSql(): String = replace("'", "''")

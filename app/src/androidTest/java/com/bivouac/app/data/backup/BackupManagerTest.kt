package com.bivouac.app.data.backup

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackDayEntity
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.prefs.MapLayerPreferences
import com.bivouac.app.data.prefs.SettingsPreferences
import com.bivouac.app.data.prefs.SpeedCalibrationMode
import com.bivouac.app.ui.map.MapLayer
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Exercises the actual mechanism behind BIV-66's "sauvegarder violemment, restaurer" safety net —
// with synthetic data only (no real hike data available in this repository/worktree), on the
// instrumentation target context. Never run against a physical device with real Journal data; see
// docs/DEVICE_TESTS.md.
@RunWith(AndroidJUnit4::class)
class BackupManagerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var backupFile: File

    @Before
    fun setUp() {
        backupFile = File(context.cacheDir, "test-backup-${System.nanoTime()}.zip")
        BivouacDatabase.closeAndReset()
        context.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackGpxStore.dir(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        backupFile.delete()
        BivouacDatabase.closeAndReset()
        context.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackGpxStore.dir(context).deleteRecursively()
    }

    // RIC-62 : le contenu GPX vit dans un fichier, la ligne n'en porte que le chemin — l'insertion
    // de test reproduit le couple fichier + ligne tel que LoggedTrackRepository.commitImport l'écrit.
    private fun writeDay(trackId: String, dayIndex: Int, rawGpx: String): LoggedTrackDayEntity {
        val relativePath = LoggedTrackGpxStore.relativePath(trackId, dayIndex)
        LoggedTrackGpxStore.dir(context).mkdirs()
        LoggedTrackGpxStore.resolve(context, relativePath).writeText(rawGpx)
        return LoggedTrackDayEntity(trackId = trackId, dayIndex = dayIndex, rawGpxFilePath = relativePath)
    }

    @Test
    fun backupThenRestoreBringsBackJournalDataAndPreferences() = runBlocking {
        val dao = BivouacDatabase.getInstance(context).loggedTrackDao()
        dao.insert(
            LoggedTrackEntity(
                id = "t1",
                name = "Trace test backup",
                startedAt = 0L,
                contentHash = "hash1",
                distanceMeters = 5000.0,
                elevationGainMeters = 300.0,
                elevationLossMeters = 300.0,
                pointCount = 10,
                estimatedDurationMinutes = 90,
            ),
            listOf(writeDay("t1", 0, "<gpx><!-- contenu test backup --></gpx>")),
        )
        val mapPrefs = MapLayerPreferences(context)
        mapPrefs.setSelectedLayer(MapLayer.SATELLITE)
        val settingsPrefs = SettingsPreferences(context)
        settingsPrefs.setSpeedCalibrationMode(SpeedCalibrationMode.MANUAL)
        settingsPrefs.setManualCalibration(5.0, 120.0, 20.0)

        val backupResult = BackupManager.backup(context, Uri.fromFile(backupFile))
        assertTrue(backupResult.isSuccess)
        assertTrue(backupFile.length() > 0)

        // DataStore's delegate is a process-wide singleton per file — once opened (as it just was
        // above), it never re-reads from disk on its own, restore or no restore; only a fresh
        // process picks up a swapped file (see AppRestart's kdoc, and why the real UI flow forces
        // a restart after a successful restore). So preference correctness here is checked at the
        // file level — the layer this in-process test *can* actually observe — snapshotting the
        // exact bytes DataStore holds right after backup() (which itself stamps lastBackupAtMillis
        // before zipping, so this snapshot already includes it — the whole point being verified).
        val datastoreDir = File(context.filesDir, "datastore")
        val mapPrefsFile = File(datastoreDir, "map_layer_prefs.preferences_pb")
        val settingsPrefsFile = File(datastoreDir, "bivouac_settings.preferences_pb")
        val mapPrefsBytesAtBackup = mapPrefsFile.readBytes()
        val settingsPrefsBytesAtBackup = settingsPrefsFile.readBytes()
        val lastBackupAtBackupTime = settingsPrefs.lastBackupAtMillis.first()
        assertTrue(lastBackupAtBackupTime != null)

        // Wipe everything, as if a fresh install (or an aggressive test session) had just erased
        // the app's data — the whole scenario this feature exists for.
        BivouacDatabase.closeAndReset()
        context.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackGpxStore.dir(context).deleteRecursively()
        mapPrefs.setSelectedLayer(MapLayer.HIKING)
        settingsPrefs.setManualCalibration(3.5, 100.0, 0.0)
        assertEquals(0, BivouacDatabase.getInstance(context).loggedTrackDao().list().size)

        val restoreResult = BackupManager.restore(context, Uri.fromFile(backupFile))
        assertEquals(RestoreResult.Success, restoreResult)

        val restoredTracks = BivouacDatabase.getInstance(context).loggedTrackDao().list()
        assertEquals(1, restoredTracks.size)
        assertEquals("Trace test backup", restoredTracks.first().name)
        // RIC-62 : l'archive doit ramener le fichier GPX avec la base — une ligne restaurée qui
        // pointerait vers un fichier absent serait une trace vide.
        val restoredDays = BivouacDatabase.getInstance(context).loggedTrackDao().getDays("t1")
        assertEquals(1, restoredDays.size)
        val restoredGpxFile = LoggedTrackGpxStore.resolve(context, restoredDays.first().rawGpxFilePath)
        assertTrue(restoredGpxFile.exists())
        assertEquals("<gpx><!-- contenu test backup --></gpx>", restoredGpxFile.readText())
        assertTrue(mapPrefsBytesAtBackup.contentEquals(mapPrefsFile.readBytes()))
        assertTrue(settingsPrefsBytesAtBackup.contentEquals(settingsPrefsFile.readBytes()))
    }

    @Test
    fun restoreBlocksWhenBackupIsNewerThanAppSchema() = runBlocking {
        val dao = BivouacDatabase.getInstance(context).loggedTrackDao()
        dao.insert(
            LoggedTrackEntity(
                id = "t1",
                name = "Trace pré-restauration",
                startedAt = 0L,
                contentHash = "hash1",
                distanceMeters = 1000.0,
                elevationGainMeters = 0.0,
                elevationLossMeters = 0.0,
                pointCount = 2,
                estimatedDurationMinutes = 10,
            ),
            emptyList(),
        )
        BivouacDatabase.closeAndReset()

        // Simulates a backup produced by a future app version: a copy of the real Room file, with
        // PRAGMA user_version bumped past what BivouacDatabase.SCHEMA_VERSION currently knows.
        // Bumped on a *copy* — mutating the live db file in place would leave the app's own
        // database at a version its compiled schema can no longer open, unrelated to what this
        // test is actually trying to exercise.
        val dbFile = context.getDatabasePath(BivouacDatabase.DATABASE_NAME)
        val futureDbFile = File(context.cacheDir, "future-${BivouacDatabase.DATABASE_NAME}")
        dbFile.copyTo(futureDbFile, overwrite = true)
        val futureDb = SQLiteDatabase.openDatabase(futureDbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        futureDb.version = BivouacDatabase.SCHEMA_VERSION + 1
        futureDb.close()

        ZipOutputStream(backupFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("db/${BivouacDatabase.DATABASE_NAME}"))
            futureDbFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        futureDbFile.delete()

        val result = BackupManager.restore(context, Uri.fromFile(backupFile))
        assertTrue(result is RestoreResult.VersionTooNew)
        val versionTooNew = result as RestoreResult.VersionTooNew
        assertEquals(BivouacDatabase.SCHEMA_VERSION + 1, versionTooNew.backupVersion)
        assertEquals(BivouacDatabase.SCHEMA_VERSION, versionTooNew.appVersion)

        // Nothing should have been touched by a blocked restore — the pre-existing data survives.
        val tracksAfterBlockedRestore = BivouacDatabase.getInstance(context).loggedTrackDao().list()
        assertEquals(1, tracksAfterBlockedRestore.size)
    }

    // RIC-95 : une archive dont le bivouac.db n'est pas une base SQLite saine doit être refusée
    // par le PRAGMA integrity_check AVANT tout remplacement — les données en place survivent.
    @Test
    fun restoreRejectsCorruptedArchiveAndKeepsCurrentData() = runBlocking {
        val dao = BivouacDatabase.getInstance(context).loggedTrackDao()
        dao.insert(
            LoggedTrackEntity(
                id = "t1",
                name = "Trace à préserver",
                startedAt = 0L,
                contentHash = "hash1",
                distanceMeters = 1000.0,
                elevationGainMeters = 0.0,
                elevationLossMeters = 0.0,
                pointCount = 2,
                estimatedDurationMinutes = 10,
            ),
            listOf(writeDay("t1", 0, "<gpx><!-- contenu à préserver --></gpx>")),
        )
        BivouacDatabase.closeAndReset()

        ZipOutputStream(backupFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("db/${BivouacDatabase.DATABASE_NAME}"))
            zip.write("ceci n'est pas une base SQLite".toByteArray())
            zip.closeEntry()
        }

        val result = BackupManager.restore(context, Uri.fromFile(backupFile))
        assertTrue(result is RestoreResult.Error)

        val tracksAfterRejectedRestore = BivouacDatabase.getInstance(context).loggedTrackDao().list()
        assertEquals(1, tracksAfterRejectedRestore.size)
        assertEquals("Trace à préserver", tracksAfterRejectedRestore.first().name)
        // RIC-62 : le fichier GPX en place doit lui aussi survivre à une restauration refusée.
        val gpxFile = LoggedTrackGpxStore.resolve(context, LoggedTrackGpxStore.relativePath("t1", 0))
        assertTrue(gpxFile.exists())
        assertEquals("<gpx><!-- contenu à préserver --></gpx>", gpxFile.readText())
    }
}

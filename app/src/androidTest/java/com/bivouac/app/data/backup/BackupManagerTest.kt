package com.bivouac.app.data.backup

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackDayEntity
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import com.bivouac.app.data.prefs.MapLayerPreferences
import com.bivouac.app.data.prefs.SettingsPreferences
import com.bivouac.app.data.prefs.SpeedCalibrationMode
import com.bivouac.app.ui.map.MapLayer
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Exercises the actual mechanism behind BIV-66's "sauvegarder violemment, restaurer" safety net:
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
        LoggedTrackPhotoStore.dir(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        backupFile.delete()
        BivouacDatabase.closeAndReset()
        context.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackGpxStore.dir(context).deleteRecursively()
        LoggedTrackPhotoStore.dir(context).deleteRecursively()
    }

    // RIC-62 : le contenu GPX vit dans un fichier, la ligne n'en porte que le chemin : l'insertion
    // de test reproduit le couple fichier + ligne tel que LoggedTrackRepository.commitImport l'écrit.
    private fun writeDay(trackId: String, dayIndex: Int, rawGpx: String): LoggedTrackDayEntity {
        val relativePath = LoggedTrackGpxStore.relativePath(trackId, dayIndex)
        LoggedTrackGpxStore.dir(context).mkdirs()
        LoggedTrackGpxStore.resolve(context, relativePath).writeText(rawGpx)
        return LoggedTrackDayEntity(trackId = trackId, dayIndex = dayIndex, rawGpxFilePath = relativePath)
    }

    // RIC-43 : même couple fichier + ligne que LoggedTrackRepository.addPhoto écrit, avec un
    // contenu binaire quelconque : ce qui est sauvegardé et restauré est une suite d'octets, la
    // sauvegarde ne décode aucune image.
    private suspend fun writePhoto(trackId: String, bytes: ByteArray): LoggedTrackPhotoEntity {
        val relativePath = LoggedTrackPhotoStore.relativePath(trackId, "jpg")
        LoggedTrackPhotoStore.dir(context).mkdirs()
        LoggedTrackPhotoStore.resolve(context, relativePath).writeBytes(bytes)
        val entity = LoggedTrackPhotoEntity(
            trackId = trackId,
            filePath = relativePath,
            addedAtMillis = 1L,
            contentHash = "hash-${bytes.size}",
        )
        val dao = BivouacDatabase.getInstance(context).loggedTrackDao()
        return entity.copy(id = dao.insertPhoto(entity))
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

        // DataStore's delegate is a process-wide singleton per file: once opened (as it just was
        // above), it never re-reads from disk on its own, restore or no restore; only a fresh
        // process picks up a swapped file (see AppRestart's kdoc, and why the real UI flow forces
        // a restart after a successful restore). So preference correctness here is checked at the
        // file level (the layer this in-process test *can* actually observe), snapshotting the
        // exact bytes DataStore holds right after backup() (which itself stamps lastBackupAtMillis
        // before zipping, so this snapshot already includes it: the whole point being verified).
        val datastoreDir = File(context.filesDir, "datastore")
        val mapPrefsFile = File(datastoreDir, "map_layer_prefs.preferences_pb")
        val settingsPrefsFile = File(datastoreDir, "bivouac_settings.preferences_pb")
        val mapPrefsBytesAtBackup = mapPrefsFile.readBytes()
        val settingsPrefsBytesAtBackup = settingsPrefsFile.readBytes()
        val lastBackupAtBackupTime = settingsPrefs.lastBackupAtMillis.first()
        assertTrue(lastBackupAtBackupTime != null)

        // Wipe everything, as if a fresh install (or an aggressive test session) had just erased
        // the app's data: the whole scenario this feature exists for.
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
        // RIC-62 : l'archive doit ramener le fichier GPX avec la base : une ligne restaurée qui
        // pointerait vers un fichier absent serait une trace vide.
        val restoredDays = BivouacDatabase.getInstance(context).loggedTrackDao().getDays("t1")
        assertEquals(1, restoredDays.size)
        val restoredGpxFile = LoggedTrackGpxStore.resolve(context, restoredDays.first().rawGpxFilePath)
        assertTrue(restoredGpxFile.exists())
        assertEquals("<gpx><!-- contenu test backup --></gpx>", restoredGpxFile.readText())
        assertTrue(mapPrefsBytesAtBackup.contentEquals(mapPrefsFile.readBytes()))
        assertTrue(settingsPrefsBytesAtBackup.contentEquals(settingsPrefsFile.readBytes()))
    }

    /**
     * RIC-43 : une photo est la seule donnée de l'app qui ne se reconstitue depuis rien. Une
     * archive qui ne la contient pas ramène une ligne logged_track_photo dont le fichier n'existe
     * nulle part, c'est-à-dire une photo perdue en silence.
     */
    @Test
    fun backupThenRestoreBringsBackJournalPhotos() = runBlocking {
        val dao = BivouacDatabase.getInstance(context).loggedTrackDao()
        dao.insert(
            LoggedTrackEntity(
                id = "t1",
                name = "Trace avec photos",
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
        val photoBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val photo = writePhoto("t1", photoBytes)

        assertTrue(BackupManager.backup(context, Uri.fromFile(backupFile)).isSuccess)

        BivouacDatabase.closeAndReset()
        context.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackGpxStore.dir(context).deleteRecursively()
        LoggedTrackPhotoStore.dir(context).deleteRecursively()

        assertEquals(RestoreResult.Success, BackupManager.restore(context, Uri.fromFile(backupFile)))

        val restoredPhotos = BivouacDatabase.getInstance(context).loggedTrackDao().getPhotos("t1")
        assertEquals(1, restoredPhotos.size)
        assertEquals(photo.filePath, restoredPhotos.first().filePath)
        val restoredFile = LoggedTrackPhotoStore.resolve(context, restoredPhotos.first().filePath)
        assertTrue("le fichier photo doit revenir avec la base", restoredFile.exists())
        assertTrue(photoBytes.contentEquals(restoredFile.readBytes()))
    }

    /**
     * RIC-43, les deux moitiés de la cohérence après restauration, sur le même cycle :
     *
     * - un fichier de photos/ que plus aucune ligne ne référence est supprimé par le balayage
     *   post-restauration : sinon il resterait sur le stockage pour toujours, sans que rien ne
     *   puisse plus le nommer ;
     * - une ligne dont le fichier manque survit, elle. Ses métadonnées d'origine sont ce qui
     *   permettra de re-acquérir la photo depuis la galerie (RIC-151) : les supprimer perdrait la
     *   seule chose qui reste d'elle.
     */
    @Test
    fun restoreSweepsOrphanPhotoFilesButKeepsRowsWhoseFileIsMissing() = runBlocking {
        val dao = BivouacDatabase.getInstance(context).loggedTrackDao()
        dao.insert(
            LoggedTrackEntity(
                id = "t1",
                name = "Trace avec photos",
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
        // Sauvegardée avec sa ligne, mais son fichier est retiré de l'état courant juste avant la
        // sauvegarde : l'archive porte donc une ligne sans fichier.
        val photoWithoutFile = writePhoto("t1", byteArrayOf(0x0A, 0x0B))
        LoggedTrackPhotoStore.resolve(context, photoWithoutFile.filePath).delete()

        assertTrue(BackupManager.backup(context, Uri.fromFile(backupFile)).isSuccess)

        // Écrit après la sauvegarde, donc absent de l'archive : au retour, plus aucune ligne ne le
        // référencera.
        val orphanFile = LoggedTrackPhotoStore.resolve(
            context,
            LoggedTrackPhotoStore.relativePath("t1", "jpg"),
        )
        LoggedTrackPhotoStore.dir(context).mkdirs()
        orphanFile.writeBytes(byteArrayOf(0x0C, 0x0D))

        assertEquals(RestoreResult.Success, BackupManager.restore(context, Uri.fromFile(backupFile)))

        assertTrue("le fichier orphelin doit être balayé", !orphanFile.exists())
        val restoredPhotos = BivouacDatabase.getInstance(context).loggedTrackDao().getPhotos("t1")
        assertEquals(1, restoredPhotos.size)
        assertEquals(photoWithoutFile.filePath, restoredPhotos.first().filePath)
        assertTrue(
            "la ligne sans fichier doit survivre (RIC-151)",
            !LoggedTrackPhotoStore.resolve(context, restoredPhotos.first().filePath).exists(),
        )
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
        // Bumped on a *copy*: mutating the live db file in place would leave the app's own
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

        // Nothing should have been touched by a blocked restore: the pre-existing data survives.
        val tracksAfterBlockedRestore = BivouacDatabase.getInstance(context).loggedTrackDao().list()
        assertEquals(1, tracksAfterBlockedRestore.size)
    }

    // RIC-95 : une archive dont le bivouac.db n'est pas une base SQLite saine doit être refusée
    // par le PRAGMA integrity_check AVANT tout remplacement : les données en place survivent.
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

    /** Jeu d'essai commun aux cas RIC-156 : une trace, son GPX, deux photos. */
    private suspend fun seedTrackWithPhotos() {
        val dao = BivouacDatabase.getInstance(context).loggedTrackDao()
        dao.insert(
            LoggedTrackEntity(
                id = "t1",
                name = "Trace RIC-156",
                startedAt = 0L,
                contentHash = "hash1",
                distanceMeters = 5000.0,
                elevationGainMeters = 300.0,
                elevationLossMeters = 300.0,
                pointCount = 10,
                estimatedDurationMinutes = 90,
            ),
            listOf(writeDay("t1", 0, "<gpx><!-- contenu RIC-156 --></gpx>")),
        )
        writePhoto("t1", byteArrayOf(0x01, 0x02, 0x03))
        writePhoto("t1", byteArrayOf(0x04, 0x05, 0x06, 0x07))
    }

    /**
     * RIC-156 : la sauvegarde rend compte fichier par fichier, avec un dénominateur connu dès le
     * premier appel : c'est ce qui permet au dialogue bloquant d'afficher « x sur n » et non un
     * tourniquet muet pendant plusieurs dizaines de secondes de photos.
     */
    @Test
    fun backupReportsFileByFileProgress() = runBlocking {
        seedTrackWithPhotos()

        val reported = mutableListOf<Pair<Int, Int>>()
        assertTrue(BackupManager.backup(context, Uri.fromFile(backupFile)) { done, total -> reported += done to total }.isSuccess)

        assertTrue("la sauvegarde doit rendre compte", reported.isNotEmpty())
        val total = reported.first().second
        assertTrue("le total doit couvrir base, préférences, gpx et les 2 photos", total >= 4)
        assertTrue("le total ne doit jamais changer en cours de route", reported.all { it.second == total })
        assertEquals("le premier compte rendu part de zéro", 0, reported.first().first)
        assertEquals("le dernier compte rendu doit être complet", total, reported.last().first)
        assertEquals("le compteur ne doit jamais reculer ni sauter", (0..total).toList(), reported.map { it.first })
    }

    /**
     * RIC-156 : la restauration rend compte elle aussi, en deux temps : l'extraction, dénombrable
     * grâce au manifeste écrit par la sauvegarde, puis le remplacement, qui ne l'est pas.
     */
    @Test
    fun restoreReportsExtractionProgressThenReplacement() = runBlocking {
        seedTrackWithPhotos()
        assertTrue(BackupManager.backup(context, Uri.fromFile(backupFile)).isSuccess)
        BivouacDatabase.closeAndReset()

        val reported = mutableListOf<RestoreProgress>()
        val result = BackupManager.restore(context, Uri.fromFile(backupFile)) { reported += it }
        assertEquals(RestoreResult.Success, result)

        val extraction = reported.filter { it.phase == RestorePhase.EXTRACTION }
        assertTrue("l'extraction doit rendre compte", extraction.isNotEmpty())
        val total = extraction.mapNotNull { it.total }.distinct()
        assertEquals("le manifeste doit fournir un dénominateur unique", 1, total.size)
        assertEquals(
            "toutes les entrées de données doivent être comptées",
            total.single(),
            extraction.last().done,
        )
        assertTrue(
            "le remplacement doit être annoncé, et sans faux dénominateur",
            reported.any { it.phase == RestorePhase.REPLACEMENT && it.total == null },
        )
    }

    /**
     * RIC-156, reproduction de l'incident : une sauvegarde interrompue pile sur une frontière
     * d'entrée produit une archive que RIEN, dans le format zip, ne distingue d'une archive
     * complète : [ZipInputStream] ne lit que les en-têtes locaux, séquentiellement, et l'annuaire
     * central qu'il ignore est de toute façon le dernier écrit.
     *
     * Le piège est que bivouac.db est zippé en premier : l'archive amputée contient une base
     * parfaitement saine, qui passait le contrôle d'intégrité de RIC-95 et se restaurait sans le
     * moindre message, en emportant définitivement les photos et les GPX de l'appareil, puisque
     * ces répertoires sont remplacés en bloc. Ce test vérifie les deux moitiés : que la base de
     * l'archive tronquée est bien saine (donc que le contrôle existant ne pouvait pas la refuser),
     * et que le manifeste, lui, la refuse.
     */
    @Test
    fun restoreRefusesAnArchiveTruncatedOnAnEntryBoundary() = runBlocking {
        seedTrackWithPhotos()
        assertTrue(BackupManager.backup(context, Uri.fromFile(backupFile)).isSuccess)

        val truncated = File(context.cacheDir, "truncated-${System.nanoTime()}.zip")
        val keptDataEntries = copyArchivePrefix(backupFile, truncated, dataEntriesToKeep = 1)
        assertEquals("l'archive tronquée ne doit garder que la base", 1, keptDataEntries)

        // Première moitié : cette archive est structurellement irréprochable et sa base est saine.
        val extractedDb = File(context.cacheDir, "truncated-db-${System.nanoTime()}")
        ZipInputStream(truncated.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(BivouacDatabase.DATABASE_NAME)) {
                    extractedDb.outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertTrue("la base de l'archive tronquée doit exister", extractedDb.exists())
        val integrity = SQLiteDatabase.openDatabase(extractedDb.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            }
        }
        assertTrue("c'est tout le problème : cette base tronquée est saine, rien ne la trahissait", integrity)
        extractedDb.delete()

        // Seconde moitié : le manifeste refuse l'archive, et les données en place sont intactes.
        BivouacDatabase.closeAndReset()
        val result = BackupManager.restore(context, Uri.fromFile(truncated))
        truncated.delete()
        assertTrue("une archive incomplète doit être refusée", result is RestoreResult.Error)
        assertTrue(
            "le message doit dire pourquoi : ${(result as RestoreResult.Error).message}",
            result.message.contains("incomplète"),
        )
        val photosAfterRefusal = BivouacDatabase.getInstance(context).loggedTrackDao().getPhotos("t1")
        assertEquals("les photos en place doivent survivre au refus", 2, photosAfterRefusal.size)
        photosAfterRefusal.forEach {
            assertTrue(
                "le fichier de ${it.filePath} doit survivre au refus",
                LoggedTrackPhotoStore.resolve(context, it.filePath).exists(),
            )
        }
    }

    /**
     * Recopie le début d'une archive vers [destination] : le manifeste, puis les [dataEntriesToKeep]
     * premières entrées de données, et referme proprement. C'est la façon déterministe de fabriquer
     * exactement le cas piège : une troncature alignée sur une frontière d'entrée, indétectable au
     * niveau du format, là où couper le fichier à un nombre d'octets arbitraire ne produirait
     * qu'une entrée corrompue, que le zip signale tout seul.
     *
     * @return le nombre d'entrées de données recopiées.
     */
    private fun copyArchivePrefix(source: File, destination: File, dataEntriesToKeep: Int): Int {
        var kept = 0
        ZipOutputStream(destination.outputStream()).use { out ->
            ZipInputStream(source.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val isManifest = !entry.name.contains('/')
                    if (isManifest || kept < dataEntriesToKeep) {
                        out.putNextEntry(ZipEntry(entry.name))
                        zip.copyTo(out)
                        out.closeEntry()
                        if (!isManifest) kept += 1
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return kept
    }
}

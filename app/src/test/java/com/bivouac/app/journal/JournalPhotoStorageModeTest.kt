package com.bivouac.app.journal

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.db.PreparedDay
import com.bivouac.app.data.db.PreparedImport
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.operations.ExclusiveOperations
import com.bivouac.app.data.photo.PhotoStorageMode
import com.bivouac.app.data.prefs.SettingsPreferences
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-157 : le mode de stockage choisi dans les Réglages arrive bien jusqu'à la ligne écrite en
 * base, en passant par le vrai chemin de l'écran : sélection, transit, enregistrement.
 *
 * Ce qui est vérifié ici, c'est le BRANCHEMENT, pas la règle : les deux règles (quel mode s'applique
 * faute de décision, et laquelle se verrouille) sont pures et exercées par PhotoStoragePolicyTest.
 *
 * ⚠️ Limite du harnais, la même que celle déjà notée par RepositoryBackupCycleTest : le DataStore
 * des Réglages est un singleton de classloader, qui survit d'un test à l'autre et même d'une classe
 * de test à l'autre sous Robolectric. Aucun test d'ici ne peut donc partir de « aucune décision
 * enregistrée » : chacun POSE la décision qu'il exerce, et c'est ce qui les rend indépendants de
 * leur ordre d'exécution.
 */
@RunWith(RobolectricTestRunner::class)
class JournalPhotoStorageModeTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository
    private lateinit var settingsPreferences: SettingsPreferences
    private lateinit var viewModel: JournalViewModel

    private val trackId = "ric157-mode"

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        // Registre d'exclusion : un objet unique pour tout le process, donc partagé avec les autres
        // classes de test du même sandbox Robolectric. Un verrou laissé posé ailleurs ferait
        // refuser l'import de photos d'ici, sans rapport avec ce qu'on mesure.
        ExclusiveOperations.resetForTests()
        repository = LoggedTrackRepository(application)
        settingsPreferences = SettingsPreferences(application)
    }

    @After
    fun tearDown() {
        ExclusiveOperations.resetForTests()
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
    }

    /**
     * « Qualité d'origine » choisi dans les Réglages : les lignes le disent, et le fichier local est
     * l'original octet pour octet, comme avant ce ticket.
     */
    @Test
    fun theArchiveChoiceReachesTheRowsThroughTheRealEditingPath() {
        runBlocking { settingsPreferences.setPhotoStorageMode(PhotoStorageMode.FULL) }
        val entry = openTrack()

        importAndSave(entry, listOf(photoUri(1), photoUri(2)))

        assertEquals(listOf(PhotoStorageMode.FULL, PhotoStorageMode.FULL), storedModes())
    }

    /**
     * « Poids allégé » choisi dans les Réglages : les lignes le disent aussi.
     *
     * (Robolectric ne décode pas d'image et rend des dimensions sous la cible : c'est donc la
     * branche « déjà assez petite, copie brute mais politique REDUCED » qui est exercée. La
     * réduction réelle est vérifiée par PhotoReducerInstrumentedTest, en GMD.)
     */
    @Test
    fun theReducedChoiceReachesTheRowsThroughTheRealEditingPath() {
        runBlocking { settingsPreferences.setPhotoStorageMode(PhotoStorageMode.REDUCED) }
        val entry = openTrack()

        importAndSave(entry, listOf(photoUri(3), photoUri(4)))

        assertEquals(listOf(PhotoStorageMode.REDUCED, PhotoStorageMode.REDUCED), storedModes())
    }

    /**
     * Le réglage est relu à chaque lot, pas capturé à l'ouverture de l'écran : c'est ce qui permet
     * d'aller le changer dans les Réglages puis de revenir ajouter des photos sans que l'ancien
     * choix s'applique encore. Une même sortie peut donc porter des photos des deux régimes, ce qui
     * est voulu : chaque photo garde ce qu'elle a subi.
     */
    @Test
    fun theSettingIsReReadForEveryBatchNotCapturedWhenTheScreenOpens() {
        runBlocking { settingsPreferences.setPhotoStorageMode(PhotoStorageMode.FULL) }
        val entry = openTrack()
        importAndSave(entry, listOf(photoUri(5)))

        runBlocking { settingsPreferences.setPhotoStorageMode(PhotoStorageMode.REDUCED) }
        importAndSave(entry, listOf(photoUri(6)))

        assertEquals(listOf(PhotoStorageMode.FULL, PhotoStorageMode.REDUCED), storedModes())
    }

    /** L'URI de la source est relevé dans les deux modes : c'est le premier temps de la résolution. */
    @Test
    fun everyStoredPhotoCarriesTheUriItCameFrom() {
        runBlocking { settingsPreferences.setPhotoStorageMode(PhotoStorageMode.REDUCED) }
        val entry = openTrack()

        importAndSave(entry, listOf(photoUri(7)))

        assertEquals(
            listOf("content://test/photo-7"),
            runBlocking { repository.listPhotos(trackId) }.map { it.lastResolvedUri },
        )
    }

    // --- Mise en place -------------------------------------------------------------------------

    private fun openTrack(): LoggedTrackEntity {
        val entry = runBlocking { createTrack() }
        viewModel = JournalViewModel(application)
        viewModel.openTrack(entry)
        waitUntil("la trace ne s'est pas ouverte") { viewModel.uiState.value is JournalUiState.Detail }
        return entry
    }

    // Le vrai geste de l'écran : le sélecteur remplit le transit, la disquette enregistre.
    private fun importAndSave(entry: LoggedTrackEntity, uris: List<Uri>) {
        val before = runBlocking { repository.listPhotos(entry.id) }.size
        viewModel.addPhotos(uris)
        waitUntil("le lot n'est pas arrivé en transit") {
            viewModel.photoOperationProgress.value == null && viewModel.photosDirty.value
        }
        viewModel.saveDetails(tags = emptySet(), note = "")
        waitUntil("l'enregistrement ne s'est jamais terminé") {
            runBlocking { repository.listPhotos(entry.id) }.size == before + uris.size
        }
        // Les lignes sont écrites un cran AVANT que le verrou d'exclusion ne soit rendu (il l'est
        // dans le finally de saveDetails) : sans cette attente, un second lot enchaîné se ferait
        // refuser par le registre, et le test lirait « rien n'est arrivé en transit » là où il n'y a
        // qu'une course de harnais.
        waitUntil("le verrou d'exclusion n'a jamais été rendu") {
            ExclusiveOperations.current.value == null
        }
    }

    // Dans l'ordre d'affichage, qui est aussi l'ordre d'ajout ici (aucune date de prise de vue sur
    // ces octets, donc c'est addedAtMillis qui départage : voir PhotoDisplayOrder).
    private fun storedModes(): List<PhotoStorageMode> =
        runBlocking { repository.listPhotos(trackId) }.map { it.storageMode }

    private suspend fun createTrack(): LoggedTrackEntity {
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test mode de stockage",
        )
        val entry = LoggedTrackEntity(
            id = trackId,
            name = "Trace test mode de stockage",
            startedAt = 0L,
            contentHash = "hash-trace",
            distanceMeters = 1.0,
            elevationGainMeters = 2.0,
            elevationLossMeters = 3.0,
            pointCount = 2,
            estimatedDurationMinutes = 4,
        )
        repository.commitImport(
            PreparedImport(
                entry,
                listOf(
                    PreparedDay(
                        rawGpx = gpx,
                        contentHash = "hash-jour",
                        startedAtMillis = 0L,
                        elapsedSeconds = null,
                        segmentAggregate = DaySegmentAggregate.EMPTY,
                    ),
                ),
            ),
        )
        return entry
    }

    // Contenus tous différents : la déduplication par empreinte n'en garderait qu'un sinon.
    private fun photoUri(index: Int): Uri {
        val uri = Uri.parse("content://test/photo-$index")
        val bytes = ByteArray(2_048) { (index * 7 + it).toByte() }
        shadowOf(application.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    private fun waitUntil(message: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        if (!condition()) fail(message)
    }
}

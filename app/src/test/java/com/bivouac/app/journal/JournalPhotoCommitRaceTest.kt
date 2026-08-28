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
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-149 : la course entre l'enregistrement des photos et la sortie de l'écran.
 *
 * Symptôme rapporté en recette : enregistrer par le bouton « Enregistrer » du dialogue « quitter
 * sans enregistrer ? » perd une partie des photos qu'on venait d'ajouter, alors que la disquette,
 * elle, les enregistre toutes. Deux gestes, le même enregistrement, deux résultats.
 *
 * Ce que ces tests figent, c'est la cause : ce chemin-là enchaîne, sur la même frame,
 * [JournalViewModel.saveDetails] puis [JournalViewModel.closeTrack]. Le premier lance un commit
 * asynchrone qui déplace les fichiers de transit un par un ; le second appelle
 * [JournalViewModel.discardPhotoEdits], qui efface ces mêmes fichiers. La boucle de suppression
 * (un unlink par photo) va plus vite que la boucle d'enregistrement (un déplacement plus un insert
 * SQLite par photo) et la rattrape en cours de route, d'où une perte partielle, et d'autant plus
 * probable que le lot est gros. La disquette ne quitte pas l'écran, n'appelle donc pas closeTrack,
 * et ne perd rien : toute l'asymétrie est là.
 *
 * Même infrastructure Robolectric que LoggedTrackPhotoTransactionTest (vrai Context, vraie base,
 * vrais fichiers), avec en plus le pilotage du looper principal : viewModelScope y publie ses
 * reprises, et un test qui ne le fait pas tourner n'attendrait rien de ce qu'il déclenche.
 *
 * S'y ajoute ce qui garde l'écran verrouillé pendant les deux opérations photo longues, import
 * compris : [JournalViewModel.photoOperationProgress] est ce dont l'écran tire son dialogue
 * bloquant, et le publier trop tard rouvrirait exactement la porte que ces tests ferment.
 */
@RunWith(RobolectricTestRunner::class)
class JournalPhotoCommitRaceTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository
    private lateinit var viewModel: JournalViewModel

    private val trackId = "ric149-course"

    // Un lot, et pas une photo : c'est le geste réel (on revient d'une rando avec une poignée de
    // photos), et c'est aussi ce qui rend la course observable. Sur une seule photo, l'écart entre
    // les deux boucles se compte en microsecondes et le résultat tient au hasard de
    // l'ordonnanceur ; sur vingt-quatre, la suppression rattrape l'enregistrement à coup sûr.
    private val photoCount = 24

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        repository = LoggedTrackRepository(application)
    }

    @After
    fun tearDown() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
    }

    /**
     * Le chemin du dialogue de sortie, celui qui perdait des photos : on enregistre, et l'écran se
     * ferme dans la foulée.
     */
    @Test
    fun savingFromTheExitDialogKeepsEveryPhoto() {
        val entry = openTrackWithStagedPhotos()

        viewModel.saveDetails(tags = emptySet(), note = "Sortie avec photos")
        // Sans attendre quoi que ce soit : c'est exactement ce que fait le dialogue de sortie, dont
        // le bouton « Enregistrer » enchaîne la sauvegarde et la fermeture sur le même clic.
        viewModel.closeTrack()

        awaitPhotoCommit()
        assertEveryPhotoSaved(entry.id)
    }

    /**
     * Le chemin de la disquette, celui qui marchait : même enregistrement, mais l'écran reste
     * ouvert. Présent pour que le test précédent prouve bien une course, et non un enregistrement
     * cassé de bout en bout.
     */
    @Test
    fun savingFromTheSaveIconKeepsEveryPhoto() {
        val entry = openTrackWithStagedPhotos()

        viewModel.saveDetails(tags = emptySet(), note = "Sortie avec photos")

        awaitPhotoCommit()
        assertEveryPhotoSaved(entry.id)
    }

    /**
     * Le correctif tel que l'écran s'en sert : la fermeture est passée en rappel de fin
     * d'enregistrement. Elle ne doit pas partir avant que tout soit écrit, sans quoi il n'y aurait
     * plus qu'un filet de sécurité pour rattraper la course, au lieu de deux.
     */
    @Test
    fun theExitCallbackOnlyRunsOnceEveryPhotoIsWritten() {
        val entry = openTrackWithStagedPhotos()
        var photosWhenExitRan = -1
        var progressWhenExitRan: PhotoOperationProgress? =
            PhotoOperationProgress(PhotoOperationPhase.COMMIT, 0, 0)

        viewModel.saveDetails(tags = emptySet(), note = "Sortie avec photos") {
            photosWhenExitRan = runBlocking { repository.listPhotos(entry.id) }.size
            progressWhenExitRan = viewModel.photoOperationProgress.value
            viewModel.closeTrack()
        }
        assertEquals(
            "le compteur doit être publié dès l'appel, pas quand la coroutine sera ordonnancée",
            photoCount,
            viewModel.photoOperationProgress.value?.total,
        )
        assertEquals(
            "et il doit annoncer l'enregistrement, pas l'import",
            PhotoOperationPhase.COMMIT,
            viewModel.photoOperationProgress.value?.phase,
        )

        waitUntil("la fermeture n'a jamais eu lieu") { photosWhenExitRan >= 0 }
        assertEquals("tout doit être en base avant la sortie", photoCount, photosWhenExitRan)
        assertEquals("le dialogue bloquant doit être retiré avant la sortie", null, progressWhenExitRan)
        awaitIdle()
        assertEveryPhotoSaved(entry.id)
    }

    /**
     * RIC-149 : l'import passe sous le même dialogue bloquant que l'enregistrement.
     *
     * Le compteur doit exister dès le retour de [JournalViewModel.addPhotos], donc avant qu'aucune
     * coroutine n'ait pu être ordonnancée : c'est la fenêtre pendant laquelle la croix de l'écran
     * restait atteignable, et un état publié « quand la coroutine démarrera » ne la fermerait pas.
     *
     * La phase est vérifiée avec le compte : c'est elle qui décide du titre du dialogue, et un
     * import annoncé comme un enregistrement dirait à l'utilisateur que ses photos sont déjà dans
     * le Journal alors qu'elles n'ont pas quitté le transit.
     */
    @Test
    fun theImportBlocksFromTheVeryFirstFrameAndSaysSo() {
        val entry = runBlocking { createTrack() }
        viewModel = JournalViewModel(application)
        viewModel.openTrack(entry)
        waitUntil("la trace ne s'est pas ouverte") { viewModel.uiState.value is JournalUiState.Detail }

        viewModel.addPhotos((1..photoCount).map { registerPhoto(it) })

        val progress = viewModel.photoOperationProgress.value
        assertEquals(
            "le dialogue doit être demandé dès l'appel, pas quand la coroutine sera ordonnancée",
            PhotoOperationProgress(PhotoOperationPhase.IMPORT, done = 0, total = photoCount),
            progress,
        )
    }

    /**
     * Et il se retire de lui-même une fois le lot en transit : un dialogue sans porte de sortie qui
     * resterait à l'écran enfermerait l'utilisateur dans la trace ouverte.
     */
    @Test
    fun theImportProgressIsClearedOnceTheBatchIsStaged() {
        openTrackWithStagedPhotos()

        assertEquals(
            "plus rien ne doit bloquer une fois le lot en transit",
            null,
            viewModel.photoOperationProgress.value,
        )
        assertEquals("et le lot doit bien être là", photoCount, transitFiles().size)
    }

    /**
     * L'abandon franc, qui doit continuer de tout jeter : le correctif protège les fichiers d'un
     * commit en vol, il ne doit pas protéger ceux qu'on vient de renoncer à enregistrer.
     */
    @Test
    fun leavingWithoutSavingStillThrowsTheStagedPhotosAway() {
        val entry = openTrackWithStagedPhotos()

        viewModel.discardPhotoEdits()
        viewModel.closeTrack()

        awaitIdle()
        assertEquals(0, runBlocking { repository.listPhotos(entry.id) }.size)
        assertEquals(emptyList<String>(), transitFiles())
    }

    // --- Mise en place -------------------------------------------------------------------------

    private fun openTrackWithStagedPhotos(): LoggedTrackEntity {
        val entry = runBlocking { createTrack() }
        viewModel = JournalViewModel(application)
        viewModel.openTrack(entry)
        waitUntil("la trace ne s'est pas ouverte") { viewModel.uiState.value is JournalUiState.Detail }

        viewModel.addPhotos((1..photoCount).map { registerPhoto(it) })
        waitUntil("les photos ne sont pas arrivées en transit") {
            viewModel.photoOperationProgress.value == null && viewModel.photosDirty.value
        }
        assertEquals("le lot entier doit être en transit avant l'enregistrement", photoCount, transitFiles().size)
        assertEquals("rien ne doit être en base avant l'enregistrement", 0, runBlocking { repository.listPhotos(entry.id) }.size)
        return entry
    }

    private suspend fun createTrack(): LoggedTrackEntity {
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test course",
        )
        val entry = LoggedTrackEntity(
            id = trackId,
            name = "Trace test course",
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

    // registerInputStreamSupplier et non registerInputStream : le staging ouvre chaque Uri deux
    // fois (empreinte, puis copie), un flux unique serait déjà épuisé au deuxième passage. Des
    // contenus tous différents, sinon la déduplication par empreinte n'en garderait qu'un.
    private fun registerPhoto(index: Int): Uri {
        val uri = Uri.parse("content://test/photo-$index")
        val bytes = ByteArray(4_096) { (index + it).toByte() }
        shadowOf(application.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    // --- Attentes et vérifications -------------------------------------------------------------

    private fun transitFiles(): List<String> =
        LoggedTrackPhotoStore.transitDir(application).listFiles().orEmpty().filter { it.isFile }.map { it.name }

    /**
     * L'enregistrement des photos est terminé quand plus rien n'est en attente ET que le transit
     * est vide, que les fichiers soient partis en stockage (enregistrés) ou à la poubelle (perdus).
     * La suite du test dit lequel des deux s'est produit.
     */
    private fun awaitPhotoCommit() {
        waitUntil("l'enregistrement des photos ne s'est jamais terminé") {
            !viewModel.photosDirty.value && transitFiles().isEmpty()
        }
        // Une passe de plus, une fois la condition atteinte : le perdant d'une course peut encore
        // être en train de s'exécuter au moment où le gagnant publie son résultat.
        awaitIdle()
    }

    private fun assertEveryPhotoSaved(trackId: String) {
        val saved = runBlocking { repository.listPhotos(trackId) }
        assertEquals("aucune photo du lot ne doit se perdre en route", photoCount, saved.size)
        saved.forEach {
            assertTrue(
                "le fichier de ${it.filePath} doit exister",
                LoggedTrackPhotoStore.resolve(application, it.filePath).exists(),
            )
        }
        assertEquals("le transit doit être vide après l'enregistrement", emptyList<String>(), transitFiles())
    }

    private fun awaitIdle(millis: Long = 300) {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Le looper principal est en mode pausé sous Robolectric : les reprises que viewModelScope y
     * poste n'avancent que si le test les fait tourner. D'où cette boucle, qui alterne un tour de
     * looper et une pause laissant travailler les threads d'entrées/sorties.
     */
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

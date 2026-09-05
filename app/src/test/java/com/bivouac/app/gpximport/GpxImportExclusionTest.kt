package com.bivouac.app.gpximport

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.PlanificationGpxStore
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.operations.ExclusiveOperation
import com.bivouac.app.data.operations.ExclusiveOperations
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-158 : l'import d'un fichier GPX en Planification, et la duplication d'une sortie du Journal
 * vers la Planification (RIC-40), entrent au registre d'exclusion mutuelle : l'un et l'autre font
 * atterrir du contenu dans gpx-planif/, exactement ce qu'une sauvegarde zippe et qu'une
 * restauration remplace en bloc.
 *
 * Même infrastructure Robolectric que JournalGpxImportExclusionTest (vrai Context, vrais fichiers,
 * pilotage du looper principal).
 */
@RunWith(RobolectricTestRunner::class)
class GpxImportExclusionTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var viewModel: GpxImportViewModel

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        PlanificationGpxStore.dir(application).deleteRecursively()
        ExclusiveOperations.resetForTests()
        viewModel = GpxImportViewModel(application)
    }

    @After
    fun tearDown() {
        ExclusiveOperations.resetForTests()
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        PlanificationGpxStore.dir(application).deleteRecursively()
    }

    @Test
    fun importGpxIsRefusedCleanlyWhileAnotherOperationIsInFlight() {
        assertTrue(ExclusiveOperations.tryStart(ExclusiveOperation.BACKUP))

        viewModel.importGpx(application.contentResolver, registerGpx("solo"))
        idle()

        val state = viewModel.uiState.value
        assertTrue("un refus doit se traduire par un état Error", state is GpxImportUiState.Error)
        assertTrue(
            "le message doit nommer l'opération en cours",
            (state as GpxImportUiState.Error).message.contains("une sauvegarde"),
        )
        assertEquals(
            "aucun fichier de session ne doit avoir été écrit",
            emptyList<String>(),
            savedSessionFiles(),
        )
        assertEquals(
            "le verrou de l'opération déjà en vol ne doit pas bouger",
            ExclusiveOperation.BACKUP,
            ExclusiveOperations.current.value,
        )
    }

    @Test
    fun importGpxSucceedsAndReleasesTheLockOnceDone() {
        viewModel.importGpx(application.contentResolver, registerGpx("solo"))
        idle()

        assertTrue(
            "l'import doit aboutir sur une trace chargée",
            viewModel.uiState.value is GpxImportUiState.Loaded,
        )
        assertNull(
            "le verrou doit être relâché une fois l'import terminé",
            ExclusiveOperations.current.value,
        )
    }

    /** RIC-40 : même registre pour la duplication Journal -> Planification, qui écrit elle aussi. */
    @Test
    fun duplicateFromLoggedTrackIsRefusedCleanlyWhileAnotherOperationIsInFlight() {
        assertTrue(ExclusiveOperations.tryStart(ExclusiveOperation.RESTORE))

        viewModel.openDuplicateFromLoggedTrack(sampleTrack(), emptyList(), "Copie de sortie")
        idle()

        val state = viewModel.uiState.value
        assertTrue("un refus doit se traduire par un état Error", state is GpxImportUiState.Error)
        assertTrue(
            (state as GpxImportUiState.Error).message.contains("une restauration"),
        )
        assertEquals(emptyList<String>(), savedSessionFiles())
        assertEquals(ExclusiveOperation.RESTORE, ExclusiveOperations.current.value)
    }

    @Test
    fun duplicateFromLoggedTrackSucceedsAndReleasesTheLockOnceDone() {
        viewModel.openDuplicateFromLoggedTrack(sampleTrack(), emptyList(), "Copie de sortie")
        idle()

        assertTrue(viewModel.uiState.value is GpxImportUiState.Loaded)
        assertNull(ExclusiveOperations.current.value)
    }

    // --- Mise en place -------------------------------------------------------------------------

    private fun sampleTrack(): HikeTrack = HikeTrack(
        name = "Sortie à dupliquer",
        points = listOf(
            TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
            TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
        ),
    )

    private fun registerGpx(seed: String): Uri {
        val uri = Uri.parse("content://test/gpx-$seed")
        val gpx = GpxWriter.write(sampleTrack().points, "Trace test $seed")
        val bytes = gpx.toByteArray(StandardCharsets.UTF_8)
        shadowOf(application.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    private fun savedSessionFiles(): List<String> =
        PlanificationGpxStore.dir(application).listFiles().orEmpty().filter { it.isFile }.map { it.name }

    // Le verrou n'est levé qu'à la toute fin du finally (après persistCurrentStateNow(), une
    // écriture IO réelle), attendre seulement qu'uiState quitte Loading serait prématuré pour les
    // cas de succès, l'écriture pouvant encore être en vol à ce moment-là. Pour un refus, le
    // verrou de PLANIFICATION_IMPORT n'a jamais été posé (tryStart échoue avant tout), donc cette
    // condition est déjà vraie dès le retour synchrone de l'appel.
    private fun idle(timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (viewModel.uiState.value !is GpxImportUiState.Loading &&
                ExclusiveOperations.current.value != ExclusiveOperation.PLANIFICATION_IMPORT
            ) {
                shadowOf(Looper.getMainLooper()).idle()
                return
            }
            Thread.sleep(5)
        }
        fail("l'opération ne s'est jamais terminée")
    }
}

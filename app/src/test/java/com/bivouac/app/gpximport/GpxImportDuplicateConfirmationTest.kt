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
 * RIC-121 : une duplication Journal -> Planification bloquée par une trace ouverte doit se
 * reconnaître depuis l'IHM, pour que le dialogue parle de remplacement et non de simple fermeture.
 *
 * Ce que ces tests verrouillent, c'est le contrat que l'écran consomme : [GpxImportViewModel.
 * pendingDuplicateName] vaut le nom de la sortie du Journal exactement pendant la fenêtre où une
 * duplication attend une décision, et rien d'autre. Les textes eux-mêmes vivent dans le
 * composable, hors de portée d'un test JVM.
 */
@RunWith(RobolectricTestRunner::class)
class GpxImportDuplicateConfirmationTest {

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
    fun noPendingDuplicateNameWhenNothingIsWaiting() {
        assertNull(viewModel.pendingDuplicateName.value)

        // Fermeture ordinaire d'une trace jamais enregistrée : le dialogue s'ouvre, mais rien
        // n'attend derrière : c'est le cas qui doit garder les textes de fermeture d'origine.
        openTrackNeverSaved()
        viewModel.requestClose()

        assertEquals(CloseConfirmationReason.NEVER_SAVED, viewModel.closeConfirmationReason.value)
        assertNull(
            "une fermeture ordinaire ne doit rien annoncer comme duplication en attente",
            viewModel.pendingDuplicateName.value,
        )
    }

    @Test
    fun blockedDuplicateExposesTheJournalTrackName() {
        openTrackNeverSaved()

        viewModel.openDuplicateFromLoggedTrack(duplicateTrack(), emptyList(), "Copie de Boucle du Canigou", "Boucle du Canigou")

        assertEquals(
            "la même confirmation que pour une fermeture manuelle doit s'ouvrir",
            CloseConfirmationReason.NEVER_SAVED,
            viewModel.closeConfirmationReason.value,
        )
        assertEquals(
            "l'IHM doit pouvoir nommer la sortie du Journal, pas le nom de copie déjà suffixé",
            "Boucle du Canigou",
            viewModel.pendingDuplicateName.value,
        )
    }

    /** « Annuler la duplication » : la trace ouverte reste, et la copie est abandonnée pour de bon. */
    @Test
    fun dismissingTheConfirmationDropsThePendingDuplicate() {
        openTrackNeverSaved()
        viewModel.openDuplicateFromLoggedTrack(duplicateTrack(), emptyList(), "Copie de Boucle du Canigou", "Boucle du Canigou")

        viewModel.dismissCloseConfirmation()
        idle()

        assertNull(viewModel.closeConfirmationReason.value)
        assertNull(viewModel.pendingDuplicateName.value)
        assertEquals(
            "la trace ouverte doit être restée en place",
            "Trace test solo",
            (viewModel.uiState.value as GpxImportUiState.Loaded).track.name,
        )
    }

    /** « Ne pas enregistrer » : la trace ouverte est lâchée et la copie prend sa place. */
    @Test
    fun discardingOpensTheDuplicateAndClearsThePendingName() {
        openTrackNeverSaved()
        viewModel.openDuplicateFromLoggedTrack(duplicateTrack(), emptyList(), "Copie de Boucle du Canigou", "Boucle du Canigou")

        viewModel.discardAndClose()
        idle()

        assertEquals(
            "Boucle du Canigou",
            (viewModel.uiState.value as GpxImportUiState.Loaded).track.name,
        )
        assertNull(
            "la duplication n'attend plus rien une fois chargée",
            viewModel.pendingDuplicateName.value,
        )
    }

    // --- Mise en place -------------------------------------------------------------------------

    /** Une trace importée mais jamais banquée : le cas [CloseConfirmationReason.NEVER_SAVED]. */
    private fun openTrackNeverSaved() {
        viewModel.importGpx(application.contentResolver, registerGpx("solo"))
        idle()
        assertTrue(viewModel.uiState.value is GpxImportUiState.Loaded)
        assertNull(viewModel.currentBankedId.value)
    }

    private fun duplicateTrack(): HikeTrack = HikeTrack(
        name = "Boucle du Canigou",
        points = listOf(
            TrackPoint(42.5, 2.45, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
            TrackPoint(42.51, 2.46, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
        ),
    )

    private fun registerGpx(seed: String): Uri {
        val uri = Uri.parse("content://test/gpx-$seed")
        val points = listOf(
            TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
            TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
        )
        val bytes = GpxWriter.write(points, "Trace test $seed").toByteArray(StandardCharsets.UTF_8)
        shadowOf(application.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    // Même attente que GpxImportExclusionTest : le verrou d'import n'est relâché qu'après une
    // écriture IO réelle, sortir de Loading ne suffit pas.
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

package com.bivouac.app.gpximport

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.R
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.PlanificationGpxStore
import com.bivouac.app.data.db.SavedTrackRepository
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.TrackPoint
import kotlinx.coroutines.runBlocking
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
 * RIC-200 : un échec de [GpxImportViewModel.restoreLastTrack] ne doit plus écraser tout l'écran
 * avec [GpxImportUiState.Error] (ce qui masquait la banque de traces, pourtant intacte, sans
 * bouton de retour vers elle) : il se traite comme "rien à restaurer" (Idle), avec un message non
 * bloquant dans le popup déjà utilisé par bankOpenError (RIC-127), et purge la ligne singleton
 * seulement quand la cause est un fichier absent ou un GPX que GpxParser refuse de lire -- jamais
 * pour une autre exception, qui n'est pas la preuve que le fichier est perdu.
 */
@RunWith(RobolectricTestRunner::class)
class GpxImportRestoreLastTrackTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val repository = SavedTrackRepository(application)
    private lateinit var viewModel: GpxImportViewModel

    private val track = HikeTrack(
        name = "Trace test",
        points = listOf(
            TrackPoint(45.0, 6.0, 1000.0, null),
            TrackPoint(45.01, 6.01, 1100.0, null),
        ),
    )
    private val points = listOf(BivouacPoint(id = "bp-0", trackPointIndex = 0))

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        PlanificationGpxStore.dir(application).deleteRecursively()
        viewModel = GpxImportViewModel(application)
    }

    @After
    fun tearDown() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        PlanificationGpxStore.dir(application).deleteRecursively()
    }

    @Test
    fun aucuneLigneRestePasseEnIdleSansMessage() = runBlocking {
        viewModel.restoreLastTrack()
        idle()

        assertTrue("cas normal : rien à restaurer", viewModel.uiState.value is GpxImportUiState.Idle)
        assertNull("aucun message : ce n'est pas un échec", viewModel.bankOpenError.value)
    }

    @Test
    fun fichierValideRestaureLaSessionSansMessage() = runBlocking {
        repository.save(track, points, bankedId = "banked-42")

        viewModel.restoreLastTrack()
        idle()

        val state = viewModel.uiState.value
        assertTrue("cas nominal : la session doit être restaurée", state is GpxImportUiState.Loaded)
        assertEquals("Trace test", (state as GpxImportUiState.Loaded).track.name)
        assertEquals("banked-42", viewModel.currentBankedId.value)
        assertNull("aucun message : la restauration a réussi", viewModel.bankOpenError.value)
        assertEquals(
            "la ligne ne doit pas avoir été touchée par un cas nominal",
            "banked-42",
            repository.loadLast()?.bankedId,
        )
    }

    @Test
    fun fichierAbsentPasseEnIdleAvecMessageEtPurgeLaLigne() = runBlocking {
        repository.save(track, points, bankedId = null)
        savedGpxFile().delete()

        viewModel.restoreLastTrack()
        idle()

        assertTrue(
            "un fichier absent n'est pas récupérable : traité comme rien à restaurer",
            viewModel.uiState.value is GpxImportUiState.Idle,
        )
        assertEquals(
            application.getString(R.string.gpximport_restore_failed_message),
            viewModel.bankOpenError.value,
        )
        assertNull(
            "la ligne orpheline doit être purgée pour que le message ne revienne pas au prochain démarrage",
            repository.loadLast(),
        )
    }

    @Test
    fun gpxInvalidePasseEnIdleAvecMessageEtPurgeLaLigne() = runBlocking {
        repository.save(track, points, bankedId = "banked-99")
        // GPX bien formé mais sans le moindre point de trace : GpxParser.parse le refuse
        // explicitement (IOException "Aucun point de trace trouvé dans ce fichier GPX").
        savedGpxFile().writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
                <trk><name>Vide</name><trkseg></trkseg></trk>
            </gpx>
            """.trimIndent(),
            Charsets.UTF_8,
        )

        viewModel.restoreLastTrack()
        idle()

        assertTrue(
            "un GPX illisible n'est pas récupérable : traité comme rien à restaurer",
            viewModel.uiState.value is GpxImportUiState.Idle,
        )
        assertEquals(
            application.getString(R.string.gpximport_restore_failed_message),
            viewModel.bankOpenError.value,
        )
        assertNull(
            "le bankedId (banked-99) ne sauve pas la ligne : la banque contient déjà cette trace",
            repository.loadLast(),
        )
    }

    // --- Mise en place -------------------------------------------------------------------------

    private fun savedGpxFile() = PlanificationGpxStore.resolve(application, PlanificationGpxStore.savedRelativePath())

    private fun idle(timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (viewModel.uiState.value !is GpxImportUiState.Loading) {
                shadowOf(Looper.getMainLooper()).idle()
                return
            }
            Thread.sleep(5)
        }
        fail("restoreLastTrack ne s'est jamais terminé")
    }
}

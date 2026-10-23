package com.bivouac.app.bilan

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.db.LoggedTrackRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-246 : le Bilan se recalcule à chaque affichage de l'écran, pas une seule fois à la création
 * de son ViewModel (qui survit aux allers-retours entre sections, voir MainActivity). Le
 * déclencheur composable (BilanScreen) n'est pas testable en JVM ; ce test verrouille le contrat
 * du ViewModel sur lequel il s'appuie : rien n'est chargé tant que l'écran ne le demande pas
 * (donc pas de double calcul à la première entrée), une suppression en base n'est visible qu'après
 * [BilanViewModel.refresh], et un recalcul ne fait jamais repasser [BilanViewModel.loaded] à faux
 * (les chiffres précédents restent affichés).
 */
@RunWith(RobolectricTestRunner::class)
class BilanViewModelRefreshTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val repository = LoggedTrackRepository(application)
    private val dao get() = BivouacDatabase.getInstance(application).loggedTrackDao()
    private lateinit var viewModel: BilanViewModel

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        runBlocking {
            insertTrack("track-a", distanceMeters = 12_000.0)
            insertTrack("track-b", distanceMeters = 10_500.0)
        }
        viewModel = BilanViewModel(application)
    }

    @After
    fun tearDown() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
    }

    private suspend fun insertTrack(id: String, distanceMeters: Double) {
        dao.insertTrack(
            LoggedTrackEntity(
                id = id, name = "Rando $id", startedAt = 1_700_000_000_000L, contentHash = "hash-$id",
                distanceMeters = distanceMeters, elevationGainMeters = 800.0, elevationLossMeters = 800.0,
                pointCount = 10, estimatedDurationMinutes = 240,
            ),
        )
    }

    @Test
    fun rienNEstChargeTantQueLEcranNeLeDemandePas() {
        settle(millis = 300)

        assertNull("pas de calcul à la création : c'est l'écran qui le déclenche", viewModel.stats.value)
        assertFalse(viewModel.loaded.value)
    }

    @Test
    fun uneSuppressionN_estVisibleQu_ApresRefreshEtLoadedResteVrai() = runBlocking {
        viewModel.refresh()
        awaitStats { it?.totalCount == 2 }
        assertTrue(viewModel.loaded.value)

        repository.delete("track-b")
        settle(millis = 300)

        assertEquals(
            "sans nouvelle demande, le Bilan garde ses chiffres (comportement par défaut du ViewModel)",
            2,
            viewModel.stats.value?.totalCount,
        )

        viewModel.refresh()
        // Entre la demande et le résultat, les chiffres précédents restent affichés : jamais de
        // retour à l'indicateur de chargement une fois le premier calcul fait.
        assertTrue("loaded ne repasse pas à faux pendant le recalcul", viewModel.loaded.value)
        assertNotNull("les chiffres précédents restent pendant le recalcul", viewModel.stats.value)
        awaitStats { it?.totalCount == 1 }

        assertTrue("loaded reste vrai après le recalcul", viewModel.loaded.value)
        assertEquals(12_000.0, viewModel.stats.value!!.totals.distanceMeters, 1e-6)
    }

    // --- Mise en place -------------------------------------------------------------------------

    private fun settle(millis: Long) {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun awaitStats(timeoutMillis: Long = 10_000, predicate: (BilanStats?) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate(viewModel.stats.value)) return
            Thread.sleep(5)
        }
        fail("stats n'a jamais atteint l'état attendu : ${viewModel.stats.value?.totalCount}")
    }
}

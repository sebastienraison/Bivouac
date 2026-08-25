package com.bivouac.app.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.TrackPoint
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RIC-135 : bankedId doit survivre au cycle save/loadLast de l'auto-save, sans quoi
 * restoreLastTrack ne peut jamais savoir si la session qu'il restaure est déjà liée à une entrée
 * de la banque — voir GpxImportViewModel.restoreLastTrack.
 */
@RunWith(RobolectricTestRunner::class)
class SavedTrackRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = SavedTrackRepository(context)
    private val track = HikeTrack(name = "Trace test", points = listOf(TrackPoint(45.0, 6.0, 1000.0, null)))
    private val points = listOf(BivouacPoint(id = "bp-0", trackPointIndex = 0))

    @Before
    fun resetSingleton() {
        BivouacDatabase.closeAndReset()
    }

    @After
    fun tearDown() {
        BivouacDatabase.closeAndReset()
    }

    @Test
    fun loadLastReturnsTheBankedIdItWasSavedWith() = runBlocking {
        repository.save(track, points, bankedId = "banked-42")

        val restored = repository.loadLast()

        assertEquals("banked-42", restored?.bankedId)
    }

    @Test
    fun loadLastReturnsNullBankedIdForANeverBankedSession() = runBlocking {
        repository.save(track, points, bankedId = null)

        val restored = repository.loadLast()

        assertNull(restored?.bankedId)
    }

    @Test
    fun savingAgainOverwritesThePreviousBankedId() = runBlocking {
        repository.save(track, points, bankedId = "banked-42")
        repository.save(track, points, bankedId = null)

        val restored = repository.loadLast()

        assertNull("un save ultérieur sans bankedId doit remplacer l'ancien, pas le conserver", restored?.bankedId)
    }
}

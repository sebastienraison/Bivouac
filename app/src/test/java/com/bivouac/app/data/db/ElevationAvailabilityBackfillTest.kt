package com.bivouac.app.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.gpx.TrackStatsParameters
import com.bivouac.app.data.model.TrackPoint
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RIC-231 : le booléen `elevationAvailable` (une trace a-t-elle une altitude exploitable ?) des
 * jours du Journal et des traces de la Banque, et sa passe de rattrapage du premier lancement :
 * une ligne née avant la migration 23 vers 24 (colonne nulle) reçoit vrai ou faux après la passe,
 * sans reparser le fichier une seconde fois.
 */
@RunWith(RobolectricTestRunner::class)
class ElevationAvailabilityBackfillTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loggedDao get() = BivouacDatabase.getInstance(context).loggedTrackDao()
    private val bankedDao get() = BivouacDatabase.getInstance(context).bankedTrackDao()

    @Before
    fun resetSingleton() {
        BivouacDatabase.closeAndReset()
    }

    @After
    fun tearDown() {
        LoggedTrackGpxStore.dir(context).deleteRecursively()
        PlanificationGpxStore.dir(context).deleteRecursively()
        BivouacDatabase.closeAndReset()
    }

    private fun gpx(vararg elevations: Double?): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\"><trk><name>t</name><trkseg>\n")
        elevations.forEachIndexed { i, elevation ->
            val ele = elevation?.let { "<ele>$it</ele>" }.orEmpty()
            append("<trkpt lat=\"${45.0 + i * 0.001}\" lon=\"6.0\">$ele<time>2026-06-01T0${8 + i}:00:00Z</time></trkpt>\n")
        }
        append("</trkseg></trk></gpx>")
    }

    /** Un jour « d'avant la migration 24 » : altitude déjà rattrapée (RIC-19), booléen nul. */
    private suspend fun insertDay(trackId: String, content: String?, elevationBackfilled: Boolean = true) {
        loggedDao.insertTrack(
            LoggedTrackEntity(
                id = trackId, name = "Trace $trackId", startedAt = 0L, contentHash = "hash-$trackId",
                distanceMeters = 0.0, elevationGainMeters = 0.0, elevationLossMeters = 0.0,
                pointCount = 0, estimatedDurationMinutes = 0,
            ),
        )
        LoggedTrackGpxStore.dir(context).mkdirs()
        val relativePath = LoggedTrackGpxStore.relativePath(trackId, 0)
        if (content != null) LoggedTrackGpxStore.resolve(context, relativePath).writeText(content, StandardCharsets.UTF_8)
        loggedDao.insertDays(
            listOf(
                LoggedTrackDayEntity(
                    trackId = trackId, dayIndex = 0, rawGpxFilePath = relativePath,
                    elevationBackfilled = elevationBackfilled,
                ),
            ),
        )
    }

    @Test
    fun aDayWithANullBooleanBecomesTrueOrFalseAfterThePass() = runBlocking {
        insertDay("with-relief", gpx(1000.0, 1100.0, 1250.0))
        insertDay("no-ele", gpx(null, null, null))
        insertDay("all-zero", gpx(0.0, 0.0, 0.0))
        insertDay("same-value", gpx(842.0, 842.0, 842.0))

        assertEquals(4, loggedDao.countDaysNeedingElevationBackfill())
        LoggedTrackBackfill.runElevation(context, loggedDao)

        assertEquals(0, loggedDao.countDaysNeedingElevationBackfill())
        assertEquals(true, loggedDao.getDays("with-relief").single().elevationAvailable)
        assertEquals(false, loggedDao.getDays("no-ele").single().elevationAvailable)
        assertEquals(false, loggedDao.getDays("all-zero").single().elevationAvailable)
        assertEquals(false, loggedDao.getDays("same-value").single().elevationAvailable)
    }

    @Test
    fun theElevationColumnsOfARowAlreadyBackfilledAreKept() = runBlocking {
        insertDay("with-relief", gpx(1000.0, 1100.0, 1250.0))

        LoggedTrackBackfill.runElevation(context, loggedDao)

        val day = loggedDao.getDays("with-relief").single()
        assertEquals(1250.0, day.maxElevationMeters!!, 0.0)
        assertEquals(1250.0, day.lastPointElevationMeters!!, 0.0)
        assertTrue(day.elevationBackfilled)
    }

    @Test
    fun aMissingFileLeavesTheDefaultAvailableAndLeavesTheQueue() = runBlocking {
        insertDay("missing", content = null)

        LoggedTrackBackfill.runElevation(context, loggedDao)

        assertEquals(0, loggedDao.countDaysNeedingElevationBackfill())
        assertEquals(true, loggedDao.getDays("missing").single().elevationAvailable)
    }

    @Test
    fun theSecondPassIsANoOp() = runBlocking {
        insertDay("with-relief", gpx(1000.0, 1100.0, 1250.0))
        insertDay("no-ele", gpx(null, null, null))
        LoggedTrackBackfill.runElevation(context, loggedDao)

        var progressCalls = 0
        LoggedTrackBackfill.runElevation(context, loggedDao) { _, _ -> progressCalls++ }

        assertEquals(0, progressCalls)
    }

    // Le rattrapage des statistiques (montée d'ALGORITHM_VERSION) écrit le booléen avec les totaux.
    @Test
    fun theStatsBackfillWritesTheBooleanWithTheDayTotals() = runBlocking {
        insertDay("no-ele", gpx(null, null, null), elevationBackfilled = true)
        insertDay("with-relief", gpx(1000.0, 1100.0, 1250.0), elevationBackfilled = true)
        for (id in listOf("no-ele", "with-relief")) {
            loggedDao.markStatsVersion(id, 0)
        }

        LoggedTrackBackfill.runStats(context, loggedDao)

        val noEle = loggedDao.getDays("no-ele").single()
        assertEquals(false, noEle.elevationAvailable)
        assertEquals(0.0, noEle.elevationGainMeters!!, 0.0)
        assertTrue(noEle.distanceMeters!! > 100.0)
        val relief = loggedDao.getDays("with-relief").single()
        assertEquals(true, relief.elevationAvailable)
        assertTrue(relief.elevationGainMeters!! > 0.0)
    }

    // Le rattrapage RIC-109/115 (applyDayBackfill) ne connaît pas l'altitude : il ne doit pas
    // effacer un booléen déjà écrit.
    @Test
    fun theSegmentBackfillDoesNotEraseTheBoolean() = runBlocking {
        insertDay("no-ele", gpx(null, null, null))
        LoggedTrackBackfill.runElevation(context, loggedDao)
        assertEquals(false, loggedDao.getDays("no-ele").single().elevationAvailable)

        LoggedTrackBackfill.run(context, loggedDao)

        assertEquals(false, loggedDao.getDays("no-ele").single().elevationAvailable)
    }

    // La calibration de repli (une ligne par rando) n'apprend rien d'une rando sans altitude : son D+
    // de 0 n'est pas une mesure.
    @Test
    fun theCalibrationFallbackIgnoresATrackWithoutElevation() = runBlocking {
        for ((id, available) in listOf("no-ele" to false, "relief" to true)) {
            loggedDao.insertTrack(
                LoggedTrackEntity(
                    id = id, name = id, startedAt = 0L, contentHash = "hash-$id",
                    distanceMeters = 5_000.0, elevationGainMeters = 0.0, elevationLossMeters = 0.0,
                    pointCount = 0, estimatedDurationMinutes = 0,
                ),
            )
            loggedDao.insertDays(
                listOf(
                    LoggedTrackDayEntity(
                        trackId = id, dayIndex = 0, rawGpxFilePath = "gpx/$id.gpx", contentHash = "day-$id",
                        elapsedSeconds = 3_600L, elevationAvailable = available,
                    ),
                ),
            )
        }

        val input = LoggedTrackRepository(context).calibrationSamples()

        assertEquals(1, input.fallbackSamples.size)
    }

    // --- Banque ---

    private suspend fun insertBanked(id: String, points: List<TrackPoint>, available: Boolean? = null) {
        PlanificationGpxStore.dir(context).mkdirs()
        val relativePath = PlanificationGpxStore.bankedRelativePath(id)
        PlanificationGpxStore.resolve(context, relativePath)
            .writeText(GpxWriter.write(points, id), StandardCharsets.UTF_8)
        bankedDao.save(
            BankedTrackEntity(
                id = id, name = id, gpxFilePath = relativePath, bivouacTrackPointIndices = "",
                distanceMeters = 1_000.0, elevationGainMeters = 0.0, elevationLossMeters = 0.0,
                estimatedDurationMinutes = 20, savedAt = 0L, statsVersion = TrackStatsParameters.ALGORITHM_VERSION,
                elevationAvailable = available,
            ),
        )
    }

    private fun trackPoints(vararg elevations: Double?) = elevations.mapIndexed { i, elevation ->
        TrackPoint(latitude = 45.0 + i * 0.001, longitude = 6.0, elevationMeters = elevation, time = null)
    }

    @Test
    fun theBankedPassFillsTheBooleanOfOlderRows() = runBlocking {
        insertBanked("relief", trackPoints(1000.0, 1100.0, 1250.0))
        insertBanked("flat-zero", trackPoints(0.0, 0.0, 0.0))
        insertBanked("none", trackPoints(null, null, null))
        insertBanked("already", trackPoints(null, null, null), available = true) // déjà renseignée : intouchée
        val repository = BankedTrackRepository(context)

        assertEquals(3, repository.countTracksNeedingElevationAvailability())
        repository.backfillElevationAvailability()

        assertEquals(0, repository.countTracksNeedingElevationAvailability())
        assertEquals(true, bankedDao.get("relief")!!.elevationAvailable)
        assertEquals(false, bankedDao.get("flat-zero")!!.elevationAvailable)
        assertEquals(false, bankedDao.get("none")!!.elevationAvailable)
        assertEquals(true, bankedDao.get("already")!!.elevationAvailable)
    }

    @Test
    fun aBankedRowWithANullBooleanReadsAsAvailable() {
        val entity = BankedTrackEntity(
            id = "x", name = "x", gpxFilePath = "x", bivouacTrackPointIndices = "", distanceMeters = 1.0,
            elevationGainMeters = 0.0, elevationLossMeters = 0.0, estimatedDurationMinutes = 1, savedAt = 0L,
        )

        assertNull(entity.elevationAvailable)
        assertTrue(entity.toTrackStats().elevationAvailable)
        assertFalse(entity.copy(elevationAvailable = false).toTrackStats().elevationAvailable)
    }

    @Test
    fun savingABankedTrackStoresTheBooleanOfItsStats() = runBlocking {
        val repository = BankedTrackRepository(context)
        val points = trackPoints(null, null, null)
        val stats = com.bivouac.app.data.gpx.TrackStatsCalculator.compute(points)

        val id = repository.save(
            id = null, name = "sans altitude",
            track = com.bivouac.app.data.model.HikeTrack("sans altitude", points),
            bivouacPoints = emptyList(), stats = stats,
        )

        assertEquals(false, bankedDao.get(id)!!.elevationAvailable)
    }
}

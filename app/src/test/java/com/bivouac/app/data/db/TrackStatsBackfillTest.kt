package com.bivouac.app.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.SpeedCalibrationCalculator
import com.bivouac.app.data.gpx.TrackSegmenter
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.gpx.TrackStatsParameters
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.prefs.SettingsPreferences
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RIC-114 lot 2 (conception §6.2) : verrouille le rattrapage des quatre statistiques dérivées
 * (distance/D+/D-/durée, `statsVersion`) du Journal ([LoggedTrackBackfill.runStats]) et de la
 * Banque ([BankedTrackRepository.backfillStatsFields]), et la recalibration qui en découle
 * ([CalibrationRefresh.refreshIfNeeded]). Même installation que LoggedTrackBackfillTest (RIC-109) :
 * singleton remis à zéro, GPX synthétiques écrits dans LoggedTrackGpxStore/PlanificationGpxStore.
 */
@RunWith(RobolectricTestRunner::class)
class TrackStatsBackfillTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loggedDao get() = BivouacDatabase.getInstance(context).loggedTrackDao()
    private val bankedDao get() = BivouacDatabase.getInstance(context).bankedTrackDao()
    private val loggedRepository get() = LoggedTrackRepository(context)
    private val bankedRepository get() = BankedTrackRepository(context)
    private val settingsPreferences get() = SettingsPreferences(context)

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

    // Quinze points à 30 m d'écart, altitude plate (mêmes constructions que LoggedTrackBackfillTest
    // .flatGpx()) : le décalage de latitude/date donne un contenu (et donc un contentHash) distinct
    // par trace ou par jour, sans changer la forme de la trace.
    private fun flatGpx(latOffsetDeg: Double = 0.0, baseTimeIso: String = "2026-06-01T08:00:00Z"): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\"><trk><name>t</name><trkseg>\n")
            val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)
            val baseTime = Instant.parse(baseTimeIso)
            repeat(15) { i ->
                val lat = 45.0 + latOffsetDeg + i * 30.0 * degPerMeter
                val seconds = (i * 30.0 / 1000.0 / 4.0 * 3600.0).toLong()
                append("<trkpt lat=\"$lat\" lon=\"6.0\"><ele>1000.0</ele><time>${baseTime.plusSeconds(seconds)}</time></trkpt>\n")
            }
            append("</trkseg></trk></gpx>")
        }

    private fun pointsOf(gpx: String): List<TrackPoint> =
        gpx.byteInputStream(StandardCharsets.UTF_8).use { GpxParser.parse(it) }.points

    // Trace « d'avant RIC-114 » : statsVersion et valeurs volontairement fausses (conception §6.2
    // point 1), jour(s) jamais rattrapés du tout (contentHash/flatCount nuls) pour couvrir aussi le
    // piège §5.4 dans la même fixture.
    private suspend fun insertLegacyLoggedTrack(
        trackId: String,
        dayGpxContents: List<String>,
        statsVersion: Int,
        placeholderValue: Double = 9_999.0,
    ) {
        loggedDao.insertTrack(
            LoggedTrackEntity(
                id = trackId, name = "Trace $trackId", startedAt = 0L, contentHash = "hash-$trackId",
                distanceMeters = placeholderValue, elevationGainMeters = placeholderValue,
                elevationLossMeters = placeholderValue, pointCount = 0,
                estimatedDurationMinutes = placeholderValue.toInt(), statsVersion = statsVersion,
            ),
        )
        LoggedTrackGpxStore.dir(context).mkdirs()
        val days = dayGpxContents.mapIndexed { index, gpx ->
            val relativePath = LoggedTrackGpxStore.relativePath(trackId, index)
            LoggedTrackGpxStore.resolve(context, relativePath).writeText(gpx, StandardCharsets.UTF_8)
            LoggedTrackDayEntity(trackId = trackId, dayIndex = index, rawGpxFilePath = relativePath)
        }
        loggedDao.insertDays(days)
    }

    @Test
    fun recomputesTrackAndDayColumnsToMatchAFreshCalculation() = runBlocking {
        val day0 = flatGpx(latOffsetDeg = 0.0)
        val day1 = flatGpx(latOffsetDeg = 0.01, baseTimeIso = "2026-06-02T08:00:00Z")
        insertLegacyLoggedTrack("track-multi", listOf(day0, day1), statsVersion = 0)

        LoggedTrackBackfill.runStats(context, loggedDao)

        val track = loggedDao.get("track-multi")!!
        assertEquals(TrackStatsParameters.ALGORITHM_VERSION, track.statsVersion)

        val expectedDay0 = TrackStatsCalculator.compute(pointsOf(day0), SpeedCalibration.DEFAULT)
        val expectedDay1 = TrackStatsCalculator.compute(pointsOf(day1), SpeedCalibration.DEFAULT)
        assertEquals(expectedDay0.distanceMeters + expectedDay1.distanceMeters, track.distanceMeters, 1e-6)
        assertEquals(
            expectedDay0.elevationGainMeters + expectedDay1.elevationGainMeters,
            track.elevationGainMeters,
            1e-6,
        )
        assertEquals(
            expectedDay0.elevationLossMeters + expectedDay1.elevationLossMeters,
            track.elevationLossMeters,
            1e-6,
        )
        assertEquals(
            expectedDay0.estimatedDurationMinutes + expectedDay1.estimatedDurationMinutes,
            track.estimatedDurationMinutes,
        )

        // Piège §5.4 : les jours entrent totalement vierges (voir insertLegacyLoggedTrack) et
        // doivent ressortir avec TOUTES leurs colonnes dénormalisées renseignées, pas seulement les
        // quatre statistiques de la trace.
        val days = loggedDao.getDays("track-multi").sortedBy { it.dayIndex }
        assertEquals(2, days.size)
        val expectedAggregate0 = DaySegmentAggregate.of(TrackSegmenter.segment(pointsOf(day0)))
        assertNotNull(days[0].contentHash)
        assertNotNull(days[0].flatCount)
        assertEquals(expectedAggregate0.flatCount, days[0].flatCount)
        assertEquals(expectedAggregate0.flatDistanceMeters, days[0].flatDistanceMeters!!, 1e-6)
        assertEquals(expectedAggregate0.stoppedHours, days[0].stoppedHours!!, 1e-9)
    }

    @Test
    fun secondRunIsANoOpOnceStatsAreBackfilled() = runBlocking {
        insertLegacyLoggedTrack("track-idem", listOf(flatGpx()), statsVersion = 0)
        LoggedTrackBackfill.runStats(context, loggedDao)
        val first = loggedDao.get("track-idem")!!

        LoggedTrackBackfill.runStats(context, loggedDao) // countTracksNeedingStatsBackfill == 0

        val second = loggedDao.get("track-idem")!!
        assertEquals(first, second)
    }

    @Test
    fun leavesATrackAlreadyAtCurrentVersionUntouched() = runBlocking {
        insertLegacyLoggedTrack(
            "track-sentinel",
            listOf(flatGpx()),
            statsVersion = TrackStatsParameters.ALGORITHM_VERSION,
            placeholderValue = -1.0,
        )

        LoggedTrackBackfill.runStats(context, loggedDao)

        val track = loggedDao.get("track-sentinel")!!
        assertEquals(-1.0, track.distanceMeters, 0.0)
        assertEquals(TrackStatsParameters.ALGORITHM_VERSION, track.statsVersion)
    }

    @Test
    fun missingFileKeepsOldValuesButStillAdvancesTheVersion() = runBlocking {
        insertLegacyLoggedTrack("track-missing", listOf(flatGpx()), statsVersion = 0, placeholderValue = 12_345.0)
        LoggedTrackGpxStore.resolve(context, LoggedTrackGpxStore.relativePath("track-missing", 0)).delete()

        LoggedTrackBackfill.runStats(context, loggedDao)

        val track = loggedDao.get("track-missing")!!
        // Conception §5.3 : un total partiel serait pire qu'un total ancien, la trace garde donc
        // TOUTES ses anciennes valeurs...
        assertEquals(12_345.0, track.distanceMeters, 0.0)
        assertEquals(12_345.0, track.elevationGainMeters, 0.0)
        // ... mais la version monte quand même, sinon cette trace serait rejouée à chaque
        // lancement de l'application.
        assertEquals(TrackStatsParameters.ALGORITHM_VERSION, track.statsVersion)
        assertEquals(null, loggedDao.getDays("track-missing").single().contentHash)
    }

    // Conception §6.2 point 3 : interrompre après la première trace (annulation depuis
    // onProgress), vérifier qu'elle seule est à jour, relancer, vérifier que tout est à jour.
    @Test
    fun resumesAfterAnInterruptionAndCompletesTheRemainingTrack() = runBlocking {
        insertLegacyLoggedTrack("track-a", listOf(flatGpx(latOffsetDeg = 0.0)), statsVersion = 0)
        insertLegacyLoggedTrack("track-b", listOf(flatGpx(latOffsetDeg = 0.03)), statsVersion = 0)

        var lastDone = 0
        try {
            LoggedTrackBackfill.runStats(context, loggedDao) { done, _ ->
                lastDone = done
                if (done == 1) throw CancellationException("interruption simulée (test)")
            }
            fail("l'interruption simulée aurait dû interrompre le rattrapage")
        } catch (expected: CancellationException) {
            // attendu : simule l'annulation du scope (JournalViewModel/ElevationBackfillViewModel
            // détruit) après la première trace traitée.
        }
        assertEquals(1, lastDone)

        val afterInterrupt = loggedDao.list().associateBy { it.id }
        assertEquals(1, afterInterrupt.values.count { it.statsVersion == TrackStatsParameters.ALGORITHM_VERSION })
        val alreadyDone = afterInterrupt.values.single { it.statsVersion == TrackStatsParameters.ALGORITHM_VERSION }

        LoggedTrackBackfill.runStats(context, loggedDao)

        val afterResume = loggedDao.list().associateBy { it.id }
        assertTrue(afterResume.values.all { it.statsVersion == TrackStatsParameters.ALGORITHM_VERSION })
        assertEquals(alreadyDone, afterResume.getValue(alreadyDone.id))
    }

    @Test
    fun backfillsBankedTrackStatsWithPlanificationBreaksAndLeavesAnAlreadyCurrentOneUntouched() = runBlocking {
        val gpx = flatGpx()
        PlanificationGpxStore.dir(context).mkdirs()
        val path = PlanificationGpxStore.bankedRelativePath("bank-a")
        PlanificationGpxStore.resolve(context, path).writeText(gpx, StandardCharsets.UTF_8)
        bankedDao.save(
            BankedTrackEntity(
                id = "bank-a", name = "Plan A", gpxFilePath = path, bivouacTrackPointIndices = "",
                distanceMeters = 9_999.0, elevationGainMeters = 9_999.0, elevationLossMeters = 9_999.0,
                estimatedDurationMinutes = 9_999, savedAt = 0L, statsVersion = 0,
            ),
        )
        bankedDao.save(
            BankedTrackEntity(
                id = "bank-sentinel", name = "Plan B", gpxFilePath = path, bivouacTrackPointIndices = "",
                distanceMeters = -1.0, elevationGainMeters = -1.0, elevationLossMeters = -1.0,
                estimatedDurationMinutes = -1, savedAt = 0L, statsVersion = TrackStatsParameters.ALGORITHM_VERSION,
            ),
        )

        bankedRepository.backfillStatsFields()

        val backfilled = bankedDao.get("bank-a")!!
        assertEquals(TrackStatsParameters.ALGORITHM_VERSION, backfilled.statsVersion)
        val expected = TrackStatsCalculator.compute(pointsOf(gpx), SpeedCalibration.DEFAULT)
        assertEquals(expected.distanceMeters, backfilled.distanceMeters, 1e-6)
        assertEquals(expected.elevationGainMeters, backfilled.elevationGainMeters, 1e-6)

        val sentinel = bankedDao.get("bank-sentinel")!!
        assertEquals(-1.0, sentinel.distanceMeters, 0.0)
        assertEquals(TrackStatsParameters.ALGORITHM_VERSION, sentinel.statsVersion)
    }

    // Conception §6.2 point 6 : Auto et Sélection recalculées (ids conservés), Manuel intact,
    // préférence de version posée ; relancer sans rien de neuf à rattraper ne réécrit rien.
    @Test
    fun recalibratesAutoAndSelectionButNeverManualThenBecomesANoOp() = runBlocking {
        insertLegacyLoggedTrack("cal-a", listOf(flatGpx(latOffsetDeg = 0.0)), statsVersion = 0)
        insertLegacyLoggedTrack("cal-b", listOf(flatGpx(latOffsetDeg = 0.02)), statsVersion = 0)
        LoggedTrackBackfill.runStats(context, loggedDao)

        settingsPreferences.setManualCalibration(
            walkingSpeedKmh = 3.0,
            elevationGainPenaltyMetersPerKm = 120.0,
            pauseFractionPercent = 10.0,
        )
        settingsPreferences.setSelectionCalibration(SpeedCalibration(2.0, 80.0, 0.0), setOf("cal-a"))

        CalibrationRefresh.refreshIfNeeded(loggedRepository, settingsPreferences, staleJournalStatsBackfilled = true)

        val expectedAutoInput = loggedRepository.calibrationSamples()
        val expectedAuto =
            SpeedCalibrationCalculator.compute(expectedAutoInput.aggregate, expectedAutoInput.fallbackSamples)
        assertNotNull("le repli vitesse seule doit aboutir sur ce jeu synthétique", expectedAuto)
        assertEquals(
            expectedAuto!!.calibration.walkingSpeedKmh,
            settingsPreferences.autoCalibration.first().walkingSpeedKmh,
            1e-9,
        )

        val expectedSelectionInput = loggedRepository.calibrationSamples(setOf("cal-a"))
        val expectedSelection =
            SpeedCalibrationCalculator.compute(expectedSelectionInput.aggregate, expectedSelectionInput.fallbackSamples)
        assertEquals(
            (expectedSelection?.calibration ?: SpeedCalibration.DEFAULT).walkingSpeedKmh,
            settingsPreferences.selectionCalibration.first().walkingSpeedKmh,
            1e-9,
        )
        assertEquals(setOf("cal-a"), settingsPreferences.selectedTrackIds.first())

        // Manuel intouché.
        assertEquals(3.0, settingsPreferences.manualCalibration.first().walkingSpeedKmh, 1e-9)
        assertEquals(TrackStatsParameters.ALGORITHM_VERSION, settingsPreferences.calibrationStatsVersion.first())

        // Sentinelle : sans travail à faire, un second appel ne doit RIEN réécrire, pas même
        // relire le Journal pour retomber sur la même valeur par coïncidence.
        settingsPreferences.setSelectionCalibration(SpeedCalibration(-42.0, -42.0, -42.0), setOf("sentinel-id"))
        CalibrationRefresh.refreshIfNeeded(loggedRepository, settingsPreferences, staleJournalStatsBackfilled = false)
        assertEquals(-42.0, settingsPreferences.selectionCalibration.first().walkingSpeedKmh, 1e-9)
        assertEquals(setOf("sentinel-id"), settingsPreferences.selectedTrackIds.first())
    }
}

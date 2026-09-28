package com.bivouac.app.data.db

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.gpx.DaySegmentSums
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.PaceBandSum
import com.bivouac.app.data.gpx.TrackStatsParameters
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.prefs.SpeedCalibrationMode
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-146 lot 1 : pausedSeconds et les lignes de logged_track_day_pace sont écrits à l'import et au
 * rattrapage (ALGORITHM_VERSION 3), dans la même transaction que les sommes de calibration, et un
 * second rattrapage ne change rien. Même installation que TrackStatsBackfillTest : singleton remis
 * à zéro, GPX synthétiques écrits dans LoggedTrackGpxStore.
 */
@RunWith(RobolectricTestRunner::class)
class PaceAndPauseStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dao get() = BivouacDatabase.getInstance(context).loggedTrackDao()
    private val repository get() = LoggedTrackRepository(context)

    @Before
    fun resetSingleton() {
        BivouacDatabase.closeAndReset()
    }

    @After
    fun tearDown() {
        LoggedTrackGpxStore.dir(context).deleteRecursively()
        BivouacDatabase.closeAndReset()
    }

    // Journée vallonnée de 6 km environ, un point tous les 30 m, avec deux arrêts de 4 minutes :
    // assez de segments pour remplir plusieurs bandes de pente, et des pauses à détecter.
    private fun hillyGpx(latOffsetDeg: Double = 0.0, baseTimeIso: String = "2026-06-01T08:00:00Z"): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\"><trk><name>t</name><trkseg>\n")
            val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)
            val baseTime = Instant.parse(baseTimeIso)
            var seconds = 0L
            fun point(meters: Double, elevation: Double) {
                val lat = 45.0 + latOffsetDeg + meters * degPerMeter
                append("<trkpt lat=\"$lat\" lon=\"6.0\"><ele>$elevation</ele><time>${baseTime.plusSeconds(seconds)}</time></trkpt>\n")
            }
            for (i in 0 until 200) {
                val elevation = 1000.0 + 150.0 * sin(i * 0.04)
                point(i * 30.0, elevation)
                if (i == 60 || i == 140) {
                    repeat(12) { k ->
                        seconds += 20
                        point(i * 30.0 + (k % 2), elevation)
                    }
                }
                seconds += 27L + (i % 5) * 6L
            }
            append("</trkseg></trk></gpx>")
        }

    private fun pointsOf(gpx: String): List<TrackPoint> =
        gpx.byteInputStream(StandardCharsets.UTF_8).use { GpxParser.parse(it) }.points

    private suspend fun LoggedTrackDao.pacesOf(dayId: Long): List<PaceBandSum> =
        getDayPaces(dayId).map { PaceBandSum(it.band, it.segmentCount, it.distanceMeters, it.movingSeconds) }

    // Trace déjà rattrapée à la version 2 : sommes de calibration de l'ancienne définition,
    // pausedSeconds nul, aucune ligne de rythme. C'est l'état d'une installation 2.5.1 après la
    // migration 20 vers 21, et celui d'une sauvegarde de schéma 20 restaurée.
    private suspend fun insertVersion2Track(trackId: String, dayGpxContents: List<String>) {
        dao.insertTrack(
            LoggedTrackEntity(
                id = trackId, name = "Trace $trackId", startedAt = 0L, contentHash = "hash-$trackId",
                distanceMeters = 1.0, elevationGainMeters = 1.0, elevationLossMeters = 1.0, pointCount = 0,
                estimatedDurationMinutes = 1, statsVersion = 2,
            ),
        )
        LoggedTrackGpxStore.dir(context).mkdirs()
        val days = dayGpxContents.mapIndexed { index, gpx ->
            val relativePath = LoggedTrackGpxStore.relativePath(trackId, index)
            LoggedTrackGpxStore.resolve(context, relativePath).writeText(gpx, StandardCharsets.UTF_8)
            LoggedTrackDayEntity(
                trackId = trackId, dayIndex = index, rawGpxFilePath = relativePath, contentHash = "old-$index",
                flatCount = 99, flatDistanceMeters = 99.0, flatHours = 99.0, steepCount = 99,
                steepDistanceMeters = 99.0, steepGainMeters = 99.0, steepHours = 99.0, stoppedHours = 99.0,
                elevationBackfilled = true, distanceMeters = 1.0, elevationGainMeters = 1.0, elevationLossMeters = 1.0,
            )
        }
        dao.insertDays(days)
    }

    @Test
    fun statsBackfillWritesPausesPaceRowsAndFinePauseCalibrationSums() = runBlocking {
        val day0 = hillyGpx()
        val day1 = hillyGpx(latOffsetDeg = 0.02, baseTimeIso = "2026-06-02T08:00:00Z")
        insertVersion2Track("track-v2", listOf(day0, day1))

        LoggedTrackBackfill.runStats(context, dao)

        assertEquals(3, TrackStatsParameters.ALGORITHM_VERSION)
        assertEquals(TrackStatsParameters.ALGORITHM_VERSION, dao.get("track-v2")!!.statsVersion)
        val days = dao.getDays("track-v2")
        for ((day, gpx) in days.zip(listOf(day0, day1))) {
            val expected = DaySegmentSums.of(pointsOf(gpx))
            assertTrue("le jour de test doit avoir des pauses", expected.pausedSeconds > 0.0)
            assertTrue("et plusieurs bandes de pente", expected.paceBands.size >= 3)
            assertEquals(expected.pausedSeconds, day.pausedSeconds!!, 1e-9)
            assertEquals(expected.aggregate.flatCount, day.flatCount)
            assertEquals(expected.aggregate.flatHours, day.flatHours!!, 1e-12)
            assertEquals(expected.aggregate.steepHours, day.steepHours!!, 1e-12)
            assertEquals(expected.aggregate.stoppedHours, day.stoppedHours!!, 1e-12)
            assertEquals(expected.paceBands, dao.pacesOf(day.id))
        }
    }

    @Test
    fun aSecondBackfillChangesNothing() = runBlocking {
        insertVersion2Track("track-idem", listOf(hillyGpx()))
        LoggedTrackBackfill.runStats(context, dao)
        val dayId = dao.getDays("track-idem").single().id
        val firstDay = dao.getDays("track-idem").single()
        val firstPaces = dao.getDayPaces(dayId)

        // Rien à rattraper : aucune écriture.
        LoggedTrackBackfill.runStats(context, dao)
        assertEquals(firstDay, dao.getDays("track-idem").single())
        assertEquals(firstPaces, dao.getDayPaces(dayId))

        // Rattrapage forcé (version redescendue) : mêmes valeurs, et les lignes de rythme sont
        // remplacées, pas ajoutées.
        dao.markStatsVersion("track-idem", 2)
        LoggedTrackBackfill.runStats(context, dao)
        assertEquals(firstDay, dao.getDays("track-idem").single())
        assertEquals(firstPaces, dao.getDayPaces(dayId))
    }

    @Test
    fun importWritesPausesAndPaceRowsInTheSameCommit() = runBlocking {
        val day0 = hillyGpx()
        val day1 = hillyGpx(latOffsetDeg = 0.02, baseTimeIso = "2026-06-02T08:00:00Z")
        val uri0 = Uri.parse("content://test/pace/day0")
        val uri1 = Uri.parse("content://test/pace/day1")
        val resolver = context.contentResolver
        shadowOf(resolver).registerInputStreamSupplier(uri0) { ByteArrayInputStream(day0.toByteArray(StandardCharsets.UTF_8)) }
        shadowOf(resolver).registerInputStreamSupplier(uri1) { ByteArrayInputStream(day1.toByteArray(StandardCharsets.UTF_8)) }

        val trackId = repository.commitImport(repository.prepareImport(resolver, listOf(uri0, uri1)))

        val days = dao.getDays(trackId)
        assertEquals(2, days.size)
        for ((day, gpx) in days.zip(listOf(day0, day1))) {
            val expected = DaySegmentSums.of(pointsOf(gpx))
            assertNotNull(day.pausedSeconds)
            assertEquals(expected.pausedSeconds, day.pausedSeconds!!, 1e-9)
            assertEquals(expected.aggregate.stoppedHours, day.stoppedHours!!, 1e-12)
            assertEquals(expected.paceBands, dao.pacesOf(day.id))
        }
        // Une trace importée est déjà à la version courante : le rattrapage ne la reprend pas.
        assertEquals(TrackStatsParameters.ALGORITHM_VERSION, dao.get(trackId)!!.statsVersion)

        // Suppression en cascade : la trace emporte ses jours, qui emportent leurs lignes de rythme.
        repository.delete(trackId)
        assertTrue(days.all { dao.getDayPaces(it.id).isEmpty() })
    }

    @Test
    fun legacyDenormalizedBackfillAlsoWritesPausesAndPaceRows() = runBlocking {
        // Jour d'avant RIC-109 (aucune colonne dénormalisée) : LoggedTrackBackfill.run le reprend,
        // et doit écrire les mêmes sommes que l'import.
        val gpx = hillyGpx()
        dao.insertTrack(
            LoggedTrackEntity(
                id = "legacy", name = "Legacy", startedAt = 0L, contentHash = "hash-legacy",
                distanceMeters = 0.0, elevationGainMeters = 0.0, elevationLossMeters = 0.0, pointCount = 0,
                estimatedDurationMinutes = 0,
            ),
        )
        LoggedTrackGpxStore.dir(context).mkdirs()
        val path = LoggedTrackGpxStore.relativePath("legacy", 0)
        LoggedTrackGpxStore.resolve(context, path).writeText(gpx, StandardCharsets.UTF_8)
        dao.insertDays(listOf(LoggedTrackDayEntity(trackId = "legacy", dayIndex = 0, rawGpxFilePath = path)))

        LoggedTrackBackfill.run(context, dao)

        val day = dao.getDays("legacy").single()
        val expected = DaySegmentSums.of(pointsOf(gpx))
        assertEquals(expected.pausedSeconds, day.pausedSeconds!!, 1e-9)
        assertEquals(expected.aggregate.stoppedHours, day.stoppedHours!!, 1e-12)
        assertEquals(expected.paceBands, dao.pacesOf(day.id))
    }

    @Test
    fun paceBandSumsAddUpTheReferenceTracksAndExcludeTheAnalysedOne() = runBlocking {
        insertVersion2Track("a", listOf(hillyGpx(latOffsetDeg = 0.0)))
        insertVersion2Track("b", listOf(hillyGpx(latOffsetDeg = 0.02)))
        insertVersion2Track("c", listOf(hillyGpx(latOffsetDeg = 0.04), hillyGpx(latOffsetDeg = 0.05)))
        LoggedTrackBackfill.runStats(context, dao)

        suspend fun expectedFor(vararg trackIds: String): List<PaceBandSum> =
            trackIds.flatMap { id -> dao.getDays(id).flatMap { dao.pacesOf(it.id) } }
                .groupBy { it.band }
                .map { (band, rows) ->
                    PaceBandSum(band, rows.sumOf { it.segmentCount }, rows.sumOf { it.distanceMeters }, rows.sumOf { it.movingSeconds })
                }
                .sortedBy { it.band }

        fun assertSameSums(expected: List<PaceBandSum>, actual: List<PaceBandSum>) {
            assertEquals(expected.map { it.band }, actual.map { it.band })
            assertEquals(expected.map { it.segmentCount }, actual.map { it.segmentCount })
            expected.zip(actual).forEach { (e, a) ->
                assertEquals(e.distanceMeters, a.distanceMeters, 1e-6)
                assertEquals(e.movingSeconds, a.movingSeconds, 1e-6)
            }
        }

        // Tout le Journal, rando analysée exclue (mode Auto ou Manuel).
        assertSameSums(expectedFor("a", "c"), repository.paceBandSums(trackIds = null, excludedTrackId = "b"))
        // Tout le Journal, sans exclusion.
        assertSameSums(expectedFor("a", "b", "c"), repository.paceBandSums(trackIds = null, excludedTrackId = null))
        // Sélection, la rando analysée en fait partie : elle en est retirée.
        assertSameSums(expectedFor("c"), repository.paceBandSums(trackIds = setOf("a", "c"), excludedTrackId = "a"))
        // Sélection réduite à la rando analysée : aucune référence.
        assertEquals(emptyList<PaceBandSum>(), repository.paceBandSums(trackIds = setOf("a"), excludedTrackId = "a"))
    }

    @Test
    fun analysisReferencePaceBandsResolvesTheReferenceTracksFromTheCalibrationMode() = runBlocking {
        insertVersion2Track("a", listOf(hillyGpx(latOffsetDeg = 0.0)))
        insertVersion2Track("b", listOf(hillyGpx(latOffsetDeg = 0.02)))
        insertVersion2Track("c", listOf(hillyGpx(latOffsetDeg = 0.04)))
        LoggedTrackBackfill.runStats(context, dao)

        // Auto et Manuel : tout le Journal, la rando analysée (b) exclue de sa propre référence.
        val auto = repository.analysisReferencePaceBands(SpeedCalibrationMode.AUTO, selectedTrackIds = emptySet(), analyzedTrackId = "b")
        val manual = repository.analysisReferencePaceBands(SpeedCalibrationMode.MANUAL, selectedTrackIds = emptySet(), analyzedTrackId = "b")
        val wholeJournalExceptB = repository.paceBandSums(trackIds = null, excludedTrackId = "b")
        assertEquals(wholeJournalExceptB, auto)
        assertEquals(wholeJournalExceptB, manual)

        // Sélection : seules les randos sélectionnées, la rando analysée en fait partie et en est
        // retirée (même si elle est aussi la seule autre trace du Journal).
        val selection = repository.analysisReferencePaceBands(
            SpeedCalibrationMode.SELECTION,
            selectedTrackIds = setOf("a", "b"),
            analyzedTrackId = "b",
        )
        assertEquals(repository.paceBandSums(trackIds = setOf("a", "b"), excludedTrackId = "b"), selection)
        // "c" n'est pas dans la sélection : il ne doit apparaître dans aucune des deux ci-dessus.
        assertTrue(selection.isNotEmpty())
        assertEquals(repository.paceBandSums(trackIds = setOf("a"), excludedTrackId = null), selection)
    }
}

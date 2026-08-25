package com.bivouac.app.bilan

import com.bivouac.app.data.db.LoggedTrackDayEntity
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.gpx.SpeedCalibration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-19 : calculateur pur (voir BilanStatsCalculator), testé comme TrackStatsCalculator/
 * SpeedCalibrationCalculator — pas de Robolectric nécessaire, aucune dépendance Android.
 */
class BilanStatsCalculatorTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun millisAt(year: Int, month: Int, day: Int = 15): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun track(
        id: String,
        year: Int,
        month: Int,
        day: Int = 15,
        name: String = id,
        distanceMeters: Double = 10_000.0,
        elevationGainMeters: Double = 500.0,
    ) = LoggedTrackEntity(
        id = id,
        name = name,
        startedAt = millisAt(year, month, day),
        contentHash = "hash-$id",
        distanceMeters = distanceMeters,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationGainMeters,
        pointCount = 10,
        estimatedDurationMinutes = 120,
    )

    private fun day(
        trackId: String,
        dayIndex: Int,
        startedAtMillis: Long? = null,
        flatDistanceMeters: Double? = null,
        flatHours: Double? = null,
        steepCount: Int? = null,
        steepDistanceMeters: Double? = null,
        steepGainMeters: Double? = null,
        steepHours: Double? = null,
        maxElevationMeters: Double? = null,
        lastPointElevationMeters: Double? = null,
    ) = LoggedTrackDayEntity(
        id = (trackId.hashCode().toLong() * 100) + dayIndex,
        trackId = trackId,
        dayIndex = dayIndex,
        rawGpxFilePath = "gpx/$trackId-day$dayIndex.gpx",
        startedAtMillis = startedAtMillis,
        flatCount = if (flatDistanceMeters != null) 20 else null,
        flatDistanceMeters = flatDistanceMeters,
        flatHours = flatHours,
        steepCount = steepCount,
        steepDistanceMeters = steepDistanceMeters,
        steepGainMeters = steepGainMeters,
        steepHours = steepHours,
        maxElevationMeters = maxElevationMeters,
        lastPointElevationMeters = lastPointElevationMeters,
        elevationBackfilled = true,
    )

    @Test
    fun emptyJournalHasNoRecordsAndNoProgression() {
        val stats = BilanStatsCalculator.compute(emptyList(), emptyMap(), SpeedCalibration.DEFAULT, zone)

        assertEquals(0, stats.totalCount)
        assertTrue(stats.progression.isEmpty())
        assertNull(stats.mostActiveMonthInsight)
        assertNull(stats.kmEffortRecord)
        assertNull(stats.vamRecord)
        assertNull(stats.maxAltitudeRecord)
        assertNull(stats.highestBivouacRecord)
        assertNull(stats.maxDistanceDayRecord)
        assertNull(stats.maxGainDayRecord)
        assertNull(stats.longestTrekRecord)
    }

    @Test
    fun totalsSumAcrossTracksAndBivouacCountFollowsDayCountMinusOne() {
        val trekDays = listOf(day("trek", 0), day("trek", 1), day("trek", 2)) // 2 bivouacs
        val singleDay = listOf(day("single", 0)) // 0 bivouac
        val tracks = listOf(
            track("trek", 2025, 7, distanceMeters = 50_000.0, elevationGainMeters = 2_000.0),
            track("single", 2025, 8, distanceMeters = 10_000.0, elevationGainMeters = 300.0),
        )
        val stats = BilanStatsCalculator.compute(
            tracks,
            mapOf("trek" to trekDays, "single" to singleDay),
            SpeedCalibration.DEFAULT,
            zone,
            now = YearMonth.of(2025, 8),
        )

        assertEquals(2, stats.totalCount)
        assertEquals(60_000.0, stats.totals.distanceMeters, 1e-9)
        assertEquals(2_300.0, stats.totals.elevationGainMeters, 1e-9)
        assertEquals(2, stats.bivouacCount)
    }

    @Test
    fun kmEffortRecordPicksHighestEquivalentDistance() {
        val tracks = listOf(
            track("small", 2025, 5),
            track("steep", 2025, 6),
        )
        val daysByTrackId = mapOf(
            // 10 km + 500 m / 100 m/km = 15 km-eff.
            "small" to listOf(day("small", 0, flatDistanceMeters = 6_000.0, steepDistanceMeters = 4_000.0, steepGainMeters = 500.0)),
            // 8 km + 2000 m / 100 m/km = 28 km-eff. -> gagne malgré une distance plus courte
            "steep" to listOf(day("steep", 0, flatDistanceMeters = 3_000.0, steepDistanceMeters = 5_000.0, steepGainMeters = 2_000.0)),
        )
        val stats = BilanStatsCalculator.compute(tracks, daysByTrackId, SpeedCalibration.DEFAULT, zone)

        assertEquals("steep", stats.kmEffortRecord?.trackId)
        assertEquals(28.0, stats.kmEffortRecord!!.value, 1e-9)
    }

    @Test
    fun kmEffortRecordComparesSingleDaysNotWholeTreks() {
        val tracks = listOf(
            // Trek de 3 jours modérés : gros total cumulé, mais aucune journée individuelle très
            // engagée. Avant ce correctif, kmEffortRecord comparait le total du trek (30 km-eff.
            // sur l'ensemble) à la sortie ci-dessous et gagnait à tort.
            track("trek", 2025, 7, distanceMeters = 30_000.0, elevationGainMeters = 1_500.0),
            // Une seule sortie très engagée, mais plus courte que le trek cumulé.
            track("single", 2025, 8, distanceMeters = 8_000.0, elevationGainMeters = 2_000.0),
        )
        val daysByTrackId = mapOf(
            "trek" to listOf(
                // 10 km + 500 m / 100 m/km = 15 km-eff. par jour, jamais plus.
                day("trek", 0, flatDistanceMeters = 6_000.0, steepDistanceMeters = 4_000.0, steepGainMeters = 500.0),
                day("trek", 1, flatDistanceMeters = 6_000.0, steepDistanceMeters = 4_000.0, steepGainMeters = 500.0),
                day("trek", 2, flatDistanceMeters = 6_000.0, steepDistanceMeters = 4_000.0, steepGainMeters = 500.0),
            ),
            // 8 km + 2000 m / 100 m/km = 28 km-eff., largement au-dessus de chaque jour du trek.
            "single" to listOf(day("single", 0, flatDistanceMeters = 3_000.0, steepDistanceMeters = 5_000.0, steepGainMeters = 2_000.0)),
        )
        val stats = BilanStatsCalculator.compute(tracks, daysByTrackId, SpeedCalibration.DEFAULT, zone)

        assertEquals("single", stats.kmEffortRecord?.trackId)
        assertEquals(28.0, stats.kmEffortRecord!!.value, 1e-9)
    }

    @Test
    fun vamRecordIgnoresDaysBelowTheSharedSpeedCalibrationGuard() {
        val tracks = listOf(track("weak", 2025, 5), track("strong", 2025, 6))
        val daysByTrackId = mapOf(
            // Sous le seuil MIN_STEEP_SEGMENTS (10) : écarté même si le ratio serait spectaculaire.
            "weak" to listOf(day("weak", 0, steepCount = 3, steepGainMeters = 400.0, steepHours = 0.1)),
            "strong" to listOf(day("strong", 0, steepCount = 15, steepGainMeters = 900.0, steepHours = 1.5)),
        )
        val stats = BilanStatsCalculator.compute(tracks, daysByTrackId, SpeedCalibration.DEFAULT, zone)

        assertEquals("strong", stats.vamRecord?.trackId)
        assertEquals(600.0, stats.vamRecord!!.value, 1e-9)
    }

    @Test
    fun vamRecordIsNullWhenNoDayMeetsTheGainGuard() {
        val tracks = listOf(track("flatish", 2025, 5))
        // Assez de segments, mais D+ sous MIN_TOTAL_GAIN_METERS (300 m).
        val daysByTrackId = mapOf("flatish" to listOf(day("flatish", 0, steepCount = 12, steepGainMeters = 200.0, steepHours = 1.0)))

        val stats = BilanStatsCalculator.compute(tracks, daysByTrackId, SpeedCalibration.DEFAULT, zone)

        assertNull(stats.vamRecord)
    }

    @Test
    fun maxAltitudeRecordPicksTheHighestDayIgnoringUnbackfilledOnes() {
        val tracks = listOf(track("a", 2025, 5), track("b", 2025, 6))
        val daysByTrackId = mapOf(
            "a" to listOf(day("a", 0, maxElevationMeters = 2_000.0)),
            // Pas encore rattrapé (null) : ne doit jamais gagner par défaut.
            "b" to listOf(day("b", 0, maxElevationMeters = null), day("b", 1, maxElevationMeters = 2_800.0)),
        )
        val stats = BilanStatsCalculator.compute(tracks, daysByTrackId, SpeedCalibration.DEFAULT, zone)

        assertEquals("b", stats.maxAltitudeRecord?.trackId)
        assertEquals(2_800.0, stats.maxAltitudeRecord!!.value, 1e-9)
    }

    @Test
    fun highestBivouacRecordExcludesTheLastDayOfATrackAndSingleDayTracks() {
        val tracks = listOf(track("trek", 2025, 7), track("solo", 2025, 8))
        val daysByTrackId = mapOf(
            "trek" to listOf(
                day("trek", 0, lastPointElevationMeters = 1_800.0), // bivouac après le jour 0
                day("trek", 1, lastPointElevationMeters = 2_400.0), // bivouac après le jour 1
                // Dernier jour du trek : son dernier point est la sortie, pas un bivouac, même si
                // son altitude serait la plus haute de toutes.
                day("trek", 2, lastPointElevationMeters = 3_500.0),
            ),
            // Une seule journée : jamais de bivouac, quelle que soit son altitude.
            "solo" to listOf(day("solo", 0, lastPointElevationMeters = 4_000.0)),
        )
        val stats = BilanStatsCalculator.compute(tracks, daysByTrackId, SpeedCalibration.DEFAULT, zone)

        assertEquals("trek", stats.highestBivouacRecord?.trackId)
        assertEquals(2_400.0, stats.highestBivouacRecord!!.value, 1e-9)
        assertEquals(1, stats.highestBivouacRecord?.dayIndex)
    }

    @Test
    fun maxDistanceAndGainDayRecordsUseSegmentSums() {
        val tracks = listOf(track("trek", 2025, 7))
        val daysByTrackId = mapOf(
            "trek" to listOf(
                day("trek", 0, flatDistanceMeters = 5_000.0, steepDistanceMeters = 2_000.0, steepGainMeters = 400.0),
                day("trek", 1, flatDistanceMeters = 8_000.0, steepDistanceMeters = 6_000.0, steepGainMeters = 1_800.0),
            ),
        )
        val stats = BilanStatsCalculator.compute(tracks, daysByTrackId, SpeedCalibration.DEFAULT, zone)

        assertEquals(14.0, stats.maxDistanceDayRecord!!.value, 1e-9) // (8000+6000)/1000
        assertEquals(1_800.0, stats.maxGainDayRecord!!.value, 1e-9)
        assertNull("mono-jour, pas de positionnement day-level", stats.maxDistanceDayRecord?.dayIndex)
    }

    @Test
    fun longestTrekRecordRequiresMoreThanOneDayAndCarriesTrekTotals() {
        val tracks = listOf(
            track("solo", 2025, 5, distanceMeters = 20_000.0),
            track("trek", 2025, 6, distanceMeters = 142_000.0, elevationGainMeters = 6_240.0),
        )
        val daysByTrackId = mapOf(
            "solo" to listOf(day("solo", 0)),
            "trek" to (0..8).map { day("trek", it) }, // 9 jours
        )
        val stats = BilanStatsCalculator.compute(tracks, daysByTrackId, SpeedCalibration.DEFAULT, zone)

        assertEquals("trek", stats.longestTrekRecord?.trackId)
        assertEquals(9.0, stats.longestTrekRecord!!.value, 1e-9)
        assertEquals(0, stats.longestTrekRecord?.dayIndex) // ouvre au premier jour du trek
        assertEquals(142.0, stats.longestTrekRecord!!.extraDistanceKm!!, 1e-9)
        assertEquals(6_240.0, stats.longestTrekRecord!!.extraGainMeters!!, 1e-9)
    }

    @Test
    fun insightIsNullWithFewerThanTwoDistinctCalendarMonths() {
        val tracks = listOf(track("a", 2025, 7, day = 3), track("b", 2025, 7, day = 20))
        val stats = BilanStatsCalculator.compute(tracks, emptyMap(), SpeedCalibration.DEFAULT, zone)

        assertNull(stats.mostActiveMonthInsight)
    }

    @Test
    fun insightPicksTheMonthOfYearWithMostCumulativeOutingsAcrossYears() {
        val tracks = listOf(
            track("a", 2021, 7),
            track("b", 2022, 7),
            track("c", 2022, 8),
            track("d", 2023, 8),
            track("e", 2024, 8),
        )
        val stats = BilanStatsCalculator.compute(tracks, emptyMap(), SpeedCalibration.DEFAULT, zone)

        val insight = requireNotNull(stats.mostActiveMonthInsight)
        assertEquals(8, insight.monthOfYear)
        assertEquals(3, insight.cumulativeCount)
        assertEquals(2021, insight.sinceYear)
    }

    @Test
    fun progressionCoversEveryCalendarMonthFromFirstEntryToNowWithoutGaps() {
        val tracks = listOf(track("a", 2025, 3), track("b", 2025, 6))
        val stats = BilanStatsCalculator.compute(
            tracks,
            emptyMap(),
            SpeedCalibration.DEFAULT,
            zone,
            now = YearMonth.of(2025, 6),
        )

        val sorties = stats.progression.first { it.metric == ProgressionMetric.SORTIES }
        // Mars, avril, mai, juin : 4 mois, y compris les deux mois sans aucune sortie.
        assertEquals(4, sorties.points.size)
        assertEquals(YearMonth.of(2025, 3), sorties.points.first().yearMonth)
        assertEquals(YearMonth.of(2025, 6), sorties.points.last().yearMonth)
        assertEquals(1.0, sorties.points[0].value)
        assertEquals(0.0, sorties.points[1].value) // avril : aucune sortie, 0 et pas un trou
        assertEquals(0.0, sorties.points[2].value)
        assertEquals(1.0, sorties.points[3].value)
    }

    @Test
    fun progressionSpeedMetricForwardFillsMonthsWithoutMeasurableFlatSpeed() {
        val tracks = listOf(track("a", 2025, 3), track("b", 2025, 5))
        val daysByTrackId = mapOf(
            "a" to listOf(day("a", 0, flatDistanceMeters = 4_000.0, flatHours = 1.0)), // 4 km/h
            // Mai : sortie réelle mais sans plat mesurable (flatHours absent) -> doit reporter mars.
            "b" to listOf(day("b", 0, flatDistanceMeters = null, flatHours = null)),
        )
        val stats = BilanStatsCalculator.compute(
            tracks,
            daysByTrackId,
            SpeedCalibration.DEFAULT,
            zone,
            now = YearMonth.of(2025, 5),
        )

        val vitesse = stats.progression.first { it.metric == ProgressionMetric.VITESSE }
        assertEquals(3, vitesse.points.size) // mars, avril, mai
        assertEquals(4.0, vitesse.points[0].value!!, 1e-9)
        assertEquals(4.0, vitesse.points[1].value!!, 1e-9) // avril : reporté (aucune sortie)
        assertEquals(4.0, vitesse.points[2].value!!, 1e-9) // mai : reporté (sortie sans plat mesurable)
    }
}

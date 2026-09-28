package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * RIC-146 lot 3 : TrackAnalysisMapMapping (brief lot 3 §3, conversion des index de jour vers la
 * liste de l'écran, et regroupement des tronçons en polylignes).
 */
class TrackAnalysisMapMappingTest {

    private fun segment(
        startIndex: Int,
        endIndex: Int,
        paceClass: Int?,
    ) = AnalyzedSegment(
        startIndex = startIndex,
        endIndex = endIndex,
        distanceMeters = 200.0,
        netSlopePercent = 0.0,
        slopeClass = 0,
        movingSeconds = 100.0,
        movingSpeedKmh = 3.0,
        referenceSpeedKmh = 3.0,
        paceClass = paceClass,
        speedClass = paceClass,
    )

    // --- dayOffsets / toScreenIndex --------------------------------------------------------------

    @Test
    fun dayOffsetsGivesTheFirstScreenIndexOfEachDay() {
        // Trois jours de 10, 5 et 8 points : le jour 0 commence à l'index 0 de l'écran, le jour 1 à
        // 10 (après les 10 points du jour 0), le jour 2 à 15 (10 + 5).
        val offsets = TrackAnalysisMapMapping.dayOffsets(listOf(10, 5, 8))
        assertEquals(listOf(0, 10, 15), offsets)
    }

    @Test
    fun dayOffsetsOfASingleDayIsJustZero() {
        assertEquals(listOf(0), TrackAnalysisMapMapping.dayOffsets(listOf(42)))
    }

    @Test
    fun toScreenIndexAddsTheDaysOffset() {
        val offsets = TrackAnalysisMapMapping.dayOffsets(listOf(10, 5, 8))
        // Un tronçon local à l'index 3 du jour 1 (2e jour) tombe à l'index 13 de l'écran (10 + 3).
        assertEquals(13, TrackAnalysisMapMapping.toScreenIndex(offsets, dayIndex = 1, localIndex = 3))
        assertEquals(0, TrackAnalysisMapMapping.toScreenIndex(offsets, dayIndex = 0, localIndex = 0))
    }

    // --- colorGroups -------------------------------------------------------------------------------

    @Test
    fun consecutiveSegmentsOfTheSameClassMergeIntoOneGroup() {
        val segments = listOf(
            segment(0, 1, paceClass = 2),
            segment(1, 2, paceClass = 2),
            segment(2, 3, paceClass = 2),
        )
        val offsets = TrackAnalysisMapMapping.dayOffsets(listOf(4))
        val groups = TrackAnalysisMapMapping.colorGroups(segments, offsets, dayIndex = 0) { it.paceClass }
        assertEquals(1, groups.size)
        assertEquals(TrackAnalysisMapMapping.ColorGroup(0, 3, 2), groups.single())
    }

    @Test
    fun aClassChangeStartsANewGroup() {
        val segments = listOf(
            segment(0, 1, paceClass = 2),
            segment(1, 2, paceClass = 3),
            segment(2, 3, paceClass = 3),
        )
        val offsets = TrackAnalysisMapMapping.dayOffsets(listOf(4))
        val groups = TrackAnalysisMapMapping.colorGroups(segments, offsets, dayIndex = 0) { it.paceClass }
        assertEquals(
            listOf(
                TrackAnalysisMapMapping.ColorGroup(0, 1, 2),
                TrackAnalysisMapMapping.ColorGroup(1, 3, 3),
            ),
            groups,
        )
    }

    @Test
    fun consecutiveUnclassedSegmentsMergeAsOneNeutralGroup() {
        // Un troncon sans classe (null) prend la couleur neutre (brief §3) : deux null consécutifs
        // fusionnent comme deux tronçons de même classe le feraient.
        val segments = listOf(
            segment(0, 1, paceClass = null),
            segment(1, 2, paceClass = null),
        )
        val offsets = TrackAnalysisMapMapping.dayOffsets(listOf(3))
        val groups = TrackAnalysisMapMapping.colorGroups(segments, offsets, dayIndex = 0) { it.paceClass }
        assertEquals(TrackAnalysisMapMapping.ColorGroup(0, 2, null), groups.single())
    }

    @Test
    fun screenIndicesOfTheSecondDayAreOffsetByThePreviousDaysPoints() {
        val segments = listOf(segment(0, 2, paceClass = 1))
        val offsets = TrackAnalysisMapMapping.dayOffsets(listOf(10, 5))
        val groups = TrackAnalysisMapMapping.colorGroups(segments, offsets, dayIndex = 1) { it.paceClass }
        assertEquals(TrackAnalysisMapMapping.ColorGroup(10, 12, 1), groups.single())
    }

    // --- pauseMarkerKind (conception section 7.2) --------------------------------------------------

    @Test
    fun aPauseUnderFiveMinutesIsADot() {
        assertEquals(TrackAnalysisMapMapping.PauseMarkerKind.DOT, TrackAnalysisMapMapping.pauseMarkerKind(299.0))
    }

    @Test
    fun aPauseOfFiveMinutesIsAnIconWithoutDuration() {
        assertEquals(TrackAnalysisMapMapping.PauseMarkerKind.ICON, TrackAnalysisMapMapping.pauseMarkerKind(300.0))
    }

    @Test
    fun aPauseJustUnderTenMinutesIsStillAnIconWithoutDuration() {
        assertEquals(TrackAnalysisMapMapping.PauseMarkerKind.ICON, TrackAnalysisMapMapping.pauseMarkerKind(599.0))
    }

    @Test
    fun aPauseOfTenMinutesShowsItsDuration() {
        assertEquals(
            TrackAnalysisMapMapping.PauseMarkerKind.ICON_WITH_DURATION,
            TrackAnalysisMapMapping.pauseMarkerKind(600.0),
        )
    }

    // --- dayAndLocalIndex (lot 4, inverse de toScreenIndex) -----------------------------------------

    @Test
    fun dayAndLocalIndexFindsTheDayContainingTheScreenIndex() {
        val offsets = TrackAnalysisMapMapping.dayOffsets(listOf(10, 5, 8))
        assertEquals(0 to 0, TrackAnalysisMapMapping.dayAndLocalIndex(offsets, 0))
        assertEquals(0 to 9, TrackAnalysisMapMapping.dayAndLocalIndex(offsets, 9))
        assertEquals(1 to 0, TrackAnalysisMapMapping.dayAndLocalIndex(offsets, 10))
        assertEquals(2 to 3, TrackAnalysisMapMapping.dayAndLocalIndex(offsets, 18))
    }

    // --- segmentAt (lot 4, ligne de lecture du profil) ----------------------------------------------

    @Test
    fun segmentAtFindsTheSegmentCoveringTheLocalIndex() {
        val segments = listOf(segment(0, 3, paceClass = 1), segment(3, 6, paceClass = 2))
        assertEquals(segments[0], TrackAnalysisMapMapping.segmentAt(segments, 0))
        assertEquals(segments[0], TrackAnalysisMapMapping.segmentAt(segments, 2))
        assertEquals(segments[1], TrackAnalysisMapMapping.segmentAt(segments, 3))
        assertEquals(segments[1], TrackAnalysisMapMapping.segmentAt(segments, 6))
    }

    @Test
    fun segmentAtOfAnEmptyListIsNull() {
        assertEquals(null, TrackAnalysisMapMapping.segmentAt(emptyList(), 0))
    }

    // --- elapsedSecondsSinceStart (lot 4, axe en durée, conception section 7.4) ---------------------

    private fun point(time: Instant?) = TrackPoint(latitude = 0.0, longitude = 0.0, elevationMeters = null, time = time)

    private val baseInstant = Instant.parse("2026-08-27T08:00:00Z")

    @Test
    fun elapsedSecondsOfASingleDayCountsFromItsFirstPoint() {
        val points = listOf(
            point(baseInstant),
            point(baseInstant.plusSeconds(60)),
            point(baseInstant.plusSeconds(600)),
        )
        val elapsed = TrackAnalysisMapMapping.elapsedSecondsSinceStart(points, emptyList())
        assertEquals(listOf(0.0, 60.0, 600.0), elapsed?.toList())
    }

    @Test
    fun elapsedSecondsOfTwoDaysSkipsTheNight() {
        // Jour 0 : 0 à 1h (index 0-1). La nuit sépare le point 1 du point 2. Jour 1 reprend à zéro
        // sur son propre écoulé, puis s'ajoute au total du jour 0 (conception "bout à bout, sans
        // la nuit") : le point 3, à 30 min du départ du jour 1, tombe à 1h + 30 min = 1h30.
        val points = listOf(
            point(baseInstant),
            point(baseInstant.plusSeconds(3_600)),
            point(baseInstant.plusSeconds(20 * 3_600)), // le lendemain, bien plus tard
            point(baseInstant.plusSeconds(20 * 3_600 + 1_800)),
        )
        val elapsed = TrackAnalysisMapMapping.elapsedSecondsSinceStart(points, listOf(1))
        assertEquals(listOf(0.0, 3_600.0, 3_600.0, 3_600.0 + 1_800.0), elapsed?.toList())
    }

    @Test
    fun elapsedSecondsIsNullWithoutTimestamps() {
        val points = listOf(point(baseInstant), point(null))
        assertNull(TrackAnalysisMapMapping.elapsedSecondsSinceStart(points, emptyList()))
    }

    // --- clockGridlines (lot 4, graduations de l'axe en durée) --------------------------------------

    @Test
    fun clockGridlinesAreRoundHoursNotTooCloseToEitherEdge() {
        // Reproduit la rando de la maquette validée (2026-09-28) : départ 08:11, arrivée 15:51.
        // 09:00 (49 min du départ) et 15:00 (51 min de l'arrivée) sont trop proches et absents ;
        // 10:00 à 14:00 restent.
        val start = Instant.parse("2026-08-27T08:11:00Z")
        val end = Instant.parse("2026-08-27T15:51:00Z")
        val points = listOf(point(start), point(end))
        val gridlines = TrackAnalysisMapMapping.clockGridlines(points, emptyList(), ZoneId.of("UTC"))
        assertEquals(
            listOf(
                LocalTime.of(10, 0),
                LocalTime.of(11, 0),
                LocalTime.of(12, 0),
                LocalTime.of(13, 0),
                LocalTime.of(14, 0),
            ),
            gridlines?.map { it.time },
        )
    }

    @Test
    fun clockGridlinesWidenTheStepOnALongSingleDayToAvoidOverlap() {
        // RIC-146 lot 5 (brief Partie A.3, défaut cap14-2day-duration-axis.png) : une seule
        // journée de presque 14h (09:12 à 23:05) donnerait 12 marques à l'heure (11h à 22h) une
        // fois les bords retirés, bien plus que MAX_INTERMEDIATE_DURATION_TICKS (5) : le pas
        // s'élargit à 3h, la première valeur de CLOCK_GRIDLINE_STEPS_HOURS qui repasse sous ce
        // plafond, et ne laisse que 4 marques bien espacées.
        val start = Instant.parse("2026-08-27T09:12:00Z")
        val end = Instant.parse("2026-08-27T23:05:00Z")
        val points = listOf(point(start), point(end))
        val gridlines = TrackAnalysisMapMapping.clockGridlines(points, emptyList(), ZoneId.of("UTC"))
        assertEquals(
            listOf(LocalTime.of(12, 0), LocalTime.of(15, 0), LocalTime.of(18, 0), LocalTime.of(21, 0)),
            gridlines?.map { it.time },
        )
    }

    @Test
    fun clockGridlinesWidenTheStepOnTwoDaysToAvoidOverlap() {
        // RIC-146 lot 5 (brief Partie A.3) : une rando de deux jours (09:12-17:20 puis
        // 09:05-15:11) cumule 12 marques à l'heure une fois les bords retirés : le pas s'élargit à
        // 3h, comme pour la journée unique ci-dessus ("sur un jour comme sur plusieurs", brief).
        val day0Start = Instant.parse("2026-08-27T09:12:00Z")
        val day0End = Instant.parse("2026-08-27T17:20:00Z")
        val day1Start = Instant.parse("2026-08-28T09:05:00Z")
        val day1End = Instant.parse("2026-08-28T15:11:00Z")
        val points = listOf(point(day0Start), point(day0End), point(day1Start), point(day1End))
        val gridlines = TrackAnalysisMapMapping.clockGridlines(points, listOf(1), ZoneId.of("UTC"))
        assertEquals(
            listOf(LocalTime.of(12, 0), LocalTime.of(15, 0), LocalTime.of(12, 0)),
            gridlines?.map { it.time },
        )
        // Aucune paire de marques voisines ne se touche : au moins une heure d'écart partout
        // (brief "que deux libellés voisins ne se touchent jamais"), y compris entre le dernier
        // repère du jour 0 (15h) et le premier du jour 1 (12h, sur l'axe concaténé).
        val elapsedGaps = gridlines!!.map { it.elapsedSeconds }.zipWithNext { a, b -> b - a }
        assertEquals(true, elapsedGaps.all { it >= 3_600.0 })
    }

    @Test
    fun clockGridlinesIsNullWithoutTimestamps() {
        val points = listOf(point(baseInstant), point(null))
        assertNull(TrackAnalysisMapMapping.clockGridlines(points, emptyList(), ZoneId.of("UTC")))
    }

    // --- nearestIndex (lot 4, recherche du point sous le geste, distance et durée) ------------------

    @Test
    fun nearestIndexFindsTheClosestValue() {
        val values = doubleArrayOf(0.0, 10.0, 25.0, 40.0)
        assertEquals(0, TrackAnalysisMapMapping.nearestIndex(values, -5.0))
        assertEquals(1, TrackAnalysisMapMapping.nearestIndex(values, 12.0))
        assertEquals(2, TrackAnalysisMapMapping.nearestIndex(values, 24.0))
        assertEquals(3, TrackAnalysisMapMapping.nearestIndex(values, 1000.0))
    }

    @Test
    fun nearestIndexBreaksATieOnTheSmallerIndex() {
        // À égale distance de deux valeurs (10 et 20, cible 15), l'index le plus petit gagne : même
        // convention que l'ancienne recherche binaire d'ElevationProfile (distance).
        val values = doubleArrayOf(0.0, 10.0, 20.0)
        assertEquals(1, TrackAnalysisMapMapping.nearestIndex(values, 15.0))
    }
}

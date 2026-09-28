package com.bivouac.app.data.gpx

import org.junit.Assert.assertEquals
import org.junit.Test

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
}

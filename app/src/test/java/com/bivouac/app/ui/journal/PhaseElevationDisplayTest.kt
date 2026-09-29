package com.bivouac.app.ui.journal

import com.bivouac.app.data.gpx.TimelinePhaseKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** RIC-146 lot 8 : dénivelé affiché dans le titre d'une phase selon sa nature ([phaseElevationDisplayFor]). */
class PhaseElevationDisplayTest {

    @Test
    fun montee_DPlusSeul() {
        assertEquals(PhaseElevationDisplay.GAIN, phaseElevationDisplayFor(TimelinePhaseKind.CLIMB))
    }

    @Test
    fun descente_DMoinsSeul() {
        assertEquals(PhaseElevationDisplay.LOSS, phaseElevationDisplayFor(TimelinePhaseKind.DESCENT))
    }

    @Test
    fun vallonne_LesDeux() {
        assertEquals(PhaseElevationDisplay.BOTH, phaseElevationDisplayFor(TimelinePhaseKind.ROLLING))
    }

    @Test
    fun plat_LesDeux() {
        assertEquals(PhaseElevationDisplay.BOTH, phaseElevationDisplayFor(TimelinePhaseKind.FLAT))
    }
}

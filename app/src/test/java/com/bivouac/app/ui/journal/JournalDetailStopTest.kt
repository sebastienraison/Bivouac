package com.bivouac.app.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Test

class JournalDetailStopTest {
    private val anchors = mapOf(
        JournalDetailStop.DETAIL to 100f,
        JournalDetailStop.PROFILE to 400f,
        JournalDetailStop.SUMMARY to 700f,
    )

    @Test
    fun slowDragSettlesOnNearestStop() {
        assertEquals(JournalDetailStop.PROFILE, settleJournalDetailStop(430f, 100f, anchors))
        assertEquals(JournalDetailStop.SUMMARY, settleJournalDetailStop(620f, 0f, anchors))
    }

    @Test
    fun upwardFlingAdvancesOnlyOneStop() {
        assertEquals(JournalDetailStop.DETAIL, settleJournalDetailStop(380f, -1_200f, anchors))
    }

    @Test
    fun downwardFlingAdvancesOnlyOneStop() {
        assertEquals(JournalDetailStop.SUMMARY, settleJournalDetailStop(420f, 1_200f, anchors))
    }

    @Test
    fun flingAtEdgeStaysOnEdge() {
        assertEquals(JournalDetailStop.DETAIL, settleJournalDetailStop(100f, -1_200f, anchors))
        assertEquals(JournalDetailStop.SUMMARY, settleJournalDetailStop(700f, 1_200f, anchors))
    }
}

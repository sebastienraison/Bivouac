package com.bivouac.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerStopTest {
    private val anchors = mapOf(
        DrawerStop.DETAIL to 100f,
        DrawerStop.PROFILE to 400f,
        DrawerStop.SUMMARY to 700f,
    )

    @Test
    fun slowDragSettlesOnNearestStop() {
        assertEquals(DrawerStop.PROFILE, settleDrawerStop(430f, 100f, anchors))
        assertEquals(DrawerStop.SUMMARY, settleDrawerStop(620f, 0f, anchors))
    }

    @Test
    fun upwardFlingAdvancesOnlyOneStop() {
        assertEquals(DrawerStop.DETAIL, settleDrawerStop(380f, -1_200f, anchors))
    }

    @Test
    fun downwardFlingAdvancesOnlyOneStop() {
        assertEquals(DrawerStop.SUMMARY, settleDrawerStop(420f, 1_200f, anchors))
    }

    @Test
    fun flingAtEdgeStaysOnEdge() {
        assertEquals(DrawerStop.DETAIL, settleDrawerStop(100f, -1_200f, anchors))
        assertEquals(DrawerStop.SUMMARY, settleDrawerStop(700f, 1_200f, anchors))
    }
}

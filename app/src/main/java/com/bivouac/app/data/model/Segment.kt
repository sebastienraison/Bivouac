package com.bivouac.app.data.model

import com.bivouac.app.data.gpx.TrackStats

data class Segment(
    val points: List<TrackPoint>,
    val stats: TrackStats,
)

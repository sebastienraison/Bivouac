package com.bivouac.app.data.model

import java.time.Instant

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val time: Instant?,
)

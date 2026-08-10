package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint

object TrackGeometry {

    /** Index of the track point closest to the given coordinates (plain linear scan). */
    fun nearestPointIndex(points: List<TrackPoint>, latitude: Double, longitude: Double): Int {
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        for (i in points.indices) {
            val point = points[i]
            val distance = GeoMath.haversineMeters(latitude, longitude, point.latitude, point.longitude)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }
        return bestIndex
    }
}

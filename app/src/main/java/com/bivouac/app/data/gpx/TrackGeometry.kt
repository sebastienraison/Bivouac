package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint

object TrackGeometry {
    fun isLoop(points: List<TrackPoint>, thresholdMeters: Double): Boolean {
        if (points.size < 3) return false
        val first = points.first()
        val last = points.last()
        return GeoMath.haversineMeters(
            first.latitude, first.longitude, last.latitude, last.longitude,
        ) < thresholdMeters
    }

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

    /** Running distance (meters) from the first point up to and including each point. */
    fun cumulativeDistancesMeters(points: List<TrackPoint>): DoubleArray {
        val distances = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            distances[i] = distances[i - 1] + GeoMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        return distances
    }
}

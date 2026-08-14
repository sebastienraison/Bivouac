package com.bivouac.app.data.db

import android.content.Context
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.HikeTrack
import java.util.UUID

class BankedTrackRepository(context: Context) {

    private val dao = BivouacDatabase.getInstance(context).bankedTrackDao()

    suspend fun list(): List<BankedTrackEntity> = dao.list()

    /** Saves under [id] if provided (overwrite), otherwise creates a new entry and returns its id. */
    suspend fun save(
        id: String?,
        name: String,
        track: HikeTrack,
        bivouacPoints: List<BivouacPoint>,
        stats: TrackStats,
    ): String {
        val entity = BankedTrackEntity(
            id = id ?: UUID.randomUUID().toString(),
            name = name,
            gpxContent = GpxWriter.write(track.points, name),
            bivouacTrackPointIndices = bivouacPoints.joinToString(",") { it.trackPointIndex.toString() },
            distanceMeters = stats.distanceMeters,
            elevationGainMeters = stats.elevationGainMeters,
            elevationLossMeters = stats.elevationLossMeters,
            pointCount = stats.pointCount,
            estimatedDurationMinutes = stats.estimatedDurationMinutes,
            savedAt = System.currentTimeMillis(),
        )
        dao.save(entity)
        return entity.id
    }

    suspend fun open(id: String): Pair<HikeTrack, List<BivouacPoint>>? {
        val entity = dao.get(id) ?: return null
        val track = entity.gpxContent.byteInputStream().use { GpxParser.parse(it) }
        val bivouacPoints = entity.bivouacTrackPointIndices
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .map { BivouacPoint(id = UUID.randomUUID().toString(), trackPointIndex = it) }
        return track to bivouacPoints
    }

    /** Renames an entry in place, keeping its track/bivouac content and id unchanged. */
    suspend fun rename(id: String, name: String) {
        val entity = dao.get(id) ?: return
        val track = entity.gpxContent.byteInputStream().use { GpxParser.parse(it) }
        dao.save(
            entity.copy(
                name = name,
                gpxContent = GpxWriter.write(track.points, name),
                savedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(id: String) {
        dao.delete(id)
    }
}

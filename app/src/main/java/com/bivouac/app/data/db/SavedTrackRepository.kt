package com.bivouac.app.data.db

import android.content.Context
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.HikeTrack
import java.util.UUID

class SavedTrackRepository(context: Context) {

    private val appContext = context.applicationContext

    // RIC-103 : résolu à chaque accès et non figé à la construction, pour survivre au cycle
    // fermeture/réouverture d'une sauvegarde — voir LoggedTrackRepository.
    private val dao get() = BivouacDatabase.getInstance(appContext).savedTrackDao()

    suspend fun save(track: HikeTrack, bivouacPoints: List<BivouacPoint>) {
        val entity = SavedTrackEntity(
            trackName = track.name,
            gpxContent = GpxWriter.write(track.points, track.name ?: "Trace"),
            bivouacTrackPointIndices = bivouacPoints.joinToString(",") { it.trackPointIndex.toString() },
        )
        dao.save(entity)
    }

    suspend fun loadLast(): Pair<HikeTrack, List<BivouacPoint>>? {
        val entity = dao.get() ?: return null
        val track = entity.gpxContent.byteInputStream().use { GpxParser.parse(it) }
        val bivouacPoints = entity.bivouacTrackPointIndices
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .map { BivouacPoint(id = UUID.randomUUID().toString(), trackPointIndex = it) }
        return track to bivouacPoints
    }

    suspend fun clear() {
        dao.clear()
    }
}

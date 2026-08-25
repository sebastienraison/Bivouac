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

    suspend fun save(track: HikeTrack, bivouacPoints: List<BivouacPoint>, bankedId: String?) {
        // Toujours le même nom de fichier (singleton) : ce writeText écrase l'ancien contenu au
        // lieu d'en accumuler un par appel, comme le fait déjà le REPLACE au niveau de la ligne.
        PlanificationGpxStore.dir(appContext).mkdirs()
        val relativePath = PlanificationGpxStore.savedRelativePath()
        PlanificationGpxStore.resolve(appContext, relativePath)
            .writeText(GpxWriter.write(track.points, track.name ?: "Trace"), Charsets.UTF_8)
        val entity = SavedTrackEntity(
            trackName = track.name,
            gpxFilePath = relativePath,
            bivouacTrackPointIndices = bivouacPoints.joinToString(",") { it.trackPointIndex.toString() },
            bankedId = bankedId,
        )
        dao.save(entity)
    }

    // RIC-135 : bankedId fait partie du triplet restitué, pour que restoreLastTrack sache si la
    // session qu'il restaure est déjà liée à une entrée de la banque plutôt que de toujours
    // repartir de zéro (currentBankedId = null).
    data class RestoredSession(val track: HikeTrack, val bivouacPoints: List<BivouacPoint>, val bankedId: String?)

    suspend fun loadLast(): RestoredSession? {
        val entity = dao.get() ?: return null
        val track = PlanificationGpxStore.resolve(appContext, entity.gpxFilePath)
            .inputStream().use { GpxParser.parse(it) }
        val bivouacPoints = entity.bivouacTrackPointIndices
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .map { BivouacPoint(id = UUID.randomUUID().toString(), trackPointIndex = it) }
        return RestoredSession(track, bivouacPoints, entity.bankedId)
    }

    suspend fun clear() {
        val entity = dao.get()
        dao.clear()
        entity?.let { PlanificationGpxStore.resolve(appContext, it.gpxFilePath).delete() }
    }
}

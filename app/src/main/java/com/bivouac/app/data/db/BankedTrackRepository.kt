package com.bivouac.app.data.db

import android.content.Context
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.HikeTrack
import java.util.UUID

class BankedTrackRepository(context: Context) {

    private val appContext = context.applicationContext

    // RIC-103 : résolu à chaque accès et non figé à la construction, pour survivre au cycle
    // fermeture/réouverture d'une sauvegarde — voir LoggedTrackRepository.
    private val dao get() = BivouacDatabase.getInstance(appContext).bankedTrackDao()

    suspend fun list(): List<BankedTrackEntity> = dao.list()

    /** Saves under [id] if provided (overwrite), otherwise creates a new entry and returns its id. */
    suspend fun save(
        id: String?,
        name: String,
        track: HikeTrack,
        bivouacPoints: List<BivouacPoint>,
        stats: TrackStats,
    ): String {
        val entityId = id ?: UUID.randomUUID().toString()
        // Nommé d'après l'id, pas un nom de fichier généré à chaque appel : un overwrite explicite
        // (id fourni) ou un rename() retombent sur ce même fichier, jamais un nouveau à côté.
        PlanificationGpxStore.dir(appContext).mkdirs()
        val relativePath = PlanificationGpxStore.bankedRelativePath(entityId)
        PlanificationGpxStore.resolve(appContext, relativePath)
            .writeText(GpxWriter.write(track.points, name), Charsets.UTF_8)
        val entity = BankedTrackEntity(
            id = entityId,
            name = name,
            gpxFilePath = relativePath,
            bivouacTrackPointIndices = bivouacPoints.joinToString(",") { it.trackPointIndex.toString() },
            distanceMeters = stats.distanceMeters,
            elevationGainMeters = stats.elevationGainMeters,
            elevationLossMeters = stats.elevationLossMeters,
            estimatedDurationMinutes = stats.estimatedDurationMinutes,
            savedAt = System.currentTimeMillis(),
        )
        dao.save(entity)
        return entity.id
    }

    suspend fun open(id: String): Pair<HikeTrack, List<BivouacPoint>>? {
        val entity = dao.get(id) ?: return null
        val track = PlanificationGpxStore.resolve(appContext, entity.gpxFilePath)
            .inputStream().use { GpxParser.parse(it) }
        val bivouacPoints = entity.bivouacTrackPointIndices
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .map { BivouacPoint(id = UUID.randomUUID().toString(), trackPointIndex = it) }
        return track to bivouacPoints
    }

    /** Renames an entry in place, keeping its track/bivouac content and id unchanged. */
    suspend fun rename(id: String, name: String) {
        val entity = dao.get(id) ?: return
        val file = PlanificationGpxStore.resolve(appContext, entity.gpxFilePath)
        val track = file.inputStream().use { GpxParser.parse(it) }
        // Réécrit le fichier existant en place (même chemin) : le <name> embarqué doit rester
        // synchronisé avec le nom affiché, comme avant RIC-97, mais ça ne touche plus la colonne.
        file.writeText(GpxWriter.write(track.points, name), Charsets.UTF_8)
        dao.save(entity.copy(name = name, savedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) {
        // Chemin relevé avant le DELETE, ligne supprimée avant son fichier, jamais l'inverse — même
        // ordre que LoggedTrackRepository.delete(), pour ne pas perdre la référence si la
        // suppression du fichier échoue.
        val entity = dao.get(id) ?: return
        dao.delete(id)
        PlanificationGpxStore.resolve(appContext, entity.gpxFilePath).delete()
    }
}

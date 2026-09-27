package com.bivouac.app.data.db

import android.content.Context
import android.util.Log
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.gpx.TrackStatsParameters
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.gpximport.planificationTotalStats
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class BankedTrackRepository(context: Context) {

    private val appContext = context.applicationContext

    // RIC-103 : résolu à chaque accès et non figé à la construction, pour survivre au cycle
    // fermeture/réouverture d'une sauvegarde : voir LoggedTrackRepository.
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
        // Chemin relevé avant le DELETE, ligne supprimée avant son fichier, jamais l'inverse : même
        // ordre que LoggedTrackRepository.delete(), pour ne pas perdre la référence si la
        // suppression du fichier échoue.
        val entity = dao.get(id) ?: return
        dao.delete(id)
        PlanificationGpxStore.resolve(appContext, entity.gpxFilePath).delete()
    }

    /**
     * RIC-114 lot 2 (phase « Banque », conception §5.3) : rattrapage des quatre statistiques
     * stockées d'une trace de la banque, avec les coupures d'enregistrement de la Planification
     * (mêmes que le profil et les segments, [planificationTotalStats]). Même patron par paquets de
     * 10 et même traitement des fichiers illisibles que [LoggedTrackBackfill.runStats] : la ligne
     * garde ses anciennes valeurs mais passe quand même à la version courante.
     */
    suspend fun countTracksNeedingStatsBackfill(): Int =
        dao.countTracksNeedingStatsBackfill(TrackStatsParameters.ALGORITHM_VERSION)

    suspend fun backfillStatsFields(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val total = dao.countTracksNeedingStatsBackfill(TrackStatsParameters.ALGORITHM_VERSION)
        if (total == 0) return
        Log.i(TAG, "Rattrapage RIC-114 (Banque) : $total trace(s) à traiter")
        onProgress(0, total)
        var processed = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = dao.getTracksNeedingStatsBackfill(TrackStatsParameters.ALGORITHM_VERSION, BACKFILL_BATCH_SIZE)
            if (batch.isEmpty()) break
            for (entity in batch) {
                currentCoroutineContext().ensureActive()
                backfillStatsOne(entity)
                processed++
                onProgress(processed, total)
            }
        }
        Log.i(TAG, "Rattrapage RIC-114 (Banque) terminé : $processed trace(s)")
    }

    private suspend fun backfillStatsOne(entity: BankedTrackEntity) {
        val points = runCatching {
            PlanificationGpxStore.resolve(appContext, entity.gpxFilePath).inputStream()
                .use { GpxParser.parse(it) }.points
        }.getOrElse {
            Log.w("BankedTrackRepository", "Trace banquée ${entity.id} illisible (RIC-114), valeurs conservées", it)
            dao.markStatsVersion(entity.id, TrackStatsParameters.ALGORITHM_VERSION)
            return
        }
        val bivouacs = entity.bivouacTrackPointIndices.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .map { BivouacPoint(id = UUID.randomUUID().toString(), trackPointIndex = it) }
        val stats = planificationTotalStats(points, bivouacs, SpeedCalibration.DEFAULT)
        dao.updateTrackStats(
            id = entity.id,
            distanceMeters = stats.distanceMeters,
            elevationGainMeters = stats.elevationGainMeters,
            elevationLossMeters = stats.elevationLossMeters,
            estimatedDurationMinutes = stats.estimatedDurationMinutes,
            statsVersion = TrackStatsParameters.ALGORITHM_VERSION,
        )
    }

    private companion object {
        const val TAG = "BankedTrackRepository"
        const val BACKFILL_BATCH_SIZE = 10
    }
}

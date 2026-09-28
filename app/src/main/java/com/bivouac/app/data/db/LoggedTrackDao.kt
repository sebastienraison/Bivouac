package com.bivouac.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.PaceBandSum
import com.bivouac.app.data.photo.PhotoStorageMode

// RIC-114 lot 2 : ce qu'un jour a besoin d'écrire pendant le rattrapage des statistiques (voir
// LoggedTrackBackfill.runStats) : mêmes colonnes que updateDayDenormalizedFields ci-dessous,
// regroupées ici pour que applyStatsBackfill applique une liste entière dans une seule transaction
// avec la trace, plutôt qu'un appel par jour éclaté à l'appelant.
//
// RIC-207 : distanceMeters/elevationGainMeters/elevationLossMeters rejoignent l'aggregate de
// segments ci-dessous : les totaux du jour, calculés dans la même passe (backfillStatsOne a déjà
// TrackStatsCalculator.compute pour ce jour, avant même de construire cette mise à jour).
//
// RIC-146 : pausedSeconds et paceBands (voir DaySegmentSums) suivent le même chemin, pour que le
// rythme par pente d'un jour ne soit jamais d'une autre génération que ses sommes de calibration.
data class DayStatsUpdate(
    val id: Long,
    val contentHash: String,
    val startedAtMillis: Long?,
    val elapsedSeconds: Long?,
    val aggregate: DaySegmentAggregate,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val pausedSeconds: Double,
    val paceBands: List<PaceBandSum>,
)

@Dao
interface LoggedTrackDao {

    @Query("SELECT * FROM logged_track ORDER BY startedAt DESC")
    suspend fun list(): List<LoggedTrackEntity>

    @Query("SELECT * FROM logged_track WHERE id = :id")
    suspend fun get(id: String): LoggedTrackEntity?

    @Query("SELECT * FROM logged_track_day WHERE trackId = :trackId ORDER BY dayIndex")
    suspend fun getDays(trackId: String): List<LoggedTrackDayEntity>

    @Query("SELECT * FROM logged_track_day ORDER BY trackId, dayIndex")
    suspend fun getAllDays(): List<LoggedTrackDayEntity>

    // RIC-115 : stoppedHours IS NULL, pas flatCount IS NULL : même relais qu'avait fait RIC-109 en
    // passant de contentHash IS NULL à flatCount IS NULL (voir l'historique de ce commentaire).
    // stoppedHours est arrivé après flatCount et consorts (migration 11->12) : sur une banque déjà
    // entièrement rattrapée à RIC-109, flatCount n'est plus jamais nul nulle part, alors que
    // stoppedHours l'est partout : filtrer sur flatCount laisserait cette colonne vide pour
    // toujours. stoppedHours seul suffit comme marqueur : backfillOne écrit toutes les colonnes
    // dénormalisées ensemble, jamais les unes sans les autres (voir LoggedTrackBackfill), donc
    // stoppedHours non nul implique déjà flatCount et contentHash non nuls.
    @Query("SELECT * FROM logged_track_day WHERE stoppedHours IS NULL ORDER BY trackId, dayIndex LIMIT :limit")
    suspend fun getDaysNeedingBackfill(limit: Int): List<LoggedTrackDayEntity>

    @Query("SELECT COUNT(*) FROM logged_track_day WHERE stoppedHours IS NULL")
    suspend fun countDaysNeedingBackfill(): Int

    // RIC-207 : distanceMeters/elevationGainMeters/elevationLossMeters par défaut à null, jamais
    // écrits par le rattrapage RIC-109/115 (voir LoggedTrackBackfill.writeDenormalizedFields, qui ne
    // calcule que l'agrégat de segments) : seul backfillStatsOne (RIC-114/207) les connaît, via
    // applyStatsBackfill ci-dessous.
    @Query(
        "UPDATE logged_track_day SET contentHash = :contentHash, startedAtMillis = :startedAtMillis, " +
            "elapsedSeconds = :elapsedSeconds, flatCount = :flatCount, " +
            "flatDistanceMeters = :flatDistanceMeters, flatHours = :flatHours, steepCount = :steepCount, " +
            "steepDistanceMeters = :steepDistanceMeters, steepGainMeters = :steepGainMeters, " +
            "steepHours = :steepHours, stoppedHours = :stoppedHours, distanceMeters = :distanceMeters, " +
            "elevationGainMeters = :elevationGainMeters, elevationLossMeters = :elevationLossMeters, " +
            "pausedSeconds = :pausedSeconds WHERE id = :id",
    )
    suspend fun updateDayDenormalizedFields(
        id: Long,
        contentHash: String,
        startedAtMillis: Long?,
        elapsedSeconds: Long?,
        flatCount: Int,
        flatDistanceMeters: Double,
        flatHours: Double,
        steepCount: Int,
        steepDistanceMeters: Double,
        steepGainMeters: Double,
        steepHours: Double,
        stoppedHours: Double,
        distanceMeters: Double? = null,
        elevationGainMeters: Double? = null,
        elevationLossMeters: Double? = null,
        pausedSeconds: Double? = null,
    )

    // RIC-146 : les lignes de rythme d'un jour sont toujours remplacées en bloc, jamais mises à
    // jour une à une : une bande qui s'est vidée au nouveau calcul doit disparaître, pas garder
    // sa valeur de la génération précédente.
    @Query("DELETE FROM logged_track_day_pace WHERE dayId = :dayId")
    suspend fun deleteDayPaces(dayId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDayPaces(paces: List<LoggedTrackDayPaceEntity>)

    @Transaction
    suspend fun replaceDayPaces(dayId: Long, paceBands: List<PaceBandSum>) {
        deleteDayPaces(dayId)
        if (paceBands.isNotEmpty()) insertDayPaces(paceBands.map { it.toEntity(dayId) })
    }

    // RIC-146 : rattrapage RIC-109/115 d'un jour (LoggedTrackBackfill.run) : colonnes et lignes de
    // rythme dans une même transaction, comme applyStatsBackfill ci-dessous.
    @Transaction
    suspend fun applyDayBackfill(
        id: Long,
        contentHash: String,
        startedAtMillis: Long?,
        elapsedSeconds: Long?,
        aggregate: DaySegmentAggregate,
        pausedSeconds: Double,
        paceBands: List<PaceBandSum>,
    ) {
        updateDayDenormalizedFields(
            id = id,
            contentHash = contentHash,
            startedAtMillis = startedAtMillis,
            elapsedSeconds = elapsedSeconds,
            flatCount = aggregate.flatCount,
            flatDistanceMeters = aggregate.flatDistanceMeters,
            flatHours = aggregate.flatHours,
            steepCount = aggregate.steepCount,
            steepDistanceMeters = aggregate.steepDistanceMeters,
            steepGainMeters = aggregate.steepGainMeters,
            steepHours = aggregate.steepHours,
            stoppedHours = aggregate.stoppedHours,
            pausedSeconds = pausedSeconds,
        )
        replaceDayPaces(id, paceBands)
    }

    @Query("SELECT * FROM logged_track_day_pace WHERE dayId = :dayId ORDER BY band")
    suspend fun getDayPaces(dayId: Long): List<LoggedTrackDayPaceEntity>

    // RIC-146 : le rythme par bande sommé sur un ensemble de randos, pour la référence de la vue
    // Analyse (conception section 5.4) : la Sélection en mode Sélection, tout le Journal sinon, la
    // rando analysée toujours exclue. Deux requêtes plutôt qu'une liste d'identifiants dans tous
    // les cas : une clause IN porte une variable par identifiant, et SQLite en plafonne le nombre ;
    // le Journal entier ne doit pas dépendre de ce plafond, une Sélection saisie à la main, si.
    @Query(
        "SELECT p.band AS band, SUM(p.segmentCount) AS segmentCount, " +
            "SUM(p.distanceMeters) AS distanceMeters, SUM(p.movingSeconds) AS movingSeconds " +
            "FROM logged_track_day_pace p JOIN logged_track_day d ON d.id = p.dayId " +
            "WHERE d.trackId IN (:trackIds) GROUP BY p.band ORDER BY p.band",
    )
    suspend fun sumPacesForTracks(trackIds: Collection<String>): List<PaceBandTotal>

    @Query(
        "SELECT p.band AS band, SUM(p.segmentCount) AS segmentCount, " +
            "SUM(p.distanceMeters) AS distanceMeters, SUM(p.movingSeconds) AS movingSeconds " +
            "FROM logged_track_day_pace p JOIN logged_track_day d ON d.id = p.dayId " +
            "WHERE :excludedTrackId IS NULL OR d.trackId <> :excludedTrackId " +
            "GROUP BY p.band ORDER BY p.band",
    )
    suspend fun sumAllPaces(excludedTrackId: String?): List<PaceBandTotal>

    // RIC-114 lot 2 : statsVersion < version, pas une colonne IS NULL (voir LoggedTrackEntity.
    // statsVersion) : le prochain changement d'algorithme montera encore ALGORITHM_VERSION plutôt
    // que d'ajouter un marqueur de plus.
    @Query("SELECT * FROM logged_track WHERE statsVersion < :version ORDER BY id LIMIT :limit")
    suspend fun getTracksNeedingStatsBackfill(version: Int, limit: Int): List<LoggedTrackEntity>

    @Query("SELECT COUNT(*) FROM logged_track WHERE statsVersion < :version")
    suspend fun countTracksNeedingStatsBackfill(version: Int): Int

    @Query("UPDATE logged_track SET statsVersion = :version WHERE id = :id")
    suspend fun markStatsVersion(id: String, version: Int)

    @Query(
        "UPDATE logged_track SET distanceMeters = :distanceMeters, " +
            "elevationGainMeters = :elevationGainMeters, elevationLossMeters = :elevationLossMeters, " +
            "estimatedDurationMinutes = :estimatedDurationMinutes, statsVersion = :statsVersion " +
            "WHERE id = :id",
    )
    suspend fun updateTrackStats(
        id: String,
        distanceMeters: Double,
        elevationGainMeters: Double,
        elevationLossMeters: Double,
        estimatedDurationMinutes: Int,
        statsVersion: Int,
    )

    // RIC-114 lot 2 : une seule transaction Room par trace, jours d'abord puis la trace (voir
    // conception §5.3) : jamais une trace dont le total ne serait plus la somme de ses jours, pas
    // même une fenêtre d'une milliseconde.
    @Transaction
    suspend fun applyStatsBackfill(
        trackId: String,
        distanceMeters: Double,
        elevationGainMeters: Double,
        elevationLossMeters: Double,
        estimatedDurationMinutes: Int,
        statsVersion: Int,
        dayUpdates: List<DayStatsUpdate>,
    ) {
        for (day in dayUpdates) {
            updateDayDenormalizedFields(
                id = day.id,
                contentHash = day.contentHash,
                startedAtMillis = day.startedAtMillis,
                elapsedSeconds = day.elapsedSeconds,
                flatCount = day.aggregate.flatCount,
                flatDistanceMeters = day.aggregate.flatDistanceMeters,
                flatHours = day.aggregate.flatHours,
                steepCount = day.aggregate.steepCount,
                steepDistanceMeters = day.aggregate.steepDistanceMeters,
                steepGainMeters = day.aggregate.steepGainMeters,
                steepHours = day.aggregate.steepHours,
                stoppedHours = day.aggregate.stoppedHours,
                distanceMeters = day.distanceMeters,
                elevationGainMeters = day.elevationGainMeters,
                elevationLossMeters = day.elevationLossMeters,
                pausedSeconds = day.pausedSeconds,
            )
            replaceDayPaces(day.id, day.paceBands)
        }
        updateTrackStats(
            id = trackId,
            distanceMeters = distanceMeters,
            elevationGainMeters = elevationGainMeters,
            elevationLossMeters = elevationLossMeters,
            estimatedDurationMinutes = estimatedDurationMinutes,
            statsVersion = statsVersion,
        )
    }

    // RIC-19 : marqueur dédié (elevationBackfilled), pas flatCount IS NULL : ce rattrapage porte des
    // colonnes différentes de celui de RIC-109 et une ligne peut avoir l'un sans l'autre dans les
    // deux sens (voir LoggedTrackDayEntity.elevationBackfilled).
    @Query(
        "SELECT * FROM logged_track_day WHERE elevationBackfilled = 0 ORDER BY trackId, dayIndex LIMIT :limit",
    )
    suspend fun getDaysNeedingElevationBackfill(limit: Int): List<LoggedTrackDayEntity>

    @Query("SELECT COUNT(*) FROM logged_track_day WHERE elevationBackfilled = 0")
    suspend fun countDaysNeedingElevationBackfill(): Int

    @Query(
        "UPDATE logged_track_day SET maxElevationMeters = :maxElevationMeters, " +
            "lastPointElevationMeters = :lastPointElevationMeters, elevationBackfilled = 1 WHERE id = :id",
    )
    suspend fun updateDayElevationFields(
        id: Long,
        maxElevationMeters: Double?,
        lastPointElevationMeters: Double?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(entity: LoggedTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDays(days: List<LoggedTrackDayEntity>): List<Long>

    // RIC-146 : [dayPaceBands] porte les lignes de rythme de chaque jour, dans l'ordre de [days] :
    // elles ont besoin de l'identifiant que la base attribue au jour, d'où l'insertion ici, dans la
    // même transaction, plutôt qu'à l'appelant. Liste vide (défaut) : aucun jour n'a de rythme.
    @Transaction
    suspend fun insert(
        entity: LoggedTrackEntity,
        days: List<LoggedTrackDayEntity>,
        dayPaceBands: List<List<PaceBandSum>> = emptyList(),
    ) {
        insertTrack(entity)
        val dayIds = insertDays(days)
        dayIds.forEachIndexed { index, dayId ->
            val paces = dayPaceBands.getOrNull(index).orEmpty()
            if (paces.isNotEmpty()) insertDayPaces(paces.map { it.toEntity(dayId) })
        }
    }

    // logged_track_day rows cascade-delete with their parent (ForeignKey.CASCADE).
    @Query("DELETE FROM logged_track WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE logged_track SET note = :note WHERE id = :id")
    suspend fun updateNote(id: String, note: String)

    @Query("UPDATE logged_track SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String)

    @Query("SELECT * FROM logged_track_tag")
    suspend fun getAllTags(): List<LoggedTrackTagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: LoggedTrackTagEntity)

    @Query("DELETE FROM logged_track_tag WHERE trackId = :trackId AND tag = :tag")
    suspend fun deleteTag(trackId: String, tag: String)

    @Query("SELECT * FROM logged_track_photo WHERE trackId = :trackId ORDER BY takenAtMillis, addedAtMillis")
    suspend fun getPhotos(trackId: String): List<LoggedTrackPhotoEntity>

    @Query("SELECT * FROM logged_track_photo WHERE id = :id")
    suspend fun getPhoto(id: Long): LoggedTrackPhotoEntity?

    // RIC-141 : les identifiants des traces qui ont au moins une photo, toutes traces confondues.
    // Une requête pour toute la banque, sur le modèle de getAllTags : la liste du Journal ne veut
    // qu'un booléen par ligne, charger les photos trace par trace pour l'obtenir serait N requêtes
    // et N listes d'entités pour rien.
    @Query("SELECT DISTINCT trackId FROM logged_track_photo")
    suspend fun getTrackIdsWithPhotos(): List<String>

    // RIC-43 : les chemins seuls, toutes traces confondues : ce dont le balayage des orphelins de
    // BackupManager a besoin, sans charger les lignes entières.
    @Query("SELECT filePath FROM logged_track_photo")
    suspend fun getAllPhotoFilePaths(): List<String>

    // RIC-140/151/157 : les lignes entières, toutes traces confondues. Trois usages qui doivent
    // raisonner sur la banque de photos complète et non sur une sortie : le décompte de l'espace
    // occupé par mode de stockage, la passe de recompression du stock, et la recherche des
    // fichiers manquants. Aucun blob ici (le contenu vit dans un fichier depuis RIC-43), donc
    // aucune raison de craindre la limite CursorWindow qui a motivé LoggedTrackGpxStore.
    @Query("SELECT * FROM logged_track_photo")
    suspend fun getAllPhotos(): List<LoggedTrackPhotoEntity>

    // RIC-157 : la recompression a remplacé le fichier local par sa version réduite. Les deux
    // colonnes bougent ensemble et JAMAIS l'une sans l'autre : un chemin neuf avec un mode resté
    // FULL ferait réexaminer indéfiniment une photo déjà traitée, et un mode REDUCED sur l'ancien
    // chemin décrirait un fichier qui n'existe plus.
    //
    // L'empreinte, elle, ne bouge pas : elle porte les octets d'ORIGINE, et c'est ce qui permettra
    // de retrouver l'original plus tard (voir PhotoOriginalResolver). La recalculer sur la copie
    // réduite reviendrait à perdre définitivement ce lien.
    @Query("UPDATE logged_track_photo SET filePath = :filePath, storageMode = :storageMode WHERE id = :id")
    suspend fun updatePhotoStorage(id: Long, filePath: String, storageMode: PhotoStorageMode)

    @Insert
    suspend fun insertPhoto(photo: LoggedTrackPhotoEntity): Long

    @Query("DELETE FROM logged_track_photo WHERE id = :id")
    suspend fun deletePhoto(id: Long)

    // RIC-152 : « Purger les photos », le seul endroit de l'app qui vide la table d'un bloc. Jamais
    // appelé automatiquement : voir LoggedTrackRepository.purgeAllPhotos.
    @Query("DELETE FROM logged_track_photo")
    suspend fun deleteAllPhotos()

    @Query("SELECT COUNT(*) FROM logged_track_photo")
    suspend fun countPhotos(): Int

    // RIC-43 : conservé alors que le repositionnement n'est plus atteignable depuis l'UI (le menu
    // d'appui long ne garde que « Supprimer »). Le placement des photos sur la trace revient dans
    // un lot ultérieur, qui reprendra cette requête telle quelle plutôt que de la réécrire.
    @Query(
        "UPDATE logged_track_photo SET positionPointIndex = :positionPointIndex, " +
            "positionApproximate = :positionApproximate WHERE id = :id",
    )
    suspend fun updatePhotoPosition(id: Long, positionPointIndex: Int?, positionApproximate: Boolean)

    // RIC-157 : la recherche profonde vient de retrouver l'original ailleurs que là où il était :
    // le nouvel URI est mémorisé pour que la fois suivante s'arrête au premier temps de la
    // résolution (voir PhotoOriginalResolver). Seule cette colonne bouge : rien d'autre de la photo
    // n'a changé, surtout pas son empreinte.
    @Query("UPDATE logged_track_photo SET lastResolvedUri = :lastResolvedUri WHERE id = :id")
    suspend fun updatePhotoLastResolvedUri(id: Long, lastResolvedUri: String?)

    // RIC-143 / RIC-144 : les ajustements d'affichage validés dans l'éditeur « Ajuster ».
    //
    // Les cinq colonnes ensemble et jamais séparément : rotation et recadrage se pensent dans le
    // même repère (le cadre vit dans l'image tournée), les écrire l'une sans l'autre laisserait un
    // instant où le rectangle désigne une autre zone de la photo. Les quatre colonnes de recadrage
    // valent null ensemble quand il n'y a plus de recadrage : c'est aussi ce qui permet de DÉFAIRE
    // un recadrage, pas seulement d'en poser un.
    //
    // Le fichier n'est pas touché : c'est tout le principe du non destructif.
    @Query(
        "UPDATE logged_track_photo SET rotationQuarterTurns = :rotationQuarterTurns, " +
            "cropLeft = :cropLeft, cropTop = :cropTop, cropRight = :cropRight, " +
            "cropBottom = :cropBottom WHERE id = :id",
    )
    suspend fun updatePhotoAdjustments(
        id: Long,
        rotationQuarterTurns: Int,
        cropLeft: Float?,
        cropTop: Float?,
        cropRight: Float?,
        cropBottom: Float?,
    )

    // RIC-170 : la légende, écrite à la sauvegarde du mode édition comme les ajustements ci-dessus.
    // Null remet la photo sans légende (bulle et visionneuse retrouvent alors « Ajouter une
    // légende » / rien à afficher).
    @Query("UPDATE logged_track_photo SET caption = :caption WHERE id = :id")
    suspend fun updatePhotoCaption(id: Long, caption: String?)

    // RIC-171 : « Retirer de la carte » / « Replacer sur la carte ». La position (positionPointIndex)
    // n'est jamais touchée par cette colonne : retirer puis replacer retrouve exactement le même
    // point, rien n'a jamais été perdu.
    @Query("UPDATE logged_track_photo SET shownOnMap = :shownOnMap WHERE id = :id")
    suspend fun updatePhotoShownOnMap(id: Long, shownOnMap: Boolean)
}

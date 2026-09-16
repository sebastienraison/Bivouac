package com.bivouac.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.bivouac.app.data.photo.PhotoStorageMode

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

    @Query(
        "UPDATE logged_track_day SET contentHash = :contentHash, startedAtMillis = :startedAtMillis, " +
            "elapsedSeconds = :elapsedSeconds, flatCount = :flatCount, " +
            "flatDistanceMeters = :flatDistanceMeters, flatHours = :flatHours, steepCount = :steepCount, " +
            "steepDistanceMeters = :steepDistanceMeters, steepGainMeters = :steepGainMeters, " +
            "steepHours = :steepHours, stoppedHours = :stoppedHours WHERE id = :id",
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
    )

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
    suspend fun insertDays(days: List<LoggedTrackDayEntity>)

    @Transaction
    suspend fun insert(entity: LoggedTrackEntity, days: List<LoggedTrackDayEntity>) {
        insertTrack(entity)
        insertDays(days)
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
}

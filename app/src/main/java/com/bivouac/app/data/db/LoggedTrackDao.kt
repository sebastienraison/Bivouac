package com.bivouac.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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

    // RIC-115 : stoppedHours IS NULL, pas flatCount IS NULL — même relais qu'avait fait RIC-109 en
    // passant de contentHash IS NULL à flatCount IS NULL (voir l'historique de ce commentaire).
    // stoppedHours est arrivé après flatCount et consorts (migration 11->12) : sur une banque déjà
    // entièrement rattrapée à RIC-109, flatCount n'est plus jamais nul nulle part, alors que
    // stoppedHours l'est partout — filtrer sur flatCount laisserait cette colonne vide pour
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

    // RIC-19 : marqueur dédié (elevationBackfilled), pas flatCount IS NULL — ce rattrapage porte des
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

    @Insert
    suspend fun insertPhoto(photo: LoggedTrackPhotoEntity): Long

    @Query("DELETE FROM logged_track_photo WHERE id = :id")
    suspend fun deletePhoto(id: Long)

    @Query(
        "UPDATE logged_track_photo SET positionPointIndex = :positionPointIndex, " +
            "positionApproximate = :positionApproximate WHERE id = :id",
    )
    suspend fun updatePhotoPosition(id: Long, positionPointIndex: Int?, positionApproximate: Boolean)
}

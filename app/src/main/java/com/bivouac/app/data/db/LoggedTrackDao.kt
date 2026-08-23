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

    // contentHash IS NULL identifie exactement les lignes que le rattrapage n'a pas encore
    // traitées : un hash se calcule pour n'importe quel fichier, horodaté ou non.
    @Query("SELECT * FROM logged_track_day WHERE contentHash IS NULL ORDER BY trackId, dayIndex LIMIT :limit")
    suspend fun getDaysNeedingBackfill(limit: Int): List<LoggedTrackDayEntity>

    @Query("SELECT COUNT(*) FROM logged_track_day WHERE contentHash IS NULL")
    suspend fun countDaysNeedingBackfill(): Int

    @Query(
        "UPDATE logged_track_day SET contentHash = :contentHash, startedAtMillis = :startedAtMillis, " +
            "elapsedSeconds = :elapsedSeconds WHERE id = :id",
    )
    suspend fun updateDayDenormalizedFields(
        id: Long,
        contentHash: String,
        startedAtMillis: Long?,
        elapsedSeconds: Long?,
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
}

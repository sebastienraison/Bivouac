package com.bivouac.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BankedTrackDao {

    @Query("SELECT * FROM banked_track ORDER BY savedAt DESC")
    suspend fun list(): List<BankedTrackEntity>

    @Query("SELECT * FROM banked_track WHERE id = :id")
    suspend fun get(id: String): BankedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BankedTrackEntity)

    @Query("DELETE FROM banked_track WHERE id = :id")
    suspend fun delete(id: String)
}

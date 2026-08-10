package com.bivouac.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SavedTrackDao {

    @Query("SELECT * FROM saved_track WHERE id = ${SavedTrackEntity.SINGLETON_ID}")
    suspend fun get(): SavedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SavedTrackEntity)

    @Query("DELETE FROM saved_track")
    suspend fun clear()
}

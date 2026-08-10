package com.bivouac.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SavedTrackEntity::class], version = 1, exportSchema = false)
abstract class BivouacDatabase : RoomDatabase() {

    abstract fun savedTrackDao(): SavedTrackDao

    companion object {
        @Volatile private var instance: BivouacDatabase? = null

        fun getInstance(context: Context): BivouacDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BivouacDatabase::class.java,
                    "bivouac.db",
                ).build().also { instance = it }
            }
    }
}

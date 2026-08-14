package com.bivouac.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// One raw, untouched GPX file belonging to a logged_track — a separate row per day rather than a
// single blob, so a multi-day hike (several device exports, one per day) can be represented
// without ever concatenating or otherwise altering the original files. Unlike banked_track's
// gpxContent (re-serialized, extensions stripped), rawGpxContent is stored byte-for-byte as
// imported: the Journal's whole point is to keep everything, even data the app doesn't use yet.
@Entity(
    tableName = "logged_track_day",
    foreignKeys = [
        ForeignKey(
            entity = LoggedTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId")],
)
data class LoggedTrackDayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val dayIndex: Int,
    val rawGpxContent: String,
)

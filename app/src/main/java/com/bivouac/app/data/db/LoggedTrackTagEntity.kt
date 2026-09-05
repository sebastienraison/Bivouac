package com.bivouac.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// One tag on a logged_track: system tags (see SystemTag) and free-form tags share this same
// table and column; a fixed value set isn't enforced at the DB level, only by which values the UI
// offers as toggleable chips versus free text.
@Entity(
    tableName = "logged_track_tag",
    foreignKeys = [
        ForeignKey(
            entity = LoggedTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trackId", "tag"], unique = true)],
)
data class LoggedTrackTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val tag: String,
)

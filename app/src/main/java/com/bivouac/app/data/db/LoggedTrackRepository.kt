package com.bivouac.app.data.db

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.model.HikeTrack
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

// A parsed-and-ready-to-store import that hasn't been written to the DB yet — lets the caller
// check for duplicates (findDuplicate) before committing (commitImport), without re-reading or
// re-parsing the file.
data class PreparedImport(val entity: LoggedTrackEntity, val days: List<LoggedTrackDayEntity>)

sealed interface DuplicateMatch {
    val existing: LoggedTrackEntity
    data class Exact(override val existing: LoggedTrackEntity) : DuplicateMatch
    data class Probable(override val existing: LoggedTrackEntity) : DuplicateMatch
}

class LoggedTrackRepository(context: Context) {

    private val dao = BivouacDatabase.getInstance(context).loggedTrackDao()

    suspend fun list(): List<LoggedTrackEntity> = dao.list()

    /**
     * Reads, parses and hashes a single raw GPX file into a one-day logged track, without writing
     * anything to the DB yet — the raw content itself is only used transiently here (via
     * [GpxParser]) to compute the denormalized stats and the hike's start date; it's stored
     * untouched separately (see LoggedTrackDayEntity).
     */
    suspend fun prepareImport(resolver: ContentResolver, uri: Uri): PreparedImport {
        val rawGpx = resolver.openInputStream(uri)?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            ?: throw IOException("Impossible d'ouvrir le fichier sélectionné")
        val track = rawGpx.byteInputStream(StandardCharsets.UTF_8).use { GpxParser.parse(it) }
        val stats = TrackStatsCalculator.compute(track.points)
        val startedAt = track.points.firstOrNull()?.time?.toEpochMilli() ?: System.currentTimeMillis()

        val id = UUID.randomUUID().toString()
        val entity = LoggedTrackEntity(
            id = id,
            name = track.name ?: "Trace sans nom",
            sourceFileName = queryDisplayName(resolver, uri),
            startedAt = startedAt,
            contentHash = sha256(rawGpx),
            distanceMeters = stats.distanceMeters,
            elevationGainMeters = stats.elevationGainMeters,
            elevationLossMeters = stats.elevationLossMeters,
            pointCount = stats.pointCount,
            estimatedDurationMinutes = stats.estimatedDurationMinutes,
        )
        val day = LoggedTrackDayEntity(trackId = id, dayIndex = 0, rawGpxContent = rawGpx)
        return PreparedImport(entity, listOf(day))
    }

    /**
     * Two-tier check: an identical content hash means the exact same file was already imported
     * (re-exporting the same hike from a different tool won't hash the same, so this alone isn't
     * enough) — [DuplicateMatch.Exact], meant to hard-block. Failing that, a start time within an
     * hour and a distance within 5% of an existing entry is treated as a probable duplicate — two
     * different hikes of similar length can happen on the same day, but not within the same hour
     * — [DuplicateMatch.Probable], meant as a "import anyway?" warning rather than a block.
     */
    suspend fun findDuplicate(prepared: PreparedImport): DuplicateMatch? {
        val all = dao.list()
        all.find { it.contentHash == prepared.entity.contentHash }?.let { return DuplicateMatch.Exact(it) }
        all.find { isProbablySameHike(it, prepared.entity) }?.let { return DuplicateMatch.Probable(it) }
        return null
    }

    suspend fun commitImport(prepared: PreparedImport): String {
        dao.insert(prepared.entity, prepared.days)
        return prepared.entity.id
    }

    /** Concatenates every day's raw GPX, in order, into a single flat track for display. */
    suspend fun open(id: String): HikeTrack? {
        val entity = dao.get(id) ?: return null
        val days = dao.getDays(id)
        if (days.isEmpty()) return null
        val points = days.sortedBy { it.dayIndex }.flatMap { day ->
            day.rawGpxContent.byteInputStream(StandardCharsets.UTF_8).use { GpxParser.parse(it) }.points
        }
        return HikeTrack(name = entity.name, points = points)
    }

    suspend fun delete(id: String) {
        dao.delete(id)
    }

    suspend fun updateNote(id: String, note: String) {
        dao.updateNote(id, note)
    }

    suspend fun rename(id: String, name: String) {
        dao.updateName(id, name)
    }

    /** trackId -> its tags, for every track that has at least one. */
    suspend fun tagsByTrackId(): Map<String, List<String>> =
        dao.getAllTags().groupBy({ it.trackId }, { it.tag })

    suspend fun addTag(trackId: String, tag: String) {
        dao.insertTag(LoggedTrackTagEntity(trackId = trackId, tag = tag))
    }

    suspend fun removeTag(trackId: String, tag: String) {
        dao.deleteTag(trackId, tag)
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun isProbablySameHike(a: LoggedTrackEntity, b: LoggedTrackEntity): Boolean {
        val startCloseEnough = abs(a.startedAt - b.startedAt) <= ONE_HOUR_MILLIS
        val avgDistance = (a.distanceMeters + b.distanceMeters) / 2
        val distanceCloseEnough = avgDistance > 0 && abs(a.distanceMeters - b.distanceMeters) / avgDistance <= 0.05
        return startCloseEnough && distanceCloseEnough
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ONE_HOUR_MILLIS = 3_600_000L
    }
}

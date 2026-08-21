package com.bivouac.app.data.db

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.SpeedCalibrationCalculator
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.Segment
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import kotlin.math.abs

// A parsed-and-ready-to-store import that hasn't been written to the DB yet — lets the caller
// check for duplicates (findDuplicate) before committing (commitImport), without re-reading or
// re-parsing the file. Carries the raw content itself (one entry per day, in day order): the
// backing file only gets written at commit time, so a discarded duplicate leaves no orphan.
data class PreparedImport(val entity: LoggedTrackEntity, val rawGpxByDay: List<String>)

// Une trace du Journal ouverte pour affichage : [track] est l'ensemble des jours concaténés (la
// carte et le profil altimétrique n'ont pas à connaître le découpage), [daySegments] ces mêmes
// points redécoupés par jour, un par LoggedTrackDayEntity et dans l'ordre des jours, pour la
// ventilation « Total » + « Jour N » de la vue détail (RIC-41). Une trace d'un seul jour donne une
// liste à un élément ; l'affichage de la ventilation ne se déclenche qu'au-delà, même convention
// que les segments de Planification.
data class LoggedTrackDetail(val track: HikeTrack, val daySegments: List<Segment>)

sealed interface DuplicateMatch {
    val existing: LoggedTrackEntity
    data class Exact(override val existing: LoggedTrackEntity) : DuplicateMatch
    data class Probable(override val existing: LoggedTrackEntity) : DuplicateMatch
}

class LoggedTrackRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = BivouacDatabase.getInstance(context).loggedTrackDao()

    suspend fun list(): List<LoggedTrackEntity> = dao.list()

    /**
     * Reads, parses and hashes one or more raw GPX files into a single logged track (RIC-41 : un
     * fichier = un jour d'une même sortie), without writing anything to the DB yet — the raw
     * content itself is only used transiently here (via [GpxParser]) to compute the denormalized
     * stats and the hike's start date; it's stored untouched separately (see LoggedTrackDayEntity).
     *
     * L'ordre des jours ne suit pas l'ordre de sélection mais l'horodatage de chaque fichier, voir
     * [ImportDayOrdering]. Les statistiques agrégées sont la somme de celles de chaque jour, pas
     * un calcul sur la concaténation : ça évite de compter le trajet fictif entre l'arrivée d'un
     * jour et le départ du lendemain.
     */
    suspend fun prepareImport(
        resolver: ContentResolver,
        uris: List<Uri>,
        calibration: SpeedCalibration = SpeedCalibration.DEFAULT,
    ): PreparedImport {
        require(uris.isNotEmpty()) { "Aucun fichier sélectionné" }
        // readBounded plutôt que readBytes() : même plafond de taille que le parseur, appliqué
        // avant la première allocation pleine du fichier (entrée externe non maîtrisée, RIC-95).
        val parsed = uris.map { uri ->
            val rawGpx = resolver.openInputStream(uri)?.use { GpxParser.readBounded(it) }
                ?: throw IOException("Impossible d'ouvrir le fichier sélectionné")
            rawGpx to rawGpx.byteInputStream(StandardCharsets.UTF_8).use { GpxParser.parse(it) }
        }
        val ordered = ImportDayOrdering.orderIndices(parsed.map { (_, track) -> track.points.firstOrNull()?.time })
            .map { parsed[it] }
        val dayStats = ordered.map { (_, track) -> TrackStatsCalculator.compute(track.points, calibration) }
        val startedAt = ordered.first().second.points.firstOrNull()?.time?.toEpochMilli()
            ?: System.currentTimeMillis()

        val id = UUID.randomUUID().toString()
        val entity = LoggedTrackEntity(
            id = id,
            name = ordered.first().second.name ?: "Trace sans nom",
            startedAt = startedAt,
            // Concaténation des contenus dans l'ordre des jours, pas une combinaison de hachages
            // par fichier : réimporter exactement le même lot doit retomber sur le même hash pour
            // que findDuplicate le bloque. Sur un fichier unique, ça reste le hash de ce fichier,
            // donc les traces déjà importées gardent le leur.
            contentHash = sha256(ordered.joinToString("\n") { (rawGpx, _) -> rawGpx }),
            distanceMeters = dayStats.sumOf { it.distanceMeters },
            elevationGainMeters = dayStats.sumOf { it.elevationGainMeters },
            elevationLossMeters = dayStats.sumOf { it.elevationLossMeters },
            pointCount = ordered.sumOf { (_, track) -> track.points.size },
            estimatedDurationMinutes = dayStats.sumOf { it.estimatedDurationMinutes },
        )
        return PreparedImport(entity, ordered.map { (rawGpx, _) -> rawGpx })
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

    // Fichiers d'abord, lignes ensuite : si l'insert échoue, les fichiers tout juste écrits sont
    // retirés ; si une écriture de fichier échoue, rien n'a touché la base — dans les deux cas
    // aucune ligne ne peut pointer vers un fichier absent.
    suspend fun commitImport(prepared: PreparedImport): String {
        LoggedTrackGpxStore.dir(appContext).mkdirs()
        val days = prepared.rawGpxByDay.mapIndexed { dayIndex, rawGpx ->
            val relativePath = LoggedTrackGpxStore.relativePath(prepared.entity.id, dayIndex)
            LoggedTrackGpxStore.resolve(appContext, relativePath).writeText(rawGpx, StandardCharsets.UTF_8)
            LoggedTrackDayEntity(trackId = prepared.entity.id, dayIndex = dayIndex, rawGpxFilePath = relativePath)
        }
        try {
            dao.insert(prepared.entity, days)
        } catch (e: Exception) {
            days.forEach { LoggedTrackGpxStore.resolve(appContext, it.rawGpxFilePath).delete() }
            throw e
        }
        return prepared.entity.id
    }

    /** Concatenates every day's raw GPX, in order, into a single flat track for display. */
    suspend fun open(id: String): HikeTrack? {
        val entity = dao.get(id) ?: return null
        val days = dao.getDays(id)
        if (days.isEmpty()) return null
        val points = days.sortedBy { it.dayIndex }.flatMap { day ->
            LoggedTrackGpxStore.resolve(appContext, day.rawGpxFilePath).inputStream()
                .use { GpxParser.parse(it) }.points
        }
        return HikeTrack(name = entity.name, points = points)
    }

    /**
     * Même trace concaténée que [open], plus la ventilation par jour (RIC-41) dont la vue détail a
     * besoin pour afficher « Total » et « Jour N ». La durée de chaque jour est calculée avec la
     * calibration par défaut : c'est l'appelant qui la re-dérive sous la calibration active, comme
     * [LoggedTrackEntity.toTrackStats] le fait déjà pour la ligne agrégée.
     */
    suspend fun openDetail(id: String): LoggedTrackDetail? {
        val entity = dao.get(id) ?: return null
        val days = dao.getDays(id).sortedBy { it.dayIndex }
        if (days.isEmpty()) return null
        val dayTracks = days.map { day ->
            LoggedTrackGpxStore.resolve(appContext, day.rawGpxFilePath).inputStream().use { GpxParser.parse(it) }
        }
        return LoggedTrackDetail(
            track = HikeTrack(name = entity.name, points = dayTracks.flatMap { it.points }),
            daySegments = dayTracks.map { Segment(it.points, TrackStatsCalculator.compute(it.points)) },
        )
    }

    suspend fun delete(id: String) {
        // Chemins relevés avant le DELETE (le CASCADE emporte les lignes de jours) ; la ligne
        // disparaît avant son fichier, jamais l'inverse.
        val days = dao.getDays(id)
        dao.delete(id)
        days.forEach { LoggedTrackGpxStore.resolve(appContext, it.rawGpxFilePath).delete() }
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

    /**
     * Calibration samples (BIV-16 Auto/Sélection) for every track in [ids], or the whole Journal
     * when null. A track only contributes if at least one of its days has real GPX timestamps —
     * silently skipped otherwise, same as [SpeedCalibrationCalculator] already tolerates.
     */
    suspend fun calibrationSamples(ids: Set<String>? = null): List<SpeedCalibrationCalculator.Sample> {
        val entries = dao.list().filter { ids == null || it.id in ids }
        return entries.mapNotNull { calibrationSample(it) }
    }

    // Elapsed time is summed per day (not first-to-last across the whole track) so an overnight
    // gap on a multi-day hike never gets counted as "elapsed walking time" — see
    // SpeedCalibrationCalculator's kdoc for why a real elapsed duration matters here.
    //
    // Le plafond CursorWindow qui faisait planter ici (BIV-16 recette) est levé depuis RIC-62 —
    // le contenu vient d'un fichier, plus d'un Cursor. Le runCatching reste : un fichier manquant
    // ou un GPX corrompu ne doit pas couler la calibration des autres traces — même traitement
    // "silently skipped" qu'une trace sans horodatages exploitables.
    private suspend fun calibrationSample(entry: LoggedTrackEntity): SpeedCalibrationCalculator.Sample? {
        val elapsedHours = runCatching {
            dao.getDays(entry.id).sumOf { day ->
                val points = LoggedTrackGpxStore.resolve(appContext, day.rawGpxFilePath).inputStream()
                    .use { GpxParser.parse(it) }.points
                val first = points.firstOrNull()?.time
                val last = points.lastOrNull()?.time
                if (first != null && last != null) Duration.between(first, last).toMillis() / 3_600_000.0 else 0.0
            }
        }.getOrElse {
            Log.w("LoggedTrackRepository", "Trace « ${entry.name} » illisible pour la calibration, ignorée", it)
            return null
        }
        if (elapsedHours <= 0.0) return null
        return SpeedCalibrationCalculator.Sample(
            distanceMeters = entry.distanceMeters,
            elevationGainMeters = entry.elevationGainMeters,
            elapsedHours = elapsedHours,
        )
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

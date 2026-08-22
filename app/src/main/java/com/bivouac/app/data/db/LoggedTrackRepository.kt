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
data class PreparedImport(val entity: LoggedTrackEntity, val days: List<PreparedDay>)

// Un jour d'un import en attente, avec ce qui a été relevé pendant le parsing — le fichier est
// déjà lu et parsé à ce moment-là, donc dénormaliser ne coûte rien de plus ici, alors que le
// relire ensuite coûte une passe complète. Voir LoggedTrackDayEntity pour le sens des champs.
data class PreparedDay(
    val rawGpx: String,
    val contentHash: String,
    val startedAtMillis: Long?,
    val elapsedSeconds: Long?,
)

// Une trace du Journal ouverte pour affichage : [track] est l'ensemble des jours concaténés (la
// carte et le profil altimétrique n'ont pas à connaître le découpage), [daySegments] ces mêmes
// points redécoupés par jour, un par LoggedTrackDayEntity et dans l'ordre des jours, pour la
// ventilation « Total » + « Jour N » de la vue détail (RIC-41). Une trace d'un seul jour donne une
// liste à un élément ; l'affichage de la ventilation ne se déclenche qu'au-delà, même convention
// que les segments de Planification.
data class LoggedTrackDetail(val track: HikeTrack, val daySegments: List<Segment>)

// Ce que la liste du Journal doit savoir des jours d'une trace, sans ouvrir de fichier.
data class DaySummary(val dayCount: Int, val startMillis: List<Long>)

sealed interface DuplicateMatch {
    val existing: LoggedTrackEntity
    data class Exact(override val existing: LoggedTrackEntity) : DuplicateMatch
    data class Probable(override val existing: LoggedTrackEntity) : DuplicateMatch

    /**
     * Au moins un jour du lot entrant est déjà, à l'octet près, un jour d'une sortie du Journal,
     * sans que les deux sorties soient identiques : importer une seule journée d'un trek déjà en
     * banque, ou le trek entier alors qu'une de ses journées y est déjà seule.
     *
     * Traité comme un avertissement et jamais comme un blocage, dans les deux sens : la
     * ressemblance est certaine mais l'intention ne l'est pas, et refuser un trek de six jours
     * parce que son jour 3 existe déjà à part serait pire que le doublon.
     */
    data class SharedDay(
        override val existing: LoggedTrackEntity,
        val sharedDays: Int,
        val incomingDays: Int,
    ) : DuplicateMatch
}

class LoggedTrackRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = BivouacDatabase.getInstance(context).loggedTrackDao()

    suspend fun list(): List<LoggedTrackEntity> = dao.list()

    /**
     * Rattrape les colonnes dénormalisées des traces importées avant la migration 8 vers 9. Sans
     * effet une fois la banque à jour, et interruptible : voir [LoggedTrackBackfill].
     */
    suspend fun backfillDenormalizedFields() = LoggedTrackBackfill.run(appContext, dao)

    /**
     * Ce que la liste doit savoir des jours d'une trace sans ouvrir le moindre fichier : combien
     * elle en compte, et quand chacun a commencé. Une requête pour toute la banque.
     */
    suspend fun daySummariesByTrackId(): Map<String, DaySummary> =
        dao.getAllDays()
            .groupBy { it.trackId }
            .mapValues { (_, days) ->
                DaySummary(
                    dayCount = days.size,
                    // Les jours sans horodatage exploitable, et ceux que le rattrapage n'a pas
                    // encore traités, sont absents : afficher les dates connues vaut mieux
                    // qu'inventer les autres. dayCount, lui, est toujours juste.
                    startMillis = days.sortedBy { it.dayIndex }.mapNotNull { it.startedAtMillis },
                )
            }

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
        val days = ordered.map { (rawGpx, track) ->
            PreparedDay(
                rawGpx = rawGpx,
                contentHash = sha256(rawGpx),
                startedAtMillis = track.points.firstOrNull()?.time?.toEpochMilli(),
                elapsedSeconds = elapsedSeconds(track),
            )
        }
        return PreparedImport(entity, days)
    }

    // null quand le jour n'a pas d'horodatage exploitable, ce qui est un cas réel : la calibration
    // écarte alors la trace au lieu de lui prêter une durée inventée.
    private fun elapsedSeconds(track: HikeTrack): Long? {
        val first = track.points.firstOrNull()?.time ?: return null
        val last = track.points.lastOrNull()?.time ?: return null
        return Duration.between(first, last).seconds.takeIf { it > 0 }
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
        findSharedDay(prepared, all)?.let { return it }
        all.find { isProbablySameHike(it, prepared.entity) }?.let { return DuplicateMatch.Probable(it) }
        return null
    }

    /**
     * Constat A de la recette : le hash de `logged_track` porte sur la concaténation des jours
     * dans l'ordre, donc un fichier seul ne peut jamais correspondre à un trek de trois jours, et
     * le repli « doublon probable » ne rattrape pas non plus, le jour 1 partageant la date de
     * départ du trek entier mais pas sa distance. D'où cette comparaison jour à jour, qui n'est
     * possible que depuis que le hash de chaque fichier est stocké.
     *
     * Ne voit que les jours déjà rattrapés (voir [LoggedTrackBackfill]) : sur une banque en cours
     * de rattrapage la détection est partielle, jamais fausse.
     */
    private suspend fun findSharedDay(prepared: PreparedImport, all: List<LoggedTrackEntity>): DuplicateMatch? {
        val incomingHashes = prepared.days.map { it.contentHash }.toSet()
        val matchesByTrack = dao.getAllDays()
            .filter { it.contentHash != null && it.contentHash in incomingHashes }
            .groupBy { it.trackId }
        // La sortie qui partage le plus de jours avec le lot entrant : sur un trek réimporté
        // journée par journée, c'est celle qui rend l'avertissement compréhensible.
        val (trackId, days) = matchesByTrack.maxByOrNull { it.value.size } ?: return null
        val existing = all.find { it.id == trackId } ?: return null
        return DuplicateMatch.SharedDay(
            existing = existing,
            sharedDays = days.size,
            incomingDays = prepared.days.size,
        )
    }

    // Fichiers d'abord, lignes ensuite : si l'insert échoue, les fichiers tout juste écrits sont
    // retirés ; si une écriture de fichier échoue, rien n'a touché la base — dans les deux cas
    // aucune ligne ne peut pointer vers un fichier absent.
    suspend fun commitImport(prepared: PreparedImport): String {
        LoggedTrackGpxStore.dir(appContext).mkdirs()
        val days = prepared.days.mapIndexed { dayIndex, day ->
            val relativePath = LoggedTrackGpxStore.relativePath(prepared.entity.id, dayIndex)
            LoggedTrackGpxStore.resolve(appContext, relativePath).writeText(day.rawGpx, StandardCharsets.UTF_8)
            LoggedTrackDayEntity(
                trackId = prepared.entity.id,
                dayIndex = dayIndex,
                rawGpxFilePath = relativePath,
                contentHash = day.contentHash,
                startedAtMillis = day.startedAtMillis,
                elapsedSeconds = day.elapsedSeconds,
            )
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
        // Deux requêtes pour toute la banque, contre une par trace plus un parsing complet de
        // chacun de ses fichiers auparavant. C'était la cause de la lenteur d'import : le coût ne
        // dépendait pas de ce qu'on importait mais du nombre de traces déjà en banque, donc il
        // grossissait tout seul (constat B de la recette).
        val daysByTrack = dao.getAllDays().groupBy { it.trackId }
        return entries.mapNotNull { entry ->
            val days = daysByTrack[entry.id].orEmpty()
            // Une trace dont un seul jour n'est pas encore rattrapé repasse entièrement par
            // l'ancien chemin : mélanger une somme partielle avec des jours ignorés donnerait une
            // durée écoulée trop courte, donc une vitesse trop rapide, silencieusement.
            if (days.isNotEmpty() && days.all { it.contentHash != null }) {
                val elapsedHours = days.sumOf { it.elapsedSeconds ?: 0L } / 3_600.0
                if (elapsedHours <= 0.0) {
                    null
                } else {
                    SpeedCalibrationCalculator.Sample(
                        distanceMeters = entry.distanceMeters,
                        elevationGainMeters = entry.elevationGainMeters,
                        elapsedHours = elapsedHours,
                    )
                }
            } else {
                calibrationSample(entry)
            }
        }
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

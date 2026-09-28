package com.bivouac.app.data.db

import android.content.Context
import android.util.Log
import com.bivouac.app.data.gpx.DaySegmentSums
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.gpx.TrackStatsParameters
import com.bivouac.app.data.model.TrackPoint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

/**
 * Remplit après coup les colonnes dénormalisées de `logged_track_day` pour les traces importées
 * avant la migration 8 vers 9 (contentHash/startedAtMillis/elapsedSeconds, RIC-98/99), les sept
 * sommes de segments introduites par la migration 10 vers 11 (RIC-109 : flatCount et consorts) et
 * la huitième introduite par la migration 11 vers 12 (RIC-115 : stoppedHours, voir
 * [com.bivouac.app.data.gpx.DaySegmentAggregate]), étendu plutôt que dupliqué en un second
 * rattrapage séparé : le GPX est de toute façon déjà lu et parsé ici pour les trois premières
 * colonnes, calculer les segments à ce même endroit coûte une passe de plus sur des points déjà en
 * mémoire, alors qu'un second rattrapage indépendant relirait tous les fichiers depuis zéro. Voir
 * [LoggedTrackDayEntity] pour ce que ces colonnes portent.
 *
 * Pourquoi ici et pas dans la migration : le rattrapage lit et parse tous les fichiers de la
 * banque, ce qui se compte en secondes sur une archive un peu fournie. Le faire pendant la
 * migration figerait l'app à la première ouverture d'après mise à jour, sans le moindre retour à
 * l'écran, et rendrait cette migration capable d'échouer sur un fichier corrompu, alors qu'un
 * simple ALTER TABLE ne peut rien perdre.
 *
 * Le rattrapage est donc facultatif par construction : tant qu'une ligne n'est pas traitée, les
 * lecteurs retombent sur l'ancien chemin, celui qui reparse. C'est plus lent, jamais faux, et ça
 * se résorbe tout seul. Une trace illisible est marquée traitée avec un hash calculé sur le
 * contenu brut, pour ne pas la reprendre indéfiniment à chaque lancement.
 *
 * RIC-109 : le marqueur "pas encore traité" était devenu flatCount IS NULL, pas contentHash IS
 * NULL : une ligne déjà rattrapée par RIC-98/99 (donc avec un contentHash) repassait une fois de
 * plus ici pour recevoir les sommes de segments. RIC-115 relaie ce même marqueur une fois de plus,
 * à stoppedHours IS NULL (voir [LoggedTrackDao.getDaysNeedingBackfill]) : une ligne déjà rattrapée
 * jusqu'à RIC-109 (donc avec un flatCount) repasse à son tour ici pour recevoir stoppedHours, que
 * backfillOne calcule et écrit désormais dans la même passe que toutes les colonnes historiques.
 */
object LoggedTrackBackfill {

    // Par paquets, avec un point d'annulation entre chaque : le rattrapage tourne dans le scope du
    // ViewModel du Journal, quitter l'écran doit pouvoir l'arrêter net plutôt que de le laisser
    // finir 107 traces dans le vide.
    private const val BATCH_SIZE = 10

    suspend fun run(context: Context, dao: LoggedTrackDao) {
        val appContext = context.applicationContext
        val remaining = dao.countDaysNeedingBackfill()
        if (remaining == 0) return
        Log.i(TAG, "Rattrapage des colonnes dénormalisées : $remaining jour(s) à traiter")

        var processed = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = dao.getDaysNeedingBackfill(BATCH_SIZE)
            if (batch.isEmpty()) break
            for (day in batch) {
                currentCoroutineContext().ensureActive()
                backfillOne(appContext, dao, day)
                processed++
            }
        }
        Log.i(TAG, "Rattrapage terminé : $processed jour(s)")
    }

    private suspend fun backfillOne(context: Context, dao: LoggedTrackDao, day: LoggedTrackDayEntity) {
        val file = LoggedTrackGpxStore.resolve(context, day.rawGpxFilePath)
        val rawGpx = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrElse {
            // Fichier absent ou illisible : rien à dénormaliser, mais il faut sortir cette ligne
            // de la file d'attente. Un hash de chaîne vide n'entrera en collision avec aucun
            // fichier réel, donc la détection de doublon n'en est pas faussée. DaySegmentSums.EMPTY
            // (des zéros, pas des nuls) marque ce jour comme traité au même titre que les trois
            // colonnes historiques.
            Log.w(TAG, "Jour ${day.id} illisible, marqué traité sans donnée", it)
            dao.writeDenormalizedFields(day.id, sha256(""), null, null, DaySegmentSums.EMPTY)
            return
        }
        val contentHash = sha256(rawGpx)
        val points = runCatching {
            rawGpx.byteInputStream(StandardCharsets.UTF_8).use { GpxParser.parse(it) }.points
        }.getOrElse {
            Log.w(TAG, "Jour ${day.id} non parsable, hash seul", it)
            dao.writeDenormalizedFields(day.id, contentHash, null, null, DaySegmentSums.EMPTY)
            return
        }
        val first = points.firstOrNull()?.time
        val last = points.lastOrNull()?.time
        val elapsed = if (first != null && last != null) {
            Duration.between(first, last).seconds.takeIf { it > 0 }
        } else {
            null
        }
        dao.writeDenormalizedFields(day.id, contentHash, first?.toEpochMilli(), elapsed, DaySegmentSums.of(points))
    }

    // Regroupe les paramètres de segments en un seul appel lisible aux trois points d'appel
    // ci-dessus. RIC-146 : pausedSeconds et les lignes de rythme suivent les sommes de calibration,
    // dans la même transaction (voir LoggedTrackDao.applyDayBackfill).
    private suspend fun LoggedTrackDao.writeDenormalizedFields(
        id: Long,
        contentHash: String,
        startedAtMillis: Long?,
        elapsedSeconds: Long?,
        sums: DaySegmentSums,
    ) = applyDayBackfill(
        id = id,
        contentHash = contentHash,
        startedAtMillis = startedAtMillis,
        elapsedSeconds = elapsedSeconds,
        aggregate = sums.aggregate,
        pausedSeconds = sums.pausedSeconds,
        paceBands = sums.paceBands,
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * RIC-19 : rattrapage de maxElevationMeters/lastPointElevationMeters, séparé de [run] ci-dessus
     * plutôt que fusionné dans la même passe : deux raisons :
     *
     * 1. Déclenchement différent : [run] est fire-and-forget depuis JournalViewModel.init,
     *    annulable si l'utilisateur quitte l'écran Journal (RIC-132). Celui-ci est appelé depuis
     *    une porte bloquante au niveau de l'appli (voir ElevationBackfillGate), avant toute
     *    navigation : les fusionner forcerait l'un des deux appelants à connaître les contraintes
     *    de l'autre.
     * 2. Sur une banque déjà à jour pour RIC-109 (l'immense majorité des installations réelles,
     *    l'app étant sortie depuis un moment), [run] est un no-op immédiat et ce rattrapage-ci est
     *    le seul à lire quoi que ce soit : les fusionner n'aurait fait gagner qu'un util marginal
     *    (une seule lecture de fichier au lieu de deux) pour le cas, de plus en plus rare, d'une
     *    mise à jour directe depuis une version antérieure à RIC-98/99.
     *
     * [onProgress] est appelé après chaque jour traité (et une fois immédiatement avec le total),
     * pour piloter le spinner + compteur de la porte bloquante.
     */
    suspend fun runElevation(
        context: Context,
        dao: LoggedTrackDao,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val appContext = context.applicationContext
        val total = dao.countDaysNeedingElevationBackfill()
        if (total == 0) return
        Log.i(TAG, "Rattrapage altitude (RIC-19) : $total jour(s) à traiter")
        onProgress(0, total)

        var processed = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = dao.getDaysNeedingElevationBackfill(BATCH_SIZE)
            if (batch.isEmpty()) break
            for (day in batch) {
                currentCoroutineContext().ensureActive()
                backfillElevationOne(appContext, dao, day)
                processed++
                onProgress(processed, total)
            }
        }
        Log.i(TAG, "Rattrapage altitude terminé : $processed jour(s)")
    }

    private suspend fun backfillElevationOne(context: Context, dao: LoggedTrackDao, day: LoggedTrackDayEntity) {
        val file = LoggedTrackGpxStore.resolve(context, day.rawGpxFilePath)
        val points = runCatching {
            file.readText(StandardCharsets.UTF_8).byteInputStream(StandardCharsets.UTF_8)
                .use { GpxParser.parse(it) }.points
        }.getOrElse {
            // Fichier absent ou illisible : rien à mesurer, mais la ligne doit sortir de la file
            // d'attente au même titre que dans [run] : un rattrapage silencieux mais sans fin
            // n'apporterait rien de plus qu'un blocage éternel de la porte d'accueil.
            Log.w(TAG, "Jour ${day.id} illisible pour l'altitude, marqué traité sans donnée", it)
            dao.updateDayElevationFields(day.id, maxElevationMeters = null, lastPointElevationMeters = null)
            return
        }
        val maxElevation = points.mapNotNull { it.elevationMeters }.maxOrNull()
        val lastPointElevation = points.lastOrNull()?.elevationMeters
        dao.updateDayElevationFields(day.id, maxElevation, lastPointElevation)
    }

    /**
     * RIC-114 lot 2 : rattrapage des quatre statistiques dérivées de `logged_track`
     * (distance/D+/D-/durée) après une montée de [TrackStatsParameters.ALGORITHM_VERSION], voir
     * conception §5.3. Bloquant côté appelant (même porte que [runElevation], voir
     * ElevationBackfillGate), au même titre que RIC-19 : la calibration (phase 3, en aval) dépend
     * de ces valeurs, une trace mixte serait pire qu'un rattrapage qui retarde un peu la navigation.
     *
     * Par paquets de 10 traces, [onProgress] après chaque trace : même patron que [run] ci-dessus.
     * Une trace dont au moins un jour est illisible ou non parsable garde toutes ses anciennes
     * valeurs (jours ET trace, aucune écriture partielle) mais passe quand même à la version
     * courante (sinon rejouée à chaque lancement) : voir [backfillStatsOne].
     */
    suspend fun runStats(
        context: Context,
        dao: LoggedTrackDao,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val appContext = context.applicationContext
        val total = dao.countTracksNeedingStatsBackfill(TrackStatsParameters.ALGORITHM_VERSION)
        if (total == 0) return
        Log.i(TAG, "Rattrapage RIC-114 (Journal) : $total trace(s) à traiter")
        onProgress(0, total)

        var processed = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = dao.getTracksNeedingStatsBackfill(TrackStatsParameters.ALGORITHM_VERSION, BATCH_SIZE)
            if (batch.isEmpty()) break
            for (track in batch) {
                currentCoroutineContext().ensureActive()
                backfillStatsOne(appContext, dao, track)
                processed++
                onProgress(processed, total)
            }
        }
        Log.i(TAG, "Rattrapage RIC-114 (Journal) terminé : $processed trace(s)")
    }

    // Un jour déjà lu et parsé, avec ce qu'il faut pour reconstruire DayStatsUpdate : même
    // découpage que PreparedDay (LoggedTrackRepository.prepareImport), qui calcule exactement les
    // mêmes colonnes au même instant du parsing.
    private data class ParsedDay(
        val day: LoggedTrackDayEntity,
        val contentHash: String,
        val points: List<TrackPoint>,
        val startedAtMillis: Long?,
        val elapsedSeconds: Long?,
    )

    private suspend fun backfillStatsOne(context: Context, dao: LoggedTrackDao, track: LoggedTrackEntity) {
        val days = dao.getDays(track.id).sortedBy { it.dayIndex }
        val parsed = mutableListOf<ParsedDay>()
        for (day in days) {
            val file = LoggedTrackGpxStore.resolve(context, day.rawGpxFilePath)
            val rawGpx = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrElse {
                // Un seul jour illisible suffit à garder TOUTE la trace à ses anciennes valeurs
                // (conception §5.3) : un total partiel serait pire qu'un total ancien. La version
                // monte quand même, sinon cette trace serait rejouée à chaque lancement.
                Log.w(TAG, "Trace ${track.id} (RIC-114) : jour ${day.id} illisible, valeurs conservées", it)
                dao.markStatsVersion(track.id, TrackStatsParameters.ALGORITHM_VERSION)
                return
            }
            val points = runCatching {
                rawGpx.byteInputStream(StandardCharsets.UTF_8).use { GpxParser.parse(it) }.points
            }.getOrElse {
                Log.w(TAG, "Trace ${track.id} (RIC-114) : jour ${day.id} non parsable, valeurs conservées", it)
                dao.markStatsVersion(track.id, TrackStatsParameters.ALGORITHM_VERSION)
                return
            }
            val first = points.firstOrNull()?.time
            val last = points.lastOrNull()?.time
            val elapsed = if (first != null && last != null) {
                Duration.between(first, last).seconds.takeIf { it > 0 }
            } else {
                null
            }
            parsed += ParsedDay(day, sha256(rawGpx), points, first?.toEpochMilli(), elapsed)
        }
        // Durée recalculée avec SpeedCalibration.DEFAULT (conception §5.3) : jamais affichée telle
        // quelle (tous les affichages repassent par recomputeDuration sous la calibration active),
        // elle doit seulement rester cohérente avec les trois autres colonnes.
        //
        // RIC-207 : distance/D+/D- de dayStats deviennent aussi les totaux stockés du jour
        // (DayStatsUpdate.distanceMeters et consorts) : la somme de ces totaux par jour reste par
        // construction égale à la somme passée à applyStatsBackfill pour la trace ci-dessous, ce qui
        // est exactement l'invariant "somme des jours == total de la rando".
        val dayStats = parsed.map { TrackStatsCalculator.compute(it.points, SpeedCalibration.DEFAULT) }
        //
        // RIC-146 (ALGORITHM_VERSION 3) : les sommes de calibration passent à la définition fine
        // des pauses, et chaque jour reçoit pausedSeconds et ses lignes de rythme par pente dans
        // la même transaction (DaySegmentSums).
        val dayUpdates = parsed.zip(dayStats).map { (p, stats) ->
            val sums = DaySegmentSums.of(p.points)
            DayStatsUpdate(
                id = p.day.id,
                contentHash = p.contentHash,
                startedAtMillis = p.startedAtMillis,
                elapsedSeconds = p.elapsedSeconds,
                aggregate = sums.aggregate,
                distanceMeters = stats.distanceMeters,
                elevationGainMeters = stats.elevationGainMeters,
                elevationLossMeters = stats.elevationLossMeters,
                pausedSeconds = sums.pausedSeconds,
                paceBands = sums.paceBands,
            )
        }
        dao.applyStatsBackfill(
            trackId = track.id,
            distanceMeters = dayStats.sumOf { it.distanceMeters },
            elevationGainMeters = dayStats.sumOf { it.elevationGainMeters },
            elevationLossMeters = dayStats.sumOf { it.elevationLossMeters },
            estimatedDurationMinutes = dayStats.sumOf { it.estimatedDurationMinutes },
            statsVersion = TrackStatsParameters.ALGORITHM_VERSION,
            dayUpdates = dayUpdates,
        )
    }

    private const val TAG = "LoggedTrackBackfill"
}

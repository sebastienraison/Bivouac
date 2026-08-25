package com.bivouac.app.data.db

import android.content.Context
import android.util.Log
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.TrackSegmenter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

/**
 * Remplit après coup les colonnes dénormalisées de `logged_track_day` pour les traces importées
 * avant la migration 8 vers 9 (contentHash/startedAtMillis/elapsedSeconds, RIC-98/99), ainsi que
 * les sept sommes de segments introduites par la migration 10 vers 11 (RIC-109 : flatCount et
 * consorts, voir [com.bivouac.app.data.gpx.DaySegmentAggregate]) — étendu plutôt que dupliqué en un
 * second rattrapage séparé : le GPX est de toute façon déjà lu et parsé ici pour les trois premières
 * colonnes, calculer les segments à ce même endroit coûte une passe de plus sur des points déjà en
 * mémoire, alors qu'un second rattrapage indépendant relirait tous les fichiers depuis zéro. Voir
 * [LoggedTrackDayEntity] pour ce que ces colonnes portent.
 *
 * Pourquoi ici et pas dans la migration : le rattrapage lit et parse tous les fichiers de la
 * banque, ce qui se compte en secondes sur une archive un peu fournie. Le faire pendant la
 * migration figerait l'app à la première ouverture d'après mise à jour, sans le moindre retour à
 * l'écran, et rendrait cette migration capable d'échouer sur un fichier corrompu — alors qu'un
 * simple ALTER TABLE ne peut rien perdre.
 *
 * Le rattrapage est donc facultatif par construction : tant qu'une ligne n'est pas traitée, les
 * lecteurs retombent sur l'ancien chemin, celui qui reparse. C'est plus lent, jamais faux, et ça
 * se résorbe tout seul. Une trace illisible est marquée traitée avec un hash calculé sur le
 * contenu brut, pour ne pas la reprendre indéfiniment à chaque lancement.
 *
 * RIC-109 : le marqueur "pas encore traité" est désormais flatCount IS NULL, pas contentHash IS
 * NULL (voir [LoggedTrackDao.getDaysNeedingBackfill]) — une ligne déjà rattrapée par RIC-98/99 (donc
 * avec un contentHash) repasse une fois de plus ici pour recevoir les sommes de segments, que
 * backfillOne calcule et écrit désormais dans la même passe que les trois colonnes historiques.
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
            // fichier réel, donc la détection de doublon n'en est pas faussée. DaySegmentAggregate
            // .EMPTY (des zéros, pas des nuls) marque ce jour comme traité au même titre que les
            // trois colonnes historiques.
            Log.w(TAG, "Jour ${day.id} illisible, marqué traité sans donnée", it)
            dao.writeDenormalizedFields(day.id, sha256(""), null, null, DaySegmentAggregate.EMPTY)
            return
        }
        val contentHash = sha256(rawGpx)
        val points = runCatching {
            rawGpx.byteInputStream(StandardCharsets.UTF_8).use { GpxParser.parse(it) }.points
        }.getOrElse {
            Log.w(TAG, "Jour ${day.id} non parsable, hash seul", it)
            dao.writeDenormalizedFields(day.id, contentHash, null, null, DaySegmentAggregate.EMPTY)
            return
        }
        val first = points.firstOrNull()?.time
        val last = points.lastOrNull()?.time
        val elapsed = if (first != null && last != null) {
            Duration.between(first, last).seconds.takeIf { it > 0 }
        } else {
            null
        }
        val aggregate = DaySegmentAggregate.of(TrackSegmenter.segment(points))
        dao.writeDenormalizedFields(day.id, contentHash, first?.toEpochMilli(), elapsed, aggregate)
    }

    // Regroupe les sept paramètres de segments en un seul appel lisible, plutôt que de répéter
    // aggregate.flatCount, aggregate.flatDistanceMeters, ... aux trois points d'appel ci-dessus.
    private suspend fun LoggedTrackDao.writeDenormalizedFields(
        id: Long,
        contentHash: String,
        startedAtMillis: Long?,
        elapsedSeconds: Long?,
        aggregate: DaySegmentAggregate,
    ) = updateDayDenormalizedFields(
        id = id,
        contentHash = contentHash,
        startedAtMillis = startedAtMillis,
        elapsedSeconds = elapsedSeconds,
        flatCount = aggregate.flatCount,
        flatDistanceMeters = aggregate.flatDistanceMeters,
        flatHours = aggregate.flatHours,
        steepCount = aggregate.steepCount,
        steepDistanceMeters = aggregate.steepDistanceMeters,
        steepGainMeters = aggregate.steepGainMeters,
        steepHours = aggregate.steepHours,
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * RIC-19 : rattrapage de maxElevationMeters/lastPointElevationMeters, séparé de [run] ci-dessus
     * plutôt que fusionné dans la même passe — deux raisons :
     *
     * 1. Déclenchement différent : [run] est fire-and-forget depuis JournalViewModel.init,
     *    annulable si l'utilisateur quitte l'écran Journal (RIC-132). Celui-ci est appelé depuis
     *    une porte bloquante au niveau de l'appli (voir ElevationBackfillGate), avant toute
     *    navigation — les fusionner forcerait l'un des deux appelants à connaître les contraintes
     *    de l'autre.
     * 2. Sur une banque déjà à jour pour RIC-109 (l'immense majorité des installations réelles,
     *    l'app étant sortie depuis un moment), [run] est un no-op immédiat et ce rattrapage-ci est
     *    le seul à lire quoi que ce soit — les fusionner n'aurait fait gagner qu'un util marginal
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
            // d'attente au même titre que dans [run] — un rattrapage silencieux mais sans fin
            // n'apporterait rien de plus qu'un blocage éternel de la porte d'accueil.
            Log.w(TAG, "Jour ${day.id} illisible pour l'altitude, marqué traité sans donnée", it)
            dao.updateDayElevationFields(day.id, maxElevationMeters = null, lastPointElevationMeters = null)
            return
        }
        val maxElevation = points.mapNotNull { it.elevationMeters }.maxOrNull()
        val lastPointElevation = points.lastOrNull()?.elevationMeters
        dao.updateDayElevationFields(day.id, maxElevation, lastPointElevation)
    }

    private const val TAG = "LoggedTrackBackfill"
}

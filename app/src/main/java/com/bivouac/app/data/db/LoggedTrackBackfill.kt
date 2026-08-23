package com.bivouac.app.data.db

import android.content.Context
import android.util.Log
import com.bivouac.app.data.gpx.GpxParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

/**
 * Remplit après coup les colonnes dénormalisées de `logged_track_day` pour les traces importées
 * avant la migration 8 vers 9. Voir [LoggedTrackDayEntity] pour ce qu'elles portent.
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
            // fichier réel, donc la détection de doublon n'en est pas faussée.
            Log.w(TAG, "Jour ${day.id} illisible, marqué traité sans donnée", it)
            dao.updateDayDenormalizedFields(day.id, sha256(""), null, null)
            return
        }
        val contentHash = sha256(rawGpx)
        val times = runCatching {
            rawGpx.byteInputStream(StandardCharsets.UTF_8).use { GpxParser.parse(it) }.points
        }.getOrElse {
            Log.w(TAG, "Jour ${day.id} non parsable, hash seul", it)
            dao.updateDayDenormalizedFields(day.id, contentHash, null, null)
            return
        }
        val first = times.firstOrNull()?.time
        val last = times.lastOrNull()?.time
        val elapsed = if (first != null && last != null) {
            Duration.between(first, last).seconds.takeIf { it > 0 }
        } else {
            null
        }
        dao.updateDayDenormalizedFields(day.id, contentHash, first?.toEpochMilli(), elapsed)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val TAG = "LoggedTrackBackfill"
}

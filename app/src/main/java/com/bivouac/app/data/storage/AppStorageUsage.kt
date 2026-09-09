package com.bivouac.app.data.storage

import android.content.Context
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import com.bivouac.app.data.db.PlanificationGpxStore
import com.bivouac.app.data.photo.PhotoRecompression
import com.bivouac.app.data.photo.PhotoStorageMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RIC-140 : la part des photos dans le stockage de l'app, décomposée par mode de stockage.
 *
 * Les deux modes sont comptés séparément parce que c'est exactement la question que se pose
 * quelqu'un qui vient de découvrir le réglage de RIC-157 : combien pèse ce que j'ai déjà, et
 * combien en reste-t-il en pleine résolution ?
 *
 * [missingCount] n'est pas une anomalie à corriger d'ici : une ligne dont le fichier a disparu
 * compte pour zéro octet, ce qui est exact, et c'est « Retrouver les photos manquantes » (RIC-151)
 * qui s'en occupe. Elle est comptée pour que le nombre de photos affiché ici ne contredise pas
 * celui du Journal.
 *
 * [directoryBytes] est le poids réel du dossier `photos/`, qui peut dépasser la somme des deux
 * modes : un fichier orphelin (import interrompu, ligne supprimée pendant qu'une écriture était en
 * vol) occupe de la place sans qu'aucune ligne ne le revendique. C'est lui qui fait foi dans le
 * total, sans quoi l'écran annoncerait moins que ce que le système facture à l'app.
 */
data class PhotoStorageUsage(
    val fullCount: Int,
    val fullBytes: Long,
    val reducedCount: Int,
    val reducedBytes: Long,
    val missingCount: Int,
    val directoryBytes: Long,
) {
    val count: Int get() = fullCount + reducedCount
}

/**
 * RIC-140 : ce que Bivouac occupe sur le téléphone, poste par poste.
 *
 * Le découpage suit ce que l'utilisateur peut décider d'en faire, pas l'arborescence : les GPX
 * (supprimables en supprimant des sorties), les photos (recompressibles, purgeables), la base
 * (incompressible), et le reste, qui n'est actionnable par rien et n'est là que pour que la somme
 * des postes soit bien le total.
 */
data class AppStorageUsage(
    val gpxJournalBytes: Long,
    val gpxJournalFileCount: Int,
    val gpxPlanificationBytes: Long,
    val gpxPlanificationFileCount: Int,
    val photos: PhotoStorageUsage,
    val databaseBytes: Long,
    val otherBytes: Long,
    /** RIC-157 : ce que la recompression du stock peut reprendre, ou null s'il n'y a rien à proposer. */
    val recompression: PhotoRecompression.Estimate?,
) {
    val gpxBytes: Long get() = gpxJournalBytes + gpxPlanificationBytes

    val totalBytes: Long get() = gpxBytes + photos.directoryBytes + databaseBytes + otherBytes
}

/**
 * RIC-140 : le relevé lui-même.
 *
 * **Calculé à la demande, jamais observé.** Chaque poste est une somme de `length()` sur des
 * arborescences entières : c'est rapide (quelques centaines de `stat`), mais ça reste du disque, et
 * le refaire à chaque recomposition transformerait un écran de consultation en boucle d'IO. L'écran
 * appelle une fois à son ouverture et affiche un état de chargement en attendant.
 *
 * **Le total part de ce que le système facture, pas de ce que la base déclare.** Les postes GPX et
 * photos sont mesurés sur les répertoires entiers, orphelins compris ; les décomptes par ligne
 * (nombre de photos, répartition par mode) viennent de la base et servent à raconter, pas à
 * totaliser. Sans quoi l'écran annoncerait un chiffre plus petit que les Réglages d'Android, sans
 * que personne puisse expliquer l'écart.
 */
object AppStorageUsageCalculator {

    suspend fun compute(context: Context, photos: List<LoggedTrackPhotoEntity>): AppStorageUsage =
        withContext(Dispatchers.IO) {
            val gpxJournal = LoggedTrackGpxStore.dir(context).measure()
            val gpxPlanification = PlanificationGpxStore.dir(context).measure()
            val photosDir = LoggedTrackPhotoStore.dir(context).measure()

            var fullCount = 0
            var fullBytes = 0L
            var reducedCount = 0
            var reducedBytes = 0L
            var missingCount = 0
            val recompressionCandidateBytes = mutableListOf<Long>()
            for (photo in photos) {
                val length = LoggedTrackPhotoStore.resolve(context, photo.filePath).lengthOrZero()
                if (length == 0L) missingCount++
                when (photo.storageMode) {
                    PhotoStorageMode.FULL -> {
                        fullCount++
                        fullBytes += length
                        if (length > 0L && PhotoRecompression.isRecompressible(photo)) {
                            recompressionCandidateBytes += length
                        }
                    }
                    PhotoStorageMode.REDUCED -> {
                        reducedCount++
                        reducedBytes += length
                    }
                }
            }

            val databaseBytes = databaseBytes(context)
            // Tout ce que l'app occupe et qui n'est aucun des postes ci-dessus : les préférences
            // DataStore, la zone de transit des photos, les caches de la carte. Mesuré par
            // différence plutôt qu'énuméré, pour que le total reste juste même quand une
            // bibliothèque écrit là où on ne l'attend pas.
            val otherBytes = (
                context.filesDir.measure().bytes - gpxJournal.bytes - gpxPlanification.bytes - photosDir.bytes +
                    context.cacheDir.measure().bytes
                ).coerceAtLeast(0L)

            AppStorageUsage(
                gpxJournalBytes = gpxJournal.bytes,
                gpxJournalFileCount = gpxJournal.files,
                gpxPlanificationBytes = gpxPlanification.bytes,
                gpxPlanificationFileCount = gpxPlanification.files,
                photos = PhotoStorageUsage(
                    fullCount = fullCount,
                    fullBytes = fullBytes,
                    reducedCount = reducedCount,
                    reducedBytes = reducedBytes,
                    missingCount = missingCount,
                    directoryBytes = photosDir.bytes,
                ),
                databaseBytes = databaseBytes,
                otherBytes = otherBytes,
                recompression = PhotoRecompression.estimate(
                    candidateFileBytes = recompressionCandidateBytes,
                    reducedSampleCount = reducedCount,
                    reducedSampleBytes = reducedBytes,
                ),
            )
        }

    /**
     * La base et ses deux fichiers annexes.
     *
     * `-wal` et `-shm` comptent : sur une base en mode WAL ils portent des pages bien réelles, et
     * le `-wal` d'une session chargée n'a rien d'anecdotique. Mêmes suffixes que ce que
     * BackupManager sauvegarde et restaure, pour la même raison.
     */
    private fun databaseBytes(context: Context): Long {
        val database = context.getDatabasePath(BivouacDatabase.DATABASE_NAME)
        return listOf("", "-wal", "-shm").sumOf { File(database.path + it).lengthOrZero() }
    }

    private data class DirectoryUsage(val bytes: Long, val files: Int)

    // walkTopDown et non listFiles : gpx/ et photos/ sont plats aujourd'hui, mais un total faux
    // parce qu'un sous-dossier est apparu est exactement le genre d'erreur qu'on ne voit jamais.
    private fun File.measure(): DirectoryUsage {
        if (!exists()) return DirectoryUsage(0L, 0)
        var bytes = 0L
        var files = 0
        walkTopDown().forEach {
            if (it.isFile) {
                bytes += it.lengthOrZero()
                files++
            }
        }
        return DirectoryUsage(bytes, files)
    }

    // length() rend 0 sur un fichier absent ou illisible, ce qui est déjà la bonne réponse ici :
    // un poste qu'on ne sait pas mesurer vaut mieux à zéro qu'en exception au milieu d'un relevé.
    private fun File.lengthOrZero(): Long = runCatching { if (isFile) length() else 0L }.getOrDefault(0L)
}

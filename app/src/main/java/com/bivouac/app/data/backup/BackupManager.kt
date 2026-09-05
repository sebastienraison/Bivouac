package com.bivouac.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import com.bivouac.app.data.db.PlanificationGpxStore
import com.bivouac.app.data.prefs.MAP_LAYER_DATASTORE_NAME
import com.bivouac.app.data.prefs.SETTINGS_DATASTORE_NAME
import com.bivouac.app.data.prefs.SettingsPreferences
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface RestoreResult {
    data object Success : RestoreResult
    data class VersionTooNew(val backupVersion: Int, val appVersion: Int) : RestoreResult
    data class Error(val message: String) : RestoreResult
}

/** RIC-156 : les deux temps d'une restauration, tels que le dialogue bloquant les annonce. */
enum class RestorePhase {
    /** Lecture de l'archive et extraction en zone temporaire. Dénombrable si l'archive a un manifeste. */
    EXTRACTION,

    /** Contrôle d'intégrité puis remplacement des fichiers en place. Non dénombrable, et bien plus court. */
    REPLACEMENT,
}

/** RIC-156 : où en est une restauration. [total] est null quand l'archive ne dit pas ce qu'elle contient. */
data class RestoreProgress(val phase: RestorePhase, val done: Int, val total: Int?)

/**
 * Full-database backup/restore (BIV-66): a raw copy of bivouac.db (plus its WAL/SHM sidecars, if
 * SQLite hasn't already checkpointed them away), the DataStore preference files, the Journal's raw
 * GPX files (filesDir/gpx, RIC-62), Planification's own GPX files (filesDir/gpx-planif, RIC-97) and
 * the Journal's photos (filesDir/photos, RIC-43),
 * zipped via SAF so the destination can be anywhere the system document picker reaches (Drive,
 * Nextcloud, local storage...). Deliberately not a structured export: this is a byte-for-byte
 * safety net for aggressive test sessions and real-device recette, not a portable/partial format:
 * see BIV-21 for the curated per-trace GPX export that already covers that need.
 */
object BackupManager {

    private const val DB_ENTRY_PREFIX = "db/"
    private const val PREFS_ENTRY_PREFIX = "prefs/"

    // RIC-62 : le GPX brut du Journal ne vit plus dans bivouac.db mais dans filesDir/gpx/ : sans
    // ces entrées, une sauvegarde v8 serait amputée de tout son contenu de traces.
    private const val GPX_ENTRY_PREFIX = LoggedTrackGpxStore.DIR_NAME + "/"

    // RIC-97 : même raison côté Planification (banked_track et saved_track), dans filesDir/gpx-planif/.
    private const val GPX_PLANIF_ENTRY_PREFIX = PlanificationGpxStore.DIR_NAME + "/"

    // RIC-43 : même raison pour les photos du Journal, dans filesDir/photos/. Ce sont les seules
    // données de l'app qui ne se reconstituent pas depuis un GPX : une archive sans elles ramène
    // des lignes logged_track_photo dont les fichiers n'existent nulle part.
    private const val PHOTOS_ENTRY_PREFIX = LoggedTrackPhotoStore.DIR_NAME + "/"

    // Only ever consulted together, and always the same three suffixes: see BivouacDatabase's
    // own comment on closeAndReset() for why a clean close should normally leave -wal/-shm empty
    // or absent, but they're still backed up/restored if present, just in case.
    private val DB_SIDECAR_SUFFIXES = listOf("", "-wal", "-shm")
    private val PREFS_FILE_NAMES = listOf("$MAP_LAYER_DATASTORE_NAME.preferences_pb", "$SETTINGS_DATASTORE_NAME.preferences_pb")

    /**
     * RIC-156 : manifeste écrit en TÊTE de l'archive, avant la moindre donnée.
     *
     * Il porte le nombre d'entrées de données que l'archive est censée contenir. C'est ce qui rend
     * une archive tronquée détectable : un zip est une suite d'en-têtes locaux suivis d'un annuaire
     * central écrit à la fermeture, et [ZipInputStream] ne lit que les en-têtes locaux, séquentiellement.
     * Une écriture interrompue pile sur une frontière d'entrée produit donc un fichier que rien, dans
     * le format lui-même, ne distingue d'une archive complète, et comme bivouac.db est zippé en
     * premier, cette archive amputée passait le contrôle d'intégrité SQLite (RIC-95) et restaurait
     * une base saine sans ses photos ni ses GPX, en écrasant définitivement celles de l'appareil.
     *
     * Sert accessoirement de dénominateur à la progression de la restauration. Les archives
     * antérieures à RIC-156 n'en ont pas : elles restent restaurables, sans ce contrôle et sans
     * compteur (voir [extractZip]).
     */
    private const val MANIFEST_ENTRY_NAME = "manifest.properties"
    private const val MANIFEST_FILE_COUNT_KEY = "fileCount"

    suspend fun backup(
        context: Context,
        destination: Uri,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            writeBackup(context, destination, onProgress)
            Result.success(Unit)
        } catch (e: CancellationException) {
            // RIC-156 : une sauvegarde annulée en cours de route (l'utilisateur quitte l'app, le
            // process meurt proprement) ne doit pas laisser derrière elle un fichier d'apparence
            // normale. Le nettoyage n'est pas suspendu, il s'exécute donc bien alors que la
            // coroutine est déjà annulée.
            discardPartialBackup(context, destination)
            throw e
        } catch (e: Exception) {
            discardPartialBackup(context, destination)
            Result.failure(e)
        }
    }

    private suspend fun writeBackup(context: Context, destination: Uri, onProgress: (Int, Int) -> Unit) {
        // Stamped into the settings file *before* it gets zipped, not after backup() returns:
        // that way the backup is self-describing: restoring it later naturally shows the date
        // it was taken as "Dernière sauvegarde", instead of whatever (or nothing) was live at
        // restore time.
        SettingsPreferences(context).setLastBackupAtMillis(System.currentTimeMillis())

        // RIC-128 : synchronized(BivouacDatabase) partage le même moniteur que getInstance()/
        // closeAndReset() (synchronized(this) dans leur companion object, Kotlin résout le
        // nom de classe nu vers l'instance du companion). Sans ce verrou, un accès DB tiers
        // pendant la boucle de copie ci-dessous rouvrait silencieusement la base via
        // getInstance() : une écriture concurrente était alors commise en base mais pouvait
        // être absente de l'archive déjà en cours de zip, sans que la sauvegarde le signale
        // comme échouée. Verrou bloquant (pas de Mutex coroutine) volontairement : ce bloc ne
        // contient aucun point de suspension réel (aucun des appels ci-dessous n'est un
        // `suspend fun`), donc pas de risque de tenir le moniteur au travers d'un changement
        // de thread : cohérent avec synchronized(this) déjà utilisé par ailleurs dans
        // BivouacDatabase, appelé depuis ces mêmes contextes suspendus sans souci.
        val bytesWritten = synchronized(BivouacDatabase) {
            // Closing Room forces a WAL checkpoint and flushes any pending writes into
            // bivouac.db itself, so the plain file copy below is consistent even without a
            // filesystem-level transaction wrapping it.
            BivouacDatabase.closeAndReset()
            try {
                // RIC-156 : le catalogue est arrêté AVANT d'ouvrir la destination : c'est ce qui
                // donne son dénominateur à la progression, et le compte que le manifeste annonce.
                val entries = collectBackupEntries(context)
                val output = context.contentResolver.openOutputStream(destination)
                    ?: throw IOException("Impossible d'ouvrir la destination sélectionnée.")
                val counting = CountingOutputStream(output)
                ZipOutputStream(counting).use { zip ->
                    writeManifest(zip, entries.size)
                    onProgress(0, entries.size)
                    entries.forEachIndexed { index, (name, file) ->
                        writeEntry(zip, name, file)
                        onProgress(index + 1, entries.size)
                    }
                }
                counting.bytesWritten
            } finally {
                // Re-primes the singleton right away rather than leaving it null until
                // whatever screen happens to touch the DB next: a backup shouldn't leave the
                // app in a half-initialized state if the user keeps using it right after.
                BivouacDatabase.getInstance(context)
            }
        }
        verifyWrittenSize(context, destination, bytesWritten)
    }

    /**
     * RIC-156 : tout ce que l'archive doit contenir, nom d'entrée compris, arrêté en une fois.
     *
     * L'ordre est celui d'écriture historique (base, préférences, gpx/, gpx-planif/, photos/) : la
     * base d'abord, pour qu'une archive lue par un humain commence par l'essentiel.
     */
    private fun collectBackupEntries(context: Context): List<Pair<String, File>> {
        val dbFile = context.getDatabasePath(BivouacDatabase.DATABASE_NAME)
        val datastoreDir = File(context.filesDir, "datastore")
        val entries = mutableListOf<Pair<String, File>>()
        for (suffix in DB_SIDECAR_SUFFIXES) {
            val file = File(dbFile.parentFile, dbFile.name + suffix)
            if (file.exists()) entries += (DB_ENTRY_PREFIX + file.name) to file
        }
        for (name in PREFS_FILE_NAMES) {
            val file = File(datastoreDir, name)
            if (file.exists()) entries += (PREFS_ENTRY_PREFIX + file.name) to file
        }
        for (file in LoggedTrackGpxStore.dir(context).listFiles().orEmpty()) {
            if (file.isFile) entries += (GPX_ENTRY_PREFIX + file.name) to file
        }
        for (file in PlanificationGpxStore.dir(context).listFiles().orEmpty()) {
            if (file.isFile) entries += (GPX_PLANIF_ENTRY_PREFIX + file.name) to file
        }
        for (file in LoggedTrackPhotoStore.dir(context).listFiles().orEmpty()) {
            if (file.isFile) entries += (PHOTOS_ENTRY_PREFIX + file.name) to file
        }
        return entries
    }

    private fun writeManifest(zip: ZipOutputStream, fileCount: Int) {
        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
        zip.write(
            (
                "# Sauvegarde Bivouac. Ne pas modifier : ce compte est ce qui permet de détecter\n" +
                    "# une archive tronquée avant qu'elle ne remplace des données saines.\n" +
                    "$MANIFEST_FILE_COUNT_KEY=$fileCount\n"
                ).toByteArray(),
        )
        zip.closeEntry()
    }

    /**
     * RIC-156 : la destination passe par SAF, on ne peut donc ni écrire à côté puis renommer, ni
     * garantir l'atomicité de l'écriture : le document est créé par le sélecteur AVANT que quoi que
     * ce soit y soit écrit, et le fournisseur peut être distant (Drive, Nextcloud).
     *
     * Le compromis retenu, faute de mieux, est en trois temps :
     *  - le manifeste en tête rend toute troncature détectable à la RESTAURATION, donc avant que
     *    l'archive douteuse ne puisse écraser quoi que ce soit ;
     *  - cette vérification-ci compare, juste après fermeture, la taille annoncée par le
     *    fournisseur au nombre d'octets réellement poussés : elle attrape le cas « le fournisseur
     *    n'a pas tout gardé » (quota, coupure d'upload) tout de suite, et pas six mois plus tard ;
     *  - un échec, quel qu'il soit, supprime le document (voir [discardPartialBackup]) : jamais de
     *    zip partiel présenté à l'utilisateur comme une sauvegarde.
     *
     * Seule une taille STRICTEMENT inférieure est considérée comme un échec, et une taille inconnue
     * est acceptée : certains fournisseurs ne renseignent pas la colonne, d'autres comptent autre
     * chose que les octets du flux. Supprimer une sauvegarde correcte sur un doute coûterait
     * infiniment plus cher que de laisser passer un cas exotique, que le manifeste rattrapera.
     */
    private fun verifyWrittenSize(context: Context, destination: Uri, bytesWritten: Long) {
        val reported = runCatching {
            context.contentResolver.query(destination, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
                }
        }.getOrNull() ?: return
        if (reported < bytesWritten) {
            throw IOException(
                "La sauvegarde n'a pas été écrite en entier ($reported octets sur $bytesWritten). " +
                    "Vérifie l'espace disponible sur la destination, puis recommence.",
            )
        }
    }

    /**
     * RIC-156 : supprime le document de destination après un échec ou une annulation.
     *
     * Sans ça, la moindre interruption laissait un .zip d'apparence normale, à la bonne date, dans
     * le répertoire de sauvegardes de l'utilisateur, indiscernable d'une bonne archive au moment
     * où il en aurait le plus besoin. Toute erreur de suppression est ignorée : on est déjà dans le
     * chemin d'échec, et le manifeste reste le filet de sécurité à la restauration.
     */
    private fun discardPartialBackup(context: Context, destination: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
    }

    /** Compte les octets réellement poussés vers la destination, pour [verifyWrittenSize]. */
    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    suspend fun restore(
        context: Context,
        source: Uri,
        onProgress: (RestoreProgress) -> Unit = {},
    ): RestoreResult = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "restore-${System.nanoTime()}")
        try {
            tempDir.mkdirs()
            onProgress(RestoreProgress(RestorePhase.EXTRACTION, done = 0, total = null))
            val extraction = extractZip(context, source, tempDir) { done, total ->
                onProgress(RestoreProgress(RestorePhase.EXTRACTION, done, total))
            }
            if (extraction.errorMessage != null) {
                return@withContext RestoreResult.Error(extraction.errorMessage)
            }
            onProgress(RestoreProgress(RestorePhase.REPLACEMENT, done = 0, total = null))

            val extractedDb = File(tempDir, BivouacDatabase.DATABASE_NAME)
            if (!extractedDb.exists()) {
                return@withContext RestoreResult.Error("Cette archive ne contient pas de base Bivouac (bivouac.db manquant).")
            }

            // RIC-95 : l'intégrité de la base extraite est vérifiée AVANT de toucher au moindre
            // fichier en place : une archive tronquée ou corrompue ne doit jamais remplacer une
            // base saine.
            if (!passesIntegrityCheck(extractedDb)) {
                return@withContext RestoreResult.Error(
                    "L'archive contient une base corrompue ou illisible : restauration annulée, les données actuelles sont intactes.",
                )
            }

            val backupVersion = readUserVersion(extractedDb)
            if (backupVersion > BivouacDatabase.SCHEMA_VERSION) {
                return@withContext RestoreResult.VersionTooNew(backupVersion, BivouacDatabase.SCHEMA_VERSION)
            }

            // RIC-158 : même moniteur que backup() (RIC-128) et pour la même raison, pas
            // seulement pour se protéger d'un backup() concurrent : celui-là est déjà exclu par
            // ExclusiveOperations (RIC-156), qui empêche toute paire de ces opérations de démarrer
            // en même temps. Ce que ExclusiveOperations NE couvre PAS, c'est un accès DB tiers qui
            // n'en fait pas partie : n'importe quel repository, sur n'importe quel écran, peut
            // appeler BivouacDatabase.getInstance() à tout moment (lecture réactive d'un
            // StateFlow, recomposition...). Sans ce verrou, un tel appel pendant la fenêtre
            // ci-dessous rouvrirait silencieusement une base (vide ou à moitié remplacée, selon
            // l'instant exact) pendant que replaceWithRollback() écarte puis remplace ses
            // fichiers, un risque plus grave ici que pour backup() puisque les fichiers eux-mêmes
            // sont renommés/remplacés, pas seulement copiés. Réentrant sans risque : closeAndReset()
            // et getInstance() reprennent le même moniteur (synchronized(this) sur le companion
            // object, qui résout vers cette même instance de classe) depuis le même thread, et ce
            // bloc ne traverse aucun point de suspension (aucun appel ci-dessous n'est `suspend`),
            // donc jamais tenu au travers d'un changement de thread : même raisonnement que
            // BackupManager.writeBackup.
            synchronized(BivouacDatabase) {
                BivouacDatabase.closeAndReset()
                try {
                    replaceWithRollback(context, tempDir)
                } finally {
                    BivouacDatabase.getInstance(context)
                }
            }
            sweepOrphanPhotoFiles(context)
            RestoreResult.Success
        } catch (e: Exception) {
            RestoreResult.Error(e.message ?: "Échec de la restauration.")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * RIC-43 : après une restauration réussie, les fichiers de `photos/` que plus aucune ligne ne
     * référence sont supprimés.
     *
     * Le remplacement en bloc du répertoire couvre déjà le cas courant, mais pas tout : une archive
     * peut avoir été zippée pendant qu'une écriture était à moitié faite (fichier écrit, ligne pas
     * encore insérée), et un rollback partiel d'un ajout laisse la même trace. Sans ce balayage,
     * ces fichiers restent sur le stockage pour toujours sans que rien ne puisse plus les nommer.
     *
     * Volontairement asymétrique : l'inverse (une ligne dont le fichier manque) n'est PAS traité
     * ici. Ces lignes portent les métadonnées d'origine qui serviront à re-acquérir la photo depuis
     * la galerie (RIC-151) ; les supprimer perdrait la seule chose qui reste d'elle. Voir
     * LoggedTrackRepository.missingPhotoFileIds pour la façon dont l'app les affiche.
     *
     * Le balayage n'est jamais une cause d'échec de restauration : celle-ci a déjà réussi quand il
     * s'exécute, et une purge ratée ne coûte que du stockage.
     */
    private suspend fun sweepOrphanPhotoFiles(context: Context) {
        runCatching {
            val dir = LoggedTrackPhotoStore.dir(context)
            val files = dir.listFiles().orEmpty().filter { it.isFile }
            if (files.isEmpty()) return@runCatching
            // Le store est plat (voir LoggedTrackPhotoStore.relativePath) : comparer les noms de
            // fichier suffit, et évite de dépendre de la façon dont le chemin relatif est écrit.
            val referenced = BivouacDatabase.getInstance(context).loggedTrackDao()
                .getAllPhotoFilePaths()
                .mapTo(mutableSetOf()) { it.substringAfterLast('/') }
            files.filterNot { it.name in referenced }.forEach { it.delete() }
        }
    }

    private const val PRE_RESTORE_SUFFIX = ".pre-restore"

    /**
     * RIC-95 : le remplacement couvre plusieurs fichiers (base + sidecars + préférences), il ne
     * peut pas être atomique au sens filesystem, mais chaque fichier existant est d'abord écarté
     * par un rename (atomique, même répertoire) en `*.pre-restore` avant d'écrire son remplaçant.
     * Si une copie échoue à mi-chemin, tout l'état d'origine est remis en place tel quel au lieu
     * de laisser un mélange mi-ancien mi-nouveau ; l'ancien état n'est purgé qu'une fois tous les
     * nouveaux fichiers écrits.
     */
    private fun replaceWithRollback(context: Context, tempDir: File) {
        val dbFile = context.getDatabasePath(BivouacDatabase.DATABASE_NAME)
        val datastoreDir = File(context.filesDir, "datastore").apply { mkdirs() }

        // destination -> fichier extrait à y copier (null : rien à copier, la destination doit
        // juste disparaître). Un sidecar absent de l'archive ne doit pas survivre au remplacement
        // (un vieux -wal rejouant des pages périmées sur la base fraîchement restaurée la
        // corromprait) ; un fichier de préférences absent de l'archive, lui, reste intact.
        val replacements = mutableMapOf<File, File?>()
        for (suffix in DB_SIDECAR_SUFFIXES) {
            val destination = File(dbFile.parentFile, dbFile.name + suffix)
            val extracted = File(tempDir, dbFile.name + suffix).takeIf { it.exists() }
            if (extracted != null || destination.exists()) replacements[destination] = extracted
        }
        for (name in PREFS_FILE_NAMES) {
            val extracted = File(tempDir, name).takeIf { it.exists() } ?: continue
            replacements[File(datastoreDir, name)] = extracted
        }

        // RIC-62 : le répertoire gpx/ suit le même principe écarter-puis-remplacer, en bloc (un
        // seul rename pour tout le répertoire). Il est remplacé même quand l'archive n'a aucune
        // entrée gpx/ : la base restaurée ne référence que ses propres fichiers, une archive
        // d'avant la v8 régénérera les siens via migration7To8 à la réouverture, et des fichiers
        // de l'état écarté n'ont rien à faire sous la base d'un autre état.
        val gpxDir = LoggedTrackGpxStore.dir(context)
        val extractedGpxDir = File(tempDir, LoggedTrackGpxStore.DIR_NAME)

        // RIC-97 : même principe pour gpx-planif/ (banked_track/saved_track) : une archive d'avant
        // la v10 régénérera les siens via migration9To10 à la réouverture, même raisonnement.
        val gpxPlanifDir = PlanificationGpxStore.dir(context)
        val extractedGpxPlanifDir = File(tempDir, PlanificationGpxStore.DIR_NAME)

        // RIC-43 : et pour photos/. Remplacé en bloc lui aussi, y compris quand l'archive n'en
        // contient aucune : les fichiers de l'état courant n'ont plus rien qui les référence une
        // fois la base d'un autre état en place. Les laisser, c'était une fuite de stockage
        // définitive, avec des photos de l'utilisateur qui survivaient à un retour en arrière
        // qu'il croyait complet. Contrairement aux GPX, rien ne les régénère : une archive
        // d'avant la v15 revient donc sans photo, ce qui est exact.
        val photosDir = LoggedTrackPhotoStore.dir(context)
        val extractedPhotosDir = File(tempDir, LoggedTrackPhotoStore.DIR_NAME)

        // destination -> son original écarté (null : la destination n'existait pas avant).
        // Seules les destinations présentes dans cette map ont été mises en sûreté : c'est elle
        // (et pas `replacements`) que le rollback parcourt, pour ne jamais supprimer un original
        // encore en place si un rename a échoué au milieu de la première boucle.
        val originals = mutableMapOf<File, File?>()
        var gpxAside: File? = null
        var gpxPlanifAside: File? = null
        var photosAside: File? = null
        try {
            for (destination in replacements.keys) {
                if (destination.exists()) {
                    val aside = File(destination.path + PRE_RESTORE_SUFFIX)
                    aside.delete()
                    if (!destination.renameTo(aside)) {
                        throw IOException("Impossible d'écarter ${destination.name} avant remplacement.")
                    }
                    originals[destination] = aside
                } else {
                    originals[destination] = null
                }
            }
            if (gpxDir.exists()) {
                val aside = File(gpxDir.path + PRE_RESTORE_SUFFIX)
                aside.deleteRecursively()
                if (!gpxDir.renameTo(aside)) {
                    throw IOException("Impossible d'écarter le répertoire ${gpxDir.name} avant remplacement.")
                }
                gpxAside = aside
            }
            if (gpxPlanifDir.exists()) {
                val aside = File(gpxPlanifDir.path + PRE_RESTORE_SUFFIX)
                aside.deleteRecursively()
                if (!gpxPlanifDir.renameTo(aside)) {
                    throw IOException("Impossible d'écarter le répertoire ${gpxPlanifDir.name} avant remplacement.")
                }
                gpxPlanifAside = aside
            }
            if (photosDir.exists()) {
                val aside = File(photosDir.path + PRE_RESTORE_SUFFIX)
                aside.deleteRecursively()
                if (!photosDir.renameTo(aside)) {
                    throw IOException("Impossible d'écarter le répertoire ${photosDir.name} avant remplacement.")
                }
                photosAside = aside
            }
            for ((destination, extracted) in replacements) {
                extracted?.copyTo(destination, overwrite = true)
            }
            if (extractedGpxDir.exists()) {
                extractedGpxDir.copyRecursively(gpxDir, overwrite = true)
            }
            if (extractedGpxPlanifDir.exists()) {
                extractedGpxPlanifDir.copyRecursively(gpxPlanifDir, overwrite = true)
            }
            if (extractedPhotosDir.exists()) {
                extractedPhotosDir.copyRecursively(photosDir, overwrite = true)
            }
        } catch (e: Exception) {
            for ((destination, aside) in originals) {
                destination.delete()
                aside?.renameTo(destination)
            }
            gpxAside?.let { aside ->
                gpxDir.deleteRecursively()
                aside.renameTo(gpxDir)
            }
            gpxPlanifAside?.let { aside ->
                gpxPlanifDir.deleteRecursively()
                aside.renameTo(gpxPlanifDir)
            }
            photosAside?.let { aside ->
                photosDir.deleteRecursively()
                aside.renameTo(photosDir)
            }
            throw e
        }
        for (aside in originals.values) aside?.delete()
        gpxAside?.deleteRecursively()
        gpxPlanifAside?.deleteRecursively()
        photosAside?.deleteRecursively()
    }

    // PRAGMA integrity_check sur la base extraite, ouverte en lecture-écriture pour qu'un
    // éventuel -wal extrait à côté soit d'abord rejoué (l'archive est déjà dans un dossier
    // temporaire à nous, l'écriture y est sans conséquence). Un contenu qui n'est même pas une
    // base SQLite lève à l'ouverture : même verdict.
    private fun passesIntegrityCheck(dbFile: File): Boolean = runCatching {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            }
        }
    }.getOrDefault(false)

    /** RIC-156 : issue d'une extraction : [errorMessage] non nul si elle a échoué. */
    private data class ExtractionResult(val errorMessage: String?)

    /** Returns an error message on failure, null on success. Flattens entries by basename: the
     * db/ and prefs/ zip prefixes exist only to make a manually-opened archive self-explanatory,
     * every real filename involved is already unique on its own. Exceptions (RIC-62, RIC-97) : les
     * entrées gpx/ et gpx-planif/ gardent leur sous-répertoire, replaceWithRollback remplaçant
     * chacun de ces répertoires en bloc.
     *
     * RIC-156 : le manifeste ([MANIFEST_ENTRY_NAME]) est lu au passage, pas extrait. Il fournit le
     * dénominateur de la progression dès la première entrée, puis le compte attendu que le nombre
     * d'entrées réellement lues doit égaler. Une archive sans manifeste (antérieure à RIC-156) est
     * extraite sans compteur ni contrôle : la refuser reviendrait à priver l'utilisateur de ses
     * sauvegardes existantes pour une garantie qu'elles n'ont jamais eue. */
    private fun extractZip(
        context: Context,
        source: Uri,
        tempDir: File,
        onProgress: (done: Int, total: Int?) -> Unit,
    ): ExtractionResult {
        val input = context.contentResolver.openInputStream(source)
            ?: return ExtractionResult("Impossible de lire le fichier sélectionné.")
        var expectedFileCount: Int? = null
        var extracted = 0
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (entry.name == MANIFEST_ENTRY_NAME) {
                    expectedFileCount = readManifestFileCount(zip.readBytes())
                    onProgress(0, expectedFileCount)
                } else if (name.isNotBlank() && !entry.isDirectory) {
                    val targetDir = when {
                        entry.name.startsWith(GPX_ENTRY_PREFIX) ->
                            File(tempDir, LoggedTrackGpxStore.DIR_NAME).apply { mkdirs() }
                        entry.name.startsWith(GPX_PLANIF_ENTRY_PREFIX) ->
                            File(tempDir, PlanificationGpxStore.DIR_NAME).apply { mkdirs() }
                        entry.name.startsWith(PHOTOS_ENTRY_PREFIX) ->
                            File(tempDir, LoggedTrackPhotoStore.DIR_NAME).apply { mkdirs() }
                        else -> tempDir
                    }
                    File(targetDir, name).outputStream().use { out -> zip.copyTo(out) }
                    extracted += 1
                    onProgress(extracted, expectedFileCount)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val expected = expectedFileCount
        if (expected != null && extracted != expected) {
            return ExtractionResult(
                "Cette sauvegarde est incomplète : elle annonce $expected fichiers mais n'en contient " +
                    "que $extracted. Elle a probablement été interrompue pendant sa création. " +
                    "Restauration annulée, les données actuelles sont intactes.",
            )
        }
        return ExtractionResult(null)
    }

    /** Lecture tolérante du manifeste : tout ce qui n'est pas un compte exploitable vaut « absent ». */
    private fun readManifestFileCount(bytes: ByteArray): Int? = bytes.decodeToString()
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$MANIFEST_FILE_COUNT_KEY=") }
        ?.substringAfter('=')
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }

    private fun writeEntry(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    // SQLiteDatabase.version reads/writes PRAGMA user_version directly: the same counter Room
    // stamps the schema version into, so this needs no Room machinery of its own.
    private fun readUserVersion(dbFile: File): Int {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.version
        } finally {
            db.close()
        }
    }
}

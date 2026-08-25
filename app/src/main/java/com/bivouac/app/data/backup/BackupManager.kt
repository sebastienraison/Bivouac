package com.bivouac.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.db.PlanificationGpxStore
import com.bivouac.app.data.prefs.MAP_LAYER_DATASTORE_NAME
import com.bivouac.app.data.prefs.SETTINGS_DATASTORE_NAME
import com.bivouac.app.data.prefs.SettingsPreferences
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface RestoreResult {
    data object Success : RestoreResult
    data class VersionTooNew(val backupVersion: Int, val appVersion: Int) : RestoreResult
    data class Error(val message: String) : RestoreResult
}

/**
 * Full-database backup/restore (BIV-66) — a raw copy of bivouac.db (plus its WAL/SHM sidecars, if
 * SQLite hasn't already checkpointed them away), the DataStore preference files, the Journal's raw
 * GPX files (filesDir/gpx, RIC-62) and Planification's own GPX files (filesDir/gpx-planif, RIC-97),
 * zipped via SAF so the destination can be anywhere the system document picker reaches (Drive,
 * Nextcloud, local storage...). Deliberately not a structured export: this is a byte-for-byte
 * safety net for aggressive test sessions and real-device recette, not a portable/partial format —
 * see BIV-21 for the curated per-trace GPX export that already covers that need.
 */
object BackupManager {

    private const val DB_ENTRY_PREFIX = "db/"
    private const val PREFS_ENTRY_PREFIX = "prefs/"

    // RIC-62 : le GPX brut du Journal ne vit plus dans bivouac.db mais dans filesDir/gpx/ — sans
    // ces entrées, une sauvegarde v8 serait amputée de tout son contenu de traces.
    private const val GPX_ENTRY_PREFIX = LoggedTrackGpxStore.DIR_NAME + "/"

    // RIC-97 : même raison côté Planification (banked_track et saved_track), dans filesDir/gpx-planif/.
    private const val GPX_PLANIF_ENTRY_PREFIX = PlanificationGpxStore.DIR_NAME + "/"

    // Only ever consulted together, and always the same three suffixes — see BivouacDatabase's
    // own comment on closeAndReset() for why a clean close should normally leave -wal/-shm empty
    // or absent, but they're still backed up/restored if present, just in case.
    private val DB_SIDECAR_SUFFIXES = listOf("", "-wal", "-shm")
    private val PREFS_FILE_NAMES = listOf("$MAP_LAYER_DATASTORE_NAME.preferences_pb", "$SETTINGS_DATASTORE_NAME.preferences_pb")

    suspend fun backup(context: Context, destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Stamped into the settings file *before* it gets zipped, not after backup() returns —
            // that way the backup is self-describing: restoring it later naturally shows the date
            // it was taken as "Dernière sauvegarde", instead of whatever (or nothing) was live at
            // restore time.
            SettingsPreferences(context).setLastBackupAtMillis(System.currentTimeMillis())

            // RIC-128 : synchronized(BivouacDatabase) partage le même moniteur que getInstance()/
            // closeAndReset() (synchronized(this) dans leur companion object — Kotlin résout le
            // nom de classe nu vers l'instance du companion). Sans ce verrou, un accès DB tiers
            // pendant la boucle de copie ci-dessous rouvrait silencieusement la base via
            // getInstance() : une écriture concurrente était alors commise en base mais pouvait
            // être absente de l'archive déjà en cours de zip, sans que la sauvegarde le signale
            // comme échouée. Verrou bloquant (pas de Mutex coroutine) volontairement : ce bloc ne
            // contient aucun point de suspension réel (aucun des appels ci-dessous n'est un
            // `suspend fun`), donc pas de risque de tenir le moniteur au travers d'un changement
            // de thread — cohérent avec synchronized(this) déjà utilisé par ailleurs dans
            // BivouacDatabase, appelé depuis ces mêmes contextes suspendus sans souci.
            synchronized(BivouacDatabase) {
                // Closing Room forces a WAL checkpoint and flushes any pending writes into
                // bivouac.db itself, so the plain file copy below is consistent even without a
                // filesystem-level transaction wrapping it.
                BivouacDatabase.closeAndReset()
                try {
                    val dbFile = context.getDatabasePath(BivouacDatabase.DATABASE_NAME)
                    val datastoreDir = File(context.filesDir, "datastore")
                    val output = context.contentResolver.openOutputStream(destination)
                        ?: throw IOException("Impossible d'ouvrir la destination sélectionnée.")
                    ZipOutputStream(output).use { zip ->
                        for (suffix in DB_SIDECAR_SUFFIXES) {
                            val file = File(dbFile.parentFile, dbFile.name + suffix)
                            if (file.exists()) writeEntry(zip, DB_ENTRY_PREFIX + file.name, file)
                        }
                        for (name in PREFS_FILE_NAMES) {
                            val file = File(datastoreDir, name)
                            if (file.exists()) writeEntry(zip, PREFS_ENTRY_PREFIX + file.name, file)
                        }
                        for (file in LoggedTrackGpxStore.dir(context).listFiles().orEmpty()) {
                            if (file.isFile) writeEntry(zip, GPX_ENTRY_PREFIX + file.name, file)
                        }
                        for (file in PlanificationGpxStore.dir(context).listFiles().orEmpty()) {
                            if (file.isFile) writeEntry(zip, GPX_PLANIF_ENTRY_PREFIX + file.name, file)
                        }
                    }
                } finally {
                    // Re-primes the singleton right away rather than leaving it null until
                    // whatever screen happens to touch the DB next — a backup shouldn't leave the
                    // app in a half-initialized state if the user keeps using it right after.
                    BivouacDatabase.getInstance(context)
                }
            }
        }
    }

    suspend fun restore(context: Context, source: Uri): RestoreResult = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "restore-${System.nanoTime()}")
        try {
            tempDir.mkdirs()
            extractZip(context, source, tempDir)
                ?.let { return@withContext RestoreResult.Error(it) }

            val extractedDb = File(tempDir, BivouacDatabase.DATABASE_NAME)
            if (!extractedDb.exists()) {
                return@withContext RestoreResult.Error("Cette archive ne contient pas de base Bivouac (bivouac.db manquant).")
            }

            // RIC-95 : l'intégrité de la base extraite est vérifiée AVANT de toucher au moindre
            // fichier en place — une archive tronquée ou corrompue ne doit jamais remplacer une
            // base saine.
            if (!passesIntegrityCheck(extractedDb)) {
                return@withContext RestoreResult.Error(
                    "L'archive contient une base corrompue ou illisible — restauration annulée, les données actuelles sont intactes.",
                )
            }

            val backupVersion = readUserVersion(extractedDb)
            if (backupVersion > BivouacDatabase.SCHEMA_VERSION) {
                return@withContext RestoreResult.VersionTooNew(backupVersion, BivouacDatabase.SCHEMA_VERSION)
            }

            BivouacDatabase.closeAndReset()
            try {
                replaceWithRollback(context, tempDir)
            } finally {
                BivouacDatabase.getInstance(context)
            }
            RestoreResult.Success
        } catch (e: Exception) {
            RestoreResult.Error(e.message ?: "Échec de la restauration.")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private const val PRE_RESTORE_SUFFIX = ".pre-restore"

    /**
     * RIC-95 : le remplacement couvre plusieurs fichiers (base + sidecars + préférences), il ne
     * peut pas être atomique au sens filesystem — mais chaque fichier existant est d'abord écarté
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
        // entrée gpx/ : la base restaurée ne référence que ses propres fichiers — une archive
        // d'avant la v8 régénérera les siens via migration7To8 à la réouverture, et des fichiers
        // de l'état écarté n'ont rien à faire sous la base d'un autre état.
        val gpxDir = LoggedTrackGpxStore.dir(context)
        val extractedGpxDir = File(tempDir, LoggedTrackGpxStore.DIR_NAME)

        // RIC-97 : même principe pour gpx-planif/ (banked_track/saved_track) — une archive d'avant
        // la v10 régénérera les siens via migration9To10 à la réouverture, même raisonnement.
        val gpxPlanifDir = PlanificationGpxStore.dir(context)
        val extractedGpxPlanifDir = File(tempDir, PlanificationGpxStore.DIR_NAME)

        // destination -> son original écarté (null : la destination n'existait pas avant).
        // Seules les destinations présentes dans cette map ont été mises en sûreté — c'est elle
        // (et pas `replacements`) que le rollback parcourt, pour ne jamais supprimer un original
        // encore en place si un rename a échoué au milieu de la première boucle.
        val originals = mutableMapOf<File, File?>()
        var gpxAside: File? = null
        var gpxPlanifAside: File? = null
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
            for ((destination, extracted) in replacements) {
                extracted?.copyTo(destination, overwrite = true)
            }
            if (extractedGpxDir.exists()) {
                extractedGpxDir.copyRecursively(gpxDir, overwrite = true)
            }
            if (extractedGpxPlanifDir.exists()) {
                extractedGpxPlanifDir.copyRecursively(gpxPlanifDir, overwrite = true)
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
            throw e
        }
        for (aside in originals.values) aside?.delete()
        gpxAside?.deleteRecursively()
        gpxPlanifAside?.deleteRecursively()
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

    /** Returns an error message on failure, null on success. Flattens entries by basename — the
     * db/ and prefs/ zip prefixes exist only to make a manually-opened archive self-explanatory,
     * every real filename involved is already unique on its own. Exceptions (RIC-62, RIC-97) : les
     * entrées gpx/ et gpx-planif/ gardent leur sous-répertoire, replaceWithRollback remplaçant
     * chacun de ces répertoires en bloc. */
    private fun extractZip(context: Context, source: Uri, tempDir: File): String? {
        val input = context.contentResolver.openInputStream(source)
            ?: return "Impossible de lire le fichier sélectionné."
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (name.isNotBlank() && !entry.isDirectory) {
                    val targetDir = when {
                        entry.name.startsWith(GPX_ENTRY_PREFIX) ->
                            File(tempDir, LoggedTrackGpxStore.DIR_NAME).apply { mkdirs() }
                        entry.name.startsWith(GPX_PLANIF_ENTRY_PREFIX) ->
                            File(tempDir, PlanificationGpxStore.DIR_NAME).apply { mkdirs() }
                        else -> tempDir
                    }
                    File(targetDir, name).outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    // SQLiteDatabase.version reads/writes PRAGMA user_version directly — the same counter Room
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

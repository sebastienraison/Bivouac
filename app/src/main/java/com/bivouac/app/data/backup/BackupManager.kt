package com.bivouac.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.prefs.MAP_LAYER_DATASTORE_NAME
import com.bivouac.app.data.prefs.SETTINGS_DATASTORE_NAME
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
 * SQLite hasn't already checkpointed them away) and the DataStore preference files, zipped via SAF
 * so the destination can be anywhere the system document picker reaches (Drive, Nextcloud, local
 * storage...). Deliberately not a structured export: this is a byte-for-byte safety net for
 * aggressive test sessions and real-device recette, not a portable/partial format — see BIV-21 for
 * the curated per-trace GPX export that already covers that need.
 */
object BackupManager {

    private const val DB_ENTRY_PREFIX = "db/"
    private const val PREFS_ENTRY_PREFIX = "prefs/"

    // Only ever consulted together, and always the same three suffixes — see BivouacDatabase's
    // own comment on closeAndReset() for why a clean close should normally leave -wal/-shm empty
    // or absent, but they're still backed up/restored if present, just in case.
    private val DB_SIDECAR_SUFFIXES = listOf("", "-wal", "-shm")
    private val PREFS_FILE_NAMES = listOf("$MAP_LAYER_DATASTORE_NAME.preferences_pb", "$SETTINGS_DATASTORE_NAME.preferences_pb")

    suspend fun backup(context: Context, destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Closing Room forces a WAL checkpoint and flushes any pending writes into bivouac.db
            // itself, so the plain file copy below is consistent even without a filesystem-level
            // transaction wrapping it.
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
                }
            } finally {
                // Re-primes the singleton right away rather than leaving it null until whatever
                // screen happens to touch the DB next — a backup shouldn't leave the app in a
                // half-initialized state if the user keeps using it right after.
                BivouacDatabase.getInstance(context)
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

            val backupVersion = readUserVersion(extractedDb)
            if (backupVersion > BivouacDatabase.SCHEMA_VERSION) {
                return@withContext RestoreResult.VersionTooNew(backupVersion, BivouacDatabase.SCHEMA_VERSION)
            }

            BivouacDatabase.closeAndReset()
            try {
                val dbFile = context.getDatabasePath(BivouacDatabase.DATABASE_NAME)
                for (suffix in DB_SIDECAR_SUFFIXES) {
                    val destinationFile = File(dbFile.parentFile, dbFile.name + suffix)
                    val extracted = File(tempDir, dbFile.name + suffix)
                    // A sidecar absent from the backup must not survive from whatever database is
                    // being overwritten — an old -wal replaying stale pages on top of a freshly
                    // restored main file would silently corrupt the restore.
                    if (extracted.exists()) extracted.copyTo(destinationFile, overwrite = true) else destinationFile.delete()
                }
                val datastoreDir = File(context.filesDir, "datastore").apply { mkdirs() }
                for (name in PREFS_FILE_NAMES) {
                    val extracted = File(tempDir, name)
                    if (extracted.exists()) extracted.copyTo(File(datastoreDir, name), overwrite = true)
                }
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

    /** Returns an error message on failure, null on success. Flattens entries by basename — the
     * db/ and prefs/ zip prefixes exist only to make a manually-opened archive self-explanatory,
     * every real filename involved is already unique on its own. */
    private fun extractZip(context: Context, source: Uri, tempDir: File): String? {
        val input = context.contentResolver.openInputStream(source)
            ?: return "Impossible de lire le fichier sélectionné."
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (name.isNotBlank() && !entry.isDirectory) {
                    File(tempDir, name).outputStream().use { out -> zip.copyTo(out) }
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

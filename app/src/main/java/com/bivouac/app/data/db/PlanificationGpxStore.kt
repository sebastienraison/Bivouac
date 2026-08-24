package com.bivouac.app.data.db

import android.content.Context
import java.io.File

// RIC-97 : même principe que LoggedTrackGpxStore (RIC-62) pour le GPX de Planification — la banque
// de traces (banked_track) et le filet de session en cours (saved_track, singleton). Répertoire
// distinct de gpx/ (Journal) plutôt que partagé : les deux domaines n'ont rien à voir l'un avec
// l'autre, et BackupManager copie/restaure chaque répertoire en bloc, séparément.
object PlanificationGpxStore {

    const val DIR_NAME = "gpx-planif"

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    // Un fichier par trace banquée, nommé d'après son id stable : un save() qui réutilise le même
    // id (overwrite explicite ou rename()) retombe sur le même fichier, jamais un nouveau à côté.
    fun bankedRelativePath(id: String): String = "$DIR_NAME/banked-$id.gpx"

    // Toujours le même nom : SavedTrackEntity est un singleton (SINGLETON_ID), donc chaque save()
    // doit écraser ce fichier plutôt que d'en accumuler un par appel.
    fun savedRelativePath(): String = "$DIR_NAME/saved.gpx"

    // Chemin relatif à filesDir, jamais absolu : le préfixe absolu peut varier (profil
    // multi-utilisateur, restauration sur un autre appareil), voir LoggedTrackGpxStore.
    fun resolve(context: Context, relativePath: String): File = File(context.filesDir, relativePath)
}

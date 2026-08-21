package com.bivouac.app.data.db

import android.content.Context
import java.io.File

// RIC-62 : le GPX brut du Journal vit dans des fichiers sous filesDir/gpx/, la base ne garde
// qu'un chemin. Un contenu qui ne transite plus jamais par un Cursor Room ne peut plus, par
// construction, heurter la limite CursorWindow (~2 Mo/ligne, SQLiteBlobTooBigException) qui
// faisait planter l'ouverture des traces volumineuses.
object LoggedTrackGpxStore {

    const val DIR_NAME = "gpx"

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    // Chemin relatif à filesDir, jamais absolu : le répertoire de données absolu peut varier
    // (profil multi-utilisateur Android, restauration d'une sauvegarde sur un autre appareil),
    // le préfixe est donc résolu à chaque accès.
    fun relativePath(trackId: String, dayIndex: Int): String = "$DIR_NAME/$trackId-day$dayIndex.gpx"

    fun resolve(context: Context, relativePath: String): File = File(context.filesDir, relativePath)
}

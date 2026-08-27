package com.bivouac.app.data.db

import android.content.Context
import java.io.File
import java.util.UUID

// RIC-43 : les photos d'une sortie du Journal vivent sous filesDir/photos/, même logique que
// LoggedTrackGpxStore pour le GPX — la base ne garde qu'un chemin relatif, jamais le contenu.
object LoggedTrackPhotoStore {

    const val DIR_NAME = "photos"

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    // Nom de fichier généré (UUID), pas dérivé de l'id Room autogénéré : le fichier doit exister
    // avant l'insert (voir LoggedTrackRepository.addPhoto, même ordre "fichier d'abord, ligne
    // ensuite" que commitImport), donc avant qu'un id de ligne existe.
    fun relativePath(trackId: String, extension: String): String =
        "$DIR_NAME/$trackId-${UUID.randomUUID()}.$extension"

    fun resolve(context: Context, relativePath: String): File = File(context.filesDir, relativePath)
}

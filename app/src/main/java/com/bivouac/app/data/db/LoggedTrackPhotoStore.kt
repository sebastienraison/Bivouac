package com.bivouac.app.data.db

import android.content.Context
import java.io.File
import java.util.UUID

// RIC-43 : les photos d'une sortie du Journal vivent sous filesDir/photos/, même logique que
// LoggedTrackGpxStore pour le GPX — la base ne garde qu'un chemin relatif, jamais le contenu.
//
// RIC-149 : s'y ajoute une zone de transit sous cacheDir, où atterrissent les photos ajoutées
// pendant le mode édition tant que la sauvegarde n'a pas eu lieu. cacheDir et non filesDir : c'est
// exactement l'endroit prévu pour un état transitoire — exclu des sauvegardes du système, et
// purgeable par Android quand le stockage se remplit, ce qui est acceptable pour un ajout non
// encore validé et ne l'aurait jamais été pour une photo enregistrée.
object LoggedTrackPhotoStore {

    const val DIR_NAME = "photos"

    // Même nom de dossier que le définitif, mais sous cacheDir : deux arborescences distinctes,
    // aucune chance qu'un chemin de l'une désigne un fichier de l'autre.
    const val TRANSIT_DIR_NAME = "photos-transit"

    /**
     * RIC-149 : marqueur porté par le chemin d'une photo en transit.
     *
     * Le bandeau, la galerie, la visionneuse, les marqueurs de la carte et la bulle du curseur
     * partent tous du même `filePath` et le résolvent par [resolve]. Préfixer le chemin plutôt que
     * d'introduire un type d'affichage parallèle laisse ces cinq points de rendu strictement
     * inchangés : une photo en transit s'affiche « comme les autres » parce qu'elle est, pour eux,
     * une photo comme les autres. Le préfixe n'atteint jamais la base : les lignes ne sont écrites
     * qu'au moment du commit, à partir du chemin définitif (voir
     * LoggedTrackRepository.commitPendingPhotos).
     *
     * Deux-points comme séparateur : interdit dans les noms de fichiers générés ici (UUID +
     * extension), donc aucun chemin réel ne peut commencer par lui par accident.
     */
    const val TRANSIT_PREFIX = "transit:"

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun transitDir(context: Context): File = File(context.cacheDir, TRANSIT_DIR_NAME)

    // Nom de fichier généré (UUID), pas dérivé de l'id Room autogénéré : le fichier doit exister
    // avant l'insert (voir LoggedTrackRepository.addPhoto, même ordre "fichier d'abord, ligne
    // ensuite" que commitImport), donc avant qu'un id de ligne existe.
    fun relativePath(trackId: String, extension: String): String =
        "$DIR_NAME/$trackId-${UUID.randomUUID()}.$extension"

    fun transitPath(trackId: String, extension: String): String =
        "$TRANSIT_PREFIX$trackId-${UUID.randomUUID()}.$extension"

    fun isTransit(relativePath: String): Boolean = relativePath.startsWith(TRANSIT_PREFIX)

    fun resolve(context: Context, relativePath: String): File =
        if (isTransit(relativePath)) {
            File(transitDir(context), relativePath.removePrefix(TRANSIT_PREFIX))
        } else {
            File(context.filesDir, relativePath)
        }
}

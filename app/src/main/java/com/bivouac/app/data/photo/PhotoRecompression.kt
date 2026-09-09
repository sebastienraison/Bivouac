package com.bivouac.app.data.photo

import com.bivouac.app.data.db.LoggedTrackPhotoEntity

/**
 * RIC-140/157 : ce que la recompression du stock peut espérer reprendre, et sur quelles photos.
 *
 * Isolé d'Android (aucun accès au disque, aucun décodage) pour être exercé en JVM pure : c'est le
 * chiffre annoncé à l'utilisateur avant qu'il n'accepte une opération longue qui réécrit ses
 * fichiers. Il n'a pas le droit d'être faux dans son ordre de grandeur.
 */
object PhotoRecompression {

    /**
     * Poids supposé d'une copie réduite quand le Journal n'en contient pas encore assez pour le
     * mesurer.
     *
     * Ordre de grandeur d'un JPEG 2048 px de grand côté à qualité 85 sur une photo de paysage
     * ([PhotoStoragePolicy]), à comparer aux ~4,5 Mo d'une copie intégrale mesurés à l'origine du
     * ticket. Une constante et non une fraction du fichier d'origine : le poids d'une réduction
     * dépend de la définition CIBLE et du contenu, pas de la taille de la source, et une photo de
     * 24 Mpx comme une de 12 Mpx atterrissent au même endroit une fois ramenées à 2048 px.
     */
    const val ASSUMED_REDUCED_PHOTO_BYTES = 700_000L

    /**
     * Nombre de copies réduites déjà présentes à partir duquel leur poids moyen remplace la
     * constante ci-dessus.
     *
     * Trois et non une : la moyenne d'une seule photo, c'est cette photo, et un panorama ou une
     * capture d'écran fausserait toute l'estimation. Au-delà, la mesure sur l'appareil réel et sur
     * les photos réelles de l'utilisateur bat toujours une constante posée ici.
     */
    const val MIN_REDUCED_SAMPLES = 3

    /** RIC-140 : ce que l'écran « Espace utilisé » annonce, ou null quand il n'y a rien à annoncer. */
    data class Estimate(val photoCount: Int, val freedBytes: Long)

    /**
     * La photo est-elle du travail pour la passe de recompression ?
     *
     * Trois conditions, et la troisième est celle qui compte : il faut de quoi RETROUVER l'original
     * dans la galerie, sans quoi il n'y aura rien à réduire. « De quoi » veut dire un URI mémorisé
     * à l'import, ou au moins une métadonnée que la recherche profonde sait requêter (voir
     * [PhotoOriginalResolver.lookupsFor], qui rend une liste vide quand il n'y a rien à chercher).
     *
     * L'empreinte est exigée parce qu'elle est le seul juge de la confirmation : une ligne sans
     * empreinte ne pourrait jamais reconnaître son original, donc jamais être recompressée.
     *
     * Ce n'est qu'une PRÉ-sélection : elle ne dit pas que l'original existe encore, seulement qu'il
     * vaut la peine de le chercher. La confirmation, elle, ne se fait qu'en hachant les octets, et
     * c'est la passe elle-même qui la fait, photo par photo.
     */
    fun isRecompressible(photo: LoggedTrackPhotoEntity): Boolean {
        if (photo.storageMode != PhotoStorageMode.FULL) return false
        if (photo.contentHash.isBlank()) return false
        val hasKnownUri = !photo.lastResolvedUri.isNullOrBlank()
        return hasKnownUri || PhotoOriginalResolver.lookupsFor(photo.sourceDisplayName, photo.sourceDateTakenMillis).isNotEmpty()
    }

    /**
     * Ce que la recompression de [candidateFileBytes] libérerait, au mieux.
     *
     * Photo par photo et non sur la somme : une copie intégrale déjà plus légère que la cible ne
     * rendra rien (elle sera d'ailleurs reconnue « déjà sous la cible » par [PhotoReducer.planFor]
     * et laissée intacte), et la compter en négatif dans une somme globale masquerait le gain des
     * autres. Chaque photo apporte donc son propre gain, jamais une perte.
     *
     * @param candidateFileBytes le poids du fichier local de chaque photo pré-sélectionnée par
     *   [isRecompressible].
     * @param reducedSampleCount / [reducedSampleBytes] les copies réduites déjà présentes dans le
     *   Journal, qui servent de mesure quand il y en a assez : voir [MIN_REDUCED_SAMPLES].
     * @return null quand il n'y a rien à proposer : aucune candidate, ou aucun octet à reprendre.
     *   Un bouton qui annoncerait « libérer 0 Mo » n'a pas à exister.
     */
    fun estimate(
        candidateFileBytes: List<Long>,
        reducedSampleCount: Int = 0,
        reducedSampleBytes: Long = 0L,
    ): Estimate? {
        if (candidateFileBytes.isEmpty()) return null
        val perPhoto = if (reducedSampleCount >= MIN_REDUCED_SAMPLES && reducedSampleBytes > 0L) {
            reducedSampleBytes / reducedSampleCount
        } else {
            ASSUMED_REDUCED_PHOTO_BYTES
        }
        val freed = candidateFileBytes.sumOf { (it - perPhoto).coerceAtLeast(0L) }
        if (freed <= 0L) return null
        return Estimate(photoCount = candidateFileBytes.size, freedBytes = freed)
    }

    /**
     * RIC-157 : faut-il enchaîner, juste après la bascule vers la copie réduite, la proposition de
     * recompresser le stock déjà importé ?
     *
     * Trois conditions, toutes nécessaires :
     * - la bascule VA vers [PhotoStorageMode.REDUCED] et EN VIENT d'un autre mode : ni un simple
     *   affichage des Réglages (aucun changement), ni un choix de « Pleine résolution » n'ont à
     *   déclencher quoi que ce soit ;
     * - il existe des photos en [PhotoStorageMode.FULL] à cet instant : sans stock, la proposition
     *   n'aurait rien à annoncer ;
     * - [estimate] n'est pas nul : il vaut null quand aucune de ces photos n'est recompressible
     *   (original introuvable) ou quand la recompression ne libérerait rien (déjà sous la cible,
     *   voir [estimate]). Un bouton qui promettrait de libérer 0 Mo n'a pas à exister, ici pas plus
     *   qu'à l'écran « Espace utilisé ».
     */
    fun shouldOfferRecompressionAfterModeChange(
        previousMode: PhotoStorageMode,
        newMode: PhotoStorageMode,
        fullPhotoCount: Int,
        estimate: Estimate?,
    ): Boolean {
        if (newMode != PhotoStorageMode.REDUCED || previousMode == newMode) return false
        if (fullPhotoCount <= 0) return false
        return estimate != null
    }
}

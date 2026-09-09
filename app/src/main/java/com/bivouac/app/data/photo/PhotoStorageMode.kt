package com.bivouac.app.data.photo

/**
 * RIC-157 : comment une photo du Journal est stockée localement.
 *
 * Mesure d'origine : les copies intégrales pèsent ~4,5 Mo pièce, 855 Mo sur un usage réel. D'où le
 * choix laissé à l'utilisateur (voir Réglages) entre une copie réduite, largement suffisante pour
 * revoir ses photos dans le Journal, et une copie intégrale pour qui veut que le Journal soit
 * l'archive elle-même.
 *
 * Persisté sur chaque ligne ([com.bivouac.app.data.db.LoggedTrackPhotoEntity.storageMode]) et pas
 * seulement dans les préférences : le réglage peut changer à tout moment, alors que ce qu'une photo
 * DÉJÀ importée a subi, lui, ne change plus. C'est la colonne, pas la préférence, qui dira au lot B
 * (recompression du stock, montée en qualité dans la visionneuse) sur quoi il reste du travail.
 */
enum class PhotoStorageMode {
    /** Copie intégrale des octets d'origine : le comportement historique, et « Qualité d'archive ». */
    FULL,

    /**
     * Copie redimensionnée et réencodée : voir [PhotoStoragePolicy].
     *
     * Vaut aussi pour une photo déjà plus petite que la cible, recopiée telle quelle faute d'avoir
     * quoi que ce soit à gagner : voir [PhotoStoragePolicy] pour ce que cette convention implique.
     */
    REDUCED,
}

/**
 * RIC-157 : les paramètres de la copie réduite, et la règle de défaut du réglage.
 *
 * Isolés d'Android (aucun import du framework) pour être exercés en JVM pure : c'est la logique,
 * pas le décodage d'image, qui décide de ce qui arrive aux photos de l'utilisateur.
 */
object PhotoStoragePolicy {

    /**
     * Le grand côté visé par la copie réduite.
     *
     * 2048 px couvre largement l'usage réel des photos du Journal (vignette, bandeau, visionneuse
     * plein écran sur un téléphone dont l'écran fait au mieux ~1200 px de large) et divise le poids
     * par un ordre de grandeur sur une photo de 12 Mpx. Le grand côté et non une surface : c'est ce
     * qui garde le même plafond de définition à un portrait et à un paysage.
     */
    const val REDUCED_LONG_SIDE_PX = 2048

    /**
     * Qualité JPEG du réencodage.
     *
     * 85 est le palier au-delà duquel le gain de poids devient marginal et en deçà duquel les
     * artefacts deviennent visibles sur les aplats (ciel, neige), c'est-à-dire exactement ce que
     * photographie une rando.
     */
    const val REDUCED_JPEG_QUALITY = 85

    /**
     * RIC-157 : le mode qui s'applique quand l'utilisateur n'a rien tranché.
     *
     * [decision] est ce qui est persisté dans les Réglages : `null` veut dire « jamais choisi »,
     * et c'est un état durable, pas un état transitoire de démarrage.
     *
     * Deux défauts et non un seul, parce que les deux situations n'ont rien à voir :
     * - **Journal sans aucune photo** : personne ne peut être surpris par un changement qui ne
     *   s'appliquera qu'à des photos pas encore importées. [PhotoStorageMode.REDUCED], le bon
     *   défaut, celui qui évite les 855 Mo.
     * - **Journal qui a déjà des photos** : l'utilisateur a un stock constitué sous le régime de la
     *   copie intégrale, et une mise à jour de l'app n'a pas à changer silencieusement le régime de
     *   ce qu'il ajoutera demain à côté. [PhotoStorageMode.FULL] jusqu'à ce qu'il tranche
     *   lui-même : c'est précisément ce que la proposition post-mise à jour va lui demander (voir
     *   PhotoStorageChoicePrompt).
     */
    fun resolve(decision: PhotoStorageMode?, hasExistingPhotos: Boolean): PhotoStorageMode =
        decision ?: if (hasExistingPhotos) PhotoStorageMode.FULL else PhotoStorageMode.REDUCED
}

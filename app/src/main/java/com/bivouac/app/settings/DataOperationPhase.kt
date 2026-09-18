package com.bivouac.app.settings

/**
 * RIC-156 : les trois temps que le dialogue bloquant des Réglages sait annoncer.
 *
 * La restauration en a deux, et non un seul : l'extraction est la phase longue et dénombrable, le
 * remplacement est court et ne l'est pas : les fondre donnerait un compteur qui se fige à la fin
 * sans que rien n'explique pourquoi.
 *
 * RIC-191 (lot 4 i18n) : SORTI de SettingsViewModel.kt sans autre changement, même package donc
 * aucun import à reprendre ailleurs. Les six titres restent écrits en français en dur, seul endroit
 * du lot 4 dans ce cas, et pour une raison précise : ils ne sont LUS que par
 * ui/settings/SettingsScreen.kt et ui/startup/PhotoStorageChoicePrompt.kt, via `it.phase.title`.
 * Les passer en @StringRes oblige à résoudre la ressource dans ces deux écrans, qui appartiennent
 * au lot 3 (RIC-190), en cours en parallèle. Les isoler ici permet au garde-fou
 * tools/i18n/check_hardcoded.py de n'exclure que ce fichier au lieu de tout SettingsViewModel.kt.
 * Les clés existent déjà dans l'inventaire (backup_progress_title, restore_extraction_progress_
 * title, restore_replacement_progress_title, photo_purge_progress_title,
 * photo_recovery_progress_title, storage_usage_recompression_progress_title) : le lot 3 n'a qu'à
 * les brancher, et à retirer ce fichier de la liste d'exclusion du garde-fou.
 */
enum class DataOperationPhase(val title: String) {
    BACKUP("Sauvegarde en cours"),
    RESTORE_EXTRACTION("Lecture de la sauvegarde"),
    RESTORE_REPLACEMENT("Restauration en cours"),

    // RIC-158 : la purge des photos peut porter sur des centaines de Mo, assez long pour mériter
    // le même dialogue bloquant que la sauvegarde et la restauration, cohérence oblige.
    PHOTO_PURGE("Purge des photos en cours"),

    // RIC-151 : une recherche dans la galerie et un réencodage par photo manquante : c'est
    // l'opération la plus lente des quatre, et de loin celle qui a le plus besoin d'un compteur.
    PHOTO_RECOVERY("Recherche des photos manquantes"),

    // RIC-157 : même titre que le bouton dédié de l'écran « Espace utilisé » (StorageUsageScreen) :
    // c'est la même opération, seulement déclenchée d'un second endroit (la proposition posée à la
    // bascule vers la copie réduite), et son dialogue bloquant ne doit pas se distinguer de l'autre.
    PHOTO_RECOMPRESS("Recompression en cours"),
}

/** RIC-156 : où en est la sauvegarde ou la restauration. [total] est null quand le travail n'est pas dénombrable. */
data class DataOperationProgress(val phase: DataOperationPhase, val done: Int, val total: Int?)

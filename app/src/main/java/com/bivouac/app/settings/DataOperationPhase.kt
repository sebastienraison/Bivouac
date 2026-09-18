package com.bivouac.app.settings

import androidx.annotation.StringRes
import com.bivouac.app.R

/**
 * RIC-156 : les trois temps que le dialogue bloquant des Réglages sait annoncer.
 *
 * La restauration en a deux, et non un seul : l'extraction est la phase longue et dénombrable, le
 * remplacement est court et ne l'est pas : les fondre donnerait un compteur qui se fige à la fin
 * sans que rien n'explique pourquoi.
 *
 * RIC-191 (lot 4 i18n) : sorti de SettingsViewModel.kt vers son propre fichier, même package donc
 * aucun import à reprendre ailleurs, et le titre devient un identifiant de ressource, résolu par
 * les deux écrans qui l'affichent. Même raison que pour ExclusiveOperation : une valeur d'enum vit
 * tout le process, elle ne peut pas porter une chaîne figée dans la langue qui avait cours à son
 * chargement.
 */
enum class DataOperationPhase(@StringRes val titleRes: Int) {
    BACKUP(R.string.backup_progress_title),
    RESTORE_EXTRACTION(R.string.restore_extraction_progress_title),
    RESTORE_REPLACEMENT(R.string.restore_replacement_progress_title),

    // RIC-158 : la purge des photos peut porter sur des centaines de Mo, assez long pour mériter
    // le même dialogue bloquant que la sauvegarde et la restauration, cohérence oblige.
    PHOTO_PURGE(R.string.photo_purge_progress_title),

    // RIC-151 : une recherche dans la galerie et un réencodage par photo manquante : c'est
    // l'opération la plus lente des quatre, et de loin celle qui a le plus besoin d'un compteur.
    PHOTO_RECOVERY(R.string.photo_recovery_progress_title),

    // RIC-157 : même titre que le bouton dédié de l'écran « Espace utilisé » (StorageUsageScreen) :
    // c'est la même opération, seulement déclenchée d'un second endroit (la proposition posée à la
    // bascule vers la copie réduite), et son dialogue bloquant ne doit pas se distinguer de l'autre.
    PHOTO_RECOMPRESS(R.string.storage_usage_recompression_progress_title),
}

/** RIC-156 : où en est la sauvegarde ou la restauration. [total] est null quand le travail n'est pas dénombrable. */
data class DataOperationProgress(val phase: DataOperationPhase, val done: Int, val total: Int?)

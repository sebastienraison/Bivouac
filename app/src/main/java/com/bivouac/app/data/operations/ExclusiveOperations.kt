package com.bivouac.app.data.operations

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RIC-156 : les opérations longues qui touchent au stockage de l'app et ne peuvent jamais se
 * chevaucher.
 *
 * Le libellé est celui affiché par l'écran qui refuse un geste pendant qu'une autre opération
 * tourne ; il est porté ici et non côté écran parce que le refus vient forcément d'un écran
 * différent de celui qui a lancé l'opération (Réglages refuse à cause du Journal, et
 * réciproquement).
 *
 * RIC-158 étend le registre à la purge des photos et à tout ce qui écrit dans gpx/ ou
 * gpx-planif/ à l'occasion d'un import : mêmes fichiers, même risque de chevauchement avec une
 * sauvegarde ou une restauration en cours.
 */
enum class ExclusiveOperation(val label: String) {
    BACKUP("une sauvegarde"),
    RESTORE("une restauration"),
    PHOTO_IMPORT("un import de photos"),
    PHOTO_COMMIT("un enregistrement de photos"),

    // RIC-158 : supprime en masse les fichiers de photos/ : aussi bien touché par une sauvegarde
    // (qui les zippe) qu'une restauration (qui remplace le répertoire en bloc).
    PHOTO_PURGE("une purge de photos"),

    // RIC-158 : tout import du Journal qui écrit dans gpx/ : un seul fichier, un trek multi-jours
    // (plusieurs fichiers pour une seule sortie) ou un lot de sorties séparées.
    JOURNAL_IMPORT("un import du Journal"),

    // RIC-158 : tout ce qui fait atterrir du contenu nouveau dans gpx-planif/ en dehors d'une
    // édition ordinaire : l'import d'un fichier GPX externe et la duplication d'une sortie du
    // Journal vers la Planification (RIC-40), qui écrit elle aussi dans ce répertoire.
    PLANIFICATION_IMPORT("un import en Planification"),
}

/**
 * RIC-156 : registre de l'opération longue en vol, unique pour tout le process.
 *
 * Motivé par un incident réel : depuis les Réglages, une restauration d'une vieille archive a pu
 * être lancée pendant qu'une sauvegarde était en cours d'écriture. Les deux manipulent les mêmes
 * fichiers (base, préférences, gpx/, photos/) et la restauration ferme puis remplace la base sous
 * la boucle de zip de la sauvegarde. Rendre les deux bloquantes à l'écran ferme le chemin courant,
 * mais pas la garantie : les points d'entrée sont sur deux écrans différents (Réglages, Journal) et
 * chacun ne connaît que son propre état.
 *
 * Volontairement un `object` et non une dépendance injectée : ce verrou porte sur des fichiers du
 * process, pas sur une instance de quoi que ce soit : deux registres coexistant ne protégeraient
 * plus rien. Les ViewModels étant recréés à chaque rotation, un état porté par l'un d'eux ne
 * survivrait de toute façon pas à l'opération qu'il est censé garder.
 *
 * `synchronized` et non un Mutex de coroutine : [tryStart] doit pouvoir être appelé depuis un
 * gestionnaire de clic, donc hors contexte suspendu : c'est même essentiel, le verrou doit être
 * posé par le geste lui-même et non au moment où la coroutine sera ordonnancée (même raisonnement
 * que RIC-149 pour le dialogue bloquant des photos). La section critique ne contient que deux
 * lectures/écritures de champ.
 */
object ExclusiveOperations {

    private val _current = MutableStateFlow<ExclusiveOperation?>(null)

    /** L'opération en vol, ou null. Les écrans s'en servent pour désactiver leurs points d'entrée. */
    val current: StateFlow<ExclusiveOperation?> = _current.asStateFlow()

    /**
     * Pose [operation] si et seulement si aucune autre n'est en vol.
     *
     * @return false si une opération tourne déjà : l'appelant doit alors renoncer proprement, sans
     *   rien entamer. Une même opération demandée deux fois est refusée elle aussi : deux imports
     *   de photos simultanés se marcheraient dessus autant que deux opérations différentes.
     */
    fun tryStart(operation: ExclusiveOperation): Boolean = synchronized(this) {
        if (_current.value != null) return false
        _current.value = operation
        true
    }

    /**
     * Lève le verrou. Sans effet si l'opération en vol n'est pas [operation] : une fin tardive
     * (coroutine annulée dont le `finally` s'exécute après qu'une autre opération a démarré) ne
     * doit jamais libérer le verrou de quelqu'un d'autre.
     */
    fun finish(operation: ExclusiveOperation) = synchronized(this) {
        if (_current.value == operation) _current.value = null
    }

    /** Réservé aux tests : remet le registre à zéro entre deux cas. */
    fun resetForTests() = synchronized(this) {
        _current.value = null
    }
}

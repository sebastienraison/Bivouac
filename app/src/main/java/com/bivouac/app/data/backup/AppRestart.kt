package com.bivouac.app.data.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity

/**
 * RIC-204 : après une restauration (BIV-66), l'app ne doit plus fermer le processus.
 *
 * L'ancienne implémentation relançait l'activité (LAUNCH_SINGLE_TASK + CLEAR_TOP) puis appelait
 * `Runtime.getRuntime().exit(0)`. Sur MainActivity en singleTask, l'intent de relance était livré
 * à l'instance déjà en place (`onNewIntent`, result code=3, aucune nouvelle Activity créée) : le
 * `exit(0)` qui suivait de quelques millisecondes tuait donc cette même instance avant qu'elle ait
 * pu traiter quoi que ce soit, et jetait sa tâche avec elle. Rien n'échouait côté Android (aucun
 * refus de lancement d'activité en arrière-plan) : c'est le process qui coupait sa propre relance
 * sous ses pieds. Résultat pour l'utilisateur : l'app se ferme et ne se rouvre jamais seule.
 *
 * [refresh] répare ça en ne tuant plus rien : elle vide le ViewModelStore de l'Activity hôte puis
 * la recrée. `Activity.recreate()` seul ne suffirait pas : son ViewModelStore SURVIT à la
 * recréation par conception (c'est tout le principe de ViewModel, voir sa propre doc), donc les
 * ViewModel déjà vivants (Planification, Journal, Réglages) referaient surface identiques,
 * chacun accroché à ses repositories, flux et DAO construits sur l'ancienne base. Vider le store
 * AVANT de recréer force chaque écran à reconstruire un ViewModel neuf, qui résout une base et des
 * DataStore frais :
 *  - BivouacDatabase : chaque repository résout `BivouacDatabase.getInstance()` à chaque accès et
 *    ne garde jamais son DAO (RIC-103) ; un ViewModel neuf lit donc naturellement la base
 *    fraîchement restaurée sans rien de plus à faire ici.
 *  - SettingsPreferences / MapLayerPreferences (RIC-204) : leur DataStore, lui, est un singleton
 *    de PROCESS qui ne se relit jamais seul depuis le disque une fois ouvert. Un ViewModel neuf
 *    n'y change donc rien à lui seul : c'est BackupManager.restore() qui vide ce cache-là,
 *    au moment même où il remplace les fichiers .preferences_pb (voir ResettableDataStoreHolder).
 *    Les deux caches sont donc traités, mais à deux endroits différents, chacun au bon moment.
 *
 * Ce qui n'a PAS besoin d'être traité ici (vérifié, pas supposé) : les fichiers de photos, dont le
 * nom est un UUID généré à l'ajout (jamais dérivé du contenu ni réutilisé d'une sauvegarde à
 * l'autre), donc jamais en collision avec ce qu'un cache d'images en mémoire pourrait déjà tenir ;
 * et le cache de tuiles osmdroid, qui ne porte que des tuiles de carte réseau, sans rapport avec
 * bivouac.db, les GPX, les photos ou les préférences que restaure BackupManager.
 */
object AppRestart {
    fun refresh(context: Context) {
        val activity = context.findComponentActivity()
            ?: error("AppRestart.refresh() appelé hors d'un ComponentActivity : $context")
        activity.viewModelStore.clear()
        activity.recreate()
    }

    private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }
}

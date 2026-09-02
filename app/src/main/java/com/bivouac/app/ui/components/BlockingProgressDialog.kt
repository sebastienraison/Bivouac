package com.bivouac.app.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * RIC-156 : ce que le dialogue bloquant affiche d'une opération en cours.
 *
 * [done] et [total] sont nullables ensemble : tous les flux ne savent pas dénombrer leur travail à
 * l'avance (voir la restauration d'une archive sans manifeste, BackupManager). Dans ce cas le
 * dialogue montre le tourniquet seul, sans compteur — assumé, plutôt qu'un faux dénominateur.
 */
data class BlockingProgress(
    val title: String,
    val done: Int? = null,
    val total: Int? = null,
)

/**
 * RIC-156 : seuil d'apparition. En dessous, l'opération se termine sans que rien ne soit affiché.
 *
 * Mesuré en recette : l'enregistrement des détails d'une trace sans photo prend ~43 ms, et le
 * dialogue y apparaissait puis disparaissait dans la même respiration — perçu comme un défaut
 * d'affichage, pas comme une information. 200 ms est le seuil courant en deçà duquel une attente
 * est ressentie comme instantanée : rien à signaler à l'utilisateur.
 */
const val BLOCKING_DIALOG_APPEARANCE_DELAY_MILLIS = 200L

/**
 * RIC-156 : durée minimale d'affichage, une fois le dialogue apparu.
 *
 * Le seuil d'apparition ne suffit pas : une opération qui dure 210 ms franchirait le seuil puis
 * disparaîtrait 10 ms plus tard, soit exactement le clignotement qu'on cherche à supprimer. Une
 * fois visible, le dialogue reste donc au moins ce temps-là, même si l'opération est déjà finie.
 */
const val BLOCKING_DIALOG_MINIMUM_VISIBLE_MILLIS = 500L

/**
 * RIC-156 : verdict d'affichage rendu par [antiFlashDecision].
 *
 * @param visible le dialogue doit-il être à l'écran maintenant.
 * @param shownAtMillis l'instant d'apparition à mémoriser (null : le dialogue n'est pas apparu, ou
 *   son maintien minimal est écoulé). L'appelant reporte cette valeur dans son état.
 * @param recheckInMillis délai après lequel il faut redemander un verdict, null si l'état est
 *   stable jusqu'au prochain changement d'opération.
 */
data class AntiFlashDecision(
    val visible: Boolean,
    val shownAtMillis: Long?,
    val recheckInMillis: Long?,
)

/**
 * RIC-156 : toute la logique anti-flash, sans horloge ni Compose — le temps est fourni par
 * l'appelant, ce qui la rend testable en unitaire pur (voir AntiFlashDecisionTest).
 *
 * CAPITAL : cette fonction ne décide que du VISUEL. L'état « opération en vol » qui alimente les
 * gardes (sortie de l'écran des photos, exclusion mutuelle via ExclusiveOperations) est posé au
 * geste et levé à la fin réelle, sans le moindre différé — sinon le seuil d'apparition ouvrirait
 * une fenêtre de 200 ms pendant laquelle rien ne protège quoi que ce soit.
 *
 * @param operationStartedAtMillis instant où l'opération courante est entrée en vol, null si
 *   aucune ne tourne.
 * @param shownAtMillis instant d'apparition du dialogue, null s'il n'est pas affiché.
 */
fun antiFlashDecision(
    operationStartedAtMillis: Long?,
    shownAtMillis: Long?,
    nowMillis: Long,
    appearanceDelayMillis: Long = BLOCKING_DIALOG_APPEARANCE_DELAY_MILLIS,
    minimumVisibleMillis: Long = BLOCKING_DIALOG_MINIMUM_VISIBLE_MILLIS,
): AntiFlashDecision {
    if (operationStartedAtMillis != null) {
        // Déjà visible : on y reste tant que l'opération dure. Une opération qui démarre pendant
        // le maintien minimal d'une précédente passe aussi par ici — le dialogue enchaîne sans se
        // fermer entre les deux, ce qui est exactement le comportement voulu.
        if (shownAtMillis != null) return AntiFlashDecision(true, shownAtMillis, null)
        val remaining = appearanceDelayMillis - (nowMillis - operationStartedAtMillis)
        return if (remaining > 0) {
            AntiFlashDecision(visible = false, shownAtMillis = null, recheckInMillis = remaining)
        } else {
            AntiFlashDecision(visible = true, shownAtMillis = nowMillis, recheckInMillis = null)
        }
    }
    // Plus rien en vol, et rien n'a jamais été affiché : l'opération a été plus rapide que le
    // seuil d'apparition, elle ne laisse aucune trace à l'écran.
    if (shownAtMillis == null) return AntiFlashDecision(false, null, null)
    val remaining = minimumVisibleMillis - (nowMillis - shownAtMillis)
    return if (remaining > 0) {
        AntiFlashDecision(visible = true, shownAtMillis = shownAtMillis, recheckInMillis = remaining)
    } else {
        AntiFlashDecision(visible = false, shownAtMillis = null, recheckInMillis = null)
    }
}

/**
 * RIC-156 : le dialogue bloquant partagé — tourniquet, compteur, aucune porte de sortie.
 *
 * Extrait de JournalScreen (RIC-149), où il ne servait que les opérations photo, pour que la
 * sauvegarde et la restauration s'appuient dessus au lieu d'en recopier une variante. Il n'ouvre
 * volontairement aucun bouton et refuse le retour arrière comme le clic à côté : ce qu'il couvre
 * n'est pas interruptible sans risque de perte de données.
 *
 * L'anti-flash est intégré ici et non chez les appelants : c'est le seul endroit qui connaisse
 * l'affichage, et le seul où l'oubli serait impossible.
 *
 * @param progress non nul tant que l'opération est en vol. Le contenu affiché est figé sur la
 *   dernière valeur non nulle reçue, pour que le maintien minimal après la fin réelle ait toujours
 *   quelque chose à montrer plutôt qu'un dialogue vide.
 */
@Composable
fun BlockingProgressDialog(
    progress: BlockingProgress?,
    appearanceDelayMillis: Long = BLOCKING_DIALOG_APPEARANCE_DELAY_MILLIS,
    minimumVisibleMillis: Long = BLOCKING_DIALOG_MINIMUM_VISIBLE_MILLIS,
) {
    var operationStartedAtMillis by remember { mutableStateOf<Long?>(null) }
    var shownAtMillis by remember { mutableStateOf<Long?>(null) }
    var visible by remember { mutableStateOf(false) }
    var displayed by remember { mutableStateOf<BlockingProgress?>(null) }

    val inFlight = progress != null
    // Clé sur le booléen et non sur `progress` : l'instant d'entrée en vol ne doit pas être remis à
    // zéro à chaque avancée du compteur, sinon le seuil d'apparition ne serait jamais atteint sur
    // une opération qui progresse vite.
    LaunchedEffect(inFlight) {
        operationStartedAtMillis = if (inFlight) SystemClock.elapsedRealtime() else null
    }
    LaunchedEffect(progress) {
        if (progress != null) displayed = progress
    }
    LaunchedEffect(operationStartedAtMillis, shownAtMillis) {
        while (true) {
            val decision = antiFlashDecision(
                operationStartedAtMillis = operationStartedAtMillis,
                shownAtMillis = shownAtMillis,
                nowMillis = SystemClock.elapsedRealtime(),
                appearanceDelayMillis = appearanceDelayMillis,
                minimumVisibleMillis = minimumVisibleMillis,
            )
            visible = decision.visible
            // Le verdict change l'instant d'apparition mémorisé : on le reporte et on sort, la
            // boucle repart d'elle-même puisque cet état est une clé de l'effet.
            if (decision.shownAtMillis != shownAtMillis) {
                shownAtMillis = decision.shownAtMillis
                return@LaunchedEffect
            }
            delay(decision.recheckInMillis ?: return@LaunchedEffect)
        }
    }

    val shown = displayed
    if (!visible || shown == null) return
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(shown.title) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(blockingProgressLabel(shown.done, shown.total))
            }
        },
        confirmButton = {},
    )
}

/**
 * RIC-156 : « 3 sur 12… » quand le travail est dénombrable, une phrase d'attente sinon. Interne
 * plutôt que privée pour rester vérifiable en test.
 */
internal fun blockingProgressLabel(done: Int?, total: Int?): String =
    if (done != null && total != null && total > 0) "$done sur $total…" else "Patiente un instant…"

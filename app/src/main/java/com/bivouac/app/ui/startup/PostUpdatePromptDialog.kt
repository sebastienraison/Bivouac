package com.bivouac.app.ui.startup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * RIC-157 : le dialogue d'une proposition posée après une mise à jour, générique.
 *
 * L'app avait déjà besoin de ce geste deux fois (le rattrapage d'altitude de RIC-19, cette
 * proposition-ci), et en aura besoin encore : le mécanisme est donc écrit une fois, avec sa
 * persistance (UpdatePromptPreferences) et sa décision pure (shouldShowUpdatePrompt). Ici, il ne
 * reste que la forme.
 *
 * **Deux temps, et non un.** Le premier explique l'enjeu, le second porte le choix, fourni par
 * l'appelant ([action]). Poser le choix d'emblée obligerait à trancher avant d'avoir lu pourquoi ;
 * ne poser que l'explication et renvoyer ailleurs perdrait la moitié des gens en route.
 *
 * **Choix INLINE et non navigation vers les Réglages** : à ce moment-là de la vie du process, la
 * navigation n'est pas encore stabilisée (MainActivity résout sa destination de départ depuis une
 * préférence, et Réglages n'est même pas un univers d'accueil légitime, voir
 * AppSectionPreferences) : y pousser une destination au lancement se battrait avec cette
 * résolution. Le choix inline reste par ailleurs strictement le même composant que celui des
 * Réglages (PhotoStorageModeChoice pour le premier usage), donc aucune divergence de formulation
 * possible entre les deux endroits.
 *
 * **Trois issues, trois poids visuels différents.** « Agir » est le bouton de confirmation,
 * « Peut-être plus tard » celui d'annulation, et « Ne plus me le proposer » un lien discret sous le
 * texte : c'est l'issue définitive, elle ne doit pas être celle qu'on touche en visant à côté.
 *
 * Le dialogue ne se ferme jamais tout seul (ni retour arrière ni appui à côté) : les trois issues
 * n'ont pas la même conséquence, laisser un geste ambigu en choisir une serait pire que d'obliger
 * à répondre. La proposition est de toute façon enregistrée comme posée dès son affichage, donc
 * personne n'est enfermé : tuer l'app la reporte à la version suivante.
 */
@Composable
fun PostUpdatePromptDialog(
    title: String,
    message: String,
    actionLabel: String,
    onLater: () -> Unit,
    onNever: () -> Unit,
    action: @Composable (onDone: () -> Unit) -> Unit,
) {
    // rememberSaveable : une rotation ou un passage clair/sombre recrée l'Activity, et faire
    // retomber quelqu'un sur l'explication alors qu'il était en train de choisir serait un
    // aller-retour gratuit.
    var choosing by rememberSaveable { mutableStateOf(false) }

    if (choosing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(title) },
            text = { action { choosing = false } },
            confirmButton = {},
        )
        return
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(12.dp))
                TextButton(onClick = onNever) {
                    Text(
                        "Ne plus me le proposer",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = true }) { Text(actionLabel) } },
        dismissButton = { TextButton(onClick = onLater) { Text("Peut-être plus tard") } },
    )
}

package com.bivouac.app.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.bivouac.app.ui.components.ChoiceOptionCard

/**
 * RIC-104 : un fichier reçu de l'extérieur (SEND/VIEW) ne dit pas de lui-même s'il s'agit d'une
 * rando déjà faite (Journal) ou d'une trace à préparer (Planification) : ce que les deux FAB
 * internes savent par construction (voir RIC-65 et le bouton de Planification), pas ce chemin.
 * Composant partagé plutôt que dupliqué : les deux univers y renvoient le même choix.
 *
 * Aucune des deux branches n'est recommandée : contrairement au lot de fichiers de RIC-41, rien ici
 * ne rend l'une plus probable que l'autre, et en désigner une reviendrait à deviner.
 */
@Composable
fun UniverseChoiceDialog(
    onJournalChosen: () -> Unit,
    onPlanificationChosen: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Cette trace, c'est pour le Journal ou pour Planification ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceOptionCard(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = "Journal",
                    subtitle = "Une rando déjà faite, à archiver dans ton carnet",
                    onClick = onJournalChosen,
                )
                ChoiceOptionCard(
                    icon = Icons.Default.Route,
                    title = "Planification",
                    subtitle = "Une trace à préparer, avec ses points de bivouac",
                    onClick = onPlanificationChosen,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("Annuler") }
        },
    )
}

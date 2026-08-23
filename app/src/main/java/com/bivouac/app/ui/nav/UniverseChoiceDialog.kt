package com.bivouac.app.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * RIC-104 : un fichier reçu de l'extérieur (SEND/VIEW) ne dit pas de lui-même s'il s'agit d'une
 * rando déjà faite (Journal) ou d'une trace à préparer (Planification) — ce que les deux FAB
 * internes savent par construction (voir RIC-65 et le bouton de Planification), pas ce chemin.
 * Composant partagé plutôt que dupliqué : les deux univers y renvoient le même choix.
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UniverseChoiceOption(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = "Journal",
                    subtitle = "Une rando déjà faite, à archiver dans ton carnet",
                    onClick = onJournalChosen,
                )
                UniverseChoiceOption(
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

@Composable
private fun UniverseChoiceOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

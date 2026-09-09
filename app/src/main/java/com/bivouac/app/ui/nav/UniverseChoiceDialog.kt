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
 *
 * RIC-108 : au-delà d'un fichier reçu, la branche Planification est grisée. Elle n'a jamais su
 * ouvrir qu'un fichier à la fois (son propre sélecteur est un OpenDocument, pas un
 * OpenMultipleDocuments) : la choisir pour un lot en perdait silencieusement tout le reste. Grisée
 * plutôt que retirée : une option absente ne dit pas pourquoi elle l'est, et le seul chemin qui
 * marche pour un lot (le Journal) est juste à côté.
 */
@Composable
fun UniverseChoiceDialog(
    // Le nombre de fichiers reçus, et non la liste : ce dialogue ne fait rien de leur contenu.
    fileCount: Int,
    onJournalChosen: () -> Unit,
    onPlanificationChosen: () -> Unit,
    onCancel: () -> Unit,
) {
    val planificationAvailable = UniverseChoice.planificationAccepts(fileCount)
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
                    subtitle = if (planificationAvailable) {
                        "Une trace à préparer, avec ses points de bivouac"
                    } else {
                        UniverseChoice.PLANIFICATION_MULTI_FILE_SUBTITLE
                    },
                    onClick = onPlanificationChosen,
                    enabled = planificationAvailable,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("Annuler") }
        },
    )
}

/**
 * RIC-108 : la règle que le dialogue applique, isolée du composable pour être testable seule et
 * pour n'exister qu'à un endroit.
 *
 * Le constat qui la motive vient du test communautaire F-Droid : partager trois fichiers vers
 * Bivouac puis choisir « Planification » ouvrait le premier et jetait les deux autres sans un mot.
 * Option la plus sobre retenue par le pilotage : refuser le choix, plutôt qu'agrandir la
 * Planification au multi-fichiers (ce qu'elle ne sait pas faire) ou avertir après coup.
 */
object UniverseChoice {

    /** Ce que la Planification sait ouvrir d'un seul geste : un fichier, pas un lot. */
    const val MAX_PLANIFICATION_FILES = 1

    const val PLANIFICATION_MULTI_FILE_SUBTITLE =
        "N'ouvre qu'un fichier à la fois : passe par le Journal pour un lot"

    fun planificationAccepts(fileCount: Int): Boolean = fileCount <= MAX_PLANIFICATION_FILES
}

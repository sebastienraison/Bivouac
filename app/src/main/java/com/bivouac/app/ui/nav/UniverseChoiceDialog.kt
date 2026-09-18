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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bivouac.app.R
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
        title = { Text(stringResource(R.string.nav_universe_choice_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceOptionCard(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = stringResource(R.string.journal_list_screen_title),
                    subtitle = stringResource(R.string.nav_universe_choice_option_journal_subtitle),
                    onClick = onJournalChosen,
                )
                ChoiceOptionCard(
                    icon = Icons.Default.Route,
                    title = stringResource(R.string.nav_section_planification),
                    subtitle = stringResource(
                        if (planificationAvailable) {
                            R.string.nav_universe_choice_option_planification_subtitle
                        } else {
                            // RIC-190 (lot 3 i18n) : la constante UniverseChoice.PLANIFICATION_
                            // MULTI_FILE_SUBTITLE a été remplacée par cette ressource. Le when
                            // choisit un id, le stringResource reste unique.
                            R.string.nav_universe_choice_planification_disabled_subtitle
                        },
                    ),
                    onClick = onPlanificationChosen,
                    enabled = planificationAvailable,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel_button)) }
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

    fun planificationAccepts(fileCount: Int): Boolean = fileCount <= MAX_PLANIFICATION_FILES
}

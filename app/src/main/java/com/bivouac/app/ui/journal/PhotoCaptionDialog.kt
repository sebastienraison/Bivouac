package com.bivouac.app.ui.journal

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import com.bivouac.app.data.db.LoggedTrackPhotoEntity

/**
 * RIC-170 : la saisie de légende, ouverte par un tap sur la zone légende de la visionneuse (écran 2
 * de la maquette validée le 2026-09-16), en mode édition seulement.
 *
 * Un petit dialogue plutôt qu'un champ inline sur la photo elle-même : c'est ce que la spec appelle
 * « le plus propre en Compose », et c'est aussi le patron déjà en place pour renommer une trace
 * (voir le dialogue de renommage de JournalScreen) : pas de mise en forme, un champ qui grandit avec
 * le texte (comportement par défaut d'OutlinedTextField non contraint à `singleLine`).
 *
 * Rien n'est écrit en base ici : OK rend la légende à l'appelant, qui la pose dans le brouillon
 * d'édition en attente de la disquette (RIC-149), exactement comme un ajustement de recadrage.
 */
@Composable
internal fun PhotoCaptionDialog(
    photo: LoggedTrackPhotoEntity,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Curseur en fin de texte, comme la reprise d'une note de trace (JournalScreen.beginEditing) :
    // compléter une légende déjà là est le cas courant.
    var text by remember(photo.id) {
        val initial = photo.caption.orEmpty()
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Légende") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Ajouter une légende") },
                // RIC-176 : majuscule automatique en début de phrase, comme le champ de note de
                // JournalScreen (même nature de texte libre, saisi au clavier).
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.text) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Annuler") }
        },
    )
}

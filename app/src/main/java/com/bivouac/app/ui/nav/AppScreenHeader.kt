package com.bivouac.app.ui.nav

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * En-tête commun aux écrans plein natif (Journal, Réglages) : titre à gauche, sélecteur de
 * section à droite. Partagé plutôt que recopié — une première duplication, entre Journal et
 * Réglages, avait déjà divergé en pratique : le titre de Réglages restait sur la couleur de texte
 * par défaut (noire) faute d'être posé dans un `Surface`/`Scaffold` comme celui du Journal, et
 * passait au noir sur fond noir en thème sombre. Un seul composant, appelé dans le topBar d'un
 * Scaffold des deux côtés, ferme la porte à ce genre d'écart.
 */
@Composable
fun AppScreenHeader(
    title: String,
    currentSection: AppSection,
    onSectionSelected: (AppSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        SectionMenuButton(current = currentSection, onSelect = onSectionSelected)
    }
}

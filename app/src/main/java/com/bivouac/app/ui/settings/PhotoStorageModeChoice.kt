package com.bivouac.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bivouac.app.data.photo.PhotoStorageMode
import com.bivouac.app.data.photo.PhotoStoragePolicy

/**
 * RIC-157 : le choix « Stockage des photos », rendu une seule fois pour ses deux points d'entrée :
 * la section Photos des Réglages, et la proposition posée après une mise à jour
 * (PhotoStorageChoicePrompt).
 *
 * Partagé et pas recopié : c'est le même choix, avec les mêmes conséquences, et le voir formulé de
 * deux façons différentes selon l'endroit où on tombe dessus donnerait l'impression de deux
 * réglages distincts.
 *
 * Même patron que le mode de calcul de la vitesse juste au-dessus dans les Réglages : boutons
 * segmentés pour les deux branches, et sous eux la phrase qui dit ce que la branche COURANTE
 * implique. Deux libellés courts plutôt qu'un long : « Copie réduite (recommandé) » ne tient pas
 * dans un demi-bouton segmenté, et la recommandation vit très bien dans la phrase.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoStorageModeChoice(
    mode: PhotoStorageMode,
    onModeSelected: (PhotoStorageMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            PhotoStorageMode.entries.forEachIndexed { index, candidate ->
                SegmentedButton(
                    selected = candidate == mode,
                    onClick = { onModeSelected(candidate) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = PhotoStorageMode.entries.size),
                    label = { Text(candidate.label(), maxLines = 1) },
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            mode.explanation(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

// L'ordre de déclaration de l'enum est celui des boutons : FULL d'abord (le comportement
// historique), REDUCED ensuite. Les libellés, eux, ne suivent pas les noms techniques : personne
// n'a à savoir que la valeur stockée s'appelle FULL.
internal fun PhotoStorageMode.label(): String = when (this) {
    PhotoStorageMode.FULL -> "Pleine résolution"
    PhotoStorageMode.REDUCED -> "Copie réduite"
}

/**
 * Le compromis en une phrase, du point de vue de la branche choisie. Les deux disent le poids ET la
 * fidélité, dans cet ordre : c'est le poids qui motive le ticket, mais c'est la fidélité qu'on
 * craint de perdre.
 */
internal fun PhotoStorageMode.explanation(): String = when (this) {
    PhotoStorageMode.FULL ->
        "Chaque photo est copiée à l'identique : rien n'est perdu, mais le Journal grossit vite " +
            "(environ 4 Mo par photo)."
    PhotoStorageMode.REDUCED ->
        "Recommandé : les photos sont ramenées à ${PhotoStoragePolicy.REDUCED_LONG_SIDE_PX} px sur " +
            "leur grand côté, une dizaine de fois plus légères et largement assez nettes pour les " +
            "revoir. Tes photos d'origine, elles, ne sont jamais touchées."
}

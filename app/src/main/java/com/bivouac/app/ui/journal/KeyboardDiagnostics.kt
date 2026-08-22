package com.bivouac.app.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * RIC-100, outillage jetable. Quatre correctifs successifs ont échoué sur le même symptôme (les
 * nouvelles lignes d'une note passent sous le clavier au fil de la frappe), chacun fondé sur une
 * hypothèse plausible et fausse. Plutôt qu'un cinquième essai au jugé, cet affichage relève les
 * quatre grandeurs qui séparent les deux familles de causes possibles.
 *
 * **Famille A, l'inset n'arrive pas.** Le clavier s'ouvre mais `ime` reste à 0, ou `vp` ne diminue
 * pas : c'est la réservation de place qui échoue, et le correctif est du côté des insets ou du
 * mode de redimensionnement de la fenêtre.
 *
 * **Famille B, personne ne demande le défilement.** `ime` monte, `vp` diminue, mais `scr` ne bouge
 * pas pendant la frappe alors que `note` descend sous la ligne de flottaison : la place est bien
 * réservée, mais rien ne réclame que le curseur soit ramené dans la vue. Le correctif est alors de
 * suivre le rectangle du curseur.
 *
 * À retirer dès la cause établie : [ENABLED] à false suffit à faire disparaître l'affichage, et le
 * fichier entier part avec le correctif définitif.
 */
const val KEYBOARD_DIAGNOSTICS_ENABLED = true

@Stable
class KeyboardProbe {
    /** Hauteur de l'inset clavier. 0 clavier fermé, sinon la hauteur du clavier. */
    var imeBottomPx by mutableIntStateOf(0)

    /** Hauteur de la fenêtre de défilement, celle qui doit se rogner à l'ouverture du clavier. */
    var viewportHeightPx by mutableIntStateOf(0)

    /** Hauteur du contenu défilant. Ne bouge pas avec le clavier, sert de repère. */
    var contentHeightPx by mutableIntStateOf(0)

    /** Position et amplitude du défilement de cette zone. */
    var scrollValue by mutableIntStateOf(0)
    var scrollMax by mutableIntStateOf(0)

    /** Bas du champ de notes, en coordonnées fenêtre. Comparé à [floorPx] pour savoir s'il est masqué. */
    var noteBottomPx by mutableIntStateOf(0)

    /**
     * Nombre de demandes de défilement émises vers le bas du champ, et pourquoi la dernière a été
     * refusée le cas échéant. Sépare « la demande n'est jamais émise » de « elle est émise mais
     * sans effet », deux causes qui ne se corrigent pas au même endroit.
     */
    var bringCount by mutableIntStateOf(0)
    var bringSkip by mutableStateOf("-")

    /** Hauteur totale disponible, pour situer [noteBottomPx]. */
    var windowHeightPx by mutableIntStateOf(0)

    /** Ligne de flottaison : sous cette ordonnée, on est derrière le clavier. */
    val floorPx: Int get() = windowHeightPx - imeBottomPx

    /** Ce qui dépasse sous le clavier. Positif = le bas du champ est masqué, et de combien. */
    val hiddenPx: Int get() = noteBottomPx - floorPx
}

/**
 * Bandeau de relevé, à lire pendant la frappe. Volontairement brut et dense : il doit tenir en
 * haut de l'écran sans masquer la saisie, et se lire d'un coup d'œil sur une capture.
 */
@Composable
fun KeyboardDiagnosticsOverlay(probe: KeyboardProbe, modifier: Modifier = Modifier) {
    if (!KEYBOARD_DIAGNOSTICS_ENABLED) return
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(Color(0xCC000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            "ime ${probe.imeBottomPx}  vp ${probe.viewportHeightPx}  cnt ${probe.contentHeightPx}  win ${probe.windowHeightPx}",
            style = style,
        )
        Text(
            "scr ${probe.scrollValue}/${probe.scrollMax}  note ${probe.noteBottomPx}  " +
                "bring ${probe.bringCount} ${probe.bringSkip}",
            style = style,
        )
        Text(
            "sol ${probe.floorPx}  masqué ${probe.hiddenPx}",
            style = style.copy(color = if (probe.hiddenPx > 0) Color(0xFFFF8A80) else Color(0xFF9CCC65)),
        )
    }
}

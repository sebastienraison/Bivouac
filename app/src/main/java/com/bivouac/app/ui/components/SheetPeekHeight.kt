package com.bivouac.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * RIC-116 : le plafond de hauteur du repli des deux BottomSheetScaffold de l'app (Journal, Import
 * GPX), pour qu'un tiroir ne puisse pas avaler la carte sur une fenêtre courte (paysage, écran
 * partagé, petit téléphone).
 *
 * Lit la hauteur de la FENÊTRE (LocalWindowInfo) et non Configuration.screenHeightDp, pour deux
 * raisons qui vont dans le même sens :
 *
 * 1. À partir de targetSdk 35, Configuration.screenHeightDp inclut les barres système, alors
 *    qu'il les excluait avant : la même expression n'aurait pas rendu la même valeur selon la
 *    version d'Android sous laquelle l'app tourne. C'est le seul changement de comportement
 *    d'Android 15 qui atteignait du code de cette app (lint le signale sous
 *    ConfigurationScreenWidthHeight).
 * 2. L'app est en edge-to-edge : ses tiroirs sont posés et mesurés dans la fenêtre entière, barres
 *    comprises. La hauteur de fenêtre est donc la grandeur que le calcul visait depuis le début,
 *    et elle ne dépend d'aucune sémantique de Configuration.
 *
 * Le plafond ne mord que sur une fenêtre de moins de deux fois [EMPTY_SHEET_PEEK_HEIGHT] (300 dp), donc
 * jamais sur un téléphone courant, même en paysage.
 */
@Composable
@ReadOnlyComposable
fun halfWindowHeight(): Dp {
    val heightPx = LocalWindowInfo.current.containerSize.height
    return with(LocalDensity.current) { heightPx.toDp() } / 2f
}

/**
 * Hauteur utile, au-dessus de la barre de navigation, du cran bas d'un tiroir qui ne porte pas de
 * contenu à mesurer (la banque de Planification, la vue « plusieurs traces » du Journal).
 */
val EMPTY_SHEET_PEEK_HEIGHT = 150.dp

/**
 * RIC-224, étendu au Journal par RIC-237 : hauteur du cran bas d'un tiroir. Le tiroir est posé
 * dans la fenêtre entière (edge-to-edge) : sa hauteur de repli compte donc aussi la zone de la
 * barre de navigation. Sans elle, une barre opaque (navigation à trois boutons, 48 dp) mangeait
 * d'autant la hauteur utile et cachait la première ligne du tiroir (la ligne des chiffres de la
 * première trace de la banque, le titre et la croix de la vue « plusieurs traces »). La hauteur
 * utile au-dessus de la barre reste [EMPTY_SHEET_PEEK_HEIGHT] ; en gestes, la barre est fine
 * (environ 16 dp) et la différence s'y limite. Le plafond de [halfWindowHeight] s'applique au
 * total, pour qu'une fenêtre courte garde sa carte.
 */
fun peekHeightAboveNavigationBar(navigationBarHeight: Dp, maxPeekHeight: Dp): Dp =
    (EMPTY_SHEET_PEEK_HEIGHT + navigationBarHeight).coerceAtMost(maxPeekHeight)

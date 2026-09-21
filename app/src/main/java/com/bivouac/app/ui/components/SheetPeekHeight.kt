package com.bivouac.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

/**
 * RIC-116 : le plafond de hauteur du repli des deux BottomSheetScaffold de l'app (Journal, Import
 * GPX), pour qu'un tiroir ne puisse pas avaler la carte sur une fenêtre courte (paysage, écran
 * partagé, petit téléphone).
 *
 * Lit la hauteur de la FENÊTRE (LocalWindowInfo) et non Configuration.screenHeightDp, pour deux
 * raisons qui vont dans le même sens :
 *
 * 1. Sous targetSdk 35, Configuration.screenHeightDp inclut désormais les barres système, alors
 *    qu'il les excluait avant : la même expression n'aurait pas rendu la même valeur selon la
 *    version d'Android sous laquelle l'app tourne. C'est le seul changement de comportement
 *    d'Android 15 qui atteignait du code de cette app (lint le signale sous
 *    ConfigurationScreenWidthHeight).
 * 2. L'app est en edge-to-edge : ses tiroirs sont posés et mesurés dans la fenêtre entière, barres
 *    comprises. La hauteur de fenêtre est donc la grandeur que le calcul visait depuis le début,
 *    et elle ne dépend d'aucune sémantique de Configuration.
 *
 * Le plafond ne mord que sur une fenêtre de moins de deux fois PEEK_HEIGHT_EMPTY (300 dp), donc
 * jamais sur un téléphone courant, même en paysage.
 */
@Composable
@ReadOnlyComposable
fun halfWindowHeight(): Dp {
    val heightPx = LocalWindowInfo.current.containerSize.height
    return with(LocalDensity.current) { heightPx.toDp() } / 2f
}

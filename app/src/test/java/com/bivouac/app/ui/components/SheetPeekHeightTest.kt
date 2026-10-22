package com.bivouac.app.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-224 et RIC-237 : le cran bas partagé par la banque de Planification et la vue « plusieurs
 * traces » du Journal compte la barre de navigation. Ce que ces tests tiennent : l'arithmétique.
 * Que le titre et la croix restent visibles au-dessus de la barre est une vérification à l'écran.
 */
class SheetPeekHeightTest {

    @Test
    fun `sans barre le cran vaut la hauteur utile`() {
        assertEquals(150.dp, peekHeightAboveNavigationBar(0.dp, 600.dp))
    }

    @Test
    fun `trois boutons le cran compte la barre de 48 dp`() {
        assertEquals(198.dp, peekHeightAboveNavigationBar(48.dp, 600.dp))
    }

    @Test
    fun `gestes la difference se limite a la barre fine`() {
        assertEquals(166.dp, peekHeightAboveNavigationBar(16.dp, 600.dp))
    }

    @Test
    fun `le plafond de la demi-fenetre s'applique au total`() {
        assertEquals(120.dp, peekHeightAboveNavigationBar(48.dp, 120.dp))
    }
}

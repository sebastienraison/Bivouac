package com.bivouac.app.ui.gpximport

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-224 : le calcul des crans du tiroir de la Planification, vérifié sans appareil, avec une
 * barre de navigation de 0 px (gestes, ou paysage) et de 144 px (trois boutons, 48 dp à 420 dpi
 * ramenés à une densité de 3 pour garder des chiffres ronds).
 *
 * Ce que ces tests tiennent : l'arithmétique des crans. Ce qu'ils ne tiennent pas : que la table
 * se dessine bien sous le bord bas visible du cran Profil, qui est de la composition ; c'est une
 * vérification à l'écran, en trois boutons puis par gestes.
 */
class PlanificationDrawerHeightsTest {

    private val full = 2400f
    private val status = 100f
    private val summaryContent = 400
    private val margin = 24f // 8 dp
    private val block = 318f // 106 dp
    private val divider = 3f // 1 dp

    private fun heights(nav: Float, measuredSegments: Int, measuredSummary: Int = summaryContent, full: Float = this.full) =
        computePlanificationDrawerHeights(
            fullHeightPx = full,
            measuredSummaryHeightPx = measuredSummary,
            measuredSegmentsAdditionPx = measuredSegments,
            navigationBarHeightPx = nav,
            statusBarHeightPx = status,
            fallbackSummaryHeightPx = 450f,
            summaryBottomMarginPx = margin,
            profileBlockHeightPx = block,
            dividerHeightPx = divider,
        )

    @Test
    fun `gestes barre nulle le cran Profil ne reserve que le separateur`() {
        val h = heights(nav = 0f, measuredSegments = 600)
        assertEquals(summaryContent + margin, h.summaryHeightPx, 0.001f)
        assertEquals(h.summaryHeightPx + block + divider, h.profileHeightPx, 0.001f)
        assertEquals(h.profileHeightPx + 600f, h.detailHeightPx, 0.001f)
    }

    @Test
    fun `trois boutons la table commence exactement sous le bord bas du cran Profil`() {
        val nav = 144f
        val h = heights(nav = nav, measuredSegments = 600)
        // Disposition du composable : synthese (contenu + bande barre + marge), bloc profil,
        // separateur, espace barre de navigation. La table suit : son haut est donc au bord bas
        // visible du cran Profil, jamais dessous la barre.
        val tableTop = summaryContent + nav + margin + block + divider + nav
        assertEquals(tableTop, h.profileHeightPx, 0.001f)
    }

    @Test
    fun `trois boutons le cran Details compte le padding bas de la table`() {
        val nav = 144f
        val h = heights(nav = nav, measuredSegments = 600)
        // Contenu total dessine : tout ce qui precede la table, la table mesuree, et son padding
        // barre de navigation en bas (que sa mesure n'inclut pas).
        val contentTotal = summaryContent + nav + margin + block + divider + nav + 600 + nav
        assertEquals(contentTotal, h.detailHeightPx, 0.001f)
    }

    @Test
    fun `le cran Profil ne depend pas de la table RIC-95 BIV-57 pas de saut au premier bivouac`() {
        for (nav in listOf(0f, 144f)) {
            val without = heights(nav = nav, measuredSegments = 0)
            val with = heights(nav = nav, measuredSegments = 600)
            assertEquals(without.summaryHeightPx, with.summaryHeightPx, 0.001f)
            assertEquals(without.profileHeightPx, with.profileHeightPx, 0.001f)
        }
    }

    @Test
    fun `le cran Profil monte d'exactement la barre de navigation RIC-95 libelles de l'axe X`() {
        val gestures = heights(nav = 0f, measuredSegments = 0)
        val threeButtons = heights(nav = 144f, measuredSegments = 0)
        // La synthese compte la barre une fois, la reserve sous la courbe une fois.
        assertEquals(gestures.profileHeightPx + 2 * 144f, threeButtons.profileHeightPx, 0.001f)
    }

    @Test
    fun `sans bivouac le cran Details n'ajoute rien au cran Profil`() {
        val h = heights(nav = 144f, measuredSegments = 0)
        assertEquals(h.profileHeightPx, h.detailHeightPx, 0.001f)
    }

    @Test
    fun `grand contenu barre nulle le cran Details plafonne a l'ecran et la table defile`() {
        val h = heights(nav = 0f, measuredSegments = 5000)
        assertEquals(full, h.detailHeightPx, 0.001f)
        assertEquals(full - h.profileHeightPx - status, h.segmentsMaxHeightPx, 0.001f)
        assertTrue(h.segmentsMaxHeightPx > 0f)
    }

    @Test
    fun `grand contenu trois boutons meme plafond la barre ne le deplace pas`() {
        val h = heights(nav = 144f, measuredSegments = 5000)
        assertEquals(full, h.detailHeightPx, 0.001f)
        assertEquals(full - h.profileHeightPx - status, h.segmentsMaxHeightPx, 0.001f)
    }

    @Test
    fun `mesure de la synthese absente repli sur la hauteur par defaut plus la barre`() {
        val h = heights(nav = 144f, measuredSegments = 0, measuredSummary = 0)
        assertEquals(450f + 144f, h.summaryHeightPx, 0.001f)
    }

    @Test
    fun `fenetre tres courte les crans restent bornes par l'ecran`() {
        val h = heights(nav = 144f, measuredSegments = 600, full = 500f)
        assertTrue(h.summaryHeightPx <= 500f)
        assertTrue(h.profileHeightPx in h.summaryHeightPx..500f)
        assertTrue(h.detailHeightPx in h.profileHeightPx..500f)
        assertEquals(0f, h.segmentsMaxHeightPx, 0.001f)
    }
}

package com.bivouac.app.ui.map

import com.bivouac.app.ui.map.PauseLabelLayout.Anchor
import com.bivouac.app.ui.map.PauseLabelLayout.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** RIC-216 : l'empilement des étiquettes de pause, voir [PauseLabelLayout]. */
class PauseLabelLayoutTest {

    // Valeurs rondes en pixels : étiquette de 40 px de large de texte + 2 x 4 px de marge, soit 48 px,
    // et 14 px de haut ; base 20 px au-dessus du marqueur ; écart de 2 px.
    private val padding = 4f
    private val height = 14f
    private val baseOffset = 20f
    private val gap = 2f

    private fun stack(vararg anchors: Anchor): List<Box> =
        PauseLabelLayout.stack(anchors.toList(), padding, height, baseOffset, gap)

    private fun anchor(x: Float, y: Float, textWidth: Float = 40f) = Anchor(x, y, textWidth)

    @Test
    fun `a single label sits at its base position`() {
        val boxes = stack(anchor(100f, 200f))

        assertEquals(listOf(Box(left = 76f, top = 166f, right = 124f, bottom = 180f)), boxes)
    }

    @Test
    fun `disjoint labels do not move`() {
        // Écart horizontal de 60 px entre les marqueurs : les rectangles de 48 px ne se touchent pas.
        val boxes = stack(anchor(100f, 200f), anchor(160f, 200f))

        assertEquals(180f, boxes[0].bottom, 0f)
        assertEquals(180f, boxes[1].bottom, 0f)
        assertFalse(boxes[0].overlaps(boxes[1]))
    }

    @Test
    fun `overlapping labels stack, the later one above the earlier one`() {
        // Écart horizontal de 30 px : les rectangles se recouvrent en largeur, à la même hauteur.
        val boxes = stack(anchor(100f, 200f), anchor(130f, 200f))

        assertEquals(180f, boxes[0].bottom, 0f)
        // Bas du second = haut du premier (166) moins l'écart (2).
        assertEquals(164f, boxes[1].bottom, 0f)
        assertEquals(150f, boxes[1].top, 0f)
        assertFalse(boxes[0].overlaps(boxes[1]))
    }

    @Test
    fun `a stacked label keeps the abscissa of its marker`() {
        val boxes = stack(anchor(100f, 200f), anchor(130f, 200f))

        assertEquals(130f - 24f, boxes[1].left, 0f)
        assertEquals(130f + 24f, boxes[1].right, 0f)
    }

    @Test
    fun `three labels in cascade climb one above the other`() {
        val boxes = stack(anchor(100f, 200f), anchor(110f, 200f), anchor(120f, 200f))

        assertEquals(180f, boxes[0].bottom, 0f)
        assertEquals(164f, boxes[1].bottom, 0f)
        assertEquals(148f, boxes[2].bottom, 0f)
        for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse(boxes[i].overlaps(boxes[j]))
    }

    @Test
    fun `a label only climbs above the labels it actually overlaps`() {
        // 2 recouvre 1 et monte. 3 (rectangle de 36 à 84) recouvre 1 (de 76 à 124) mais pas 2 (de 86
        // à 134) : il monte juste au-dessus de 1, au même niveau que 2, sans passer au-dessus de 2.
        val boxes = stack(anchor(100f, 200f), anchor(110f, 200f), anchor(60f, 200f))

        assertEquals(180f, boxes[0].bottom, 0f)
        assertEquals(164f, boxes[1].bottom, 0f)
        assertEquals(164f, boxes[2].bottom, 0f)
        for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse(boxes[i].overlaps(boxes[j]))
    }

    @Test
    fun `labels overlapping in width only, at distant heights, do not move`() {
        // Même abscisse, mais 100 px d'écart vertical : aucun recouvrement en hauteur.
        val boxes = stack(anchor(100f, 200f), anchor(100f, 300f))

        assertEquals(180f, boxes[0].bottom, 0f)
        assertEquals(280f, boxes[1].bottom, 0f)
    }

    @Test
    fun `labels touching by an edge only are not considered overlapping`() {
        // Rectangles de 48 px de large, marqueurs distants de 48 px : bords qui se touchent.
        val boxes = stack(anchor(100f, 200f), anchor(148f, 200f))

        assertEquals(180f, boxes[1].bottom, 0f)
    }

    @Test
    fun `placement order follows the list order, not the screen position`() {
        // Même paire inversée : celui qui vient en premier dans la liste garde sa place.
        val first = stack(anchor(100f, 200f), anchor(130f, 200f))
        val reversed = stack(anchor(130f, 200f), anchor(100f, 200f))

        assertEquals(180f, first[0].bottom, 0f)
        assertEquals(164f, first[1].bottom, 0f)
        assertEquals(180f, reversed[0].bottom, 0f)
        assertEquals(164f, reversed[1].bottom, 0f)
    }

    @Test
    fun `an empty list places nothing`() {
        assertEquals(emptyList<Box>(), stack())
    }
}

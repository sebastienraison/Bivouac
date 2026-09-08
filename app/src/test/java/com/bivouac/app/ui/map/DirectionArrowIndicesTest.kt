package com.bivouac.app.ui.map

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-139 : le placement des flèches de direction, en coordonnées écran pures.
 *
 * Le défaut corrigé : BIV-46 posait deux à quatre flèches tous les 2500 m de terrain, calculées une
 * fois pour toutes ; zoomé, elles tombaient toutes hors du cadre et la trace n'en portait plus une
 * seule. L'espacement se mesure maintenant en fraction de la largeur visible, et le recalcul suit
 * le zoom et le déplacement.
 *
 * Toutes les traces ci-dessous sont synthétiques et déjà projetées : c'est exactement ce que la
 * fonction reçoit en vrai, une fois la projection osmdroid appliquée.
 */
class DirectionArrowIndicesTest {

    private val viewportWidth = 1000.0
    private val viewportHeight = 2000.0
    private val margin = 20.0
    private val minSeparation = 26.0

    private fun indices(
        xs: DoubleArray,
        ys: DoubleArray,
        width: Double = viewportWidth,
        height: Double = viewportHeight,
        maxArrows: Int = 40,
    ): List<Int> = directionArrowIndices(
        screenX = xs,
        screenY = ys,
        viewportWidthPx = width,
        viewportHeightPx = height,
        marginPx = margin,
        minSeparationPx = minSeparation,
        maxArrows = maxArrows,
    )

    /** Ligne horizontale d'un pixel par point, traversant tout l'écran et débordant des deux côtés. */
    private fun horizontalLine(fromX: Int, toX: Int, y: Double): Pair<DoubleArray, DoubleArray> {
        val xs = (fromX..toX).map { it.toDouble() }.toDoubleArray()
        val ys = DoubleArray(xs.size) { y }
        return xs to ys
    }

    @Test
    fun spacingIsAThirdOfTheVisibleWidth() {
        val (xs, ys) = horizontalLine(0, 3000, y = 500.0)

        val placed = indices(xs, ys)

        assertTrue("une trace traversant l'écran doit porter plusieurs flèches", placed.size >= 3)
        // Espacement attendu : un tiers de 1000 px (retouche recette, RIC-139 : 25 % faisait trop
        // dense, environ trois flèches par largeur d'écran visées au lieu de quatre). Les positions
        // sont les x eux-mêmes ici.
        placed.zipWithNext().forEach { (a, b) ->
            assertEquals("l'espacement doit valoir un tiers de largeur", 1000.0 / 3.0, xs[b] - xs[a], 2.0)
        }
    }

    /** Zoomer, c'est étirer la trace à l'écran : à cadrage égal, il doit y avoir plus de flèches. */
    @Test
    fun zoomingInKeepsTheSameOnScreenDensity() {
        val (farXs, farYs) = horizontalLine(0, 3000, y = 500.0)
        // Deux fois plus étirée : la portion visible ne couvre plus que la moitié de la trace, mais
        // l'écran, lui, est tout aussi rempli : le nombre de flèches VISIBLES ne doit pas bouger.
        val zoomedXs = DoubleArray(farXs.size) { farXs[it] * 2 }

        val far = indices(farXs, farYs)
        val zoomed = indices(zoomedXs, farYs)

        assertEquals(far.size, zoomed.size)
    }

    @Test
    fun nothingIsPlacedOutsideTheViewport() {
        val (xs, ys) = horizontalLine(-4000, 4000, y = 500.0)

        indices(xs, ys).forEach { i ->
            assertTrue("flèche hors cadre en $i : x=${xs[i]}", xs[i] >= margin && xs[i] <= viewportWidth - margin)
            assertTrue(ys[i] >= margin && ys[i] <= viewportHeight - margin)
        }
    }

    /**
     * Lacets serrés : la trace fait des allers-retours de 30 px de large en descendant l'écran.
     * Un tiers de largeur le long du tracé n'y fait que quelques pixels en ligne droite : sans la
     * distance minimale, les flèches s'empileraient les unes sur les autres.
     */
    @Test
    fun consecutiveArrowsNeverOverlapInTightSwitchbacks() {
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        var y = 100.0
        repeat(40) { leg ->
            val goingRight = leg % 2 == 0
            val range = if (goingRight) (0..30) else (30 downTo 0)
            range.forEach { step ->
                xs += 500.0 + step
                ys += y
            }
            y += 5.0
        }
        val placed = indices(xs.toDoubleArray(), ys.toDoubleArray())

        placed.zipWithNext().forEach { (a, b) ->
            val gap = hypot(xs[b] - xs[a], ys[b] - ys[a])
            assertTrue("deux flèches consécutives à $gap px, sous le seuil de $minSeparation", gap >= minSeparation)
        }
    }

    /**
     * Aller-retour : la trace part vers la droite puis revient sur ses pas quelques pixels plus bas.
     * Les flèches de l'aller et du retour se côtoient en pointant en sens inverse, et c'est VOULU :
     * la règle anti-chevauchement ne compare qu'aux flèches consécutives, jamais aux précédentes
     * passages. Le test le vérifie en exigeant des flèches sur les deux branches.
     */
    @Test
    fun outAndBackKeepsArrowsOnBothLegs() {
        val out = (100..900).map { it.toDouble() }
        val back = (900 downTo 100).map { it.toDouble() }
        val xs = (out + back).toDoubleArray()
        val ys = DoubleArray(xs.size) { if (it < out.size) 500.0 else 508.0 }

        val placed = indices(xs, ys)

        assertTrue("l'aller doit porter des flèches", placed.any { it < out.size })
        assertTrue("le retour aussi, en sens inverse", placed.any { it >= out.size })
    }

    /**
     * Trace plus courte que l'espacement : une petite boucle vue de loin. Sans traitement, aucun
     * seuil n'est franchi et la boucle se retrouve sans aucune flèche, exactement le cas où la
     * direction est ambiguë (marqueur de départ et d'arrivée confondus).
     */
    @Test
    fun aTrackShorterThanTheSpacingStillGetsOneArrow() {
        val (xs, ys) = horizontalLine(400, 500, y = 500.0)

        val placed = indices(xs, ys)

        assertEquals(1, placed.size)
        assertEquals("la flèche unique doit tomber au milieu du tracé", 450.0, xs[placed.single()], 5.0)
    }

    /** Une trace entièrement hors cadre ne produit rien, pas même la flèche de repli. */
    @Test
    fun aTrackEntirelyOffScreenGetsNothing() {
        val (xs, ys) = horizontalLine(3000, 3100, y = 5000.0)

        assertEquals(emptyList<Int>(), indices(xs, ys))
    }

    @Test
    fun theArrowCountIsCapped() {
        val (xs, ys) = horizontalLine(0, 60000, y = 500.0)

        assertTrue(indices(xs, ys, maxArrows = 5).size <= 5)
    }

    @Test
    fun degenerateInputsAreRefusedRatherThanCrashing() {
        val (xs, ys) = horizontalLine(0, 3000, y = 500.0)

        assertEquals(emptyList<Int>(), indices(DoubleArray(2), DoubleArray(2)))
        assertEquals(emptyList<Int>(), indices(xs, ys, width = 0.0))
        assertEquals(emptyList<Int>(), indices(xs, ys, height = 0.0))
        assertEquals(emptyList<Int>(), indices(xs, ys, maxArrows = 0))
    }
}

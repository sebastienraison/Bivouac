package com.bivouac.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAxisLabelsTest {
    // Un graphe de 72 dp, un libellé de 12 dp, 2 dp d'écart : valeurs du profil réel à densité 1.
    private val plotHeight = 72f
    private val labelHeight = 12f
    private val gap = 2f

    private fun resolve(min: Double, max: Double, candidates: List<Double>) =
        resolveAltitudeMarks(min, max, candidates, plotHeight, labelHeight, gap)

    // Position dessinée de chaque libellé, pour vérifier l'absence de chevauchement sur ce qui sort.
    private fun top(min: Double, max: Double, elevation: Double): Float {
        val y = (plotHeight - (elevation - min) / (max - min) * plotHeight).toFloat()
        return altitudeLabelTop(y, labelHeight, plotHeight)
    }

    private fun assertNoOverlap(min: Double, max: Double, marks: List<Double>) {
        val tops = (listOf(min, max) + marks).map { top(min, max, it) }.sorted()
        tops.zipWithNext().forEach { (a, b) -> assertTrue("$a puis $b", b - a >= labelHeight + gap) }
    }

    @Test
    fun repereArrondiVersLeMaxEstRetire() {
        // Cas réel : max 2 754, repère 2 500 arrondi, à 254 m soit 11 dp sous le max. Le 1 500 est
        // lui aussi trop près du min (1 130) une fois le libellé du min rabattu dans le graphe.
        val kept = resolve(1130.0, 2754.0, listOf(2500.0, 2000.0, 1500.0))
        assertEquals(listOf(2000.0), kept)
        assertNoOverlap(1130.0, 2754.0, kept)
    }

    @Test
    fun repereArrondiVersLeMinEstRetire() {
        // 900 à 58 m du min (842) sur 600 m de plage : 7 dp, le libellé du min est rabattu juste dessous.
        val kept = resolve(842.0, 1442.0, listOf(900.0, 1250.0))
        assertEquals(listOf(1250.0), kept)
        assertNoOverlap(842.0, 1442.0, kept)
    }

    @Test
    fun repereProcheDuMinParLeCoerceInEstRetire() {
        // Cas réel : 1 000 à 158 m du min (842) sur 600 m, soit 19 dp. En position théorique, il ne
        // chevauche pas le min ; le libellé du min étant rabattu vers le haut (coerceIn), il le fait.
        val kept = resolve(842.0, 1442.0, listOf(1000.0))
        assertTrue(kept.isEmpty())
    }

    @Test
    fun repereAssezEloigneEstConserve() {
        val kept = resolve(842.0, 1442.0, listOf(1100.0, 1250.0))
        assertEquals(listOf(1100.0, 1250.0), kept)
        assertNoOverlap(842.0, 1442.0, kept)
    }

    @Test
    fun deuxRepereVoisinsGardentLePlusHaut() {
        val kept = resolve(0.0, 3000.0, listOf(1500.0, 1450.0))
        assertEquals(listOf(1500.0), kept)
    }

    @Test
    fun grapheBasRetireLesRepresIntermediaires() {
        // 30 dp de haut : le min et le max se tiennent à peine, rien ne rentre entre eux.
        val kept = resolveAltitudeMarks(0.0, 1000.0, listOf(500.0), 30f, labelHeight, gap)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun repereJusteAuDessusDuMinChevaucheLeMinRabattu() {
        // Repère à 6 dp du min : chevauche le libellé du min rabattu vers le haut.
        val kept = resolveAltitudeMarks(0.0, 1000.0, listOf(100.0), plotHeight, labelHeight, gap)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun plageFaibleSansRepere() {
        assertTrue(evenlySpacedRoundMarks(500.0, 530.0, ALTITUDE_MIN_SPACING, MAX_INTERMEDIATE_GRIDLINES, ALTITUDE_ROUNDING_UNITS).isEmpty())
        assertTrue(resolve(500.0, 530.0, emptyList()).isEmpty())
    }

    @Test
    fun plageDeMoinsDUnMetreRetireLIntermediaire() {
        assertEquals(emptyList<Double>(), resolve(1000.0, 1000.5, listOf(1000.2)))
    }

    @Test
    fun altitudeLabelTopResteDansLeGraphe() {
        assertEquals(0f, altitudeLabelTop(0f, 12f, 72f), 0f)
        assertEquals(60f, altitudeLabelTop(72f, 12f, 72f), 0f)
        assertEquals(30f, altitudeLabelTop(36f, 12f, 72f), 0f)
        assertEquals(0f, altitudeLabelTop(10f, 12f, 8f), 0f)
    }

    // --- axe horizontal ---

    private fun slot(left: Float, width: Float, priority: AxisLabelPriority) = AxisLabelSlot(left, width, priority)

    private val margin = 4f

    @Test
    fun curseurSurUnRepereLeFaitDisparaitre() {
        // Cas réel : curseur à 14,3 (largeur 16) à côté du repère 15.
        val slots = listOf(
            slot(100f, 16f, AxisLabelPriority.INTERMEDIATE),
            slot(90f, 16f, AxisLabelPriority.CURSOR),
        )
        assertEquals(listOf(false, true), resolveAxisLabels(slots, margin))
    }

    @Test
    fun curseurSurZeroFaitDisparaitreZero() {
        val slots = listOf(
            slot(32f, 6f, AxisLabelPriority.ENDPOINT),
            slot(34f, 16f, AxisLabelPriority.CURSOR),
        )
        assertEquals(listOf(false, true), resolveAxisLabels(slots, margin))
    }

    @Test
    fun curseurSurLeTotalFaitDisparaitreLeTotal() {
        val slots = listOf(
            slot(300f, 16f, AxisLabelPriority.ENDPOINT),
            slot(296f, 20f, AxisLabelPriority.CURSOR),
        )
        assertEquals(listOf(false, true), resolveAxisLabels(slots, margin))
    }

    @Test
    fun curseurSurUnBivouacFaitDisparaitreLeBivouac() {
        val slots = listOf(
            slot(150f, 16f, AxisLabelPriority.BIVOUAC),
            slot(150f, 16f, AxisLabelPriority.CURSOR),
        )
        assertEquals(listOf(false, true), resolveAxisLabels(slots, margin))
    }

    @Test
    fun bivouacPresDeZeroDisparaitMaisZeroRestePlusPrioritaire() {
        val slots = listOf(
            slot(32f, 6f, AxisLabelPriority.ENDPOINT),
            slot(36f, 16f, AxisLabelPriority.BIVOUAC),
        )
        assertEquals(listOf(true, false), resolveAxisLabels(slots, margin))
    }

    @Test
    fun bivouacPrimeSurRepereIntermediaire() {
        val slots = listOf(
            slot(100f, 16f, AxisLabelPriority.INTERMEDIATE),
            slot(110f, 16f, AxisLabelPriority.BIVOUAC),
        )
        assertEquals(listOf(false, true), resolveAxisLabels(slots, margin))
    }

    @Test
    fun margeDeQuatreEstRespectee() {
        // 100..116 puis 120 : exactement 4 de marge, pas de chevauchement ; 119 : chevauchement.
        val ok = listOf(slot(100f, 16f, AxisLabelPriority.ENDPOINT), slot(120f, 16f, AxisLabelPriority.INTERMEDIATE))
        assertEquals(listOf(true, true), resolveAxisLabels(ok, margin))
        val ko = listOf(slot(100f, 16f, AxisLabelPriority.ENDPOINT), slot(119f, 16f, AxisLabelPriority.INTERMEDIATE))
        assertEquals(listOf(true, false), resolveAxisLabels(ko, margin))
    }

    @Test
    fun libellesDistantsSontTousGardes() {
        val slots = listOf(
            slot(32f, 6f, AxisLabelPriority.ENDPOINT),
            slot(280f, 20f, AxisLabelPriority.ENDPOINT),
            slot(100f, 16f, AxisLabelPriority.BIVOUAC),
            slot(160f, 16f, AxisLabelPriority.CURSOR),
            slot(220f, 16f, AxisLabelPriority.INTERMEDIATE),
        )
        assertTrue(resolveAxisLabels(slots, margin).all { it })
    }

    @Test
    fun ordreDEntreeDepartageUneMemePriorite() {
        val slots = listOf(
            slot(100f, 16f, AxisLabelPriority.INTERMEDIATE),
            slot(105f, 16f, AxisLabelPriority.INTERMEDIATE),
        )
        assertEquals(listOf(true, false), resolveAxisLabels(slots, margin))
    }

    @Test
    fun listeVide() {
        assertTrue(resolveAxisLabels(emptyList(), margin).isEmpty())
    }

    @Test
    fun axisLabelLeftAligneEtRabat() {
        assertEquals(32f, axisLabelLeft(AxisLabelAlign.START, 50f, 10f, 32f, 300f), 0f)
        assertEquals(290f, axisLabelLeft(AxisLabelAlign.END, 50f, 10f, 32f, 300f), 0f)
        assertEquals(95f, axisLabelLeft(AxisLabelAlign.CENTER, 100f, 10f, 32f, 300f), 0f)
        assertEquals(32f, axisLabelLeft(AxisLabelAlign.CENTER, 33f, 10f, 32f, 300f), 0f)
        assertEquals(290f, axisLabelLeft(AxisLabelAlign.CENTER, 299f, 10f, 32f, 300f), 0f)
    }

    @Test
    fun axeEnDureeLesHeuresDepartEtArriveePrennentLeDessus() {
        // Rando longue : un repère à 8 dp du départ recouvre « 8:11 » (24 dp de large).
        val slots = listOf(
            slot(32f, 24f, AxisLabelPriority.ENDPOINT),
            slot(276f, 24f, AxisLabelPriority.ENDPOINT),
            slot(36f, 24f, AxisLabelPriority.INTERMEDIATE),
            slot(150f, 24f, AxisLabelPriority.INTERMEDIATE),
        )
        val visible = resolveAxisLabels(slots, margin)
        assertEquals(listOf(true, true, false, true), visible)
        assertFalse(visible[2])
    }
}

package com.bivouac.app.ui.journal

import com.bivouac.app.ui.components.DisplayFigures
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-234 : répartition à deux niveaux du Journal, de haut en bas : les années contre la capsule,
 * puis les randos de chaque année contre la valeur AJUSTÉE de leur année.
 */
class JournalFiguresApportionmentTest {

    private fun figures(distance: Double, gain: Double = 0.0, loss: Double = 0.0) = DisplayFigures(distance, gain, loss)

    private fun yearA() = JournalYearFigures(
        total = figures(9_880.0, 801.0, 700.2),
        rows = listOf(figures(5_450.0, 400.5, 350.1), figures(4_430.0, 400.5, 350.1)),
    )

    private fun yearB() = JournalYearFigures(
        total = figures(2_900.0, 100.6, 100.6),
        rows = listOf(figures(1_450.0, 50.3, 50.3), figures(1_450.0, 50.3, 50.3)),
    )

    private fun capsule() = figures(12_780.0, 901.6, 800.8)

    @Test
    fun threeLevels_sumExactlyAtEachLevelOnDistance() {
        val shown = apportionJournalFigures(capsule(), listOf(yearA(), yearB()))
        // Capsule 12,8 km : 9,9 + 2,9 seraient 12,8 mais 9,88 s'arrondirait seul à 9,9 et 2,9 à 2,9 ; le dixième
        // manquant (127 + 1 = 128) va à l'année de plus grand reste, A (0,8).
        assertEquals(listOf(9_900.0, 2_900.0), shown.map { it.total.distanceMeters })
        assertEquals(12_800.0, shown.sumOf { it.total.distanceMeters }, 1e-9)
        // Randos de l'année A contre 9,9 ; de l'année B contre 2,9.
        assertEquals(listOf(5_500.0, 4_400.0), shown[0].rows.map { it.distanceMeters })
        assertEquals(listOf(1_500.0, 1_400.0), shown[1].rows.map { it.distanceMeters })
        shown.forEach { year -> assertEquals(year.total.distanceMeters, year.rows.sumOf { it.distanceMeters }, 1e-9) }
    }

    @Test
    fun threeLevels_sumExactlyAtEachLevelOnElevation() {
        val shown = apportionJournalFigures(capsule(), listOf(yearA(), yearB()))
        // D+ affiché de la capsule : 901 (tronqué). D- : 800.
        assertEquals(901.0, shown.sumOf { it.total.elevationGainMeters }, 1e-9)
        assertEquals(800.0, shown.sumOf { it.total.elevationLossMeters }, 1e-9)
        shown.forEach { year ->
            assertEquals(year.total.elevationGainMeters, year.rows.sumOf { it.elevationGainMeters }, 1e-9)
            assertEquals(year.total.elevationLossMeters, year.rows.sumOf { it.elevationLossMeters }, 1e-9)
        }
    }

    @Test
    fun rowsFollowTheAdjustedYear_notTheRealOne() {
        // Année A réelle : 9,88 km (9,9 arrondi seul) ; ajustée à 9,9 ici. On force un ajustement
        // différent : capsule 12,66 km pour 9,88 + 2,78, années 98,8 et 27,8 -> 99 + 28 = 127 : A passe
        // à 9,9 et B à 2,8 ; les randos de B sommeront 2,8 et non 2,78 arrondi à part.
        val a = yearA()
        val b = JournalYearFigures(figures(2_780.0), listOf(figures(1_390.0), figures(1_390.0)))
        val shown = apportionJournalFigures(figures(12_660.0), listOf(a, b))
        assertEquals(listOf(9_900.0, 2_800.0), shown.map { it.total.distanceMeters })
        assertEquals(2_800.0, shown[1].rows.sumOf { it.distanceMeters }, 1e-9)
    }

    @Test
    fun withoutCapsule_yearsAreLeftAsTheyAre_andRowsSumToTheirYear() {
        // Filtre actif : la capsule compte tout le Journal, pas les années affichées. Les années ne
        // sont pas ajustées ; les randos le restent contre le total arrondi de leur année.
        val shown = apportionJournalFigures(null, listOf(yearA(), yearB()))
        assertEquals(listOf(9_880.0, 2_900.0), shown.map { it.total.distanceMeters })
        assertEquals(99.0, shown[0].rows.sumOf { it.distanceMeters } / 100.0, 1e-9)
        assertEquals(29.0, shown[1].rows.sumOf { it.distanceMeters } / 100.0, 1e-9)
    }

    @Test
    fun hikeWithoutElevation_keepsItsZeroParts() {
        val year = JournalYearFigures(
            total = figures(6_050.0, 300.6, 200.6),
            rows = listOf(figures(3_025.0, 0.0, 0.0), figures(3_025.0, 300.6, 200.6)),
        )
        val shown = apportionJournalFigures(figures(6_050.0, 300.6, 200.6), listOf(year))
        assertEquals(0.0, shown[0].rows[0].elevationGainMeters, 0.0)
        assertEquals(0.0, shown[0].rows[0].elevationLossMeters, 0.0)
        assertEquals(300.0, shown[0].rows[1].elevationGainMeters, 0.0)
    }

    @Test
    fun emptyJournal_givesNothing() {
        assertEquals(emptyList<JournalYearFigures>(), apportionJournalFigures(figures(0.0), emptyList()))
    }
}

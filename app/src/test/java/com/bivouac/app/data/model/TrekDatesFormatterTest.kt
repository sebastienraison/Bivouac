package com.bivouac.app.data.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrekDatesFormatterTest {

    @Test
    fun `deux jours du meme mois se citent, sans repeter le mois`() {
        val days = listOf(LocalDate.of(2025, 5, 12), LocalDate.of(2025, 5, 13))
        assertEquals("12 et 13 mai 2025", TrekDatesFormatter.format(days))
    }

    @Test
    fun `deux jours a cheval sur deux mois gardent les deux mois`() {
        val days = listOf(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 1))
        assertEquals("31 mars et 1er avril 2026", TrekDatesFormatter.format(days))
    }

    @Test
    fun `trois jours s'encadrent au lieu de s'enumerer`() {
        val days = listOf(
            LocalDate.of(2026, 3, 3),
            LocalDate.of(2026, 3, 4),
            LocalDate.of(2026, 3, 6),
        )
        assertEquals("du 3 au 6 mars 2026", TrekDatesFormatter.format(days))
    }

    @Test
    fun `une plage du meme mois ne le mentionne qu'une fois`() {
        val days = (0..4).map { LocalDate.of(2025, 6, 11).plusDays(it.toLong()) }
        assertEquals("du 11 au 15 juin 2025", TrekDatesFormatter.format(days))
    }

    @Test
    fun `une plage a cheval sur deux mois garde les deux mois`() {
        val days = (0..2).map { LocalDate.of(2020, 3, 30).plusDays(it.toLong()) }
        assertEquals("du 30 mars au 1er avril 2020", TrekDatesFormatter.format(days))
    }

    @Test
    fun `l'annee de depart est tue meme a cheval sur deux annees`() {
        // Une sortie ne dure pas onze mois : un changement d'année ne peut être que décembre vers
        // janvier, donc « du 31 décembre au 2 janvier 2021 » ne se lit pas autrement.
        val days = (0..2).map { LocalDate.of(2020, 12, 31).plusDays(it.toLong()) }
        assertEquals("du 31 décembre au 2 janvier 2021", TrekDatesFormatter.format(days))
    }

    @Test
    fun `deux jours a cheval sur deux annees suivent la meme regle`() {
        val days = listOf(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1))
        assertEquals("31 décembre et 1er janvier 2026", TrekDatesFormatter.format(days))
    }

    @Test
    fun `le premier du mois s'ecrit 1er en fin de plage`() {
        val days = (0..3).map { LocalDate.of(2025, 7, 29).plusDays(it.toLong()) }
        assertEquals("du 29 juillet au 1er août 2025", TrekDatesFormatter.format(days))
    }

    @Test
    fun `le premier du mois s'ecrit 1er en debut de plage`() {
        val days = (0..3).map { LocalDate.of(2025, 8, 1).plusDays(it.toLong()) }
        assertEquals("du 1er au 4 août 2025", TrekDatesFormatter.format(days))
    }

    @Test
    fun `un seul jour ne dit rien de plus que la date de depart deja affichee`() {
        assertNull(TrekDatesFormatter.format(listOf(LocalDate.of(2025, 5, 12))))
    }

    @Test
    fun `aucune date connue ne produit rien`() {
        assertNull(TrekDatesFormatter.format(emptyList()))
    }

    @Test
    fun `deux jours tombant sur la meme date ne comptent que pour un`() {
        // Deux fichiers d'une même journée : rien à encadrer, ce n'est pas un trek de deux jours.
        val days = listOf(LocalDate.of(2025, 5, 12), LocalDate.of(2025, 5, 12))
        assertNull(TrekDatesFormatter.format(days))
    }

    @Test
    fun `les dates sont remises dans l'ordre avant mise en forme`() {
        val days = listOf(LocalDate.of(2025, 5, 13), LocalDate.of(2025, 5, 12))
        assertEquals("12 et 13 mai 2025", TrekDatesFormatter.format(days))
    }

    @Test
    fun `le seuil separe bien la citation de l'encadrement`() {
        val cited = (0 until TrekDatesFormatter.MAX_ENUMERATED_DAYS)
            .map { LocalDate.of(2025, 6, 1).plusDays(it.toLong()) }
        assertEquals("1er et 2 juin 2025", TrekDatesFormatter.format(cited))

        val framed = (0..TrekDatesFormatter.MAX_ENUMERATED_DAYS)
            .map { LocalDate.of(2025, 6, 1).plusDays(it.toLong()) }
        assertEquals("du 1er au 3 juin 2025", TrekDatesFormatter.format(framed))
    }
}

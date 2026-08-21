package com.bivouac.app.data.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrekDatesFormatterTest {

    @Test
    fun `deux jours du meme mois ne repetent ni le mois ni l'annee`() {
        val days = listOf(LocalDate.of(2025, 5, 12), LocalDate.of(2025, 5, 13))
        assertEquals("12 et 13 mai 2025", TrekDatesFormatter.format(days))
    }

    @Test
    fun `trois jours a cheval sur deux mois repetent le mois qui change`() {
        val days = listOf(
            LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 4, 2),
        )
        assertEquals("31 mars, 1er et 2 avril 2026", TrekDatesFormatter.format(days))
    }

    @Test
    fun `un changement d'annee affiche les deux annees en entier`() {
        val days = listOf(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1))
        assertEquals("31 décembre 2025 et 1er janvier 2026", TrekDatesFormatter.format(days))
    }

    @Test
    fun `le premier du mois s'ecrit 1er meme en fin d'enumeration`() {
        val days = listOf(LocalDate.of(2025, 7, 30), LocalDate.of(2025, 7, 31), LocalDate.of(2025, 8, 1))
        assertEquals("30, 31 juillet et 1er août 2025", TrekDatesFormatter.format(days))
    }

    @Test
    fun `au-dela du seuil l'enumeration cede la place a une plage`() {
        val days = (1..6).map { LocalDate.of(2025, 6, 10 + it) }
        assertEquals("du 11 juin au 16 juin 2025", TrekDatesFormatter.format(days))
    }

    @Test
    fun `une plage a cheval sur deux mois garde les deux mois`() {
        val days = (0..4).map { LocalDate.of(2025, 8, 30).plusDays(it.toLong()) }
        assertEquals("du 30 août au 3 septembre 2025", TrekDatesFormatter.format(days))
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
        // Deux fichiers d'une même journée : rien à énumérer, ce n'est pas un trek de deux jours.
        val days = listOf(LocalDate.of(2025, 5, 12), LocalDate.of(2025, 5, 12))
        assertNull(TrekDatesFormatter.format(days))
    }

    @Test
    fun `les dates sont remises dans l'ordre avant mise en forme`() {
        val days = listOf(LocalDate.of(2025, 5, 13), LocalDate.of(2025, 5, 12))
        assertEquals("12 et 13 mai 2025", TrekDatesFormatter.format(days))
    }

    @Test
    fun `le seuil separe bien enumeration et plage`() {
        val enumerated = (0 until TrekDatesFormatter.MAX_ENUMERATED_DAYS)
            .map { LocalDate.of(2025, 6, 1).plusDays(it.toLong()) }
        assertEquals("1er, 2 et 3 juin 2025", TrekDatesFormatter.format(enumerated))

        val ranged = (0..TrekDatesFormatter.MAX_ENUMERATED_DAYS)
            .map { LocalDate.of(2025, 6, 1).plusDays(it.toLong()) }
        assertEquals("du 1er juin au 4 juin 2025", TrekDatesFormatter.format(ranged))
    }
}

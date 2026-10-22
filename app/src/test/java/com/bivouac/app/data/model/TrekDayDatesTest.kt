package com.bivouac.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-233 : un jour est daté par son premier et son dernier point horodaté. Un jour qui passe
 * minuit (fichier gardé en une journée par « Pause », nuit de moins de quatre heures) porte ses
 * deux dates ; un jour contenu dans une seule date n'en porte qu'une.
 */
class TrekDayDatesTest {

    private val paris = ZoneId.of("Europe/Paris")

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(paris).toInstant()

    private fun point(time: Instant?) = TrackPoint(latitude = 42.5, longitude = 2.9, elevationMeters = null, time = time)

    @Test
    fun `un jour qui finit le lendemain porte ses deux dates`() {
        val day = listOf(point(at("2025-12-31T20:00:00")), point(at("2025-12-31T23:30:00")), point(at("2026-01-01T09:15:00")))
        assertEquals(listOf(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1)), TrekDayDates.of(listOf(day), paris))
    }

    @Test
    fun `un jour contenu dans une seule date n'en porte qu'une`() {
        val day = listOf(point(at("2026-06-11T08:00:00")), point(at("2026-06-11T17:30:00")))
        assertEquals(listOf(LocalDate.of(2026, 6, 11)), TrekDayDates.of(listOf(day), paris))
    }

    @Test
    fun `un jour sans horodatage est ignore`() {
        val dated = listOf(point(at("2026-06-11T08:00:00")), point(at("2026-06-11T17:30:00")))
        val undated = listOf(point(null), point(null))
        assertEquals(listOf(LocalDate.of(2026, 6, 11)), TrekDayDates.of(listOf(dated, undated), paris))
    }

    @Test
    fun `premier et dernier point HORODATES, pas les points des extremites`() {
        val day = listOf(
            point(null),
            point(at("2026-06-11T22:00:00")),
            point(at("2026-06-12T01:00:00")),
            point(null),
        )
        assertEquals(listOf(LocalDate.of(2026, 6, 11), LocalDate.of(2026, 6, 12)), TrekDayDates.of(listOf(day), paris))
    }

    @Test
    fun `union de plusieurs jours, dedoublonnee et triee`() {
        val first = listOf(point(at("2026-06-11T08:00:00")), point(at("2026-06-12T00:30:00")))
        val second = listOf(point(at("2026-06-12T09:00:00")), point(at("2026-06-12T18:00:00")))
        assertEquals(
            listOf(LocalDate.of(2026, 6, 11), LocalDate.of(2026, 6, 12)),
            TrekDayDates.of(listOf(first, second), paris),
        )
    }

    // Liste du Journal : fin du jour = startedAtMillis + elapsedSeconds (pilotage, 22/10).
    @Test
    fun `liste, un jour qui passe minuit porte ses deux dates`() {
        val start = at("2025-12-31T20:00:00")
        val elapsed = 13L * 3_600 + 15 * 60 // fin le 1er janvier à 09:15
        assertEquals(
            listOf(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1)),
            TrekDayDates.ofStartAndElapsed(listOf(start.toEpochMilli() to elapsed), paris),
        )
    }

    @Test
    fun `liste, sans duree ecoulee le jour garde sa seule date de debut`() {
        val start = at("2025-12-31T20:00:00")
        assertEquals(
            listOf(LocalDate.of(2025, 12, 31)),
            TrekDayDates.ofStartAndElapsed(listOf(start.toEpochMilli() to null), paris),
        )
    }

    @Test
    fun `liste, un jour sans horodatage est ignore`() {
        val start = at("2026-06-11T08:00:00")
        assertEquals(
            listOf(LocalDate.of(2026, 6, 11)),
            TrekDayDates.ofStartAndElapsed(listOf(start.toEpochMilli() to 3_600L, null to null), paris),
        )
    }
}

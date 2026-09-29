package com.bivouac.app.ui.journal

import com.bivouac.app.data.model.TrackPoint
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-212 : durée affichée au bloc bivouac entre deux jours ([bivouacNightDurationSeconds]),
 * index du repère touché ([bivouacMarkerLocalIndex]) et nombre de blocs selon le nombre de jours
 * AFFICHÉS ([bivouacBlockCount]).
 *
 * Règle des 24 h (substituée le 2026-09-29 par le fil de pilotage à la "règle du lendemain"
 * d'origine du brief, qui comparait des dates locales) : la durée s'affiche si et seulement si
 * l'écart entre arrivée et départ est strictement entre 0 et 24 h, quel que soit le fuseau ou le
 * passage de minuit. Les cas ci-dessous reprennent exactement ceux demandés par le pilotage.
 */
class TimelineBivouacTest {

    @Test
    fun quatorzeHeuresDeBivouacSAffichentAvecLaValeurExacte() {
        // Arrivée 17h, départ le lendemain à 7h : 14 h = 50 400 s.
        val arrival = Instant.parse("2026-07-01T17:00:00Z")
        val departure = Instant.parse("2026-07-02T07:00:00Z")
        assertEquals(50_400.0, bivouacNightDurationSeconds(arrival, departure)!!, 0.001)
    }

    @Test
    fun uneArriveeApresMinuitSuivieDUnDepartLeMatinMemeAfficheSaDuree() {
        // Arrivée 00h30, départ 6h30 le même jour calendaire : 6 h, la règle des 24 h l'affiche
        // (c'est précisément le cas que l'ancienne "règle du lendemain" excluait à tort, raison du
        // changement de règle par le pilotage du 2026-09-29).
        val arrival = Instant.parse("2026-07-02T00:30:00Z")
        val departure = Instant.parse("2026-07-02T06:30:00Z")
        assertEquals(6 * 3_600.0, bivouacNightDurationSeconds(arrival, departure)!!, 0.001)
    }

    @Test
    fun unEcartDeVingtTroisHeuresCinquanteNeufMinutesSAffiche() {
        val arrival = Instant.parse("2026-07-01T08:00:00Z")
        val departure = arrival.plusSeconds(23 * 3_600L + 59 * 60L)
        assertEquals(23 * 3_600.0 + 59 * 60.0, bivouacNightDurationSeconds(arrival, departure)!!, 0.001)
    }

    @Test
    fun unEcartDeVingtQuatreHeuresExactementNAfficheQueLeLogo() {
        val arrival = Instant.parse("2026-07-01T08:00:00Z")
        val departure = arrival.plusSeconds(24 * 3_600L)
        assertNull(bivouacNightDurationSeconds(arrival, departure))
    }

    @Test
    fun unEcartDeTrenteHuitHeuresNAfficheQueLeLogo() {
        // Une journée non enregistrée entre les deux : bien au-delà de la borne des 24 h.
        val arrival = Instant.parse("2026-07-01T08:00:00Z")
        val departure = arrival.plusSeconds(38 * 3_600L)
        assertNull(bivouacNightDurationSeconds(arrival, departure))
    }

    @Test
    fun unEcartNulNAfficheQueLeLogo() {
        val instant = Instant.parse("2026-07-01T08:00:00Z")
        assertNull(bivouacNightDurationSeconds(instant, instant))
    }

    @Test
    fun unEcartNegatifNAfficheQueLeLogo() {
        val arrival = Instant.parse("2026-07-01T08:00:00Z")
        val departure = arrival.minusSeconds(60)
        assertNull(bivouacNightDurationSeconds(arrival, departure))
    }

    @Test
    fun unHorodatageManquantCoteArriveeNAfficheQueLeLogo() {
        assertNull(bivouacNightDurationSeconds(null, Instant.parse("2026-07-02T07:00:00Z")))
    }

    @Test
    fun unHorodatageManquantCoteDepartNAfficheQueLeLogo() {
        assertNull(bivouacNightDurationSeconds(Instant.parse("2026-07-01T17:00:00Z"), null))
    }

    // --- bivouacMarkerLocalIndex ------------------------------------------------------------------

    private fun point(time: Instant?) = TrackPoint(latitude = 0.0, longitude = 0.0, elevationMeters = null, time = time)

    @Test
    fun lIndexDuRepereEstCeluiDuDernierPointHorodate() {
        val points = listOf(
            point(Instant.parse("2026-07-01T08:00:00Z")),
            point(Instant.parse("2026-07-01T09:00:00Z")),
            point(null), // dernier point du jour sans horodatage propre (report de l'heure connue, TrackTimelineCalculator)
        )
        assertEquals(1, bivouacMarkerLocalIndex(points))
    }

    @Test
    fun lIndexDuRepereEstNullSiAucunPointNAUnHorodatage() {
        val points = listOf(point(null), point(null))
        assertNull(bivouacMarkerLocalIndex(points))
    }

    @Test
    fun lIndexDuRepereEstNullSurUneListeVide() {
        assertNull(bivouacMarkerLocalIndex(emptyList()))
    }

    // --- bivouacBlockCount -------------------------------------------------------------------------

    @Test
    fun aucunJourAfficheNaAucunBloc() {
        assertEquals(0, bivouacBlockCount(0))
    }

    @Test
    fun uneRandoDUnSeulJourAfficheNaAucunBloc() {
        assertEquals(0, bivouacBlockCount(1))
    }

    @Test
    fun uneRandoDeDeuxJoursAffichesAUnBloc() {
        assertEquals(1, bivouacBlockCount(2))
    }

    @Test
    fun uneRandoDeCinqJoursAffichesAQuatreBlocs() {
        assertEquals(4, bivouacBlockCount(5))
        assertTrue(bivouacBlockCount(5) < 5) // jamais un bloc de plus que de nuits réelles
    }
}

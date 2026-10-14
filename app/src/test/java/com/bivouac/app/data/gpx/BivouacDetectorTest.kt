package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// RIC-225 : règle du bivouac (trou d'au moins 4 h touchant la nuit 19 h-5 h, heure locale du lieu).
// Les tests imposent le fuseau par le paramètre zoneFor : ils ne dépendent ni de la bibliothèque de
// fuseaux ni du fuseau de la machine.

class BivouacDetectorTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val toronto = ZoneId.of("America/Toronto")

    /** Instant correspondant à l'heure locale [local] (ISO, sans fuseau) dans [zone]. */
    private fun at(local: String, zone: ZoneId = paris): Instant =
        LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun point(time: Instant?, north: Double = 0.0) =
        TrackPoint(latitude = 45.0 + north * 1e-5, longitude = 6.0, elevationMeters = 1_000.0, time = time)

    private fun gap(from: Instant, to: Instant) = listOf(point(from), point(to))

    private fun detect(points: List<TrackPoint>, zone: ZoneId = paris) = BivouacDetector.detect(points) { zone }

    private fun isBivouac(from: String, to: String, zone: ZoneId = paris): Boolean =
        detect(gap(at(from, zone), at(to, zone)), zone).isNotEmpty()

    // --- La table de la conception ------------------------------------------------------------

    @Test
    fun `20 h 30 a 3 h est un bivouac`() {
        assertTrue(isBivouac("2026-07-14T20:30:00", "2026-07-15T03:00:00"))
    }

    @Test
    fun `21 h a 7 h est un bivouac`() {
        assertTrue(isBivouac("2026-07-14T21:00:00", "2026-07-15T07:00:00"))
    }

    @Test
    fun `17 h a 8 h est un bivouac`() {
        assertTrue(isBivouac("2026-07-14T17:00:00", "2026-07-15T08:00:00"))
    }

    @Test
    fun `11 h a 16 h n'est pas un bivouac, la sieste ne touche pas la nuit`() {
        assertTrue(!isBivouac("2026-07-14T11:00:00", "2026-07-14T16:00:00"))
    }

    @Test
    fun `22 h a 23 h 30 n'est pas un bivouac, le diner dure moins de 4 h`() {
        assertTrue(!isBivouac("2026-07-14T22:00:00", "2026-07-14T23:30:00"))
    }

    // --- Les bornes ---------------------------------------------------------------------------

    @Test
    fun `un trou de 4 h exactement commencant a 19 h 00 est un bivouac`() {
        assertTrue(isBivouac("2026-07-14T19:00:00", "2026-07-14T23:00:00"))
    }

    @Test
    fun `un trou de 3 h 59 commencant a 19 h 00 n'est pas un bivouac`() {
        assertTrue(!isBivouac("2026-07-14T19:00:00", "2026-07-14T22:59:00"))
    }

    @Test
    fun `un trou de 3 h 59 min 59 s n'est pas un bivouac, 4 h pile l'est`() {
        assertTrue(!isBivouac("2026-07-14T19:00:00", "2026-07-14T22:59:59"))
        assertTrue(isBivouac("2026-07-14T19:00:00", "2026-07-14T23:00:00"))
    }

    @Test
    fun `un trou de 5 h 00 a 9 h 00 n'est pas un bivouac, 5 h est exclu`() {
        assertTrue(!isBivouac("2026-07-15T05:00:00", "2026-07-15T09:00:00"))
    }

    @Test
    fun `un trou de 4 h 59 a 9 h 00 est un bivouac`() {
        assertTrue(isBivouac("2026-07-15T04:59:00", "2026-07-15T09:00:00"))
    }

    @Test
    fun `un trou de 15 h 00 a 18 h 59 n'est pas un bivouac, il s'arrete avant 19 h`() {
        assertTrue(!isBivouac("2026-07-14T14:00:00", "2026-07-14T18:59:59"))
    }

    @Test
    fun `un trou de 15 h 00 a 19 h 00 est un bivouac, la reprise a 19 h 00 touche la fenetre fermee`() {
        assertTrue(isBivouac("2026-07-14T15:00:00", "2026-07-14T19:00:00"))
    }

    @Test
    fun `un trou de 14 h ou plus touche toujours la nuit`() {
        // 05:00 a 19:00 : 14 h pile, la reprise tombe sur le debut de fenetre.
        assertTrue(isBivouac("2026-07-14T05:00:00", "2026-07-14T19:00:00"))
        // 05:00 a 18:59:59 : moins de 14 h, entre deux fenetres, pas de bivouac.
        assertTrue(!isBivouac("2026-07-14T05:00:00", "2026-07-14T18:59:59"))
    }

    @Test
    fun `un trou de plusieurs jours est un bivouac`() {
        assertTrue(isBivouac("2026-07-14T10:00:00", "2026-07-18T10:00:00"))
    }

    @Test
    fun `un trou a l'envers n'est pas un bivouac`() {
        assertTrue(!isBivouac("2026-07-15T08:00:00", "2026-07-14T20:00:00"))
    }

    @Test
    fun `la distance entre arret et reprise ne compte pas`() {
        val near = listOf(point(at("2026-07-14T20:00:00"), north = 0.0), point(at("2026-07-15T07:00:00"), north = 0.0))
        val far = listOf(point(at("2026-07-14T20:00:00"), north = 0.0), point(at("2026-07-15T07:00:00"), north = 1_000_000.0))
        assertEquals(1, detect(near).size)
        assertEquals(1, detect(far).size)
    }

    // --- Index, instants et fuseau rendus -----------------------------------------------------

    @Test
    fun `le resultat porte les index, les instants et le fuseau retenu`() {
        val points = listOf(
            point(at("2026-07-14T17:00:00")),
            point(at("2026-07-14T17:30:00")),
            point(at("2026-07-15T08:00:00")),
            point(at("2026-07-15T08:30:00")),
        )
        val found = detect(points).single()
        assertEquals(1, found.stopIndex)
        assertEquals(2, found.resumeIndex)
        assertEquals(at("2026-07-14T17:30:00"), found.stopAt)
        assertEquals(at("2026-07-15T08:00:00"), found.resumeAt)
        assertEquals(paris, found.zone)
    }

    @Test
    fun `un trek de cinq jours en un fichier donne quatre bivouacs aux bons index`() {
        val points = mutableListOf<TrackPoint>()
        for (day in 14..18) {
            // dix points par jour, de 08 h 00 a 17 h 30 toutes les 30 minutes
            for (slot in 0 until 10) {
                val minutes = 8 * 60 + slot * 30
                points += point(at("2026-07-%02dT%02d:%02d:00".format(day, minutes / 60, minutes % 60)), north = points.size * 30.0)
            }
        }
        val found = detect(points)
        assertEquals(listOf(9, 19, 29, 39), found.map { it.stopIndex })
        assertEquals(listOf(10, 20, 30, 40), found.map { it.resumeIndex })
    }

    // --- Points sans heure --------------------------------------------------------------------

    @Test
    fun `un fichier sans aucun horodatage ne donne aucun bivouac`() {
        val points = List(50) { point(null, north = it * 30.0) }
        assertTrue(detect(points).isEmpty())
    }

    @Test
    fun `un point sans heure dans la nuit coupe la chaine, aucun trou n'est mesure a travers lui`() {
        val points = listOf(
            point(at("2026-07-14T17:00:00")),
            point(null),
            point(at("2026-07-15T09:00:00")),
        )
        assertTrue(detect(points).isEmpty())
    }

    @Test
    fun `un point sans heure ne masque pas un trou voisin`() {
        val points = listOf(
            point(null),
            point(at("2026-07-14T20:00:00")),
            point(at("2026-07-15T07:00:00")),
            point(null),
        )
        val found = detect(points).single()
        assertEquals(1, found.stopIndex)
        assertEquals(2, found.resumeIndex)
    }

    @Test
    fun `listes vide et d'un point ne donnent rien`() {
        assertTrue(detect(emptyList()).isEmpty())
        assertTrue(detect(listOf(point(at("2026-07-14T20:00:00")))).isEmpty())
    }

    // --- Changements d'heure ------------------------------------------------------------------

    @Test
    fun `passage a l'heure d'hiver, nuit du 24 au 25 octobre 2026, decision en heure locale reelle`() {
        // 03:30 UTC a 07:30 UTC le 25/10 : 05:30 a 09:30 en heure d'ete (+2), mais 04:30 a 08:30 en
        // heure d'hiver (+1), seule valable apres 01:00 UTC. L'arret est avant 05:00 locale : bivouac.
        val bivouac = gap(Instant.parse("2026-10-25T03:30:00Z"), Instant.parse("2026-10-25T07:30:00Z"))
        assertEquals(1, detect(bivouac).size)
        // 04:00 UTC a 08:00 UTC : 05:00 a 09:00 locale, 05:00 exclu : pas de bivouac.
        val pas = gap(Instant.parse("2026-10-25T04:00:00Z"), Instant.parse("2026-10-25T08:00:00Z"))
        assertTrue(detect(pas).isEmpty())
        // La veille au soir (heure d'ete, +2) : 19:00 locale = 17:00 UTC, trou de 4 h pile.
        val soir = gap(Instant.parse("2026-10-24T17:00:00Z"), Instant.parse("2026-10-24T21:00:00Z"))
        assertEquals(1, detect(soir).size)
        val avant = gap(Instant.parse("2026-10-24T16:59:00Z"), Instant.parse("2026-10-24T20:58:59Z"))
        assertTrue(detect(avant).isEmpty())
    }

    @Test
    fun `passage a l'heure d'ete, nuit du 28 au 29 mars 2026, decision en heure locale reelle`() {
        // 03:00 UTC a 07:00 UTC le 29/03 : 05:00 a 09:00 en heure d'ete (+2), seule valable apres
        // 01:00 UTC (04:00 a 08:00 si l'on gardait l'heure d'hiver). 05:00 exclu : pas de bivouac.
        val pas = gap(Instant.parse("2026-03-29T03:00:00Z"), Instant.parse("2026-03-29T07:00:00Z"))
        assertTrue(detect(pas).isEmpty())
        // Une minute plus tot : l'arret est a 04:59 locale : bivouac.
        val bivouac = gap(Instant.parse("2026-03-29T02:59:00Z"), Instant.parse("2026-03-29T06:59:00Z"))
        assertEquals(1, detect(bivouac).size)
        // La veille au soir (heure d'hiver, +1) : 19:00 locale = 18:00 UTC.
        val soir = gap(Instant.parse("2026-03-28T18:00:00Z"), Instant.parse("2026-03-28T22:00:00Z"))
        assertEquals(1, detect(soir).size)
        val avant = gap(Instant.parse("2026-03-28T17:00:00Z"), Instant.parse("2026-03-28T20:59:59Z"))
        assertTrue(detect(avant).isEmpty())
    }

    // --- Le fuseau compte ---------------------------------------------------------------------

    @Test
    fun `le meme trou change de verdict selon le fuseau`() {
        // 17 h 30 a 21 h 30 UTC un jour d'ete : 19 h 30 a 23 h 30 a Paris (+2), 13 h 30 a 17 h 30 a Toronto (-4).
        val points = gap(Instant.parse("2026-07-14T17:30:00Z"), Instant.parse("2026-07-14T21:30:00Z"))
        assertEquals(1, BivouacDetector.detect(points) { paris }.size)
        assertTrue(BivouacDetector.detect(points) { toronto }.isEmpty())
    }

    @Test
    fun `zoneFor ne recoit que le point d'arret et seulement pour un trou d'au moins 4 h`() {
        val points = listOf(
            point(at("2026-07-14T08:00:00"), north = 0.0),
            point(at("2026-07-14T08:30:00"), north = 1.0),
            point(at("2026-07-15T08:00:00"), north = 2.0),
        )
        val asked = mutableListOf<TrackPoint>()
        BivouacDetector.detect(points) { asked += it; paris }
        assertEquals(listOf(points[1]), asked)
    }

    // --- Le repli de LocalZone ----------------------------------------------------------------

    @Test
    fun `LocalZone rend un fuseau sans lever d'exception, y compris en plein ocean`() {
        assertEquals(ZoneId.systemDefault(), LocalZone.forPoint(point(null)))
        val ocean = TrackPoint(latitude = 0.0, longitude = -30.0, elevationMeters = null, time = null)
        assertEquals(ZoneId.systemDefault(), LocalZone.forPoint(ocean))
    }

    @Test
    fun `constantes de la regle`() {
        assertEquals(14_400L, BivouacDetector.MIN_GAP_SECONDS)
        assertEquals(19, BivouacDetector.NIGHT_START_HOUR)
        assertEquals(5, BivouacDetector.NIGHT_END_HOUR)
    }
}

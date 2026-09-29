package com.bivouac.app.ui.journal

import com.bivouac.app.data.gpx.ReferenceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RIC-146 lot 5 (brief Partie A.4), adapté lot 7 : géométrie de la barre d'écart centrée, réutilisée
 * par la section « Forme du jour » (résumé par nature de terrain, conception 2 section 4) : sens
 * (gauche = plus lent, droite = plus rapide), plafond à 50 % de la piste, classe neutre au milieu.
 * Fonction pure, prend directement les vitesses plutôt qu'un [com.bivouac.app.data.gpx.AnalysisBand]
 * depuis que le tableau des onze bandes de pente (seul appelant de l'ancienne signature) a disparu.
 */
class PaceBarGeometryTest {

    @Test
    fun plusLentPartVersLaGauche() {
        // 3 km/h contre une référence à 4 km/h : -25 %.
        val geometry = paceBarGeometryFor(hikeSpeedKmh = 3.0, referenceSpeedKmh = 4.0, referenceSource = ReferenceSource.REFERENCE)
        assertEquals(true, geometry.slower)
        assertEquals(0.5f, geometry.halfFraction, 0.001f)
    }

    @Test
    fun plusRapideVaVersLaDroite() {
        // 5 km/h contre une référence à 4 km/h : +25 %.
        val geometry = paceBarGeometryFor(hikeSpeedKmh = 5.0, referenceSpeedKmh = 4.0, referenceSource = ReferenceSource.REFERENCE)
        assertEquals(false, geometry.slower)
        assertEquals(0.5f, geometry.halfFraction, 0.001f)
    }

    @Test
    fun laLongueurEstPlafonneeA50PourcentDeLaPiste() {
        // Écart énorme (rando dix fois plus rapide) : la moitié de piste ne déborde jamais.
        val geometry = paceBarGeometryFor(hikeSpeedKmh = 40.0, referenceSpeedKmh = 4.0, referenceSource = ReferenceSource.REFERENCE)
        assertEquals(1f, geometry.halfFraction, 0.001f)
    }

    @Test
    fun memeVitesseQueLaReferenceEstSansBarreEtSansSens() {
        val geometry = paceBarGeometryFor(hikeSpeedKmh = 4.0, referenceSpeedKmh = 4.0, referenceSource = ReferenceSource.REFERENCE)
        assertEquals(0f, geometry.halfFraction, 0.001f)
        // Pas d'écart : ni plus lent ni plus rapide, mais slower reste connu (false) puisque
        // l'écart (0.0) est exploitable ; seule la barre elle-même est de longueur nulle.
        assertEquals(false, geometry.slower)
    }

    @Test
    fun laClasseDuMilieuEstNeutre() {
        // Écart faible, à l'intérieur des bornes de la classe 2 (voir AnalysisParameters.paceClassBoundsPercent).
        val geometry = paceBarGeometryFor(hikeSpeedKmh = 4.0, referenceSpeedKmh = 4.0, referenceSource = ReferenceSource.REFERENCE)
        assertEquals(2, geometry.paceClass)
    }

    @Test
    fun sansReferenceLaBarreEstAbsenteEtLaClasseInconnue() {
        val geometry = paceBarGeometryFor(hikeSpeedKmh = 3.0, referenceSpeedKmh = null, referenceSource = ReferenceSource.NONE)
        assertEquals(0f, geometry.halfFraction, 0.001f)
        assertNull(geometry.slower)
        assertNull(geometry.paceClass)
    }

    @Test
    fun referenceSourceNoneEcarteMemeAvecUneVitesseDeReferenceNonNulle() {
        // Repli du 5.4 (conception) : la bande a emprunté referenceSpeedKmh à la rando elle-même
        // mais referenceSource reste NONE tant qu'aucune vraie référence n'existe.
        val geometry = paceBarGeometryFor(hikeSpeedKmh = 3.0, referenceSpeedKmh = 3.5, referenceSource = ReferenceSource.NONE)
        assertEquals(0f, geometry.halfFraction, 0.001f)
        assertNull(geometry.paceClass)
    }
}

package com.bivouac.app.ui.map

import com.bivouac.app.data.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-174 : reproduction et correction du gel du glissement de repositionnement remonté en
 * recette sur S22 (« le marqueur bouge de quelques pixels puis se fige jusqu'au relâchement »).
 *
 * Hypothèse du pilotage, CONFIRMÉE en isolant [PhotoPlacementDragController.onDrag] : avant ce
 * correctif, `photoPlacementMarker` appelait `onDragged` à chaque frame de toucher brut (voir
 * l'historique git de HikeMapView.kt), pas seulement quand l'index aimanté changeait. Chaque appel
 * écrit dans le brouillon d'édition, ce qui recompose HikeMapView et relance `renderTrack`, qui
 * vide `mapView.overlays` et reconstruit tous les marqueurs : l'instance de Marker tenue par le
 * doigt disparaît, et plus rien ne suit le geste jusqu'au prochain ACTION_DOWN. Ce fichier ne peut
 * pas rejouer un vrai geste tactile osmdroid (voir PlanificationScreenModeTest : la composition
 * d'un MapView déclenche de vrais téléchargements de tuile, hors de portée d'un test JVM), donc la
 * reproduction porte sur la garde extraite du listener, comme demandé au minimum par le ticket :
 * [FakeMapDragState.isDragging] doit rester haut pendant tout le glissement (ce qui empêche
 * `renderTrack` de reconstruire, voir HikeMapView.renderTrack) et [onDragged] ne doit être notifié
 * qu'aux changements d'index réels, pas à chaque frame brute.
 */
class PhotoPlacementDragControllerTest {

    // Cinq points alignés, distants d'un cran de latitude chacun : assez pour distinguer
    // plusieurs index aimantés sans complexité géographique inutile.
    private val points = (0 until 5).map { i -> TrackPoint(45.0 + i * 0.01, 6.0, 1000.0, time = null) }

    private class FakeMapDragState : MapDragStateHandle {
        override var isDragging: Boolean = false
    }

    @Test
    fun leGlissementLeveLeDrapeauDesLeDebut() {
        val dragState = FakeMapDragState()
        val controller = PhotoPlacementDragController(points, dragState) {}

        assertFalse("rien ne doit être en cours avant le premier toucher", dragState.isDragging)
        controller.onDragStart()

        assertTrue(
            "renderTrack doit sauter la reconstruction des overlays dès ACTION_DOWN, sinon la " +
                "toute première frame de mouvement détruit déjà le Marker sous le doigt",
            dragState.isDragging,
        )
    }

    @Test
    fun leRelachementAbaisseLeDrapeau() {
        val dragState = FakeMapDragState()
        val controller = PhotoPlacementDragController(points, dragState) {}
        controller.onDragStart()

        controller.onDragEnd(points[2].latitude, points[2].longitude)

        assertFalse(
            "une fois le doigt levé, renderTrack doit pouvoir reconstruire la carte normalement",
            dragState.isDragging,
        )
    }

    /**
     * Le cœur de la cause remontée en recette : deux frames de toucher brut qui s'aimantent au
     * MÊME point de trace ne doivent déclencher qu'UNE seule écriture dans le brouillon, pas une
     * par frame. Avant ce correctif, chaque frame appelait onDragged sans condition.
     */
    @Test
    fun deuxFramesSurLeMemeIndexAimanteNeNotifientQuUneFois() {
        val emitted = mutableListOf<Int>()
        val controller = PhotoPlacementDragController(points, FakeMapDragState()) { emitted.add(it) }
        controller.onDragStart()

        // Deux positions brutes distinctes mais toutes deux plus proches du point d'index 1 que de
        // tout autre : c'est exactement le cas d'un doigt qui tremble légèrement sur place.
        controller.onDrag(points[1].latitude, points[1].longitude)
        controller.onDrag(points[1].latitude + 0.0001, points[1].longitude)

        assertEquals(
            "deux frames aimantées au même point ne doivent produire qu'une seule notification",
            listOf(1),
            emitted,
        )
    }

    @Test
    fun chaqueChangementDIndexAimanteNotifie() {
        val emitted = mutableListOf<Int>()
        val controller = PhotoPlacementDragController(points, FakeMapDragState()) { emitted.add(it) }
        controller.onDragStart()

        controller.onDrag(points[0].latitude, points[0].longitude)
        controller.onDrag(points[2].latitude, points[2].longitude)
        controller.onDrag(points[4].latitude, points[4].longitude)

        assertEquals(listOf(0, 2, 4), emitted)
    }

    /**
     * Le profil altimétrique doit continuer de suivre le doigt à chaque position aimantée pendant
     * le glissement, pas seulement au relâchement : voir JournalScreen.photoPlacementPreviewIndex.
     */
    @Test
    fun onDragRendLIndexAimantePourQueLeMarqueurSuiveLeDoigt() {
        val controller = PhotoPlacementDragController(points, FakeMapDragState()) {}

        val nearestIndex = controller.onDrag(points[3].latitude, points[3].longitude)

        assertEquals(3, nearestIndex)
    }

    /**
     * Le relâchement réémet la valeur finale MÊME si la dernière frame de onDrag l'avait déjà
     * notifiée : un point de mise à jour déterministe après la rafale de callbacks de mouvement,
     * comme cursorMarker.onMarkerDragEnd juste au-dessus dans HikeMapView.kt.
     */
    @Test
    fun leRelachementReemetLaValeurFinaleMemeSansChangement() {
        val emitted = mutableListOf<Int>()
        val controller = PhotoPlacementDragController(points, FakeMapDragState()) { emitted.add(it) }
        controller.onDragStart()
        controller.onDrag(points[2].latitude, points[2].longitude)

        controller.onDragEnd(points[2].latitude, points[2].longitude)

        assertEquals(
            "le relâchement doit rester un point de mise à jour déterministe, même sans nouvel " +
                "index, sinon la carte peut rester périmée après un tout petit glissement",
            listOf(2, 2),
            emitted,
        )
    }

    @Test
    fun unNouveauGlissementRepartDUnEtatVierge() {
        val emitted = mutableListOf<Int>()
        val dragState = FakeMapDragState()
        val controller = PhotoPlacementDragController(points, dragState) { emitted.add(it) }
        controller.onDragStart()
        controller.onDrag(points[1].latitude, points[1].longitude)
        controller.onDragEnd(points[1].latitude, points[1].longitude)
        emitted.clear()

        // Un second Marker (donc un second contrôleur, voir photoPlacementMarker) est reconstruit
        // au prochain renderTrack : ce test documente que rejouer la séquence sur un contrôleur
        // NEUF se comporte pareil, sans état résiduel d'un glissement précédent.
        val secondController = PhotoPlacementDragController(points, dragState) { emitted.add(it) }
        secondController.onDragStart()
        secondController.onDrag(points[1].latitude, points[1].longitude)

        assertEquals(
            "même index qu'avant, mais un contrôleur neuf : doit notifier à nouveau",
            listOf(1),
            emitted,
        )
    }
}

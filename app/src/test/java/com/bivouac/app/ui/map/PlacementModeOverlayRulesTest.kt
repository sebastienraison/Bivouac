package com.bivouac.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-185 : reproduction et correction de la recette S22 du 17/09 (« le marqueur à déplacer a un
 * halo élargi mais sa zone de saisie reste celle du pin, et les autres marqueurs/le tap de trace
 * prennent le toucher à sa place »).
 *
 * renderTrack ne peut pas se tester directement en JVM pur : assembler un vrai MapView osmdroid
 * déclenche de vrais téléchargements de tuile au premier rendu (voir le commentaire en tête de
 * PhotoPlacementDragControllerTest, et PlanificationScreenModeTest cité là-bas). Ce fichier vérifie
 * donc [placementModeOverlayRules], la décision extraite dont renderTrack se contente de lire les
 * champs : que le mode placement (photoPlacementTarget non nul) coupe bien le tap de trace, les
 * clics photo/cluster, le glissement du curseur et la réouverture de sa bulle, et que rien ne
 * change hors de ce mode.
 *
 * Ne couvre PAS le fait que le marqueur de placement est ajouté EN DERNIER dans renderTrack (c'est
 * structurel, un seul point d'ajout tout en bas de la fonction, vérifié par lecture du code) ni
 * l'ordre des overlays qui ne dépendent pas du mode (polylines, extrémités, flèches). Voir le
 * rapport du ticket pour ce qui reste vérifié par lecture seule.
 */
class PlacementModeOverlayRulesTest {

    @Test
    fun horsModePlacementRienNeChangeQuelQueSoitBivouacsReadOnly() {
        val bivouacsModifiables = placementModeOverlayRules(placementActive = false, bivouacsReadOnly = false)
        assertTrue(bivouacsModifiables.trackTapOverlayEnabled)
        assertTrue(bivouacsModifiables.photoMarkersInteractive)
        assertTrue(bivouacsModifiables.bivouacMarkersDraggable)
        assertTrue(bivouacsModifiables.cursorDraggable)
        assertTrue(bivouacsModifiables.cursorBubbleReopens)

        val bivouacsEnLectureSeule = placementModeOverlayRules(placementActive = false, bivouacsReadOnly = true)
        assertTrue(bivouacsEnLectureSeule.trackTapOverlayEnabled)
        assertTrue(bivouacsEnLectureSeule.photoMarkersInteractive)
        assertFalse(
            "bivouacsReadOnly doit rester le seul facteur hors mode placement",
            bivouacsEnLectureSeule.bivouacMarkersDraggable,
        )
        assertTrue(bivouacsEnLectureSeule.cursorDraggable)
        assertTrue(bivouacsEnLectureSeule.cursorBubbleReopens)
    }

    /**
     * Le coeur du correctif : en mode placement, TOUT devient inerte sauf le marqueur de placement
     * lui-même (qui ne passe pas par ces règles, il est toujours actif). bivouacMarkersDraggable
     * reste false même si bivouacsReadOnly = false : le mode placement ne doit pas dépendre d'un
     * réglage indépendant pour désarmer les bivouacs (voir le commentaire RIC-185 sur bivouacMarker
     * dans HikeMapView.renderTrack : l'écran Journal pose déjà bivouacsReadOnly = true partout où le
     * placement est possible, mais cette règle ne doit pas en dépendre pour rester vraie).
     */
    @Test
    fun enModePlacementToutDevientInerteMemeAvecDesBivouacsModifiables() {
        val rules = placementModeOverlayRules(placementActive = true, bivouacsReadOnly = false)

        assertFalse("le tap de trace doit disparaître", rules.trackTapOverlayEnabled)
        assertFalse("les photos/clusters ne doivent plus rien faire au clic", rules.photoMarkersInteractive)
        assertFalse(
            "les bivouacs ne doivent pas pouvoir être glissés pendant le placement, quel que soit bivouacsReadOnly",
            rules.bivouacMarkersDraggable,
        )
        assertFalse("le curseur ne doit plus pouvoir être saisi", rules.cursorDraggable)
        assertFalse("la bulle du curseur ne doit pas se rouvrir", rules.cursorBubbleReopens)
    }

    @Test
    fun enModePlacementAvecBivouacsDejaEnLectureSeuleRienNeBouge() {
        val rules = placementModeOverlayRules(placementActive = true, bivouacsReadOnly = true)

        assertEquals(
            placementModeOverlayRules(placementActive = true, bivouacsReadOnly = false),
            rules,
        )
    }
}

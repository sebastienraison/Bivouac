package com.bivouac.app.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-185 : recette S22 du 17/09, symptôme n°1 (« le marqueur à déplacer a un halo élargi dessiné,
 * mais sa zone de saisie reste celle de l'icône du pin : un toucher dans le cercle mais hors du pin
 * le rate »).
 *
 * [isWithinPlacementHalo] est la partie pure de [PhotoPlacementHitMarker.hitTest] (extraite pour
 * les mêmes raisons que PhotoPlacementDragController : monter un vrai MapView osmdroid télécharge
 * de vraies tuiles, hors de portée d'un test JVM). `Marker.hitTest` par défaut, appelé d'abord par
 * `PhotoPlacementHitMarker.hitTest` via `super.hitTest`, teste `mOrientedMarkerRect.contains(x, y)`
 * (vérifié dans le bytecode de Marker.class, osmdroid 6.1.20) : un point strictement dans ce petit
 * rectangle du pin renverrait déjà true là-bas, avant même d'atteindre cette fonction. Elle n'est
 * donc appelée, en usage réel, que pour des touchers HORS du pin, ce qui correspond exactement au
 * cas "hors du pin (vrai)" du ticket : n'importe quel point dans le rayon du halo doit y répondre
 * vrai, qu'il soit dans le petit rectangle du pin ou non n'a plus d'importance pour cette fonction.
 */
class PlacementHaloHitTestTest {

    private val centerX = 500
    private val centerY = 800
    private val haloRadiusPx = 90f

    @Test
    fun unToucherDansLeHaloHorsDuPinRepondVrai() {
        // Décalage de 60 px du centre : bien au-delà d'un pin typique (quelques dizaines de px de
        // large), mais toujours sous le rayon du halo (90 px) : c'est exactement le point mort du
        // bug remonté en recette.
        assertTrue(
            isWithinPlacementHalo(
                touchX = centerX + 60f, touchY = centerY.toFloat(),
                centerX = centerX, centerY = centerY, haloRadiusPx = haloRadiusPx,
            ),
        )
    }

    @Test
    fun unToucherHorsDuHaloRepondFaux() {
        assertFalse(
            isWithinPlacementHalo(
                touchX = centerX + 150f, touchY = centerY.toFloat(),
                centerX = centerX, centerY = centerY, haloRadiusPx = haloRadiusPx,
            ),
        )
    }

    /** La distance est euclidienne (Pythagore), pas seulement horizontale ou verticale. */
    @Test
    fun laDistanceSeMesureEnDiagonale() {
        // 70 px horizontaux + 70 px verticaux : chaque composante seule serait sous le rayon (90),
        // mais leur diagonale (~99 px) le dépasse.
        assertFalse(
            isWithinPlacementHalo(
                touchX = centerX + 70f, touchY = centerY + 70f,
                centerX = centerX, centerY = centerY, haloRadiusPx = haloRadiusPx,
            ),
        )
        // Même déplacement diagonal mais sous le rayon (50+50, diagonale ~71 px).
        assertTrue(
            isWithinPlacementHalo(
                touchX = centerX + 50f, touchY = centerY + 50f,
                centerX = centerX, centerY = centerY, haloRadiusPx = haloRadiusPx,
            ),
        )
    }

    /** Un toucher exactement sur le centre du marqueur doit évidemment répondre vrai. */
    @Test
    fun unToucherAuCentreRepondVrai() {
        assertTrue(
            isWithinPlacementHalo(
                touchX = centerX.toFloat(), touchY = centerY.toFloat(),
                centerX = centerX, centerY = centerY, haloRadiusPx = haloRadiusPx,
            ),
        )
    }

    /** La bordure du halo (rayon exact) est incluse : cohérent avec CursorDragMarker.hitTest. */
    @Test
    fun laBordureExacteDuHaloRepondVrai() {
        assertTrue(
            isWithinPlacementHalo(
                touchX = centerX + haloRadiusPx, touchY = centerY.toFloat(),
                centerX = centerX, centerY = centerY, haloRadiusPx = haloRadiusPx,
            ),
        )
    }
}

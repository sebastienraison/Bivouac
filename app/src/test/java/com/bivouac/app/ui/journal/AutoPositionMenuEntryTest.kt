package com.bivouac.app.ui.journal

import com.bivouac.app.data.photo.PhotoPositionCorrelator.Position
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-178 : le menu Position, option C décidée par Seb. Une seule entrée « retour à la position
 * automatique », dont le libellé/l'absence/le grisage suivent [PhotoPositionCorrelator.correlate]
 * rejoué sur les métadonnées d'origine de la photo. Voir PhotoViewerDialog.autoPositionMenuEntry,
 * fonction pure testée ici comme [PhotoViewerRemovalIndexTest] et [PhotoCropGeometryTest] : pas de
 * Compose ni de Robolectric nécessaire.
 */
class AutoPositionMenuEntryTest {

    // --- Les quatre cas de la spec -------------------------------------------------------------

    @Test
    fun gpsCertainEtPasEncoreLa_libelleGpsActive() {
        val entry = autoPositionMenuEntry(
            correlated = Position(pointIndex = 5, approximate = false),
            currentPointIndex = 2,
            currentApproximate = false,
        )

        assertEquals(AutoPositionMenuEntry.GPS_ENABLED, entry)
    }

    @Test
    fun horodatageApproximatifEtPasEncoreLa_libelleHeureActive() {
        val entry = autoPositionMenuEntry(
            correlated = Position(pointIndex = 5, approximate = true),
            currentPointIndex = 2,
            currentApproximate = false,
        )

        assertEquals(AutoPositionMenuEntry.TIME_ENABLED, entry)
    }

    @Test
    fun aucunePositionTrouvee_entreeAbsente() {
        val entry = autoPositionMenuEntry(
            correlated = Position.NONE,
            currentPointIndex = 2,
            currentApproximate = false,
        )

        assertEquals(AutoPositionMenuEntry.HIDDEN, entry)
    }

    @Test
    fun dejaALaPositionGpsAvecLeMemeMarquage_entreeGrisee() {
        val entry = autoPositionMenuEntry(
            correlated = Position(pointIndex = 5, approximate = false),
            currentPointIndex = 5,
            currentApproximate = false,
        )

        assertEquals(AutoPositionMenuEntry.GPS_ALREADY_THERE, entry)
    }

    // --- Le marquage compte autant que l'index --------------------------------------------------

    /**
     * Même index, mais la photo est actuellement CERTAINE (placement/repositionnement manuel) et
     * la routine rejouée rendrait une position APPROXIMATIVE (horodatage) : ce n'est PAS un no-op,
     * la pastille d'approximation doit pouvoir apparaître si l'utilisateur choisit quand même cette
     * entrée, donc elle reste active.
     */
    @Test
    fun memeIndexMaisMarquageDifferent_resteActive() {
        val entry = autoPositionMenuEntry(
            correlated = Position(pointIndex = 5, approximate = true),
            currentPointIndex = 5,
            currentApproximate = false,
        )

        assertEquals(AutoPositionMenuEntry.TIME_ENABLED, entry)
    }

    @Test
    fun dejaALaPositionParHorodatageAvecLeMemeMarquage_entreeGrisee() {
        val entry = autoPositionMenuEntry(
            correlated = Position(pointIndex = 5, approximate = true),
            currentPointIndex = 5,
            currentApproximate = true,
        )

        assertEquals(AutoPositionMenuEntry.TIME_ALREADY_THERE, entry)
    }

    @Test
    fun aucunePositionActuelle_nEstJamaisConfonduAvecDejaLa() {
        val entry = autoPositionMenuEntry(
            correlated = Position(pointIndex = 0, approximate = false),
            currentPointIndex = null,
            currentApproximate = false,
        )

        assertEquals(AutoPositionMenuEntry.GPS_ENABLED, entry)
    }
}

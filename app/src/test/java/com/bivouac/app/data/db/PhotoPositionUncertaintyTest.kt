package com.bivouac.app.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-43 : la règle d'affichage de la pastille « positionnement approximatif », telle que la revue
 * de conception l'a arrêtée : un seul des quatre chemins de positionnement la mérite.
 *
 * Test sur l'entité seule, sans base ni Android : positionUncertain est une lecture de deux
 * colonnes, et c'est justement cette lecture qui doit rester stable, quel que soit le chemin qui a
 * écrit les colonnes.
 */
class PhotoPositionUncertaintyTest {

    private fun photo(
        positionApproximate: Boolean,
        takenAtZoneCertain: Boolean?,
        positionPointIndex: Int? = 12,
    ) = LoggedTrackPhotoEntity(
        trackId = "trace",
        filePath = "photos/trace-x.jpg",
        addedAtMillis = 0L,
        positionPointIndex = positionPointIndex,
        positionApproximate = positionApproximate,
        takenAtZoneCertain = takenAtZoneCertain,
    )

    // Cas 1 : position venue du GPS de la photo. Le corrélateur ne la marque jamais approximative,
    // et la borne de distance garantit désormais qu'elle est bien sur la trace.
    @Test
    fun gpsPosition_isNeverUncertain() {
        assertFalse(photo(positionApproximate = false, takenAtZoneCertain = false).positionUncertain)
        assertFalse(photo(positionApproximate = false, takenAtZoneCertain = true).positionUncertain)
        assertFalse(photo(positionApproximate = false, takenAtZoneCertain = null).positionUncertain)
    }

    // Cas 2 : corrélation temporelle, mais le fichier portait son fuseau (tag d'offset EXIF, ou
    // horodatage GPS en UTC). L'horodatage est comparable à celui de la trace sans hypothèse : le
    // WIP mettait quand même une pastille, ce n'est plus le cas.
    @Test
    fun timeCorrelationWithAKnownZone_isNotUncertain() {
        assertFalse(photo(positionApproximate = true, takenAtZoneCertain = true).positionUncertain)
    }

    // Cas 3 : le seul qui mérite la pastille. Corrélation temporelle sur un horodatage dont le
    // fuseau est supposé être celui du téléphone au moment de l'ajout : hypothèse fausse dès que
    // la photo vient d'un voyage lointain, et rien dans le fichier ne permet de le voir.
    @Test
    fun timeCorrelationWithAnAssumedZone_isUncertain() {
        assertTrue(photo(positionApproximate = true, takenAtZoneCertain = false).positionUncertain)
    }

    // Cas 4 : placement ou repositionnement manuel. LoggedTrackRepository.repositionPhoto remet
    // positionApproximate à false, l'utilisateur ayant confirmé de sa main.
    @Test
    fun manualPlacement_isNotUncertain() {
        assertFalse(photo(positionApproximate = false, takenAtZoneCertain = false).positionUncertain)
    }

    // Lignes d'avant la migration 15 -> 16 : le fuseau n'était pas relevé, la colonne vaut null.
    // « Inconnu » se comporte comme « pas certain », soit exactement l'affichage qu'elles avaient
    // avant la migration : jamais une position qu'on présenterait comme plus sûre qu'elle ne l'est.
    @Test
    fun legacyRowWithoutTheZoneColumn_staysUncertainWhenItWasTimeCorrelated() {
        assertTrue(photo(positionApproximate = true, takenAtZoneCertain = null).positionUncertain)
    }
}

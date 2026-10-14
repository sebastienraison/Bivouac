package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import java.time.ZoneId

/**
 * RIC-225 : fuseau horaire du lieu d'un point, utilisé par [BivouacDetector] pour lire la fenêtre
 * de nuit en heure locale.
 *
 * État actuel : repli seul, [ZoneId.systemDefault]. La déduction du fuseau par les coordonnées
 * (bibliothèque drtimcooper/LatLongToTimezone, MIT) n'est pas embarquée : son fichier source pèse
 * 1,7 Mo, hors de l'ordre de grandeur fixé par le cadrage du lot 1. La décision revient au pilotage.
 * Le jour où une bibliothèque est retenue, elle s'ajoute ici et nulle part ailleurs : le détecteur
 * ne connaît que cette fonction, et ses tests imposent leur fuseau par le paramètre `zoneFor`.
 */
object LocalZone {

    /** Fuseau du lieu du [point]. Ne lève jamais d'exception. */
    @Suppress("UNUSED_PARAMETER")
    fun forPoint(point: TrackPoint): ZoneId = ZoneId.systemDefault()
}

package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.thirdparty.timezonemapper.TimezoneMapper
import java.time.DateTimeException
import java.time.ZoneId

/**
 * RIC-225 : fuseau horaire du lieu d'un point, utilisé par [BivouacDetector] pour lire la fenêtre
 * de nuit en heure locale.
 *
 * Le fuseau est déduit des coordonnées par la bibliothèque LatLongToTimezone (MIT, embarquée sous
 * `thirdparty/timezonemapper`, fichier unique avec les polygones en dur : ni réseau ni fichier de
 * données). L'heure d'été est ensuite gérée par `java.time`.
 *
 * Repli sur [ZoneId.systemDefault] quand la bibliothèque ne rend rien (null ou chaîne vide), rend
 * l'identifiant "unknown" ou un identifiant que `java.time` ne connaît pas, ou lève une exception :
 * jamais d'exception vers l'appelant.
 */
object LocalZone {

    /** Fuseau du lieu du [point]. Ne lève jamais d'exception. */
    fun forPoint(point: TrackPoint): ZoneId = forCoordinates(point.latitude, point.longitude)

    internal fun forCoordinates(latitude: Double, longitude: Double): ZoneId {
        val id = try {
            TimezoneMapper.latLngToTimezoneString(latitude, longitude)
        } catch (e: RuntimeException) {
            null
        }
        if (id.isNullOrEmpty()) return ZoneId.systemDefault()
        return try {
            ZoneId.of(id)
        } catch (e: DateTimeException) {
            ZoneId.systemDefault()
        }
    }
}

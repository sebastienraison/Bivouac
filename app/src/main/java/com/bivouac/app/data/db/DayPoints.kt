package com.bivouac.app.data.db

import android.content.Context
import android.util.Log
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.model.TrackPoint
import java.nio.charset.StandardCharsets

/**
 * RIC-226 (lot 2 de la 2.7.0, conception RIC-222 §4) : la seule façon de lire les points d'un jour
 * du Journal. Un jour est soit un fichier GPX entier (plage nulle, toutes les lignes d'avant la
 * migration 21 vers 22), soit une plage `[firstPointIndex, lastPointIndex]` de ce fichier, bornes
 * incluses, en index de la liste rendue par [GpxParser.parse] (voir [LoggedTrackDayEntity]).
 *
 * Tout lecteur passe par [slice], directement ou par [parse] et [read] : c'est ce qui garantit que
 * la plage est appliquée partout de la même façon, et qu'aucun écran ni aucun rattrapage ne lit
 * par erreur le fichier entier d'un jour découpé.
 *
 * Une plage incohérente (une seule borne renseignée, borne négative, inversée, ou au-delà du
 * dernier point du fichier) est une erreur de données, pas une erreur de l'appelant : elle est
 * journalisée et le jour rend une liste vide, jamais une exception. Un fichier absent ou un GPX
 * non parsable, en revanche, lève comme avant ce ticket ([read], [parse]) : chaque appelant a déjà
 * son propre traitement de ces cas (rattrapage marqué traité, calibration ignorée, écran d'erreur).
 */
object DayPoints {

    private const val TAG = "DayPoints"

    /** Les points de [day] pris dans [filePoints], tous les points de son fichier parsé. */
    fun slice(filePoints: List<TrackPoint>, day: LoggedTrackDayEntity): List<TrackPoint> {
        val first = day.firstPointIndex
        val last = day.lastPointIndex
        if (first == null && last == null) return filePoints
        if (first == null || last == null || first < 0 || last < first || last > filePoints.lastIndex) {
            Log.w(TAG, "Jour ${day.id} (${day.trackId}) : plage [$first, $last] invalide pour ${filePoints.size} point(s), aucun point rendu")
            return emptyList()
        }
        // Copie et non vue (subList) : la liste rendue vit plus longtemps que l'appel, et rien ne
        // doit pouvoir la relier à celle d'un autre jour du même fichier.
        return filePoints.subList(first, last + 1).toList()
    }

    /** Les points de [day], depuis le contenu brut déjà lu de son fichier. Lève si le GPX est invalide. */
    fun parse(rawGpx: String, day: LoggedTrackDayEntity): List<TrackPoint> =
        slice(parseAll(rawGpx), day)

    /** Les points de [day], depuis son fichier. Lève si le fichier manque ou si le GPX est invalide. */
    fun read(context: Context, day: LoggedTrackDayEntity): List<TrackPoint> =
        slice(readFile(context, day.rawGpxFilePath), day)

    /**
     * Les points de chacun de [days], dans l'ordre : même chose que [read] jour par jour, mais un
     * fichier partagé par plusieurs jours (rando découpée) n'est lu et parsé qu'une fois.
     */
    fun readAll(context: Context, days: List<LoggedTrackDayEntity>): List<List<TrackPoint>> {
        val parsedByPath = mutableMapOf<String, List<TrackPoint>>()
        return days.map { day ->
            slice(parsedByPath.getOrPut(day.rawGpxFilePath) { readFile(context, day.rawGpxFilePath) }, day)
        }
    }

    private fun readFile(context: Context, relativePath: String): List<TrackPoint> =
        LoggedTrackGpxStore.resolve(context, relativePath).inputStream().use { GpxParser.parse(it) }.points

    private fun parseAll(rawGpx: String): List<TrackPoint> =
        rawGpx.byteInputStream(StandardCharsets.UTF_8).use { GpxParser.parse(it) }.points
}

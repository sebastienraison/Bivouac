package com.bivouac.app.data.gpx

import android.text.Html
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.TrackPoint
import io.jenetics.jpx.GPX
import io.jenetics.jpx.Length
import java.io.IOException
import java.io.InputStream
import java.util.Optional
import java.util.stream.Collectors

private fun <T> Optional<T>.orNull(): T? = orElse(null)

// GPX creators occasionally leave HTML/XML entities (e.g. "d&amp;apos;En", "&eacute;") un-decoded
// in text fields when they aren't strictly valid XML entities that a lenient reader resolves.
private fun decodeEntities(text: String): String =
    Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()

/**
 * Parses a GPX file into a flat, ordered [HikeTrack].
 *
 * All tracks and segments found in the file are concatenated in document order into a single
 * point list. Day boundaries are not derived from the GPX structure itself — they come later
 * from where the user places bivouac points along this track.
 */
object GpxParser {

    @Throws(IOException::class)
    fun parse(input: InputStream): HikeTrack {
        val gpx = GPX.Reader.of(GPX.Reader.Mode.LENIENT).read(input)

        val points = gpx.tracks()
            .flatMap { it.segments() }
            .flatMap { it.points() }
            .map { wayPoint ->
                TrackPoint(
                    latitude = wayPoint.latitude.toDegrees(),
                    longitude = wayPoint.longitude.toDegrees(),
                    elevationMeters = wayPoint.elevation.orNull()?.to(Length.Unit.METER),
                    time = wayPoint.time.orNull(),
                )
            }
            .collect(Collectors.toList())

        if (points.isEmpty()) {
            throw IOException("Aucun point de trace trouvé dans ce fichier GPX")
        }

        val name = gpx.tracks().findFirst().orNull()?.name?.orNull()?.let(::decodeEntities)

        return HikeTrack(name = name, points = points)
    }
}

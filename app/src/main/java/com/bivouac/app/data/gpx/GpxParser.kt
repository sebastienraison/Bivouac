package com.bivouac.app.data.gpx

import android.text.Html
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.TrackPoint
import io.jenetics.jpx.GPX
import io.jenetics.jpx.Length
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Optional
import java.util.stream.Collectors

private fun <T> Optional<T>.orNull(): T? = orElse(null)

// GPX creators occasionally leave HTML/XML entities (e.g. "d&amp;apos;En", "&eacute;") un-decoded
// in text fields when they aren't strictly valid XML entities that a lenient reader resolves.
private fun decodeEntities(text: String): String =
    Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()

// jpx's lenient reader skips vendor <extensions> blocks (Garmin heart rate/cadence/temperature,
// etc.) via an internal javax.xml.transform.stax.StAXSource copy: a class Android's JDK subset
// doesn't provide, so any real-world GPX with such extensions crashes with a NoClassDefFoundError
// instead of being skipped. None of that data is used here, so the blocks are stripped from the
// raw XML before jpx ever sees them.
//
// Hand-rolled linear scan rather than a regex (RIC-95): the previous
// `<extensions\b.*?</extensions>` with DOT_MATCHES_ALL was quadratic in the worst case on forged
// input (many opening tags, no closer), an easy CPU burn from an untrusted stream. Same semantics:
// a block runs to the first closing tag; an opening tag with no closer anywhere is left verbatim.
// `internal` for direct JVM unit-testing (GpxParser.parse needs android.text.Html).
internal fun stripExtensionsBlocks(xml: String): String {
    val openTag = "<extensions"
    val closeTag = "</extensions>"
    val out = StringBuilder(xml.length)
    var i = 0
    // Une fois constaté qu'aucun closeTag n'existe plus loin, inutile de re-balayer la fin du
    // document pour chaque ouverture suivante : c'est ce re-balayage qui rendait le pire cas
    // quadratique.
    var closerMayExist = true
    while (true) {
        val start = xml.indexOf(openTag, i)
        if (start < 0) break
        // "<extensionsXyz" is some other element: require a name boundary right after.
        val boundary = xml.getOrNull(start + openTag.length)
        val isExtensionsTag = boundary == '>' || boundary == '/' || boundary?.isWhitespace() == true
        if (!isExtensionsTag) {
            out.append(xml, i, start + openTag.length)
            i = start + openTag.length
            continue
        }
        val tagEnd = xml.indexOf('>', start)
        if (tagEnd < 0) break
        if (xml[tagEnd - 1] == '/') { // self-closing <extensions/> (attributes or not)
            out.append(xml, i, start)
            i = tagEnd + 1
            continue
        }
        val closerAt = if (closerMayExist) xml.indexOf(closeTag, tagEnd + 1) else -1
        if (closerAt < 0) {
            closerMayExist = false
            out.append(xml, i, tagEnd + 1)
            i = tagEnd + 1
            continue
        }
        out.append(xml, i, start)
        i = closerAt + closeTag.length
    }
    out.append(xml, i, xml.length)
    return out.toString()
}

/**
 * Parses a GPX file into a flat, ordered [HikeTrack].
 *
 * All tracks and segments found in the file are concatenated in document order into a single
 * point list. Day boundaries are not derived from the GPX structure itself: they come later
 * from where the user places bivouac points along this track.
 */
object GpxParser {

    // Plafond volontairement très large (une trace GPX réelle, même multi-jours et verbeuse,
    // reste sous quelques Mo) : ce flux peut arriver d'une source externe non maîtrisée
    // (VIEW/SEND depuis une autre app, activity exportée), il ne doit pas pouvoir faire allouer
    // une chaîne arbitrairement grande en mémoire (RIC-95).
    const val MAX_GPX_BYTES = 50 * 1024 * 1024

    /**
     * Reads [input] fully as UTF-8, refusing (via [IOException]) anything past [MAX_GPX_BYTES].
     * Shared with the import paths that need the raw content itself (hashing/storage), so the
     * same bound applies before any full-file allocation, wherever the bytes come from.
     */
    @Throws(IOException::class)
    fun readBounded(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (buffer.size() + read > MAX_GPX_BYTES) {
                throw IOException("Fichier GPX trop volumineux (plus de ${MAX_GPX_BYTES / (1024 * 1024)} Mo)")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    @Throws(IOException::class)
    fun parse(input: InputStream): HikeTrack {
        val rawXml = readBounded(input)
        val cleanedXml = stripExtensionsBlocks(rawXml)
        val gpx = GPX.Reader.of(GPX.Reader.Mode.LENIENT).read(cleanedXml.byteInputStream(Charsets.UTF_8))

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

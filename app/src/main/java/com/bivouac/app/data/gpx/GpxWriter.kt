package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint

object GpxWriter {

    fun write(points: List<TrackPoint>, trackName: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Bivouac\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(trackName)).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (point in points) {
            sb.append("      <trkpt lat=\"").append(point.latitude).append("\" lon=\"").append(point.longitude).append("\">\n")
            point.elevationMeters?.let { sb.append("        <ele>").append(it).append("</ele>\n") }
            point.time?.let { sb.append("        <time>").append(it).append("</time>\n") }
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    fun suggestedFileName(name: String): String {
        val sanitized = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return "$sanitized.gpx"
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

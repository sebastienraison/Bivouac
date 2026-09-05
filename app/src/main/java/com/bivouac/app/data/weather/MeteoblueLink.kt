package com.bivouac.app.data.weather

import java.util.Locale
import kotlin.math.abs

/**
 * Deep link to a meteoblue forecast centered on raw coordinates, no geocoding needed: meteoblue
 * (unlike Météo France) resolves a plain lat/lon URL directly.
 */
object MeteoblueLink {
    private val SUPPORTED_LANGUAGES = setOf("fr", "en")

    fun forCoordinates(latitude: Double, longitude: Double): String {
        val latSuffix = if (latitude >= 0) "N" else "S"
        val lonSuffix = if (longitude >= 0) "E" else "W"
        val lat = String.format(Locale.ROOT, "%.2f", abs(latitude))
        val lon = String.format(Locale.ROOT, "%.2f", abs(longitude))
        val lang = Locale.getDefault().language.takeIf { it in SUPPORTED_LANGUAGES } ?: "en"
        return "https://www.meteoblue.com/$lang/weather/week/$lat$latSuffix$lon$lonSuffix"
    }
}

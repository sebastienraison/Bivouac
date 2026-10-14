package com.bivouac.app.ui.journal

import com.bivouac.app.i18n.espacesNormalisees
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

// RIC-227 : dates du dialogue « Bivouac détecté », avec les motifs réellement générés depuis
// l'inventaire (values/strings.xml et values-fr/strings.xml), dans le fuseau du bivouac.
class BivouacDetectedDialogFormatTest {

    private val paris = ZoneId.of("Europe/Paris")

    /** Valeur d'une <string> générée, échappements Android (\' et \") retirés comme le ferait getString. */
    private fun resource(file: String, name: String): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(file))
        val nodes = doc.documentElement.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as Element
            if (node.getAttribute("name") == name) return node.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
        error("$name absente de $file")
    }

    private fun format(locale: Locale, dir: String, stop: String, resume: String): BivouacDateTimes =
        formatBivouacDateTimes(
            stopAt = LocalDateTime.parse(stop).atZone(paris).toInstant(),
            resumeAt = LocalDateTime.parse(resume).atZone(paris).toInstant(),
            zone = paris,
            locale = locale,
            pattern = resource("src/main/res/$dir/strings.xml", "bivouac_detected_datetime_pattern"),
            patternWithYear = resource("src/main/res/$dir/strings.xml", "bivouac_detected_datetime_pattern_with_year"),
        )

    @Test
    fun `francais, meme annee`() {
        val result = format(Locale.FRENCH, "values-fr", "2026-08-12T20:30:00", "2026-08-13T07:15:00")
        assertEquals("mercredi 12 août à 20 h 30", result.stop.espacesNormalisees())
        assertEquals("jeudi 13 août à 7 h 15", result.resume.espacesNormalisees())
    }

    @Test
    fun `francais, heure du matin sans zero, minutes sur deux chiffres`() {
        val result = format(Locale.FRENCH, "values-fr", "2026-08-13T21:05:00", "2026-08-14T06:05:00")
        assertEquals("jeudi 13 août à 21 h 05", result.stop.espacesNormalisees())
        assertEquals("vendredi 14 août à 6 h 05", result.resume.espacesNormalisees())
    }

    @Test
    fun `anglais, meme annee`() {
        val result = format(Locale.ENGLISH, "values", "2026-08-12T20:30:00", "2026-08-13T07:15:00")
        assertEquals("Wednesday 12 August at 20:30", result.stop)
        assertEquals("Thursday 13 August at 07:15", result.resume)
    }

    @Test
    fun `annee ajoutee aux deux instants quand elle change`() {
        val fr = format(Locale.FRENCH, "values-fr", "2025-12-31T20:00:00", "2026-01-01T09:00:00")
        assertEquals("mercredi 31 décembre 2025 à 20 h 00", fr.stop.espacesNormalisees())
        assertEquals("jeudi 1 janvier 2026 à 9 h 00", fr.resume.espacesNormalisees())
        val en = format(Locale.ENGLISH, "values", "2025-12-31T20:00:00", "2026-01-01T09:00:00")
        assertEquals("Wednesday 31 December 2025 at 20:00", en.stop)
        assertEquals("Thursday 1 January 2026 at 09:00", en.resume)
    }

    @Test
    fun `fuseau du bivouac, pas celui de l'appareil`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val stop = LocalDateTime.parse("2026-08-12T20:30:00").atZone(paris).toInstant()
        val result = formatBivouacDateTimes(stop, stop.plusSeconds(10 * 3_600), tokyo, Locale.ENGLISH, "HH:mm", "yyyy HH:mm")
        assertEquals("03:30", result.stop)
    }
}

package com.bivouac.app.bilan

import androidx.compose.ui.graphics.Color
import com.bivouac.app.ui.components.BivouacIconColor
import com.bivouac.app.ui.components.DistanceIconColor
import com.bivouac.app.ui.components.DurationIconColor
import com.bivouac.app.ui.components.GainIconColor
import com.bivouac.app.ui.components.formatGroupedInt
import com.bivouac.app.ui.components.formatKm1
import java.time.Instant
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// RIC-19 : mise en forme d'affichage, volontairement séparée de BilanStatsCalculator (qui ne
// manipule que des nombres et des millis) : même partition que le reste de l'app entre calculateurs
// purs (TrackStatsCalculator...) et formatage propre à l'écran (StatsRows.formatDuration...).
//
// formatKm1/formatGroupedInt : RIC-136 les a fait migrer vers ui.components.NumberFormatting,
// partagées avec StatsRows et le reste de l'app plutôt que dupliquées ici.

// RIC-187 (lot 0 i18n) : Locale.FRENCH figé remplacé par Locale.getDefault() (mois en toutes
// lettres dans la locale de l'appareil, "avril 2026" / "April 2026" : pas d'ambiguïté d'ordre, un
// seul mot puis l'année).
internal fun formatMonthYear(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val date = Instant.ofEpochMilli(millis).atZone(zone)
    return "${date.month.getDisplayName(TextStyle.FULL, locale)} ${date.year}"
}

// RIC-187 (lot 0 i18n) : le when-expression figé sur le français ("J F M A M J J A S O N D") est
// remplacé par Month.getDisplayName(TextStyle.NARROW, locale), qui donne directement l'initiale
// CLDR de la locale (identiques en français et en anglais pour ce jeu de 12 mois, sauf août -> A vs
// August -> A, en réalité déjà identiques ici aussi). Tranché par la note de relecture de
// l'inventaire i18n (clé bilan_month_initials_array, volontairement non générée en ressource, voir
// tools/i18n/generate_strings.py) : pas de <string-array>, java.time suffit (minSdk 26).
internal fun monthInitial(month: Month, locale: Locale = Locale.getDefault()): String =
    month.getDisplayName(TextStyle.NARROW, locale)

internal fun recordLabel(kind: BilanRecordKind): String = when (kind) {
    BilanRecordKind.KM_EFFORT -> "km-effort, une sortie"
    BilanRecordKind.VAM -> "meilleure VAM"
    BilanRecordKind.MAX_ALTITUDE -> "altitude max atteinte"
    BilanRecordKind.HIGHEST_BIVOUAC -> "bivouac le plus haut"
    BilanRecordKind.MAX_DISTANCE_DAY -> "Distance max, une journée"
    BilanRecordKind.MAX_GAIN_DAY -> "D+ max, une journée"
    BilanRecordKind.BIGGEST_TREK -> "Plus gros trek"
}

internal fun recordValueText(record: BilanRecord): String = when (record.kind) {
    BilanRecordKind.KM_EFFORT -> "${formatKm1(record.value)} km-eff."
    BilanRecordKind.VAM -> "${formatGroupedInt(record.value)} m/h"
    BilanRecordKind.MAX_ALTITUDE -> "${formatGroupedInt(record.value)} m"
    BilanRecordKind.HIGHEST_BIVOUAC -> "${formatGroupedInt(record.value)} m"
    BilanRecordKind.MAX_DISTANCE_DAY -> "${formatKm1(record.value)} km"
    BilanRecordKind.MAX_GAIN_DAY -> "${formatGroupedInt(record.value)} m"
    BilanRecordKind.BIGGEST_TREK -> {
        val days = record.value.toInt()
        "$days jour${if (days > 1) "s" else ""}"
    }
}

// RIC-19 (revu) : nom et date chacun sur leur ligne (plutôt que "nom · date" concaténé), et pour
// BIGGEST_TREK (seul kind à porter extraDistanceKm/extraGainMeters) une troisième ligne à part
// pour le km/D+ du trek : un nom de sortie déjà long ne les repoussait plus qu'à la coupure,
// tantôt sur une ligne, tantôt sur deux, sans mise en page prévisible.
internal fun recordMetaLines(record: BilanRecord): List<String> {
    val distance = record.extraDistanceKm
    val gain = record.extraGainMeters
    val lines = mutableListOf(record.placeName, formatMonthYear(record.whenMillis))
    if (distance != null && gain != null) {
        lines += "${formatKm1(distance)} km · ${formatGroupedInt(gain)} m D+"
    }
    return lines
}

internal fun recordColor(kind: BilanRecordKind): Color = when (kind) {
    BilanRecordKind.KM_EFFORT -> DistanceIconColor
    BilanRecordKind.VAM -> GainIconColor
    BilanRecordKind.MAX_ALTITUDE -> DurationIconColor
    BilanRecordKind.HIGHEST_BIVOUAC -> BivouacIconColor
    BilanRecordKind.MAX_DISTANCE_DAY -> DistanceIconColor
    BilanRecordKind.MAX_GAIN_DAY -> GainIconColor
    BilanRecordKind.BIGGEST_TREK -> DurationIconColor
}

// RIC-19 §2 : "Tu sors surtout en juillet (12 sorties cumulées depuis 2021)" : formulation exacte
// de la maquette.
//
// RIC-187 (lot 0 i18n) : Locale.FRENCH volontairement PAS remplacé ici, contrairement au reste du
// fichier. Le nom du mois n'est qu'un fragment d'une phrase française codée en dur ("Tu sors
// surtout en ...", accord du pluriel "s" ajouté à la main) : rendre le mois localisable sans
// traduire la phrase qui l'entoure donnerait un résultat mêlant les deux langues ("You mostly go
// out en juillet" sur un appareil anglais), pire que le tout-français actuel. Même famille de
// décision que TrekDatesFormatter.format (data/model/TrekDatesFormatter.kt) : à traiter avec la
// migration de l'écran Bilan (lots 1 à 4), pas au lot 0 qui ne touche aucun écran.
internal fun formatInsight(insight: MostActiveMonthInsight): String {
    val monthName = Month.of(insight.monthOfYear).getDisplayName(TextStyle.FULL, Locale.FRENCH)
    val plural = if (insight.cumulativeCount > 1) "s" else ""
    return "Tu sors surtout en $monthName (${insight.cumulativeCount} sortie$plural cumulée$plural depuis ${insight.sinceYear})"
}

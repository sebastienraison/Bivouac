package com.bivouac.app.bilan

import androidx.compose.ui.graphics.Color
import com.bivouac.app.ui.components.BivouacIconColor
import com.bivouac.app.ui.components.DistanceIconColor
import com.bivouac.app.ui.components.DurationIconColor
import com.bivouac.app.ui.components.GainIconColor
import java.text.NumberFormat
import java.time.Instant
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// RIC-19 : mise en forme d'affichage, volontairement séparée de BilanStatsCalculator (qui ne
// manipule que des nombres et des millis) — même partition que le reste de l'app entre calculateurs
// purs (TrackStatsCalculator...) et formatage propre à l'écran (StatsRows.formatDuration...).

internal fun formatMonthYear(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = Instant.ofEpochMilli(millis).atZone(zone)
    return "${date.month.getDisplayName(TextStyle.FULL, Locale.FRENCH)} ${date.year}"
}

private val KM_FORMAT: NumberFormat
    get() = NumberFormat.getNumberInstance(Locale.FRANCE).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }

internal fun formatKm1(km: Double): String = KM_FORMAT.format(km)

internal fun formatGroupedInt(value: Double): String = NumberFormat.getIntegerInstance(Locale.FRANCE).format(value.toLong())

internal fun monthInitial(month: Month): String = when (month) {
    Month.JANUARY -> "J"
    Month.FEBRUARY -> "F"
    Month.MARCH -> "M"
    Month.APRIL -> "A"
    Month.MAY -> "M"
    Month.JUNE -> "J"
    Month.JULY -> "J"
    Month.AUGUST -> "A"
    Month.SEPTEMBER -> "S"
    Month.OCTOBER -> "O"
    Month.NOVEMBER -> "N"
    Month.DECEMBER -> "D"
}

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
// pour le km/D+ du trek — un nom de sortie déjà long ne les repoussait plus qu'à la coupure,
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

// RIC-19 §2 : "Tu sors surtout en juillet (12 sorties cumulées depuis 2021)" — formulation exacte
// de la maquette.
internal fun formatInsight(insight: MostActiveMonthInsight): String {
    val monthName = Month.of(insight.monthOfYear).getDisplayName(TextStyle.FULL, Locale.FRENCH)
    val plural = if (insight.cumulativeCount > 1) "s" else ""
    return "Tu sors surtout en $monthName (${insight.cumulativeCount} sortie$plural cumulée$plural depuis ${insight.sinceYear})"
}

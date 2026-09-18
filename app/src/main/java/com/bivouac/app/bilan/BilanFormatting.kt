package com.bivouac.app.bilan

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.bivouac.app.R
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
//
// RIC-190 (lot 3 i18n) : ces fonctions prennent un Context et lisent leurs textes dans les
// ressources, même patron que GpxImportScreen.formatSavedAt au lot 1. Un Context plutôt que des
// @Composable : elles restent testables sans monter d'écran (BilanFormattingLocaleTest, sous
// Robolectric).

// RIC-187 (lot 0 i18n) : Locale.FRENCH figé remplacé par Locale.getDefault() (mois en toutes
// lettres dans la locale de l'appareil, "avril 2026" / "April 2026" : pas d'ambiguïté d'ordre, un
// seul mot puis l'année).
internal fun formatMonthYear(
    context: Context,
    millis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val date = Instant.ofEpochMilli(millis).atZone(zone)
    return context.getString(R.string.fmt_bilan_month_year, date.month.getDisplayName(TextStyle.FULL, locale), date.year)
}

// RIC-187 (lot 0 i18n) : le when-expression figé sur le français ("J F M A M J J A S O N D") est
// remplacé par Month.getDisplayName(TextStyle.NARROW, locale), qui donne directement l'initiale
// CLDR de la locale (identiques en français et en anglais pour ce jeu de 12 mois, sauf août -> A vs
// August -> A, en réalité déjà identiques ici aussi). Tranché par la note de relecture de
// l'inventaire i18n (clé bilan_month_initials_array, volontairement non générée en ressource, voir
// tools/i18n/generate_strings.py) : pas de <string-array>, java.time suffit (minSdk 26).
internal fun monthInitial(month: Month, locale: Locale = Locale.getDefault()): String =
    month.getDisplayName(TextStyle.NARROW, locale)

// RIC-190 (lot 3 i18n) : un id de ressource et non une chaîne, résolu au point d'affichage
// (BilanScreen) : BilanRecordKind est un enum du calculateur, qui ne doit porter aucun texte.
@StringRes
internal fun recordLabelRes(kind: BilanRecordKind): Int = when (kind) {
    BilanRecordKind.KM_EFFORT -> R.string.bilan_record_label_km_effort
    BilanRecordKind.VAM -> R.string.bilan_record_label_vam
    BilanRecordKind.MAX_ALTITUDE -> R.string.bilan_record_label_max_altitude
    BilanRecordKind.HIGHEST_BIVOUAC -> R.string.bilan_record_label_highest_bivouac
    BilanRecordKind.MAX_DISTANCE_DAY -> R.string.bilan_record_label_max_distance_day
    BilanRecordKind.MAX_GAIN_DAY -> R.string.bilan_record_label_max_gain_day
    BilanRecordKind.BIGGEST_TREK -> R.string.bilan_record_label_biggest_trek
}

internal fun recordValueText(context: Context, record: BilanRecord): String = when (record.kind) {
    BilanRecordKind.KM_EFFORT ->
        context.getString(R.string.fmt_bilan_record_value_km_effort, formatKm1(record.value))
    BilanRecordKind.VAM ->
        context.getString(R.string.fmt_bilan_record_value_vam, formatGroupedInt(record.value))
    BilanRecordKind.MAX_ALTITUDE ->
        context.getString(R.string.format_elevation_meters, formatGroupedInt(record.value))
    BilanRecordKind.HIGHEST_BIVOUAC ->
        context.getString(R.string.format_elevation_meters, formatGroupedInt(record.value))
    BilanRecordKind.MAX_DISTANCE_DAY ->
        context.getString(R.string.format_distance_km, formatKm1(record.value))
    BilanRecordKind.MAX_GAIN_DAY ->
        context.getString(R.string.format_elevation_meters, formatGroupedInt(record.value))
    // RIC-190 (lot 3 i18n) : le "s" conditionnel collé au mot devient un vrai <plurals> Android.
    BilanRecordKind.BIGGEST_TREK -> {
        val days = record.value.toInt()
        context.resources.getQuantityString(R.plurals.bilan_record_value_biggest_trek, days, days)
    }
}

// RIC-19 (revu) : nom et date chacun sur leur ligne (plutôt que "nom · date" concaténé), et pour
// BIGGEST_TREK (seul kind à porter extraDistanceKm/extraGainMeters) une troisième ligne à part
// pour le km/D+ du trek : un nom de sortie déjà long ne les repoussait plus qu'à la coupure,
// tantôt sur une ligne, tantôt sur deux, sans mise en page prévisible.
internal fun recordMetaLines(context: Context, record: BilanRecord): List<String> {
    val distance = record.extraDistanceKm
    val gain = record.extraGainMeters
    val lines = mutableListOf(record.placeName, formatMonthYear(context, record.whenMillis))
    if (distance != null && gain != null) {
        lines += context.getString(
            R.string.fmt_bilan_record_meta_extra,
            formatKm1(distance),
            formatGroupedInt(gain),
        )
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
// RIC-190 (lot 3 i18n) : la PHRASE ENTIÈRE devient un <plurals> à paramètres, et le Locale.FRENCH
// que le lot 0 avait gardé disparaît avec elle. Il n'était là que parce que traduire le seul nom du
// mois dans une phrase française en dur aurait donné « You mostly go out en juillet » ; la phrase
// étant maintenant traduite, le mois suit la locale de l'appareil comme partout ailleurs. Le "s"
// conditionnel de « sortie(s) cumulée(s) » est remplacé par les formes one/other de la ressource.
internal fun formatInsight(context: Context, insight: MostActiveMonthInsight): String {
    val monthName = Month.of(insight.monthOfYear).getDisplayName(TextStyle.FULL, Locale.getDefault())
    // RIC-192 : le cumul de sorties porte le séparateur de milliers de la locale ; l'année
    // (%3$d) reste un entier brut.
    return context.resources.getQuantityString(
        R.plurals.bilan_insight_most_active_month,
        insight.cumulativeCount,
        monthName,
        formatGroupedInt(insight.cumulativeCount),
        insight.sinceYear,
    )
}

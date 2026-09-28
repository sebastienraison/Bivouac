package com.bivouac.app.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bivouac.app.R
import com.bivouac.app.bilan.recordColor
import com.bivouac.app.bilan.recordLabelRes
import com.bivouac.app.bilan.recordValueText
import com.bivouac.app.data.gpx.AnalysisBand
import com.bivouac.app.data.gpx.AnalysisParameters
import com.bivouac.app.data.gpx.AnalyzedPause
import com.bivouac.app.data.gpx.ReferenceSource
import com.bivouac.app.data.gpx.TrackAnalysisMapMapping
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.journal.JournalAnalysisResult
import com.bivouac.app.ui.components.DurationIconColor
import com.bivouac.app.ui.components.formatDuration
import com.bivouac.app.ui.components.formatGroupedInt
import com.bivouac.app.ui.components.formatKm1
import com.bivouac.app.ui.map.AnalysisColoring
import com.bivouac.app.ui.map.AnalysisColors
import java.time.Instant
import kotlin.math.roundToInt

/**
 * RIC-146 lot 3 : contenu du mode Analyse (conception section 5 et 7.3), factorisé hors de
 * JournalScreen.kt (déjà volumineux) pour rester lisible. Consommé uniquement par
 * ThreeStopJournalDetail : tout est `internal`, rien de ce fichier n'est une API publique du
 * module.
 */

// --- Étiquette "Analyse" à côté du titre (brief lot 3 §1) -----------------------------------------

@Composable
internal fun AnalysisBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.journal_analysis_badge),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// --- Chiffre de tête et quatre valeurs, remplace StatsRows en mode Analyse (brief lot 3 §4) -------

@Composable
internal fun AnalysisHeadlineHeader(result: JournalAnalysisResult?, loading: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (loading || result == null) {
            // Brief lot 3 §2 : indicateur de progression discret pendant le calcul, hors fil
            // principal (voir JournalViewModel.computeAnalysis) ; la carte reste utilisable.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            return
        }
        val totals = result.analysis.totals
        val estimate = result.analysis.estimate
        if (totals == null || estimate == null) {
            // Brief lot 3 §5 : trace sans horodatage, pas de chiffre de tête ni de quatre valeurs.
            Text(
                text = stringResource(R.string.journal_analysis_no_timestamps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        val realMinutes = (totals.elapsedSeconds / 60.0).roundToInt()
        val estimatedMinutes = (estimate.totalSeconds / 60.0).roundToInt()
        val deltaMinutes = (estimate.deltaSeconds / 60.0).roundToInt()
        val headline = when {
            deltaMinutes == 0 -> stringResource(R.string.journal_analysis_headline_same, formatDuration(realMinutes))
            deltaMinutes > 0 -> stringResource(
                R.string.journal_analysis_headline_more,
                formatDuration(realMinutes),
                formatDuration(deltaMinutes),
                formatDuration(estimatedMinutes),
            )
            else -> stringResource(
                R.string.journal_analysis_headline_less,
                formatDuration(realMinutes),
                formatDuration(-deltaMinutes),
                formatDuration(estimatedMinutes),
            )
        }
        Text(text = headline, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(
                R.string.journal_analysis_headline_detail,
                formatDuration((totals.movingSeconds / 60.0).roundToInt()),
                formatDuration((estimate.walkingSeconds / 60.0).roundToInt()),
                formatDuration((totals.pausedSeconds / 60.0).roundToInt()),
                formatDuration((estimate.pausedSeconds / 60.0).roundToInt()),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnalysisStatItem(
                value = formatDuration((totals.movingSeconds / 60.0).roundToInt()),
                label = stringResource(R.string.journal_analysis_stat_walking_time),
                modifier = Modifier.weight(1f),
            )
            AnalysisStatItem(
                value = formatDuration((totals.pausedSeconds / 60.0).roundToInt()),
                label = pluralStringResource(R.plurals.journal_analysis_stat_breaks, totals.pauseCount, formatGroupedInt(totals.pauseCount)),
                modifier = Modifier.weight(1f),
            )
            AnalysisStatItem(
                value = stringResource(R.string.settings_speed_value_format, formatKm1(totals.movingSpeedKmh)),
                label = stringResource(R.string.journal_analysis_stat_moving_speed),
                modifier = Modifier.weight(1f),
            )
            AnalysisStatItem(
                value = totals.averageClimbingSpeedMetersPerHour?.let {
                    stringResource(R.string.fmt_bilan_record_value_vam, formatGroupedInt(it.roundToInt()))
                } ?: "-",
                label = stringResource(R.string.journal_analysis_stat_climbing),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AnalysisStatItem(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = value, style = MaterialTheme.typography.titleSmall)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- Sélecteur de coloration, cran Profil (brief lot 3 §4) -----------------------------------------

@Composable
internal fun AnalysisColoringSelector(
    coloring: AnalysisColoring,
    onColoringChanged: (AnalysisColoring) -> Unit,
    hasTimestamps: Boolean,
    modifier: Modifier = Modifier,
) {
    // Brief lot 3 §5 : trace sans horodatage, seule la coloration Pente est proposée.
    val available = if (hasTimestamps) AnalysisColoring.entries else listOf(AnalysisColoring.SLOPE)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        available.forEach { candidate ->
            val labelRes = when (candidate) {
                AnalysisColoring.PACE -> R.string.journal_analysis_coloring_pace
                AnalysisColoring.SLOPE -> R.string.journal_analysis_coloring_slope
                AnalysisColoring.SPEED -> R.string.journal_analysis_coloring_speed
            }
            FilterChip(
                selected = coloring == candidate,
                onClick = { onColoringChanged(candidate) },
                label = { Text(stringResource(labelRes)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

// --- Légende, en bas à gauche de la carte (brief lot 3 §3) -----------------------------------------

@Composable
internal fun AnalysisLegend(coloring: AnalysisColoring, modifier: Modifier = Modifier) {
    val (titleRes, palette) = when (coloring) {
        AnalysisColoring.PACE -> R.string.journal_analysis_legend_pace_title to AnalysisColors.pace
        AnalysisColoring.SLOPE -> R.string.journal_analysis_legend_slope_title to AnalysisColors.slope
        AnalysisColoring.SPEED -> R.string.journal_analysis_legend_speed_title to AnalysisColors.speed
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text = stringResource(titleRes), style = MaterialTheme.typography.labelSmall)
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            palette.forEach { color ->
                Box(modifier = Modifier.size(width = 20.dp, height = 6.dp).background(color))
            }
        }
        if (coloring == AnalysisColoring.PACE) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                LegendCaption(stringResource(R.string.journal_analysis_legend_pace_slower))
                LegendCaption(stringResource(R.string.journal_analysis_legend_pace_usual))
                LegendCaption(stringResource(R.string.journal_analysis_legend_pace_faster))
            }
        }
    }
}

@Composable
private fun LegendCaption(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// --- Cran Détails : quatre sections (brief lot 3 §4) ------------------------------------------------

/** Une pause de la conception, associée à l'heure réelle de son point de départ (best-effort : `null`
 * si l'index reconverti tombe hors de [points], ne devrait pas arriver). */
private data class TimedPause(val pause: AnalyzedPause, val time: Instant?)

@Composable
internal fun AnalysisDetailsContent(
    result: JournalAnalysisResult,
    points: List<TrackPoint>,
    dayPointCounts: List<Int>,
    modifier: Modifier = Modifier,
) {
    val analysis = result.analysis
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (analysis.totals != null && analysis.bands.isNotEmpty()) {
            PaceBySlopeSection(analysis.bands)
        }
        if (analysis.totals != null) {
            // Reconversion vers l'heure réelle : mêmes jonctions de jours que la carte (conception
            // section 5.2, brief lot 3 §3), une pause étant locale à son jour (AnalyzedPause.startIndex).
            val dayOffsets = TrackAnalysisMapMapping.dayOffsets(dayPointCounts)
            val timedPauses = analysis.days.flatMapIndexed { dayIndex, day ->
                day.pauses.map { pause ->
                    val screenIndex = TrackAnalysisMapMapping.toScreenIndex(dayOffsets, dayIndex, pause.startIndex)
                    TimedPause(pause, points.getOrNull(screenIndex)?.time)
                }
            }
            PausesSection(timedPauses)
        }
        analysis.bestPassages?.let { best ->
            if (best.climbMetersPerHour != null || best.flatSpeedKmh != null) BestPassagesSection(best)
        }
        if (result.records.isNotEmpty()) RecordsSection(result.records)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun PaceBySlopeSection(bands: List<AnalysisBand>) {
    Column {
        SectionTitle(stringResource(R.string.journal_analysis_section_pace_by_slope))
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            bands.filter { it.hikeDistanceMeters > 0 }.forEach { band -> PaceBandRow(band) }
        }
        Text(
            text = stringResource(R.string.journal_analysis_pace_by_slope_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun PaceBandRow(band: AnalysisBand) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = analysisBandLabel(band.band), style = MaterialTheme.typography.bodySmall)
            val hikeText = band.hikeSpeedKmh?.let { stringResource(R.string.settings_speed_value_format, formatKm1(it)) } ?: "-"
            val referenceText = band.referenceSpeedKmh?.let { stringResource(R.string.settings_speed_value_format, formatKm1(it)) } ?: "-"
            Text(text = "$hikeText / $referenceText", style = MaterialTheme.typography.bodySmall)
        }
        // Barre d'écart centrée : ratio hike/référence, bornée à [0.5x, 1.5x] pour rester lisible
        // même avec un écart extrême, la valeur exacte étant déjà donnée en toutes lettres au-dessus.
        val ratio = if (band.hikeSpeedKmh != null && band.referenceSpeedKmh != null && band.referenceSpeedKmh > 0) {
            (band.hikeSpeedKmh / band.referenceSpeedKmh).coerceIn(0.5, 1.5)
        } else {
            1.0
        }
        val fraction = ((ratio - 0.5) / 1.0).toFloat().coerceIn(0f, 1f)
        val barColor = if (band.referenceSource == ReferenceSource.NONE) {
            MaterialTheme.colorScheme.outlineVariant
        } else {
            AnalysisColors.pace[(fraction * 4).roundToInt().coerceIn(0, 4)]
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor),
            )
        }
    }
}

@Composable
private fun analysisBandLabel(band: Int): String {
    val bounds = AnalysisParameters.SLOPE_BAND_BOUNDS_PERCENT
    return when (band) {
        0 -> stringResource(R.string.journal_analysis_band_downhill_over, formatGroupedInt((-bounds[0]).toInt()))
        in 1..4 -> stringResource(
            R.string.journal_analysis_band_downhill_range,
            formatGroupedInt((-bounds[band]).toInt()),
            formatGroupedInt((-bounds[band - 1]).toInt()),
        )
        5 -> stringResource(R.string.journal_analysis_band_flat)
        in 6..9 -> stringResource(
            R.string.journal_analysis_band_uphill_range,
            formatGroupedInt(bounds[band - 1].toInt()),
            formatGroupedInt(bounds[band].toInt()),
        )
        else -> stringResource(R.string.journal_analysis_band_uphill_over, formatGroupedInt(bounds[9].toInt()))
    }
}

@Composable
private fun PausesSection(pauses: List<TimedPause>) {
    val longPauses = pauses.filter { it.pause.seconds >= 300.0 }.sortedBy { it.time ?: Instant.EPOCH }
    val shortCount = pauses.size - longPauses.size
    Column {
        SectionTitle(stringResource(R.string.journal_analysis_section_breaks))
        if (longPauses.isEmpty()) {
            Text(
                text = "-",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            longPauses.forEach { timed -> PauseRow(timed) }
        }
        if (shortCount > 0) {
            Text(
                text = pluralStringResource(R.plurals.journal_analysis_short_breaks, shortCount, formatGroupedInt(shortCount)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun PauseRow(timed: TimedPause) {
    val pause = timed.pause
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            timed.time?.let { Text(text = formatTimeOfDay(it), style = MaterialTheme.typography.bodySmall) }
            Text(
                text = formatDuration((pause.seconds / 60.0).roundToInt()),
                style = MaterialTheme.typography.bodySmall,
                color = DurationIconColor,
            )
        }
        Text(
            text = stringResource(
                R.string.journal_analysis_break_position,
                formatKm1(pause.distanceMeters / 1000.0),
                stringResource(R.string.format_elevation_meters, formatGroupedInt(pause.elevationMeters.roundToInt())),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BestPassagesSection(best: com.bivouac.app.data.gpx.BestPassages) {
    Column {
        SectionTitle(stringResource(R.string.journal_analysis_section_best))
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            best.climbMetersPerHour?.let {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.journal_analysis_best_climb), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.fmt_bilan_record_value_vam, formatGroupedInt(it.roundToInt())), style = MaterialTheme.typography.bodySmall)
                }
            }
            best.flatSpeedKmh?.let {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.journal_analysis_best_flat), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.settings_speed_value_format, formatKm1(it)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RecordsSection(records: List<com.bivouac.app.bilan.BilanRecord>) {
    Column {
        SectionTitle(stringResource(R.string.journal_analysis_section_records))
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val context = androidx.compose.ui.platform.LocalContext.current
            records.forEach { record ->
                val color = recordColor(record.kind)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                    Column {
                        Text(text = recordValueText(context, record), style = MaterialTheme.typography.bodyMedium, color = color)
                        Text(
                            text = stringResource(recordLabelRes(record.kind)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

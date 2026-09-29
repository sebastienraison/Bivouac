package com.bivouac.app.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bivouac.app.R
import com.bivouac.app.bilan.recordColor
import com.bivouac.app.bilan.recordLabelRes
import com.bivouac.app.bilan.recordValueText
import com.bivouac.app.data.gpx.AnalysisTotals
import com.bivouac.app.data.gpx.ReferenceSource
import com.bivouac.app.data.gpx.TerrainSummary
import com.bivouac.app.data.gpx.TimelinePhaseKind
import com.bivouac.app.data.gpx.TrackAnalysisMapMapping
import com.bivouac.app.data.gpx.TrackFigures
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.journal.JournalAnalysisResult
import com.bivouac.app.ui.components.ElevationProfileAxis
import com.bivouac.app.ui.components.formatDuration
import com.bivouac.app.ui.components.formatGroupedInt
import com.bivouac.app.ui.components.formatKm1
import com.bivouac.app.ui.map.AnalysisColoring
import com.bivouac.app.ui.map.AnalysisColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * RIC-146 lot 7 : contenu du mode Analyse, nouvelle structure (conception 2 section 5, brief
 * Partie C/D), factorisé hors de JournalScreen.kt (déjà volumineux) pour rester lisible. Consommé
 * uniquement par ThreeStopJournalDetail : tout est `internal`, rien de ce fichier n'est une API
 * publique du module.
 *
 * Remplace intégralement le contenu du lot 3 à 5 (chiffre de tête + quatre valeurs, tableau des
 * onze bandes de pente, liste des pauses, légende flottante sur la carte, bouton rond, étiquette
 * "Analyse") : la seconde conception (2026-09-29) refait la structure de l'écran, jugé trop dense
 * et aux libellés coupés à 360 points de large par le propriétaire.
 */

// --- Choix Carnet | Analyse, sous le titre et la date (brief Partie A) -----------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JournalViewModeSelector(
    analysisActive: Boolean,
    onModeChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Même composant segmenté que le choix Manuel/Auto/Sélection des Réglages (brief Partie A.1,
    // cohérence de l'app), déjà repris par AnalysisColoringSelector plus bas.
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !analysisActive,
            onClick = { onModeChanged(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            label = { Text(stringResource(R.string.journal_detail_view_logbook)) },
        )
        SegmentedButton(
            selected = analysisActive,
            onClick = { onModeChanged(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            label = { Text(stringResource(R.string.journal_detail_view_analysis)) },
        )
    }
}

// --- Chiffre de tête (brief Partie D.1) -------------------------------------------------------------

/** Quelle formule de la seconde ligne du chiffre de tête (brief §D.1) selon le signe de l'écart à
 * l'estimation. Fonction pure, extraite de [AnalysisHeadlineHeader] pour être testée directement
 * par HeadlineGapWordingTest, même patron que [readoutPaceWordingFor] plus bas. */
internal enum class HeadlineGapWording { LESS, MORE, SAME }

/** [deltaMinutes] : écart arrondi à la minute (réel moins estimé) ; `0` -> [HeadlineGapWording.SAME]
 * ("l'écart arrondi vaut zéro minute", inventaire v16). */
internal fun headlineGapWordingFor(deltaMinutes: Int): HeadlineGapWording = when {
    deltaMinutes == 0 -> HeadlineGapWording.SAME
    deltaMinutes > 0 -> HeadlineGapWording.MORE
    else -> HeadlineGapWording.LESS
}

/**
 * Durée réelle en grand, part de marche à côté, écart à l'estimation dessous. Remplace le chiffre
 * de tête et les quatre valeurs du lot 3 (marche/pauses/vitesse/VAM, déplacées dans "Forme du jour"
 * et "Chiffres", brief §4.5/§4.6).
 */
@Composable
internal fun AnalysisHeadlineHeader(result: JournalAnalysisResult?, loading: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (loading || result == null) {
            // Indicateur de progression discret pendant le calcul, hors fil principal (voir
            // JournalViewModel.computeAnalysis) ; la carte reste utilisable.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            return
        }
        val totals = result.analysis.totals
        val estimate = result.analysis.estimate
        if (totals == null || estimate == null) {
            // Brief §5 : trace sans horodatage, ce message remplace tout le chiffre de tête.
            Text(
                text = stringResource(R.string.journal_analysis_no_timestamps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        val realMinutes = (totals.elapsedSeconds / 60.0).roundToInt()
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = formatDuration(realMinutes), style = MaterialTheme.typography.headlineSmall)
            result.timeline.walkingSharePercent?.let { percent ->
                Text(
                    text = stringResource(R.string.fmt_walking_share, percent.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        val deltaMinutes = (estimate.deltaSeconds / 60.0).roundToInt()
        val estimatedText = formatDuration((estimate.totalSeconds / 60.0).roundToInt())
        val gapText = when (headlineGapWordingFor(deltaMinutes)) {
            HeadlineGapWording.SAME -> stringResource(R.string.journal_analysis_gap_same, estimatedText)
            HeadlineGapWording.MORE -> stringResource(R.string.journal_analysis_gap_more, formatPauseDuration(estimate.deltaSeconds), estimatedText)
            HeadlineGapWording.LESS -> stringResource(R.string.journal_analysis_gap_less, formatPauseDuration(-estimate.deltaSeconds), estimatedText)
        }
        Text(
            text = gapText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// --- Sélecteur de coloration (brief Partie D.2) ------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalysisColoringSelector(
    coloring: AnalysisColoring,
    onColoringChanged: (AnalysisColoring) -> Unit,
    hasTimestamps: Boolean,
    modifier: Modifier = Modifier,
) {
    // Brief §5 : trace sans horodatage, seule la coloration Pente est proposée.
    val available = if (hasTimestamps) AnalysisColoring.entries else listOf(AnalysisColoring.SLOPE)
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        available.forEachIndexed { index, candidate ->
            val labelRes = when (candidate) {
                // "Allure" renommée "Forme" (conception 2 section 2, trop proche de "Vitesse").
                AnalysisColoring.PACE -> R.string.journal_analysis_coloring_form
                AnalysisColoring.SLOPE -> R.string.journal_analysis_coloring_slope
                AnalysisColoring.SPEED -> R.string.journal_analysis_coloring_speed
            }
            SegmentedButton(
                selected = coloring == candidate,
                onClick = { onColoringChanged(candidate) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = available.size),
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
}

/**
 * RIC-146 lot 7 (brief Partie D.3) : bascule à deux choix Distance/Durée de l'axe du profil.
 * N'existe pas sur une trace sans horodatage : l'appelant ne la monte alors pas du tout, voir
 * [AnalysisLegendAxisRow].
 */
/**
 * RIC-146 lot 8 (finition B4) : premier essai avec `SingleChoiceSegmentedButtonRow`
 * (`LocalMinimumInteractiveComponentSize` à 0, hauteur 32 dp, texte labelSmall) mesuré encore trop
 * large en vérification à 360 points : la ligne légende + axe passait sur deux lignes même en
 * français (le composant Material3 garde un padding interne incompressible par ces leviers-là).
 * Remplacé par une bascule maison, mêmes deux segments, sans les marges internes généreuses du
 * composant Material3 : c'est ce qui manquait pour tenir sur une ligne.
 */
private val CompactAxisSelectorHeight = 28.dp

@Composable
internal fun AnalysisAxisSelector(
    axis: ElevationProfileAxis,
    onAxisChanged: (ElevationProfileAxis) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(CompactAxisSelectorHeight)
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)),
    ) {
        ElevationProfileAxis.entries.forEach { candidate ->
            val labelRes = when (candidate) {
                ElevationProfileAxis.DISTANCE -> R.string.journal_analysis_axis_distance_label
                ElevationProfileAxis.DURATION -> R.string.journal_analysis_axis_duration_label
            }
            val selected = axis == candidate
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(onClick = { onAxisChanged(candidate) })
                    .padding(horizontal = 8.dp),
            ) {
                Text(stringResource(labelRes), maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * RIC-146 lot 7 (brief Partie D.3, maquette v5) : légende compacte à gauche (cinq pastilles entre
 * deux mots, selon la coloration), choix de l'axe à droite. Remplace la légende flottante sur la
 * carte (brief Partie E, "plus de légende sur la carte") et le sélecteur d'axe accolé à la
 * coloration du lot 4 (désormais sur sa propre ligne, sous le sélecteur de coloration pleine
 * largeur, brief §4.2/§4.3).
 *
 * RIC-146 lot 7, deux défauts visuels trouvés en vérification à 360 points, en anglais (le plus
 * défavorable des deux langues sur cette ligne : "Duration" plus long que "Durée") :
 *   1. une largeur fixe pour la bascule d'axe coupait "Duration" ("Distanc"/"Duratio").
 *   2. donner sa largeur naturelle à la bascule d'axe (non tronquée) et le reste à la légende via
 *      un `weight` compressait alors la légende à presque rien : "faster" se coupait lettre par
 *      lettre ("f"/"a"/"s"/"t"/"e"/"r").
 * Correction : `FlowRow` plutôt qu'un `Row` à somme fixe. Légende et bascule gardent CHACUNE sa
 * largeur naturelle, jamais compressées ; si les deux ne tiennent pas sur une ligne à 360 points,
 * la bascule (ajoutée en second) passe seule à la ligne suivante plutôt que de rogner l'une ou
 * l'autre. Aucun libellé individuel ne se coupe ni ne passe sur deux lignes, seule cette ligne-ci
 * peut en occuper deux (la maquette v5, plus large, tenait sur une seule).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnalysisLegendAxisRow(
    coloring: AnalysisColoring,
    hasTimestamps: Boolean,
    axis: ElevationProfileAxis,
    onAxisChanged: (ElevationProfileAxis) -> Unit,
    modifier: Modifier = Modifier,
) {
    // RIC-146 lot 8 (finition B4) : espace entre légende et bascule resserré de 16 à 12 dp (avec la
    // bascule elle-même resserrée, voir AnalysisAxisSelector) pour tenir sur une ligne à 360 points.
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CompactLegend(coloring = coloring, modifier = Modifier.align(Alignment.CenterVertically))
        // Brief §5 : trace sans horodatage, la bascule n'est pas affichée du tout, l'axe restant
        // en distance.
        if (hasTimestamps) {
            AnalysisAxisSelector(axis = axis, onAxisChanged = onAxisChanged, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun CompactLegend(coloring: AnalysisColoring, modifier: Modifier = Modifier) {
    val (leftRes, rightRes, palette) = when (coloring) {
        AnalysisColoring.PACE -> Triple(R.string.journal_analysis_legend_pace_slower, R.string.journal_analysis_legend_pace_faster, AnalysisColors.pace)
        AnalysisColoring.SLOPE -> Triple(R.string.journal_analysis_legend_slope_low, R.string.journal_analysis_legend_slope_high, AnalysisColors.slope)
        AnalysisColoring.SPEED -> Triple(R.string.journal_analysis_legend_speed_low, R.string.journal_analysis_legend_speed_high, AnalysisColors.speed)
    }
    // RIC-146 lot 8 (finition B4) : pastilles et espacements resserrés (16->14 dp, 2->1.5 dp,
    // 6->4 dp) pour laisser sa place à la bascule d'axe sur la même ligne à 360 points.
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(leftRes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
            palette.forEach { color -> Box(modifier = Modifier.size(width = 14.dp, height = 6.dp).clip(RoundedCornerShape(2.dp)).background(color)) }
        }
        Text(stringResource(rightRes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// RIC-146 lot 8 (finition B1) : titre en couleur primaire, grand espace au-dessus (l'arrangement
// spacedBy(8.dp) du Column défilant de JournalScreen fournit déjà 8 dp, complétés ici à 32 dp au
// total) et espace net en dessous (12 dp) ; internal pour être réutilisée par la frise "Déroulé"
// (JournalAnalysisTimeline.kt), seule autre section du mode Analyse hors de ce fichier.
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
    )
}

// --- "Forme du jour" : tableau réel/estimé/écart puis résumé par nature de terrain (brief D.5) ------

// RIC-146 lot 7 (brief Partie D.5) : sous l'heure ou la minute, "12 min" (journal_analysis_pause_minutes),
// à partir d'une heure le format existant (StatsRows.formatDuration). Même règle que la carte, voir
// HikeMapView.formatShortDuration (dupliquée là-bas, hors composition).
// internal (pas private) : réutilisée telle quelle par la frise "Déroulé" (JournalAnalysisTimeline.kt,
// lot 8), même règle de format qu'ici et que la carte (HikeMapView.formatShortDuration).
@Composable
internal fun formatPauseDuration(seconds: Double): String {
    val totalMinutes = (seconds / 60.0).roundToInt()
    return if (totalMinutes < 60) {
        stringResource(R.string.journal_analysis_pause_minutes, totalMinutes.toString())
    } else {
        formatDuration(totalMinutes)
    }
}

/** Signe de l'écart du tableau "Forme du jour" (brief §D.5) : fonction pure, testée directement par
 * AnalysisFormGapSignTest. `PLUS` sur un écart nul (réel = estimé), choix arbitraire sans effet
 * visible (magnitude alors "0 min"). */
internal enum class FormGapSign { MINUS, PLUS }

internal fun formGapSignFor(deltaSeconds: Double): FormGapSign = if (deltaSeconds < 0.0) FormGapSign.MINUS else FormGapSign.PLUS

@Composable
private fun formGapText(deltaSeconds: Double): String {
    val magnitude = formatPauseDuration(abs(deltaSeconds))
    return when (formGapSignFor(deltaSeconds)) {
        FormGapSign.MINUS -> stringResource(R.string.journal_analysis_form_gap_minus, magnitude)
        FormGapSign.PLUS -> stringResource(R.string.journal_analysis_form_gap_plus, magnitude)
    }
}

@Composable
private fun FormTableRow(label: String, actual: String, estimated: String, gap: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = style, color = color, modifier = Modifier.weight(1.6f))
        Text(text = actual, style = style, color = color, modifier = Modifier.weight(1f))
        Text(text = estimated, style = style, color = color, modifier = Modifier.weight(1f))
        Text(text = gap, style = style, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FormTable(totals: AnalysisTotals, estimate: com.bivouac.app.data.gpx.AnalysisEstimate) {
    // RIC-146 lot 8 (finition B2) : interligne porté de 6 à 10 dp (fourchette demandée 10-12 dp).
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FormTableRow(
            label = "",
            actual = stringResource(R.string.journal_analysis_form_actual),
            estimated = stringResource(R.string.journal_analysis_form_estimated),
            gap = stringResource(R.string.journal_analysis_form_gap),
            header = true,
        )
        val durationDelta = totals.elapsedSeconds - estimate.totalSeconds
        FormTableRow(
            label = stringResource(R.string.journal_analysis_form_duration),
            actual = formatDuration((totals.elapsedSeconds / 60.0).roundToInt()),
            estimated = formatDuration((estimate.totalSeconds / 60.0).roundToInt()),
            gap = formGapText(durationDelta),
        )
        val walkingDelta = totals.movingSeconds - estimate.walkingSeconds
        FormTableRow(
            label = stringResource(R.string.journal_analysis_form_walking),
            actual = formatDuration((totals.movingSeconds / 60.0).roundToInt()),
            estimated = formatDuration((estimate.walkingSeconds / 60.0).roundToInt()),
            gap = formGapText(walkingDelta),
        )
        val pausesDelta = totals.pausedSeconds - estimate.pausedSeconds
        FormTableRow(
            label = stringResource(R.string.journal_analysis_form_breaks, formatGroupedInt(totals.pauseCount)),
            actual = formatDuration((totals.pausedSeconds / 60.0).roundToInt()),
            estimated = formatDuration((estimate.pausedSeconds / 60.0).roundToInt()),
            gap = formGapText(pausesDelta),
        )
    }
}

/**
 * RIC-146 lot 5 (brief Partie A.4), réutilisée lot 7 pour le résumé par nature de terrain : géométrie
 * de la barre d'écart centrée, en fonction pure (pas de `@Composable`, pas de couleur résolue) pour
 * rester testable en JVM. [halfFraction] : 0..1, la part de CHAQUE MOITIÉ de piste que la barre
 * occupe (donc jusqu'à 50 % de la largeur totale). [slower] : `null` sans écart exploitable ; sinon
 * vrai si plus lent que la référence (barre vers la gauche), faux si plus rapide (vers la droite).
 * [paceClass] : 0-4 (voir [com.bivouac.app.data.gpx.AnalysisParameters.paceClassOf]), `null` sans
 * écart exploitable.
 */
internal data class PaceBarGeometry(val halfFraction: Float, val slower: Boolean?, val paceClass: Int?)

internal fun paceBarGeometryFor(hikeSpeedKmh: Double?, referenceSpeedKmh: Double?, referenceSource: ReferenceSource): PaceBarGeometry {
    val deviationPercent = if (hikeSpeedKmh != null && referenceSpeedKmh != null && referenceSpeedKmh > 0 && referenceSource != ReferenceSource.NONE) {
        (hikeSpeedKmh / referenceSpeedKmh - 1.0) * 100.0
    } else {
        null
    }
    val halfFraction = deviationPercent?.let { (abs(it) / 50.0).toFloat().coerceIn(0f, 1f) } ?: 0f
    return PaceBarGeometry(
        halfFraction = halfFraction,
        slower = deviationPercent?.let { it < 0.0 },
        paceClass = deviationPercent?.let { com.bivouac.app.data.gpx.AnalysisParameters.DEFAULT.paceClassOf(it) },
    )
}

@Composable
private fun PaceDeviationBar(geometry: PaceBarGeometry) {
    val barColor = geometry.paceClass?.let { AnalysisColors.pace[it] } ?: MaterialTheme.colorScheme.outlineVariant
    // RIC-146 lot 8 (finition B3) : piste portée de 6 à 8 dp de haut, coins plus arrondis en
    // conséquence ; surfaceContainerHighest reste le fond (déjà theme-aware, visible en clair et en
    // sombre par construction du schéma Material).
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        // Trait central : repère du "0 %" (référence égalée), toujours visible, même sans écart.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(1.5.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSurfaceVariant),
        )
        if (geometry.halfFraction > 0f) {
            val slower = geometry.slower == true
            val halfBoxAlignment = if (slower) Alignment.CenterStart else Alignment.CenterEnd
            val fillAlignment = if (slower) Alignment.CenterEnd else Alignment.CenterStart
            Box(modifier = Modifier.fillMaxWidth(0.5f).fillMaxHeight().align(halfBoxAlignment)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(geometry.halfFraction)
                        .fillMaxHeight()
                        .align(fillAlignment)
                        .background(barColor),
                )
            }
        }
    }
}

@Composable
private fun TerrainSummaryRow(kind: TimelinePhaseKind, summary: TerrainSummary) {
    val labelRes = when (kind) {
        TimelinePhaseKind.CLIMB -> R.string.journal_analysis_terrain_climbs
        TimelinePhaseKind.DESCENT -> R.string.journal_analysis_terrain_descents
        else -> R.string.journal_analysis_terrain_flat
    }
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodySmall)
            val hikeText = summary.movingSpeedKmh?.let { stringResource(R.string.settings_speed_value_format, formatKm1(it)) } ?: "-"
            val referenceText = summary.referenceSpeedKmh?.let { stringResource(R.string.settings_speed_value_format, formatKm1(it)) } ?: "-"
            Text(text = "$hikeText / $referenceText", style = MaterialTheme.typography.bodySmall)
        }
        val geometry = paceBarGeometryFor(
            hikeSpeedKmh = summary.movingSpeedKmh,
            referenceSpeedKmh = summary.referenceSpeedKmh,
            referenceSource = if (summary.referenceSpeedKmh != null) ReferenceSource.REFERENCE else ReferenceSource.NONE,
        )
        PaceDeviationBar(geometry)
    }
}

@Composable
internal fun AnalysisFormSection(totals: AnalysisTotals, estimate: com.bivouac.app.data.gpx.AnalysisEstimate, terrainSummary: List<TerrainSummary>) {
    Column {
        // RIC-146 lot 8 (finition B1) : plus de padding top ici, SectionTitle porte déjà l'espace
        // net en dessous du titre (12 dp).
        SectionTitle(stringResource(R.string.journal_analysis_section_form))
        FormTable(totals, estimate)
        Column(modifier = Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            terrainSummary.filter { it.distanceMeters > 0 }.forEach { summary -> TerrainSummaryRow(summary.kind, summary) }
        }
        Text(
            text = stringResource(R.string.journal_analysis_pace_by_slope_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// --- "Chiffres" : six lignes, réduites à quatre sans horodatage (brief D.6) -------------------------

/** Les six lignes possibles de la section "Chiffres" (brief §D.6), dans l'ordre de la maquette. */
internal enum class FigureRowKind { MOVING_SPEED, CLIMBING_SPEED, HIGHEST, LOWEST, STEEPEST_CLIMB, STEEPEST_DESCENT }

/**
 * Quelles lignes affiche la section "Chiffres" (brief §D.6, §5) : fonction pure, extraite
 * d'[AnalysisFiguresSection] pour être testée directement par FigureRowsTest. [hasTimestamps] faux
 * (trace sans horodatage) : réduite aux altitudes et aux pentes, les deux vitesses supposant un
 * temps de marche qui n'existe pas alors.
 */
internal fun figureRowsFor(hasTimestamps: Boolean): List<FigureRowKind> = if (hasTimestamps) {
    listOf(
        FigureRowKind.MOVING_SPEED,
        FigureRowKind.CLIMBING_SPEED,
        FigureRowKind.HIGHEST,
        FigureRowKind.LOWEST,
        FigureRowKind.STEEPEST_CLIMB,
        FigureRowKind.STEEPEST_DESCENT,
    )
} else {
    listOf(FigureRowKind.HIGHEST, FigureRowKind.LOWEST, FigureRowKind.STEEPEST_CLIMB, FigureRowKind.STEEPEST_DESCENT)
}

@Composable
private fun FigureRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun AnalysisFiguresSection(totals: AnalysisTotals?, figures: TrackFigures, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SectionTitle(stringResource(R.string.journal_analysis_section_figures))
        // RIC-146 lot 8 (finition B2) : interligne porté de 8 à 10 dp ; plus de padding top, voir
        // le commentaire de AnalysisFormSection.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            figureRowsFor(hasTimestamps = totals != null).forEach { kind ->
                when (kind) {
                    FigureRowKind.MOVING_SPEED -> FigureRow(
                        stringResource(R.string.journal_analysis_figure_moving_speed),
                        stringResource(R.string.settings_speed_value_format, formatKm1(totals!!.movingSpeedKmh)),
                    )
                    FigureRowKind.CLIMBING_SPEED -> FigureRow(
                        stringResource(R.string.journal_analysis_figure_climbing_speed),
                        totals!!.averageClimbingSpeedMetersPerHour?.let {
                            stringResource(R.string.fmt_bilan_record_value_vam, formatGroupedInt(it.roundToInt()))
                        } ?: "-",
                    )
                    FigureRowKind.HIGHEST -> FigureRow(
                        stringResource(R.string.journal_analysis_figure_highest),
                        figures.highestElevationMeters?.let { stringResource(R.string.format_elevation_meters, formatGroupedInt(it.roundToInt())) } ?: "-",
                    )
                    FigureRowKind.LOWEST -> FigureRow(
                        stringResource(R.string.journal_analysis_figure_lowest),
                        figures.lowestElevationMeters?.let { stringResource(R.string.format_elevation_meters, formatGroupedInt(it.roundToInt())) } ?: "-",
                    )
                    FigureRowKind.STEEPEST_CLIMB -> FigureRow(
                        stringResource(R.string.journal_analysis_figure_steepest_climb),
                        figures.steepestClimbPercent?.let { stringResource(R.string.settings_pause_percent_value, it.roundToInt()) } ?: "-",
                    )
                    FigureRowKind.STEEPEST_DESCENT -> FigureRow(
                        stringResource(R.string.journal_analysis_figure_steepest_descent),
                        figures.steepestDescentPercent?.let { stringResource(R.string.settings_pause_percent_value, it.roundToInt()) } ?: "-",
                    )
                }
            }
        }
    }
}

// --- "Meilleurs passages" et "Records" (brief D.7/D.8, inchangés depuis le lot 3) -------------------

@Composable
internal fun BestPassagesSection(best: com.bivouac.app.data.gpx.BestPassages) {
    Column {
        SectionTitle(stringResource(R.string.journal_analysis_section_best))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
internal fun RecordsSection(records: List<com.bivouac.app.bilan.BilanRecord>) {
    Column {
        SectionTitle(stringResource(R.string.journal_analysis_section_records))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

// --- Ligne de lecture, sous le profil, en mode Analyse (brief lot 4 §7, inchangée) ------------------

// Pente signée : "+1%"/"-5%" (settings_pause_percent_value gère déjà le signe négatif via %d, seul
// le "+" du cas positif ou nul manque). Pas d'espace avant le "%" : même formatage que la légende.
@Composable
private fun formatSignedSlopePercent(percent: Double): String {
    val rounded = percent.roundToInt()
    val formatted = stringResource(R.string.settings_pause_percent_value, rounded)
    return if (rounded >= 0) "+$formatted" else formatted
}

// Écart d'allure en valeur absolue : le sens (plus lent/plus rapide) est déjà dans le libellé qui
// suit (journal_analysis_readout_slower/faster), la chaîne n'a donc pas à porter de signe.
@Composable
private fun deviationPercentText(movingSpeedKmh: Double?, referenceSpeedKmh: Double?): String {
    if (movingSpeedKmh == null || referenceSpeedKmh == null || referenceSpeedKmh <= 0.0) return "-"
    val deviation = ((movingSpeedKmh / referenceSpeedKmh) - 1.0) * 100.0
    return stringResource(R.string.settings_pause_percent_value, abs(deviation).roundToInt())
}

/**
 * RIC-146 lot 4 (brief §7) : quelle formule de la ligne de lecture suit la classe d'allure d'un
 * tronçon. Extrait de [AnalysisReadoutLine] en fonction pure (pas de `@Composable`, pas de
 * `stringResource`) pour rester testable en JVM : `internal`, testé directement par
 * AnalysisReadoutWordingTest.
 */
internal enum class ReadoutPaceWording { SLOWER, USUAL, FASTER, STOPPED }

/** [paceClass] : 0-4 (voir [com.bivouac.app.data.gpx.AnalysisParameters.paceClassOf]), `null` pour
 * un tronçon sans vitesse exploitable. Classe 2 = milieu des cinq, "rythme habituel" (brief §7). */
internal fun readoutPaceWordingFor(paceClass: Int?): ReadoutPaceWording = when {
    paceClass == null -> ReadoutPaceWording.STOPPED
    paceClass in 0..1 -> ReadoutPaceWording.SLOWER
    paceClass in 3..4 -> ReadoutPaceWording.FASTER
    else -> ReadoutPaceWording.USUAL
}

/**
 * RIC-146 lot 4 (brief §7) : ce que le geste sur le profil raconte du tronçon sous le doigt.
 * [cursorIndex] : même index que la carte et le profil (écran, toutes journées concaténées).
 *
 * Distance et altitude reprises de la série commune concaténée ([TrackStatsCalculator.series],
 * mêmes [seriesBreaks] que [com.bivouac.app.ui.components.ElevationProfile]) et non de
 * [com.bivouac.app.data.gpx.AnalyzedPause.distanceMeters] (local à son jour) : c'est cette série qui
 * positionne la courbe sous le doigt, la ligne de lecture doit décrire exactement ce point-là,
 * y compris sur une sortie de plusieurs jours.
 *
 * Un tronçon sans vitesse exploitable (movingSpeedKmh nul) affiche "-" à la place de la vitesse dans
 * la première ligne plutôt que de supprimer la ligne, la conception ne prévoyant pas de variante
 * plus courte du format.
 */
@Composable
internal fun AnalysisReadoutLine(
    cursorIndex: Int?,
    result: JournalAnalysisResult?,
    points: List<TrackPoint>,
    dayPointCounts: List<Int>,
    seriesBreaks: Set<Int>,
    modifier: Modifier = Modifier,
) {
    val analysis = result?.analysis
    val segmentAndPoint = if (cursorIndex == null || analysis == null || analysis.totals == null) {
        null
    } else {
        val dayOffsets = TrackAnalysisMapMapping.dayOffsets(dayPointCounts)
        val (dayIndex, localIndex) = TrackAnalysisMapMapping.dayAndLocalIndex(dayOffsets, cursorIndex)
        val segment = analysis.days.getOrNull(dayIndex)?.segments?.let { TrackAnalysisMapMapping.segmentAt(it, localIndex) }
        val point = points.getOrNull(cursorIndex)
        if (segment != null && point?.time != null) segment to point else null
    }
    val series = remember(points, seriesBreaks) { TrackStatsCalculator.series(points, seriesBreaks) }
    val distanceKm = cursorIndex?.let { series.cumulativeDistanceMeters.getOrNull(it) }?.div(1000.0)
    val elevation = cursorIndex?.let { series.smoothedElevationMeters?.getOrNull(it) }

    if (segmentAndPoint == null || distanceKm == null || elevation == null) {
        Text(
            text = stringResource(R.string.journal_analysis_readout_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val (segment, point) = segmentAndPoint
    val line1 = stringResource(
        R.string.journal_analysis_readout_section,
        formatKm1(distanceKm),
        formatTimeOfDay(point.time!!),
        stringResource(R.string.format_elevation_meters, formatGroupedInt(elevation.roundToInt())),
        formatSignedSlopePercent(segment.netSlopePercent),
        segment.movingSpeedKmh?.let { stringResource(R.string.settings_speed_value_format, formatKm1(it)) } ?: "-",
    )
    val deviationText = deviationPercentText(segment.movingSpeedKmh, segment.referenceSpeedKmh)
    val line2 = when (readoutPaceWordingFor(segment.paceClass)) {
        ReadoutPaceWording.STOPPED -> stringResource(R.string.journal_analysis_readout_stopped)
        ReadoutPaceWording.SLOWER -> stringResource(R.string.journal_analysis_readout_slower, deviationText)
        ReadoutPaceWording.FASTER -> stringResource(R.string.journal_analysis_readout_faster, deviationText)
        ReadoutPaceWording.USUAL -> stringResource(R.string.journal_analysis_readout_usual)
    }
    Column(modifier = modifier) {
        Text(text = line1, style = MaterialTheme.typography.bodySmall)
        Text(text = line2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

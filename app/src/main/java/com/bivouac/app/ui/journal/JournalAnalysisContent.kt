package com.bivouac.app.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.journal.JournalAnalysisResult
import com.bivouac.app.ui.components.DurationIconColor
import com.bivouac.app.ui.components.ElevationProfileAxis
import com.bivouac.app.ui.components.formatDuration
import com.bivouac.app.ui.components.formatGroupedInt
import com.bivouac.app.ui.components.formatKm1
import com.bivouac.app.ui.map.AnalysisColoring
import com.bivouac.app.ui.map.AnalysisColors
import java.time.Instant
import kotlin.math.abs
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

// --- Sélecteur de coloration et bascule d'axe, cran Profil (brief lot 3 §4, lot 4 §5) --------------
//
// RIC-146 lot 4 correction 2 : remplace les FilterChip du lot 3 par le composant segmenté déjà
// utilisé pour le choix Manuel/Auto/Sélection des Réglages (SettingsScreen.SpeedCalibrationSection),
// pour la cohérence de l'app (brief §Partie A.2). SegmentedButton reste @ExperimentalMaterial3Api,
// comme dans les Réglages.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalysisColoringSelector(
    coloring: AnalysisColoring,
    onColoringChanged: (AnalysisColoring) -> Unit,
    hasTimestamps: Boolean,
    modifier: Modifier = Modifier,
) {
    // Brief lot 3 §5 : trace sans horodatage, seule la coloration Pente est proposée.
    val available = if (hasTimestamps) AnalysisColoring.entries else listOf(AnalysisColoring.SLOPE)
    // RIC-146 lot 4 : chaque SegmentedButton se répartit la largeur qu'on offre à ce
    // SingleChoiceSegmentedButtonRow (comportement du composant M3, voir le commentaire de
    // AnalysisProfileControlsRow pour la contrainte que ça impose côté appelant).
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        available.forEachIndexed { index, candidate ->
            val labelRes = when (candidate) {
                AnalysisColoring.PACE -> R.string.journal_analysis_coloring_pace
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
 * RIC-146 lot 4 (brief §5) : bascule à deux choix km/h de l'axe du profil, sur la même ligne que
 * [AnalysisColoringSelector], à sa droite (voir [AnalysisProfileControlsRow]). N'existe pas sur une
 * trace sans horodatage (brief §5, "la bascule n'est pas affichée, l'axe reste en distance") :
 * l'appelant ne la monte alors pas du tout, voir [AnalysisProfileControlsRow].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalysisAxisSelector(
    axis: ElevationProfileAxis,
    onAxisChanged: (ElevationProfileAxis) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        ElevationProfileAxis.entries.forEachIndexed { index, candidate ->
            val (labelRes, descriptionRes) = when (candidate) {
                ElevationProfileAxis.DISTANCE -> R.string.journal_analysis_axis_distance to R.string.journal_analysis_axis_distance_description
                ElevationProfileAxis.DURATION -> R.string.journal_analysis_axis_time to R.string.journal_analysis_axis_time_description
            }
            // contentDescription posé sur le bouton entier : "km"/"h" seuls ne disent rien en
            // lecture d'écran, la chaîne dédiée si (brief §5, chaînes "AJOUT DU PILOTAGE pour la
            // lecture d'écran").
            val description = stringResource(descriptionRes)
            SegmentedButton(
                selected = axis == candidate,
                onClick = { onAxisChanged(candidate) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ElevationProfileAxis.entries.size),
                label = { Text(stringResource(labelRes)) },
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}

/**
 * RIC-146 lot 4 (brief §5) : le sélecteur de coloration et, s'il y a lieu, la bascule d'axe, sur la
 * même ligne (maquette validée du 2026-09-28, planche section Profil). Remplace l'appel direct à
 * [AnalysisColoringSelector] du lot 3 dans ThreeStopJournalDetail.
 */
@Composable
internal fun AnalysisProfileControlsRow(
    coloring: AnalysisColoring,
    onColoringChanged: (AnalysisColoring) -> Unit,
    hasTimestamps: Boolean,
    axis: ElevationProfileAxis,
    onAxisChanged: (ElevationProfileAxis) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // RIC-146 lot 4, défaut visuel trouvé en vérification (brief Partie D) : chaque
        // SegmentedButton se partage la largeur que son SingleChoiceSegmentedButtonRow reçoit
        // (comportement du composant M3, pas de notre fait) plutôt que de ne prendre que la
        // largeur nécessaire à son contenu. Dans un Row sans poids, le premier enfant mesuré
        // (AnalysisColoringSelector) recevait alors TOUTE la largeur de la ligne et la consommait
        // en entier, ne laissant presque rien à la bascule d'axe mesurée ensuite : "km"/"h" se
        // coupaient en deux lignes ("k"/"m"). Solution : mesurer la bascule d'axe EN PREMIER, avec
        // une largeur fixe généreuse (garantit qu'elle ne se coupe jamais, quel que soit le reste),
        // puis laisser le sélecteur de coloration prendre tout ce qu'il reste (weight(1f,
        // fill = false) : il ne demande que ce qu'il lui faut, mais ne peut plus déborder sur la
        // bascule puisqu'elle a déjà réservé sa place).
        AnalysisColoringSelector(
            coloring = coloring,
            onColoringChanged = onColoringChanged,
            hasTimestamps = hasTimestamps,
            modifier = Modifier.weight(1f, fill = false),
        )
        // Brief §5 : trace sans horodatage, la bascule n'est pas affichée du tout (pas seulement
        // désactivée), l'axe restant en distance.
        if (hasTimestamps) {
            AnalysisAxisSelector(axis = axis, onAxisChanged = onAxisChanged, modifier = Modifier.width(150.dp))
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
        // RIC-146 lot 5 (brief Partie A.2) : couleur explicite, assortie au fond `colorScheme.surface`
        // juste au-dessus. Sans elle, ce Text retombe sur le LocalContentColor ambiant : cette
        // légende flotte directement sur la carte, hors de tout Surface qui l'aurait fixé au thème
        // (contrairement à LegendCaption plus bas, déjà explicite) ; l'ambiant valait alors le noir
        // par défaut de Compose, illisible sur un fond sombre en thème sombre (mais invisible en
        // thème clair, d'où le défaut signalé uniquement là).
        Text(text = stringResource(titleRes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            palette.forEach { color ->
                Box(modifier = Modifier.size(width = 20.dp, height = 6.dp).background(color))
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            when (coloring) {
                AnalysisColoring.PACE -> {
                    LegendCaption(stringResource(R.string.journal_analysis_legend_pace_slower))
                    LegendCaption(stringResource(R.string.journal_analysis_legend_pace_usual))
                    LegendCaption(stringResource(R.string.journal_analysis_legend_pace_faster))
                }
                AnalysisColoring.SLOPE -> {
                    // Brief §3 : "libellés des extrémités" ; bornes = classes de couleur (pas les
                    // onze bandes de référence), mêmes seuils que AnalysisParameters.slopeClassOf.
                    val bounds = AnalysisParameters.SLOPE_CLASS_BOUNDS_PERCENT
                    LegendCaption(
                        stringResource(
                            R.string.journal_analysis_legend_below,
                            stringResource(R.string.settings_pause_percent_value, bounds.first().toInt()),
                        ),
                    )
                    LegendCaption(
                        stringResource(
                            R.string.journal_analysis_legend_above,
                            stringResource(R.string.settings_pause_percent_value, bounds.last().toInt()),
                        ),
                    )
                }
                AnalysisColoring.SPEED -> {
                    val bounds = AnalysisParameters.SPEED_CLASS_BOUNDS_KMH
                    LegendCaption(
                        stringResource(
                            R.string.journal_analysis_legend_below,
                            stringResource(R.string.settings_speed_value_format, formatKm1(bounds.first())),
                        ),
                    )
                    LegendCaption(
                        stringResource(
                            R.string.journal_analysis_legend_above,
                            stringResource(R.string.settings_speed_value_format, formatKm1(bounds.last())),
                        ),
                    )
                }
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

/**
 * RIC-146 lot 5 (brief Partie A.4) : géométrie de la barre d'écart centrée, en fonction pure (pas
 * de `@Composable`, pas de couleur résolue) pour rester testable en JVM, même raison que
 * [readoutPaceWordingFor] plus bas. [halfFraction] : 0..1, la part de CHAQUE MOITIÉ de piste que la
 * barre occupe (donc jusqu'à 50 % de la largeur totale, brief "plafonnée à 50 %"). [slower] :
 * `null` sans écart exploitable (pas de barre à dessiner, seul le trait central reste) ; sinon vrai
 * si cette rando est plus lente que la référence sur cette bande (barre vers la gauche, brief), faux
 * si plus rapide (vers la droite). [paceClass] : 0-4 (voir [AnalysisParameters.paceClassOf]),
 * `null` sans écart exploitable (le composant retombe alors sur une couleur neutre indépendante de
 * la palette).
 */
internal data class PaceBarGeometry(val halfFraction: Float, val slower: Boolean?, val paceClass: Int?)

internal fun paceBarGeometryFor(band: AnalysisBand): PaceBarGeometry {
    val deviationPercent = if (
        band.hikeSpeedKmh != null && band.referenceSpeedKmh != null &&
        band.referenceSpeedKmh > 0 && band.referenceSource != ReferenceSource.NONE
    ) {
        (band.hikeSpeedKmh / band.referenceSpeedKmh - 1.0) * 100.0
    } else {
        null
    }
    val halfFraction = deviationPercent?.let { (abs(it) / 50.0).toFloat().coerceIn(0f, 1f) } ?: 0f
    return PaceBarGeometry(
        halfFraction = halfFraction,
        slower = deviationPercent?.let { it < 0.0 },
        paceClass = deviationPercent?.let { AnalysisParameters.DEFAULT.paceClassOf(it) },
    )
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
        // RIC-146 lot 5 (brief Partie A.4, maquette validée) : barre centrée sur un trait vertical
        // au milieu de la piste, plutôt que partant de la gauche (défaut cap05-final-details-top.png,
        // qui contredisait le texte d'aide "Barre à gauche : plus lent"). Écart signé (même formule
        // que TrackAnalysisCalculator.paceClass, "hikeSpeedKmh / referenceSpeedKmh - 1") : négatif
        // (plus lent) étale la barre vers la gauche du trait, positif (plus rapide) vers la droite.
        // Longueur proportionnelle à l'écart en %, plafonnée à 50 % de la largeur TOTALE de la piste
        // (brief) : dans chaque moitié de piste (elle-même 50 % de la largeur), la barre occupe
        // jusqu'à 100 % de cette moitié à 50 points d'écart ou plus. Géométrie calculée par
        // paceBarGeometryFor (fonction pure, testée séparément) : ce composant ne fait plus que la
        // dessiner.
        val geometry = paceBarGeometryFor(band)
        // Classe d'allure de l'écart : couleur neutre dans la classe du milieu (brief), déjà vraie
        // par construction puisque AnalysisColors.pace[2] == AnalysisColors.neutral.
        val barColor = geometry.paceClass?.let { AnalysisColors.pace[it] } ?: MaterialTheme.colorScheme.outlineVariant
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(3.dp))
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

// RIC-146 lot 4 correction 3 (brief Partie A.3) : sous l'heure, "12 min" (journal_analysis_pause_minutes,
// ajoutée à l'inventaire v14 pour cette correction) ; à partir d'une heure, le format existant
// (StatsRows.formatDuration). Même règle sur la carte, voir HikeMapView.formatShortDuration
// (dupliquée là-bas, hors composition : pas de fonction commune simple entre les deux).
@Composable
private fun formatPauseDuration(seconds: Double): String {
    val totalMinutes = (seconds / 60.0).roundToInt()
    return if (totalMinutes < 60) {
        stringResource(R.string.journal_analysis_pause_minutes, totalMinutes.toString())
    } else {
        formatDuration(totalMinutes)
    }
}

@Composable
private fun PauseRow(timed: TimedPause) {
    val pause = timed.pause
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            timed.time?.let { Text(text = formatTimeOfDay(it), style = MaterialTheme.typography.bodySmall) }
            Text(
                text = formatPauseDuration(pause.seconds),
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

// --- Ligne de lecture, sous le profil, en mode Analyse (brief lot 4 §7) -----------------------------

// Pente signée : "+1%"/"-5%" (settings_pause_percent_value gère déjà le signe négatif via %d, seul
// le "+" du cas positif ou nul manque). Pas d'espace avant le "%" : même formatage que la légende
// (journal_analysis_legend_below/above, lot 3), pour rester cohérent dans l'écran.
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
 * [AnalyzedPause.distanceMeters] (local à son jour, brief non concerné ici) : c'est cette série qui
 * positionne la courbe sous le doigt, la ligne de lecture doit décrire exactement ce point-là,
 * y compris sur une sortie de plusieurs jours.
 *
 * Choix non tranché par la conception, pris ici par prudence : un tronçon sans vitesse exploitable
 * (movingSpeedKmh nul, brief §7 "tronçon sans vitesse exploitable") affiche "-" à la place de la
 * vitesse dans la première ligne plutôt que de supprimer la ligne, la conception ne prévoyant pas
 * de variante plus courte du format ; voir le rapport du lot.
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

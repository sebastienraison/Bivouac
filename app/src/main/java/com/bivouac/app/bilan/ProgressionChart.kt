package com.bivouac.app.bilan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.bivouac.app.ui.components.formatGroupedInt
import com.bivouac.app.ui.components.formatKm1
import kotlin.math.ceil

private val COLUMN_WIDTH = 20.dp
private val BAR_WIDTH = 14.dp
private val CHART_HEIGHT = 80.dp

// RIC-215 : marge à droite des valeurs d'axe (celle qui existait déjà). La largeur de la colonne
// n'est plus fixe : elle se calcule sur le plus large des libellés affichés, voir axisColumnWidth.
private val AXIS_END_PADDING = 6.dp

// Largeur minimale de la colonne d'axe : l'ancienne valeur fixe. Sans minimum, le bord gauche des
// barres bougeait d'un onglet à l'autre selon la longueur des valeurs ; 34 dp suffit aux petites
// valeurs, la colonne ne s'élargit qu'au-delà.
internal const val AXIS_MIN_WIDTH_DP = 34f

/**
 * RIC-215 : largeur de la colonne d'axe = le plus large des libellés réellement affichés (mesurés)
 * plus la marge, jamais sous [minWidth]. Une largeur fixe (34dp) coupait « 3 853 » en « 3 85 » dès
 * que la valeur dépassait quatre chiffres ; le minimum garde un bord gauche stable entre onglets.
 */
internal fun axisColumnWidth(labelWidths: List<Float>, margin: Float, minWidth: Float = AXIS_MIN_WIDTH_DP): Float =
    maxOf((labelWidths.maxOrNull() ?: 0f) + margin, minWidth)

/**
 * RIC-19 §2 : barres pour les 4 métriques cumulatives, ligne pour Vitesse (moyenne, pas une somme,
 * voir [ProgressionMetric.isLine]). Défilement horizontal sur tout l'historique réel, ouvert sur les
 * mois les plus récents (comme la maquette) plutôt que sur le tout premier mois du Journal.
 *
 * Trois Row en horizontalScroll partageant le même [rememberScrollState] : seule celle du haut
 * (barres/ligne) accepte le geste, les deux du bas (labels mois, labels année) se contentent de
 * suivre le même décalage, évite deux gestes concurrents sur une seule zone de défilement
 * visuelle. Mois et année sur deux lignes séparées plutôt qu'une bascule mois/année dans la même
 * colonne : un millésime à 4 chiffres ne tient pas sur une largeur de colonne (20dp) pensée pour
 * une seule lettre de mois, il y retombait sur deux lignes.
 *
 * Graduation min/milieu/max à gauche (colonne dimensionnée par [axisColumnWidth] : elle ne
 * défile pas avec les barres) : sans elle, la hauteur des barres n'était comparable qu'entre elles, pas lisible en
 * valeur absolue. min/max calculés une seule fois ici et transmis à BarChart/LineChart plutôt que
 * recalculés dans chacun, pour que le graphique et sa graduation restent toujours d'accord.
 */
@Composable
internal fun ProgressionChart(series: ProgressionSeries, color: Color, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    LaunchedEffect(series.metric, series.points.size) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    val values = series.points.mapNotNull { it.value }
    // Barres : toujours ancrées à 0 (une somme mensuelle n'a pas de "négatif"), coerceAtLeast(1.0)
    // pour ne pas diviser par zéro si toutes les valeurs valent 0. Ligne (Vitesse, une moyenne) :
    // bornée à sa vraie plage min/max, un 0 forcé écraserait des écarts réels de quelques km/h à
    // peine visibles sur 80dp de haut.
    val minValue = if (series.metric.isLine) (values.minOrNull() ?: 0.0) else 0.0
    val maxValue = (values.maxOrNull() ?: 0.0).let { if (series.metric.isLine) it else it.coerceAtLeast(1.0) }
    Row(modifier = modifier) {
        AxisLabels(metric = series.metric, minValue = minValue, maxValue = maxValue)
        Column {
            Row(modifier = Modifier.horizontalScroll(scrollState)) {
                if (series.metric.isLine) {
                    LineChart(series, color, minValue, maxValue)
                } else {
                    BarChart(series, color, maxValue)
                }
            }
            Row(modifier = Modifier.horizontalScroll(scrollState, enabled = false)) {
                series.points.forEach { point ->
                    Box(modifier = Modifier.width(COLUMN_WIDTH), contentAlignment = Alignment.Center) {
                        Text(
                            text = monthInitial(point.yearMonth.month),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(modifier = Modifier.horizontalScroll(scrollState, enabled = false)) {
                series.points.forEachIndexed { index, point ->
                    val isYearBoundary = index == 0 || point.yearMonth.year != series.points[index - 1].yearMonth.year
                    Box(modifier = Modifier.width(COLUMN_WIDTH), contentAlignment = Alignment.Center) {
                        if (isYearBoundary) {
                            // wrapContentWidth(unbounded = true) : "2023" (4 chiffres) dépasse
                            // légèrement les 20dp d'une colonne mois (pensée pour une seule lettre),
                            // le label déborde donc visuellement de part et d'autre plutôt que de
                            // retomber sur deux lignes, sans élargir la colonne elle-même (les trois
                            // Row doivent garder exactement la même largeur totale pour rester
                            // synchronisées au défilement).
                            // RIC-196 : au tout premier point, il n'y a rien à gauche pour absorber
                            // le débordement gauche du centrage : la zone défilante le coupe net.
                            // Cette seule colonne s'aligne donc à gauche (débordement uniquement vers
                            // la droite, dans la place déjà occupée par les mois suivants). Reproduit
                            // au dernier point pour la même raison côté droit : si la série se
                            // termine sur un mois de janvier (cas réel chaque année), ce jalon-là est
                            // aussi le dernier de la zone défilante et se fait couper à droite sans ce
                            // traitement (vérifié à la date système, voir rapport). Les autres jalons
                            // d'année, au milieu de la série, gardent le centrage existant, qui a
                            // toujours de la marge des deux côtés.
                            val lastIndex = series.points.lastIndex
                            Text(
                                text = "${point.yearMonth.year}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                modifier = when (index) {
                                    0 -> Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true)
                                    lastIndex -> Modifier.wrapContentWidth(align = Alignment.End, unbounded = true)
                                    else -> Modifier.wrapContentWidth(unbounded = true)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AxisLabels(metric: ProgressionMetric, minValue: Double, maxValue: Double) {
    val maxText = formatAxisValue(metric, maxValue)
    val midText = formatAxisValue(metric, (minValue + maxValue) / 2)
    val minText = formatAxisValue(metric, minValue)
    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    // Mesure avec le style et l'échelle de police réellement appliqués aux Text ci-dessous. Arrondi
    // au pixel supérieur : un demi-pixel de moins suffirait à recouper le dernier chiffre.
    val columnWidth = remember(maxText, midText, minText, style, density) {
        with(density) {
            val widths = listOf(maxText, midText, minText).map {
                ceil(textMeasurer.measure(it, style, maxLines = 1, softWrap = false).size.width.toFloat()).toDp().value
            }
            axisColumnWidth(widths, AXIS_END_PADDING.value).dp
        }
    }
    Box(modifier = Modifier.width(columnWidth).height(CHART_HEIGHT)) {
        Text(
            text = maxText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = AXIS_END_PADDING),
        )
        Text(
            text = midText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = AXIS_END_PADDING),
        )
        Text(
            text = minText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = AXIS_END_PADDING),
        )
    }
}

private fun formatAxisValue(metric: ProgressionMetric, value: Double): String = when (metric) {
    ProgressionMetric.VITESSE -> formatKm1(value)
    else -> formatGroupedInt(value)
}

@Composable
private fun BarChart(series: ProgressionSeries, color: Color, maxValue: Double) {
    Row {
        series.points.forEach { point ->
            Box(modifier = Modifier.width(COLUMN_WIDTH).height(CHART_HEIGHT), contentAlignment = Alignment.BottomCenter) {
                val heightFraction = ((point.value ?: 0.0) / maxValue).coerceIn(0.0, 1.0)
                val barHeight = (CHART_HEIGHT.value * heightFraction).coerceAtLeast(2.0)
                Box(
                    modifier = Modifier
                        .width(BAR_WIDTH)
                        .height(barHeight.dp)
                        .background(color, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                )
            }
        }
    }
}

@Composable
private fun LineChart(series: ProgressionSeries, color: Color, minValue: Double, maxValue: Double) {
    val totalWidth = COLUMN_WIDTH * series.points.size
    val range = (maxValue - minValue).let { if (it <= 0.0) 1.0 else it }
    Canvas(modifier = Modifier.width(totalWidth).height(CHART_HEIGHT)) {
        val colWidthPx = COLUMN_WIDTH.toPx()
        val points = series.points.mapIndexedNotNull { index, point ->
            val v = point.value ?: return@mapIndexedNotNull null
            val x = index * colWidthPx + colWidthPx / 2f
            val y = (size.height - ((v - minValue) / range * size.height)).toFloat()
            Offset(x, y)
        }
        for (i in 0 until points.size - 1) {
            drawLine(color = color, start = points[i], end = points[i + 1], strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
        points.forEach { drawCircle(color = color, radius = 2.5.dp.toPx(), center = it) }
    }
}

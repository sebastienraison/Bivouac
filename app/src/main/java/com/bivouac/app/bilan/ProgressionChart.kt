package com.bivouac.app.bilan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

private val COLUMN_WIDTH = 20.dp
private val BAR_WIDTH = 14.dp
private val CHART_HEIGHT = 80.dp

/**
 * RIC-19 §2 : barres pour les 4 métriques cumulatives, ligne pour Vitesse (moyenne, pas une somme —
 * voir [ProgressionMetric.isLine]). Défilement horizontal sur tout l'historique réel, ouvert sur les
 * mois les plus récents (comme la maquette) plutôt que sur le tout premier mois du Journal.
 *
 * Deux Row en horizontalScroll partageant le même [rememberScrollState] : seule celle du haut
 * (barres/ligne) accepte le geste, celle du bas (labels mois/année) se contente de suivre le même
 * décalage — évite deux gestes concurrents sur une seule zone de défilement visuelle.
 */
@Composable
internal fun ProgressionChart(series: ProgressionSeries, color: Color, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    LaunchedEffect(series.metric, series.points.size) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Column(modifier = modifier) {
        Row(modifier = Modifier.horizontalScroll(scrollState)) {
            if (series.metric.isLine) {
                LineChart(series, color)
            } else {
                BarChart(series, color)
            }
        }
        Row(modifier = Modifier.horizontalScroll(scrollState, enabled = false)) {
            series.points.forEachIndexed { index, point ->
                val isYearBoundary = index == 0 || point.yearMonth.year != series.points[index - 1].yearMonth.year
                Box(modifier = Modifier.width(COLUMN_WIDTH), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isYearBoundary) "${point.yearMonth.year}" else monthInitial(point.yearMonth.month),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BarChart(series: ProgressionSeries, color: Color) {
    val maxValue = (series.points.mapNotNull { it.value }.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
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
private fun LineChart(series: ProgressionSeries, color: Color) {
    val totalWidth = COLUMN_WIDTH * series.points.size
    val values = series.points.mapNotNull { it.value }
    if (values.isEmpty()) {
        Box(modifier = Modifier.width(totalWidth).height(CHART_HEIGHT))
        return
    }
    val minValue = values.min()
    val maxValue = values.max()
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

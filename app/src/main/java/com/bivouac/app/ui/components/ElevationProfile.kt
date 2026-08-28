package com.bivouac.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bivouac.app.R
import com.bivouac.app.data.gpx.GeoMath
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.DayJunctions
import com.bivouac.app.data.model.TrackPoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// RIC-136 : élargi de 26 à 32dp — "999" tenait tout juste, mais une altitude groupée à 4 chiffres
// ("1 234", espace fine insécable comprise) déborderait sinon en montagne (>1000 m, terrain courant
// pour cette app).
private val LEFT_LABEL_WIDTH = 32.dp
private val BOTTOM_AXIS_HEIGHT = 14.dp

// Round "nice" values a gridline is snapped to, largest-first so the picked unit is the coarsest
// one that still resolves the ideal, evenly-spaced position without moving it too far.
private val ALTITUDE_ROUNDING_UNITS = listOf(50.0, 100.0, 250.0, 500.0, 1000.0)
private const val ALTITUDE_MIN_SPACING = 250.0
private const val MAX_INTERMEDIATE_GRIDLINES = 3

private val DISTANCE_ROUNDING_UNITS_KM = listOf(1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0)
private const val DISTANCE_MIN_SPACING_KM = 5.0
private const val MAX_INTERMEDIATE_DISTANCE_TICKS = 4

// Keeps a round gridline from landing right on top of a bivouac's own distance label.
private val COLLISION_MARGIN = 20.dp

/**
 * Intermediate gridlines between [min] and [max] (exclusive), evenly spaced first and rounded
 * second — rather than picking round multiples of a fixed step and keeping whichever fall in
 * range, which leaves the two edge gaps (min-to-first-mark, last-mark-to-max) an arbitrary size
 * next to the even spacing between the marks themselves. Picks as many marks as fit without going
 * below [minSpacing], capped at [maxCount], and rounds each to whichever unit in [roundingUnits]
 * is nearest the ideal spacing (so a mark never drifts far from its ideal, evenly-spaced
 * position, while still landing on a value that reads as round).
 *
 * [reserveEdgeMargin] additionally keeps a full spare mark-to-mark gap between the outermost
 * marks and min/max — worth it on the altitude axis, where vertical room for labels is tight, but
 * needlessly conservative on the much wider distance axis (confirmed by eye: a track's edge gap
 * can safely run a bit tighter than the interior spacing there without looking cramped).
 */
private fun evenlySpacedRoundMarks(
    min: Double,
    max: Double,
    minSpacing: Double,
    maxCount: Int,
    roundingUnits: List<Double>,
    reserveEdgeMargin: Boolean = true,
): List<Double> {
    val range = max - min
    if (range <= 0) return emptyList()
    val maxK = if (reserveEdgeMargin) floor(range / minSpacing - 1).toInt() else floor(range / minSpacing).toInt()
    val k = maxK.coerceIn(0, maxCount)
    if (k <= 0) return emptyList()
    val idealSpacing = range / (k + 1)
    val roundingUnit = roundingUnits.minByOrNull { abs(it - idealSpacing) } ?: roundingUnits.first()
    return (1..k)
        .map { i -> (min + i * idealSpacing).let { ideal -> (ideal / roundingUnit).roundToLong() * roundingUnit } }
        .distinct()
        .filter { it > min && it < max }
}

private fun formatKm(km: Double): String {
    val rounded = (km * 10).roundToInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) {
        "${rounded.toInt()}"
    } else {
        String.format(Locale.FRANCE, "%.1f", rounded)
    }
}

/**
 * Elevation profile of the whole track, with a dot for each bivouac point at its actual altitude,
 * and altitude/distance rulers. RIC-138 : un export GPX réel a parfois quelques points sans
 * altitude, épars dans le fichier — [TrackStatsCalculator.smoothedElevationSeries] les interpole
 * plutôt que d'abandonner toute la série ; ce composant ne rend donc rien seulement si AUCUN point
 * de la trace n'a d'altitude.
 *
 * [cursorIndex] (Journal-only, BIV-52) draws a synced marker at that point; tapping or
 * horizontally dragging anywhere on the plot reports the nearest point's index via
 * [onCursorDragged] — height doesn't matter, only horizontal position.
 */
@Composable
fun ElevationProfile(
    points: List<TrackPoint>,
    bivouacPoints: List<BivouacPoint>,
    modifier: Modifier = Modifier,
    cursorIndex: Int? = null,
    onCursorDragged: (Int) -> Unit = {},
    // Journal : dernier point de chaque jour qui s'achève, sur une sortie de plusieurs fichiers.
    // Vide en Planification, où la trace est d'un seul tenant.
    dayBoundaryIndices: List<Int> = emptyList(),
) {
    val elevations = remember(points) { TrackStatsCalculator.smoothedElevationSeries(points) }
    if (elevations == null || elevations.size < 2) return

    // Distance-based, not index-based: GPS point density varies along a track (denser on slow or
    // steep sections), so evenly spacing by index would visually distort the horizontal scale.
    // Les jonctions où l'enregistrement a réellement été coupé, à ne surtout pas compter comme du
    // parcours : l'axe annoncerait plus de kilomètres que les statistiques de la même vue, qui
    // sont sommées jour par jour précisément pour éviter ce trajet fictif.
    val recordingGaps = remember(points, dayBoundaryIndices) {
        DayJunctions.recordingGaps(points, dayBoundaryIndices)
    }
    val cumulativeDistances = remember(points, recordingGaps) {
        val distances = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val step = if (i - 1 in recordingGaps) {
                0.0
            } else {
                GeoMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            }
            distances[i] = distances[i - 1] + step
        }
        distances
    }
    val totalDistance = cumulativeDistances.last().coerceAtLeast(1.0)
    val totalKm = totalDistance / 1000.0

    val minElevation = elevations.min()
    val maxElevation = elevations.max()
    val range = (maxElevation - minElevation).coerceAtLeast(1.0)

    val curveColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val bivouacColor = colorResource(R.color.marker_bivouac)
    val cursorColor = colorResource(R.color.marker_cursor)
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier.fillMaxWidth().height(72.dp + BOTTOM_AXIS_HEIGHT)
            .pointerInput(cumulativeDistances, totalDistance) {
                val leftPadPx = LEFT_LABEL_WIDTH.toPx()
                val plotWidthPx = size.width - leftPadPx
                var lastReportedIndex = -1

                fun indexForX(x: Float): Int {
                    val distance = (((x - leftPadPx) / plotWidthPx).toDouble() * totalDistance).coerceIn(0.0, totalDistance)
                    var lo = 0
                    var hi = cumulativeDistances.lastIndex
                    while (lo < hi) {
                        val mid = (lo + hi) / 2
                        if (cumulativeDistances[mid] < distance) lo = mid + 1 else hi = mid
                    }
                    if (lo > 0 && abs(cumulativeDistances[lo - 1] - distance) <= abs(cumulativeDistances[lo] - distance)) return lo - 1
                    return lo
                }

                fun reportIndexAt(x: Float) {
                    val index = indexForX(x)
                    if (index != lastReportedIndex) {
                        lastReportedIndex = index
                        onCursorDragged(index)
                    }
                }

                detectDragGestures(
                    onDragStart = { offset -> reportIndexAt(offset.x) },
                    onDrag = { change, _ -> reportIndexAt(change.position.x) },
                )
            },
    ) {
        val leftPad = LEFT_LABEL_WIDTH.toPx()
        val plotWidth = size.width - leftPad
        val plotHeight = size.height - BOTTOM_AXIS_HEIGHT.toPx()
        // A transient layout pass — an ancestor's height still catching up to newly measured
        // content, for instance — can hand this Canvas less space than its own .height(...)
        // modifier above asks for. Skip that one frame rather than feed a zero/negative extent
        // into the coerceIn calls below (crashes: "maximum X is less than minimum 0"); it
        // self-corrects on the next layout pass once the ancestor catches up.
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

        fun xFor(distanceMeters: Double) = leftPad + (plotWidth * distanceMeters / totalDistance).toFloat()
        fun yFor(elevation: Double) = (plotHeight - (elevation - minElevation) / range * plotHeight).toFloat()

        fun drawCenteredLabel(text: String, x: Float, y: Float, color: Color) {
            val textWidth = textMeasurer.measure(text, labelStyle).size.width
            drawText(
                textMeasurer = textMeasurer,
                text = text,
                topLeft = Offset((x - textWidth / 2f).coerceIn(leftPad, size.width - textWidth), y),
                style = labelStyle.copy(color = color),
            )
        }

        // Altitude ruler (horizontal gridlines): exact min/max always shown, round intermediates
        // evenly spaced between them.
        val altitudeGridlines = listOf(maxElevation, minElevation) +
            evenlySpacedRoundMarks(minElevation, maxElevation, ALTITUDE_MIN_SPACING, MAX_INTERMEDIATE_GRIDLINES, ALTITUDE_ROUNDING_UNITS)
        altitudeGridlines.forEach { elevation ->
            val y = yFor(elevation)
            drawLine(
                color = gridColor,
                start = Offset(leftPad, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                alpha = 0.3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
            )
            drawText(
                textMeasurer = textMeasurer,
                text = formatGroupedInt(elevation.roundToInt()),
                topLeft = Offset(0f, (y - 6.dp.toPx()).coerceIn(0f, plotHeight - 10.dp.toPx())),
                style = labelStyle,
            )
        }

        // Curve. Une coupure d'enregistrement rompt le tracé plutôt que de le prolonger : les deux
        // points partagent la même abscisse, puisque rien n'a été parcouru entre eux, et les
        // relier d'un trait plein donnerait à lire une montée verticale qui n'a pas eu lieu.
        val path = Path().apply {
            elevations.forEachIndexed { index, elevation ->
                val x = xFor(cumulativeDistances[index])
                val y = yFor(elevation)
                if (index == 0 || index - 1 in recordingGaps) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            path = path,
            color = curveColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Le lien entre les deux bouts, en pointillé : la nuit a bien relié ces deux altitudes,
        // mais aucun trajet enregistré ne les joint.
        recordingGaps.forEach { index ->
            val next = index + 1
            if (index !in elevations.indices || next !in elevations.indices) return@forEach
            drawLine(
                color = curveColor,
                start = Offset(xFor(cumulativeDistances[index]), yFor(elevations[index])),
                end = Offset(xFor(cumulativeDistances[next]), yFor(elevations[next])),
                strokeWidth = 2.dp.toPx(),
                alpha = 0.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
        }

        // Bivouac markers: dot on the curve, drop line down to the axis, and — since that line
        // already marks the spot — its exact distance labelled right there on the axis.
        val bivouacXs = bivouacPoints.map { bivouac ->
            val index = bivouac.trackPointIndex.coerceIn(0, elevations.lastIndex)
            val x = xFor(cumulativeDistances[index])
            val y = yFor(elevations[index])
            drawLine(
                color = bivouacColor,
                start = Offset(x, y),
                end = Offset(x, plotHeight),
                strokeWidth = 1.dp.toPx(),
                alpha = 0.6f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())),
            )
            drawCircle(color = bivouacColor, radius = 4.dp.toPx(), center = Offset(x, y))
            drawCenteredLabel(formatKm(cumulativeDistances[index] / 1000.0), x, plotHeight + 2.dp.toPx(), bivouacColor)
            x
        }

        // Cursor (BIV-52): a solid line (vs. bivouacs' dashed ones) so it reads as "live" rather
        // than a fixed waypoint — distance labelled the same way a bivouac's is, for consistency.
        if (cursorIndex != null) {
            val index = cursorIndex.coerceIn(0, elevations.lastIndex)
            val x = xFor(cumulativeDistances[index])
            val y = yFor(elevations[index])
            drawLine(color = cursorColor, start = Offset(x, y), end = Offset(x, plotHeight), strokeWidth = 1.5.dp.toPx())
            drawCircle(color = cursorColor, radius = 5.dp.toPx(), center = Offset(x, y))
            drawCenteredLabel(formatKm(cumulativeDistances[index] / 1000.0), x, plotHeight + 2.dp.toPx(), cursorColor)
        }

        // Distance ruler (vertical gridlines): exact 0/total always shown, round intermediates
        // evenly spaced between them, dropped if they'd collide with a bivouac's own label.
        val collisionMarginPx = COLLISION_MARGIN.toPx()
        val intermediateKm = evenlySpacedRoundMarks(
            0.0, totalKm, DISTANCE_MIN_SPACING_KM, MAX_INTERMEDIATE_DISTANCE_TICKS, DISTANCE_ROUNDING_UNITS_KM,
            reserveEdgeMargin = false,
        )
            .filter { km -> bivouacXs.none { abs(it - xFor(km * 1000.0)) < collisionMarginPx } }

        fun drawDistanceGridline(km: Double, alignEnd: Boolean? /* null = center */) {
            val x = xFor(km * 1000.0)
            drawLine(
                color = gridColor,
                start = Offset(x, 0f),
                end = Offset(x, plotHeight),
                strokeWidth = 1.dp.toPx(),
                alpha = 0.3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
            )
            val text = formatKm(km)
            val textWidth = textMeasurer.measure(text, labelStyle).size.width
            val textX = when (alignEnd) {
                false -> leftPad
                true -> size.width - textWidth
                null -> (x - textWidth / 2f).coerceIn(leftPad, size.width - textWidth)
            }
            drawText(textMeasurer = textMeasurer, text = text, topLeft = Offset(textX, plotHeight + 2.dp.toPx()), style = labelStyle)
        }

        drawDistanceGridline(0.0, alignEnd = false)
        drawDistanceGridline(totalKm, alignEnd = true)
        intermediateKm.forEach { km -> drawDistanceGridline(km, alignEnd = null) }
    }
}

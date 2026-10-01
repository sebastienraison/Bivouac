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
import androidx.compose.ui.geometry.Size
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
import com.bivouac.app.data.gpx.TrackAnalysisMapMapping
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.gpx.TrackStatsParameters
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.DayJunctions
import com.bivouac.app.data.model.TrackPoint
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// RIC-136 : élargi de 26 à 32dp : "999" tenait tout juste, mais une altitude groupée à 4 chiffres
// ("1 234", espace fine insécable comprise) déborderait sinon en montagne (>1000 m, terrain courant
// pour cette app).
private val LEFT_LABEL_WIDTH = 32.dp
private val BOTTOM_AXIS_HEIGHT = 14.dp

// Round "nice" values a gridline is snapped to, largest-first so the picked unit is the coarsest
// one that still resolves the ideal, evenly-spaced position without moving it too far.
internal val ALTITUDE_ROUNDING_UNITS = listOf(50.0, 100.0, 250.0, 500.0, 1000.0)
internal const val ALTITUDE_MIN_SPACING = 250.0
internal const val MAX_INTERMEDIATE_GRIDLINES = 3

private val DISTANCE_ROUNDING_UNITS_KM = listOf(1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0)
private const val DISTANCE_MIN_SPACING_KM = 5.0
private const val MAX_INTERMEDIATE_DISTANCE_TICKS = 4

// Keeps a round gridline from landing right on top of a bivouac's own distance label.
private val COLLISION_MARGIN = 20.dp

// RIC-215 : distance minimale entre deux libellés de l'axe des altitudes (en plus de leur hauteur
// mesurée).
private val ALTITUDE_LABEL_GAP = 2.dp

/**
 * RIC-146 lot 4 (brief §5) : l'axe horizontal du profil. [DISTANCE] est le comportement existant,
 * inchangé. [DURATION] n'est proposé que par le mode Analyse, sur une trace horodatée (brief §5,
 * "trace sans horodatage : la bascule n'est pas affichée, l'axe reste en distance") : si les points
 * reçus n'ont pas d'horodatage exploitable, [ElevationProfile] revient silencieusement à
 * [DISTANCE] plutôt que d'afficher un axe vide.
 */
enum class ElevationProfileAxis { DISTANCE, DURATION }

/**
 * RIC-146 lot 4 (brief §4) : la couleur d'une plage de tronçons du profil, en mode Analyse. Bornes
 * en index de la liste [ElevationProfile.points] (mêmes conventions que
 * [com.bivouac.app.data.gpx.TrackAnalysisMapMapping.ColorGroup]), déjà résolues en [Color] par
 * l'appelant : [ElevationProfile] reste agnostique de la coloration et de la palette du mode
 * Analyse (partagé avec la Planification, brief "périmètre").
 */
data class ProfileColorRange(val startIndex: Int, val endIndex: Int, val color: Color)

/**
 * RIC-146 lot 4 (brief §6) : une pause à marquer sur le profil, bornes déjà en index de l'écran
 * (voir [com.bivouac.app.data.gpx.TrackAnalysisMapMapping.toScreenIndex]).
 */
data class ProfilePause(val startIndex: Int, val endIndex: Int, val seconds: Double)

// Brief §6 : "plus gros à partir de 10 minutes" (point de l'axe distance). Seuil de la conception
// (section 7.2/7.4), repris tel quel ; distinct du système à trois tailles de la carte
// (TrackAnalysisMapMapping.pauseMarkerKind), le profil n'a que deux tailles de point.
private const val PROFILE_PAUSE_LONG_SECONDS = 600.0
private val PAUSE_DOT_RADIUS = 1.5.dp
private val PAUSE_DOT_RADIUS_LONG = 3.dp
private val PAUSE_BAND_MIN_WIDTH = 2.dp

/**
 * Round "nice" clock label, device locale/zone : mêmes réglages que
 * [com.bivouac.app.ui.journal.formatTimeOfDay], dupliqué ici (comme
 * `HikeMapView.formatShortDuration` duplique `StatsRows.formatDuration`) pour ne pas faire
 * dépendre ce composant partagé (Planification comprise) du package ui.journal.
 */
private fun formatClockTime(instant: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).withZone(zone).format(instant)

/** Même formatage que [formatClockTime], pour une heure ronde sans date (graduations
 * intermédiaires, voir [com.bivouac.app.data.gpx.TrackAnalysisMapMapping.clockGridlines]). */
private fun formatClockTime(time: LocalTime): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(time)

/**
 * Intermediate gridlines between [min] and [max] (exclusive), evenly spaced first and rounded
 * second: rather than picking round multiples of a fixed step and keeping whichever fall in
 * range, which leaves the two edge gaps (min-to-first-mark, last-mark-to-max) an arbitrary size
 * next to the even spacing between the marks themselves. Picks as many marks as fit without going
 * below [minSpacing], capped at [maxCount], and rounds each to whichever unit in [roundingUnits]
 * is nearest the ideal spacing (so a mark never drifts far from its ideal, evenly-spaced
 * position, while still landing on a value that reads as round).
 *
 * [reserveEdgeMargin] additionally keeps a full spare mark-to-mark gap between the outermost
 * marks and min/max: worth it on the altitude axis, where vertical room for labels is tight, but
 * needlessly conservative on the much wider distance axis (confirmed by eye: a track's edge gap
 * can safely run a bit tighter than the interior spacing there without looking cramped).
 */
internal fun evenlySpacedRoundMarks(
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

// RIC-187 (lot 0 i18n) : Locale.FRANCE figé remplacé par Locale.getDefault(), comme dans
// NumberFormatting.kt et TotalsCapsule.kt.
private fun formatKm(km: Double): String {
    val rounded = (km * 10).roundToInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) {
        "${rounded.toInt()}"
    } else {
        String.format(Locale.getDefault(), "%.1f", rounded)
    }
}

/**
 * Elevation profile of the whole track, with a dot for each bivouac point at its actual altitude,
 * and altitude/distance rulers. RIC-138 : un export GPX réel a parfois quelques points sans
 * altitude, épars dans le fichier : [TrackStatsCalculator.series] les interpole
 * plutôt que d'abandonner toute la série ; ce composant ne rend donc rien seulement si AUCUN point
 * de la trace n'a d'altitude.
 *
 * [cursorIndex] (Journal-only, BIV-52) draws a synced marker at that point; tapping or
 * horizontally dragging anywhere on the plot reports the nearest point's index via
 * [onCursorDragged]: height doesn't matter, only horizontal position.
 *
 * RIC-146 lot 4 (brief "périmètre") : [axis], [colorRanges] et [pauses] sont propres au mode
 * Analyse du Journal, valeurs par défaut qui redonnent exactement le comportement actuel en
 * Planification et en Journal normal.
 */
@Composable
fun ElevationProfile(
    points: List<TrackPoint>,
    bivouacPoints: List<BivouacPoint>,
    modifier: Modifier = Modifier,
    cursorIndex: Int? = null,
    onCursorDragged: (Int) -> Unit = {},
    // Journal : dernier point de chaque jour qui s'achève, sur une sortie de plusieurs fichiers.
    // Planification : les bivouacs. Ne sert qu'au dessin des coupures d'enregistrement.
    dayBoundaryIndices: List<Int> = emptyList(),
    // RIC-114 : coupures de la série commune, calculées par l'appelant avec le même helper que
    // ses statistiques (DayJunctions.journalSeriesBreaks / planificationSeriesBreaks).
    seriesBreaks: Set<Int> = emptySet(),
    statsParameters: TrackStatsParameters = TrackStatsParameters.DEFAULT,
    // RIC-146 lot 4 (brief §5) : distance (défaut, comportement inchangé) ou durée. Repli silencieux
    // sur DISTANCE si la trace n'a pas d'horodatage exploitable, voir elapsedSeconds plus bas.
    axis: ElevationProfileAxis = ElevationProfileAxis.DISTANCE,
    // RIC-146 lot 4 (brief §4) : coloration du tracé par tronçon. Vide = comportement inchangé (un
    // seul trait de la couleur primaire du thème).
    colorRanges: List<ProfileColorRange> = emptyList(),
    // RIC-146 lot 4 (brief §6) : marqueurs de pause. Vide = comportement inchangé (aucun marqueur).
    pauses: List<ProfilePause> = emptyList(),
) {
    // RIC-114 : courbe ET axe tirés de la série commune, celle qui produit les statistiques de la
    // même vue : l'axe finit sur la distance affichée, la courbe est celle qui a donné le D+.
    // Distance-based, not index-based: GPS point density varies along a track (denser on slow or
    // steep sections), so evenly spacing by index would visually distort the horizontal scale.
    val series = remember(points, seriesBreaks, statsParameters) {
        TrackStatsCalculator.series(points, seriesBreaks, statsParameters)
    }
    val elevations = series.smoothedElevationMeters
    if (elevations == null || elevations.size < 2) return
    val cumulativeDistances = series.cumulativeDistanceMeters

    // Les jonctions où l'enregistrement a réellement été coupé : dessinées en pointillé plutôt
    // qu'en trait plein. Elles font partie des coupures de la série, donc n'ajoutent déjà aucune
    // distance à l'axe.
    val recordingGaps = remember(points, dayBoundaryIndices) {
        DayJunctions.recordingGaps(points, dayBoundaryIndices)
    }
    val totalDistance = cumulativeDistances.last().coerceAtLeast(1.0)
    val totalKm = totalDistance / 1000.0

    // RIC-146 lot 4 (brief §5) : temps écoulé depuis le départ, jours mis bout à bout sans la nuit
    // (conception section 7.4). `null` sans horodatage exploitable : l'axe retombe alors sur
    // DISTANCE, silencieusement (brief §5, "la bascule n'est pas affichée" côté sélecteur : ce
    // repli est le filet si l'appelant demandait quand même DURATION).
    val elapsedSeconds = remember(points, dayBoundaryIndices, axis) {
        if (axis == ElevationProfileAxis.DURATION) {
            TrackAnalysisMapMapping.elapsedSecondsSinceStart(points, dayBoundaryIndices)
        } else {
            null
        }
    }
    val effectiveAxis = if (axis == ElevationProfileAxis.DURATION && elapsedSeconds != null) {
        ElevationProfileAxis.DURATION
    } else {
        ElevationProfileAxis.DISTANCE
    }
    val axisValues = if (effectiveAxis == ElevationProfileAxis.DURATION) elapsedSeconds!! else cumulativeDistances
    val totalAxisValue = axisValues.last().coerceAtLeast(1.0)
    val zone = remember { ZoneId.systemDefault() }
    val clockGridlines = remember(points, dayBoundaryIndices, zone, effectiveAxis) {
        if (effectiveAxis == ElevationProfileAxis.DURATION) {
            TrackAnalysisMapMapping.clockGridlines(points, dayBoundaryIndices, zone)
        } else {
            null
        }
    }

    val minElevation = elevations.min()
    val maxElevation = elevations.max()
    val range = (maxElevation - minElevation).coerceAtLeast(1.0)

    val curveColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val bivouacColor = colorResource(R.color.marker_bivouac)
    val cursorColor = colorResource(R.color.marker_cursor)
    val pauseColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val pauseBandColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier.fillMaxWidth().height(72.dp + BOTTOM_AXIS_HEIGHT)
            .pointerInput(axisValues, totalAxisValue) {
                val leftPadPx = LEFT_LABEL_WIDTH.toPx()
                val plotWidthPx = size.width - leftPadPx
                var lastReportedIndex = -1

                fun indexForX(x: Float): Int {
                    val value = (((x - leftPadPx) / plotWidthPx).toDouble() * totalAxisValue).coerceIn(0.0, totalAxisValue)
                    return TrackAnalysisMapMapping.nearestIndex(axisValues, value)
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
        // A transient layout pass (an ancestor's height still catching up to newly measured
        // content, for instance) can hand this Canvas less space than its own .height(...)
        // modifier above asks for. Skip that one frame rather than feed a zero/negative extent
        // into the coerceIn calls below (crashes: "maximum X is less than minimum 0"); it
        // self-corrects on the next layout pass once the ancestor catches up.
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

        fun xForDistance(distanceMeters: Double) = leftPad + (plotWidth * distanceMeters / totalDistance).toFloat()
        fun xForValue(value: Double) = leftPad + (plotWidth * value / totalAxisValue).toFloat()
        fun xForIndex(index: Int) = xForValue(axisValues[index])
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

        // RIC-146 lot 4 (brief §6) : bandes de pause de l'axe en durée, tout en bas de la pile
        // (avant la courbe et les grilles) pour qu'elles se lisent comme un fond, pas un calque
        // qui cache le reste (voir la maquette validée, coloration Pente).
        if (effectiveAxis == ElevationProfileAxis.DURATION) {
            pauses.forEach { pause ->
                val startX = xForIndex(pause.startIndex.coerceIn(0, elevations.lastIndex))
                val endX = xForIndex(pause.endIndex.coerceIn(0, elevations.lastIndex))
                val width = (endX - startX).coerceAtLeast(PAUSE_BAND_MIN_WIDTH.toPx())
                drawRect(color = pauseBandColor, topLeft = Offset(startX, 0f), size = Size(width, plotHeight))
            }
        }

        // Altitude ruler (horizontal gridlines): exact min/max always shown, round intermediates
        // evenly spaced between them. RIC-215 : un repère intermédiaire dont le libellé, à sa
        // position réellement dessinée, en chevaucherait un autre (min, max ou repère voisin) est
        // retiré avec sa ligne ; l'arrondi peut en effet rapprocher un repère du min ou du max, et
        // la marge de evenlySpacedRoundMarks est en mètres, pas en fonction de la hauteur du graphe.
        val altitudeLabelHeight = textMeasurer.measure("0", labelStyle).size.height.toFloat()
        val altitudeGridlines = listOf(maxElevation, minElevation) +
            resolveAltitudeMarks(
                min = minElevation,
                max = maxElevation,
                candidates = evenlySpacedRoundMarks(minElevation, maxElevation, ALTITUDE_MIN_SPACING, MAX_INTERMEDIATE_GRIDLINES, ALTITUDE_ROUNDING_UNITS),
                plotHeight = plotHeight,
                labelHeight = altitudeLabelHeight,
                minGap = ALTITUDE_LABEL_GAP.toPx(),
            )
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
                topLeft = Offset(0f, altitudeLabelTop(y, altitudeLabelHeight, plotHeight)),
                style = labelStyle,
            )
        }

        // Curve. Une coupure d'enregistrement rompt le tracé plutôt que de le prolonger : les deux
        // points partagent la même abscisse, puisque rien n'a été parcouru entre eux, et les
        // relier d'un trait plein donnerait à lire une montée verticale qui n'a pas eu lieu.
        //
        // RIC-146 lot 4 (brief §4) : colorRanges vide -> un seul chemin, comportement d'origine
        // pixel pour pixel. Non vide (mode Analyse) -> un chemin par plage de couleur, chacune
        // dessinée avec la couleur déjà résolue par l'appelant.
        if (colorRanges.isEmpty()) {
            val path = Path().apply {
                elevations.forEachIndexed { index, elevation ->
                    val x = xForIndex(index)
                    val y = yFor(elevation)
                    if (index == 0 || index - 1 in recordingGaps) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(
                path = path,
                color = curveColor,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        } else {
            colorRanges.forEach { colorRange ->
                val start = colorRange.startIndex.coerceIn(0, elevations.lastIndex)
                val end = colorRange.endIndex.coerceIn(0, elevations.lastIndex)
                if (start >= end) return@forEach
                val path = Path().apply {
                    for (index in start..end) {
                        val x = xForIndex(index)
                        val y = yFor(elevations[index])
                        if (index == start || index - 1 in recordingGaps) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(
                    path = path,
                    color = colorRange.color,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        // Le lien entre les deux bouts, en pointillé : la nuit a bien relié ces deux altitudes,
        // mais aucun trajet enregistré ne les joint.
        recordingGaps.forEach { index ->
            val next = index + 1
            if (index !in elevations.indices || next !in elevations.indices) return@forEach
            drawLine(
                color = curveColor,
                start = Offset(xForIndex(index), yFor(elevations[index])),
                end = Offset(xForIndex(next), yFor(elevations[next])),
                strokeWidth = 2.dp.toPx(),
                alpha = 0.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
        }

        // RIC-146 lot 4 (brief §6) : pauses de l'axe en distance, un point sous la courbe, plus
        // gros à partir de 10 min (celles de l'axe en durée sont déjà dessinées plus haut, en
        // bandes, avant la courbe).
        if (effectiveAxis == ElevationProfileAxis.DISTANCE) {
            pauses.forEach { pause ->
                val index = pause.startIndex.coerceIn(0, elevations.lastIndex)
                val radius = if (pause.seconds >= PROFILE_PAUSE_LONG_SECONDS) PAUSE_DOT_RADIUS_LONG else PAUSE_DOT_RADIUS
                drawCircle(color = pauseColor, radius = radius.toPx(), center = Offset(xForIndex(index), plotHeight))
            }
        }

        // Bivouac markers: dot on the curve, drop line down to the axis, and (since that line
        // already marks the spot) its exact distance labelled right there on the axis.
        //
        // RIC-146 lot 4, défaut visuel trouvé en vérification (brief Partie D) : cette étiquette de
        // distance se dessine à la même abscisse que les graduations d'heure de l'axe en durée
        // (xForIndex dépend de l'axe), et venait s'y superposer illisiblement ("7,2" collé à
        // "11:00"). En axe durée, la ligne de lecture (AnalysisReadoutLine, sous le profil) porte
        // déjà la distance en toutes lettres : l'étiquette sur le graphique n'est plus nécessaire,
        // seuls le trait et le point restent.
        val bivouacXs = bivouacPoints.map { bivouac ->
            val index = bivouac.trackPointIndex.coerceIn(0, elevations.lastIndex)
            val x = xForIndex(index)
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
            if (effectiveAxis == ElevationProfileAxis.DISTANCE) {
                drawCenteredLabel(formatKm(cumulativeDistances[index] / 1000.0), x, plotHeight + 2.dp.toPx(), bivouacColor)
            }
            x
        }

        // Cursor (BIV-52): a solid line (vs. bivouacs' dashed ones) so it reads as "live" rather
        // than a fixed waypoint: distance labelled the same way a bivouac's is, for consistency
        // (même exception en axe durée, voir le commentaire ci-dessus).
        if (cursorIndex != null) {
            val index = cursorIndex.coerceIn(0, elevations.lastIndex)
            val x = xForIndex(index)
            val y = yFor(elevations[index])
            drawLine(color = cursorColor, start = Offset(x, y), end = Offset(x, plotHeight), strokeWidth = 1.5.dp.toPx())
            drawCircle(color = cursorColor, radius = 5.dp.toPx(), center = Offset(x, y))
            if (effectiveAxis == ElevationProfileAxis.DISTANCE) {
                drawCenteredLabel(formatKm(cumulativeDistances[index] / 1000.0), x, plotHeight + 2.dp.toPx(), cursorColor)
            }
        }

        if (effectiveAxis == ElevationProfileAxis.DISTANCE) {
            // Distance ruler (vertical gridlines): exact 0/total always shown, round intermediates
            // evenly spaced between them, dropped if they'd collide with a bivouac's own label.
            val collisionMarginPx = COLLISION_MARGIN.toPx()
            val intermediateKm = evenlySpacedRoundMarks(
                0.0, totalKm, DISTANCE_MIN_SPACING_KM, MAX_INTERMEDIATE_DISTANCE_TICKS, DISTANCE_ROUNDING_UNITS_KM,
                reserveEdgeMargin = false,
            )
                .filter { km -> bivouacXs.none { abs(it - xForDistance(km * 1000.0)) < collisionMarginPx } }

            fun drawDistanceGridline(km: Double, alignEnd: Boolean? /* null = center */) {
                val x = xForDistance(km * 1000.0)
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
        } else {
            // RIC-146 lot 4 (brief §5) : ruban d'heures d'horloge. Départ et arrivée toujours
            // dessinés, jamais arrondis ; les heures rondes intermédiaires viennent de
            // TrackAnalysisMapMapping.clockGridlines, déjà filtrées des extrémités trop proches.
            fun drawClockGridline(elapsed: Double, text: String, alignEnd: Boolean?) {
                val x = xForValue(elapsed)
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, plotHeight),
                    strokeWidth = 1.dp.toPx(),
                    alpha = 0.3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                )
                val textWidth = textMeasurer.measure(text, labelStyle).size.width
                val textX = when (alignEnd) {
                    false -> leftPad
                    true -> size.width - textWidth
                    null -> (x - textWidth / 2f).coerceIn(leftPad, size.width - textWidth)
                }
                drawText(textMeasurer = textMeasurer, text = text, topLeft = Offset(textX, plotHeight + 2.dp.toPx()), style = labelStyle)
            }

            val startInstant = points.first().time
            val endInstant = points.last().time
            if (startInstant != null) drawClockGridline(0.0, formatClockTime(startInstant, zone), alignEnd = false)
            if (endInstant != null) drawClockGridline(totalAxisValue, formatClockTime(endInstant, zone), alignEnd = true)
            clockGridlines?.forEach { gridline ->
                drawClockGridline(gridline.elapsedSeconds, formatClockTime(gridline.time), alignEnd = null)
            }
        }
    }
}

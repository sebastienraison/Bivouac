package com.bivouac.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bivouac.app.R
import com.bivouac.app.data.gpx.RealDurationCalculator
import com.bivouac.app.data.gpx.TrackStats
import kotlin.math.roundToInt

// Shared between the open-trace toolbar (Planification), the banked-trace list rows, and the
// Journal: same color roles wherever a distance/duration/D+/D- readout appears.
val DistanceIconColor = Color(0xFF3C7A5D)
val DurationIconColor = Color(0xFF6FA8CC)
val GainIconColor = Color(0xFFD98E48)
val LossIconColor = Color(0xFFD4B94E)

// RIC-19 : reprend exactement marker_bivouac (res/values/colors.xml), délibérément pas la couleur
// secondary du thème Material, utilisée par erreur pour ce thème dans les explorations de maquette
// qui ont précédé le ticket. Un Color hardcodé plutôt qu'un colorResource() ici, par cohérence avec
// les quatre constantes ci-dessus (déjà des valeurs fixes, pas des lookups de thème).
val BivouacIconColor = Color(0xFFF57C00)

// RIC-209 (brief Partie B) : comment StatsRows/TotalsCapsule affichent la durée. [PlainEstimate]
// est le comportement d'origine, gardé comme valeur par défaut des deux composants pour que rien
// ne change là où personne ne migre (Planification, brief "périmètre" : "ce qui ne change pas") :
// stats.estimatedDurationMinutes, jamais de préfixe "≈". [Resolved] est du Journal (lot 5) : une
// durée déjà résolue en secondes par l'appelant (réelle si RealDurationCalculator en a trouvé une,
// sinon l'estimation de repli), avec ou sans préfixe "≈" selon [Resolved.isEstimated].
sealed interface DurationDisplay {
    data object PlainEstimate : DurationDisplay
    data class Resolved(val seconds: Long, val isEstimated: Boolean) : DurationDisplay
}

/** Bascule [RealDurationCalculator.AggregatedDuration] (calcul pur) vers [DurationDisplay]
 * (affichage) : un seul endroit pour ce mapping, réutilisé par le Journal et le Bilan. */
fun RealDurationCalculator.AggregatedDuration.toDurationDisplay(): DurationDisplay =
    DurationDisplay.Resolved(totalSeconds, isEstimated)

// internal, pas private : TotalsCapsule.kt (même package, fichier différent) le réutilise pour sa
// propre ligne de durée.
@Composable
internal fun formatDurationDisplay(display: DurationDisplay, estimatedMinutes: Int): String = when (display) {
    DurationDisplay.PlainEstimate -> formatDuration(estimatedMinutes)
    is DurationDisplay.Resolved -> {
        val text = formatDuration((display.seconds / 60.0).roundToInt())
        if (display.isEstimated) stringResource(R.string.fmt_stats_rows_duration_estimated, text) else text
    }
}

@Composable
fun StatsRows(stats: TrackStats, muted: Boolean = false, duration: DurationDisplay = DurationDisplay.PlainEstimate) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val distanceColor = if (muted) neutral else DistanceIconColor
    val durationColor = if (muted) neutral else DurationIconColor
    val gainColor = if (muted) neutral else GainIconColor
    val lossColor = if (muted) neutral else LossIconColor

    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        InfoText(
            stringResource(R.string.format_distance_km, formatKm1(stats.distanceMeters / 1000)),
            Icons.Filled.Route,
            distanceColor,
        )
        InfoText(formatDurationDisplay(duration, stats.estimatedDurationMinutes), Icons.Filled.Schedule, durationColor)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        InfoText(
            stringResource(R.string.fmt_stats_rows_gain, formatGroupedInt(stats.elevationGainMeters)),
            Icons.AutoMirrored.Filled.TrendingUp,
            gainColor,
        )
        InfoText(
            stringResource(R.string.fmt_stats_rows_loss, formatGroupedInt(stats.elevationLossMeters)),
            Icons.AutoMirrored.Filled.TrendingDown,
            lossColor,
        )
    }
}

@Composable
fun InfoText(text: String, icon: ImageVector, iconTint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = iconTint)
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// RIC-190 (lot 3 i18n) : @Composable pour lire la ressource au point d'appel. La notation n'est pas
// la même dans les deux langues (« 5h32 » en français, « 5h 32m » dans les apps anglophones), donc
// ce n'est pas qu'un séparateur : c'est bien un format de ressource. Les minutes restent sur deux
// chiffres, posées ici et non par la ressource.
@Composable
fun formatDuration(totalMinutes: Int): String = stringResource(
    R.string.fmt_stats_rows_duration,
    totalMinutes / 60,
    (totalMinutes % 60).toString().padStart(2, '0'),
)

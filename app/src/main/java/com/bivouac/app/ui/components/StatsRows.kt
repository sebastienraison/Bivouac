package com.bivouac.app.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.LocalContext
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
//
// RIC-209 (brief Partie C, lot 9) : [Resolved.walkingSharePercent], `null` par défaut comme les
// deux autres champs sont couverts par PlainEstimate côté Planification -- une rando sans
// horodatage (isEstimated = true) ou un jour horodaté sans pausedSeconds encore rattrapé n'en a
// pas (brief §Règles), StatsRows/TotalsCapsule affichent alors la durée seule.
sealed interface DurationDisplay {
    data object PlainEstimate : DurationDisplay
    data class Resolved(val seconds: Long, val isEstimated: Boolean, val walkingSharePercent: Int? = null) : DurationDisplay
}

/** Bascule [RealDurationCalculator.AggregatedDuration] (calcul pur) vers [DurationDisplay]
 * (affichage) : un seul endroit pour ce mapping, réutilisé par le Journal et le Bilan. */
fun RealDurationCalculator.AggregatedDuration.toDurationDisplay(): DurationDisplay =
    DurationDisplay.Resolved(totalSeconds, isEstimated, walkingSharePercent)

// internal, pas private : TotalsCapsule.kt (même package, fichier différent) le réutilise pour sa
// propre ligne de durée. Ne porte jamais la part de marche (brief §Règles, lot 9 : le cartouche la
// met dans le LIBELLÉ de la case, pas dans la valeur) : voir [formatDurationWithShare] pour la
// variante StatsRows qui, elle, la met en évidence à côté de la durée.
@Composable
internal fun formatDurationDisplay(display: DurationDisplay, estimatedMinutes: Int): String = when (display) {
    DurationDisplay.PlainEstimate -> formatDuration(estimatedMinutes)
    is DurationDisplay.Resolved -> {
        val text = formatDuration((display.seconds / 60.0).roundToInt())
        if (display.isEstimated) stringResource(R.string.fmt_stats_rows_duration_estimated, text) else text
    }
}

// RIC-209 (brief Partie C, lot 9) : suffixe de part de marche, factorisé en fonction pure (Context
// plutôt que stringResource) pour être testable en JVM/Robolectric sans règle de test Compose --
// même principe que JournalCountsLocaleTest, qui vérifie les ressources via context.getString
// plutôt que le rendu du composable. `null` : la durée seule, inchangée (Planification, rando sans
// horodatage, ou jour horodaté sans pausedSeconds encore rattrapé, brief §Règles).
internal fun withWalkingShareText(context: Context, durationText: String, walkingSharePercent: Int?): String =
    // .toString() : fmt_stats_rows_duration_share déclare %2$s (chaîne, cf. l'inventaire i18n),
    // pas %2$d -- lint (StringFormatMatches) rejette un Int cru en argument d'un %s.
    walkingSharePercent?.let { context.getString(R.string.fmt_stats_rows_duration_share, durationText, it.toString()) } ?: durationText

// RIC-209 (brief Partie C, lot 9) : durée + part de marche sur la ligne du Journal ("7h40 · 80 %
// de marche"), quand [DurationDisplay.Resolved.walkingSharePercent] est connu.
@Composable
private fun formatDurationWithShare(display: DurationDisplay, estimatedMinutes: Int): String {
    val text = formatDurationDisplay(display, estimatedMinutes)
    val share = (display as? DurationDisplay.Resolved)?.walkingSharePercent
    return withWalkingShareText(LocalContext.current, text, share)
}

// FlowRow (brief §Largeur, même correction que AnalysisLegendAxisRow lot 7/8) : distance et durée
// gardent chacune leur largeur naturelle ; si "7h40 · 80 % de marche" ne tient pas à côté de la
// distance à 360 points, elle passe ENTIÈRE à la ligne suivante plutôt que d'être tronquée ou
// coupée en son milieu (aucun des deux InfoText ne porte de maxLines/ellipsis).
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsRows(stats: TrackStats, muted: Boolean = false, duration: DurationDisplay = DurationDisplay.PlainEstimate) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val distanceColor = if (muted) neutral else DistanceIconColor
    val durationColor = if (muted) neutral else DurationIconColor
    val gainColor = if (muted) neutral else GainIconColor
    val lossColor = if (muted) neutral else LossIconColor

    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        InfoText(
            stringResource(R.string.format_distance_km, formatKm1(stats.distanceMeters / 1000)),
            Icons.Filled.Route,
            distanceColor,
        )
        InfoText(formatDurationWithShare(duration, stats.estimatedDurationMinutes), Icons.Filled.Schedule, durationColor)
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

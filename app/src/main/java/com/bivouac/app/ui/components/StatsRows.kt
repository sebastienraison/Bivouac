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
import androidx.compose.ui.unit.dp
import com.bivouac.app.data.gpx.TrackStats

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

@Composable
fun StatsRows(stats: TrackStats, muted: Boolean = false) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val distanceColor = if (muted) neutral else DistanceIconColor
    val durationColor = if (muted) neutral else DurationIconColor
    val gainColor = if (muted) neutral else GainIconColor
    val lossColor = if (muted) neutral else LossIconColor

    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        InfoText("${formatKm1(stats.distanceMeters / 1000)} km", Icons.Filled.Route, distanceColor)
        InfoText(formatDuration(stats.estimatedDurationMinutes), Icons.Filled.Schedule, durationColor)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        InfoText("D+ ${formatGroupedInt(stats.elevationGainMeters)} m", Icons.AutoMirrored.Filled.TrendingUp, gainColor)
        InfoText("D- ${formatGroupedInt(stats.elevationLossMeters)} m", Icons.AutoMirrored.Filled.TrendingDown, lossColor)
    }
}

@Composable
fun InfoText(text: String, icon: ImageVector, iconTint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = iconTint)
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatDuration(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return "${hours}h${minutes.toString().padStart(2, '0')}"
}

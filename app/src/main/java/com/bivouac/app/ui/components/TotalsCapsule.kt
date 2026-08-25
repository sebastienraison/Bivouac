package com.bivouac.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.bivouac.app.R
import com.bivouac.app.data.gpx.TrackStats
import java.text.NumberFormat
import java.util.Locale

/**
 * RIC-19 §1 : capsule de totaux partagée entre le Journal (`JournalBilanCard`, en tête de liste) et
 * l'écran Bilan (en tête d'écran) — un seul composant plutôt que deux implémentations à
 * resynchroniser à la main à chaque évolution du design. Contenu iso avec l'ancienne
 * `JournalBilanCard` sauf un remplacement : D- disparaît au profit du nombre de bivouacs, thème
 * central de l'app (au même titre que la distance ou le D+, une nuit dehors n'est pas qu'un
 * sous-produit du calcul d'itinéraire).
 *
 * Design repris de la maquette Bilan (référence partagée désormais, y compris pour le Journal) :
 * fond neutre `surfaceContainerHigh` — pas `secondaryContainer` orange comme l'ancienne carte du
 * Journal — et grille compacte de 4 icônes 30dp colorées, une couleur par statistique plutôt qu'une
 * seule couleur de fond plein. Les 4 couleurs sont celles de [StatsRows] (distance/D+/durée) et
 * [BivouacIconColor] (`marker_bivouac`, pas `secondary` du thème Material comme utilisé par erreur
 * dans les premières explorations de cette maquette).
 *
 * [onClick] optionnel : la capsule du Journal (`JournalBilanCard`) l'utilise pour ouvrir l'écran
 * Bilan ; l'écran Bilan lui-même passe `null`, sa propre capsule n'a nulle part où renvoyer.
 */
@Composable
fun TotalsCapsule(
    totalLabel: String,
    stats: TrackStats,
    bivouacCount: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = totalLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TotalsStatItem(
                value = formatDistanceKm(stats.distanceMeters),
                label = "Distance",
                icon = Icons.Filled.Route,
                color = DistanceIconColor,
                modifier = Modifier.weight(1f),
            )
            TotalsStatItem(
                value = "${formatGrouped(stats.elevationGainMeters)} m",
                label = "D+ cumulé",
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                color = GainIconColor,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TotalsStatItem(
                value = formatDuration(stats.estimatedDurationMinutes),
                label = "Temps de marche",
                icon = Icons.Filled.Schedule,
                color = DurationIconColor,
                modifier = Modifier.weight(1f),
            )
            TotalsStatItem(
                value = "$bivouacCount",
                label = "Bivouacs",
                icon = ImageVector.vectorResource(R.drawable.ic_tent_outline),
                color = BivouacIconColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TotalsStatItem(value: String, label: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(color.copy(alpha = 0.22f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        }
        Column {
            Text(text = value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Séparateur de milliers façon française (espace fine insécable, cohérent avec NumberFormat.FRANCE)
// : les totaux cumulés du Bilan passent couramment les 4-5 chiffres (D+ cumulé, altitude...),
// contrairement aux stats d'une sortie unique de StatsRows qui n'en ont jamais eu besoin.
private fun formatDistanceKm(distanceMeters: Double): String {
    val format = NumberFormat.getNumberInstance(Locale.FRANCE).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }
    return "${format.format(distanceMeters / 1000)} km"
}

private fun formatGrouped(value: Double): String = NumberFormat.getIntegerInstance(Locale.FRANCE).format(value.toInt())

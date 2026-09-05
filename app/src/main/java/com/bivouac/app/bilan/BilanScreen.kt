package com.bivouac.app.bilan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.ui.components.BivouacIconColor
import com.bivouac.app.ui.components.DistanceIconColor
import com.bivouac.app.ui.components.DurationIconColor
import com.bivouac.app.ui.components.FullScreenEmptyState
import com.bivouac.app.ui.components.GainIconColor
import com.bivouac.app.ui.components.TotalsCapsule
import com.bivouac.app.ui.nav.AppScreenHeader
import com.bivouac.app.ui.nav.AppSection

/**
 * RIC-19 : écran "Bilan", prolongement du Journal : pas un tableau de bord fitness autonome (voir
 * l'esprit produit du ticket). Structure fixe, top to bottom : capsule de totaux (§1), graphique
 * Progression + insight (§2), records vedettes en grille 2×2 (§3), records secondaires en liste
 * (§4). Chaque record ouvre la sortie réelle qui le porte dans le Journal (§6), via
 * [onOpenJournalEntry], même boîte aux lettres que RIC-40/104, portée par MainActivity puisque
 * Bilan et Journal ont chacun leur propre ViewModel qui ne se voient jamais autrement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilanScreen(
    modifier: Modifier = Modifier,
    currentSection: AppSection,
    onSectionSelected: (AppSection) -> Unit,
    onOpenJournalEntry: (JournalOpenRequest) -> Unit,
    viewModel: BilanViewModel = viewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppScreenHeader(title = "Bilan", currentSection = currentSection, onSectionSelected = onSectionSelected) },
    ) { paddingValues ->
        val current = stats
        when {
            !loaded -> Box(Modifier.padding(paddingValues).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            current == null || current.totalCount == 0 -> FullScreenEmptyState(
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                title = "Pas encore de bilan",
                subtitle = "Importe ta première rando dans le Journal pour voir tes statistiques ici.",
                buttonText = "Aller au Journal",
                onButtonClick = { onSectionSelected(AppSection.JOURNAL) },
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
            )
            else -> BilanContent(
                stats = current,
                onOpenJournalEntry = onOpenJournalEntry,
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
            )
        }
    }
}

@Composable
private fun BilanContent(stats: BilanStats, onOpenJournalEntry: (JournalOpenRequest) -> Unit, modifier: Modifier = Modifier) {
    var selectedMetric by remember { mutableStateOf(ProgressionMetric.SORTIES) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TotalsCapsule(
            totalLabel = "${stats.totalCount} rando${if (stats.totalCount > 1) "s" else ""} au total",
            stats = stats.totals,
            bivouacCount = stats.bivouacCount,
        )

        if (stats.progression.isNotEmpty()) {
            ProgressionSection(
                progression = stats.progression,
                insight = stats.mostActiveMonthInsight,
                selectedMetric = selectedMetric,
                onMetricSelected = { selectedMetric = it },
            )
        }

        HeroRecordsSection(stats, onOpenJournalEntry)

        SecondaryRecordsSection(stats, onOpenJournalEntry)
    }
}

@Composable
private fun ProgressionSection(
    progression: List<ProgressionSeries>,
    insight: MostActiveMonthInsight?,
    selectedMetric: ProgressionMetric,
    onMetricSelected: (ProgressionMetric) -> Unit,
) {
    val series = progression.first { it.metric == selectedMetric }
    Column {
        SectionLabel("Progression")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            Text(
                text = selectedMetric.chartTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            MetricSelector(selectedMetric, onMetricSelected)
            Spacer(Modifier.height(12.dp))
            ProgressionChart(series = series, color = progressionColor(selectedMetric))
            if (insight != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Filled.EventNote,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatInsight(insight),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun progressionColor(metric: ProgressionMetric): Color = when (metric) {
    ProgressionMetric.SORTIES -> MaterialTheme.colorScheme.primary
    ProgressionMetric.KM -> DistanceIconColor
    ProgressionMetric.DPLUS -> GainIconColor
    ProgressionMetric.VITESSE -> DurationIconColor
    ProgressionMetric.BIVOUACS -> BivouacIconColor
}

@Composable
private fun MetricSelector(selected: ProgressionMetric, onSelected: (ProgressionMetric) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
    ) {
        ProgressionMetric.entries.forEach { metric ->
            val isSelected = metric == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelected(metric) }
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = metric.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// RIC-19 §3 : grille 2×2 fixe, un record manquant (garde-fou VAM, ou aucun trek multi-jours pour le
// bivouac le plus haut) retire simplement sa case plutôt que d'afficher un chiffre inventé, cohérent
// avec "chaque chiffre affiché doit pouvoir ramener à une sortie réelle".
@Composable
private fun HeroRecordsSection(stats: BilanStats, onOpenJournalEntry: (JournalOpenRequest) -> Unit) {
    val records = listOfNotNull(stats.kmEffortRecord, stats.vamRecord, stats.maxAltitudeRecord, stats.highestBivouacRecord)
    if (records.isEmpty()) return
    Column {
        SectionLabel("Records")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            records.chunked(2).forEach { rowRecords ->
                // IntrinsicSize.Max + fillMaxHeight sur chaque carte : sans ça, une carte dont le
                // texte de meta tient sur une ligne de plus/moins que sa voisine (longueur du nom
                // de la sortie) se retrouvait plus courte qu'elle, chaque ligne prenant sa propre
                // hauteur intrinsèque au lieu de s'aligner sur la plus haute des deux.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
                    rowRecords.forEach { record ->
                        HeroRecordCard(record, onOpenJournalEntry, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                    if (rowRecords.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// Liseré coloré à gauche (border-left: 3px solid var(--hero-c) côté maquette) : un Row avec une
// bande de 3dp plutôt qu'un Modifier.border, qui ne sait pas dessiner un seul côté.
@Composable
private fun HeroRecordCard(record: BilanRecord, onOpenJournalEntry: (JournalOpenRequest) -> Unit, modifier: Modifier = Modifier) {
    val color = recordColor(record.kind)
    Row(
        modifier = modifier
            .clickable { onOpenJournalEntry(JournalOpenRequest(record.trackId, record.dayIndex)) }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp)),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)),
        )
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = recordValueText(record), style = MaterialTheme.typography.titleMedium, color = color)
            Spacer(Modifier.height(2.dp))
            Text(
                text = recordLabel(record.kind),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
            recordMetaLines(record).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun SecondaryRecordsSection(stats: BilanStats, onOpenJournalEntry: (JournalOpenRequest) -> Unit) {
    val records = listOfNotNull(stats.maxDistanceDayRecord, stats.maxGainDayRecord, stats.biggestTrekRecord)
    if (records.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp),
    ) {
        records.forEachIndexed { index, record ->
            SecondaryRecordRow(record, onOpenJournalEntry, showDivider = index > 0)
        }
    }
}

@Composable
private fun SecondaryRecordRow(record: BilanRecord, onOpenJournalEntry: (JournalOpenRequest) -> Unit, showDivider: Boolean) {
    if (showDivider) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenJournalEntry(JournalOpenRequest(record.trackId, record.dayIndex)) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(recordColor(record.kind), RoundedCornerShape(50)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = recordLabel(record.kind), style = MaterialTheme.typography.bodyMedium)
            recordMetaLines(record).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(text = recordValueText(record), style = MaterialTheme.typography.titleSmall)
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}

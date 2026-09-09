package com.bivouac.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.data.storage.AppStorageUsage
import com.bivouac.app.settings.StorageUsageViewModel
import com.bivouac.app.ui.components.formatGroupedInt

/**
 * RIC-140 : où passe la place que Bivouac occupe sur le téléphone.
 *
 * Un écran à part et non une section des Réglages : le relevé coûte plusieurs centaines d'accès
 * disque (voir AppStorageUsageCalculator), et le faire à chaque ouverture des Réglages pour une
 * information qu'on consulte deux fois par an serait payer tout le temps ce qui sert rarement. Il a
 * donc son propre temps de chargement, assumé et affiché.
 *
 * C'est aussi le point d'entrée de la recompression du stock (RIC-157) : le seul endroit où le gain
 * possible est chiffré est aussi celui où l'action doit se trouver.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageUsageScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: StorageUsageViewModel = viewModel(),
) {
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val photosEnabled by viewModel.photosEnabled.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Espace utilisé") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        val current = usage
        if (current == null) {
            LoadingState(modifier = Modifier.padding(paddingValues))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TotalCard(current)
            BreakdownCard(current)
            if (photosEnabled) {
                current.recompression?.let { RecompressionCard(freedBytes = it.freedBytes, photoCount = it.photoCount) }
            }
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            "Calcul de l'espace occupé…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TotalCard(usage: AppStorageUsage) {
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Total occupé par Bivouac",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatBytes(usage.totalBytes),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            // La photothèque du Journal, pas la pellicule : la nuance compte, personne ne doit
            // craindre que ce chiffre parle de ses photos d'origine.
            Text(
                "Traces, photos du Journal et base de données, dans le stockage privé de " +
                    "l'application. Tes photos d'origine, dans la galerie, ne sont pas comptées ici.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(14.dp))
            UsageBar(usage)
        }
    }
}

/**
 * La répartition en une seule barre : c'est ce qui répond d'un coup d'œil à « qu'est-ce qui pèse
 * ici ? », avant même de lire un chiffre. Les mêmes couleurs servent de pastille devant chaque
 * poste de la liste juste en dessous : c'est ce qui relie les deux sans légende séparée.
 */
@Composable
private fun UsageBar(usage: AppStorageUsage) {
    val total = usage.totalBytes
    if (total <= 0L) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp)),
    ) {
        // Un poste dont la part est infime n'a pas de segment plutôt qu'un segment invisible :
        // weight(0f) ferait disparaître la ligne entière sur certaines versions de Compose.
        usageSlices(usage).forEach { (bytes, color) ->
            val share = bytes.toFloat() / total.toFloat()
            if (share > 0.005f) {
                Box(modifier = Modifier.weight(share).fillMaxHeight().background(color))
            }
        }
    }
}

@Composable
private fun usageSlices(usage: AppStorageUsage): List<Pair<Long, Color>> = listOf(
    usage.gpxBytes to MaterialTheme.colorScheme.primary,
    usage.photos.directoryBytes to MaterialTheme.colorScheme.tertiary,
    usage.databaseBytes to MaterialTheme.colorScheme.secondary,
    usage.otherBytes to MaterialTheme.colorScheme.outline,
)

@Composable
private fun BreakdownCard(usage: AppStorageUsage) {
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            UsageRow(
                color = MaterialTheme.colorScheme.primary,
                title = "Traces GPX",
                value = formatBytes(usage.gpxBytes),
                details = listOf(
                    "Journal : ${countLabel(usage.gpxJournalFileCount, "fichier", "fichiers")}, " +
                        formatBytes(usage.gpxJournalBytes),
                    "Planification : ${countLabel(usage.gpxPlanificationFileCount, "fichier", "fichiers")}, " +
                        formatBytes(usage.gpxPlanificationBytes),
                ),
            )
            UsageRow(
                color = MaterialTheme.colorScheme.tertiary,
                title = "Photos du Journal",
                value = formatBytes(usage.photos.directoryBytes),
                details = buildList {
                    add(
                        "Qualité d'archive : ${countLabel(usage.photos.fullCount, "photo", "photos")}, " +
                            formatBytes(usage.photos.fullBytes),
                    )
                    add(
                        "Copie réduite : ${countLabel(usage.photos.reducedCount, "photo", "photos")}, " +
                            formatBytes(usage.photos.reducedBytes),
                    )
                    // Dit ici parce que c'est ici que le chiffre surprend : des photos comptées
                    // dans le Journal qui ne pèsent rien. L'action qui les rattrape est dans les
                    // Réglages (RIC-151), cet écran ne fait que constater.
                    if (usage.photos.missingCount > 0) {
                        add("Dont ${countLabel(usage.photos.missingCount, "fichier absent", "fichiers absents")}")
                    }
                },
            )
            UsageRow(
                color = MaterialTheme.colorScheme.secondary,
                title = "Base de données",
                value = formatBytes(usage.databaseBytes),
                details = listOf("Sorties, jours, tags, fiches photo"),
            )
            UsageRow(
                color = MaterialTheme.colorScheme.outline,
                title = "Autres",
                value = formatBytes(usage.otherBytes),
                details = listOf("Réglages, caches de la carte, fichiers temporaires"),
            )
        }
    }
}

@Composable
private fun UsageRow(color: Color, title: String, value: String, details: List<String>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            details.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * RIC-157 : ce que la recompression du stock rendrait, chiffré avant de la lancer.
 *
 * Le « ~ » n'est pas une coquetterie : le gain réel ne se connaît qu'une fois chaque photo
 * réencodée, et il dépend de photos que l'app n'a pas encore rouvertes (voir
 * PhotoRecompression.estimate). Annoncer un chiffre net serait promettre ce qu'on ne sait pas.
 */
@Composable
private fun RecompressionCard(freedBytes: Long, photoCount: Int) {
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Recompresser pourrait libérer ~${formatBytes(freedBytes)}", style = MaterialTheme.typography.titleMedium)
            Text(
                "${countLabel(photoCount, "photo est conservée", "photos sont conservées")} en qualité " +
                    "d'archive. Bivouac peut les remplacer par une copie réduite, à condition de " +
                    "retrouver l'original dans ta galerie : celles dont l'original a disparu ou a " +
                    "changé ne sont jamais touchées.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** « 1 photo » / « 12 photos », avec le séparateur de milliers des grands nombres (RIC-136). */
internal fun countLabel(count: Int, singular: String, plural: String): String =
    if (count == 1) "1 $singular" else "${formatGroupedInt(count)} $plural"

package com.bivouac.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.photo.PhotoLibraryPermission
import com.bivouac.app.data.storage.AppStorageUsage
import com.bivouac.app.settings.StorageUsageViewModel
import com.bivouac.app.ui.components.BlockingProgress
import com.bivouac.app.ui.components.BlockingProgressDialog
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
    val recompressionProgress by viewModel.recompressionProgress.collectAsStateWithLifecycle()
    val recompressionReport by viewModel.recompressionReport.collectAsStateWithLifecycle()
    val recompressionError by viewModel.recompressionError.collectAsStateWithLifecycle()
    val ongoingOperation by viewModel.ongoingOperation.collectAsStateWithLifecycle()

    val context = LocalContext.current
    // RIC-43 : même mécanique que le bandeau Photos du Journal, et pour la même raison : après un
    // refus devenu définitif, relancer la demande rend la main sans afficher un pixel. Le drapeau
    // est ce qui permet d'expliquer nous-mêmes à la place.
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var blockedDialog by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // isGranted et non la carte de réponses : sur Android 14, « Sélectionner des photos » rend
        // READ_MEDIA_IMAGES refusée alors que l'accès partiel, lui, est bien accordé.
        if (PhotoLibraryPermission.isGranted(context)) {
            permanentlyDenied = false
            // La permission vient d'être accordée pour CE geste : l'enchaîner est ce que
            // l'utilisateur attend, lui redemander d'appuyer une seconde fois serait un cul-de-sac.
            viewModel.recompressPhotos()
        } else {
            permanentlyDenied = PhotoLibraryPermission.isPermanentlyDenied(context)
            if (permanentlyDenied) blockedDialog = true
        }
    }
    val onRecompressClick: () -> Unit = {
        when (
            photoGalleryActionOutcome(
                photosEnabled = photosEnabled,
                permissionGranted = PhotoLibraryPermission.isGranted(context),
                permanentlyDenied = permanentlyDenied,
            )
        ) {
            PhotoGalleryActionOutcome.IGNORED -> Unit
            PhotoGalleryActionOutcome.RUN -> viewModel.recompressPhotos()
            PhotoGalleryActionOutcome.EXPLAIN_BLOCKED -> blockedDialog = true
            PhotoGalleryActionOutcome.REQUEST_PERMISSION ->
                permissionLauncher.launch(PhotoLibraryPermission.requestedPermissions)
        }
    }

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
                current.recompression?.let {
                    RecompressionCard(
                        freedBytes = it.freedBytes,
                        photoCount = it.photoCount,
                        onRecompressClick = onRecompressClick,
                        // Même grisage que Sauvegarder/Restaurer : la recompression réécrit
                        // photos/, elle ne peut pas croiser une autre opération longue.
                        locked = ongoingOperation != null,
                    )
                }
            }
        }
    }

    // RIC-157 : bloquant et sans porte de sortie, comme la purge et la sauvegarde : ce qui tourne
    // remplace des fichiers de photos/ et met les lignes à jour dans la foulée.
    BlockingProgressDialog(
        progress = recompressionProgress?.let {
            BlockingProgress(title = "Recompression en cours", done = it.done, total = it.total)
        },
    )

    // Jamais de fin silencieuse : même une passe qui n'a rien pu faire le dit, et dit pourquoi.
    recompressionReport?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRecompressionReport,
            title = { Text("Recompression terminée") },
            text = { Text(recompressionReportMessage(report)) },
            confirmButton = { TextButton(onClick = viewModel::dismissRecompressionReport) { Text("OK") } },
        )
    }

    recompressionError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRecompressionError,
            title = { Text("Recompression impossible") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissRecompressionError) { Text("OK") } },
        )
    }

    // RIC-43 : le seul retour possible après un refus définitif, Android ne réaffichant plus son
    // invite. Même dialogue et même issue que le bandeau Photos du Journal.
    if (blockedDialog) {
        AlertDialog(
            onDismissRequest = { blockedDialog = false },
            title = { Text("Accès aux photos refusé") },
            text = {
                Text(
                    "Recompresser demande de retrouver tes photos d'origine dans la galerie, et " +
                        "Android ne redemandera plus l'autorisation depuis l'application. " +
                        "Autorise l'accès à la galerie dans les réglages de l'application.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    blockedDialog = false
                    context.openApplicationSettings()
                }) { Text("Ouvrir les réglages") }
            },
            dismissButton = { TextButton(onClick = { blockedDialog = false }) { Text("Annuler") } },
        )
    }
}

/**
 * RIC-157/151 : ce qu'un appui doit produire, pour toute action des Réglages qui a besoin d'aller
 * regarder dans la galerie : recompresser le stock, retrouver les photos manquantes.
 *
 * Extraite des composables pour la même raison que `addPhotosOutcome` (RIC-43) : la règle qui
 * compte, « aucune issue muette, et aucune demande de permission quand les photos sont débrayées »,
 * se vérifie alors sans monter d'écran. Commune aux deux actions parce que c'est littéralement la
 * même décision : deux copies auraient divergé sur le cas du refus définitif, qui est le seul
 * subtil.
 */
internal enum class PhotoGalleryActionOutcome { IGNORED, RUN, REQUEST_PERMISSION, EXPLAIN_BLOCKED }

internal fun photoGalleryActionOutcome(
    photosEnabled: Boolean,
    permissionGranted: Boolean,
    permanentlyDenied: Boolean,
): PhotoGalleryActionOutcome = when {
    // RIC-152 : photos débrayées, l'accès à la galerie n'est JAMAIS demandé. Inatteignable en
    // pratique (le bouton n'est pas affiché), garde de dernier recours comme pour l'ajout.
    !photosEnabled -> PhotoGalleryActionOutcome.IGNORED
    permissionGranted -> PhotoGalleryActionOutcome.RUN
    permanentlyDenied -> PhotoGalleryActionOutcome.EXPLAIN_BLOCKED
    else -> PhotoGalleryActionOutcome.REQUEST_PERMISSION
}

/**
 * RIC-157 : le rapport de fin, en toutes lettres.
 *
 * Les trois issues sont dites séparément parce qu'elles n'appellent pas la même conclusion :
 * « conservées » est réversible (l'original peut revenir), « déjà au format réduit » est définitif,
 * et le total libéré est le seul chiffre que l'utilisateur était venu chercher.
 */
internal fun recompressionReportMessage(report: LoggedTrackRepository.PhotoRecompressionReport): String {
    val lines = mutableListOf<String>()
    if (report.recompressed > 0) {
        lines += "${countLabel(report.recompressed, "photo recompressée", "photos recompressées")}, " +
            "${formatBytes(report.freedBytes)} libérés."
    } else {
        lines += "Aucune photo n'a pu être recompressée."
    }
    if (report.kept > 0) {
        lines += "${countLabel(report.kept, "photo conservée", "photos conservées")} en pleine " +
            "résolution : original introuvable ou modifié depuis l'import."
    }
    if (report.alreadyReduced > 0) {
        lines += "${countLabel(report.alreadyReduced, "photo était déjà", "photos étaient déjà")} " +
            "au format le plus léger : rien à y gagner."
    }
    return lines.joinToString("\n\n")
}

/**
 * La page « informations sur l'application » du système, seul endroit où se défait un refus devenu
 * définitif. Partagée par les deux écrans des Réglages qui peuvent le rencontrer (recompression
 * ici, recherche des photos manquantes dans SettingsScreen).
 *
 * runCatching pour la même raison qu'au Journal : un Context sans activité pour l'accueillir ferait
 * remonter une ActivityNotFoundException, et ne pas ouvrir les réglages est un échec acceptable là
 * où planter en tentant de les ouvrir ne l'est pas.
 */
internal fun Context.openApplicationSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
                        "Pleine résolution : ${countLabel(usage.photos.fullCount, "photo", "photos")}, " +
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
private fun RecompressionCard(freedBytes: Long, photoCount: Int, onRecompressClick: () -> Unit, locked: Boolean) {
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Recompresser pourrait libérer ~${formatBytes(freedBytes)}", style = MaterialTheme.typography.titleMedium)
            Text(
                "${countLabel(photoCount, "photo est conservée", "photos sont conservées")} en pleine " +
                    "résolution. Bivouac peut les remplacer par une copie réduite, à condition de " +
                    "retrouver l'original dans ta galerie : celles dont l'original a disparu ou a " +
                    "changé ne sont jamais touchées.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(
                onClick = onRecompressClick,
                enabled = !locked,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Icon(Icons.Default.Compress, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Recompresser les photos existantes")
            }
        }
    }
}

/** « 1 photo » / « 12 photos », avec le séparateur de milliers des grands nombres (RIC-136). */
internal fun countLabel(count: Int, singular: String, plural: String): String =
    if (count == 1) "1 $singular" else "${formatGroupedInt(count)} $plural"

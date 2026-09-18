package com.bivouac.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.BuildConfig
import com.bivouac.app.R
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.db.PhotoStorageSummary
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.SpeedCalibrationCalculator
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.photo.PhotoLibraryPermission
import com.bivouac.app.data.photo.PhotoStorageMode
import com.bivouac.app.data.prefs.SpeedCalibrationMode
import com.bivouac.app.settings.RestoreOutcome
import com.bivouac.app.settings.SettingsViewModel
import com.bivouac.app.ui.components.BlockingProgress
import com.bivouac.app.ui.components.BlockingProgressDialog
import com.bivouac.app.ui.components.formatDuration
import com.bivouac.app.ui.nav.AppScreenHeader
import com.bivouac.app.ui.nav.AppSection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

private const val BIVOUAC_GITHUB_URL = "https://github.com/sebastienraison/Bivouac"

/**
 * Réglages (BIV-16): Vitesse personnalisée, fonctions non libres, sauvegarde/restauration
 * (BIV-66) et crédits. Itinéraires (BIV-23, future clé API OpenRouteService) has a reserved slot
 * in the ticket but no UI yet: nothing to render until a feature actually consumes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    currentSection: AppSection,
    onSectionSelected: (AppSection) -> Unit,
    onOpenJournalSelection: () -> Unit,
    // RIC-140 : le sous-écran « Espace utilisé », joignable seulement d'ici (voir MainActivity).
    onOpenStorageUsage: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val manual by viewModel.manualCalibration.collectAsStateWithLifecycle()
    val auto by viewModel.autoCalibration.collectAsStateWithLifecycle()
    val selection by viewModel.selectionCalibration.collectAsStateWithLifecycle()
    val selectedTrackCount by viewModel.selectedTrackCount.collectAsStateWithLifecycle()
    val journalTrackCount by viewModel.journalTrackCount.collectAsStateWithLifecycle()
    val nonFreeFeaturesDisabled by viewModel.nonFreeFeaturesDisabled.collectAsStateWithLifecycle()
    val photosEnabled by viewModel.photosEnabled.collectAsStateWithLifecycle()
    val photoStorageMode by viewModel.photoStorageMode.collectAsStateWithLifecycle()
    val photoStorage by viewModel.photoStorage.collectAsStateWithLifecycle()
    val photoRecompressionOffer by viewModel.photoRecompressionOffer.collectAsStateWithLifecycle()
    val photoRecompressionReport by viewModel.photoRecompressionReport.collectAsStateWithLifecycle()
    val photoRecompressionError by viewModel.photoRecompressionError.collectAsStateWithLifecycle()
    val photoPurgeConfirmation by viewModel.photoPurgeConfirmation.collectAsStateWithLifecycle()
    val photoPurgeError by viewModel.photoPurgeError.collectAsStateWithLifecycle()
    val missingPhotoCount by viewModel.missingPhotoCount.collectAsStateWithLifecycle()
    val photoRecoveryReport by viewModel.photoRecoveryReport.collectAsStateWithLifecycle()
    val photoRecoveryError by viewModel.photoRecoveryError.collectAsStateWithLifecycle()
    val lastBackupAtMillis by viewModel.lastBackupAtMillis.collectAsStateWithLifecycle()
    val dataOperationProgress by viewModel.dataOperationProgress.collectAsStateWithLifecycle()
    val ongoingOperation by viewModel.ongoingOperation.collectAsStateWithLifecycle()
    val backupError by viewModel.backupError.collectAsStateWithLifecycle()
    val restoreOutcome by viewModel.restoreOutcome.collectAsStateWithLifecycle()

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        uri?.let { viewModel.backup(it) }
    }
    // RIC-151 : le flux de permission galerie du domaine photos, ici pour « Retrouver les photos
    // manquantes ». Même mécanique et mêmes états qu'au Journal (RIC-43) et que sur l'écran
    // « Espace utilisé » : après un refus devenu définitif, relancer la demande rend la main sans
    // afficher un pixel, d'où le drapeau et le dialogue qui explique à la place.
    var photoPermanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var photoPermissionBlockedDialog by rememberSaveable { mutableStateOf(false) }
    val photoRecoveryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // isGranted et non la carte de réponses : sur Android 14, « Sélectionner des photos » rend
        // READ_MEDIA_IMAGES refusée alors que l'accès partiel, lui, est bien accordé.
        if (PhotoLibraryPermission.isGranted(context)) {
            photoPermanentlyDenied = false
            viewModel.recoverMissingPhotos()
        } else {
            photoPermanentlyDenied = PhotoLibraryPermission.isPermanentlyDenied(context)
            if (photoPermanentlyDenied) photoPermissionBlockedDialog = true
        }
    }
    val onRecoverMissingPhotosClick: () -> Unit = {
        when (
            photoGalleryActionOutcome(
                photosEnabled = photosEnabled,
                permissionGranted = PhotoLibraryPermission.isGranted(context),
                permanentlyDenied = photoPermanentlyDenied,
            )
        ) {
            PhotoGalleryActionOutcome.IGNORED -> Unit
            PhotoGalleryActionOutcome.RUN -> viewModel.recoverMissingPhotos()
            PhotoGalleryActionOutcome.EXPLAIN_BLOCKED -> photoPermissionBlockedDialog = true
            PhotoGalleryActionOutcome.REQUEST_PERMISSION ->
                photoRecoveryPermissionLauncher.launch(PhotoLibraryPermission.requestedPermissions)
        }
    }

    // RIC-157 : même flux de permission que ci-dessus, un second exemplaire parce que sa cible est
    // une autre action (viewModel.recompressPhotosFromOffer, pas recoverMissingPhotos) : même
    // patron que StorageUsageScreen, qui porte le sien pour la même raison.
    val photoRecompressPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (PhotoLibraryPermission.isGranted(context)) {
            photoPermanentlyDenied = false
            viewModel.recompressPhotosFromOffer()
        } else {
            photoPermanentlyDenied = PhotoLibraryPermission.isPermanentlyDenied(context)
            if (photoPermanentlyDenied) photoPermissionBlockedDialog = true
        }
    }
    val onRecompressFromOfferClick: () -> Unit = {
        when (
            photoGalleryActionOutcome(
                photosEnabled = photosEnabled,
                permissionGranted = PhotoLibraryPermission.isGranted(context),
                permanentlyDenied = photoPermanentlyDenied,
            )
        ) {
            PhotoGalleryActionOutcome.IGNORED -> Unit
            PhotoGalleryActionOutcome.RUN -> viewModel.recompressPhotosFromOffer()
            PhotoGalleryActionOutcome.EXPLAIN_BLOCKED -> {
                viewModel.dismissPhotoRecompressionOffer()
                photoPermissionBlockedDialog = true
            }
            PhotoGalleryActionOutcome.REQUEST_PERMISSION ->
                photoRecompressPermissionLauncher.launch(PhotoLibraryPermission.requestedPermissions)
        }
    }
    // Picking a file only stages it: restoring overwrites the current database, so it still
    // needs an explicit confirmation below before viewModel.restore() actually runs.
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    // "*/*" rather than a strict zip mimeType: several file pickers/providers don't tag a .zip
    // correctly, same reasoning already applied to the GPX pickers elsewhere in the app.
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pendingRestoreUri = uri
    }

    // Même en-tête que l'accueil du Journal (RIC-65), via le composant partagé AppScreenHeader :
    // une première version recopiée à la main avait laissé le titre sur la couleur de texte par
    // défaut (noire), faute d'être posée dans un Surface/Scaffold comme celle du Journal : invisible
    // en thème clair par coïncidence, noir sur noir en thème sombre. Scaffold fournit ce Surface.
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppScreenHeader(
                title = stringResource(R.string.settings_screen_title),
                currentSection = currentSection,
                onSectionSelected = onSectionSelected,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            SpeedCalibrationSection(
                mode = mode,
                manual = manual,
                auto = auto,
                selection = selection,
                selectedTrackCount = selectedTrackCount,
                journalTrackCount = journalTrackCount,
                onModeSelected = viewModel::setMode,
                onManualSpeedChanged = viewModel::setManualSpeed,
                onManualPenaltyChanged = viewModel::setManualPenalty,
                onManualPauseChanged = viewModel::setManualPause,
                onChooseTracksClick = onOpenJournalSelection,
            )
            NonFreeFeaturesSection(
                disabled = nonFreeFeaturesDisabled,
                onToggle = viewModel::setNonFreeFeaturesDisabled,
            )
            JournalPhotosSection(
                enabled = photosEnabled,
                onToggle = viewModel::setPhotosEnabled,
                storageMode = photoStorageMode,
                // RIC-157 : et non setPhotoStorageMode directement, qui ne fait que persister :
                // c'est ce point d'entrée-ci qui enchaîne la proposition de recompresser le stock
                // existant quand la bascule le justifie (voir choosePhotoStorageMode).
                onStorageModeSelected = viewModel::choosePhotoStorageMode,
                storage = photoStorage,
                onPurgeClick = viewModel::requestPhotoPurge,
                // RIC-158 : même registre que Sauvegarder/Restaurer ci-dessous : un import Journal
                // ou Planification en vol grise la purge aussi.
                purgeLocked = ongoingOperation != null,
                missingPhotoCount = missingPhotoCount,
                onRecoverMissingPhotosClick = onRecoverMissingPhotosClick,
            )
            DataSection(
                lastBackupAtMillis = lastBackupAtMillis,
                // RIC-156 : un seul drapeau pour les deux boutons, et il vient du registre commun
                // au process : un import de photos en cours dans le Journal les grise aussi.
                operationsLocked = ongoingOperation != null,
                onBackupClick = { backupLauncher.launch(suggestedBackupFileName()) },
                onRestoreClick = { restoreLauncher.launch(arrayOf("*/*")) },
                onStorageUsageClick = onOpenStorageUsage,
            )
            CreditsSection(
                onOpenUrl = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            )
            // RIC-133 : identifie la build exacte qui tourne (utile support/debug), et rassure que
            // la mise à jour a bien pris. Texte simple, pas une SettingsSection : rien à toucher ici.
            Text(
                text = stringResource(
                    R.string.settings_version_build_info,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.BUILD_DATE,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // RIC-156 : sauvegarde et restauration sont bloquantes, sur le même composant que les
    // opérations photo du Journal. Ce qui manquait à l'incident d'origine : rien n'empêchait de
    // lancer une restauration pendant qu'une sauvegarde s'écrivait, l'écran restant entièrement
    // manipulable. Rendu au niveau de l'écran et non dans DataSection, pour couvrir tous les
    // chemins du seul fait qu'il suit l'état du ViewModel.
    BlockingProgressDialog(
        progress = dataOperationProgress?.let {
            BlockingProgress(title = stringResource(it.phase.titleRes), done = it.done, total = it.total)
        },
    )

    // RIC-157 : posée juste après la bascule vers la copie réduite, quand il reste des photos en
    // pleine résolution à reprendre (voir choosePhotoStorageMode et
    // PhotoRecompression.shouldOfferRecompressionAfterModeChange). N et le poids viennent de la
    // même estimation que la carte de l'écran « Espace utilisé », pas d'un second calcul.
    photoRecompressionOffer?.let { estimate ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPhotoRecompressionOffer,
            title = { Text(stringResource(R.string.settings_photo_recompress_offer_title)) },
            text = {
                // RIC-190 (lot 3 i18n) : la phrase entière est un <plurals> portant le compte, au
                // lieu d'un countLabel recollant un fragment : au singulier, « Les recompresser »
                // devient « La recompresser », ce qu'un fragment ne savait pas dire.
                Text(
                    pluralStringResource(
                        R.plurals.settings_photo_recompress_offer_message,
                        estimate.photoCount,
                        estimate.photoCount,
                        formatBytes(context, estimate.freedBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onRecompressFromOfferClick) {
                    Text(stringResource(R.string.settings_photo_recompress_offer_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPhotoRecompressionOffer) {
                    Text(stringResource(R.string.settings_photo_recompress_offer_dismiss_button))
                }
            },
        )
    }

    // Même rapport et même mise en forme que le bouton dédié de l'écran « Espace utilisé » : voir
    // recompressionReportMessage (StorageUsageScreen), c'est la même opération.
    photoRecompressionReport?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPhotoRecompressionReport,
            title = { Text(stringResource(R.string.settings_photo_recompress_report_title)) },
            text = { Text(recompressionReportMessage(context, report)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPhotoRecompressionReport) {
                    Text(stringResource(R.string.common_ok_button))
                }
            },
        )
    }

    photoRecompressionError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPhotoRecompressionError,
            title = { Text(stringResource(R.string.settings_photo_recompress_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPhotoRecompressionError) {
                    Text(stringResource(R.string.common_ok_button))
                }
            },
        )
    }

    // RIC-152 : la seule suppression de photos en masse de l'app, donc une confirmation qui dit
    // exactement ce qui part et qu'on n'en revient pas. Le chiffre est repris du relevé fait au
    // moment du clic, pas recalculé : le dialogue doit confirmer ce que le bouton annonçait.
    photoPurgeConfirmation?.let { storage ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPhotoPurge,
            title = { Text(stringResource(R.string.settings_photo_purge_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_photo_purge_confirm_message,
                        formatPhotoStorage(context, storage),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPhotoPurge) {
                    Text(
                        stringResource(R.string.settings_photo_purge_confirm_button),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPhotoPurge) {
                    Text(stringResource(R.string.common_cancel_button))
                }
            },
        )
    }

    backupError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissBackupError,
            title = { Text(stringResource(R.string.settings_backup_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissBackupError) {
                    Text(stringResource(R.string.common_ok_button))
                }
            },
        )
    }

    // RIC-158 : censé être inatteignable, le bouton de purge étant grisé dès qu'une autre
    // opération tourne, reste écrit pour la même raison défensive que backupError ci-dessus.
    photoPurgeError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPhotoPurgeError,
            title = { Text(stringResource(R.string.settings_photo_purge_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPhotoPurgeError) {
                    Text(stringResource(R.string.common_ok_button))
                }
            },
        )
    }

    // RIC-151 : jamais de fin silencieuse, même quand la recherche n'a rien pu reprendre. Les trois
    // issues sont dites séparément : « retrouvée », « retrouvée mais modifiée » (rien n'a été
    // adopté, exprès) et « introuvable » n'appellent pas les mêmes conclusions.
    photoRecoveryReport?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPhotoRecoveryReport,
            title = { Text(stringResource(R.string.settings_photo_recovery_report_title)) },
            text = { Text(photoRecoveryReportMessage(context, report)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPhotoRecoveryReport) {
                    Text(stringResource(R.string.common_ok_button))
                }
            },
        )
    }

    photoRecoveryError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPhotoRecoveryError,
            title = { Text(stringResource(R.string.settings_photo_recovery_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPhotoRecoveryError) {
                    Text(stringResource(R.string.common_ok_button))
                }
            },
        )
    }

    // RIC-43/151/157 : le seul retour possible après un refus définitif, Android ne réaffichant
    // plus son invite. Même dialogue et même issue qu'au Journal, partagé par les trois actions
    // d'ici qui vont chercher dans la galerie (retrouver les photos manquantes, recompresser
    // depuis le bouton dédié ou depuis cette proposition-ci) : le texte reste générique plutôt que
    // de nommer l'une des trois.
    if (photoPermissionBlockedDialog) {
        AlertDialog(
            onDismissRequest = { photoPermissionBlockedDialog = false },
            title = { Text(stringResource(R.string.journal_msg_photo_access_denied_title)) },
            text = { Text(stringResource(R.string.settings_photo_permission_blocked_message)) },
            confirmButton = {
                TextButton(onClick = {
                    photoPermissionBlockedDialog = false
                    context.openApplicationSettings()
                }) { Text(stringResource(R.string.journal_msg_open_settings_button)) }
            },
            dismissButton = {
                TextButton(onClick = { photoPermissionBlockedDialog = false }) {
                    Text(stringResource(R.string.common_cancel_button))
                }
            },
        )
    }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text(stringResource(R.string.settings_restore_confirm_title)) },
            text = { Text(stringResource(R.string.settings_restore_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restore(uri)
                    pendingRestoreUri = null
                }) {
                    Text(
                        stringResource(R.string.settings_restore_confirm_button),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) {
                    Text(stringResource(R.string.common_cancel_button))
                }
            },
        )
    }

    restoreOutcome?.let { outcome ->
        when (outcome) {
            is RestoreOutcome.PendingRestart -> AlertDialog(
                // Not dismissible without restarting: the app's in-memory state is stale the
                // instant the on-disk files get swapped underneath it (see AppRestart's kdoc).
                onDismissRequest = {},
                title = { Text(stringResource(R.string.settings_restore_done_title)) },
                text = { Text(stringResource(R.string.settings_restore_done_message)) },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmRestartAfterRestore) {
                        Text(stringResource(R.string.settings_restore_done_restart_button))
                    }
                },
            )
            is RestoreOutcome.VersionTooNew -> AlertDialog(
                onDismissRequest = viewModel::dismissRestoreOutcome,
                title = { Text(stringResource(R.string.settings_restore_version_too_new_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.settings_restore_version_too_new_message,
                            outcome.backupVersion,
                            outcome.appVersion,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissRestoreOutcome) {
                        Text(stringResource(R.string.common_ok_button))
                    }
                },
            )
            is RestoreOutcome.Error -> AlertDialog(
                onDismissRequest = viewModel::dismissRestoreOutcome,
                title = { Text(stringResource(R.string.settings_restore_error_title)) },
                text = { Text(outcome.message) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissRestoreOutcome) {
                        Text(stringResource(R.string.common_ok_button))
                    }
                },
            )
        }
    }
}

private fun suggestedBackupFileName(): String {
    val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneId.systemDefault()).format(Instant.now())
    return "bivouac-backup-$stamp.zip"
}

@Composable
private fun SettingsSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
        ElevatedCard(shape = RoundedCornerShape(20.dp)) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    secondaryAvatar: Boolean = false,
    // RIC-140 : une ligne qui MÈNE quelque part plutôt qu'une qui porte un réglage. Le clic est
    // posé sur la ligne entière et non sur un bouton à droite : c'est ce qu'on attend d'une ligne
    // de réglages qui ouvre un écran, et ça évite une cible de 40 dp au bord de l'écran.
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (secondaryAvatar) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (secondaryAvatar) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedCalibrationSection(
    mode: SpeedCalibrationMode,
    manual: SpeedCalibration,
    auto: SpeedCalibration,
    selection: SpeedCalibration,
    selectedTrackCount: Int,
    journalTrackCount: Int,
    onModeSelected: (SpeedCalibrationMode) -> Unit,
    onManualSpeedChanged: (Double) -> Unit,
    onManualPenaltyChanged: (Double) -> Unit,
    onManualPauseChanged: (Double) -> Unit,
    onChooseTracksClick: () -> Unit,
) {
    // Auto (whole Journal) and Sélection (a subset of it) both need at least
    // MIN_TRACKS_FOR_CALIBRATION hikes to ever compute more than the default: gated on the
    // Journal's total rather than on Sélection's own confirmed count, since that's the real
    // ceiling either mode can reach (BIV-16 recette). Whichever mode is already active stays
    // reachable even if the Journal has since shrunk below that floor; see the note below.
    val calibrationModesUsable = journalTrackCount >= SpeedCalibrationCalculator.MIN_TRACKS_FOR_CALIBRATION
    SettingsSection(label = stringResource(R.string.settings_speed_calibration_section_title)) {
        SettingsRow(
            icon = Icons.Default.Speed,
            title = stringResource(R.string.settings_speed_calibration_mode_label),
            subtitle = stringResource(R.string.settings_speed_calibration_mode_description),
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            SpeedCalibrationMode.entries.forEachIndexed { index, candidate ->
                SegmentedButton(
                    selected = candidate == mode,
                    onClick = { onModeSelected(candidate) },
                    // Never locks the user OUT of their own current mode just because the Journal
                    // shrank after the fact: only blocks switching INTO Auto/Sélection from
                    // somewhere else when there isn't enough data for either to mean anything.
                    enabled = candidate == SpeedCalibrationMode.MANUAL || candidate == mode || calibrationModesUsable,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SpeedCalibrationMode.entries.size),
                    label = { Text(stringResource(candidate.labelRes())) },
                )
            }
        }
        if (!calibrationModesUsable) {
            Text(
                stringResource(R.string.settings_speed_calibration_modes_locked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp),
            )
        }
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).padding(top = 6.dp, bottom = 12.dp)) {
            when (mode) {
                SpeedCalibrationMode.MANUAL -> {
                    Text(
                        stringResource(R.string.settings_speed_calibration_manual_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    ManualCalibrationFields(manual, onManualSpeedChanged, onManualPenaltyChanged)
                }
                SpeedCalibrationMode.AUTO -> {
                    Text(
                        stringResource(R.string.settings_speed_calibration_auto_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    CalibrationStatGrid(auto)
                }
                SpeedCalibrationMode.SELECTION -> {
                    // RIC-190 (lot 3 i18n) : le when choisit un id de ressource, pas une chaîne.
                    Text(
                        when (selectedTrackCount) {
                            0 -> stringResource(R.string.settings_speed_calibration_selection_hint_empty)
                            // A single hike can't separate two unknowns (vitesse et pénalité D+)
                            // from one another: the maths behind CalibrationStatGrid genuinely
                            // has no way to isolate D+ from just one data point, so it's kept at
                            // sa valeur par défaut rather than showing a number that looks computed
                            // but isn't. Same reasoning applies with more traces if their profil
                            // (rapport dénivelé/distance) est trop similaire d'une trace à l'autre.
                            1 -> stringResource(R.string.settings_speed_calibration_selection_hint_one)
                            else -> stringResource(
                                R.string.settings_speed_calibration_selection_hint_many,
                                selectedTrackCount,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    CalibrationStatGrid(selection)
                    TextButton(onClick = onChooseTracksClick, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.settings_speed_calibration_choose_tracks_button))
                    }
                }
            }

            // RIC-115 : provision de pause : aucune séparation visuelle avec le bloc
            // vitesse/pénalité ci-dessus, même carte, même niveau hiérarchique. Visible dans les
            // 3 modes (contrairement aux champs de saisie/capsules ci-dessus, qui divergent selon
            // le mode), à partir de la calibration effectivement active pour le mode courant.
            val activeCalibration = when (mode) {
                SpeedCalibrationMode.MANUAL -> manual
                SpeedCalibrationMode.AUTO -> auto
                SpeedCalibrationMode.SELECTION -> selection
            }
            Spacer(Modifier.size(8.dp))
            DPlusPreviewRow(activeCalibration)
            Spacer(Modifier.size(14.dp))
            PauseStatCard(
                pauseFractionPercent = activeCalibration.pauseFractionPercent,
                enabled = mode == SpeedCalibrationMode.MANUAL,
                onValueChange = onManualPauseChanged,
            )
            Spacer(Modifier.size(8.dp))
            PausePreviewRow(activeCalibration.pauseFractionPercent)
        }
    }
}

// Rando type purement illustrative (RIC-115), codée en dur, ne dépend d'aucune trace réelle. Sert
// à rendre lisible la pénalité D+ (m/km), un chiffre autrement abstrait, dans les 3 modes.
private const val TYPICAL_HIKE_DISTANCE_METERS = 15_000.0
private const val TYPICAL_HIKE_GAIN_METERS = 600.0

// Base de l'aperçu de provision de pause (RIC-115) : 6h de marche pure, également codées en dur.
private const val PAUSE_PREVIEW_WALKING_MINUTES = 360.0

@Composable
private fun DPlusPreviewRow(calibration: SpeedCalibration) {
    val totalMinutes = TrackStatsCalculator.walkingMinutes(TYPICAL_HIKE_DISTANCE_METERS, TYPICAL_HIKE_GAIN_METERS, calibration)
    val dPlusOnlyMinutes = TrackStatsCalculator.walkingMinutes(0.0, TYPICAL_HIKE_GAIN_METERS, calibration)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.TrendingUp,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                R.string.settings_speed_calibration_dplus_preview,
                formatDuration(totalMinutes.roundToInt()),
                formatDuration(dPlusOnlyMinutes.roundToInt()),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PausePreviewRow(pauseFractionPercent: Double) {
    val totalMinutes = TrackStatsCalculator.applyPauseProvision(PAUSE_PREVIEW_WALKING_MINUTES, pauseFractionPercent)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Schedule,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                R.string.settings_speed_calibration_pause_preview,
                formatDuration(totalMinutes.roundToInt()),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// RIC-115 : bornes du curseur : 0 à 35 %, trois libellés qualitatifs répartis sur la plage, pas de
// graduation numérique visible à côté des libellés (voir la maquette biv16-reglages-mockup.html).
// 35 % et non 15-20 % : la médiane réelle mesurée est 12,9 %, le p90 à 26,6 % (CR_RIC115...).
private const val PAUSE_SLIDER_MAX_PERCENT = 35f

@Composable
private fun PauseStatCard(
    pauseFractionPercent: Double,
    enabled: Boolean,
    onValueChange: (Double) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Le remplissage gris plein (surfaceVariant) est ce qui fait lire cette capsule comme
            // désactivée, quel que soit l'état du texte/slider dedans : c'est le même traitement
            // que les capsules vitesse/D+ en lecture seule (StatBox). En Manuel, elles n'utilisent
            // plus StatBox du tout mais un OutlinedTextField (contour, pas de fond) ; un simple
            // contour ici plutôt qu'un remplissage retrouve ce même signal "éditable".
            .then(
                if (enabled) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                },
            )
            .padding(12.dp),
    ) {
        Text(
            stringResource(R.string.settings_pause_slider_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.settings_pause_percent_value, pauseFractionPercent.roundToInt()),
            style = MaterialTheme.typography.titleMedium,
            // RIC-115 : cette capsule reste la même en Manuel qu'en Auto/Sélection (seul le
            // slider ci-dessous bascule enabled/disabled), contrairement à vitesse/D+, qui
            // passent d'une capsule grise en lecture seule à un OutlinedTextField noir en édition.
            // Sans ce contraste, la capsule pause a l'air désactivée même quand elle est éditable.
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Slider(
            value = pauseFractionPercent.toFloat().coerceIn(0f, PAUSE_SLIDER_MAX_PERCENT),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = 0f..PAUSE_SLIDER_MAX_PERCENT,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.settings_pause_slider_min_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.settings_pause_slider_mid_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.settings_pause_slider_max_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// RIC-190 (lot 3 i18n) : un id de ressource et non une chaîne, résolu par l'appelant : le mode de
// calibration est une préférence persistée (data.prefs), son enum ne porte aucun texte.
@StringRes
private fun SpeedCalibrationMode.labelRes(): Int = when (this) {
    SpeedCalibrationMode.MANUAL -> R.string.settings_speed_mode_manual_label
    SpeedCalibrationMode.AUTO -> R.string.settings_speed_mode_auto_label
    SpeedCalibrationMode.SELECTION -> R.string.settings_speed_mode_selection_label
}

// Keeps its own draft text rather than binding directly to the persisted value, so typing "3." or
// briefly clearing the field doesn't fight DataStore's async round trip: a keystroke only commits
// once it parses to a plausible positive number; anything else (empty, a bare "-") is left as
// local, uncommitted editing state.
@Composable
private fun ManualCalibrationFields(manual: SpeedCalibration, onSpeedChanged: (Double) -> Unit, onPenaltyChanged: (Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberField(
            label = stringResource(R.string.settings_manual_speed_field_label),
            unit = stringResource(R.string.settings_unit_kmh),
            value = manual.walkingSpeedKmh,
            valueRange = 0.1..20.0,
            // Caps at "20,0" / "19,9" (4 chars), the longest a valid value in this range can be
            // with one decimal digit, so typing past it is blocked the same way as Pénalité D+,
            // not silently rejected only at parse/commit time.
            maxLength = 4,
            onValueCommitted = onSpeedChanged,
            modifier = Modifier.weight(1f),
        )
        NumberField(
            label = stringResource(R.string.settings_manual_penalty_field_label),
            unit = stringResource(R.string.settings_unit_meters),
            // "+1 km / " prefix mirrors how Auto/Sélection show this same value read-only
            // (CalibrationStatGrid: "+1 km / 100 m"): a bare "100 m/km" needed translating in
            // your head to line up with that phrasing every time you switched modes.
            prefix = stringResource(R.string.settings_manual_penalty_prefix),
            value = manual.elevationGainPenaltyMetersPerKm,
            valueRange = 1.0..999.0,
            maxLength = 3,
            digitsOnly = true,
            onValueCommitted = onPenaltyChanged,
            modifier = Modifier.weight(1f),
        )
    }
}

// The unit sits in a trailing suffix rather than inside the label: "Vitesse à plat (km/h)" was
// wide enough to wrap onto two lines in a half-width field, and the other two modes already show
// the unit next to the value (CalibrationStatGrid) rather than folded into a label.
@Composable
private fun NumberField(
    label: String,
    unit: String,
    value: Double,
    valueRange: ClosedFloatingPointRange<Double>,
    onValueCommitted: (Double) -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    // No limit by default: only Pénalité D+ needs one, to stop a 4th digit from ever appearing
    // rather than letting it show then get silently rejected at parse time.
    maxLength: Int = Int.MAX_VALUE,
    // Pénalité D+ is entiers-only (confirmed): rejects the comma outright rather than letting it
    // through and relying on valueRange/parsing to catch it after the fact.
    digitsOnly: Boolean = false,
) {
    // RIC-187 (lot 0 i18n) : même raison qu'à CalibrationStatGrid, Lint (NonObservableLocale)
    // refuse Locale.getDefault() dans une fonction @Composable.
    val locale = LocalLocale.current.platformLocale
    var draft by remember(value) { mutableStateOf(formatNumber(value, locale)) }
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            // Shrinking is always allowed, even past maxLength: otherwise a field pre-filled with
            // a value longer than the cap (old data, or one entered before this limit existed)
            // could never be edited at all, not even to delete a character.
            val underLimit = text.length <= maxLength || text.length < draft.length
            if (underLimit && (!digitsOnly || text.all(Char::isDigit))) {
                draft = text
                text.replace(',', '.').toDoubleOrNull()?.takeIf { it in valueRange }?.let(onValueCommitted)
            }
        },
        label = { Text(label) },
        prefix = prefix?.let { { Text(it) } },
        suffix = { Text(unit) },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End),
        keyboardOptions = KeyboardOptions(keyboardType = if (digitsOnly) KeyboardType.Number else KeyboardType.Decimal),
        // Typing a value that parses but sits outside valueRange is allowed to show as-is while
        // the user is still typing (never committed, see onValueChange above) but must not
        // survive once they're done editing: revert to the last valid committed value on blur.
        // Also catches a draft that starts out invalid for reasons typing alone can't produce,
        // e.g. a value restored from an old backup that predates this field's constraints.
        modifier = modifier.onFocusChanged { focusState ->
            if (!focusState.isFocused) {
                val parsed = draft.replace(',', '.').toDoubleOrNull()
                if (parsed == null || parsed !in valueRange || draft.length > maxLength) {
                    draft = formatNumber(value, locale)
                }
            }
        },
    )
}

// RIC-190 (lot 3 i18n) : la virgule française n'est plus posée à la main, le séparateur décimal
// vient de la locale (NumberFormat), comme partout ailleurs sur cet écran. Le groupement des
// milliers est explicitement coupé : il n'a aucun sens dans ces plages (0,1 à 20 km/h, 1 à 999 m),
// et l'espace insécable qu'il insérerait casserait la relecture du brouillon en nombre.
//
// La saisie, elle, continue d'accepter les deux séparateurs (voir le replace(',', '.') de
// onValueChange) : c'est une commodité de frappe, pas ce qui est réaffiché.
private fun formatNumber(value: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; isGroupingUsed = false }.format(value)

@Composable
private fun CalibrationStatGrid(calibration: SpeedCalibration) {
    // RIC-187 (lot 0 i18n) : Locale.FRANCE figé remplacé par la locale COMPOSABLE
    // (LocalLocale.current), pas Locale.getDefault() comme ailleurs dans le chantier. Lint
    // (NonObservableLocale, androidx.compose.ui) refuse Locale.getDefault() dans une fonction
    // @Composable : cet appel ne s'abonne à rien, donc un changement de langue par appareil sans
    // relancer l'app (réglages système "Langues de l'application", API 33+) ne redéclenche pas de
    // recomposition et cet écran resterait figé sur l'ancienne langue tant qu'un autre état ne le
    // recompose pas par ailleurs. LocalLocale.current EST un état observable de Compose.
    val locale = LocalLocale.current.platformLocale
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatBox(
            label = stringResource(R.string.settings_manual_speed_field_label),
            value = stringResource(
                R.string.settings_speed_value_format,
                String.format(locale, "%.1f", calibration.walkingSpeedKmh),
            ),
            modifier = Modifier.weight(1f),
        )
        StatBox(
            label = stringResource(R.string.settings_manual_penalty_field_label),
            value = stringResource(
                R.string.settings_penalty_value_format,
                calibration.elevationGainPenaltyMetersPerKm.roundToInt(),
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun NonFreeFeaturesSection(disabled: Boolean, onToggle: (Boolean) -> Unit) {
    SettingsSection(label = stringResource(R.string.settings_nonfree_section_title)) {
        SettingsRow(
            icon = Icons.Default.Shield,
            title = stringResource(R.string.settings_nonfree_toggle_label),
            subtitle = stringResource(R.string.settings_nonfree_toggle_description),
            secondaryAvatar = true,
            trailing = { Switch(checked = disabled, onCheckedChange = onToggle) },
        )
    }
}

/**
 * RIC-152 : la fonctionnalité photos du Journal, débrayable en entier.
 *
 * Activée par défaut, à l'inverse de la bascule non libre juste au-dessus : celle-ci ne protège
 * de rien, elle existe pour qui ne veut simplement pas de photos dans son journal, et n'a donc
 * aucune raison de rendre l'app plus pauvre par défaut.
 *
 * Le sous-titre insiste sur ce qui n'est pas évident : désactiver masque, ne supprime pas. Sans
 * cette phrase, la bascule ressemble à un bouton dangereux et personne ne l'essaie.
 */
@Composable
private fun JournalPhotosSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    storageMode: PhotoStorageMode,
    onStorageModeSelected: (PhotoStorageMode) -> Unit,
    storage: PhotoStorageSummary?,
    onPurgeClick: () -> Unit,
    purgeLocked: Boolean,
    missingPhotoCount: Int,
    onRecoverMissingPhotosClick: () -> Unit,
) {
    SettingsSection(label = stringResource(R.string.settings_photos_section_title)) {
        SettingsRow(
            icon = Icons.Default.PhotoLibrary,
            title = stringResource(R.string.settings_photos_toggle_label),
            subtitle = stringResource(R.string.settings_photos_toggle_description),
            secondaryAvatar = true,
            trailing = { Switch(checked = enabled, onCheckedChange = onToggle) },
        )
        // RIC-157 : masqué quand la fonctionnalité est débrayée, et pas seulement grisé : un
        // réglage qui ne peut plus rien produire n'a rien à faire à l'écran, et c'est déjà la
        // politique du bouton de purge juste en dessous, à l'inverse.
        if (enabled) {
            SettingsRow(
                icon = Icons.Default.PhotoSizeSelectLarge,
                title = stringResource(R.string.settings_photo_storage_mode_row_label),
                subtitle = stringResource(R.string.settings_photo_storage_mode_row_description),
                secondaryAvatar = true,
            )
            PhotoStorageModeChoice(
                mode = storageMode,
                onModeSelected = onStorageModeSelected,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
            )
            // RIC-151 : affichée seulement quand il y a effectivement des fichiers manquants, et
            // pas en permanence. Deux raisons : une action qui ne pourrait que répondre « zéro
            // trouvée » est du bruit dans un écran de réglages, et le nombre est ce qui donne une
            // raison de cliquer, exactement comme la volumétrie sur le bouton de purge. Le relevé
            // est fait à l'ouverture des Réglages, jamais en tâche de fond (arbitrage du
            // pilotage) : le compte peut donc dater de quelques minutes, ce qui est sans
            // conséquence, la recherche repartant de l'état réel du disque.
            if (missingPhotoCount > 0) {
                SettingsRow(
                    icon = Icons.Default.ImageSearch,
                    title = stringResource(R.string.settings_missing_photos_row_label),
                    subtitle = pluralStringResource(
                        R.plurals.settings_missing_photos_row_description,
                        missingPhotoCount,
                        missingPhotoCount,
                    ),
                    secondaryAvatar = true,
                )
                OutlinedButton(
                    onClick = onRecoverMissingPhotosClick,
                    // Même grisage que la purge : la recherche écrit elle aussi dans photos/.
                    enabled = !purgeLocked,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                ) {
                    Icon(Icons.Default.ImageSearch, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_missing_photos_row_label))
                }
            }
        }
        // RIC-152 : la contrepartie du « rien n'est supprimé » ci-dessus. Elle n'apparaît qu'une
        // fois la fonctionnalité débrayée ET s'il reste effectivement des photos : proposer de
        // purger une banque vide n'aurait aucun sens, et le proposer alors que la fonctionnalité
        // tourne encore transformerait une bascule réversible en piège.
        //
        // La volumétrie est dans le libellé du bouton et pas seulement dans le dialogue : c'est
        // elle qui donne une raison de cliquer, et c'est la question qu'on se pose en arrivant ici.
        if (!enabled && storage != null && storage.count > 0) {
            OutlinedButton(
                onClick = onPurgeClick,
                // RIC-158 : même grisage que Sauvegarder/Restaurer pendant qu'une autre opération
                // longue tourne : la purge en est une elle aussi désormais.
                enabled = !purgeLocked,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        R.string.settings_photo_purge_button,
                        formatPhotoStorage(LocalContext.current, storage),
                    ),
                )
            }
        }
    }
}

/**
 * RIC-151 : le rapport de fin de la recherche des photos manquantes.
 *
 * « Retrouvée mais modifiée » est dit à part et en toutes lettres : c'est le cas d'un service de
 * sauvegarde photo qui a recompressé la pellicule, et il faut que l'utilisateur comprenne que rien
 * n'a été adopté à la place. Adopter une image approchante serait réécrire son carnet.
 */
internal fun photoRecoveryReportMessage(context: Context, report: LoggedTrackRepository.PhotoRecoveryReport): String {
    val lines = mutableListOf<String>()
    lines += if (report.recovered > 0) {
        // RIC-190 (lot 3 i18n) : chaque ligne est un <plurals> portant la phrase ENTIÈRE, et non
        // un fragment recollé : « Sa fiche est conservée » / « Leurs fiches sont conservées » ne
        // s'accorde pas au seul groupe nominal du début.
        context.resources.getQuantityString(
            R.plurals.settings_photo_recovery_recovered_line,
            report.recovered,
            report.recovered,
        )
    } else {
        context.getString(R.string.settings_photo_recovery_none_line)
    }
    if (report.modifiedNotAdopted > 0) {
        lines += context.resources.getQuantityString(
            R.plurals.settings_photo_recovery_modified_line,
            report.modifiedNotAdopted,
            report.modifiedNotAdopted,
        )
    }
    if (report.notFound > 0) {
        lines += context.resources.getQuantityString(
            R.plurals.settings_photo_recovery_notfound_line,
            report.notFound,
            report.notFound,
        )
    }
    return lines.joinToString("\n\n")
}

/** RIC-152 : « 12 photos, 34,5 Mo » : ce que la purge va retirer, lignes et fichiers. */
internal fun formatPhotoStorage(context: Context, storage: PhotoStorageSummary): String {
    val photos = context.resources.getQuantityString(
        R.plurals.settings_photo_count_label,
        storage.count,
        storage.count,
    )
    return context.getString(R.string.settings_photo_storage_summary, photos, formatBytes(context, storage.totalBytes))
}

/**
 * Taille lisible en unités décimales (Mo = 10^6 octets), comme les fabricants et les Réglages
 * d'Android l'affichent, et non en Mio, qui donnerait un chiffre différent de celui que le système
 * annonce pour la même app, sans que personne puisse expliquer l'écart.
 */
// RIC-187 (lot 0 i18n) : Locale.FRANCE figé remplacé par Locale.getDefault() pour le SÉPARATEUR
// DÉCIMAL. RIC-190 (lot 3) : les unités "Go"/"Mo"/"Ko"/"o", restées en dur au lot 0, viennent
// maintenant des ressources ("GB"/"MB"/"kB"/"B" en anglais) : d'où le Context. Le nombre et son
// unité restent séparés, la ressource ne portant que l'unité et l'espace qui la précède.
internal fun formatBytes(context: Context, bytes: Long): String = when {
    bytes >= 1_000_000_000L ->
        context.getString(R.string.settings_format_bytes_go, String.format(Locale.getDefault(), "%.1f", bytes / 1_000_000_000.0))
    bytes >= 1_000_000L ->
        context.getString(R.string.settings_format_bytes_mo, String.format(Locale.getDefault(), "%.1f", bytes / 1_000_000.0))
    bytes >= 1_000L ->
        context.getString(R.string.settings_format_bytes_ko, String.format(Locale.getDefault(), "%.0f", bytes / 1_000.0))
    else -> context.getString(R.string.settings_format_bytes_o, bytes)
}

@Composable
private fun DataSection(
    lastBackupAtMillis: Long?,
    operationsLocked: Boolean,
    onBackupClick: () -> Unit,
    onRestoreClick: () -> Unit,
    onStorageUsageClick: () -> Unit,
) {
    SettingsSection(label = stringResource(R.string.settings_data_section_title)) {
        SettingsRow(
            icon = Icons.Default.CloudUpload,
            title = stringResource(R.string.settings_backup_row_label),
            subtitle = lastBackupAtMillis
                ?.let { stringResource(R.string.settings_backup_last_date, formatBackupTimestamp(it)) }
                ?: stringResource(R.string.settings_backup_never),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Default Button/OutlinedButton content padding (24dp horizontal) left "Sauvegarder"
            // wrapping onto two lines once squeezed into a half-width slot alongside its icon:
            // trimmed padding and a smaller icon/gap buy back just enough width.
            val buttonContentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
            // RIC-156 : plus de tourniquet dans le bouton : le dialogue bloquant est désormais le
            // seul indicateur d'avancement, et il applique l'anti-flash. Un tourniquet ici le
            // court-circuiterait en clignotant sur les opérations très courtes, exactement le
            // défaut qu'on corrige. Reste le grisage, qui est ce qui rend le chevauchement
            // inatteignable plutôt que simplement refusé.
            Button(
                onClick = onBackupClick,
                enabled = !operationsLocked,
                contentPadding = buttonContentPadding,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.settings_backup_button), maxLines = 1)
            }
            OutlinedButton(
                onClick = onRestoreClick,
                enabled = !operationsLocked,
                contentPadding = buttonContentPadding,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                // Même mot que le bouton de confirmation du dialogue de restauration : une seule
                // ressource, l'inventaire n'en prévoyait pas de seconde.
                Text(stringResource(R.string.settings_restore_confirm_button), maxLines = 1)
            }
        }
        // RIC-140 : dans « Données » et non dans « Photos du Journal », alors que les photos en
        // sont le plus gros poste : ce que cet écran raconte, c'est tout ce que l'app occupe, base
        // et traces comprises. Le chiffre n'est pas affiché ici : il coûte plusieurs centaines
        // d'accès disque (voir AppStorageUsageCalculator), et les Réglages ne les paieraient que
        // pour orner une ligne que personne n'ouvre.
        SettingsRow(
            icon = Icons.Default.PieChart,
            title = stringResource(R.string.settings_storage_usage_row_label),
            subtitle = stringResource(R.string.settings_storage_usage_row_description),
            onClick = onStorageUsageClick,
            trailing = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

// RIC-187 (lot 0 i18n) : motif en dur ("d MMMM 'à' HH:mm", Locale.FRANCE) remplacé par un style
// localisé, comme JournalScreen.formatStartedAtWithTime (même choix : FormatStyle.LONG pour la
// date, SHORT pour l'heure). Gagne l'année au passage ("14 avril 2026 19:00" au lieu de "14 avril
// à 19:00", qui n'en portait aucune) : écart mineur assumé, pas de style "jour+mois+heure sans
// année" tout fait en FormatStyle, et l'année en plus est plutôt utile sur un écran de réglages
// qui affiche la date de la dernière sauvegarde.
internal fun formatBackupTimestamp(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

// RIC-190 (lot 3 i18n) : @StringRes et non une chaîne. Ces libellés sont des noms propres,
// identiques dans les deux langues, mais ils vivent dans une liste construite à l'initialisation du
// fichier, sans Context : le même geste que pour les enums du lot.
private data class CreditLink(@StringRes val labelRes: Int, val url: String)

// stax-api rides alongside aalto-xml (see the comment on the aalto-xml/stax-api dependencies in
// app/build.gradle.kts) to make GPX parsing work at all on Android: a real bundled third-party
// library, not just an internal implementation detail, so it earns its own credit here too.
private val MAP_LAYER_CREDITS = listOf(
    CreditLink(R.string.settings_credits_esri_name, "https://www.esri.com"),
    CreditLink(R.string.settings_credits_osm_name, "https://www.openstreetmap.org"),
    CreditLink(R.string.settings_credits_opentopo_name, "https://opentopomap.org"),
)
private val WEATHER_CREDITS = listOf(CreditLink(R.string.settings_credits_meteoblue_name, "https://www.meteoblue.com"))
private val LIBRARY_CREDITS = listOf(
    CreditLink(R.string.settings_credits_osmdroid_name, "https://github.com/osmdroid/osmdroid"),
    CreditLink(R.string.settings_credits_jpx_name, "https://github.com/jenetics/jpx"),
    CreditLink(R.string.settings_credits_aaltoxml_name, "https://github.com/FasterXML/aalto-xml"),
    CreditLink(R.string.settings_credits_staxapi_name, "https://mvnrepository.com/artifact/javax.xml.stream/stax-api"),
)
private val LICENSE_CREDITS = listOf(
    CreditLink(R.string.settings_credits_license_name, "https://www.gnu.org/licenses/gpl-3.0.html"),
)

@Composable
private fun CreditsSection(onOpenUrl: (String) -> Unit) {
    SettingsSection(label = stringResource(R.string.settings_credits_section_title)) {
        CreditRow(stringResource(R.string.settings_credits_maps_label), MAP_LAYER_CREDITS, onOpenUrl)
        CreditRow(stringResource(R.string.settings_credits_weather_label), WEATHER_CREDITS, onOpenUrl)
        CreditRow(stringResource(R.string.settings_credits_libraries_label), LIBRARY_CREDITS, onOpenUrl)
        StaticCreditRow(
            stringResource(R.string.settings_credits_dev_label),
            stringResource(R.string.settings_credits_dev_value),
        )
        CreditRow(stringResource(R.string.settings_credits_license_label), LICENSE_CREDITS, onOpenUrl)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenUrl(BIVOUAC_GITHUB_URL) }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_credits_github_link),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreditRow(label: String, links: List<CreditLink>, onOpenUrl: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        FlowRow(
            modifier = Modifier.weight(1f, fill = false),
            horizontalArrangement = Arrangement.End,
        ) {
            links.forEachIndexed { index, link ->
                if (index > 0) {
                    Text(" · ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    stringResource(link.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenUrl(link.url) },
                )
            }
        }
    }
}

@Composable
private fun StaticCreditRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
    }
}

package com.bivouac.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.SpeedCalibrationCalculator
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.prefs.SpeedCalibrationMode
import com.bivouac.app.settings.RestoreOutcome
import com.bivouac.app.settings.SettingsViewModel
import com.bivouac.app.ui.components.formatDuration
import com.bivouac.app.ui.nav.AppScreenHeader
import com.bivouac.app.ui.nav.AppSection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val BIVOUAC_GITHUB_URL = "https://github.com/sebastienraison/Bivouac"

/**
 * Réglages (BIV-16): Vitesse personnalisée, fonctions non libres, sauvegarde/restauration
 * (BIV-66) et crédits. Itinéraires (BIV-23, future clé API OpenRouteService) has a reserved slot
 * in the ticket but no UI yet — nothing to render until a feature actually consumes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    currentSection: AppSection,
    onSectionSelected: (AppSection) -> Unit,
    onOpenJournalSelection: () -> Unit,
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
    val lastBackupAtMillis by viewModel.lastBackupAtMillis.collectAsStateWithLifecycle()
    val backupInProgress by viewModel.backupInProgress.collectAsStateWithLifecycle()
    val restoreInProgress by viewModel.restoreInProgress.collectAsStateWithLifecycle()
    val backupError by viewModel.backupError.collectAsStateWithLifecycle()
    val restoreOutcome by viewModel.restoreOutcome.collectAsStateWithLifecycle()

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        uri?.let { viewModel.backup(it) }
    }
    // Picking a file only stages it — restoring overwrites the current database, so it still
    // needs an explicit confirmation below before viewModel.restore() actually runs.
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    // "*/*" rather than a strict zip mimeType: several file pickers/providers don't tag a .zip
    // correctly, same reasoning already applied to the GPX pickers elsewhere in the app.
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pendingRestoreUri = uri
    }

    // Même en-tête que l'accueil du Journal (RIC-65), via le composant partagé AppScreenHeader :
    // une première version recopiée à la main avait laissé le titre sur la couleur de texte par
    // défaut (noire), faute d'être posée dans un Surface/Scaffold comme celle du Journal — invisible
    // en thème clair par coïncidence, noir sur noir en thème sombre. Scaffold fournit ce Surface.
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppScreenHeader(title = "Réglages", currentSection = currentSection, onSectionSelected = onSectionSelected) },
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
            DataSection(
                lastBackupAtMillis = lastBackupAtMillis,
                backupInProgress = backupInProgress,
                restoreInProgress = restoreInProgress,
                onBackupClick = { backupLauncher.launch(suggestedBackupFileName()) },
                onRestoreClick = { restoreLauncher.launch(arrayOf("*/*")) },
            )
            CreditsSection(
                onOpenUrl = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            )
        }
    }

    backupError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissBackupError,
            title = { Text("Sauvegarde impossible") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissBackupError) { Text("OK") } },
        )
    }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Restaurer cette sauvegarde ?") },
            text = { Text("Les randonnées et réglages actuels seront remplacés par le contenu de cette sauvegarde. Cette action est irréversible.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restore(uri)
                    pendingRestoreUri = null
                }) { Text("Restaurer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text("Annuler") }
            },
        )
    }

    restoreOutcome?.let { outcome ->
        when (outcome) {
            is RestoreOutcome.PendingRestart -> AlertDialog(
                // Not dismissible without restarting: the app's in-memory state is stale the
                // instant the on-disk files get swapped underneath it (see AppRestart's kdoc).
                onDismissRequest = {},
                title = { Text("Restauration terminée") },
                text = { Text("La base a été restaurée. L'application va redémarrer pour appliquer les changements.") },
                confirmButton = { TextButton(onClick = viewModel::confirmRestartAfterRestore) { Text("Redémarrer") } },
            )
            is RestoreOutcome.VersionTooNew -> AlertDialog(
                onDismissRequest = viewModel::dismissRestoreOutcome,
                title = { Text("Sauvegarde trop récente") },
                text = {
                    Text(
                        "Cette sauvegarde provient d'une version plus récente de l'application " +
                            "(schéma ${outcome.backupVersion}, version actuelle : schéma ${outcome.appVersion}). " +
                            "Mets à jour Bivouac avant de la restaurer.",
                    )
                },
                confirmButton = { TextButton(onClick = viewModel::dismissRestoreOutcome) { Text("OK") } },
            )
            is RestoreOutcome.Error -> AlertDialog(
                onDismissRequest = viewModel::dismissRestoreOutcome,
                title = { Text("Restauration impossible") },
                text = { Text(outcome.message) },
                confirmButton = { TextButton(onClick = viewModel::dismissRestoreOutcome) { Text("OK") } },
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
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
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
    // MIN_TRACKS_FOR_CALIBRATION hikes to ever compute more than the default — gated on the
    // Journal's total rather than on Sélection's own confirmed count, since that's the real
    // ceiling either mode can reach (BIV-16 recette). Whichever mode is already active stays
    // reachable even if the Journal has since shrunk below that floor — see the note below.
    val calibrationModesUsable = journalTrackCount >= SpeedCalibrationCalculator.MIN_TRACKS_FOR_CALIBRATION
    SettingsSection(label = "Vitesse personnalisée") {
        SettingsRow(
            icon = Icons.Default.Speed,
            title = "Mode de calcul",
            subtitle = "Utilisé pour estimer la durée des randonnées",
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            SpeedCalibrationMode.entries.forEachIndexed { index, candidate ->
                SegmentedButton(
                    selected = candidate == mode,
                    onClick = { onModeSelected(candidate) },
                    // Never locks the user OUT of their own current mode just because the Journal
                    // shrank after the fact — only blocks switching INTO Auto/Sélection from
                    // somewhere else when there isn't enough data for either to mean anything.
                    enabled = candidate == SpeedCalibrationMode.MANUAL || candidate == mode || calibrationModesUsable,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SpeedCalibrationMode.entries.size),
                    label = { Text(candidate.label()) },
                )
            }
        }
        if (!calibrationModesUsable) {
            Text(
                "Auto et Sélection demandent au moins 2 randonnées dans le Journal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp),
            )
        }
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).padding(top = 6.dp, bottom = 12.dp)) {
            when (mode) {
                SpeedCalibrationMode.MANUAL -> {
                    Text(
                        "Saisies directement, jamais recalculées automatiquement.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    ManualCalibrationFields(manual, onManualSpeedChanged, onManualPenaltyChanged)
                }
                SpeedCalibrationMode.AUTO -> {
                    Text(
                        "Calculées à partir de toutes les randonnées du Journal, recalculées à chaque nouvel import.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    CalibrationStatGrid(auto)
                }
                SpeedCalibrationMode.SELECTION -> {
                    Text(
                        when (selectedTrackCount) {
                            0 -> "Aucune trace choisie pour l'instant — calibration par défaut en attendant."
                            // A single hike can't separate two unknowns (vitesse et pénalité D+)
                            // from one another — the maths behind CalibrationStatGrid genuinely
                            // has no way to isolate D+ from just one data point, so it's kept at
                            // sa valeur par défaut rather than showing a number that looks computed
                            // but isn't. Same reasoning applies with more traces if their profil
                            // (rapport dénivelé/distance) est trop similaire d'une trace à l'autre.
                            1 -> "Calculée à partir d'une seule trace : la vitesse s'ajuste, mais la " +
                                "pénalité D+ ne peut pas être isolée avec un seul point de mesure — " +
                                "elle reste à sa valeur par défaut. Choisis au moins 2 randonnées de " +
                                "profils différents (plate et pentue) pour l'affiner aussi."
                            else -> "Calculées à partir de $selectedTrackCount traces choisies dans le Journal."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    CalibrationStatGrid(selection)
                    TextButton(onClick = onChooseTracksClick, modifier = Modifier.align(Alignment.End)) {
                        Text("Choisir les traces")
                    }
                }
            }

            // RIC-115 : provision de pause — aucune séparation visuelle avec le bloc
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

// Rando type purement illustrative (RIC-115) — codée en dur, ne dépend d'aucune trace réelle. Sert
// à rendre lisible la pénalité D+ (m/km), un chiffre autrement abstrait, dans les 3 modes.
private const val TYPICAL_HIKE_DISTANCE_METERS = 15_000.0
private const val TYPICAL_HIKE_GAIN_METERS = 600.0

// Base de l'aperçu de provision de pause (RIC-115) — 6h de marche pure, également codées en dur.
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
            "Rando type de 15 km, 600 m de D+ → ${formatDuration(totalMinutes.roundToInt())} " +
                "(dont ${formatDuration(dPlusOnlyMinutes.roundToInt())} dus au D+)",
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
            "Rando estimée à 6 h de marche → ${formatDuration(totalMinutes.roundToInt())} avec cette provision",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// RIC-115 : bornes du curseur — 0 à 35 %, trois libellés qualitatifs répartis sur la plage, pas de
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
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text("Pauses pendant la marche", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${pauseFractionPercent.roundToInt()} %",
            style = MaterialTheme.typography.titleMedium,
            // RIC-115 : cette capsule reste la même en Manuel qu'en Auto/Sélection (seul le
            // slider ci-dessous bascule enabled/disabled) — contrairement à vitesse/D+, qui
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
                "Je ne m'arrête pas ou presque",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Je fais quelques pauses",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Je fais beaucoup de pauses",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun SpeedCalibrationMode.label(): String = when (this) {
    SpeedCalibrationMode.MANUAL -> "Manuel"
    SpeedCalibrationMode.AUTO -> "Auto"
    SpeedCalibrationMode.SELECTION -> "Sélection"
}

// Keeps its own draft text rather than binding directly to the persisted value, so typing "3." or
// briefly clearing the field doesn't fight DataStore's async round trip — a keystroke only commits
// once it parses to a plausible positive number; anything else (empty, a bare "-") is left as
// local, uncommitted editing state.
@Composable
private fun ManualCalibrationFields(manual: SpeedCalibration, onSpeedChanged: (Double) -> Unit, onPenaltyChanged: (Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberField(
            label = "Vitesse à plat",
            unit = "km/h",
            value = manual.walkingSpeedKmh,
            valueRange = 0.1..20.0,
            // Caps at "20,0" / "19,9" (4 chars) — the longest a valid value in this range can be
            // with one decimal digit — so typing past it is blocked the same way as Pénalité D+,
            // not silently rejected only at parse/commit time.
            maxLength = 4,
            onValueCommitted = onSpeedChanged,
            modifier = Modifier.weight(1f),
        )
        NumberField(
            label = "Pénalité D+",
            unit = "m",
            // "+1 km / " prefix mirrors how Auto/Sélection show this same value read-only
            // (CalibrationStatGrid: "+1 km / 100 m") — a bare "100 m/km" needed translating in
            // your head to line up with that phrasing every time you switched modes.
            prefix = "+1 km / ",
            value = manual.elevationGainPenaltyMetersPerKm,
            valueRange = 1.0..999.0,
            maxLength = 3,
            digitsOnly = true,
            onValueCommitted = onPenaltyChanged,
            modifier = Modifier.weight(1f),
        )
    }
}

// The unit sits in a trailing suffix rather than inside the label — "Vitesse à plat (km/h)" was
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
    // No limit by default — only Pénalité D+ needs one, to stop a 4th digit from ever appearing
    // rather than letting it show then get silently rejected at parse time.
    maxLength: Int = Int.MAX_VALUE,
    // Pénalité D+ is entiers-only (confirmed) — rejects the comma outright rather than letting it
    // through and relying on valueRange/parsing to catch it after the fact.
    digitsOnly: Boolean = false,
) {
    var draft by remember(value) { mutableStateOf(formatNumber(value)) }
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            // Shrinking is always allowed, even past maxLength — otherwise a field pre-filled with
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
        // the user is still typing (never committed — see onValueChange above) but must not
        // survive once they're done editing: revert to the last valid committed value on blur.
        // Also catches a draft that starts out invalid for reasons typing alone can't produce,
        // e.g. a value restored from an old backup that predates this field's constraints.
        modifier = modifier.onFocusChanged { focusState ->
            if (!focusState.isFocused) {
                val parsed = draft.replace(',', '.').toDoubleOrNull()
                if (parsed == null || parsed !in valueRange || draft.length > maxLength) {
                    draft = formatNumber(value)
                }
            }
        },
    )
}

// French convention (comma) to match every other number shown on this screen — accepting a typed
// "." in onValueChange above is purely an input convenience, not what gets displayed back.
private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString().replace('.', ',')

@Composable
private fun CalibrationStatGrid(calibration: SpeedCalibration) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatBox(
            label = "Vitesse à plat",
            value = String.format(Locale.FRANCE, "%.1f km/h", calibration.walkingSpeedKmh),
            modifier = Modifier.weight(1f),
        )
        StatBox(
            label = "Pénalité D+",
            value = "+1 km / ${calibration.elevationGainPenaltyMetersPerKm.roundToInt()} m",
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
    SettingsSection(label = "Fonctionnalités non libres") {
        SettingsRow(
            icon = Icons.Default.Shield,
            title = "Désactiver les fonctions non libres",
            subtitle = "Coupe le fond satellite Esri et le lien météo Meteoblue",
            secondaryAvatar = true,
            trailing = { Switch(checked = disabled, onCheckedChange = onToggle) },
        )
    }
}

@Composable
private fun DataSection(
    lastBackupAtMillis: Long?,
    backupInProgress: Boolean,
    restoreInProgress: Boolean,
    onBackupClick: () -> Unit,
    onRestoreClick: () -> Unit,
) {
    SettingsSection(label = "Données") {
        SettingsRow(
            icon = Icons.Default.CloudUpload,
            title = "Sauvegarde de la base",
            subtitle = lastBackupAtMillis?.let { "Dernière sauvegarde : ${formatBackupTimestamp(it)}" }
                ?: "Aucune sauvegarde effectuée pour l'instant",
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Default Button/OutlinedButton content padding (24dp horizontal) left "Sauvegarder"
            // wrapping onto two lines once squeezed into a half-width slot alongside its icon —
            // trimmed padding and a smaller icon/gap buy back just enough width.
            val buttonContentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
            Button(
                onClick = onBackupClick,
                enabled = !backupInProgress,
                contentPadding = buttonContentPadding,
                modifier = Modifier.weight(1f),
            ) {
                if (backupInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sauvegarder", maxLines = 1)
                }
            }
            OutlinedButton(
                onClick = onRestoreClick,
                enabled = !restoreInProgress,
                contentPadding = buttonContentPadding,
                modifier = Modifier.weight(1f),
            ) {
                if (restoreInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restaurer", maxLines = 1)
                }
            }
        }
    }
}

private fun formatBackupTimestamp(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("d MMMM 'à' HH:mm", Locale.FRANCE)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

private data class CreditLink(val label: String, val url: String)

// stax-api rides alongside aalto-xml (see the comment on the aalto-xml/stax-api dependencies in
// app/build.gradle.kts) to make GPX parsing work at all on Android — a real bundled third-party
// library, not just an internal implementation detail, so it earns its own credit here too.
private val MAP_LAYER_CREDITS = listOf(
    CreditLink("Esri", "https://www.esri.com"),
    CreditLink("OpenStreetMap", "https://www.openstreetmap.org"),
    CreditLink("OpenTopoMap", "https://opentopomap.org"),
)
private val WEATHER_CREDITS = listOf(CreditLink("Meteoblue", "https://www.meteoblue.com"))
private val LIBRARY_CREDITS = listOf(
    CreditLink("osmdroid", "https://github.com/osmdroid/osmdroid"),
    CreditLink("JPX", "https://github.com/jenetics/jpx"),
    CreditLink("aalto-xml", "https://github.com/FasterXML/aalto-xml"),
    CreditLink("stax-api", "https://mvnrepository.com/artifact/javax.xml.stream/stax-api"),
)
private val LICENSE_CREDITS = listOf(CreditLink("GPLv3", "https://www.gnu.org/licenses/gpl-3.0.html"))

@Composable
private fun CreditsSection(onOpenUrl: (String) -> Unit) {
    SettingsSection(label = "Crédits") {
        CreditRow("Fonds de carte", MAP_LAYER_CREDITS, onOpenUrl)
        CreditRow("Météo", WEATHER_CREDITS, onOpenUrl)
        CreditRow("Bibliothèques", LIBRARY_CREDITS, onOpenUrl)
        StaticCreditRow("Développement", "Sébastien Raison, avec Claude (Anthropic)")
        CreditRow("Licence", LICENSE_CREDITS, onOpenUrl)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenUrl(BIVOUAC_GITHUB_URL) }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Code source sur GitHub", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
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
                    link.label,
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

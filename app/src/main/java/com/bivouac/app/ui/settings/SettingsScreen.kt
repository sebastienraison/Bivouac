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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.prefs.SpeedCalibrationMode
import com.bivouac.app.settings.RestoreOutcome
import com.bivouac.app.settings.SettingsViewModel
import com.bivouac.app.ui.nav.AppSection
import com.bivouac.app.ui.nav.SectionMenuButton
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
    val nonFreeFeaturesDisabled by viewModel.nonFreeFeaturesDisabled.collectAsStateWithLifecycle()
    val lastBackupAtMillis by viewModel.lastBackupAtMillis.collectAsStateWithLifecycle()
    val backupInProgress by viewModel.backupInProgress.collectAsStateWithLifecycle()
    val restoreInProgress by viewModel.restoreInProgress.collectAsStateWithLifecycle()
    val backupError by viewModel.backupError.collectAsStateWithLifecycle()
    val restoreOutcome by viewModel.restoreOutcome.collectAsStateWithLifecycle()

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        uri?.let { viewModel.backup(it) }
    }
    // "*/*" rather than a strict zip mimeType: several file pickers/providers don't tag a .zip
    // correctly, same reasoning already applied to the GPX pickers elsewhere in the app.
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.restore(it) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 88.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            SpeedCalibrationSection(
                mode = mode,
                manual = manual,
                auto = auto,
                selection = selection,
                selectedTrackCount = selectedTrackCount,
                onModeSelected = viewModel::setMode,
                onManualSpeedChanged = viewModel::setManualSpeed,
                onManualPenaltyChanged = viewModel::setManualPenalty,
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
                onGitHubClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BIVOUAC_GITHUB_URL))) },
            )
        }

        SectionMenuButton(
            current = currentSection,
            onSelect = onSectionSelected,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
        )
    }

    backupError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissBackupError,
            title = { Text("Sauvegarde impossible") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissBackupError) { Text("OK") } },
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
    onModeSelected: (SpeedCalibrationMode) -> Unit,
    onManualSpeedChanged: (Double) -> Unit,
    onManualPenaltyChanged: (Double) -> Unit,
    onChooseTracksClick: () -> Unit,
) {
    SettingsSection(label = "Vitesse personnalisée") {
        SettingsRow(
            icon = Icons.Default.Speed,
            title = "Mode de calcul",
            subtitle = "Utilisée pour estimer la durée des randonnées",
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
        ) {
            SpeedCalibrationMode.entries.forEachIndexed { index, candidate ->
                SegmentedButton(
                    selected = candidate == mode,
                    onClick = { onModeSelected(candidate) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SpeedCalibrationMode.entries.size),
                    label = { Text(candidate.label()) },
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).padding(bottom = 12.dp)) {
            when (mode) {
                SpeedCalibrationMode.MANUAL -> ManualCalibrationFields(manual, onManualSpeedChanged, onManualPenaltyChanged)
                SpeedCalibrationMode.AUTO -> {
                    Text(
                        "Calculée à partir de toutes les randonnées du Journal, recalculée à chaque nouvel import.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    CalibrationStatGrid(auto)
                }
                SpeedCalibrationMode.SELECTION -> {
                    Text(
                        if (selectedTrackCount == 0) {
                            "Aucune trace choisie pour l'instant — calibration par défaut en attendant."
                        } else {
                            "Calculée à partir de $selectedTrackCount " +
                                (if (selectedTrackCount > 1) "traces choisies" else "trace choisie") +
                                " dans le Journal."
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
            label = "Vitesse à plat (km/h)",
            value = manual.walkingSpeedKmh,
            onValueCommitted = onSpeedChanged,
            modifier = Modifier.weight(1f),
        )
        NumberField(
            label = "Pénalité D+ (m/km)",
            value = manual.elevationGainPenaltyMetersPerKm,
            onValueCommitted = onPenaltyChanged,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NumberField(label: String, value: Double, onValueCommitted: (Double) -> Unit, modifier: Modifier = Modifier) {
    var draft by remember(value) { mutableStateOf(formatNumber(value)) }
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            draft = text
            text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }?.let(onValueCommitted)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

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
            Button(onClick = onBackupClick, enabled = !backupInProgress, modifier = Modifier.weight(1f)) {
                if (backupInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sauvegarder")
                }
            }
            OutlinedButton(onClick = onRestoreClick, enabled = !restoreInProgress, modifier = Modifier.weight(1f)) {
                if (restoreInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restaurer")
                }
            }
        }
    }
}

private fun formatBackupTimestamp(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("d MMMM 'à' HH:mm", Locale.FRANCE)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

@Composable
private fun CreditsSection(onGitHubClick: () -> Unit) {
    SettingsSection(label = "Crédits") {
        CreditRow("Fonds de carte", "Esri · OpenStreetMap · OpenTopoMap")
        CreditRow("Météo", "Meteoblue")
        CreditRow("Bibliothèques", "osmdroid · JPX · aalto-xml")
        CreditRow("Développement", "Sébastien Raison, avec Claude (Anthropic)")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onGitHubClick)
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

@Composable
private fun CreditRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
    }
}

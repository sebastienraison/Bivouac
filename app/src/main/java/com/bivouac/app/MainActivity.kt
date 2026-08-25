package com.bivouac.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bivouac.app.data.prefs.AppSectionPreferences
import com.bivouac.app.journal.DuplicatePlanRequest
import com.bivouac.app.ui.gpximport.GpxImportScreen
import com.bivouac.app.ui.journal.JournalScreen
import com.bivouac.app.ui.nav.AppSection
import com.bivouac.app.ui.nav.UniverseChoiceDialog
import com.bivouac.app.ui.settings.SettingsScreen
import com.bivouac.app.ui.startup.ElevationBackfillGate
import com.bivouac.app.ui.theme.BivouacTheme
import kotlinx.coroutines.launch

private const val JOURNAL_CALIBRATION_ROUTE = "journal_calibration"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val incomingGpxUris = intent.extractGpxUris()
        setContent {
            BivouacTheme {
                // RIC-19 §5 : rattrapage bloquant des colonnes d'altitude, avant toute navigation —
                // englobe BivouacApp entier (NavHost compris) plutôt que d'être posé à l'intérieur,
                // pour qu'aucune section ne soit ne serait-ce que composée pendant le rattrapage.
                ElevationBackfillGate(modifier = Modifier.fillMaxSize()) {
                    BivouacApp(modifier = Modifier.fillMaxSize(), incomingGpxUris = incomingGpxUris)
                }
            }
        }
    }
}

@Composable
private fun BivouacApp(modifier: Modifier = Modifier, incomingGpxUris: List<Uri> = emptyList()) {
    val navController = rememberNavController()

    // RIC-40 : une boîte aux lettres entre les ViewModels du Journal et de la Planification, qui
    // ne se voient jamais autrement — ce composable est le seul endroit où les deux écrans sont
    // atteignables à la fois. Ici plutôt que dans l'un des deux ViewModels, ou dans un dépôt
    // partagé : une duplication est un passage de relais ponctuel, pas un état que l'un des deux
    // écrans possède durablement.
    var pendingDuplicate by remember { mutableStateOf<DuplicatePlanRequest?>(null) }

    // RIC-104 : tant que ce choix n'est pas tranché, ni la Planification ni le Journal ne savent
    // quoi faire du fichier — voir UniverseChoiceDialog.
    //
    // rememberSaveable, et un drapeau plutôt que la liste elle-même : une rotation ou un passage en
    // mode sombre détruit et recrée l'Activity, onCreate relit l'intent de lancement — que le
    // système conserve — et en retire les mêmes Uri. Un simple remember repartirait donc de zéro et
    // rouvrirait le dialogue par-dessus l'écran, alors même que l'utilisateur vient d'y répondre.
    // C'est le même piège que celui déjà désamorcé côté Planification pour l'import (voir le
    // LaunchedEffect de GpxImportScreen), qui se rejoue ici un cran plus haut.
    var universeChoiceResolved by rememberSaveable { mutableStateOf(false) }
    val universeChoicePending = incomingGpxUris.takeIf { it.isNotEmpty() && !universeChoiceResolved }
    // Mêmes boîtes aux lettres que pendingDuplicate ci-dessus, remplies une fois le choix
    // d'univers tranché.
    var incomingPlanificationUri by remember { mutableStateOf<Uri?>(null) }
    var incomingJournalUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // RIC-106 : dernier univers consulté, lu une seule fois au démarrage — NavHost fige son
    // startDestination à la composition initiale, le changer ensuite n'a aucun effet. Tant que ce
    // premier chargement DataStore n'a pas abouti, `startSection` reste null et rien ne navigue
    // encore ; en pratique quasi instantané (lecture mémoire), pas une vraie latence perçue.
    val context = LocalContext.current
    val appSectionPreferences = remember { AppSectionPreferences(context) }
    val coroutineScope = rememberCoroutineScope()
    val startSection by appSectionPreferences.lastVisitedSection.collectAsStateWithLifecycle(initialValue = null)

    // Standard top-level-destination navigation: pop back to the graph's start so switching
    // sections never piles up a back stack, but save/restore each section's own state (scroll
    // position, and — via the ViewModel's own store — the trace currently open in Planification)
    // across switches.
    fun onSectionSelected(section: AppSection) {
        navController.navigate(section.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        // RIC-106 : Réglages n'est jamais un univers d'accueil, voir AppSectionPreferences —
        // le no-op y est géré côté préférences plutôt que dupliqué ici à chaque appelant.
        coroutineScope.launch { appSectionPreferences.setLastVisitedSection(section) }
    }

    // Capturé dans un val local : `startSection` reste une propriété déléguée (State<AppSection?>),
    // dont le compilateur ne garantit pas le smart-cast après ce contrôle de nullité.
    val resolvedStartSection = startSection
    if (resolvedStartSection == null) {
        Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    NavHost(navController = navController, startDestination = resolvedStartSection.route, modifier = modifier) {
        composable(AppSection.PLANIFICATION.route) {
            GpxImportScreen(
                modifier = Modifier.fillMaxSize(),
                incomingGpxUri = incomingPlanificationUri,
                hasPendingExternalChoice = universeChoicePending != null,
                currentSection = AppSection.PLANIFICATION,
                onSectionSelected = ::onSectionSelected,
                pendingDuplicate = pendingDuplicate,
                onPendingDuplicateConsumed = { pendingDuplicate = null },
            )
        }
        composable(AppSection.JOURNAL.route) {
            JournalScreen(
                modifier = Modifier.fillMaxSize(),
                currentSection = AppSection.JOURNAL,
                onSectionSelected = ::onSectionSelected,
                onDuplicateToPlanification = { request ->
                    pendingDuplicate = request
                    onSectionSelected(AppSection.PLANIFICATION)
                },
                pendingImportUris = incomingJournalUris.takeIf { it.isNotEmpty() },
                onPendingImportUrisConsumed = { incomingJournalUris = emptyList() },
            )
        }
        composable(AppSection.REGLAGES.route) {
            SettingsScreen(
                modifier = Modifier.fillMaxSize(),
                currentSection = AppSection.REGLAGES,
                onSectionSelected = ::onSectionSelected,
                onOpenJournalSelection = {
                    navController.navigate(JOURNAL_CALIBRATION_ROUTE) { launchSingleTop = true }
                },
            )
        }
        // Not an AppSection: only reachable from Réglages' "Choisir les traces" (BIV-16), never
        // from the section menu — reuses JournalScreen wholesale rather than a second screen.
        composable(JOURNAL_CALIBRATION_ROUTE) {
            JournalScreen(
                modifier = Modifier.fillMaxSize(),
                currentSection = AppSection.JOURNAL,
                onSectionSelected = ::onSectionSelected,
                calibrationSelectionMode = true,
                onCalibrationSelectionDone = { navController.popBackStack() },
            )
        }
    }

    // RIC-104 : seule l'entrée externe pose cette question — les FAB internes du Journal et de
    // Planification connaissent déjà leur univers par construction, voir UniverseChoiceDialog.
    universeChoicePending?.let { uris ->
        UniverseChoiceDialog(
            onJournalChosen = {
                universeChoiceResolved = true
                incomingJournalUris = uris
                onSectionSelected(AppSection.JOURNAL)
            },
            onPlanificationChosen = {
                universeChoiceResolved = true
                // Planification n'a jamais su ouvrir qu'un seul fichier à la fois (voir son propre
                // sélecteur, OpenDocument et non OpenMultipleDocuments) — un lot externe choisi
                // pour cet univers perd donc silencieusement tout fichier au-delà du premier.
                // Comportement non tranché par RIC-104, signalé au pilotage plutôt que deviné plus
                // loin (agrandir Planification au multi-fichiers, ou désactiver ce choix au-delà
                // d'un fichier).
                incomingPlanificationUri = uris.first()
                onSectionSelected(AppSection.PLANIFICATION)
            },
            onCancel = { universeChoiceResolved = true },
        )
    }
}

/**
 * Uri(s) d'un ou plusieurs fichiers GPX reçus depuis une autre application, via ouverture directe
 * (VIEW, toujours un seul fichier), partage simple (SEND) ou partage groupé (SEND_MULTIPLE) — cf.
 * les intent-filters déclarés dans le manifeste.
 */
@Suppress("DEPRECATION")
private fun Intent.extractGpxUris(): List<Uri> = when (action) {
    Intent.ACTION_VIEW -> listOfNotNull(data)
    Intent.ACTION_SEND -> listOfNotNull(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        },
    )
    Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM)
    }.orEmpty()
    else -> emptyList()
}

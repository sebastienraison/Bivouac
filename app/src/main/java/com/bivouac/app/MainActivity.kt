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
import androidx.compose.runtime.LaunchedEffect
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
import com.bivouac.app.bilan.BilanScreen
import com.bivouac.app.bilan.JournalOpenRequest
import com.bivouac.app.data.prefs.AppSectionPreferences
import com.bivouac.app.journal.DuplicatePlanRequest
import com.bivouac.app.ui.gpximport.GpxImportScreen
import com.bivouac.app.ui.journal.JournalScreen
import com.bivouac.app.ui.nav.AppSection
import com.bivouac.app.ui.nav.UniverseChoiceDialog
import com.bivouac.app.ui.settings.SettingsScreen
import com.bivouac.app.ui.settings.StorageUsageScreen
import com.bivouac.app.ui.startup.ElevationBackfillGate
import com.bivouac.app.ui.startup.PhotoStorageChoicePrompt
import com.bivouac.app.ui.theme.BivouacTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val JOURNAL_CALIBRATION_ROUTE = "journal_calibration"

// RIC-140 : sous-écran des Réglages, comme JOURNAL_CALIBRATION_ROUTE ci-dessus : pas une section,
// donc absent du menu de navigation, et joignable seulement depuis la ligne « Espace utilisé ».
private const val STORAGE_USAGE_ROUTE = "reglages_espace_utilise"

/**
 * RIC-168 : un lot d'URI GPX entrant, associé à un numéro de génération.
 *
 * Deux lots se distinguent par IDENTITÉ (le compteur), jamais par contenu : rouvrir deux fois le
 * même fichier depuis Mes fichiers doit rouvrir deux fois le dialogue de choix d'univers
 * (UniverseChoiceDialog, RIC-104), alors que le contenu (la même URI content://) serait égal d'une
 * fois sur l'autre.
 */
internal data class IncomingGpxBatch(val uris: List<Uri>, val generation: Int)

private val NoIncomingGpx = IncomingGpxBatch(emptyList(), generation = 0)

/**
 * RIC-168 : la règle appliquée par onNewIntent, extraite en fonction pure pour être testable en
 * JVM sans instance d'Activity.
 *
 * Un intent redélivré sans GPX (l'app relancée depuis les récentes, par exemple) laisse [current]
 * inchangé : rien ne doit effacer un dialogue de choix d'univers pas encore résolu. Un intent avec
 * des URI toujours produit un lot de génération supérieure, y compris si son contenu est
 * identique au précédent : voir la kdoc d'[IncomingGpxBatch].
 */
internal fun nextIncomingGpxBatch(current: IncomingGpxBatch, newUris: List<Uri>): IncomingGpxBatch =
    if (newUris.isEmpty()) current else IncomingGpxBatch(newUris, current.generation + 1)

class MainActivity : ComponentActivity() {
    // RIC-168 : singleTask (voir le manifeste) fait qu'un intent reçu pendant que l'app est déjà
    // en tâche arrive ici plutôt que de recréer une seconde Activity. Le passer par un état
    // recomposable, plutôt qu'en argument figé de setContent comme avant, est ce qui fait passer
    // ce second intent par EXACTEMENT le même traitement que le lancement à froid (dialogue RIC-104
    // compris) sans reconstruire la Compose tree : onNewIntent n'a qu'à réassigner cette propriété,
    // toute composition qui la lit se recompose automatiquement.
    //
    // internal (et non private) pour être exercé directement depuis un test JVM (Robolectric,
    // ActivityController) sans passer par une vraie navigation Compose.
    internal var incomingGpxBatch: IncomingGpxBatch by mutableStateOf(NoIncomingGpx)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incomingGpxBatch = IncomingGpxBatch(intent.extractGpxUris(), generation = 0)
        setContent {
            BivouacTheme {
                // RIC-19 §5 : rattrapage bloquant des colonnes d'altitude, avant toute navigation :
                // englobe BivouacApp entier (NavHost compris) plutôt que d'être posé à l'intérieur,
                // pour qu'aucune section ne soit ne serait-ce que composée pendant le rattrapage.
                ElevationBackfillGate(modifier = Modifier.fillMaxSize()) {
                    BivouacApp(modifier = Modifier.fillMaxSize(), incomingGpxBatch = incomingGpxBatch)
                    // RIC-157 : proposition post-mise à jour du mode de stockage des photos. Après
                    // le rattrapage d'altitude et non avant : celui-là est bloquant et peut durer,
                    // empiler une question par-dessus n'aurait aucun sens. À côté de BivouacApp et
                    // non dedans : c'est un dialogue (donc sa propre fenêtre, aucune contribution à
                    // la mise en page) et il ne dépend d'aucun écran en particulier. Le plus
                    // souvent il ne dessine rien du tout, voir PhotoStorageChoiceViewModel.
                    PhotoStorageChoicePrompt()
                }
            }
        }
    }

    // RIC-168 : app déjà ouverte (n'importe quel écran) + GPX entrant : singleTask ramène CETTE
    // instance au premier plan et lui livre le nouvel intent ici plutôt que d'onCreate une seconde
    // fois. setIntent d'abord, pour qu'un intent relu plus tard (rotation, passage clair/sombre qui
    // recrée l'Activity, cf. le commentaire uiMode du manifeste) reparte de ce dernier intent et non
    // du tout premier. Un intent sans GPX (relancé depuis les récentes, par exemple) ne touche pas
    // au lot en attente : rien ne doit effacer un dialogue de choix d'univers pas encore résolu.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingGpxBatch = nextIncomingGpxBatch(incomingGpxBatch, intent.extractGpxUris())
    }
}

@Composable
private fun BivouacApp(modifier: Modifier = Modifier, incomingGpxBatch: IncomingGpxBatch = NoIncomingGpx) {
    val navController = rememberNavController()

    // RIC-40 : une boîte aux lettres entre les ViewModels du Journal et de la Planification, qui
    // ne se voient jamais autrement : ce composable est le seul endroit où les deux écrans sont
    // atteignables à la fois. Ici plutôt que dans l'un des deux ViewModels, ou dans un dépôt
    // partagé : une duplication est un passage de relais ponctuel, pas un état que l'un des deux
    // écrans possède durablement.
    var pendingDuplicate by remember { mutableStateOf<DuplicatePlanRequest?>(null) }

    // RIC-19 : même patron que pendingDuplicate ci-dessus : un clic sur un record du Bilan doit
    // ouvrir une trace précise du Journal, et ces deux écrans ne se voient jamais autrement que par
    // MainActivity (chacun son ViewModel).
    var pendingJournalOpenRequest by remember { mutableStateOf<JournalOpenRequest?>(null) }

    // RIC-104 : tant que ce choix n'est pas tranché, ni la Planification ni le Journal ne savent
    // quoi faire du fichier : voir UniverseChoiceDialog.
    //
    // rememberSaveable, et un drapeau plutôt que la liste elle-même : une rotation ou un passage en
    // mode sombre détruit et recrée l'Activity, onCreate relit l'intent de lancement, que le
    // système conserve, et en retire les mêmes Uri. Un simple remember repartirait donc de zéro et
    // rouvrirait le dialogue par-dessus l'écran, alors même que l'utilisateur vient d'y répondre.
    // C'est le même piège que celui déjà désamorcé côté Planification pour l'import (voir le
    // LaunchedEffect de GpxImportScreen), qui se rejoue ici un cran plus haut.
    //
    // RIC-168 : keyé sur incomingGpxBatch.generation, pas sur son contenu (les Uri elles-mêmes).
    // Un intent redélivré à une Activity singleTask (voir onNewIntent) porte un lot différent à
    // chaque fois qu'il en porte un, même si l'utilisateur rouvre le même fichier deux fois de
    // suite : la génération le distingue, une égalité de contenu ne doit jamais être prise pour
    // « déjà traité ».
    var universeChoiceResolved by rememberSaveable(incomingGpxBatch.generation) { mutableStateOf(false) }
    val universeChoicePending = incomingGpxBatch.uris.takeIf { it.isNotEmpty() && !universeChoiceResolved }
    // Mêmes boîtes aux lettres que pendingDuplicate ci-dessus, remplies une fois le choix
    // d'univers tranché.
    var incomingPlanificationUri by remember { mutableStateOf<Uri?>(null) }
    var incomingJournalUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // RIC-106 : dernier univers consulté, lu une seule fois au démarrage via .first() plutôt que
    // collectAsStateWithLifecycle : un onSectionSelected réécrit cette préférence à chaque
    // changement d'onglet (voir plus bas), et une collecte continue ferait retomber
    // resolvedStartSection sur la nouvelle valeur quelques dizaines de ms après coup. RIC-19 a mis
    // ça en évidence : NavHost, lui, ne « fige » pas silencieusement son startDestination face à
    // ça comme le commentaire précédent le supposait : un resolvedStartSection qui change en cours
    // de route reconstruit le graphe, ce qui recrée une NavBackStackEntry (donc un ViewModel) tout
    // neuf pour la destination qu'on vient d'atteindre, coupant au passage toute coroutine encore
    // en vol dessus (un openTrackById déclenché par pendingOpenRequest, notamment). Tant que cette
    // lecture unique n'a pas abouti, resolvedStartSection reste null et rien ne navigue encore ; en
    // pratique quasi instantané (lecture mémoire), pas une vraie latence perçue.
    val context = LocalContext.current
    val appSectionPreferences = remember { AppSectionPreferences(context) }
    val coroutineScope = rememberCoroutineScope()
    var resolvedStartSection by remember { mutableStateOf<AppSection?>(null) }
    LaunchedEffect(Unit) { resolvedStartSection = appSectionPreferences.lastVisitedSection.first() }

    // Standard top-level-destination navigation: pop back to the graph's start so switching
    // sections never piles up a back stack, but save/restore each section's own state (scroll
    // position, and (via the ViewModel's own store) the trace currently open in Planification)
    // across switches.
    fun onSectionSelected(section: AppSection) {
        navController.navigate(section.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        // RIC-106 : Réglages n'est jamais un univers d'accueil, voir AppSectionPreferences :
        // le no-op y est géré côté préférences plutôt que dupliqué ici à chaque appelant.
        coroutineScope.launch { appSectionPreferences.setLastVisitedSection(section) }
    }

    // Capturé dans un val local : `resolvedStartSection` reste une propriété déléguée
    // (State<AppSection?>), dont le compilateur ne garantit pas le smart-cast après ce contrôle de
    // nullité.
    val currentStartSection = resolvedStartSection
    if (currentStartSection == null) {
        Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    NavHost(navController = navController, startDestination = currentStartSection.route, modifier = modifier) {
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
                pendingOpenRequest = pendingJournalOpenRequest,
                onPendingOpenRequestConsumed = { pendingJournalOpenRequest = null },
            )
        }
        composable(AppSection.BILAN.route) {
            BilanScreen(
                modifier = Modifier.fillMaxSize(),
                currentSection = AppSection.BILAN,
                onSectionSelected = ::onSectionSelected,
                onOpenJournalEntry = { request ->
                    pendingJournalOpenRequest = request
                    onSectionSelected(AppSection.JOURNAL)
                },
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
                onOpenStorageUsage = {
                    navController.navigate(STORAGE_USAGE_ROUTE) { launchSingleTop = true }
                },
            )
        }
        // RIC-140 : pas une AppSection non plus, et sans en-tête de section : c'est un sous-écran
        // des Réglages, dont on revient par la flèche de retour ou le geste système.
        composable(STORAGE_USAGE_ROUTE) {
            StorageUsageScreen(
                modifier = Modifier.fillMaxSize(),
                onBack = { navController.popBackStack() },
            )
        }
        // Not an AppSection: only reachable from Réglages' "Choisir les traces" (BIV-16), never
        // from the section menu: reuses JournalScreen wholesale rather than a second screen.
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

    // RIC-104 : seule l'entrée externe pose cette question : les FAB internes du Journal et de
    // Planification connaissent déjà leur univers par construction, voir UniverseChoiceDialog.
    universeChoicePending?.let { uris ->
        UniverseChoiceDialog(
            fileCount = uris.size,
            onJournalChosen = {
                universeChoiceResolved = true
                incomingJournalUris = uris
                onSectionSelected(AppSection.JOURNAL)
            },
            onPlanificationChosen = {
                universeChoiceResolved = true
                // RIC-108 : ce first() ne perd plus rien. Planification n'a jamais su ouvrir qu'un
                // seul fichier à la fois (voir son propre sélecteur, OpenDocument et non
                // OpenMultipleDocuments), et le dialogue grise désormais ce choix au-delà d'un
                // fichier reçu : le seul lot qui arrive ici en compte exactement un.
                incomingPlanificationUri = uris.first()
                onSectionSelected(AppSection.PLANIFICATION)
            },
            onCancel = { universeChoiceResolved = true },
        )
    }
}

/**
 * Uri(s) d'un ou plusieurs fichiers GPX reçus depuis une autre application, via ouverture directe
 * (VIEW, toujours un seul fichier), partage simple (SEND) ou partage groupé (SEND_MULTIPLE) : cf.
 * les intent-filters déclarés dans le manifeste.
 *
 * RIC-168 : c'est cette même fonction, appelée aussi bien depuis onCreate que depuis onNewIntent,
 * qui garantit qu'un GPX reçu app déjà ouverte (singleTask) traverse exactement le même routage
 * qu'un lancement à froid. internal (et non private) pour être exercée directement par un test JVM
 * avec un vrai android.content.Intent (Robolectric), sans passer par une Activity.
 */
@Suppress("DEPRECATION")
internal fun Intent.extractGpxUris(): List<Uri> = when (action) {
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

package com.bivouac.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bivouac.app.journal.DuplicatePlanRequest
import com.bivouac.app.ui.gpximport.GpxImportScreen
import com.bivouac.app.ui.journal.JournalScreen
import com.bivouac.app.ui.nav.AppSection
import com.bivouac.app.ui.settings.SettingsScreen
import com.bivouac.app.ui.theme.BivouacTheme

private const val JOURNAL_CALIBRATION_ROUTE = "journal_calibration"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val incomingGpxUri = intent.extractGpxUri()
        setContent {
            BivouacTheme {
                BivouacApp(modifier = Modifier.fillMaxSize(), incomingGpxUri = incomingGpxUri)
            }
        }
    }
}

@Composable
private fun BivouacApp(modifier: Modifier = Modifier, incomingGpxUri: Uri? = null) {
    val navController = rememberNavController()

    // RIC-40 : une boîte aux lettres entre les ViewModels du Journal et de la Planification, qui
    // ne se voient jamais autrement — ce composable est le seul endroit où les deux écrans sont
    // atteignables à la fois. Ici plutôt que dans l'un des deux ViewModels, ou dans un dépôt
    // partagé : une duplication est un passage de relais ponctuel, pas un état que l'un des deux
    // écrans possède durablement.
    var pendingDuplicate by remember { mutableStateOf<DuplicatePlanRequest?>(null) }

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
    }

    NavHost(navController = navController, startDestination = AppSection.PLANIFICATION.route, modifier = modifier) {
        composable(AppSection.PLANIFICATION.route) {
            GpxImportScreen(
                modifier = Modifier.fillMaxSize(),
                incomingGpxUri = incomingGpxUri,
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
}

/**
 * Uri d'un fichier GPX reçu depuis une autre application, via ouverture directe (VIEW) ou
 * partage (SEND) — cf. les intent-filters déclarés dans le manifeste.
 */
@Suppress("DEPRECATION")
private fun Intent.extractGpxUri(): Uri? = when (action) {
    Intent.ACTION_VIEW -> data
    Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }
    else -> null
}

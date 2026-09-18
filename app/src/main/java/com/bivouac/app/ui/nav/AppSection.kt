package com.bivouac.app.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.bivouac.app.R

// RIC-190 (lot 3 i18n) : le libellé est un id de ressource et non une chaîne, pour la même raison
// que MapLayer.labelRes (RIC-188) : un enum se construit à l'initialisation de la classe, bien
// avant qu'un Context existe. Il est résolu au point d'affichage (SectionMenuButton), et chaque
// section partage sa ressource avec le titre de l'écran correspondant : une seule chaîne par
// section, pas deux à resynchroniser.
enum class AppSection(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    PLANIFICATION("planification", R.string.nav_section_planification, Icons.Default.Route),
    JOURNAL("journal", R.string.journal_list_screen_title, Icons.AutoMirrored.Filled.MenuBook),
    // RIC-19 : juste après Journal, dont il est le prolongement (pas un onglet indépendant) :
    // avant Réglages, qui n'a jamais rien à voir avec le contenu du carnet.
    BILAN("bilan", R.string.bilan_screen_title, Icons.Default.BarChart),
    REGLAGES("reglages", R.string.settings_screen_title, Icons.Default.Settings),
}

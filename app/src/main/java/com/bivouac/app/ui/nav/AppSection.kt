package com.bivouac.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppSection(val route: String, val label: String, val icon: ImageVector) {
    PLANIFICATION("planification", "Planification", Icons.Default.Route),
    JOURNAL("journal", "Journal", Icons.AutoMirrored.Filled.MenuBook),
    REGLAGES("reglages", "Réglages", Icons.Default.Settings),
}

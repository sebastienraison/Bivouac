package com.bivouac.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bivouac.app.ui.nav.AppSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal const val APP_SECTION_DATASTORE_NAME = "app_section_prefs"
private val Context.appSectionDataStore by preferencesDataStore(name = APP_SECTION_DATASTORE_NAME)

/**
 * RIC-106 : le dernier univers (Journal ou Planification) consulté, pour rouvrir dessus au
 * démarrage à froid plutôt que de figer un défaut : l'usage réel évolue et ne se laisse pas
 * deviner. Réglages n'est jamais persisté ici : ce n'est pas un univers d'accueil, seulement
 * atteignable via le sélecteur de section (voir AppSection).
 */
class AppSectionPreferences(private val context: Context) {
    private val key = stringPreferencesKey("last_visited_section")

    // Défaut à Planification quand rien n'a encore été visité (première installation), même
    // défaut que le comportement historique, avant que ce choix ne devienne dynamique.
    val lastVisitedSection: Flow<AppSection> = context.appSectionDataStore.data.map { prefs ->
        prefs[key]?.let { name -> runCatching { AppSection.valueOf(name) }.getOrNull() } ?: AppSection.PLANIFICATION
    }

    suspend fun setLastVisitedSection(section: AppSection) {
        if (section == AppSection.REGLAGES) return
        context.appSectionDataStore.edit { it[key] = section.name }
    }
}

package com.bivouac.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Jeu de rôles complet, repris de la maquette RIC-65. Avant, seuls primary et onPrimary étaient
// définis : tout le reste retombait sur le baseline Material, c'est-à-dire violet. Ça ne se voyait
// qu'aux endroits qui touchaient un rôle non défini (les avatars de Réglages via
// secondaryContainer), jusqu'à ce que l'accueil du Journal en expose deux de plus (le FAB tire son
// fond de primaryContainer, la carte Bilan de secondaryContainer).
//
// Les rôles sont donc tous renseignés, y compris ceux que le code n'appelle jamais directement mais
// que les composants Material vont chercher seuls : surfaceContainerHigh porte le fond des
// AlertDialog, secondaryContainer celui d'un FilterChip sélectionné. En laisser un seul au baseline
// suffit à réintroduire du violet à l'écran.
//
// Le vert et l'orange ne sont pas une identité neuve : ils formalisent celle déjà codée en dur
// ailleurs (marker_bivouac, DistanceIconColor). Les couleurs fonctionnelles de la carte et des
// statistiques restent hors thème, elles ne se dérivent pas d'un rôle Material.

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6B4F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB7EFCB),
    onPrimaryContainer = Color(0xFF002112),

    secondary = Color(0xFFA85A1E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC1),
    onSecondaryContainer = Color(0xFF331200),

    // Absent de la maquette, qui n'en avait pas l'usage. Aligné sur marker_cursor, le troisième
    // accent déjà en place dans l'app, plutôt que laissé au violet baseline.
    tertiary = Color(0xFF00695C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA7F2E5),
    onTertiaryContainer = Color(0xFF00201B),

    background = Color(0xFFFBFAF5),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFFBFAF5),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFE2E3D6),
    onSurfaceVariant = Color(0xFF43483F),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F4EE),
    surfaceContainer = Color(0xFFF1F1EA),
    surfaceContainerHigh = Color(0xFFEBEBE2),
    surfaceContainerHighest = Color(0xFFE5E5DC),
    surfaceDim = Color(0xFFDBDBD2),
    surfaceBright = Color(0xFFFBFAF5),

    outline = Color(0xFFC8C9BC),
    outlineVariant = Color(0xFFDEDFD2),
    scrim = Color(0xFF000000),

    inverseSurface = Color(0xFF2F312C),
    inverseOnSurface = Color(0xFFF1F1EA),
    inversePrimary = Color(0xFF9BD5AF),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BD5AF),
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF0D5133),
    onPrimaryContainer = Color(0xFFB7EFCB),

    secondary = Color(0xFFFFB876),
    onSecondary = Color(0xFF522300),
    secondaryContainer = Color(0xFF5C3B0E),
    onSecondaryContainer = Color(0xFFFFDCC1),

    tertiary = Color(0xFF6FD9C6),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005047),
    onTertiaryContainer = Color(0xFFA7F2E5),

    background = Color(0xFF12140F),
    onBackground = Color(0xFFE2E3DA),
    surface = Color(0xFF12140F),
    onSurface = Color(0xFFE2E3DA),
    surfaceVariant = Color(0xFF43483D),
    onSurfaceVariant = Color(0xFFC3C8B9),

    surfaceContainerLowest = Color(0xFF0D0F0B),
    surfaceContainerLow = Color(0xFF1A1C17),
    surfaceContainer = Color(0xFF1E211B),
    surfaceContainerHigh = Color(0xFF262A22),
    surfaceContainerHighest = Color(0xFF313529),
    surfaceDim = Color(0xFF12140F),
    surfaceBright = Color(0xFF383B33),

    outline = Color(0xFF4A4E43),
    outlineVariant = Color(0xFF363A31),
    scrim = Color(0xFF000000),

    inverseSurface = Color(0xFFE2E3DA),
    inverseOnSurface = Color(0xFF2F312C),
    inversePrimary = Color(0xFF2E6B4F),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun BivouacTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, content = content)
}

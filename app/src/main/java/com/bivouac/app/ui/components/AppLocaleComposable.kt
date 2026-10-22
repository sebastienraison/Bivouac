package com.bivouac.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import com.bivouac.app.R
import com.bivouac.app.data.model.AppLocale
import java.util.Locale

/**
 * RIC-245 : la locale des dates et des heures depuis une fonction composable, même règle que
 * [AppLocale.of] (voir [AppLocale.resolve]). Lit LocalLocale et la ressource `app_language_tag` :
 * deux lectures observables par Compose, donc conforme au lint NonObservableLocale, qui refuse
 * Locale.getDefault() dans une fonction composable (un changement de langue sans relancer l'app ne
 * recomposerait pas l'écran).
 */
@Composable
@ReadOnlyComposable
fun appLocale(): Locale =
    AppLocale.resolve(LocalLocale.current.platformLocale, stringResource(R.string.app_language_tag))

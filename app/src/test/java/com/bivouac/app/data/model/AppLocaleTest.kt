package com.bivouac.app.data.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-245 : la règle d'AppLocale seule, sans Android. La locale de l'appareil est gardée quand sa
 * langue est celle des ressources ; sinon c'est la langue des ressources qui l'emporte.
 */
class AppLocaleTest {

    @Test
    fun `appareil francais, ressources francaises, la locale de l'appareil reste`() {
        assertEquals(Locale.forLanguageTag("fr-FR"), AppLocale.resolve(Locale.forLanguageTag("fr-FR"), "fr"))
    }

    @Test
    fun `appareil britannique, ressources anglaises, les dates restent britanniques`() {
        assertEquals(Locale.forLanguageTag("en-GB"), AppLocale.resolve(Locale.forLanguageTag("en-GB"), "en"))
    }

    @Test
    fun `appareil allemand, ressources anglaises, les dates passent en anglais`() {
        assertEquals(Locale.forLanguageTag("en"), AppLocale.resolve(Locale.forLanguageTag("de-DE"), "en"))
    }

    @Test
    fun `appareil suisse romand, ressources francaises, les dates restent suisses`() {
        assertEquals(Locale.forLanguageTag("fr-CH"), AppLocale.resolve(Locale.forLanguageTag("fr-CH"), "fr"))
    }
}

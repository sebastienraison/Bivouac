package com.bivouac.app.data.model

import android.content.Context
import com.bivouac.app.R
import java.util.Locale

/**
 * RIC-245 : la locale des dates et des heures de l'app, point unique de la règle.
 *
 * > Locale des dates = la locale de l'appareil si sa langue est celle des ressources chargées ;
 * > sinon la locale de la langue des ressources.
 *
 * Avant ce ticket, les formateurs de dates suivaient la locale de l'appareil (RIC-187) et les
 * chaînes, elles, la langue des ressources : sur un appareil allemand, les chaînes retombent sur
 * l'anglais (aucun values-de) et les dates sortaient en allemand, « From Samstag 18 Juli at 11:42 »
 * (testeur F-Droid, 21/10). La règle ne change rien pour un appareil en français ou en anglais : un
 * `en-GB` garde ses dates britanniques, un `fr-CH` ses dates suisses ; seul un appareil dans une
 * autre langue voit ses dates passer dans la langue de la phrase qui les entoure.
 *
 * La langue des ressources est lue dans la ressource `app_language_tag` (tag BCP 47, « en » dans
 * values/, « fr » dans values-fr/) : c'est Android qui a choisi le fichier, la ressource dit lequel.
 * Une langue ajoutée renseigne la sienne, sans toucher au code.
 *
 * Les NOMBRES ne passent pas par ici : séparateur décimal et groupement des milliers restent ceux
 * de l'appareil (NumberFormatting, TotalsCapsule, ElevationProfile pour les nombres,
 * SettingsScreen). Un Allemand qui lit l'app en anglais écrit toujours ses décimales avec une
 * virgule : c'est une convention de l'appareil, pas de la langue de la phrase.
 *
 * Vit dans data/model et non ui/components : [TrekDatesFormatter], dans ce même package, en a
 * besoin, et data ne doit pas dépendre de ui. La variante composable (lint NonObservableLocale),
 * qui appelle la même [resolve], est `com.bivouac.app.ui.components.appLocale`.
 */
object AppLocale {

    /**
     * La règle seule, sans Android : [deviceLocale] si sa langue est celle de
     * [resourcesLanguageTag], sinon la locale de [resourcesLanguageTag]. Testée par AppLocaleTest.
     */
    fun resolve(deviceLocale: Locale, resourcesLanguageTag: String): Locale {
        val resourcesLocale = Locale.forLanguageTag(resourcesLanguageTag)
        return if (deviceLocale.language == resourcesLocale.language) deviceLocale else resourcesLocale
    }

    /**
     * Locale des dates pour un appel hors composition. La locale de l'appareil est celle de la
     * configuration de [context], la même qui a servi à choisir les ressources (et que la variante
     * composable lit par LocalLocale), plutôt que Locale.getDefault() : les deux entrées de la
     * règle viennent ainsi de la même source.
     */
    fun of(context: Context): Locale =
        resolve(context.resources.configuration.locales[0], context.getString(R.string.app_language_tag))
}

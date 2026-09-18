package com.bivouac.app.ui.gpximport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-187 (lot 0 i18n) puis RIC-188 (lot 1) : formatSavedAt suivait Locale.FRANCE (motif "d MMMM"
 * en dur) ; le lot 0 l'a fait suivre Locale.getDefault(), le lot 1 déplace les deux textes dans les
 * ressources (gpximport_saved_today_format pour le préfixe « aujourd'hui à », gpximport_saved_date_
 * format pour le motif jour+mois, dont l'ORDRE des champs dépend de la langue).
 *
 * D'où RobolectricTestRunner et @Config(qualifiers) : ce que ce test vérifie maintenant, c'est le
 * câblage complet (la bonne ressource est bien choisie selon la langue de l'appareil), pas
 * seulement la formule. La locale JVM est posée EN PLUS des qualifiers, parce que les deux entrées
 * ne sont pas la même : les qualifiers choisissent le fichier de ressources, Locale.getDefault()
 * pilote DateTimeFormatter (heure localisée, nom du mois).
 */
@RunWith(RobolectricTestRunner::class)
class GpxImportSavedAtFormattingLocaleTest {

    private lateinit var originalLocale: Locale
    private val zone = ZoneId.systemDefault()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun sauvegarderLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restaurerLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `date passee au format jour puis mois en francais`() {
        Locale.setDefault(Locale.FRANCE)
        val instant = ZonedDateTime.of(2020, 8, 3, 10, 0, 0, 0, zone).toInstant()
        assertEquals("3 août", formatSavedAt(context, instant.toEpochMilli()))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `date passee au format mois puis jour en anglais`() {
        Locale.setDefault(Locale.US)
        val instant = ZonedDateTime.of(2020, 8, 3, 10, 0, 0, 0, zone).toInstant()
        assertEquals("August 3", formatSavedAt(context, instant.toEpochMilli()))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `date du jour prefixee par aujourd'hui suivie de l'heure localisee`() {
        Locale.setDefault(Locale.FRANCE)
        val now = ZonedDateTime.now(zone).withHour(19).withMinute(0).withSecond(0).withNano(0)
        assertTrue(now.toLocalDate() == LocalDate.now(zone))
        assertEquals("aujourd'hui à 19:00", formatSavedAt(context, now.toInstant().toEpochMilli()))
    }

    /**
     * Le préfixe du jour même suit lui aussi la langue de l'appareil, il n'est plus figé.
     *
     * Assertion sur le seul préfixe et non sur la chaîne entière : l'heure en 12 h est déjà couverte
     * par HikeMapViewPhotoTimeFormattingLocaleTest, et le séparateur qu'ICU met devant « PM » varie
     * selon la version de CLDR embarquée (espace insécable étroite depuis CLDR 42), ce qui rendrait
     * ce test dépendant de la version de Robolectric plutôt que du câblage qu'il doit vérifier.
     */
    @Test
    @Config(qualifiers = "en-rUS")
    fun `date du jour prefixee par today en anglais`() {
        Locale.setDefault(Locale.US)
        val now = ZonedDateTime.now(zone).withHour(19).withMinute(0).withSecond(0).withNano(0)
        assertTrue(now.toLocalDate() == LocalDate.now(zone))
        assertTrue(formatSavedAt(context, now.toInstant().toEpochMilli()).startsWith("today at "))
    }
}

package com.bivouac.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.R
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.db.PhotoStorageSummary
import com.bivouac.app.i18n.espacesNormalisees
import com.bivouac.app.ui.components.formatGroupedInt
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
 * RIC-187 (lot 0 i18n) : formatBytes et formatBackupTimestamp suivaient Locale.FRANCE en dur.
 *
 * formatBackupTimestamp : motif "d MMMM 'à' HH:mm" remplacé par un style localisé
 * (FormatStyle.LONG + FormatStyle.SHORT), comme JournalScreen.formatStartedAtWithTime.
 *
 * RIC-190 (lot 3 i18n) : les unités "Go"/"Mo"/"Ko"/"o", laissées en dur au lot 0, viennent
 * maintenant des ressources, d'où le Context passé à formatBytes et le passage sous Robolectric
 * avec @Config(qualifiers). La locale JVM est posée EN PLUS des qualifiers : les qualifiers
 * choisissent le fichier de ressources (l'unité), Locale.getDefault() pilote le séparateur décimal
 * de String.format et DateTimeFormatter.
 *
 * RIC-192 : les comptes de photos des Réglages passent par formatGroupedInt, donc portent le
 * séparateur de milliers de la locale. Vérifié sur un compte à 4 chiffres, le seul qui distingue
 * « 1 234 photos » de « 1234 photos ».
 */
@RunWith(RobolectricTestRunner::class)
class SettingsFormattingLocaleTest {

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
    fun `formatBytes utilise la virgule et l unite francaises`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("12,3 Mo", formatBytes(context, 12_345_678L))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `formatBytes utilise le point et l unite anglaise`() {
        Locale.setDefault(Locale.US)
        assertEquals("12.3 MB", formatBytes(context, 12_345_678L))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `formatBytes sous le Ko, sans decimale`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("2 Ko", formatBytes(context, 2_000L))
    }

    /** Sous le kilo-octet, l'unité seule change de langue : « o » devient « B ». */
    @Test
    @Config(qualifiers = "en-rUS")
    fun `formatBytes sous le kilo-octet en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals("512 B", formatBytes(context, 512L))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `formatPhotoStorage groupe les milliers en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals(
            "1 234 photos, 12,3 Mo",
            formatPhotoStorage(context, PhotoStorageSummary(count = 1234, totalBytes = 12_345_678L))
                .espacesNormalisees(),
        )
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `formatPhotoStorage groupe les milliers en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals(
            "1,234 photos, 12.3 MB",
            formatPhotoStorage(context, PhotoStorageSummary(count = 1234, totalBytes = 12_345_678L)),
        )
    }

    /**
     * Le rapport de récupération : trois lignes, trois comptes à 4 chiffres. C'est le cas réel
     * d'une restauration sur un Journal fourni, celui où le chiffre brut devenait illisible.
     */
    @Test
    @Config(qualifiers = "fr-rFR")
    fun `photoRecoveryReportMessage groupe les milliers en francais`() {
        Locale.setDefault(Locale.FRANCE)
        val message = photoRecoveryReportMessage(
            context,
            LoggedTrackRepository.PhotoRecoveryReport(recovered = 1234, modifiedNotAdopted = 2345, notFound = 3456),
        ).espacesNormalisees()
        assertTrue(message, message.startsWith("1 234 photos retrouvées"))
        assertTrue(message, message.contains("2 345 photos retrouvées dans la galerie"))
        assertTrue(message, message.contains("3 456 photos restent introuvables"))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `photoRecoveryReportMessage groupe les milliers en anglais`() {
        Locale.setDefault(Locale.US)
        val message = photoRecoveryReportMessage(
            context,
            LoggedTrackRepository.PhotoRecoveryReport(recovered = 1234, modifiedNotAdopted = 2345, notFound = 3456),
        )
        assertTrue(message, message.startsWith("1,234 photos found and restored."))
        assertTrue(message, message.contains("2,345 photos found in the gallery"))
        assertTrue(message, message.contains("3,456 photos are still missing"))
    }

    /**
     * Les deux ressources consommées depuis un @Composable (la ligne « Retrouver les photos
     * manquantes » et la proposition de recompression, partagée avec PhotoStorageChoicePrompt) se
     * vérifient au niveau de la ressource : ce qui doit être prouvé, c'est qu'elles acceptent
     * désormais une chaîne et rendent le compte groupé, l'entier ne servant plus qu'à la quantité.
     */
    @Test
    @Config(qualifiers = "fr-rFR")
    fun `les comptes des dialogues des Reglages sont groupes en francais`() {
        Locale.setDefault(Locale.FRANCE)
        val manquantes = context.resources.getQuantityString(
            R.plurals.settings_missing_photos_row_description,
            1234,
            formatGroupedInt(1234),
        ).espacesNormalisees()
        assertTrue(manquantes, manquantes.startsWith("1 234 photos du Journal n'ont plus de fichier local"))
        val offre = context.resources.getQuantityString(
            R.plurals.settings_photo_recompress_offer_message,
            1234,
            formatGroupedInt(1234),
            formatBytes(context, 12_345_678L),
        ).espacesNormalisees()
        assertEquals(
            "1 234 photos déjà importées restent en qualité d'origine (~12,3 Mo). Les recompresser maintenant ?",
            offre,
        )
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `les comptes des dialogues des Reglages sont groupes en anglais`() {
        Locale.setDefault(Locale.US)
        val manquantes = context.resources.getQuantityString(
            R.plurals.settings_missing_photos_row_description,
            1234,
            formatGroupedInt(1234),
        )
        assertTrue(manquantes, manquantes.startsWith("1,234 photos in the Journal no longer have a local file"))
        val offre = context.resources.getQuantityString(
            R.plurals.settings_photo_recompress_offer_message,
            1234,
            formatGroupedInt(1234),
            formatBytes(context, 12_345_678L),
        )
        assertEquals(
            "1,234 photos already imported are still in original quality (~12.3 MB). Recompress them now?",
            offre,
        )
    }

    /**
     * Le compte de traces de la calibration n'était pas dans la liste du pilotage : c'est pourtant
     * le MÊME selectedTrackCount que les boutons de sélection du Journal, il ne peut pas s'écrire
     * autrement d'un écran à l'autre (RIC-192).
     */
    @Test
    @Config(qualifiers = "fr-rFR")
    fun `le compte de traces de la calibration est groupe en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals(
            "Calculées à partir de 1 234 traces choisies dans le Journal.",
            context.getString(R.string.settings_speed_calibration_selection_hint_many, formatGroupedInt(1234))
                .espacesNormalisees(),
        )
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `le compte de traces de la calibration est groupe en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals(
            "Calculated from 1,234 tracks selected in the Journal.",
            context.getString(R.string.settings_speed_calibration_selection_hint_many, formatGroupedInt(1234)),
        )
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `formatBackupTimestamp combine date longue et heure courte en francais`() {
        Locale.setDefault(Locale.FRANCE)
        val instant = ZonedDateTime.of(2026, 4, 14, 19, 0, 0, 0, zone).toInstant()
        assertEquals("14 avril 2026 19:00", formatBackupTimestamp(instant.toEpochMilli()))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `formatBackupTimestamp combine date longue et heure courte en anglais`() {
        Locale.setDefault(Locale.US)
        val instant = ZonedDateTime.of(2026, 4, 14, 19, 0, 0, 0, zone).toInstant()
        assertEquals("April 14, 2026, 7:00 PM", formatBackupTimestamp(instant.toEpochMilli()))
    }
}

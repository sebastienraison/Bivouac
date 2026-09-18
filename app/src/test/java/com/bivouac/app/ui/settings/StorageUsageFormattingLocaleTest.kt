package com.bivouac.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.R
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.i18n.espacesNormalisees
import com.bivouac.app.ui.components.formatGroupedInt
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
 * RIC-192 : l'écran « Espace utilisé » est celui qui affiche les plus gros comptes de l'app (toutes
 * les photos et tous les GPX du Journal, sans filtre). C'est donc lui qui rendait le plus visible
 * l'absence de séparateur de milliers : « 1234 photos, 2,3 Go ».
 *
 * Le rapport de recompression est testé par sa vraie fonction (recompressionReportMessage), les
 * lignes de détail et la carte, consommées depuis un @Composable, au niveau de la ressource : ce
 * qui doit être prouvé dans les deux cas est le même, à savoir que le compte arrive formaté et que
 * l'entier ne sert plus qu'à choisir la forme plurielle.
 */
@RunWith(RobolectricTestRunner::class)
class StorageUsageFormattingLocaleTest {

    private lateinit var originalLocale: Locale
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val rapport = LoggedTrackRepository.PhotoRecompressionReport(
        recompressed = 1234,
        freedBytes = 12_345_678L,
        kept = 2345,
        alreadyReduced = 3456,
    )

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
    fun `le rapport de recompression groupe les milliers en francais`() {
        Locale.setDefault(Locale.FRANCE)
        val message = recompressionReportMessage(context, rapport).espacesNormalisees()
        assertTrue(message, message.startsWith("1 234 photos recompressées, 12,3 Mo libérés."))
        assertTrue(message, message.contains("2 345 photos conservées en qualité d'origine"))
        assertTrue(message, message.contains("3 456 photos étaient déjà au format le plus léger"))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `le rapport de recompression groupe les milliers en anglais`() {
        Locale.setDefault(Locale.US)
        val message = recompressionReportMessage(context, rapport)
        assertTrue(message, message.startsWith("1,234 photos recompressed, 12.3 MB freed."))
        assertTrue(message, message.contains("2,345 photos kept in original quality"))
        assertTrue(message, message.contains("3,456 photos were already in the lightest format"))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `les lignes de detail groupent les milliers en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals(
            "Qualité d'origine : 1 234 photos, 12,3 Mo",
            detail(R.plurals.storage_usage_row_photos_full_detail, 1234, formatBytes(context, 12_345_678L)),
        )
        assertEquals(
            "Poids allégé : 1 234 photos, 12,3 Mo",
            detail(R.plurals.storage_usage_row_photos_reduced_detail, 1234, formatBytes(context, 12_345_678L)),
        )
        assertEquals("Dont 1 234 fichiers absents", detail(R.plurals.storage_usage_row_photos_missing_detail, 1234))
        assertEquals(
            "Journal : 1 234 fichiers, 12,3 Mo",
            detail(R.plurals.storage_usage_row_gpx_journal_detail, 1234, formatBytes(context, 12_345_678L)),
        )
        assertEquals(
            "Planification : 1 234 fichiers, 12,3 Mo",
            detail(R.plurals.storage_usage_row_gpx_planification_detail, 1234, formatBytes(context, 12_345_678L)),
        )
        assertTrue(
            detail(R.plurals.storage_usage_recompression_card_body, 1234)
                .startsWith("1 234 photos sont conservées en qualité d'origine."),
        )
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `les lignes de detail groupent les milliers en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals(
            "Original quality: 1,234 photos, 12.3 MB",
            detail(R.plurals.storage_usage_row_photos_full_detail, 1234, formatBytes(context, 12_345_678L)),
        )
        assertEquals(
            "Lightweight: 1,234 photos, 12.3 MB",
            detail(R.plurals.storage_usage_row_photos_reduced_detail, 1234, formatBytes(context, 12_345_678L)),
        )
        assertEquals("Including 1,234 missing files", detail(R.plurals.storage_usage_row_photos_missing_detail, 1234))
        assertEquals(
            "Journal: 1,234 files, 12.3 MB",
            detail(R.plurals.storage_usage_row_gpx_journal_detail, 1234, formatBytes(context, 12_345_678L)),
        )
        assertEquals(
            "Planning: 1,234 files, 12.3 MB",
            detail(R.plurals.storage_usage_row_gpx_planification_detail, 1234, formatBytes(context, 12_345_678L)),
        )
        assertTrue(
            detail(R.plurals.storage_usage_recompression_card_body, 1234)
                .startsWith("1,234 photos are kept in original quality."),
        )
    }

    /** Rend la ressource exactement comme l'écran le fait : entier pour la quantité, chaîne formatée pour l'affichage. */
    private fun detail(plurals: Int, compte: Int, vararg reste: Any): String =
        context.resources
            .getQuantityString(plurals, compte, formatGroupedInt(compte), *reste)
            .espacesNormalisees()
}

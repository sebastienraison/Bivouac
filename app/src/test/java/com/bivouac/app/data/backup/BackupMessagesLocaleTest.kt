package com.bivouac.app.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.R
import com.bivouac.app.i18n.espacesNormalisees
import com.bivouac.app.ui.components.formatGroupedInt
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-192 : le message d'écriture incomplète de la sauvegarde porte deux volumétries en OCTETS,
 * donc 8 ou 9 chiffres en pratique. C'est le cas le plus flagrant de la règle RIC-136 : sans
 * groupement, « 123456789 octets sur 234567890 » est illisible au moment précis où l'utilisateur
 * doit comparer les deux nombres.
 *
 * Le message est construit dans une fonction privée de BackupManager (verifyWrittenSize, appelée
 * seulement quand le fournisseur de destination ment sur la taille écrite) : c'est donc la
 * ressource qui est vérifiée, rendue exactement comme BackupManager le fait.
 */
@RunWith(RobolectricTestRunner::class)
class BackupMessagesLocaleTest {

    private lateinit var originalLocale: Locale
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
    fun `l ecriture incomplete groupe les octets en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals(
            "La sauvegarde n'a pas été écrite en entier (1 234 octets sur 12 345 678). " +
                "Vérifier l'espace disponible sur la destination, puis recommencer.",
            message(1234L, 12_345_678L),
        )
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `l ecriture incomplete groupe les octets en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals(
            "The backup was not fully written (1,234 bytes out of 12,345,678). " +
                "Check the available space on the destination, then try again.",
            message(1234L, 12_345_678L),
        )
    }

    private fun message(annonces: Long, ecrits: Long): String =
        context.getString(
            R.string.backup_incomplete_write_message,
            formatGroupedInt(annonces),
            formatGroupedInt(ecrits),
        ).espacesNormalisees()
}

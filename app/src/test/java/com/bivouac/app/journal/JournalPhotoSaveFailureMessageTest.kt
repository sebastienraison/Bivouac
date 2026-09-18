package com.bivouac.app.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-191 (lot 4 i18n) : le rapport d'échec d'enregistrement de photos était composé par un
 * `if (photoFailures == 1)` et deux chaînes françaises en dur. C'est maintenant un <plurals>, parce
 * que le nombre de formes et la règle d'accord ne sont pas les mêmes d'une langue à l'autre.
 *
 * Ce test vise la ressource plutôt que le ViewModel : construire un JournalViewModel demande une
 * base, un dépôt et des fichiers (voir JournalGpxImportExclusionTest), pour ne vérifier au bout du
 * compte que le choix de la forme plurielle, qui est ce que le lot a changé ici.
 */
@RunWith(RobolectricTestRunner::class)
class JournalPhotoSaveFailureMessageTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun message(count: Int): String =
        context.resources.getQuantityString(R.plurals.journal_error_photo_save_failed, count, count)

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `une seule photo en echec se dit au singulier`() {
        assertEquals("Une photo n'a pas pu être enregistrée.", message(1))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `plusieurs photos en echec se disent au pluriel`() {
        assertEquals("3 photos n'ont pas pu être enregistrées.", message(3))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `les deux formes existent aussi en anglais`() {
        assertEquals("A photo could not be saved.", message(1))
        assertEquals("3 photos could not be saved.", message(3))
    }
}

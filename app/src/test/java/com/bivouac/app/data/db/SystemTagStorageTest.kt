package com.bivouac.app.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-191 (lot 4 i18n) : le libellé d'un tag système est traduit, sa valeur stockée ne l'est pas.
 *
 * Ce test fige la moitié qui ne doit JAMAIS bouger. La colonne logged_track_tag.tag porte
 * [SystemTag.value], et c'est aussi la clé qui distingue un tag système d'un tag libre partout dans
 * le code (JournalViewModel, JournalScreen, tagColor). La traduire ferait basculer toutes les
 * lignes existantes du côté des tags libres, sans migration possible a posteriori : la trace de ce
 * qu'elles étaient aurait disparu.
 */
@RunWith(RobolectricTestRunner::class)
class SystemTagStorageTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `les valeurs stockees en base sont stables et non traduites`() {
        assertEquals("solo", SystemTag.SOLO.value)
        assertEquals("groupe", SystemTag.GROUPE.value)
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `les libelles suivent la langue en francais`() {
        assertEquals("Solo", context.getString(SystemTag.SOLO.labelRes))
        assertEquals("Groupe", context.getString(SystemTag.GROUPE.labelRes))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `les libelles suivent la langue en anglais`() {
        assertEquals("Solo", context.getString(SystemTag.SOLO.labelRes))
        assertEquals("Group", context.getString(SystemTag.GROUPE.labelRes))
    }
}

package com.bivouac.app.ui.gpximport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.R
import com.bivouac.app.gpximport.NameDialogPurpose
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-193 : le dialogue de nom du Planning (GpxImportScreen) servait aussi bien au premier
 * nommage à l'import qu'au renommage d'une trace déjà banquée, toujours titré
 * messages_name_dialog_title (« Nommer la trace » / « Name the track ») -- incohérent avec le
 * Journal, qui distingue déjà les deux (journal_detail_rename_dialog_title). nameDialogTitleRes
 * porte maintenant cette distinction, extraite du Composable pour rester testable sans Compose.
 */
@RunWith(RobolectricTestRunner::class)
class GpxImportRenameDialogTitleTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `RENAME et RENAME_FROM_LIST pointent vers le titre de renommage`() {
        assertEquals(R.string.gpximport_rename_dialog_title, nameDialogTitleRes(NameDialogPurpose.RENAME))
        assertEquals(R.string.gpximport_rename_dialog_title, nameDialogTitleRes(NameDialogPurpose.RENAME_FROM_LIST))
    }

    @Test
    fun `FIRST_SAVE, SAVE_THEN_CLOSE et DUPLICATE gardent le titre de premier nommage`() {
        assertEquals(R.string.messages_name_dialog_title, nameDialogTitleRes(NameDialogPurpose.FIRST_SAVE))
        assertEquals(R.string.messages_name_dialog_title, nameDialogTitleRes(NameDialogPurpose.SAVE_THEN_CLOSE))
        assertEquals(R.string.messages_name_dialog_title, nameDialogTitleRes(NameDialogPurpose.DUPLICATE))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `les deux titres different en francais`() {
        assertEquals("Nommer la trace", context.getString(R.string.messages_name_dialog_title))
        assertEquals("Renommer la trace", context.getString(R.string.gpximport_rename_dialog_title))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `les deux titres different en anglais`() {
        assertEquals("Name the track", context.getString(R.string.messages_name_dialog_title))
        assertEquals("Rename the track", context.getString(R.string.gpximport_rename_dialog_title))
    }
}

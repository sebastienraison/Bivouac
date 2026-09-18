package com.bivouac.app.data.operations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-191 (lot 4 i18n) : les libellés d'[ExclusiveOperation] ne sont plus du texte porté par l'enum
 * mais des ressources résolues à la composition du message de refus.
 *
 * Ce que ce test vérifie, et que le test JVM pur ne peut pas voir : la ressource existe réellement
 * dans les deux langues, et la phrase de refus l'incorpore bien. Un identifiant de ressource valide
 * mais pointant sur la mauvaise chaîne passerait le contrôle JVM sans être vu.
 */
@RunWith(RobolectricTestRunner::class)
class ExclusiveOperationLabelResolutionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = ExclusiveOperations.resetForTests()

    @After
    fun tearDown() = ExclusiveOperations.resetForTests()

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `chaque libelle se resout en francais`() {
        for (operation in ExclusiveOperation.entries) {
            assertTrue(operation.name, context.getString(operation.labelRes).isNotBlank())
        }
        assertEquals("une sauvegarde", context.getString(ExclusiveOperation.BACKUP.labelRes))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `chaque libelle se resout en anglais`() {
        for (operation in ExclusiveOperation.entries) {
            assertTrue(operation.name, context.getString(operation.labelRes).isNotBlank())
        }
        assertEquals("a backup", context.getString(ExclusiveOperation.BACKUP.labelRes))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `le refus nomme l'operation en vol`() {
        ExclusiveOperations.tryStart(ExclusiveOperation.RESTORE)
        val message = exclusiveOperationRefusalMessage(context)
        assertTrue(message, message.contains("une restauration"))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `le refus retombe sur le libelle par defaut quand rien ne tourne`() {
        // Cas défensif : le verrou peut s'être levé entre le refus et la composition du message.
        val message = exclusiveOperationRefusalMessage(context)
        assertTrue(message, message.contains("another operation"))
    }
}

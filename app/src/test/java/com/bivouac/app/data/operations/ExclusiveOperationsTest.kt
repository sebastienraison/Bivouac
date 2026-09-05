package com.bivouac.app.data.operations

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RIC-156/RIC-158 : l'exclusion mutuelle entre les sept opérations longues du registre.
 *
 * Ce que ces tests figent vient d'un incident réel : une restauration d'une vieille archive a pu
 * être lancée depuis les Réglages pendant qu'une sauvegarde était en cours d'écriture, et la
 * sauvegarde a semblé être avortée. Les deux manipulent les mêmes fichiers (base, préférences,
 * gpx/, gpx-planif/, photos/) ; l'import et l'enregistrement de photos du Journal touchent aux
 * mêmes photos/, la purge de photos (RIC-158) aussi, et les imports Journal/Planification (RIC-158)
 * touchent respectivement gpx/ et gpx-planif/. Aucune paire, quel que soit l'ordre, ne doit pouvoir
 * se chevaucher : y compris deux instances de la même opération.
 */
class ExclusiveOperationsTest {

    @Before
    fun setUp() = ExclusiveOperations.resetForTests()

    @After
    fun tearDown() = ExclusiveOperations.resetForTests()

    @Test
    fun aucuneOperationNEstEnVolAuDepart() {
        assertNull(ExclusiveOperations.current.value)
    }

    /**
     * Toutes les paires ordonnées, les paires « la même deux fois » comprises : deux imports
     * de photos simultanés se marcheraient dessus autant que deux opérations différentes.
     */
    @Test
    fun toutChevauchementEstRefuseQuelQueSoitLOrdre() {
        for (first in ExclusiveOperation.entries) {
            for (second in ExclusiveOperation.entries) {
                ExclusiveOperations.resetForTests()
                assertTrue(
                    "la première opération ($first) doit pouvoir démarrer",
                    ExclusiveOperations.tryStart(first),
                )
                assertFalse(
                    "$second n'aurait pas dû pouvoir démarrer pendant $first",
                    ExclusiveOperations.tryStart(second),
                )
                assertEquals(
                    "un refus ne doit rien changer à l'opération en vol",
                    first,
                    ExclusiveOperations.current.value,
                )
            }
        }
    }

    @Test
    fun leVerrouSeLibereEtLaSuivantePasse() {
        for (first in ExclusiveOperation.entries) {
            for (second in ExclusiveOperation.entries) {
                ExclusiveOperations.resetForTests()
                assertTrue(ExclusiveOperations.tryStart(first))
                ExclusiveOperations.finish(first)
                assertNull(ExclusiveOperations.current.value)
                assertTrue(
                    "$second doit pouvoir démarrer une fois $first terminée",
                    ExclusiveOperations.tryStart(second),
                )
            }
        }
    }

    /**
     * Une fin tardive ne doit jamais libérer le verrou de quelqu'un d'autre : le `finally` d'une
     * coroutine annulée peut très bien s'exécuter après qu'une autre opération a démarré.
     */
    @Test
    fun uneFinTardiveNeLiberePasLeVerrouDUneAutreOperation() {
        assertTrue(ExclusiveOperations.tryStart(ExclusiveOperation.BACKUP))
        ExclusiveOperations.finish(ExclusiveOperation.BACKUP)
        assertTrue(ExclusiveOperations.tryStart(ExclusiveOperation.PHOTO_IMPORT))

        ExclusiveOperations.finish(ExclusiveOperation.BACKUP)

        assertEquals(ExclusiveOperation.PHOTO_IMPORT, ExclusiveOperations.current.value)
        assertFalse(ExclusiveOperations.tryStart(ExclusiveOperation.RESTORE))
    }

    /** Le libellé alimente le message de refus : il doit rester lisible dans une phrase. */
    @Test
    fun chaqueOperationPorteUnLibelleNonVide() {
        for (operation in ExclusiveOperation.entries) {
            assertTrue(operation.name, operation.label.isNotBlank())
        }
    }
}

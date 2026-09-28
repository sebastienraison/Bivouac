package com.bivouac.app.ui.gpximport

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * RIC-209 (brief Partie B) : "Ce qui ne change pas... Toute la Planification". StatsRows/
 * TotalsCapsule ont reçu un paramètre `duration: DurationDisplay = DurationDisplay.PlainEstimate`
 * (brief : "leurs nouveaux paramètres ont des valeurs par défaut qui redonnent exactement
 * l'affichage actuel") : la Planification n'a donc RIEN à changer pour garder ses estimations,
 * elle n'a simplement pas à passer ce paramètre.
 *
 * Un test de non-régression sur le comportement observable de ces écrans demanderait Compose/
 * Robolectric (hors de ce module de tests JVM purs). Ce test garde le même filet plus simplement,
 * au niveau de la source : tant que ces deux fichiers ne mentionnent ni DurationDisplay ni
 * RealDurationCalculator, ils utilisent forcément le comportement par défaut de StatsRows/
 * TotalsCapsule, donc l'estimation recalculée sans préfixe "≈" (voir StatsRows.kt,
 * DurationDisplay.PlainEstimate). Une évolution future de la Planification qui voudrait la durée
 * réelle devra le faire consciemment, en passant ce test intentionnellement obsolète plutôt qu'en
 * silence.
 */
class PlanificationKeepsEstimatedDurationsTest {

    // Le test tourne avec app/ comme répertoire de travail (voir GeneratedStringsUpToDateTest).
    private val planificationFiles = listOf(
        File("src/main/java/com/bivouac/app/ui/gpximport/GpxImportScreen.kt"),
        File("src/main/java/com/bivouac/app/ui/gpximport/ThreeStopPlanificationDetail.kt"),
    )

    @Test
    fun laPlanificationNAppelleNiDurationDisplayNiRealDurationCalculator() {
        planificationFiles.forEach { file ->
            val text = file.readText()
            assertFalse("${file.path} référence DurationDisplay (brief : la Planification garde ses estimations)", text.contains("DurationDisplay"))
            assertFalse("${file.path} référence RealDurationCalculator (brief : la Planification garde ses estimations)", text.contains("RealDurationCalculator"))
        }
    }
}

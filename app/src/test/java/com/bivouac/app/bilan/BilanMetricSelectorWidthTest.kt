package com.bivouac.app.bilan

import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-193 (2.g) : bilan_metric_label_km devient "Distance" (fr et en), au lieu de "Km" -- plus
 * long. MetricSelector (BilanScreen.kt) répartit les 5 libellés en Box(weight(1f)) SANS padding
 * horizontal ni maxLines, sur une Row qui hérite de la largeur de l'écran moins :
 *   - le padding(horizontal = 20.dp) de la Column racine (BilanContent)
 *   - le padding(16.dp) de la carte Progression (ProgressionSection)
 * Sur un écran 360 dp : 360 - 2*20 - 2*16 = 288 dp, soit 57,6 dp par onglet.
 *
 * Pas d'infrastructure de test Compose avec mesure de layout dans ce module (androidx.compose.ui
 * test n'est câblé qu'en androidTest, exclu ici -- consigne RIC-193 : pas de connectedAndroidTest).
 * Faute de pouvoir mesurer le layout Compose réel, ce test mesure le texte au Paint Android
 * (labelSmall Material3 : 11sp, Typeface par défaut), à densité 1 (1dp = 1px), et compare chaque
 * libellé des 5 onglets à la largeur d'onglet disponible. C'est une approximation (pas de
 * letterSpacing 0.5sp de labelSmall ici, pas la police exacte de l'appareil), mais elle est
 * cohérente d'un libellé à l'autre : si "Bivouacs" (8 caractères, déjà en prod) passe la marge,
 * "Distance" (8 caractères, formes plus étroites) le peut aussi.
 */
@RunWith(RobolectricTestRunner::class)
class BilanMetricSelectorWidthTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    // 288 dp / 5 onglets, arrondi en dessous pour rester une marge de sécurité.
    private val availableTabWidthDp = 57f
    private val labelSmallTextSizeSp = 11f

    private fun measuredWidthDp(text: String): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = labelSmallTextSizeSp
        }
        return paint.measureText(text)
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `les 5 libelles francais tiennent dans la largeur d'un onglet`() {
        val labels = listOf(
            R.string.bilan_metric_label_sorties,
            R.string.bilan_metric_label_km,
            R.string.bilan_metric_label_dplus,
            R.string.bilan_metric_label_vitesse,
            R.string.bilan_metric_label_bivouacs,
        ).map { context.getString(it) }
        assertTrue("Randos, Distance, D+, Vitesse, Bivouacs", labels == listOf("Randos", "Distance", "D+", "Vitesse", "Bivouacs"))
        labels.forEach { label ->
            val width = measuredWidthDp(label)
            assertTrue(
                "« $label » mesure ${width}dp, au-delà des ${availableTabWidthDp}dp disponibles par onglet",
                width <= availableTabWidthDp,
            )
        }
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `les 5 libelles anglais tiennent dans la largeur d'un onglet`() {
        val labels = listOf(
            R.string.bilan_metric_label_sorties,
            R.string.bilan_metric_label_km,
            R.string.bilan_metric_label_dplus,
            R.string.bilan_metric_label_vitesse,
            R.string.bilan_metric_label_bivouacs,
        ).map { context.getString(it) }
        assertTrue("Hikes, Distance, Ascent, Speed, Bivouacs", labels == listOf("Hikes", "Distance", "Ascent", "Speed", "Bivouacs"))
        labels.forEach { label ->
            val width = measuredWidthDp(label)
            assertTrue(
                "« $label » mesure ${width}dp, au-delà des ${availableTabWidthDp}dp disponibles par onglet",
                width <= availableTabWidthDp,
            )
        }
    }

    /**
     * "Distance" ne doit pas être significativement plus large que "Bivouacs" (même 8 caractères,
     * déjà en prod dans la même rangée) : si ce test casse, la marge prise ci-dessus ne suffit
     * probablement plus et le doute doit être tranché sur un vrai appareil.
     */
    @Test
    fun `Distance n'est pas plus large que Bivouacs`() {
        assertTrue(measuredWidthDp("Distance") <= measuredWidthDp("Bivouacs") + 4f)
    }
}

package com.bivouac.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-209 (brief Partie C, chantier RIC-146 lot 9) : la part de marche à côté de la durée
 * (ligne du Journal, [withWalkingShareText]) et dans le libellé de la case du cartouche
 * ([totalsDurationLabelText]). Les deux fonctions sont volontairement des Context -> String pures
 * (pas des @Composable) pour être vérifiées ici sans règle de test Compose, même principe que
 * JournalCountsLocaleTest (context.getString avec l'id et les arguments réels).
 */
@RunWith(RobolectricTestRunner::class)
class DurationShareDisplayTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- withWalkingShareText (ligne StatsRows) -------------------------------------------------

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `la duree porte sa part de marche en francais`() {
        assertEquals("7h40 · 80 % de marche", withWalkingShareText(context, "7h40", 80))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `la duree porte sa part de marche en anglais`() {
        assertEquals("7h 40m · 80% walking", withWalkingShareText(context, "7h 40m", 80))
    }

    @Test
    fun `sans part de marche connue, la duree reste seule - Planification inchangee`() {
        // Brief §Règles : rando sans horodatage ("≈ 4h34", sans part de marche) et Planification
        // (DurationDisplay.PlainEstimate, qui ne porte jamais de walkingSharePercent) passent tous
        // les deux `null` ici : le texte ressort identique à avant ce lot, aucune régression.
        assertEquals("≈ 4h34", withWalkingShareText(context, "≈ 4h34", walkingSharePercent = null))
        assertEquals("7h40", withWalkingShareText(context, "7h40", walkingSharePercent = null))
    }

    // --- totalsDurationLabelText (libellé de la case du cartouche) -----------------------------

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `le libelle du cartouche est la part de marche quand elle est connue`() {
        assertEquals("77 % de marche", totalsDurationLabelText(context, walkingSharePercent = 77))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `le libelle du cartouche est la part de marche en anglais`() {
        assertEquals("77% walking", totalsDurationLabelText(context, walkingSharePercent = 77))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `le libelle du cartouche redevient Duree totale faute de pauses connues`() {
        // Au moins un jour horodaté sans pausedSeconds encore rattrapé : walkingSharePercent est
        // déjà null en amont (RealDurationCalculator), ce cas est donc indiscernable ici du
        // suivant -- exactement ce que le brief §Règles demande ("le libellé redevient... Durée
        // totale") sans distinguer les deux raisons au niveau de l'affichage.
        assertEquals("Durée totale", totalsDurationLabelText(context, walkingSharePercent = null))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `le libelle du cartouche reste Duree totale si aucune rando nest horodatee`() {
        assertEquals("Durée totale", totalsDurationLabelText(context, walkingSharePercent = null))
    }
}

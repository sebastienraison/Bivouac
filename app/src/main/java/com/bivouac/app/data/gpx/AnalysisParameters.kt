package com.bivouac.app.data.gpx

/**
 * RIC-146 : paramètres de la définition fine de la pause et du rythme par pente, source unique.
 * Une seule définition de la pause dans toute l'app : la calibration (DaySegmentAggregate) et la
 * future vue Analyse lisent les mêmes valeurs, sans quoi la prévision et l'analyse d'une même
 * rando se contrediraient.
 *
 * Pause : on reste dans un rayon de 15 m autour du premier point pendant au moins 120 s, ou
 * l'enregistrement s'interrompt au moins 120 s sans déplacement de plus de 50 m (montre en pause
 * automatique). L'ancienne définition (un tronçon de 200 m parcouru à moins de 1 km/h) comptait
 * comme de la marche un arrêt de 3 minutes pris dans une montée.
 *
 * Mesures (conception du 2026-09-28, section 3) :
 *   Roc de Frausa (18,7 km, D+ 1 389 m), marche prévue / réelle :
 *     deux définitions  6h50 / 6h12 ; une seule  6h03 / 6h08
 *   calibration de la sélection de 11 randos, ancienne définition puis définition fine :
 *     vitesse à plat 3,64 puis 3,73 km/h ; pénalité D+ 223 puis 353 m/km ; pauses 19,1 puis 23,3 %
 *   erreur médiane sur les 92 randos hors sélection :
 *     temps de marche 11,8 puis 9,3 % ; temps total 14,4 puis 15,5 %
 *   validation croisée sur les 103 randos :
 *     temps de marche 11,6 puis 8,7 % ; temps total 16,3 puis 14,4 %
 * Le temps total ne s'améliore pas nettement : les pauses restent imprévisibles. Le gain est sur
 * le temps de marche, et surtout sur la cohérence entre prévision et analyse.
 *
 * Bandes de pente nette : onze bandes, bornes en % dans [slopeBandBoundsPercent]. La bande d'un
 * tronçon est le nombre de bornes inférieures ou égales à sa pente (0 : descente de plus de 25 %,
 * 5 : plat entre -2 et 2 %, 10 : montée de plus de 25 %).
 *
 * Un tronçon n'est retenu pour l'allure que si sa vitesse en marche est comprise entre
 * [minMovingSpeedKmh] et [maxMovingSpeedKmh]. Sous 1 km/h, il ne renseigne pas sur l'allure de
 * marche : l'exclure est impératif, pas un raffinement (CR_CALIBRATION_SEGMENTS.md section 6.1 :
 * sans cette exclusion, l'estimateur par segments est PIRE que l'ancien calcul par ligne-rando).
 * Au-delà de 8 km/h, la vitesse n'est pas crédible pour de la marche : c'est une pause qui déborde
 * sur le tronçon et ne lui laisse qu'un temps de marche minuscule.
 *
 * Conception, portage Python de référence et scripts de validation : docs/pilotage/ric-146/ (hors
 * dépôt, non publié).
 */
data class AnalysisParameters(
    /** Rayon autour du premier point d'une pause, en mètres (strictement inférieur). */
    val pauseRadiusMeters: Double,
    /** Durée minimale d'une pause, en secondes. */
    val pauseMinSeconds: Double,
    /** Déplacement maximal (strictement inférieur) d'un trou d'enregistrement compté comme pause. */
    val gapMaxMeters: Double,
    /** Bornes des bandes de pente nette, en %, croissantes : n bornes font n + 1 bandes. */
    val slopeBandBoundsPercent: List<Double>,
    /** Vitesse en marche minimale d'un tronçon retenu, en km/h (incluse). */
    val minMovingSpeedKmh: Double,
    /** Vitesse en marche maximale d'un tronçon retenu, en km/h (incluse). */
    val maxMovingSpeedKmh: Double,
) {
    val slopeBandCount: Int get() = slopeBandBoundsPercent.size + 1

    /** Bande d'une pente nette : nombre de bornes inférieures ou égales à [slopePercent]. */
    fun slopeBandOf(slopePercent: Double): Int = slopeBandBoundsPercent.count { it <= slopePercent }

    companion object {
        const val PAUSE_RADIUS_METERS = 15.0
        const val PAUSE_MIN_SECONDS = 120.0
        const val GAP_MAX_METERS = 50.0
        val SLOPE_BAND_BOUNDS_PERCENT = listOf(-25.0, -15.0, -10.0, -5.0, -2.0, 2.0, 5.0, 10.0, 15.0, 25.0)
        const val MIN_MOVING_SPEED_KMH = 1.0
        const val MAX_MOVING_SPEED_KMH = 8.0

        val DEFAULT = AnalysisParameters(
            pauseRadiusMeters = PAUSE_RADIUS_METERS,
            pauseMinSeconds = PAUSE_MIN_SECONDS,
            gapMaxMeters = GAP_MAX_METERS,
            slopeBandBoundsPercent = SLOPE_BAND_BOUNDS_PERCENT,
            minMovingSpeedKmh = MIN_MOVING_SPEED_KMH,
            maxMovingSpeedKmh = MAX_MOVING_SPEED_KMH,
        )
    }
}

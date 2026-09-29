package com.bivouac.app.data.gpx

import kotlin.math.abs

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
 *   une rando réelle de 18,7 km et 1 389 m de D+, marche prévue / réelle :
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
 * RIC-146 lot 2 (conception section 5.4, 5.6, 7.5) : classes de couleur et meilleurs passages de la
 * vue Analyse.
 *   - [paceClassBoundsPercent] : classe d'allure selon l'écart e (%) entre la vitesse en marche
 *     d'un tronçon et sa vitesse de référence : e < bornes[0], e < bornes[1], e <= bornes[2],
 *     e <= bornes[3], sinon. Deux bornes strictes puis deux inclusives, comme le portage de
 *     référence (`reference_lot2.py`) : ce n'est pas une coquille, ni un cas symétrique.
 *   - [slopeClassBoundsPercent] : classe de pente selon la valeur ABSOLUE de la pente nette
 *     (sans distinguer montée et descente, décision de Seb section 2), bornes toutes strictes.
 *   - [speedClassBoundsKmh] : classe de vitesse en marche, bornes toutes strictes.
 *   - [minReferenceSegmentsPerBand] : sous ce nombre de tronçons dans la référence, une bande
 *     replie sur la vitesse de la rando elle-même (section 5.4).
 *   - [bestClimbMinMovingSeconds]/[bestFlatMinMovingSeconds] : temps de marche cumulé minimal
 *     d'un meilleur passage (section 5.6).
 *
 * RIC-146 lot 6 (conception section 3, mesures section 3.4) : paramètres du découpage d'une
 * journée en phases pour la frise chronologique de la vue Analyse, portage de référence
 * `banc_frise.py` (fonction `phases`).
 *   - [pauseEventSeconds] : durée à partir de laquelle une pause coupe la journée en tronçons de
 *     marche ; une phase ne contient jamais une telle pause et ne la franchit jamais.
 *   - [minWalkBetweenPausesMeters] : deux pauses séparées par moins de cette distance de marche
 *     sont réunies en une seule (durée affichée = somme des deux, bloc = tout l'intervalle) ; en
 *     tête ou en queue de journée, un tronçon de marche plus court que ça est rattaché à la pause
 *     voisine.
 *   - [minPhaseMeters] : une phase de terrain plus courte est fondue dans sa voisine la plus
 *     longue, ou dans ses deux voisines si elles sont de même nature.
 *   - [phaseSlopePercent] : seuil de pente nette d'un tronçon de 200 m pour le classer montée,
 *     descente ou plat, à la fois pour grouper les tronçons d'une phase et pour relire la nature
 *     d'ensemble d'une phase une fois ses bornes fixées. Même valeur que
 *     [TrackSegmenter.FLAT_SLOPE_PERCENT] aujourd'hui, mais un réglage distinct : celui-ci classe
 *     une phase de frise, l'autre un tronçon entier pour la calibration vitesse/pénalité ; rien
 *     n'impose qu'ils bougent ensemble à l'avenir.
 *   - [rollingThresholdMetersPerKm] : au-delà de ce dénivelé cumulé (D+ plus D-) par km, une
 *     phase sans pente nette (ni montée ni descente) est dite vallonnée plutôt que plate.
 *
 * Mesures sur les 103 randos de la sauvegarde utilisée pour la conception (section 3.4) :
 *   lignes de frise par rando 1 à 32, médiane 7 ; part de la distance en montée/descente/vallonné/
 *   plat 40/41/12/7 % ; pauses réunies au seuil de 100 m : 11 sur l'ensemble ; trous et
 *   chevauchements de l'invariant de partage du temps (chaque élément commence où le précédent
 *   finit) : 0 et 0. Avec l'ancienne règle (seuil de pente 4 %, pas de nature vallonnée), 34 % de
 *   la distance était dite plate, pour un dénivelé cumulé médian de 50 m/km sur ces phases : d'où
 *   l'ajout de la nature vallonnée et l'abaissement du seuil à 2 %.
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
    /** Bornes de la classe d'allure, en % d'écart à la référence : 2 strictes puis 2 inclusives. */
    val paceClassBoundsPercent: List<Double>,
    /** Bornes de la classe de pente, en % de pente nette absolue, toutes strictes. */
    val slopeClassBoundsPercent: List<Double>,
    /** Bornes de la classe de vitesse en marche, en km/h, toutes strictes. */
    val speedClassBoundsKmh: List<Double>,
    /** Nombre minimal de tronçons d'une bande de référence, sous lequel elle replie (section 5.4). */
    val minReferenceSegmentsPerBand: Int,
    /** Temps de marche cumulé minimal d'une meilleure montée soutenue, en secondes. */
    val bestClimbMinMovingSeconds: Double,
    /** Temps de marche cumulé minimal d'un meilleur passage à plat, en secondes. */
    val bestFlatMinMovingSeconds: Double,
    /** RIC-146 lot 6 : durée minimale d'une pause qui coupe la journée en tronçons de marche. */
    val pauseEventSeconds: Double,
    /** RIC-146 lot 6 : marche minimale entre deux pauses d'événement pour ne pas les réunir. */
    val minWalkBetweenPausesMeters: Double,
    /** RIC-146 lot 6 : longueur minimale d'une phase de terrain avant fusion avec sa voisine. */
    val minPhaseMeters: Double,
    /** RIC-146 lot 6 : seuil de pente nette (%) qui classe un tronçon ou une phase montée/descente/plat. */
    val phaseSlopePercent: Double,
    /** RIC-146 lot 6 : dénivelé cumulé (m/km) au-delà duquel une phase plate est dite vallonnée. */
    val rollingThresholdMetersPerKm: Double,
) {
    val slopeBandCount: Int get() = slopeBandBoundsPercent.size + 1

    /** Bande d'une pente nette : nombre de bornes inférieures ou égales à [slopePercent]. */
    fun slopeBandOf(slopePercent: Double): Int = slopeBandBoundsPercent.count { it <= slopePercent }

    /**
     * Centre de la bande [band] (0 à [slopeBandCount] - 1), pour l'interpolation entre bandes
     * (section 5.4) : moyenne des deux bornes qui l'encadrent, ou une extrémité ouverte étendue de
     * [OPEN_BAND_CENTER_MARGIN_PERCENT] au-delà de la dernière borne connue, comme le portage de
     * référence (`analyse.py`, fonction `band_center`).
     */
    fun slopeBandCenter(band: Int): Double {
        val bounds = slopeBandBoundsPercent
        return when (band) {
            0 -> bounds.first() - OPEN_BAND_CENTER_MARGIN_PERCENT
            bounds.size -> bounds.last() + OPEN_BAND_CENTER_MARGIN_PERCENT
            else -> (bounds[band - 1] + bounds[band]) / 2.0
        }
    }

    /** Classe d'allure : voir [paceClassBoundsPercent]. */
    fun paceClassOf(deltaPercent: Double): Int {
        val b = paceClassBoundsPercent
        return when {
            deltaPercent < b[0] -> 0
            deltaPercent < b[1] -> 1
            deltaPercent <= b[2] -> 2
            deltaPercent <= b[3] -> 3
            else -> 4
        }
    }

    /** Classe de pente sur la valeur absolue de [netSlopePercent] : voir [slopeClassBoundsPercent]. */
    fun slopeClassOf(netSlopePercent: Double): Int {
        val absolute = abs(netSlopePercent)
        val b = slopeClassBoundsPercent
        return when {
            absolute < b[0] -> 0
            absolute < b[1] -> 1
            absolute < b[2] -> 2
            absolute < b[3] -> 3
            else -> 4
        }
    }

    /** Classe de vitesse en marche : voir [speedClassBoundsKmh]. */
    fun speedClassOf(movingSpeedKmh: Double): Int {
        val b = speedClassBoundsKmh
        return when {
            movingSpeedKmh < b[0] -> 0
            movingSpeedKmh < b[1] -> 1
            movingSpeedKmh < b[2] -> 2
            movingSpeedKmh < b[3] -> 3
            else -> 4
        }
    }

    companion object {
        const val PAUSE_RADIUS_METERS = 15.0
        const val PAUSE_MIN_SECONDS = 120.0
        const val GAP_MAX_METERS = 50.0
        val SLOPE_BAND_BOUNDS_PERCENT = listOf(-25.0, -15.0, -10.0, -5.0, -2.0, 2.0, 5.0, 10.0, 15.0, 25.0)
        const val MIN_MOVING_SPEED_KMH = 1.0
        const val MAX_MOVING_SPEED_KMH = 8.0

        // Écart maximal au-delà duquel band_center étend une bande ouverte (bornée d'un seul côté) :
        // valeur du portage de référence, sans autre justification qu'une extrapolation raisonnable
        // au-delà de la dernière borne connue.
        private const val OPEN_BAND_CENTER_MARGIN_PERCENT = 7.5

        val PACE_CLASS_BOUNDS_PERCENT = listOf(-25.0, -10.0, 10.0, 25.0)
        val SLOPE_CLASS_BOUNDS_PERCENT = listOf(5.0, 10.0, 15.0, 25.0)
        val SPEED_CLASS_BOUNDS_KMH = listOf(2.0, 3.0, 4.0, 5.0)
        const val MIN_REFERENCE_SEGMENTS_PER_BAND = 20
        const val BEST_CLIMB_MIN_MOVING_SECONDS = 1_200.0
        const val BEST_FLAT_MIN_MOVING_SECONDS = 600.0

        const val PAUSE_EVENT_SECONDS = 300.0
        const val MIN_WALK_BETWEEN_PAUSES_METERS = 100.0
        const val MIN_PHASE_METERS = 1_000.0
        const val PHASE_SLOPE_PERCENT = 2.0
        const val ROLLING_THRESHOLD_METERS_PER_KM = 30.0

        val DEFAULT = AnalysisParameters(
            pauseRadiusMeters = PAUSE_RADIUS_METERS,
            pauseMinSeconds = PAUSE_MIN_SECONDS,
            gapMaxMeters = GAP_MAX_METERS,
            slopeBandBoundsPercent = SLOPE_BAND_BOUNDS_PERCENT,
            minMovingSpeedKmh = MIN_MOVING_SPEED_KMH,
            maxMovingSpeedKmh = MAX_MOVING_SPEED_KMH,
            paceClassBoundsPercent = PACE_CLASS_BOUNDS_PERCENT,
            slopeClassBoundsPercent = SLOPE_CLASS_BOUNDS_PERCENT,
            speedClassBoundsKmh = SPEED_CLASS_BOUNDS_KMH,
            minReferenceSegmentsPerBand = MIN_REFERENCE_SEGMENTS_PER_BAND,
            bestClimbMinMovingSeconds = BEST_CLIMB_MIN_MOVING_SECONDS,
            bestFlatMinMovingSeconds = BEST_FLAT_MIN_MOVING_SECONDS,
            pauseEventSeconds = PAUSE_EVENT_SECONDS,
            minWalkBetweenPausesMeters = MIN_WALK_BETWEEN_PAUSES_METERS,
            minPhaseMeters = MIN_PHASE_METERS,
            phaseSlopePercent = PHASE_SLOPE_PERCENT,
            rollingThresholdMetersPerKm = ROLLING_THRESHOLD_METERS_PER_KM,
        )
    }
}

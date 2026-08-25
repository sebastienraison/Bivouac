package com.bivouac.app.data.gpx

/**
 * Derives a [SpeedCalibration] from real hikes instead of asking the user to type numbers in —
 * the "Auto"/"Sélection" modes of BIV-16's Vitesse personnalisée.
 *
 * RIC-109 : remplace le calcul par ligne-rando (système 2x2 sur une ligne par rando, où distance et
 * D+ sont confondus par construction — une rando longue est aussi une rando qui monte beaucoup) par
 * un calcul par segments de 200 m qui sépare les deux effets à l'INTÉRIEUR de chaque rando. Sur le
 * Journal réel de l'utilisateur, le facteur d'inflation de variance de l'ancien calcul valait 11 à
 * 14 (retirer une seule rando d'un Journal de dix déplaçait la vitesse de 1 km/h) ; en segments, ce
 * facteur tombe à 1,6. Voir docs/pilotage/CR_CALIBRATION_SEGMENTS.md pour l'investigation complète
 * et docs/pilotage/prototype-calibration-segments/prototype_calibration.py pour l'algorithme de
 * référence, exécutable et validé, dont ce fichier est le portage fidèle.
 *
 * Modèle inchangé par rapport à l'ancien calcul : heures = distanceKm / vitesse + D+_m / (pénalité
 * * vitesse). Aucun terme de descente n'est introduit (voir CR section 12 : un modèle non linéaire
 * en pente a été testé et écarté, aucun gain démontré).
 *
 *   Phase 1 — la vitesse à plat est mesurée sur les segments de pente nette < 2 %, en écartant ceux
 *             passés à l'arrêt (< 1 km/h). Sans cette exclusion la méthode est PIRE que l'ancien
 *             calcul (CR section 6.1) : ce n'est pas un raffinement optionnel.
 *   Phase 2 — sur les segments de pente >= 2 % (montée comme descente), le temps qui dépasse ce que
 *             la marche à plat expliquerait est attribué au D+ cumulé de ces segments. Les segments
 *             de descente y participent sans apporter de D+ : leur surcoût est absorbé par la
 *             pénalité, exactement comme le fait déjà le modèle de production faute de terme D-.
 */
object SpeedCalibrationCalculator {

    data class Sample(val distanceMeters: Double, val elevationGainMeters: Double, val elapsedHours: Double)

    /** Ce que l'estimateur a réellement pu faire — utile pour ce que l'IHM raconte à l'utilisateur
     * (point ouvert pour le pilotage, voir CR_RIC109_IMPLEMENTATION.md : généraliser à tous les cas
     * de repli le message que SettingsScreen affiche déjà pour selectedTrackCount == 1). */
    data class Result(val calibration: SpeedCalibration, val fittedPenalty: Boolean, val note: String = "")

    // UI-level gate (BIV-16 recette) for Auto (whole Journal) and Sélection (confirmed subset):
    // below this, there's no meaningful calibration to compute, so the segmented control disables
    // that mode entirely. Volontairement conservé à sa valeur d'origine malgré des seuils de repli
    // par segments plus fins (MIN_FLAT_SEGMENTS etc. ci-dessous) : c'est un pré-filtre IHM, pas la
    // détection de repli elle-même, qui reste [Result.fittedPenalty].
    const val MIN_TRACKS_FOR_CALIBRATION = 2

    // Seuils de repli (CR section 5.4), identiques au prototype.
    private const val MIN_FLAT_SEGMENTS = 10
    private const val MIN_STEEP_SEGMENTS = 10
    private const val MIN_TOTAL_GAIN_METERS = 300.0

    // Bornes de plausibilité, identiques à l'ancien calcul.
    private const val MIN_SPEED_KMH = 1.0
    private const val MAX_SPEED_KMH = 8.0
    private const val MIN_PENALTY_M_PER_KM = 20.0

    /**
     * RIC-130 : le plafond de pénalité n'est plus fixe. L'ancien `MAX_PENALTY_M_PER_KM = 300`
     * (hérité tel quel de l'ancien calcul) n'a jamais été une borne physique : c'est un garde-fou
     * numérique. La pénalité vaut D+ / (vitesse * temps excédentaire), où le temps excédentaire est
     * une DIFFÉRENCE ENTRE DEUX GRANDS NOMBRES PROCHES (le temps passé sur le pentu, moins ce que la
     * vitesse à plat expliquerait). Ce genre de soustraction amplifie énormément le bruit : sur le
     * Journal réel, une erreur de 0,2 km/h sur la vitesse à plat déplace la pénalité déduite de
     * plusieurs centaines de m/km (CR_CALIBRATION_SEGMENTS.md, section 6.1). Une pénalité qui sort
     * haute est donc le plus souvent un symptôme de bruit, pas un signal.
     *
     * Mais ce bruit s'atténue avec le volume de données : sur 300 pools aléatoires tirés du Journal
     * réel (script 20_plafond_adaptatif.py), le p90 de la pénalité brute tombe de ~1780 m/km quand
     * le pool cumule 1000 m de D+ pentu à ~470 quand il en cumule 35 000, pendant que la médiane
     * reste stable autour de 370-380 : au-dessus de 300. Le plafond fixe coupait donc un signal
     * probablement réel dès que les données suffisaient, tout en restant indispensable sur les
     * petits pools (mode Sélection à 2-3 randos, où le p90 brut dépasse 1500).
     *
     * D'où un plafond fonction du D+ cumulé observé sur les segments pentus ([maxPenaltyFor]) :
     * 300 (comportement actuel conservé au point le plus fragile, le seuil de repli
     * [MIN_TOTAL_GAIN_METERS]) montant linéairement jusqu'à 450 à 20 000 m de D+, clampé aux deux
     * bouts.
     */
    private const val MAX_PENALTY_M_PER_KM_FLOOR = 300.0
    private const val MAX_PENALTY_M_PER_KM_CEILING = 450.0
    private const val ADAPTIVE_CEILING_GAIN_METERS = 20_000.0
    private val DEFAULT_PENALTY_M_PER_KM = SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm

    /** Plafond de plausibilité de la pénalité pour un pool cumulant [steepGainMeters] de D+ sur ses
     * segments pentus : interpolation linéaire clampée entre [MAX_PENALTY_M_PER_KM_FLOOR] (à
     * [MIN_TOTAL_GAIN_METERS]) et [MAX_PENALTY_M_PER_KM_CEILING] (à partir de
     * [ADAPTIVE_CEILING_GAIN_METERS]). Voir la kdoc des constantes pour la justification. */
    private fun maxPenaltyFor(steepGainMeters: Double): Double {
        val t = (steepGainMeters - MIN_TOTAL_GAIN_METERS) /
            (ADAPTIVE_CEILING_GAIN_METERS - MIN_TOTAL_GAIN_METERS)
        return MAX_PENALTY_M_PER_KM_FLOOR +
            t.coerceIn(0.0, 1.0) * (MAX_PENALTY_M_PER_KM_CEILING - MAX_PENALTY_M_PER_KM_FLOOR)
    }

    /**
     * Calibration à partir des sommes de segments du Journal (ou du sous-ensemble sélectionné), ou
     * `null` si rien n'est exploitable.
     *
     * [fallbackSamples] porte les échantillons ligne-rando (distance/D+/durée déjà dénormalisés,
     * voir [com.bivouac.app.data.db.LoggedTrackRepository.calibrationSamples]) utilisés uniquement
     * quand [aggregate] n'a pas assez de plat pour mesurer une vitesse : le prototype Python
     * (`_speed_only`) somme alors sur *tous* les segments utilisables de la sélection, mais cette
     * somme n'est pas de la forme agrégée à 7 nombres — la reconstituer exigerait de re-parser les
     * GPX, ce que RIC-62/98/99 a précisément supprimé. [fallbackSamples] est l'équivalent déjà
     * dénormalisé au niveau de la rando entière (pas du segment) : distance et D+ totaux de la
     * rando, durée réelle. Numériquement très proche de la somme "usable" du prototype (l'écart
     * tient au seul reliquat de fin de trace, < 100 m, écarté par le découpage) et sans le moindre
     * re-parsing. Voir CR_RIC109_IMPLEMENTATION.md pour la discussion complète de ce choix.
     */
    fun compute(aggregate: DaySegmentAggregate, fallbackSamples: List<Sample>): Result? {
        if (aggregate.flatCount < MIN_FLAT_SEGMENTS || aggregate.flatHours <= 0.0) {
            return speedOnly(fallbackSamples, "pas assez de terrain plat pour mesurer une vitesse à plat")
        }
        val speed = (aggregate.flatDistanceMeters / 1000.0) / aggregate.flatHours

        if (aggregate.steepCount < MIN_STEEP_SEGMENTS || aggregate.steepGainMeters < MIN_TOTAL_GAIN_METERS) {
            return Result(
                SpeedCalibration(speed.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH), DEFAULT_PENALTY_M_PER_KM),
                fittedPenalty = false,
                note = "pas assez de dénivelé pour mesurer une pénalité",
            )
        }

        val excessHours = aggregate.steepHours - (aggregate.steepDistanceMeters / 1000.0) / speed
        if (excessHours <= 0.0) {
            // Le terrain pentu a été parcouru aussi vite que le plat : rien à attribuer au D+.
            return Result(
                SpeedCalibration(speed.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH), maxPenaltyFor(aggregate.steepGainMeters)),
                fittedPenalty = false,
                note = "aucun surcoût de dénivelé mesurable",
            )
        }

        val penalty = aggregate.steepGainMeters / (speed * excessHours)
        return Result(
            SpeedCalibration(
                speed.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH),
                penalty.coerceIn(MIN_PENALTY_M_PER_KM, maxPenaltyFor(aggregate.steepGainMeters)),
            ),
            fittedPenalty = true,
        )
    }

    /** Repli à une seule inconnue : la vitesse, ajustée contre la pénalité par défaut. */
    private fun speedOnly(samples: List<Sample>, note: String): Result? {
        val valid = samples.filter { it.elapsedHours > 0.0 && it.distanceMeters >= 0.0 && it.elevationGainMeters >= 0.0 }
        if (valid.isEmpty()) return null
        val equivalentKm = valid.sumOf { it.distanceMeters / 1000.0 + it.elevationGainMeters / DEFAULT_PENALTY_M_PER_KM }
        val hours = valid.sumOf { it.elapsedHours }
        if (equivalentKm <= 0.0 || hours <= 0.0) return null
        return Result(
            SpeedCalibration((equivalentKm / hours).coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH), DEFAULT_PENALTY_M_PER_KM),
            fittedPenalty = false,
            note = note,
        )
    }
}

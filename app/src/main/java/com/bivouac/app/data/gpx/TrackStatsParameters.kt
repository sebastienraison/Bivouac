package com.bivouac.app.data.gpx

/**
 * RIC-114 : paramètres du calcul de distance et de dénivelé, source unique.
 *
 * D+ et D- : moyenne glissante de l'altitude sur une fenêtre de 50 m de distance parcourue
 * (centrée, +/- 25 m), puis somme des deltas. Distance : un point n'est cumulé que s'il est à plus
 * de 3 m du dernier point retenu. Une seule fenêtre pour toutes les sources d'altitude, sans
 * détection de source. Jamais de modèle numérique de terrain : mesuré, il double l'erreur par
 * sortie sur des altitudes barométriques, et il exigerait le réseau et l'envoi de la trace à un
 * tiers.
 *
 * Mesures (72 sorties barométriques, D+ total comparé à celui de la montre) :
 *   ancien calcul (moyenne sur 5 points)   +8,0 %
 *   fenêtre 30 m                           +1,5 %
 *   fenêtre 40 m                           -0,6 %
 *   fenêtre 50 m                           -2,2 %   (-2,7 % avec le seuil de 3 m)
 *   fenêtre 100 m                          -7,1 %
 *   distance : +2,15 % (somme brute) vers -0,17 % (seuil 3 m), écart absolu moyen 0,94 %
 * Sur 10 traces à altitude GPS de téléphone, la fenêtre de 50 m tombe à +0,9 % d'un estimateur
 * indépendant : c'est ce qui justifie une fenêtre unique.
 *
 * Alternative écartée, à reprendre si des traces GPS de téléphone s'avèrent mal servies : deux
 * profils, 40 m sur altitude barométrique et 60 m sur altitude GPS. Il faudrait alors détecter la
 * source à l'import (attribut creator du GPX, extension Garmin, altitudes quantifiées à 0,2 m),
 * stocker la source détectée par jour et par trace de la banque (le GPX de la banque est
 * réécrit et perd ces indices), passer les paramètres correspondants à chaque appel, et monter
 * ALGORITHM_VERSION pour rattraper les valeurs stockées.
 *
 * Rapport de mesure complet, scripts et données : docs/pilotage/ric-114/ (hors dépôt, non publié).
 */
data class TrackStatsParameters(
    /** Largeur totale de la fenêtre de la moyenne glissante d'altitude, en mètres parcourus. */
    val elevationWindowMeters: Double,
    /** Déplacement minimal (strictement dépassé) depuis le dernier point retenu pour cumuler. */
    val minMoveMeters: Double,
) {
    companion object {
        const val ELEVATION_WINDOW_METERS = 50.0
        const val MIN_MOVE_METERS = 3.0

        val DEFAULT = TrackStatsParameters(ELEVATION_WINDOW_METERS, MIN_MOVE_METERS)

        /**
         * Version des statistiques dérivées (distance, D+, D-, agrégats de segments) produites avec
         * [DEFAULT]. La monter déclenchera le rattrapage des valeurs stockées, sans migration de
         * schéma : c'est le lot 2 de RIC-114 qui la consomme.
         */
        const val ALGORITHM_VERSION = 1
    }
}

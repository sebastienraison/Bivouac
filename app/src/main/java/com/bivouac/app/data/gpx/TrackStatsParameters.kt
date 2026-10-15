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
         *
         * v2 : ajout des totaux par jour (distanceMeters/elevationGainMeters/elevationLossMeters sur
         * logged_track_day, RIC-207), sans changement de calcul.
         *
         * v3 : définition fine des pauses (RIC-146, voir AnalysisParameters) : les sommes de
         * calibration des jours sont recalculées sur le temps de marche, pausedSeconds et le
         * rythme par pente (logged_track_day_pace) sont remplis. Distance, D+ et D- ne changent pas ;
         * la Banque est recalculée à l'identique, le marqueur étant commun.
         *
         * v4 : nuit au bivouac à l'intérieur d'un jour (RIC-226, conception RIC-222 §3.4, voir
         * DayBivouacs et JournalDayStats). Pour un jour du Journal qui contient un bivouac détecté
         * (BivouacDetector), elapsedSeconds perd les trous de bivouac ; pauses, sommes de
         * calibration et rythme par pente sont détectés de part et d'autre de la nuit (qui n'est
         * plus ni une pause ni un segment à l'arrêt) ; la distance, le D+ et le D- du trou ne sont
         * comptés que si la reprise est à moins de 50 m. Un jour sans bivouac ne change pas ; la
         * Banque est recalculée à l'identique, le marqueur étant commun, et la calibration Auto et
         * Sélection est recalculée derrière (CalibrationRefresh).
         *
         * RIC-229 : pas de v5. Une nuit refusée (« Pause ») se calcule autrement qu'un bivouac,
         * mais aucune rando existante n'a de refus mémorisé (colonne refusedBivouacStops nulle
         * partout après la migration 22 vers 23) : il n'y a rien à recalculer. Les chiffres d'un
         * jour sont recalculés au moment où un refus est écrit (LoggedTrackRepository).
         */
        const val ALGORITHM_VERSION = 4
    }
}

/**
 * RIC-227 (lot 3 de la 2.7.0, conception RIC-222 §3.2) : version courante du crible des bivouacs
 * détectés, écrite dans `logged_track.bivouacReviewVersion` quand une rando a été proposée à
 * l'utilisateur (à l'import, au premier lancement ou par l'action de menu), qu'il ait découpé ou
 * non, et même quand aucun bivouac n'a été trouvé. Une rando en dessous de cette version est
 * repassée au crible au lancement suivant (voir ElevationBackfillGate). La monter reproposerait
 * toutes les randos : à réserver à un changement de la règle de détection (BivouacDetector).
 *
 * RIC-229 : reste à 1. La réponse « Pause » est désormais mémorisée par jour
 * (logged_track_day.refusedBivouacStops), mais les randos déjà examinées n'en ont aucune à
 * retrouver : les reproposer toutes n'apporterait rien, l'action de menu suffit pour y revenir.
 */
const val BIVOUAC_REVIEW_VERSION = 1

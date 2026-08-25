package com.bivouac.app.bilan

import com.bivouac.app.data.db.LoggedTrackDayEntity
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.SpeedCalibrationCalculator
import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.gpx.TrackStatsCalculator
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** RIC-19 §2 : les 5 métriques du sélecteur, dans l'ordre d'affichage de la maquette. */
enum class ProgressionMetric(val label: String, val chartTitle: String, val isLine: Boolean) {
    SORTIES("Sorties", "Sorties par mois", isLine = false),
    KM("Km", "Km par mois", isLine = false),
    DPLUS("D+", "D+ par mois", isLine = false),
    // Ligne (sparkline) et non barres : c'est une moyenne, pas une somme (RIC-19 §2) — sommer une
    // vitesse d'un mois à l'autre n'a aucun sens physique.
    VITESSE("Vitesse", "Vitesse à plat calibrée, par mois", isLine = true),
    BIVOUACS("Bivouacs", "Bivouacs par mois", isLine = false),
}

/** Un point du graphique Progression. [value] est null quand le mois n'a littéralement rien à montrer
 * (aucune sortie ce mois-ci pour Vitesse, avant toute mesure exploitable) — distinct de 0.0, une
 * valeur réelle pour les métriques en barres. */
data class MonthPoint(val yearMonth: YearMonth, val value: Double?)

data class ProgressionSeries(val metric: ProgressionMetric, val points: List<MonthPoint>)

enum class BilanRecordKind { KM_EFFORT, VAM, MAX_ALTITUDE, HIGHEST_BIVOUAC, MAX_DISTANCE_DAY, MAX_GAIN_DAY, LONGEST_TREK }

/**
 * Un record du Bilan, prêt à être mis en forme par l'écran (RIC-19 §3/§4) et à ramener vers la
 * sortie réelle qui le porte (RIC-19 §6, "chaque chiffre affiché doit pouvoir ramener à la ou les
 * sorties réelles du Journal derrière lui").
 *
 * [dayIndex] : non nul seulement pour [BilanRecordKind.HIGHEST_BIVOUAC] et
 * [BilanRecordKind.LONGEST_TREK] — les deux seuls records dont la spec demande explicitement un
 * positionnement sur le bon jour à l'ouverture ; les autres ouvrent directement la trace entière
 * (RIC-19 §6). [extraDistanceKm]/[extraGainMeters] ne portent que pour LONGEST_TREK (métadonnée
 * descriptive du trek, RIC-19 §4) — ailleurs toujours null.
 */
data class BilanRecord(
    val kind: BilanRecordKind,
    val value: Double,
    val placeName: String,
    val whenMillis: Long,
    val trackId: String,
    val dayIndex: Int? = null,
    val extraDistanceKm: Double? = null,
    val extraGainMeters: Double? = null,
)

data class BilanStats(
    val totalCount: Int,
    val totals: TrackStats,
    val bivouacCount: Int,
    val progression: List<ProgressionSeries>,
    // RIC-19 §2 : mois calendaire (toutes années confondues) avec le plus de sorties cumulées —
    // null si les sorties ne couvrent pas au moins 2 mois calendaires distincts, seul garde-fou de
    // tout l'écran en dehors de celui, déjà existant, de la VAM.
    val mostActiveMonthInsight: MostActiveMonthInsight?,
    val kmEffortRecord: BilanRecord?,
    val vamRecord: BilanRecord?,
    val maxAltitudeRecord: BilanRecord?,
    val highestBivouacRecord: BilanRecord?,
    val maxDistanceDayRecord: BilanRecord?,
    val maxGainDayRecord: BilanRecord?,
    val longestTrekRecord: BilanRecord?,
)

data class MostActiveMonthInsight(val monthOfYear: Int, val cumulativeCount: Int, val sinceYear: Int)

/**
 * RIC-19 : calculateur pur (aucune I/O, aucun accès Room/fichier) — reçoit tout ce dont il a besoin
 * déjà chargé, pour rester testable en JVM comme [TrackStatsCalculator]/[SpeedCalibrationCalculator].
 * [daysByTrackId] : voir [com.bivouac.app.data.db.LoggedTrackRepository.allDaysByTrackId], jours
 * triés par dayIndex.
 */
object BilanStatsCalculator {

    fun compute(
        tracks: List<LoggedTrackEntity>,
        daysByTrackId: Map<String, List<LoggedTrackDayEntity>>,
        calibration: SpeedCalibration,
        zone: ZoneId = ZoneId.systemDefault(),
        now: YearMonth = YearMonth.now(zone),
    ): BilanStats {
        val totals = TrackStatsCalculator.recomputeDuration(
            TrackStats(
                distanceMeters = tracks.sumOf { it.distanceMeters },
                elevationGainMeters = tracks.sumOf { it.elevationGainMeters },
                elevationLossMeters = tracks.sumOf { it.elevationLossMeters },
                estimatedDurationMinutes = 0,
            ),
            calibration,
        )
        val bivouacCountByTrack = tracks.associate { it.id to bivouacCount(daysByTrackId[it.id].orEmpty()) }
        val totalBivouacs = bivouacCountByTrack.values.sum()

        return BilanStats(
            totalCount = tracks.size,
            totals = totals,
            bivouacCount = totalBivouacs,
            progression = buildProgression(tracks, daysByTrackId, zone, now),
            mostActiveMonthInsight = buildInsight(tracks, zone),
            kmEffortRecord = kmEffortRecord(tracks, daysByTrackId, calibration),
            vamRecord = vamRecord(tracks, daysByTrackId),
            maxAltitudeRecord = maxAltitudeRecord(tracks, daysByTrackId),
            highestBivouacRecord = highestBivouacRecord(tracks, daysByTrackId),
            maxDistanceDayRecord = maxDistanceDayRecord(tracks, daysByTrackId),
            maxGainDayRecord = maxGainDayRecord(tracks, daysByTrackId),
            longestTrekRecord = longestTrekRecord(tracks, daysByTrackId),
        )
    }

    // dayCount - 1 : même convention que JournalDayInfo.bivouacCount côté Journal (une nuit dehors
    // est une coupure entre deux jours, pas un décompte de dates connues).
    private fun bivouacCount(days: List<LoggedTrackDayEntity>): Int = (days.size - 1).coerceAtLeast(0)

    private fun yearMonthOf(millis: Long, zone: ZoneId): YearMonth = YearMonth.from(Instant.ofEpochMilli(millis).atZone(zone))

    // --- Progression (RIC-19 §2) ------------------------------------------------------------

    private fun buildProgression(
        tracks: List<LoggedTrackEntity>,
        daysByTrackId: Map<String, List<LoggedTrackDayEntity>>,
        zone: ZoneId,
        now: YearMonth,
    ): List<ProgressionSeries> {
        if (tracks.isEmpty()) return emptyList()
        val firstMonth = tracks.minOf { yearMonthOf(it.startedAt, zone) }
        val months = generateSequence(firstMonth) { it.plusMonths(1) }.takeWhile { !it.isAfter(now) }.toList()
        val tracksByMonth = tracks.groupBy { yearMonthOf(it.startedAt, zone) }

        val bars = mapOf(
            ProgressionMetric.SORTIES to months.map { month -> tracksByMonth[month]?.size?.toDouble() ?: 0.0 },
            ProgressionMetric.KM to months.map { month ->
                tracksByMonth[month]?.sumOf { it.distanceMeters / 1000.0 } ?: 0.0
            },
            ProgressionMetric.DPLUS to months.map { month ->
                tracksByMonth[month]?.sumOf { it.elevationGainMeters } ?: 0.0
            },
            ProgressionMetric.BIVOUACS to months.map { month ->
                tracksByMonth[month]?.sumOf { bivouacCount(daysByTrackId[it.id].orEmpty()) }?.toDouble() ?: 0.0
            },
        )

        // Vitesse à plat calibrée : moyenne pondérée (Σdistance/Σheures) des segments plats des
        // jours des sorties DÉMARRÉES ce mois-là, pas une moyenne des sorties (une sortie plus
        // longue pèse plus, comme la calibration Auto elle-même). Un mois sans mesure exploitable
        // reporte la dernière valeur connue plutôt qu'un trou ou un 0 trompeur (voir MonthPoint).
        var lastKnownSpeed: Double? = null
        val speedPoints = months.map { month ->
            val days = tracksByMonth[month].orEmpty().flatMap { daysByTrackId[it.id].orEmpty() }
            val flatDistance = days.sumOf { it.flatDistanceMeters ?: 0.0 }
            val flatHours = days.sumOf { it.flatHours ?: 0.0 }
            val speed = if (flatHours > 0.0) (flatDistance / 1000.0) / flatHours else null
            if (speed != null) lastKnownSpeed = speed
            MonthPoint(month, speed ?: lastKnownSpeed)
        }

        return ProgressionMetric.entries.map { metric ->
            if (metric == ProgressionMetric.VITESSE) {
                ProgressionSeries(metric, speedPoints)
            } else {
                ProgressionSeries(metric, months.zip(bars.getValue(metric)) { month, value -> MonthPoint(month, value) })
            }
        }
    }

    private fun buildInsight(tracks: List<LoggedTrackEntity>, zone: ZoneId): MostActiveMonthInsight? {
        val yearMonths = tracks.map { yearMonthOf(it.startedAt, zone) }
        if (yearMonths.distinct().size < 2) return null
        val byMonthOfYear = tracks.groupBy { yearMonthOf(it.startedAt, zone).monthValue }
        val (monthOfYear, group) = byMonthOfYear.maxByOrNull { it.value.size } ?: return null
        return MostActiveMonthInsight(
            monthOfYear = monthOfYear,
            cumulativeCount = group.size,
            sinceYear = yearMonths.minOf { it.year },
        )
    }

    // --- Records vedettes (RIC-19 §3) --------------------------------------------------------

    // Par jour et non par trace entière (contrairement à la première version de ce calcul) : la
    // distance/D+ cumulés d'un trek de plusieurs jours dépassent presque toujours ceux d'une seule
    // journée, aussi sportive soit-elle — un trek gagnait donc systématiquement, quelle que soit
    // l'intensité réelle de chaque jour qui le compose. Mêmes colonnes que maxDistanceDayRecord/
    // maxGainDayRecord ci-dessous (flatDistanceMeters+steepDistanceMeters, steepGainMeters).
    private fun kmEffortRecord(
        tracks: List<LoggedTrackEntity>,
        daysByTrackId: Map<String, List<LoggedTrackDayEntity>>,
        calibration: SpeedCalibration,
    ): BilanRecord? {
        val byTrack = tracks.associateBy { it.id }
        var best: BilanRecord? = null
        for ((trackId, days) in daysByTrackId) {
            val entry = byTrack[trackId] ?: continue
            for (day in days) {
                val flat = day.flatDistanceMeters ?: continue
                val steep = day.steepDistanceMeters ?: continue
                val gain = day.steepGainMeters ?: continue
                val equivalentKm = (flat + steep) / 1000.0 + gain / calibration.elevationGainPenaltyMetersPerKm
                if (best == null || equivalentKm > best.value) {
                    best = BilanRecord(
                        kind = BilanRecordKind.KM_EFFORT,
                        value = equivalentKm,
                        placeName = entry.name,
                        whenMillis = day.startedAtMillis ?: entry.startedAt,
                        trackId = trackId,
                    )
                }
            }
        }
        return best
    }

    // Même garde-fou que la calibration par segments (SpeedCalibrationCalculator) : une sortie trop
    // courte ou trop plate pour mesurer une pénalité D+ fiable n'a pas plus de sens comme record VAM
    // qu'elle n'en a comme échantillon de calibration — un artefact de bruit s'afficherait sinon
    // comme "record" (RIC-19 §3, garde explicitement demandée par le ticket).
    private fun vamRecord(
        tracks: List<LoggedTrackEntity>,
        daysByTrackId: Map<String, List<LoggedTrackDayEntity>>,
    ): BilanRecord? {
        val byTrack = tracks.associateBy { it.id }
        var best: BilanRecord? = null
        for ((trackId, days) in daysByTrackId) {
            val entry = byTrack[trackId] ?: continue
            for (day in days) {
                val steepCount = day.steepCount ?: continue
                val steepGain = day.steepGainMeters ?: continue
                val steepHours = day.steepHours ?: continue
                if (steepCount < SpeedCalibrationCalculator.MIN_STEEP_SEGMENTS) continue
                if (steepGain < SpeedCalibrationCalculator.MIN_TOTAL_GAIN_METERS) continue
                if (steepHours <= 0.0) continue
                val vam = steepGain / steepHours
                if (best == null || vam > best.value) {
                    best = BilanRecord(
                        kind = BilanRecordKind.VAM,
                        value = vam,
                        placeName = entry.name,
                        whenMillis = day.startedAtMillis ?: entry.startedAt,
                        trackId = trackId,
                    )
                }
            }
        }
        return best
    }

    private fun maxAltitudeRecord(
        tracks: List<LoggedTrackEntity>,
        daysByTrackId: Map<String, List<LoggedTrackDayEntity>>,
    ): BilanRecord? = bestDayRecord(tracks, daysByTrackId, BilanRecordKind.MAX_ALTITUDE) { it.maxElevationMeters }

    // RIC-19 §3 : convention "altitude du dernier point du jour N" — seulement pour un jour qui
    // n'est PAS le dernier de sa trace (sinon ce dernier point est la sortie du trek, pas un
    // bivouac ; voir bivouacCount = dayCount - 1 ci-dessus, exactement la même exclusion).
    private fun highestBivouacRecord(
        tracks: List<LoggedTrackEntity>,
        daysByTrackId: Map<String, List<LoggedTrackDayEntity>>,
    ): BilanRecord? {
        val byTrack = tracks.associateBy { it.id }
        var best: BilanRecord? = null
        for ((trackId, days) in daysByTrackId) {
            if (days.size < 2) continue
            val entry = byTrack[trackId] ?: continue
            for (day in days.dropLast(1)) {
                val elevation = day.lastPointElevationMeters ?: continue
                if (best == null || elevation > best.value) {
                    best = BilanRecord(
                        kind = BilanRecordKind.HIGHEST_BIVOUAC,
                        value = elevation,
                        placeName = entry.name,
                        whenMillis = day.startedAtMillis ?: entry.startedAt,
                        trackId = trackId,
                        dayIndex = day.dayIndex,
                    )
                }
            }
        }
        return best
    }

    // --- Records secondaires (RIC-19 §4) -----------------------------------------------------

    // Distance/D+ du jour reconstruits depuis les sommes de segments RIC-109 (flatDistanceMeters +
    // steepDistanceMeters pour la distance, steepGainMeters pour le D+) plutôt qu'à partir de
    // nouvelles colonnes dédiées : elles existent déjà pour toute la banque (calibration), et
    // TrackSegmenter ne perd qu'un reliquat de fin de journée < 100 m (sa propre kdoc) — négligeable
    // à l'affichage en km. Le D+ peut en revanche légèrement sous-compter le D+ des segments classés
    // "plats" (pente nette < 2 %, mais pas rigoureusement nulle) : compromis documenté dans le
    // rapport final plutôt qu'une nouvelle colonne dénormalisée hors du périmètre du ticket.
    private fun maxDistanceDayRecord(
        tracks: List<LoggedTrackEntity>,
        daysByTrackId: Map<String, List<LoggedTrackDayEntity>>,
    ): BilanRecord? = bestDayRecord(tracks, daysByTrackId, BilanRecordKind.MAX_DISTANCE_DAY) { day ->
        val flat = day.flatDistanceMeters ?: return@bestDayRecord null
        val steep = day.steepDistanceMeters ?: return@bestDayRecord null
        (flat + steep) / 1000.0
    }

    private fun maxGainDayRecord(
        tracks: List<LoggedTrackEntity>,
        daysByTrackId: Map<String, List<LoggedTrackDayEntity>>,
    ): BilanRecord? = bestDayRecord(tracks, daysByTrackId, BilanRecordKind.MAX_GAIN_DAY) { it.steepGainMeters }

    private fun longestTrekRecord(
        tracks: List<LoggedTrackEntity>,
        daysByTrackId: Map<String, List<LoggedTrackDayEntity>>,
    ): BilanRecord? =
        tracks.filter { (daysByTrackId[it.id]?.size ?: 0) > 1 }
            .maxByOrNull { daysByTrackId.getValue(it.id).size }
            ?.let { entry ->
                BilanRecord(
                    kind = BilanRecordKind.LONGEST_TREK,
                    value = daysByTrackId.getValue(entry.id).size.toDouble(),
                    placeName = entry.name,
                    whenMillis = entry.startedAt,
                    trackId = entry.id,
                    // Premier jour du trek : point d'entrée naturel pour "le trek le plus long"
                    // (RIC-19 §6 demande explicitement un positionnement day-level ici).
                    dayIndex = 0,
                    extraDistanceKm = entry.distanceMeters / 1000.0,
                    extraGainMeters = entry.elevationGainMeters,
                )
            }

    // Factorise VAM à part (garde-fou spécifique) mais partage ce squelette avec altitude/distance
    // /D+ jour : "meilleur jour selon [selector], selector null = jour ignoré (pas encore rattrapé)".
    private inline fun bestDayRecord(
        tracks: List<LoggedTrackEntity>,
        daysByTrackId: Map<String, List<LoggedTrackDayEntity>>,
        kind: BilanRecordKind,
        selector: (LoggedTrackDayEntity) -> Double?,
    ): BilanRecord? {
        val byTrack = tracks.associateBy { it.id }
        var best: BilanRecord? = null
        for ((trackId, days) in daysByTrackId) {
            val entry = byTrack[trackId] ?: continue
            for (day in days) {
                val value = selector(day) ?: continue
                if (best == null || value > best.value) {
                    best = BilanRecord(
                        kind = kind,
                        value = value,
                        placeName = entry.name,
                        whenMillis = day.startedAtMillis ?: entry.startedAt,
                        trackId = trackId,
                    )
                }
            }
        }
        return best
    }
}

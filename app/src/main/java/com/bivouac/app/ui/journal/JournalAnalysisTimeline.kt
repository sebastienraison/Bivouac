package com.bivouac.app.ui.journal

import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bivouac.app.R
import com.bivouac.app.data.gpx.AnalysisParameters
import com.bivouac.app.data.gpx.DayTimeline
import com.bivouac.app.data.gpx.ShortTimelinePause
import com.bivouac.app.data.gpx.TimelineElement
import com.bivouac.app.data.gpx.TimelinePhase
import com.bivouac.app.data.gpx.TimelinePhaseKind
import com.bivouac.app.data.gpx.TimelinePause
import com.bivouac.app.data.gpx.TrackAnalysisMapMapping
import com.bivouac.app.data.model.Segment
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.ui.components.formatGroupedInt
import com.bivouac.app.ui.components.formatKm1
import com.bivouac.app.ui.map.AnalysisColoring
import com.bivouac.app.ui.map.AnalysisColors
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * RIC-146 lot 8 (conception 2 section 5.4, brief Partie A) : la frise chronologique du mode
 * Analyse, section "Déroulé" entre "Forme du jour" et "Chiffres" (voir JournalScreen.kt). Consomme
 * [DayTimeline] (calculateur du lot 6, [com.bivouac.app.data.gpx.TrackTimelineCalculator], jamais
 * modifié par ce lot) et les mêmes [Segment] par jour que le reste du mode Analyse
 * ([AnalysisReadoutLine] dans JournalAnalysisContent.kt), pour retrouver l'heure d'horloge de
 * chaque élément (le calculateur du lot 6 ne porte que des secondes écoulées depuis le premier
 * point horodaté du jour, jamais l'instant absolu) et l'index écran d'un toucher.
 *
 * `internal` partout, comme JournalAnalysisContent.kt : rien de ce fichier n'est une API publique
 * du module, seul ThreeStopJournalDetail (JournalScreen.kt) monte [AnalysisTimelineSection].
 */

// --- Constantes de mise en page (brief : "regroupées en un seul endroit") ---------------------------

/**
 * Échelle et planchers de la frise, choisis par Seb au banc d'essai du 2026-09-29
 * (`docs/pilotage/ric-146/banc-frise-2026-09-29.html`, version 6, "échelle intermédiaire" parmi
 * plusieurs essais sur les 103 randos de la sauvegarde utilisée pour la conception) : conception 2
 * section 5.4, ligne "Échelle de la frise".
 */
internal object TimelineLayout {
    /** Points (dp) par minute de durée, pour la hauteur d'une phase ou d'une pause. */
    const val MINUTE_HEIGHT_DP = 1.56f

    /** Hauteur minimale d'une phase, même très courte : garde son texte lisible. */
    const val PHASE_MIN_HEIGHT_DP = 48f

    /** Hauteur minimale d'une pause, même très courte : garde son texte lisible. */
    const val PAUSE_MIN_HEIGHT_DP = 30f

    /**
     * Hauteur minimale visible d'une interruption du trait (pause courte à l'intérieur d'une
     * phase) : en dessous, une pause de quelques secondes disparaîtrait complètement à l'écran.
     * Choix de ce lot, pas du banc d'essai (qui ne dessinait pas encore les interruptions).
     */
    const val MIN_INTERRUPTION_HEIGHT_DP = 3f

    /**
     * RIC-212 (brief Partie A, "Hauteur : fixe, hors échelle de la frise") : hauteur du bloc
     * bivouac, entre deux jours. Il ne suit PAS [MINUTE_HEIGHT_DP] comme une phase ou une pause :
     * une nuit de 14h10 y ferait 1 326 dp (14h10 = 850 min × 1,56 dp/min), largement hors écran.
     * Valeur choisie pour ce lot : assez haute pour le pictogramme de 24 dp (même taille que le
     * badge du mode Carnet, JournalScreen.kt) centré verticalement avec un peu d'air, pas plus.
     */
    const val BIVOUAC_ROW_HEIGHT_DP = 40f
}

/** Hauteur d'une phase ou d'une pause à l'échelle de la frise, avec son plancher. */
private fun heightForDurationDp(durationSeconds: Double, minHeightDp: Float): Float =
    maxOf(minHeightDp, (durationSeconds / 60.0).toFloat() * TimelineLayout.MINUTE_HEIGHT_DP)

/** Hauteur d'une [TimelinePhase] (conception 2 section 5.4, ligne "Hauteur d'une phase"). */
internal fun phaseHeightDp(durationSeconds: Double): Float =
    heightForDurationDp(durationSeconds, TimelineLayout.PHASE_MIN_HEIGHT_DP)

/** Hauteur d'une [TimelinePause] (conception 2 section 5.4, ligne "Hauteur d'une pause"). */
internal fun pauseHeightDp(durationSeconds: Double): Float =
    heightForDurationDp(durationSeconds, TimelineLayout.PAUSE_MIN_HEIGHT_DP)

// --- Classe et habillage d'écart d'une phase --------------------------------------------------------

/**
 * Classe d'allure (0 à 4, voir [AnalysisParameters.paceClassOf]) d'une phase à partir de son
 * [TimelinePhase.paceDeltaPercent] ; `null` sans écart exploitable (aucun tronçon retenu de la
 * phase n'avait de vitesse de référence), couleur neutre dans ce cas (brief : "Sans écart : couleur
 * neutre").
 */
internal fun timelinePaceClassFor(paceDeltaPercent: Int?): Int? =
    paceDeltaPercent?.let { AnalysisParameters.DEFAULT.paceClassOf(it.toDouble()) }

/**
 * RIC-146 lot 8 (brief : pastille d'écart) : quel texte porte la pastille d'une phase. Fonction
 * pure, extraite de [TimelinePaceChip] pour être testée directement par
 * TimelinePaceChipWordingTest, même patron que [readoutPaceWordingFor] (JournalAnalysisContent.kt,
 * lot 4) dont elle est volontairement distincte : cette pastille dit "sans référence", la ligne de
 * lecture du profil dit "à l'arrêt" pour la même situation (null), ce ne sont pas la même phrase.
 */
internal enum class TimelinePaceChipWording { USUAL, SLOWER, FASTER, NO_REFERENCE }

/** [paceClass] : voir [timelinePaceClassFor]. Classe 2 = milieu des cinq, "rythme habituel". */
internal fun timelinePaceChipWordingFor(paceClass: Int?): TimelinePaceChipWording = when {
    paceClass == null -> TimelinePaceChipWording.NO_REFERENCE
    paceClass in 0..1 -> TimelinePaceChipWording.SLOWER
    paceClass in 3..4 -> TimelinePaceChipWording.FASTER
    else -> TimelinePaceChipWording.USUAL
}

// --- Dénivelé affiché selon la nature ---------------------------------------------------------------

/** RIC-146 lot 8 (brief : "Dénivelé affiché selon la nature"). */
internal enum class PhaseElevationDisplay { GAIN, LOSS, BOTH }

/** [kind] : nature de la phase. Montée -> D+ seul, descente -> D- seul, vallonné et plat -> les deux. */
internal fun phaseElevationDisplayFor(kind: TimelinePhaseKind): PhaseElevationDisplay = when (kind) {
    TimelinePhaseKind.CLIMB -> PhaseElevationDisplay.GAIN
    TimelinePhaseKind.DESCENT -> PhaseElevationDisplay.LOSS
    TimelinePhaseKind.ROLLING, TimelinePhaseKind.FLAT -> PhaseElevationDisplay.BOTH
}

// --- Interruptions du trait aux pauses courtes --------------------------------------------------------

/** Un segment du trait d'une phase à dessiner en pointillé plutôt qu'en plein (pause courte). */
internal data class TraitInterruption(val startDp: Float, val heightDp: Float)

/**
 * RIC-146 lot 8 (brief : "Trait d'une phase ... interrompu à l'endroit des pauses de moins de
 * 5 minutes, en proportion de leur position et de leur durée dans la phase"). Fonction pure (pas de
 * Compose), testée directement par TimelineTraitInterruptionTest : ne retient que les
 * [ShortTimelinePause] du jour dont le début tombe dans la fenêtre de la [phase] (les pauses
 * d'événement, >= 5 min, ne peuvent jamais tomber dans une phase, règle 1 du calculateur du lot 6 :
 * ce filtre n'exclut donc que des pauses courtes d'une AUTRE phase du même jour).
 */
internal fun traitInterruptionsFor(phase: TimelinePhase, shortPauses: List<ShortTimelinePause>): List<TraitInterruption> {
    val duration = phase.endSeconds - phase.startSeconds
    if (duration <= 0.0) return emptyList()
    val phaseHeight = phaseHeightDp(duration)
    return shortPauses
        .filter { it.startSeconds >= phase.startSeconds && it.startSeconds < phase.endSeconds }
        .map { pause ->
            val startFraction = ((pause.startSeconds - phase.startSeconds) / duration).toFloat().coerceIn(0f, 1f)
            val durationFraction = (pause.pausedSeconds / duration).toFloat().coerceIn(0f, 1f)
            val startDp = startFraction * phaseHeight
            val rawHeightDp = durationFraction * phaseHeight
            val heightDp = rawHeightDp.coerceAtLeast(TimelineLayout.MIN_INTERRUPTION_HEIGHT_DP).coerceAtMost(phaseHeight - startDp)
            TraitInterruption(startDp, heightDp)
        }
}

// --- Titre de jour -----------------------------------------------------------------------------------

/** RIC-146 lot 8 (brief : "Plusieurs jours : chaque jour est précédé de ... ; une rando d'un seul
 * jour n'a pas ce titre"). Fonction pure, testée directement par TimelineDayTitleVisibleTest. */
internal fun dayTitleVisible(dayCount: Int): Boolean = dayCount > 1

/**
 * "Jour 2 · jeudi 2 juillet" / "Day 2 · Thursday, July 2" (conception 2 section 9) : aucune clé de
 * ressource ne porte ce motif jour de semaine + jour + mois (ni "EEEE d" de [formatDayLabel], sans
 * mois, ni [formatStartedAt], sans jour de semaine) ; motif localisé au lieu d'un texte écrit en
 * dur, via l'API Android qui choisit l'ordre et la ponctuation propres à la locale (virgule en
 * anglais, aucune en français) à partir d'un squelette de champs.
 */
internal fun formatTimelineDayDate(instant: Instant): String {
    val locale = Locale.getDefault()
    val pattern = AndroidDateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
    return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault()).format(instant)
}

/**
 * RIC-212 (brief Partie B) : "Jour 1 · mercredi 1 juillet · 12,4 km · 6h10", jamais coupé ni
 * tronqué à 360 points ; s'il passe sur deux lignes, la coupure ne doit tomber qu'entre la date et
 * la distance. [formatted] est déjà le texte localisé complet (les quatre paramètres de
 * journal_analysis_timeline_day déjà substitués) : cette fonction ne fait QUE poser des espaces
 * insécables (U+00A0) pour empêcher les autres points de coupure, elle ne change aucun mot.
 *
 * Choix retenu, le plus simple : le séparateur « · » apparaît trois fois dans le texte formaté (il
 * ne peut apparaître dans aucun des quatre paramètres eux-mêmes : ni un numéro de jour, ni une
 * date, une distance ou une durée ne produit cette séquence espace-point médian-espace) ; découper
 * dessus donne donc toujours exactement les quatre segments dans l'ordre, sans avoir à connaître
 * leur contenu. Seul le dernier séparateur (entre distance et durée) devient insécable ici :
 * l'appelant a déjà rendu la distance et la durée elles-mêmes insécables en interne (nécessaire en
 * anglais, où la durée porte un espace : "5h 32m") avant de les passer en paramètres, donc le
 * bloc "12,4 km · 6h10" est protégé de bout en bout. Repli identitaire si la découpe ne donne pas
 * exactement quatre segments (ne devrait pas arriver) : ne jamais planter sur un format inattendu.
 */
internal fun timelineDayTitleWithLineBreakHints(formatted: String): String {
    val parts = formatted.split(" · ")
    if (parts.size != 4) return formatted
    return "${parts[0]} · ${parts[1]} · ${parts[2]} · ${parts[3]}"
}

// --- Nuit de bivouac entre deux jours -----------------------------------------------------------------

/**
 * RIC-212 (brief Partie A, "Durée affichée", règle des 24 h substituée le 2026-09-29 par le fil de
 * pilotage à la "règle du lendemain" d'origine, note en tête de fonction) : durée de la nuit
 * affichée au bloc bivouac entre les jours N et N+1, écart entre le dernier point horodaté du jour
 * N ([arrival]) et le premier point horodaté du jour N+1 ([departure]). Fonction pure (aucune
 * dépendance Compose, aucun fuseau : la comparaison ne porte que sur l'écart entre les deux
 * instants, jamais sur une date locale), testée directement par TimelineBivouacTest.
 *
 * `null` dans tous les cas où le brief demande "le logo seul, sans texte" : un horodatage manquant
 * d'un côté ou de l'autre, un écart nul ou négatif, ou un écart de 24 h ou plus (raison du
 * changement de règle, pilotage du 2026-09-29 : une arrivée après minuit suivie d'un départ le
 * matin même doit afficher sa durée, ce que l'ancienne comparaison de dates locales empêchait).
 */
internal fun bivouacNightDurationSeconds(arrival: Instant?, departure: Instant?): Double? {
    if (arrival == null || departure == null) return null
    val seconds = Duration.between(arrival, departure).seconds.toDouble()
    return if (seconds > 0.0 && seconds < 24 * 3_600.0) seconds else null
}

/**
 * RIC-212 (brief Partie A, "Toucher") : index LOCAL au jour (dans [points], convention
 * [Segment.points]) du dernier point horodaté du jour, celui que place le repère (carte et profil)
 * au toucher du bloc bivouac qui le suit dans la frise, le même point que celui déjà affiché par
 * la ligne "Arrivée" au-dessus de ce bloc. `null` si aucun point du jour n'a d'horodatage :
 * n'arrive pas pour un jour affiché par la frise (l'invariant de
 * [com.bivouac.app.data.gpx.TrackTimelineCalculator.computeDayTimeline] exige déjà deux points
 * horodatés pour produire des [TimelineElement]), conservé ici par prudence plutôt que pour un cas
 * réel, même esprit que ce calculateur.
 */
internal fun bivouacMarkerLocalIndex(points: List<TrackPoint>): Int? =
    points.indices.lastOrNull { points[it].time != null }

/**
 * RIC-212 (brief Partie A, "Rando d'un seul jour : aucun bloc") : nombre de blocs bivouac de la
 * frise, un entre chaque paire de jours consécutifs AFFICHÉS (le filtre `elements.isNotEmpty()` de
 * [AnalysisTimelineSection], jamais le nombre brut de [DayTimeline] du jeu de données). Fonction
 * pure, testée directement par TimelineBivouacTest, même patron que [dayTitleVisible].
 */
internal fun bivouacBlockCount(displayedDayCount: Int): Int = maxOf(0, displayedDayCount - 1)

// --- Mise en page ------------------------------------------------------------------------------------

// RIC-146 lot 8, défaut trouvé en vérification visuelle à 360 points en anglais : 44 dp coupait
// "10:49 AM" sur deux lignes ("10:49 A" / "M"), l'heure système américaine étant plus large que le
// "10:49" français (24 h, sans AM/PM). Élargi à 56 dp, avec maxLines = 1 sur les trois usages en
// filet de sécurité (voir TimelineMarkerRow, TimelinePhaseRow, TimelinePauseRow).
private val TimeColumnWidth = 56.dp
private val DotColumnWidth = 24.dp
private val DotSize = 18.dp
private val MarkerDotSize = 8.dp
private val TraitWidth = 3.dp

private fun phaseIcon(kind: TimelinePhaseKind): ImageVector = when (kind) {
    TimelinePhaseKind.CLIMB -> Icons.AutoMirrored.Filled.TrendingUp
    TimelinePhaseKind.DESCENT -> Icons.AutoMirrored.Filled.TrendingDown
    TimelinePhaseKind.ROLLING -> Icons.Filled.Waves
    TimelinePhaseKind.FLAT -> Icons.AutoMirrored.Filled.TrendingFlat
}

private fun phaseNatureRes(kind: TimelinePhaseKind): Int = when (kind) {
    TimelinePhaseKind.CLIMB -> R.string.journal_analysis_phase_climb
    TimelinePhaseKind.DESCENT -> R.string.journal_analysis_phase_descent
    TimelinePhaseKind.ROLLING -> R.string.journal_analysis_phase_rolling
    TimelinePhaseKind.FLAT -> R.string.journal_analysis_phase_flat
}

@Composable
private fun formattedElevation(meters: Double): String =
    stringResource(R.string.format_elevation_meters, formatGroupedInt(meters.roundToInt()))

@Composable
private fun phaseTitle(phase: TimelinePhase): String {
    val distanceText = stringResource(R.string.format_distance_km, formatKm1(phase.distanceMeters / 1_000.0))
    val elevationText = when (phaseElevationDisplayFor(phase.kind)) {
        PhaseElevationDisplay.GAIN -> stringResource(R.string.journal_analysis_phase_gain, formattedElevation(phase.elevationGainMeters))
        PhaseElevationDisplay.LOSS -> stringResource(R.string.journal_analysis_phase_loss, formattedElevation(phase.elevationLossMeters))
        PhaseElevationDisplay.BOTH -> stringResource(
            R.string.journal_analysis_phase_gain_loss,
            formattedElevation(phase.elevationGainMeters),
            formattedElevation(phase.elevationLossMeters),
        )
    }
    return stringResource(R.string.journal_analysis_phase_title, stringResource(phaseNatureRes(phase.kind)), distanceText, elevationText)
}

@Composable
private fun phaseFigures(phase: TimelinePhase): String {
    val movingText = formatPauseDuration(phase.movingSeconds)
    val speedText = phase.movingSpeedKmh?.let { stringResource(R.string.settings_speed_value_format, formatKm1(it)) } ?: "-"
    return stringResource(R.string.journal_analysis_phase_figures, movingText, speedText)
}

@Composable
private fun TimelinePaceChip(paceClass: Int?, paceDeltaPercent: Int?, color: Color, modifier: Modifier = Modifier) {
    val text = when (timelinePaceChipWordingFor(paceClass)) {
        // paceDeltaPercent est non nul dès que paceClass l'est (timelinePaceClassFor) : la classe
        // vient uniquement de lui.
        TimelinePaceChipWording.USUAL -> stringResource(R.string.journal_analysis_readout_usual)
        TimelinePaceChipWording.SLOWER -> stringResource(
            R.string.journal_analysis_chip_slower,
            stringResource(R.string.settings_pause_percent_value, abs(paceDeltaPercent!!)),
        )
        TimelinePaceChipWording.FASTER -> stringResource(
            R.string.journal_analysis_chip_faster,
            stringResource(R.string.settings_pause_percent_value, abs(paceDeltaPercent!!)),
        )
        TimelinePaceChipWording.NO_REFERENCE -> stringResource(R.string.journal_analysis_chip_no_reference)
    }
    // RIC-146 lot 8 (vérification visuelle contre frise-hybride-reference.png) : fond plein à la
    // couleur de la classe, texte sombre par-dessus (comme la référence), plutôt qu'un fond à
    // faible opacité avec un texte de la couleur elle-même (contraste trop faible en pratique).
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = Color.Black.copy(alpha = 0.87f))
    }
}

/** Le rond + trait d'une phase : trait plein à la couleur de l'écart, coupé net (vide, pas de
 * trait du tout) aux pauses courtes qu'elle contient (voir [traitInterruptionsFor]) : même rendu
 * que frise-hybride-reference.png, où l'interruption est un blanc, jamais un pointillé. */
@Composable
private fun PhaseDotAndTrait(kind: TimelinePhaseKind, color: Color, heightDp: Float, interruptions: List<TraitInterruption>, modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(DotColumnWidth).height(heightDp.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val dotRadiusPx = DotSize.toPx() / 2
            val heightPx = size.height
            var cursor = dotRadiusPx
            for (interruption in interruptions.sortedBy { it.startDp }) {
                val startPx = interruption.startDp.dp.toPx().coerceIn(cursor, heightPx)
                if (startPx > cursor) {
                    drawLine(color, Offset(centerX, cursor), Offset(centerX, startPx), strokeWidth = TraitWidth.toPx())
                }
                // Rien à dessiner ici : l'interruption est un vide (voir la kdoc de la fonction).
                cursor = (startPx + interruption.heightDp.dp.toPx()).coerceIn(startPx, heightPx)
            }
            if (cursor < heightPx) {
                drawLine(color, Offset(centerX, cursor), Offset(centerX, heightPx), strokeWidth = TraitWidth.toPx())
            }
        }
        Box(
            modifier = Modifier.align(Alignment.TopCenter).size(DotSize).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(phaseIcon(kind), contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelinePhaseRow(phase: TimelinePhase, shortPauses: List<ShortTimelinePause>, clockTime: Instant, onClick: () -> Unit) {
    val paceClass = timelinePaceClassFor(phase.paceDeltaPercent)
    val color = AnalysisColors.colorFor(AnalysisColoring.PACE, paceClass)
    val heightDp = phaseHeightDp(phase.endSeconds - phase.startSeconds)
    val interruptions = remember(phase, shortPauses) { traitInterruptionsFor(phase, shortPauses) }
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            text = formatTimeOfDay(clockTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(TimeColumnWidth).padding(top = 2.dp),
        )
        PhaseDotAndTrait(kind = phase.kind, color = color, heightDp = heightDp, interruptions = interruptions)
        Column(modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) {
            Text(text = phaseTitle(phase), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = phaseFigures(phase),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                TimelinePaceChip(paceClass = paceClass, paceDeltaPercent = phase.paceDeltaPercent, color = color, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
    }
}

/** Hachures diagonales, motif réutilisé nulle part ailleurs dans le module (aucun n'existait déjà,
 * voir le rapport du lot) : lignes à 45°, espacées régulièrement, débordant volontairement de la
 * largeur pour couvrir tout le rectangle une fois [clipToBounds] appliqué par l'appelant. */
private fun DrawScope.drawHachures(color: Color) {
    val spacing = 7.dp.toPx()
    val strokeWidth = 1.4.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = strokeWidth)
        x += spacing
    }
}

@Composable
private fun TimelinePauseRow(pause: TimelinePause, clockTime: Instant, onClick: () -> Unit) {
    val heightDp = pauseHeightDp(pause.endSeconds - pause.startSeconds)
    val hachureColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(modifier = Modifier.fillMaxWidth().height(heightDp.dp).clickable(onClick = onClick)) {
        Text(
            text = formatTimeOfDay(clockTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(TimeColumnWidth).padding(top = 2.dp),
        )
        // Hachurée sur toute la largeur du trait ET du texte (brief), donc les deux colonnes
        // restantes réunies dans un seul fond, rond et texte dessinés par-dessus.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
                .background(trackColor)
                .drawBehind { drawHachures(hachureColor) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxHeight()) {
                Box(modifier = Modifier.width(DotColumnWidth), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(DotSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(11.dp))
                    }
                }
                Text(
                    text = stringResource(R.string.journal_analysis_timeline_break, formatPauseDuration(pause.pausedSeconds), formattedElevation(pause.startElevationMeters)),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TimelineMarkerRow(time: Instant, elevationMeters: Double, labelRes: Int) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatTimeOfDay(time),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(TimeColumnWidth),
        )
        Box(modifier = Modifier.width(DotColumnWidth), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(MarkerDotSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        Text(
            text = stringResource(labelRes, formattedElevation(elevationMeters)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShortPausesNote(shortPauses: List<ShortTimelinePause>) {
    val count = shortPauses.size
    val totalDurationText = formatPauseDuration(shortPauses.sumOf { it.pausedSeconds })
    Text(
        text = pluralStringResource(R.plurals.journal_analysis_timeline_short_breaks, count, count, totalDurationText),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = TimeColumnWidth + DotColumnWidth, top = 4.dp),
    )
}

/**
 * RIC-212 (brief Partie A) : le bloc bivouac entre deux jours affichés. Rond de la colonne du
 * milieu (pas de trait, pas de hachures : distinct d'une pause, brief "sobre, aéré") : le logo du
 * mode Carnet (JournalScreen.kt, ReadOnlyBivouacRow), avec sa teinte habituelle (pas d'Icon avec
 * tint : un Image, comme là-bas, le vectoriel porte déjà ses propres couleurs). Colonne de gauche
 * vide (brief : "pas d'heure, la ligne Arrivée la donne déjà"). [nightDurationSeconds] : `null` ->
 * logo seul (voir [bivouacNightDurationSeconds]), sinon le texte "%1$s au bivouac" à droite, au
 * format de durée existant ([formatPauseDuration]).
 */
@Composable
private fun TimelineBivouacRow(nightDurationSeconds: Double?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TimelineLayout.BIVOUAC_ROW_HEIGHT_DP.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(TimeColumnWidth))
        Box(modifier = Modifier.width(DotColumnWidth), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.ic_bivouac_badge),
                contentDescription = stringResource(R.string.journal_detail_bivouac_night_description),
                modifier = Modifier.size(24.dp),
            )
        }
        if (nightDurationSeconds != null) {
            Text(
                text = stringResource(R.string.journal_analysis_timeline_bivouac, formatPauseDuration(nightDurationSeconds)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** Un jour de la frise : départ, éléments (phases et pauses) dans l'ordre, arrivée. */
@Composable
private fun DayTimelineView(day: DayTimeline, dayStartInstant: Instant, dayIndex: Int, dayOffsets: List<Int>, onElementSelected: (Int) -> Unit) {
    Column {
        TimelineMarkerRow(time = dayStartInstant, elevationMeters = day.startElevationMeters, labelRes = R.string.journal_analysis_timeline_start)
        for (element: TimelineElement in day.elements) {
            when (element) {
                is TimelinePhase -> TimelinePhaseRow(
                    phase = element,
                    shortPauses = day.shortPauses,
                    clockTime = dayStartInstant.plusMillis((element.startSeconds * 1_000).toLong()),
                    onClick = { onElementSelected(TrackAnalysisMapMapping.toScreenIndex(dayOffsets, dayIndex, element.startIndex)) },
                )
                is TimelinePause -> TimelinePauseRow(
                    pause = element,
                    clockTime = dayStartInstant.plusMillis((element.startSeconds * 1_000).toLong()),
                    onClick = { onElementSelected(TrackAnalysisMapMapping.toScreenIndex(dayOffsets, dayIndex, element.startIndex)) },
                )
            }
        }
        TimelineMarkerRow(
            time = dayStartInstant.plusMillis((day.elapsedSeconds * 1_000).toLong()),
            elevationMeters = day.endElevationMeters,
            labelRes = R.string.journal_analysis_timeline_finish,
        )
        if (day.shortPauses.isNotEmpty()) {
            ShortPausesNote(day.shortPauses)
        }
    }
}

/**
 * RIC-146 lot 8 (brief Partie A) : section "Déroulé", une frise par jour. [days] :
 * [com.bivouac.app.journal.JournalAnalysisResult.timeline]`.days`, l'appelant ne la monte pas du
 * tout si `null` (trace sans horodatage, brief note finale). [daySegments] : mêmes jours, même
 * ordre, que ceux passés à [com.bivouac.app.data.gpx.TrackTimelineCalculator.compute]
 * (`JournalViewModel.computeAnalysis`, `pointsByDay = detail.daySegments.map { it.points }`) :
 * seule source de l'instant absolu de départ de chaque jour, que [DayTimeline] ne porte pas
 * (il ne porte que des secondes écoulées depuis ce premier point horodaté).
 * [onElementSelected] : index dans la trace concaténée de l'écran (même convention que le
 * `cursorIndex` de JournalScreen.kt), appelé au toucher d'une phase ou d'une pause, brief "place le
 * repère à son début, sur la carte et sur le profil" (même callback que le glissement sur le profil).
 *
 * RIC-212 (brief Partie A) : entre deux jours AFFICHÉS consécutifs (voir [displayedDayIndices]
 * ci-dessous, jamais deux [dayIndex] simplement consécutifs : un jour sans le moindre point
 * horodaté, cas de bord que l'invariant du calculateur ne produit pas en pratique, ne serait de
 * toute façon pas rendu), un [TimelineBivouacRow] : sa durée vient de
 * [bivouacNightDurationSeconds] entre le dernier point horodaté du jour qui s'achève et le premier
 * du jour suivant, son toucher place le repère sur ce dernier point ([bivouacMarkerLocalIndex]).
 */
@Composable
internal fun AnalysisTimelineSection(days: List<DayTimeline>, daySegments: List<Segment>, onElementSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    val dayOffsets = remember(daySegments) { TrackAnalysisMapMapping.dayOffsets(daySegments.map { it.points.size }) }
    val multiDay = dayTitleVisible(days.size)
    // RIC-212 : les seuls jours réellement rendus (voir day.elements.isNotEmpty() plus bas) ; sert
    // à retrouver, pour un jour donné, le jour affiché SUIVANT (brief bivouacBlockCount : "chaque
    // paire de jours consécutifs affichés").
    val displayedDayIndices = remember(days) { days.indices.filter { days[it].elements.isNotEmpty() } }
    Column(modifier = modifier) {
        SectionTitle(stringResource(R.string.journal_analysis_section_timeline))
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            displayedDayIndices.forEachIndexed { position, dayIndex ->
                val day = days[dayIndex]
                // Non-null : day.elements non vide implique qu'au moins un point horodaté existe
                // pour ce jour (même invariant que TrackTimelineCalculator.computeDayTimeline,
                // qui rend un DayTimeline vide sinon), et daySegments[dayIndex].points est
                // exactement la liste passée en entrée de ce calcul (voir kdoc ci-dessus).
                val dayStartInstant = daySegments[dayIndex].points.first { it.time != null }.time!!
                Column {
                    if (multiDay) {
                        val distanceText = stringResource(R.string.format_distance_km, formatKm1(day.distanceMeters / 1_000.0))
                        val durationText = formatPauseDuration(day.elapsedSeconds)
                        // RIC-212 (brief Partie B) : espaces insécables du côté des paramètres pour
                        // qu'aucune coupure de ligne ne tombe DANS la distance ni DANS la durée
                        // (l'anglais porte un espace, "5h 32m") ; timelineDayTitleWithLineBreakHints
                        // s'occupe ensuite du séparateur ENTRE elles.
                        val titleText = timelineDayTitleWithLineBreakHints(
                            stringResource(
                                R.string.journal_analysis_timeline_day,
                                (dayIndex + 1).toString(),
                                formatTimelineDayDate(dayStartInstant),
                                distanceText.replace(' ', ' '),
                                durationText.replace(' ', ' '),
                            ),
                        )
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    DayTimelineView(
                        day = day,
                        dayStartInstant = dayStartInstant,
                        dayIndex = dayIndex,
                        dayOffsets = dayOffsets,
                        onElementSelected = onElementSelected,
                    )
                }
                val nextDayIndex = displayedDayIndices.getOrNull(position + 1)
                if (nextDayIndex != null) {
                    val arrival = daySegments[dayIndex].points.lastOrNull { it.time != null }?.time
                    val departure = daySegments[nextDayIndex].points.firstOrNull { it.time != null }?.time
                    val nightDurationSeconds = bivouacNightDurationSeconds(arrival, departure)
                    val markerLocalIndex = bivouacMarkerLocalIndex(daySegments[dayIndex].points)
                    TimelineBivouacRow(
                        nightDurationSeconds = nightDurationSeconds,
                        onClick = {
                            markerLocalIndex?.let {
                                onElementSelected(TrackAnalysisMapMapping.toScreenIndex(dayOffsets, dayIndex, it))
                            }
                        },
                    )
                }
            }
        }
    }
}

package com.bivouac.app.ui.journal

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bivouac.app.R
import com.bivouac.app.journal.BivouacQuestion
import com.bivouac.app.ui.components.appLocale
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** RIC-227 : l'arrêt et la reprise d'un bivouac, mis en forme pour `bivouac_detected_body`. */
internal data class BivouacDateTimes(val stop: String, val resume: String)

/**
 * RIC-227 (textes validés par Seb le 15/10) : « mardi 12 août à 20 h 30 » / « Tuesday 12 August at
 * 20:30 », dans la [locale] des dates de l'app (AppLocale, RIC-245) et le fuseau du bivouac ([zone], celui retenu par la
 * détection, pas celui de l'appareil). [pattern] et [patternWithYear] sont les motifs de
 * l'inventaire (`bivouac_detected_datetime_pattern` et `..._with_year`) : le second sert aux deux
 * instants quand ils ne tombent pas la même année dans [zone]. Fonction pure, testée par
 * BivouacDetectedDialogFormatTest.
 *
 * RIC-235 : le premier du mois s'écrit [firstDayOfMonth] (`journal_trek_dates_first_day_of_month`,
 * « 1er » en français, « 1 » en anglais), comme dans la ligne de dates du Journal : « mercredi
 * 1er avril à 6 h 40 ». Le jour devient alors un littéral quoté du motif
 * ([patternWithDayOfMonthLiteral]) plutôt qu'une substitution dans le texte rendu : le texte porte
 * d'autres « 1 » (l'heure, « 1 h 01 », les minutes, l'année), et seul le motif dit lequel est le jour.
 */
internal fun formatBivouacDateTimes(
    stopAt: Instant,
    resumeAt: Instant,
    zone: ZoneId,
    locale: Locale,
    pattern: String,
    patternWithYear: String,
    firstDayOfMonth: String,
): BivouacDateTimes {
    val sameYear = stopAt.atZone(zone).year == resumeAt.atZone(zone).year
    val base = if (sameYear) pattern else patternWithYear
    fun format(instant: Instant): String {
        val effective = if (instant.atZone(zone).dayOfMonth == 1) patternWithDayOfMonthLiteral(base, firstDayOfMonth) else base
        return DateTimeFormatter.ofPattern(effective, locale).withZone(zone).format(instant)
    }
    return BivouacDateTimes(format(stopAt), format(resumeAt))
}

/**
 * RIC-235 (validé par Seb le 22/10) : durée du trou dans le corps du dialogue, alignée sur le motif
 * des instants : « 19 h 02 » en français, « 19h 02m » en anglais (`bivouac_detected_gap_hours_minutes`),
 * au lieu du format compact de la frise (« 19h02 », formatPauseDuration, qui ne change pas). Sous
 * une heure, « 59 min » (`journal_analysis_pause_minutes`). Arrondi à la minute comme
 * formatPauseDuration ; les minutes sur deux chiffres sont posées ici et non par la ressource, comme
 * pour `fmt_stats_rows_duration`. Testée par BivouacGapDurationFormatTest.
 */
internal fun formatBivouacGapDuration(seconds: Double, context: Context): String {
    val totalMinutes = (seconds / 60.0).roundToInt()
    return if (totalMinutes < 60) {
        context.getString(R.string.journal_analysis_pause_minutes, totalMinutes.toString())
    } else {
        context.getString(
            R.string.bivouac_detected_gap_hours_minutes,
            totalMinutes / 60,
            (totalMinutes % 60).toString().padStart(2, '0'),
        )
    }
}

/**
 * RIC-235 : [pattern] (motif DateTimeFormatter) dont chaque champ jour du mois `d` (une lettre, hors
 * texte quoté) est remplacé par [literal], quoté. Les autres lettres, `dd` compris, et le texte
 * déjà quoté (`'à'`, `'h'`, avec ses apostrophes doublées) sont recopiés tels quels.
 */
internal fun patternWithDayOfMonthLiteral(pattern: String, literal: String): String {
    val quoted = "'" + literal.replace("'", "''") + "'"
    val out = StringBuilder()
    var inQuote = false
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '\'' && pattern.getOrNull(i + 1) == '\'' -> {
                out.append("''")
                i += 2
            }
            c == '\'' -> {
                inQuote = !inQuote
                out.append(c)
                i++
            }
            !inQuote && c == 'd' -> {
                var end = i
                while (end < pattern.length && pattern[end] == 'd') end++
                if (end - i == 1) out.append(quoted) else out.append(pattern, i, end)
                i = end
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

/**
 * RIC-227 (conception RIC-222 §3.1 et §3.2) : la question posée pour chaque bivouac détecté, à
 * l'import, au crible du premier lancement et par l'action de menu : un seul composant pour les
 * trois. Titre avec le nom de la rando quand [BivouacQuestion.trackName] est connu (crible du
 * premier lancement), compteur au-dessus du corps quand il y a plus d'une question.
 *
 * Quitter le dialogue autrement que par un bouton (retour, appui hors du dialogue) vaut « Pause »
 * ([onPause], RIC-229) ; aucun délai, aucune réponse par défaut tant qu'il est ouvert. La durée
 * du trou passe par [formatBivouacGapDuration] (RIC-235), alignée sur le motif des instants.
 */
@Composable
internal fun BivouacDetectedDialog(question: BivouacQuestion, onSplit: () -> Unit, onPause: () -> Unit) {
    val bivouac = question.bivouac
    val context = LocalContext.current
    val dateTimes = formatBivouacDateTimes(
        stopAt = bivouac.stopAt,
        resumeAt = bivouac.resumeAt,
        zone = bivouac.zone,
        // RIC-245 : la locale des dates de l'app (AppLocale), lue par sa variante composable,
        // observable (lint NonObservableLocale) : sur un appareil allemand, la phrase est anglaise
        // et ses jours et mois aussi.
        locale = appLocale(),
        pattern = stringResource(R.string.bivouac_detected_datetime_pattern),
        patternWithYear = stringResource(R.string.bivouac_detected_datetime_pattern_with_year),
        firstDayOfMonth = stringResource(R.string.journal_trek_dates_first_day_of_month),
    )
    val gapSeconds = Duration.between(bivouac.stopAt, bivouac.resumeAt).seconds.toDouble()
    AlertDialog(
        onDismissRequest = onPause,
        title = {
            Text(
                question.trackName?.let { stringResource(R.string.bivouac_detected_title_in_track, it) }
                    ?: stringResource(R.string.bivouac_detected_title),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (question.total > 1) {
                    Text(
                        text = stringResource(R.string.bivouac_detected_counter, question.number, question.total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.bivouac_detected_body, dateTimes.stop, dateTimes.resume, formatBivouacGapDuration(gapSeconds, context)))
                Text(stringResource(R.string.bivouac_detected_question))
            }
        },
        confirmButton = {
            TextButton(onClick = onSplit) { Text(stringResource(R.string.bivouac_detected_split)) }
        },
        dismissButton = {
            TextButton(onClick = onPause) { Text(stringResource(R.string.bivouac_detected_keep)) }
        },
    )
}

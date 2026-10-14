package com.bivouac.app.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bivouac.app.R
import com.bivouac.app.journal.BivouacQuestion
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** RIC-227 : l'arrêt et la reprise d'un bivouac, mis en forme pour `bivouac_detected_body`. */
internal data class BivouacDateTimes(val stop: String, val resume: String)

/**
 * RIC-227 (textes validés par Seb le 15/10) : « mardi 12 août à 20 h 30 » / « Tuesday 12 August at
 * 20:30 », dans la [locale] de l'appareil et le fuseau du bivouac ([zone], celui retenu par la
 * détection, pas celui de l'appareil). [pattern] et [patternWithYear] sont les motifs de
 * l'inventaire (`bivouac_detected_datetime_pattern` et `..._with_year`) : le second sert aux deux
 * instants quand ils ne tombent pas la même année dans [zone]. Fonction pure, testée par
 * BivouacDetectedDialogFormatTest.
 */
internal fun formatBivouacDateTimes(
    stopAt: Instant,
    resumeAt: Instant,
    zone: ZoneId,
    locale: Locale,
    pattern: String,
    patternWithYear: String,
): BivouacDateTimes {
    val sameYear = stopAt.atZone(zone).year == resumeAt.atZone(zone).year
    val formatter = DateTimeFormatter.ofPattern(if (sameYear) pattern else patternWithYear, locale).withZone(zone)
    return BivouacDateTimes(formatter.format(stopAt), formatter.format(resumeAt))
}

/**
 * RIC-227 (conception RIC-222 §3.1 et §3.2) : la question posée pour chaque bivouac détecté, à
 * l'import, au crible du premier lancement et par l'action de menu : un seul composant pour les
 * trois. Titre avec le nom de la rando quand [BivouacQuestion.trackName] est connu (crible du
 * premier lancement), compteur au-dessus du corps quand il y a plus d'une question.
 *
 * Quitter le dialogue autrement que par un bouton (retour, appui hors du dialogue) vaut « Ne pas
 * découper » ([onKeep]) ; aucun délai, aucune réponse par défaut tant qu'il est ouvert. La durée
 * du trou passe par [formatPauseDuration], le formateur de « 13h10 au bivouac » de la frise.
 */
@Composable
internal fun BivouacDetectedDialog(question: BivouacQuestion, onSplit: () -> Unit, onKeep: () -> Unit) {
    val bivouac = question.bivouac
    val dateTimes = formatBivouacDateTimes(
        stopAt = bivouac.stopAt,
        resumeAt = bivouac.resumeAt,
        zone = bivouac.zone,
        // Locale observable de Compose (lint NonObservableLocale), même choix que SettingsScreen.
        locale = LocalLocale.current.platformLocale,
        pattern = stringResource(R.string.bivouac_detected_datetime_pattern),
        patternWithYear = stringResource(R.string.bivouac_detected_datetime_pattern_with_year),
    )
    val gapSeconds = Duration.between(bivouac.stopAt, bivouac.resumeAt).seconds.toDouble()
    AlertDialog(
        onDismissRequest = onKeep,
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
                Text(stringResource(R.string.bivouac_detected_body, dateTimes.stop, dateTimes.resume, formatPauseDuration(gapSeconds)))
                Text(stringResource(R.string.bivouac_detected_question))
            }
        },
        confirmButton = {
            TextButton(onClick = onSplit) { Text(stringResource(R.string.bivouac_detected_split)) }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.bivouac_detected_keep)) }
        },
    )
}

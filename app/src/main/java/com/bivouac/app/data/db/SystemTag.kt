package com.bivouac.app.data.db

import androidx.annotation.StringRes
import com.bivouac.app.R

// Fixed, stable values: solo/groupe are meant to later feed the personalized speed model
// (calibration filtered by tag), so they can't be free text.
//
// RIC-145 : le tag système EXTREME ("extreme" / "Extrême (danger)") a été retiré. Aucune migration
// n'accompagne ce retrait : les lignes logged_track_tag qui portent encore la valeur "extreme"
// restent en base et s'affichent désormais comme un tag libre ordinaire (couleur de la palette
// libre, libellé brut, suppressible à la main depuis l'édition d'une sortie).
//
// RIC-191 (lot 4 i18n) : le libellé devient un identifiant de ressource, résolu à l'affichage.
// AUCUNE MIGRATION N'EST NÉCESSAIRE et aucune n'est faite : ce qui est écrit dans logged_track_tag
// a toujours été [value] ("solo", "groupe"), jamais le libellé. C'est la même chaîne qui sert de
// clé de comparaison partout (JournalViewModel, JournalScreen, tagColor), et elle ne bouge pas :
// une base écrite avant ce lot se relit à l'identique après, et réciproquement. Traduire [value]
// serait au contraire une régression : les lignes existantes deviendraient des tags libres.
enum class SystemTag(val value: String, @StringRes val labelRes: Int) {
    SOLO("solo", R.string.tag_system_solo),
    GROUPE("groupe", R.string.tag_system_groupe),
}

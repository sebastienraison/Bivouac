package com.bivouac.app.data.db

// Fixed, stable values: solo/groupe are meant to later feed the personalized speed model
// (calibration filtered by tag), so they can't be free text.
//
// RIC-145 : le tag système EXTREME ("extreme" / "Extrême (danger)") a été retiré. Aucune migration
// n'accompagne ce retrait : les lignes logged_track_tag qui portent encore la valeur "extreme"
// restent en base et s'affichent désormais comme un tag libre ordinaire (couleur de la palette
// libre, libellé brut, suppressible à la main depuis l'édition d'une sortie).
enum class SystemTag(val value: String, val label: String) {
    SOLO("solo", "Solo"),
    GROUPE("groupe", "Groupe"),
}

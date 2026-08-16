package com.bivouac.app.data.db

// Fixed, stable values — solo/groupe are meant to later feed the personalized speed model
// (calibration filtered by tag), so they can't be free text. "Extrême" means dangerous, not
// difficult (explicit user distinction).
enum class SystemTag(val value: String, val label: String) {
    SOLO("solo", "Solo"),
    GROUPE("groupe", "Groupe"),
    EXTREME("extreme", "Extrême (danger)"),
}

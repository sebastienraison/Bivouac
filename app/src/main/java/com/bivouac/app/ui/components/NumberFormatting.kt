package com.bivouac.app.ui.components

import java.text.NumberFormat
import java.util.Locale

// RIC-136 : séparateur de milliers (espace fine insécable en français, virgule en anglais, via
// NumberFormat), à partager par tout composant affichant un nombre susceptible d'atteindre 4
// chiffres (D+/D- cumulés, altitudes en montagne...), plutôt que de le réinventer localement comme
// StatsRows le faisait jusqu'ici avec un simple .toInt() interpolé. Attention :
// String.format(locale, "%.1f", ...) change bien le séparateur décimal mais N'AJOUTE PAS le
// séparateur de milliers, contrairement à NumberFormat : piège identifié en corrigeant RIC-136, à
// ne pas réintroduire.
//
// RIC-187 (lot 0 i18n) : Locale.FRANCE figé remplacé par Locale.getDefault(), reprise partout dans
// ce fichier comme dans le reste du chantier (voir aussi TotalsCapsule.kt, ElevationProfile.kt) :
// virgule décimale et séparateur de milliers suivent désormais la locale de l'appareil.

private val KM_FORMAT: NumberFormat
    get() = NumberFormat.getNumberInstance(Locale.getDefault()).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }

fun formatKm1(km: Double): String = KM_FORMAT.format(km)

fun formatGroupedInt(value: Double): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value.toLong())

fun formatGroupedInt(value: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

// RIC-192 : surcharge Long, pour les volumétries en octets de la sauvegarde. Passer par la
// surcharge Double reviendrait à écrire .toDouble() sur le site d'appel, sans rien y gagner.
fun formatGroupedInt(value: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

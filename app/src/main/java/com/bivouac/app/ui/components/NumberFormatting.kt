package com.bivouac.app.ui.components

import java.text.NumberFormat
import java.util.Locale

// RIC-136 : séparateur de milliers façon française (espace fine insécable, via NumberFormat) —
// à partager par tout composant affichant un nombre susceptible d'atteindre 4 chiffres (D+/D-
// cumulés, altitudes en montagne...), plutôt que de le réinventer localement comme StatsRows le
// faisait jusqu'ici avec un simple .toInt() interpolé. Attention : String.format(Locale.FRANCE,
// "%.1f", ...) change bien la virgule décimale mais N'AJOUTE PAS le séparateur de milliers,
// contrairement à NumberFormat — piège identifié en corrigeant RIC-136, à ne pas réintroduire.

private val KM_FORMAT: NumberFormat
    get() = NumberFormat.getNumberInstance(Locale.FRANCE).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }

fun formatKm1(km: Double): String = KM_FORMAT.format(km)

fun formatGroupedInt(value: Double): String = NumberFormat.getIntegerInstance(Locale.FRANCE).format(value.toLong())

fun formatGroupedInt(value: Int): String = NumberFormat.getIntegerInstance(Locale.FRANCE).format(value)

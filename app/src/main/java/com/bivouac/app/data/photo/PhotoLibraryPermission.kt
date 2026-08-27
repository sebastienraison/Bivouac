package com.bivouac.app.data.photo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

// RIC-43 : permission galerie pour la recherche par plage de dates (opt-in, voir Reglages) -
// jamais demandee pour la selection generique via le Photo Picker, qui n'en a besoin d'aucune.
object PhotoLibraryPermission {

    // La permission a demander depend de la version : READ_MEDIA_IMAGES depuis l'API 33
    // (READ_EXTERNAL_STORAGE y est silencieusement sans effet), READ_EXTERNAL_STORAGE en dessous
    // (minSdk 26).
    val manifestPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    // Accordee au sens large : acces complet (READ_MEDIA_IMAGES/READ_EXTERNAL_STORAGE), ou acces
    // partiel Android 14+ (READ_MEDIA_VISUAL_USER_SELECTED, "Selectionner des photos") - dans les
    // deux cas MediaStore repond, avec un perimetre juste plus restreint pour le second. Un refus
    // complet est le seul cas qui doit declencher le repli silencieux vers le Photo Picker
    // generique (voir la spec RIC-43).
    fun isGranted(context: Context): Boolean {
        if (granted(context, manifestPermission)) return true
        return Build.VERSION.SDK_INT >= 34 &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

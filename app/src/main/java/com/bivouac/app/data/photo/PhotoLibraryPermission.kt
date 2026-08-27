package com.bivouac.app.data.photo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

// RIC-43 : permission galerie pour la recherche par plage de dates (opt-in, voir Réglages) —
// jamais demandée pour la sélection générique via le Photo Picker, qui n'en a besoin d'aucune.
object PhotoLibraryPermission {

    // La permission à demander dépend de la version : READ_MEDIA_IMAGES depuis l'API 33
    // (READ_EXTERNAL_STORAGE y est silencieusement sans effet), READ_EXTERNAL_STORAGE en dessous
    // (minSdk 26).
    val manifestPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    // Accordée au sens large : accès complet (READ_MEDIA_IMAGES/READ_EXTERNAL_STORAGE), ou accès
    // partiel Android 14+ (READ_MEDIA_VISUAL_USER_SELECTED, « Sélectionner des photos ») — dans
    // les deux cas MediaStore répond, avec un périmètre juste plus restreint pour le second. Un
    // refus complet est le seul cas qui doit déclencher le repli silencieux vers le Photo Picker
    // générique (voir la spec RIC-43).
    fun isGranted(context: Context): Boolean {
        if (granted(context, manifestPermission)) return true
        return Build.VERSION.SDK_INT >= 34 &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

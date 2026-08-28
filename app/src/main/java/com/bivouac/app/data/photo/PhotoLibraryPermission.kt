package com.bivouac.app.data.photo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// RIC-43 : la permission galerie, seule porte d'entrée des photos du Journal depuis que toute la
// sélection passe par le sélecteur interne (voir PhotoPickerDialog). Demandée au moment où
// l'utilisateur ajoute des photos à une sortie, jamais au démarrage, et jamais du tout si la
// fonctionnalité photos est désactivée dans les Réglages (RIC-152).
object PhotoLibraryPermission {

    // La permission à demander dépend de la version : READ_MEDIA_IMAGES depuis l'API 33
    // (READ_EXTERNAL_STORAGE y est silencieusement sans effet), READ_EXTERNAL_STORAGE en dessous
    // (minSdk 26).
    val manifestPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    /**
     * Ce qu'on demande d'un seul tenant, en une seule invite système.
     *
     * READ_MEDIA_VISUAL_USER_SELECTED (Android 14+) est demandée avec la lecture, et pas à sa
     * place : c'est ce qui fait apparaître « Sélectionner des photos » dans l'invite, donc ce qui
     * permet à l'utilisateur de n'ouvrir qu'une partie de sa pellicule plutôt que de devoir
     * choisir entre tout ou rien.
     *
     * ACCESS_MEDIA_LOCATION (Android 10+) est ce qui débloque le GPS de l'EXIF, voir le manifest
     * et PhotoExifReader. Le système ne l'accorde pas sans l'accès en lecture, d'où la demande
     * groupée ; son refus isolé ne change rien au reste (lecture dégradée, sans GPS).
     */
    val requestedPermissions: Array<String>
        get() = buildList {
            add(manifestPermission)
            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }.toTypedArray()

    // Accordée au sens large : accès complet (READ_MEDIA_IMAGES/READ_EXTERNAL_STORAGE), ou accès
    // partiel Android 14+ (READ_MEDIA_VISUAL_USER_SELECTED, « Sélectionner des photos ») — dans
    // les deux cas MediaStore répond, avec un périmètre juste plus restreint pour le second.
    fun isGranted(context: Context): Boolean {
        if (granted(context, manifestPermission)) return true
        return isPartialAccess(context)
    }

    /**
     * Accès partiel Android 14+ : MediaStore ne montre que les photos que l'utilisateur a
     * explicitement ouvertes à l'app. Distinct d'un accès complet parce que le sélecteur doit
     * alors proposer d'en ouvrir davantage (voir PhotoPickerDialog) plutôt que de laisser croire
     * que la pellicule ne contient que ça.
     */
    fun isPartialAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            !granted(context, manifestPermission) &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    /**
     * ACCESS_MEDIA_LOCATION accordée, c'est-à-dire MediaStore autorisé à rendre l'EXIF complet.
     * Avant l'API 29 la question ne se pose pas : rien n'était expurgé, donc « accordée » est la
     * bonne réponse.
     */
    fun isMediaLocationGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)

    /**
     * RIC-43 : le refus qu'on vient d'essuyer est-il définitif, c'est-à-dire le système
     * refusera-t-il désormais d'afficher son invite ?
     *
     * À n'appeler qu'au retour d'une demande refusée, et c'est ce qui lève l'ambiguïté de
     * `shouldShowRequestPermissionRationale` : il rend false aussi bien pour « jamais demandé » que
     * pour « refusé définitivement », deux situations qu'aucune API ne distingue. Au retour d'un
     * refus, le premier cas est exclu par construction.
     *
     * Compte pour beaucoup ici : une demande relancée après un refus définitif rend la main
     * immédiatement, sans afficher quoi que ce soit. C'est ce qui rendait l'appui sur « Ajouter »
     * muet, voir JournalScreen. Le savoir, c'est pouvoir expliquer nous-mêmes au lieu de relancer
     * une demande qui ne dessinera rien.
     *
     * Sans Activity sous la main (cas théorique, l'écran en a toujours une), on répond « pas
     * définitif » : mieux vaut relancer une demande de trop que de condamner l'ajout de photos sur
     * une supposition.
     */
    fun isPermanentlyDenied(context: Context): Boolean {
        val activity = context.findActivity() ?: return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, manifestPermission)
    }

    // Le Context d'un composable est un ContextWrapper enveloppant l'Activity, parfois sur
    // plusieurs couches (thème Material, ContextThemeWrapper de la vue hôte).
    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

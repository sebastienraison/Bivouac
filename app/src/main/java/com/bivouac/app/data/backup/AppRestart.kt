package com.bivouac.app.data.backup

import android.content.Context
import android.content.Intent

/**
 * After a restore (BIV-66), every ViewModel already alive (Planification, Journal, Réglages) is
 * holding StateFlows, repositories and DAOs built against the *previous* database/DataStore
 * files: `Activity.recreate()` alone wouldn't help, since its ViewModelStore survives recreation
 * by design (that's the whole point of ViewModel) and would just hand the same stale instances
 * back. A full process restart sidesteps having to hunt down and manually invalidate every one of
 * them: everything below the launcher Activity is guaranteed to be rebuilt from scratch.
 */
object AppRestart {
    fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}

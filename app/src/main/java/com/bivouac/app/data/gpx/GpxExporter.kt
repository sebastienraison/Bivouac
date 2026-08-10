package com.bivouac.app.data.gpx

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.bivouac.app.data.model.TrackPoint
import java.io.File

object GpxExporter {

    /**
     * Writes the segment to a cache file and returns a chooser intent to open it in another app.
     *
     * ACTION_VIEW (rather than ACTION_SEND) so the chooser only lists apps that actually declare
     * being able to open this file type, instead of every generic share target (Bluetooth, Drive,
     * messaging apps...) that accepts arbitrary content.
     */
    fun openIntent(context: Context, points: List<TrackPoint>, name: String): Intent {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, GpxWriter.suggestedFileName(name))
        file.writeText(GpxWriter.write(points, name))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/gpx+xml")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(viewIntent, "Ouvrir la trace")
    }
}

package com.bivouac.app

import android.app.Application
import org.osmdroid.config.Configuration

class BivouacApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // osmdroid requires a distinct user agent, otherwise OSM tile servers may block requests.
        Configuration.getInstance().userAgentValue = packageName
    }
}

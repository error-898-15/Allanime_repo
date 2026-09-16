package com.uchiharepo.fojik

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FojikPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the Fojik provider into CloudStream 3
        registerMainAPI(FojikProvider())
    }
}

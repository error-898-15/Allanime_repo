package com.uchiharepo.flixvision

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FlixVisionPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FlixVisionProvider())
    }
}

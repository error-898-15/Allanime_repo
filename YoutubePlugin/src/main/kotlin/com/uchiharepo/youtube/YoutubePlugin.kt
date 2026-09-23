package com.uchiharepo.youtube

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YoutubePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YoutubeProvider())
    }
}

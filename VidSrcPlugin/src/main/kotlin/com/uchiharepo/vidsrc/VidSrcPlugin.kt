package com.uchiharepo.vidsrc

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VidSrcPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VidSrcProvider())
    }
}

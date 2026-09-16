package com.uchiharepo.animesalt

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimeSaltPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeSaltProvider())
    }
}

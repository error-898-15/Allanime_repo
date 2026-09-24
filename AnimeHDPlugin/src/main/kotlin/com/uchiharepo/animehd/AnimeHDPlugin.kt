package com.uchiharepo.animehd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeHDProvider())
    }
}

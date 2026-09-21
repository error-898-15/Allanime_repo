package com.uchiharepo.just4anime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Just4AnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Just4AnimeProvider())
    }
}

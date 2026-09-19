package com.uchiharepo.aniflix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AniflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniflixProvider())
    }
}

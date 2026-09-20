package com.uchiharepo.effedupmovies

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class EffedUpMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(EffedUpMoviesProvider())
    }
}

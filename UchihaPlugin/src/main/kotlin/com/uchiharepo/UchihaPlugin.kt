package com.uchiharepo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class UchihaPlugin : Plugin() {
    override fun load(context: Context) {
        // Register Blakite Anime provider (Hindi/English Subbed & Dubbed Anime with Rumble Cloud HLS streaming)
        registerMainAPI(BlakiteAnimeProvider())
    }
}

package com.uchiharepo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class UchihaPlugin : Plugin() {
    override fun load(context: Context) {
        // Register Blakite Anime provider (Hindi/English Subbed & Dubbed Anime)
        registerMainAPI(BlakiteAnimeProvider())

        // Register ZLive provider (Live Sports, Events, and TV Channels)
        registerMainAPI(ZLiveProvider())
    }
}

package com.uchiharepo.hianime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HiAnimePlugin : Plugin() {
    override fun load(context: Context) {
        // Register HiAnime provider (Subbed & Dubbed Anime with HD multi-quality HLS streaming)
        registerMainAPI(HiAnimeProvider())
    }
}

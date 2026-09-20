package com.uchiharepo.gogoanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class GogoanimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(GogoanimeProvider())
    }
}

package com.uchiharepo.cinevood

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CineVoodPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineVoodProvider())
    }
}

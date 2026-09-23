package com.uchiharepo.primevideo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PrimeVideoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PrimeVideoProvider())
    }
}

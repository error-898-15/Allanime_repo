package com.uchiharepo.joya9

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Joya9Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Joya9Provider())
    }
}

package com.uchiharepo.cinevood

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CinevoodPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CinevoodProvider())
    }
}

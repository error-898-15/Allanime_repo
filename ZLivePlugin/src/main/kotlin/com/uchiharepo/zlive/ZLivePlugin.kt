package com.uchiharepo.zlive

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ZLivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ZLiveProvider())
    }
}

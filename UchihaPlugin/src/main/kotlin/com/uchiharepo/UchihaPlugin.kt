package com.uchiharepo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class UchihaPlugin : Plugin() {
    override fun load(context: Context) {
        // STEP 1 Foundation: Ready for STEP 2 provider registration
        // In STEP 2, website/app providers will be registered here via:
        // registerMainAPI(YourProvider())
    }
}

package com.neowatch

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DiziPalGuncelPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziPalGuncel())
        registerExtractorAPI(ContentXGenel())
    }
}

package com.neowatch

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DiziPalOrjinalPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziPalOrjinal())
        registerExtractorAPI(VideoPlays())
    }
}

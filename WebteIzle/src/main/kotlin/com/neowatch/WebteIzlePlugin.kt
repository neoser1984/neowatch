package com.neowatch

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class WebteIzlePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WebteIzle())
    }
}
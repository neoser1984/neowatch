package com.neowatch

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NeoTeshisPlugin: Plugin() {
    override fun load(context: Context) {
        NeoTeshis.baglam = context.applicationContext ?: context
        registerMainAPI(NeoTeshis())
    }
}

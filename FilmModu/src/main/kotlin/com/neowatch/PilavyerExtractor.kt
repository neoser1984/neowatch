package com.neowatch

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * Pilavyer oynatıcısı.
 * Sayfadaki `<div data-pv="SLUG">` + `.../assets/js/core.js` → `{BASE}/assets/js/s.php?s=SLUG`
 * Oynatıcı sayfasındaki `window.__PLAYER__` içinde m3u8 (stream) ve altyazılar bulunur.
 */
open class Pilavyer : ExtractorApi() {
    override val name            = "Pilavyer"
    override val mainUrl         = "https://play2.pilavyerplay.top"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base   = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: return
        val extRef = referer ?: "$mainUrl/"
        val html   = app.get(url, referer = extRef).text

        val json   = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\})\s*;\s*</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return
        val oyuncu = jsonOku<PlayerVeri>(json) ?: return

        oyuncu.subs?.forEach { sub ->
            val subUrl = sub.src ?: return@forEach
            subtitleCallback.invoke(newSubtitleFile(sub.label ?: sub.lang ?: "Türkçe", subUrl))
        }

        val stream = oyuncu.stream ?: return
        Log.d("NeO_Pilavyer", "stream » $stream")

        callback.invoke(
            newExtractorLink(this.name, this.name, stream, ExtractorLinkType.M3U8) {
                this.referer = "$base/"
                this.quality = Qualities.Unknown.value
            }
        )
    }

    data class PlayerVeri(
        @JsonProperty("stream") val stream: String?       = null,
        @JsonProperty("title")  val title: String?        = null,
        @JsonProperty("subs")   val subs: List<Altyazi>?  = null
    )

    data class Altyazi(
        @JsonProperty("lang")  val lang: String?  = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("src")   val src: String?   = null
    )
}

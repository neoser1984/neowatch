package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Dizipal Orijinal'in yeni bölümlerde kullandığı oynatıcı.
 * Örnek: https://streamcorecdn.com/dizi/256440/1/1
 * Sayfadaki `const src = '/play.m3u8?id=..&m=master&token=..&expires=..'` HLS listesini verir,
 * `tracksData.subtitles` altyazıları (göreli yol + tokenParams) içerir.
 */
open class StreamCore : ExtractorApi() {
    override val name            = "StreamCore"
    override val mainUrl         = "https://streamcorecdn.com"
    override val requiresReferer = true

    companion object {
        fun uygunMu(url: String): Boolean = url.contains("streamcorecdn") || Regex("""/(dizi|film)/\d+(/\d+/\d+)?/?$""").containsMatchIn(url)
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base  = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: mainUrl
        val yanit = app.get(url, referer = referer ?: "$base/")
        val sayfa = yanit.text

        val src = Regex("""const\s+src\s*=\s*['"]([^'"]+)['"]""").find(sayfa)?.groupValues?.get(1)
        if (src == null) {
            Log.d("NeO_StreamCore", "kaynak bulunamadı » $url")
            return
        }
        val m3u8 = if (src.startsWith("http")) src else base + (if (src.startsWith("/")) src else "/$src")
        Log.d("NeO_StreamCore", "m3u8 » $m3u8")

        callback.invoke(
            newExtractorLink(this.name, this.name, m3u8, ExtractorLinkType.M3U8) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Origin" to base)
            }
        )

        // Altyazılar
        val token  = Regex("""tokenParams\s*=\s*["']([^"']*)["']""").find(sayfa)?.groupValues?.get(1).orEmpty()
        val id     = Regex("""[?&]id=(\d+)""").find(src)?.groupValues?.get(1)
        val izler  = Regex("""tracksData\s*=\s*(\{.*?\});""").find(sayfa)?.groupValues?.get(1) ?: return

        runCatching {
            val altlar = JSONObject(izler).optJSONArray("subtitles") ?: return@runCatching
            for (i in 0 until altlar.length()) {
                val a     = altlar.optJSONObject(i) ?: continue
                val yol   = a.optString("url").takeIf { it.isNotBlank() } ?: continue
                val adres = when {
                    yol.startsWith("http") -> yol
                    id != null             -> "$base/play.m3u8?id=$id&p=${URLEncoder.encode(yol, "UTF-8")}$token"
                    else                   -> continue
                }
                subtitleCallback.invoke(newSubtitleFile(a.optString("name").ifBlank { "Türkçe" }, adres))
            }
        }
    }
}

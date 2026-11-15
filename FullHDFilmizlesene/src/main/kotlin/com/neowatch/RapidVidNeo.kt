package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.RapidVid
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

/**
 * RapidVid (rapidvid.org / .net …) çözücüsü.
 * Kasım 2026: yeni oynatıcı (core.js) ayarları  window._p8 = '…'  içinde taşıyor:
 * ters çevrilmiş base64 → her karakterden "K9L" anahtarına göre 1/3/2 çıkarılır → base64 → JSON.
 * JSON'da "cm" H.264 HLS ana listesi, "tm" AV1 listesi, "ct" altyazılardır.
 * Eski tip sayfalar için CloudStream'in yerleşik RapidVid çıkarıcısına düşülür.
 */
open class RapidVidNeo : ExtractorApi() {
    override val name            = "RapidVid"
    override val mainUrl         = "https://rapidvid.org"
    override val requiresReferer = true

    companion object {
        fun uygunMu(url: String): Boolean = Regex("""^https?://(?:www\.)?rapidvid\.[a-z]+/""").containsMatchIn(url)

        fun p8Coz(p8: String): String? = runCatching {
            val ilk  = base64DecodeArray(p8.reversed())
            val anah = "K9L"
            val ara  = StringBuilder(ilk.size)
            ilk.forEachIndexed { i, b ->
                val kaydir = anah[i % 3].code % 5 + 1
                ara.append(((b.toInt() and 0xff) - kaydir).toChar())
            }
            String(base64DecodeArray(ara.toString()), Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base  = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: mainUrl
        val sayfa = app.get(url, referer = referer ?: "$base/", headers = mapOf("Sec-Fetch-Dest" to "iframe")).text

        val p8 = Regex("""window\._p8\s*=\s*['"]([^'"]+)['"]""").find(sayfa)?.groupValues?.get(1)
        if (p8 == null) {
            Log.d("NeO_RapidVid", "_p8 yok, eski çözücü » $url")
            RapidVid().getUrl(url, referer, subtitleCallback, callback)
            return
        }

        val ayar = p8Coz(p8)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (ayar == null) {
            Log.d("NeO_RapidVid", "_p8 çözülemedi » $url")
            return
        }

        ayar.optJSONArray("ct")?.let { liste ->
            for (i in 0 until liste.length()) {
                val a     = liste.optJSONObject(i) ?: continue
                val dosya = a.optString("file").ifBlank { null } ?: continue
                if (a.optString("kind").let { it.isNotBlank() && it != "captions" && it != "subtitles" }) continue
                val adres = if (dosya.startsWith("http")) dosya else "$base/${dosya.trimStart('/')}"
                subtitleCallback.invoke(newSubtitleFile(a.optString("label").ifBlank { "Türkçe" }, adres))
            }
        }

        val akislar = listOf("cm" to name, "tm" to "$name AV1").mapNotNull { (anahtar, isim) ->
            ayar.optString(anahtar).takeIf { it.startsWith("http") }?.let { isim to it }
        }.distinctBy { it.second }

        for ((isim, akis) in akislar) {
            Log.d("NeO_RapidVid", "$isim » $akis")
            callback.invoke(
                newExtractorLink(isim, isim, akis, ExtractorLinkType.M3U8) {
                    this.referer = "$base/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Origin" to base)
                }
            )
        }
    }
}

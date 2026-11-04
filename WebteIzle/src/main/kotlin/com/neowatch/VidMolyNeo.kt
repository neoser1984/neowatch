package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * VidMoly (vidmoly.org / .to / .me / .biz) çözücüsü.
 * CloudStream'in yerleşik Vidmoly çıkarıcısı sayfadaki  sources: [{ file: '…' }]  dizisini JSON sanıp
 * tek tırnak yüzünden hata veriyor; burada adres doğrudan düzenli ifadeyle okunur.
 */
open class VidMolyNeo : ExtractorApi() {
    override val name            = "VidMoly"
    override val mainUrl         = "https://vidmoly.org"
    override val requiresReferer = true

    companion object {
        fun uygunMu(url: String): Boolean = Regex("""^https?://(?:www\.)?vidmoly\.[a-z]+/""").containsMatchIn(url)
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base  = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: mainUrl
        val sayfa = Regex("""/w/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)?.let { "$base/embed-$it.html" } ?: url

        var metin = app.get(
            sayfa,
            referer = referer ?: "$base/",
            headers = mapOf(
                "User-Agent"     to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36",
                "Sec-Fetch-Dest" to "iframe"
            )
        ).text
        if (metin.contains("eval(function(p,a,c,k,e,d)")) metin += "\n" + (runCatching { getAndUnpack(metin) }.getOrNull() ?: "")

        val dosya = Regex("""sources\s*:\s*\[\s*\{\s*"?file"?\s*:\s*['"]([^'"]+)['"]""").find(metin)?.groupValues?.get(1)
            ?: Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").find(metin)?.groupValues?.get(1)
            ?: return
        Log.d("NeO_VidMoly", "dosya » $dosya")

        Regex("""\{\s*"?file"?\s*:\s*['"]([^'"]+\.(?:vtt|srt)[^'"]*)['"]\s*,\s*"?label"?\s*:\s*['"]([^'"]*)['"]""").findAll(metin).forEach { m ->
            val adres = m.groupValues[1].replace("\\/", "/").let { if (it.startsWith("http")) it else "$base/${it.trimStart('/')}" }
            subtitleCallback.invoke(newSubtitleFile(m.groupValues[2].ifBlank { "Türkçe" }, adres))
        }

        callback.invoke(
            newExtractorLink(name, name, dosya.replace("\\/", "/"), if (dosya.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = "$base/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

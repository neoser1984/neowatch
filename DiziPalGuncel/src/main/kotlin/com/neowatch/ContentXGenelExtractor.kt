package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * ContentX / Pichive / PlayRu / DPlayer ailesi oynatıcılar için alan adından bağımsız çözücü.
 * Örnek: https://<host>/iframe.php?v=<hash>
 */
open class ContentXGenel : ExtractorApi() {
    override val name            = "ContentX"
    override val mainUrl         = "https://contentx.me"
    override val requiresReferer = true

    companion object {
        fun uygunMu(url: String): Boolean = url.contains("/iframe.php?v=")
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val tamUrl  = if (url.startsWith("//")) "https:$url" else url
        val base    = Regex("""^(https?://[^/]+)""").find(tamUrl)?.groupValues?.get(1) ?: return
        val host    = base.substringAfter("://").substringAfter("four.").substringAfter("sn.").substringBefore(".")
            .replaceFirstChar { it.uppercase() }
        val extRef  = referer ?: "$base/"
        val iSource = app.get(tamUrl, referer = extRef).text

        val altyazilar = mutableSetOf<String>()
        Regex("""\"file\":\"([^\"]+)\",\"label\":\"([^\"]+)\"""").findAll(iSource).forEach {
            val (subUrl, subLang) = it.destructured
            if (!altyazilar.add(subUrl)) return@forEach

            subtitleCallback.invoke(
                newSubtitleFile(
                    subLang.replace("\\u0131", "ı").replace("\\u0130", "İ").replace("\\u00fc", "ü").replace("\\u00e7", "ç"),
                    subUrl.replace("\\", "").let { s -> if (s.startsWith("http")) s else "$base/${s.trimStart('/')}" }
                )
            )
        }

        val iExtract = Regex("""window\.openPlayer\('([^']+)'""").find(iSource)?.groupValues?.get(1)
        if (iExtract == null) {
            // Eski tip oynatıcı: doğrudan file:"..." içerir
            val dosya = Regex("""file\s*:\s*"([^"]+)""").find(iSource)?.groupValues?.get(1) ?: return
            callback.invoke(
                newExtractorLink(host, host, dosya, if (dosya.contains(".m3u8")) ExtractorLinkType.M3U8 else null) {
                    this.referer = "$base/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        kaynakEkle(base, iExtract, extRef, tamUrl, host, callback)

        val iDublaj = Regex(""",\"([^']+)\",\"Türkçe""").find(iSource)?.groupValues?.get(1)
        if (iDublaj != null && iDublaj != iExtract) {
            kaynakEkle(base, iDublaj, extRef, tamUrl, "$host Türkçe Dublaj", callback)
        }
    }

    private suspend fun kaynakEkle(base: String, id: String, extRef: String, iframe: String, isim: String, callback: (ExtractorLink) -> Unit) {
        val vidSource = app.get("$base/source2.php?v=$id", referer = extRef).text
        val m3uLink   = Regex("""file\":\"([^\"]+)""").find(vidSource)?.groupValues?.get(1)?.replace("\\", "") ?: return
        Log.d("NeO_ContentX", "$isim » $m3uLink")

        callback.invoke(
            newExtractorLink(isim, isim, m3uLink, ExtractorLinkType.M3U8) {
                this.referer = iframe
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

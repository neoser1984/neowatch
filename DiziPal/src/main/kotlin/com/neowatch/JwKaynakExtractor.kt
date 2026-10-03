package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * jwplayer("...").setup({ sources: [{file:"...m3u8"}], tracks: [...] }) kullanan
 * XFileSharing tabanlı oynatıcılar (formationfeed.net vb.) için çözücü.
 * Örnek: https://formationfeed.net/embed-7yno2djovj7e.html
 */
open class JwKaynak : ExtractorApi() {
    override val name            = "PalPlayer"
    override val mainUrl         = "https://formationfeed.net"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: mainUrl

        val sayfa = app.get(
            url,
            referer = referer ?: "$base/",
            headers = mapOf("Sec-Fetch-Dest" to "iframe")
        ).text

        // Bazı sunucular betiği p,a,c,k,e,d ile paketliyor
        val metin = if (sayfa.contains("eval(function(p,a,c,k,e")) {
            runCatching { getAndUnpack(sayfa) }.getOrNull()?.let { sayfa + "\n" + it } ?: sayfa
        } else {
            sayfa
        }

        val kaynaklar = Regex("""sources\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(metin)?.groupValues?.get(1)
        if (kaynaklar == null) {
            Log.d("NeO_JwKaynak", "kaynak bulunamadı » $url")
            return
        }

        Regex("""file\s*:\s*["']([^"']+)["']""").findAll(kaynaklar).forEach { eslesme ->
            val dosya = eslesme.groupValues[1].replace("\\/", "/")
            Log.d("NeO_JwKaynak", "$base » $dosya")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = dosya,
                    type   = if (dosya.contains(".m3u8")) ExtractorLinkType.M3U8 else null
                ) {
                    this.referer = "$base/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Origin" to base)
                }
            )
        }

        // Altyazılar
        Regex("""tracks\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(metin)?.groupValues?.get(1)?.let { izler ->
            Regex("""\{[^}]*?file\s*:\s*["']([^"']+)["'][^}]*?}""").findAll(izler).forEach { iz ->
                val blok  = iz.value
                if (blok.contains("thumbnails")) return@forEach
                val dosya = iz.groupValues[1].replace("\\/", "/")
                val etiket = Regex("""label\s*:\s*["']([^"']+)["']""").find(blok)?.groupValues?.get(1) ?: "Türkçe"
                subtitleCallback.invoke(newSubtitleFile(if (etiket.equals("Turkish", true)) "Türkçe" else etiket, dosya))
            }
        }
    }
}

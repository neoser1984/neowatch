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

        // ? Yeni oynatıcı (np.js): window.NP_CONFIG = {"sources":[{"title","master":".../nm.php?v=…"}],"subtitles":[{"label","url"}]}
        nesneAl(iSource, "NP_CONFIG")?.let { np ->
            if (npCoz(np, base, host, tamUrl, subtitleCallback, callback)) return
        }

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

    /** Sayfadaki  window.<ad> = {…}  nesnesini (iç içe süslü parantez ve dizeleri gözeterek) metin olarak döndürür. */
    private fun nesneAl(kaynak: String, ad: String): String? {
        val bas = kaynak.indexOf(ad).takeIf { it >= 0 } ?: return null
        val ilk = kaynak.indexOf('{', bas).takeIf { it >= 0 } ?: return null
        var derinlik = 0
        var dizede   = false
        var i        = ilk
        while (i < kaynak.length) {
            val c = kaynak[i]
            if (dizede) {
                if (c == '\\') i++ else if (c == '"') dizede = false
            } else when (c) {
                '"' -> dizede = true
                '{' -> derinlik++
                '}' -> { derinlik--; if (derinlik == 0) return kaynak.substring(ilk, i + 1) }
            }
            i++
        }
        return null
    }

    private fun duzelt(s: String): String =
        Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }.replace("\\/", "/")

    private suspend fun npCoz(np: String, base: String, host: String, iframe: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val altyazilar = Regex(""""subtitles"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(np)?.groupValues?.get(1) ?: ""
        Regex("""\{[^{}]*?"label"\s*:\s*"([^"]*)"[^{}]*?"url"\s*:\s*"([^"]+)"[^{}]*?}""").findAll(altyazilar).forEach { m ->
            val adres = duzelt(m.groupValues[2]).let { if (it.startsWith("http")) it else "$base/${it.trimStart('/')}" }
            subtitleCallback.invoke(newSubtitleFile(duzelt(m.groupValues[1]).ifBlank { "Türkçe" }, adres))
        }

        val kaynaklar = Regex(""""sources"\s*:\s*\[(.*?)]\s*,\s*"(?:subtitles|vast|download|track)"""", RegexOption.DOT_MATCHES_ALL).find(np)?.groupValues?.get(1)
            ?: Regex(""""sources"\s*:\s*\[(.*)]""", RegexOption.DOT_MATCHES_ALL).find(np)?.groupValues?.get(1) ?: return false
        var bulundu = false
        Regex("""\{[^{}]*?"master"\s*:\s*"([^"]+)"[^{}]*?}""").findAll(kaynaklar).forEach { m ->
            val master = duzelt(m.groupValues[1]).let { if (it.startsWith("http")) it else "$base/${it.trimStart('/')}" }
            val baslik = Regex(""""title"\s*:\s*"([^"]*)"""").find(m.value)?.groupValues?.get(1)?.let { duzelt(it) }
            val isim   = if (baslik.isNullOrBlank() || baslik.equals("Orijinal", true)) host else "$host $baslik"
            Log.d("NeO_ContentX", "$isim » $master")
            bulundu = true
            callback.invoke(
                newExtractorLink(isim, isim, master, ExtractorLinkType.M3U8) {
                    this.referer = iframe
                    this.headers = mapOf("Origin" to base)
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return bulundu
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

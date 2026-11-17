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
        val yanit   = app.get(tamUrl, referer = extRef)
        val iSource = yanit.text
        Log.d("NeO_ContentX", "iframe ${yanit.code} uzunluk=${iSource.length} np=${iSource.contains("NP_CONFIG")} gate=${iSource.contains("NP_GATE")} " +
            "openPlayer=${iSource.contains("openPlayer")} başlık='${Regex("<title[^>]*>([^<]{0,60})").find(iSource)?.groupValues?.get(1)?.trim()}' » $tamUrl")

        // ? Yeni oynatıcı (np.js): window.NP_CONFIG = {"sources":[{"title","master":".../nm.php?v=…"}],"subtitles":[{"label","url"}]}
        nesneAl(iSource, "NP_CONFIG")?.let { np ->
            if (npCoz(np, iSource, base, host, tamUrl, subtitleCallback, callback)) return
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
            val dosya = Regex("""file\s*:\s*"([^"]+)""").find(iSource)?.groupValues?.get(1)
            if (dosya == null) { Log.d("NeO_ContentX", "kaynak bulunamadı (NP_CONFIG/openPlayer/file yok) » $tamUrl"); return }
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

    private suspend fun npCoz(np: String, sayfa: String, base: String, host: String, iframe: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // Kasım 2026: "gate":{"n":"…","b":12} varsa master ve altyazı adreslerine iş kanıtı (&p=…) eklenmeli; yoksa 404
        val kapi = Regex(""""gate"\s*:\s*\{\s*"n"\s*:\s*"([^"]+)"(?:\s*,\s*"b"\s*:\s*(\d+))?""").find(np)?.let { g ->
            NpKapi.coz(sayfa, g.groupValues[1], g.groupValues[2].toIntOrNull())
        }
        Log.d("NeO_ContentX", "gate=${np.contains("\"gate\"")} p=${kapi ?: "yok"}")
        fun ekle(adres: String) = if (kapi == null) adres else adres + (if (adres.contains("?")) "&" else "?") + "p=$kapi"

        val altyazilar = Regex(""""subtitles"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(np)?.groupValues?.get(1) ?: ""
        Regex("""\{[^{}]*?"label"\s*:\s*"([^"]*)"[^{}]*?"url"\s*:\s*"([^"]+)"[^{}]*?\}""").findAll(altyazilar).forEach { m ->
            val adres = duzelt(m.groupValues[2]).let { if (it.startsWith("http")) it else "$base/${it.trimStart('/')}" }
            subtitleCallback.invoke(newSubtitleFile(duzelt(m.groupValues[1]).ifBlank { "Türkçe" }, ekle(adres)))
        }

        val kaynaklar = Regex(""""sources"\s*:\s*\[(.*?)]\s*,\s*"(?:subtitles|vast|download|track)"""", RegexOption.DOT_MATCHES_ALL).find(np)?.groupValues?.get(1)
            ?: Regex(""""sources"\s*:\s*\[(.*)]""", RegexOption.DOT_MATCHES_ALL).find(np)?.groupValues?.get(1) ?: return false
        var bulundu = false
        Regex("""\{[^{}]*?"master"\s*:\s*"([^"]+)"[^{}]*?\}""").findAll(kaynaklar).forEach { m ->
            val master = ekle(duzelt(m.groupValues[1]).let { if (it.startsWith("http")) it else "$base/${it.trimStart('/')}" })
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

/**
 * dplayer / np.js oynatıcısının "gate" iş kanıtı.
 * Sayfadaki  window.NP_GATE = function(n, h){ … }  her yüklemede farklı üretilir: önek (String.fromCharCode),
 * ayraç, birleştirme sırası, sayı tabanı ve denetlenen SHA-256 sözcüğü/maskesi değişir. Fonksiyon metni çözümlenip
 * aynı arama yapılır. NP_GATE yoksa np.js'teki yedek kullanılır: sha256(n + ":" + i)[0] >>> (32 - b) == 0.
 */
object NpKapi {
    private const val SINIR = 30_000_000

    fun coz(sayfa: String, n: String, b: Int?): String? = runCatching { ozel(sayfa, n) ?: yedek(n, b ?: 12) }
        .onFailure { Log.d("NeO_ContentX", "gate » ${it.message}") }.getOrNull()

    private fun ozel(sayfa: String, n: String): String? {
        val f = Regex("""NP_GATE\s*=\s*function\s*\(\s*([\w$]+)\s*,\s*([\w$]+)\s*\)\s*\{([\s\S]*?)\}\s*;""").find(sayfa) ?: return null
        val (pN, pH, govde) = f.destructured

        val sabitler = mutableMapOf<String, String>()
        Regex("""var\s+([\w$]+)\s*=\s*String\.fromCharCode\(([\d\s,]+)\)""").findAll(govde).forEach { m ->
            sabitler[m.groupValues[1]] = m.groupValues[2].split(",").mapNotNull { it.trim().toIntOrNull()?.toChar() }.joinToString("")
        }
        Regex("""var\s+([\w$]+)\s*=\s*(["'])([^"']*)\2\s*[;,]""").findAll(govde).forEach { m -> sabitler[m.groupValues[1]] = m.groupValues[3] }

        val dongu = Regex("""var\s+([\w$]+)\s*=\s*[\w$]+\.toString\(\s*(\d+)\s*\)""").find(govde) ?: return null
        val sayac = dongu.groupValues[1]
        val taban = dongu.groupValues[2].toInt().takeIf { it in 2..36 } ?: return null

        val cagri = Regex(Regex.escape(pH) + """\(([^()]*)\)\s*\[\s*(\d+)\s*]\s*&\s*(\d+)\s*\)?\s*===?\s*0""").find(govde) ?: return null
        val sozcuk = cagri.groupValues[2].toInt().takeIf { it in 0..7 } ?: return null
        val maske  = cagri.groupValues[3].toLong()

        // Birleştirilen parçalar: n parametresi, sayaç (null), sabit değişkenler ve tırnaklı metinler
        val parcalar: List<String?> = cagri.groupValues[1].split("+").map { it.trim() }.map { p ->
            when {
                p == pN    -> n
                p == sayac -> null
                sabitler.containsKey(p) -> sabitler.getValue(p)
                Regex("""^(["'])(.*)\1$""").matches(p) -> p.substring(1, p.length - 1)
                else -> return null
            }
        }
        if (parcalar.none { it == null }) return null

        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (i in 0 until SINIR) {
            val s = i.toString(taban)
            val girdi = parcalar.joinToString("") { it ?: s }
            val h = md.digest(girdi.toByteArray(Charsets.UTF_8))
            if ((sozcukAl(h, sozcuk) and maske) == 0L) return s
        }
        return null
    }

    private fun yedek(n: String, b: Int): String? {
        val kaydir = 32 - b.coerceIn(1, 31)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (i in 0 until SINIR) {
            val h = md.digest("$n:$i".toByteArray(Charsets.UTF_8))
            if ((sozcukAl(h, 0) ushr kaydir) == 0L) return i.toString()
        }
        return null
    }

    private fun sozcukAl(h: ByteArray, i: Int): Long {
        val o = i * 4
        return ((h[o].toLong() and 0xff) shl 24) or ((h[o + 1].toLong() and 0xff) shl 16) or
               ((h[o + 2].toLong() and 0xff) shl 8) or (h[o + 3].toLong() and 0xff)
    }
}

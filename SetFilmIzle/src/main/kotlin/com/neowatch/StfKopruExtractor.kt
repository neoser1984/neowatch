package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import java.net.URI
import kotlin.random.Random

/**
 * SetFilmizle'nin "köprü" oynatıcısı.
 * Örnek: https://setplay.shop/player/stfplay.php?t=...&p=...&a=...
 *
 * Köprü sayfası normalde fastplay.mom çerçevesini açar; "nb2=1" parametresiyle
 * doğrudan sunucudan oynatma sayfası gelir. Bu sayfadaki STF_KOPRU.src (manifest.php)
 * HLS listesini verir. Liste istekleri, sayfadaki SPG_A.sp değerinden üretilen
 * "X-Sp" başlığını ister (zaman + rastgele + FNV-1a özeti).
 */
open class StfKopru : ExtractorApi() {
    override val name            = "SetPlay"
    override val mainUrl         = "https://setplay.shop"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base     = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: mainUrl
        val sayfaUrl = if (url.contains("nb2=")) url else url + (if (url.contains("?")) "&" else "?") + "nb2=1"

        val sayfa = app.get(sayfaUrl, referer = referer ?: "$base/").text

        val kaynak = Regex("""STF_KOPRU\s*=\s*\{[\s\S]*?src\s*:\s*"([^"]+)"""").find(sayfa)?.groupValues?.get(1)
        if (kaynak == null) {
            Log.d("NeO_StfKopru", "manifest bulunamadı » $url")
            return
        }

        val manifest = runCatching { URI(sayfaUrl).resolve(kaynak.replace("\\/", "/")).toString() }.getOrNull() ?: return
        val sp       = Regex(""""sp"\s*:\s*"([^"]*)"""").find(sayfa)?.groupValues?.get(1).orEmpty()
        val spT      = Regex(""""spT"\s*:\s*(\d+)""").find(sayfa)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
            ?: (System.currentTimeMillis() / 1000)

        val basliklar = mutableMapOf("Origin" to base)
        if (sp.isNotBlank()) basliklar["X-Sp"] = xSp(sp, spT)

        Log.d("NeO_StfKopru", "manifest » $manifest")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = manifest,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = sayfaUrl
                this.quality = Qualities.Unknown.value
                this.headers = basliklar
            }
        )

        // Altyazılar
        altyazilar(sayfa).forEach { (etiket, dosya) ->
            subtitleCallback.invoke(newSubtitleFile(etiket, dosya))
        }
    }

    private fun altyazilar(sayfa: String): List<Pair<String, String>> {
        val bas = sayfa.indexOf("subtitles:").takeIf { it >= 0 } ?: return emptyList()
        val ac  = sayfa.indexOf('[', bas).takeIf { it >= 0 } ?: return emptyList()

        var derinlik = 0
        var son      = -1
        for (i in ac until sayfa.length) {
            when (sayfa[i]) {
                '[' -> derinlik++
                ']' -> { derinlik--; if (derinlik == 0) { son = i; break } }
            }
        }
        if (son < 0) return emptyList()

        return runCatching {
            val dizi  = JSONArray(sayfa.substring(ac, son + 1))
            val liste = mutableListOf<Pair<String, String>>()
            for (i in 0 until dizi.length()) {
                val o    = dizi.optJSONObject(i) ?: continue
                val tur  = o.optString("kind")
                val file = o.optString("file")
                if (file.isBlank() || tur == "thumbnails") continue
                liste.add((o.optString("label").ifBlank { o.optString("lang").ifBlank { "Türkçe" } }) to file)
            }
            liste
        }.getOrDefault(emptyList())
    }

    /** SPG koruması: "<zaman>.<rastgele>.<fnv1a(sp|zaman|rastgele)>" */
    private fun xSp(sp: String, zaman: Long): String {
        val rastgele = java.lang.Long.toString(Random.nextLong(0, 2176782336L), 36)
        return "$zaman.$rastgele.${fnv1a("$sp|$zaman|$rastgele")}"
    }

    private fun fnv1a(metin: String): String {
        var h = 2166136261L
        for (c in metin) {
            h = h xor (c.code.toLong() and 0xffff)
            h = (h * 16777619L) and 0xffffffffL
        }
        return java.lang.Long.toHexString(h)
    }
}

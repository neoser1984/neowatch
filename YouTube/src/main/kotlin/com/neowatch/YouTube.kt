package com.neowatch

import android.util.Log
import java.net.URLEncoder
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// ! Herkese açık Invidious sunucularının API'leri artık engelli (403). Liste ve arama doğrudan
//   youtube.com sayfalarındaki ytInitialData verisinden okunur; oynatma CloudStream'in kendi
//   YouTube çıkarıcısıyla (NewPipe) yapılır.
class YouTube : MainAPI() {
    override var mainUrl              = "https://www.youtube.com"
    override var name                 = "YouTube"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Others)

    private val basliklar = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.6"
    )
    private val cerezler = mapOf("CONSENT" to "YES+cb", "SOCS" to "CAI")

    // ? sp: CAMSBAgDEAE%3D → bu hafta yüklenen videolar, izlenmeye göre sıralı
    override val mainPage = mainPageOf(
        "müzik"            to "Haftanın Müzikleri",
        "film fragmanı"    to "Fragmanlar",
        "haber"            to "Haberler",
        "belgesel"         to "Belgeseller",
        "oyun"             to "Oyun",
        "komedi"           to "Komedi",
        "spor özet"        to "Spor",
        "çocuk çizgi film" to "Çocuk",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val liste = ara(request.data, "CAMSBAgDEAE%3D")
        return newHomePageResponse(request.name, liste, false)
    }

    override suspend fun search(query: String): List<SearchResponse> = ara(query, "EgIQAQ%3D%3D")

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private suspend fun ara(sorgu: String, sp: String): List<SearchResponse> {
        val metin = app.get(
            "${mainUrl}/results?search_query=${URLEncoder.encode(sorgu, "UTF-8")}&sp=${sp}&hl=tr&gl=TR",
            headers = basliklar, cookies = cerezler
        ).text

        return videoListesi(metin).map { (id, baslik) ->
            newMovieSearchResponse(baslik, "${mainUrl}/watch?v=${id}", TvType.Others) {
                this.posterUrl = "https://i.ytimg.com/vi/${id}/hqdefault.jpg"
            }
        }
    }

    // ytInitialData içindeki videoRenderer / compactVideoRenderer / lockupViewModel kayıtlarından (id, başlık) çıkarır
    private fun videoListesi(metin: String): List<Pair<String, String>> {
        val sonuc = linkedMapOf<String, String>()

        Regex(""""(?:videoRenderer|compactVideoRenderer|gridVideoRenderer)"\s*:\s*\{\s*"videoId"\s*:\s*"([A-Za-z0-9_-]{11})"""").findAll(metin).forEach { m ->
            val id    = m.groupValues[1]
            if (sonuc.containsKey(id)) return@forEach
            val parca = metin.substring(m.range.last, minOf(metin.length, m.range.last + 6000))
            val baslik = Regex(""""title"\s*:\s*\{\s*"runs"\s*:\s*\[\s*\{\s*"text"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(parca)?.groupValues?.get(1)
                ?: Regex(""""title"\s*:\s*\{[^{}]*?"simpleText"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(parca)?.groupValues?.get(1)
                ?: return@forEach
            sonuc[id] = jsonCoz(baslik)
        }

        // Yeni görünüm: "lockupViewModel":{ ... "contentId":"<id>" ... "title":{"content":"…"}
        Regex(""""lockupViewModel"\s*:\s*\{(.{0,3000}?)"contentId"\s*:\s*"([A-Za-z0-9_-]{11})"""").findAll(metin).forEach { m ->
            val id = m.groupValues[2]
            if (sonuc.containsKey(id)) return@forEach
            val parca = metin.substring(m.range.first, minOf(metin.length, m.range.last + 4000))
            val baslik = Regex(""""title"\s*:\s*\{\s*"content"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(parca)?.groupValues?.get(1) ?: return@forEach
            sonuc[id] = jsonCoz(baslik)
        }

        return sonuc.map { it.key to it.value }
    }

    private fun jsonCoz(s: String): String = runCatching { jsonCevir<String>("\"$s\"") }.getOrElse { s.replace("\\\"", "\"").replace("\\u0026", "&") }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = Regex("""(?:v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1) ?: return null
        val metin   = app.get("${mainUrl}/watch?v=${videoId}&hl=tr&gl=TR", headers = basliklar, cookies = cerezler).text

        // ? ytInitialPlayerResponse → "videoDetails":{"videoId":"<id>","title":"…","lengthSeconds":"…","shortDescription":"…","author":"…"}
        val dIdx    = Regex(""""videoDetails"\s*:\s*\{\s*"videoId"\s*:\s*"$videoId"""").find(metin)?.range?.first
        val detay   = dIdx?.let { metin.substring(it, minOf(metin.length, it + 12000)) } ?: ""
        fun alan(ad: String) = Regex(""""$ad"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(detay)?.groupValues?.get(1)?.let { jsonCoz(it) }
        fun meta(ad: String) = Regex("""<meta (?:name|property)="$ad" content="([^"]*)"""").find(metin)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#39;", "'")

        // Yedek: oEmbed (bot doğrulama sayfası gelse bile başlık/kanal döner)
        val oembed   = if (alan("title").isNullOrBlank()) runCatching {
            app.get("${mainUrl}/oembed?url=${URLEncoder.encode("${mainUrl}/watch?v=${videoId}", "UTF-8")}&format=json", headers = basliklar).text
        }.getOrNull() else null
        fun oAlan(ad: String) = oembed?.let { Regex(""""$ad"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(it)?.groupValues?.get(1)?.let { v -> jsonCoz(v) } }

        val baslik   = listOf(alan("title"), oAlan("title"), meta("og:title"), meta("title"),
            Regex("""<title>(.*?)(?: - YouTube)?</title>""").find(metin)?.groupValues?.get(1))
            .firstOrNull { !it.isNullOrBlank() && it != "YouTube" } ?: return null
        val aciklama = alan("shortDescription")?.takeIf { it.isNotBlank() } ?: meta("og:description")
        val kanal    = alan("author")?.takeIf { it.isNotBlank() } ?: oAlan("author_name")
        val sure     = alan("lengthSeconds")?.toIntOrNull()?.let { it / 60 }
        val oneriler = videoListesi(metin.substringAfter("\"secondaryResults\"", "")).filter { it.first != videoId }.take(20).map { (id, b) ->
            newMovieSearchResponse(b, "${mainUrl}/watch?v=${id}", TvType.Others) { this.posterUrl = "https://i.ytimg.com/vi/${id}/hqdefault.jpg" }
        }

        return newMovieLoadResponse(baslik, "${mainUrl}/watch?v=${videoId}", TvType.Others, videoId) {
            this.plot            = aciklama
            this.posterUrl       = "https://i.ytimg.com/vi/${videoId}/hqdefault.jpg"
            this.duration        = sure
            this.recommendations = oneriler
            this.tags            = listOfNotNull(kanal)
            if (kanal != null) this.actors = listOf(ActorData(Actor(kanal)))
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("YTB", "data » $data")
        return loadExtractor("https://www.youtube.com/watch?v=${data}", subtitleCallback, callback)
    }
}

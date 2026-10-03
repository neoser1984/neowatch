package com.neowatch

import android.util.Log
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import okhttp3.MultipartBody
import org.json.JSONObject

class SetFilmIzle : MainAPI() {
    override var mainUrl              = "https://www.setfilmizle.ltd"
    override var name                 = "SetFilmIzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/film/"            to "Filmler",
        "${mainUrl}/dizi/"            to "Diziler",
        "${mainUrl}/tur/aile/"        to "Aile",
        "${mainUrl}/tur/aksiyon/"     to "Aksiyon",
        "${mainUrl}/tur/animasyon/"   to "Animasyon",
        "${mainUrl}/tur/belgesel/"    to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "${mainUrl}/tur/biyografi/"   to "Biyografi",
        "${mainUrl}/tur/dram/"        to "Dram",
        "${mainUrl}/tur/fantastik/"   to "Fantastik",
        "${mainUrl}/tur/gerilim/"     to "Gerilim",
        "${mainUrl}/tur/gizem/"       to "Gizem",
        "${mainUrl}/tur/komedi/"      to "Komedi",
        "${mainUrl}/tur/korku/"       to "Korku",
        "${mainUrl}/tur/macera/"      to "Macera",
        "${mainUrl}/tur/mini-dizi/"   to "Mini Dizi",
        "${mainUrl}/tur/muzik/"       to "Müzik",
        "${mainUrl}/tur/romantik/"    to "Romantik",
        "${mainUrl}/tur/savas/"       to "Savaş",
        "${mainUrl}/tur/spor/"        to "Spor",
        "${mainUrl}/tur/suc/"         to "Suç",
        "${mainUrl}/tur/tarih/"       to "Tarih",
        "${mainUrl}/tur/western/"     to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = if (page > 1) "${request.data}page/${page}/" else request.data
        val document = app.get(url, referer = "${mainUrl}/").document
        val home     = document.select("div.fgrid a.card-link").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val title     = this.selectFirst("span.hcard-title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        val score     = this.selectFirst("span.badge-imdb")?.text()?.trim()
        val year      = this.select("dl.hcard-kunye div").firstOrNull { it.selectFirst("dt")?.text()?.trim() == "Yıl" }
            ?.selectFirst("dd")?.text()?.trim()?.toIntOrNull()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year      = year
                this.score     = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year      = year
                this.score     = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val metin = app.get(
            "${mainUrl}/wp-admin/admin-ajax.php",
            params  = mapOf("action" to "stf_live_search", "keyword" to query),
            referer = "${mainUrl}/",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text

        val json = runCatching { JSONObject(metin) }.getOrNull() ?: return emptyList()

        return json.keys().asSequence().mapNotNull { anahtar ->
            val o     = json.optJSONObject(anahtar) ?: return@mapNotNull null
            val title = o.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href  = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val img   = o.optString("img").replace(Regex("""-\d+x\d+(\.\w+)$"""), "$1")
            val extra = o.optJSONObject("extra")
            val year  = extra?.optString("date")?.toIntOrNull()
            val score = extra?.optString("imdb")

            if (href.contains("/dizi/")) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = img
                    this.year      = year
                    this.score     = Score.from10(score)
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = img
                    this.year      = year
                    this.score     = Score.from10(score)
                }
            }
        }.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Document.bilgi(etiket: String): Element? {
        return this.select("div.fbox-info span").firstOrNull {
            it.selectFirst("b")?.text()?.trim()?.removeSuffix(":") == etiket
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "${mainUrl}/").document

        val title       = document.selectFirst("h1 span.fbox-title-tx")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.substringBefore(" izle")?.trim()
            ?: return null
        val poster      = fixUrlNull(document.selectFirst("img.fbox-cover-img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val background  = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year        = document.bilgi("Yıl")?.selectFirst("a")?.text()?.trim()?.toIntOrNull()
            ?: document.selectFirst("span.fbox-date")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val tags        = document.bilgi("Tür")?.select("a")?.map { it.text().trim() }
        val duration    = document.bilgi("Süre")?.ownText()?.filter { it.isDigit() }?.toIntOrNull()
        val score       = document.selectFirst("a.fbox-imdb b.imdb-score")?.text()?.trim()
        val trailer     = document.selectFirst("button[data-trailer]")?.attr("data-trailer")?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http")) it else "https://www.youtube.com/embed/$it"
        }
        val actors      = document.select("a.fk-k[href*='/oyuncu/']").mapNotNull { a ->
            val ad = a.selectFirst("span.fk-t b")?.text()?.trim() ?: return@mapNotNull null
            Actor(ad, fixUrlNull(a.selectFirst("img")?.attr("src"))) to a.selectFirst("span.fk-t i")?.text()?.trim()
        }
        val recommendations = document.select("a.card-link").mapNotNull { it.toSearchResult() }.filter { it.url != url }

        if (url.contains("/dizi/")) {
            val episodes = document.select("div.season-panel").flatMap { panel ->
                val sezon = panel.attr("data-season").toIntOrNull()
                panel.select("a.fep").mapNotNull { ep ->
                    val epHref  = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
                    val baslik  = ep.selectFirst("div.fep-title")?.text()?.trim() ?: ""
                    val alt     = ep.selectFirst("div.fep-sub")?.ownText()?.trim()
                    val bolum   = Regex("""(\d+)\.\s*Bölüm""").find(baslik)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""-(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    val sezonNo = sezon ?: Regex("""-(\d+)-sezon""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

                    newEpisode(epHref) {
                        this.name    = alt?.takeIf { it.isNotBlank() } ?: baslik
                        this.season  = sezonNo
                        this.episode = bolum
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = background
                this.plot                = description
                this.year                = year
                this.tags                = tags
                this.score               = Score.from10(score)
                this.duration            = duration
                this.recommendations     = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl           = poster
            this.backgroundPosterUrl = background
            this.plot                = description
            this.year                = year
            this.tags                = tags
            this.score               = Score.from10(score)
            this.duration            = duration
            this.recommendations     = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("STF", "data » $data")
        val metin    = app.get(data, referer = "${mainUrl}/").text
        val document = org.jsoup.Jsoup.parse(metin, data)

        val nonce  = Regex("""video\s*:\s*"([^"]+)"""").find(metin)?.groupValues?.get(1) ?: return false
        val postId = document.selectFirst("#stfPlayer")?.attr("data-post-id")?.takeIf { it.isNotBlank() } ?: return false
        val ajax   = Regex("""STF_AJAX\s*=\s*\{\s*url\s*:\s*"([^"]+)"""").find(metin)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: "${mainUrl}/wp-admin/admin-ajax.php"

        val kaynaklar = document.select("#stfPlayer button.fsrc[data-player-name]").map {
            it.attr("data-player-name") to it.attr("data-part-key")
        }.distinct()
        if (kaynaklar.isEmpty()) return false

        var bulundu = false
        val sayac: (ExtractorLink) -> Unit = { bulundu = true; callback.invoke(it) }

        for ((oynatici, parca) in kaynaklar) {
            runCatching {
                val govde = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("action", "get_video_url")
                    .addFormDataPart("nonce", nonce)
                    .addFormDataPart("post_id", postId)
                    .addFormDataPart("player_name", oynatici)
                    .addFormDataPart("part_key", parca)
                    .build()

                val yanit = app.post(ajax, requestBody = govde, referer = data, headers = mapOf("Origin" to mainUrl)).text
                Log.d("STF", "$oynatici » $yanit")

                val veri  = JSONObject(yanit).optJSONObject("data") ?: return@runCatching
                val akim  = veri.optJSONObject("stream")
                val etiket = if (parca.isBlank()) oynatici else "$oynatici $parca"

                when {
                    akim?.optString("type") == "bridge" && akim.optString("url").isNotBlank() -> {
                        StfKopru().getUrl(akim.optString("url"), "${mainUrl}/", subtitleCallback, sayac)
                    }
                    akim != null && akim.optString("src").isNotBlank() -> {
                        val src = akim.optString("src")
                        sayac.invoke(
                            newExtractorLink(this.name, "${this.name} - $etiket", src, if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else null) {
                                this.referer = "${mainUrl}/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        akim.optJSONArray("subtitles")?.let { altlar ->
                            for (i in 0 until altlar.length()) {
                                val a = altlar.optJSONObject(i) ?: continue
                                val dosya = a.optString("file").ifBlank { a.optString("src") }
                                if (dosya.isBlank() || a.optString("kind") == "thumbnails") continue
                                subtitleCallback.invoke(newSubtitleFile(a.optString("label").ifBlank { "Türkçe" }, dosya))
                            }
                        }
                    }
                    veri.optString("url").isNotBlank() -> {
                        loadExtractor(veri.optString("url"), "${mainUrl}/", subtitleCallback, sayac)
                    }
                }
            }.onFailure { Log.d("STF", "$oynatici hata » ${it.message}") }
        }

        return bulundu
    }
}

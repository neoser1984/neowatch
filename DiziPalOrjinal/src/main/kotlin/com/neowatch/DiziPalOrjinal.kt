package com.neowatch

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiziPalOrjinal : MainAPI() {
    override var mainUrl              = "https://dizipalorjinal12.com"
    override var name                 = "DiziPalOrjinal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/bolumler" to "Son Bölümler",
        "${mainUrl}/diziler"  to "Yabancı Diziler",
        "${mainUrl}/filmler"  to "Filmler",
        "${mainUrl}/populer"  to "Popüler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sayfali = request.data.endsWith("/diziler") || request.data.endsWith("/filmler")
        if (!sayfali && page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val url      = if (page > 1) "${request.data}?page=${page}" else request.data
        val document = app.get(url, referer = "${mainUrl}/").document

        val home = if (request.data.endsWith("/bolumler")) {
            document.select("a[href^='/bolumler/']").filter { it.selectFirst("img") != null }.mapNotNull { it.sonBolumler() }
        } else {
            document.select("a.group.block[href^='/diziler/'], a.group.block[href^='/filmler/']").mapNotNull { it.toSearchResult() }
        }.distinctBy { it.url }

        val hasNext = sayfali && document.selectFirst("a[href*='page=${page + 1}']") != null

        return newHomePageResponse(request.name, home, hasNext)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val satirlar = this.select("p")
        val name     = satirlar.getOrNull(0)?.text()?.trim() ?: return null
        val episode  = satirlar.getOrNull(1)?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "")
        val href     = fixUrlNull(this.attr("href")) ?: return null

        return newTvSeriesSearchResponse(if (episode.isNullOrBlank()) name else "$name $episode", href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(this@sonBolumler.selectFirst("img")?.attr("src"))
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("h3")?.text()?.trim() ?: this.selectFirst("img")?.attr("alt")?.trim() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val spans     = this.select("span").map { it.text().trim() }
        val year      = spans.firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()
        val score     = spans.firstOrNull { it.matches(Regex("""\d+(\.\d+)?""")) && !it.matches(Regex("""\d{4}""")) }

        return if (href.contains("/filmler/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year      = year
                this.score     = Score.from10(score)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year      = year
                this.score     = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/api/v1/search",
            params  = mapOf("q" to query, "per_page" to "30"),
            referer = "${mainUrl}/",
            headers = mapOf("Accept" to "application/json")
        ).parsedSafe<AramaYanit>() ?: return emptyList()

        return response.data.orEmpty().mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val slug  = item.slug ?: return@mapNotNull null

            if (item.type == "movie") {
                newMovieSearchResponse(title, "${mainUrl}/filmler/${slug}", TvType.Movie) {
                    this.posterUrl = item.poster
                    this.year      = item.year
                    this.score     = Score.from10(item.imdb)
                }
            } else {
                newTvSeriesSearchResponse(title, "${mainUrl}/diziler/${slug}", TvType.TvSeries) {
                    this.posterUrl = item.poster
                    this.year      = item.year
                    this.score     = Score.from10(item.imdb)
                }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        var gercekUrl = url
        var document  = app.get(url, referer = "${mainUrl}/").document

        // Son bölümler listesinden gelen bölüm bağlantısını dizi sayfasına çevir
        if (url.contains("/bolumler/")) {
            val dizi = fixUrlNull(document.selectFirst("nav a[href^='/diziler/'], a[href^='/diziler/']")?.attr("href")) ?: return null
            gercekUrl = dizi
            document  = app.get(dizi, referer = "${mainUrl}/").document
        }

        val title       = document.selectFirst("h1")?.text()?.removeSuffix(" izle")?.trim() ?: return null
        val ogImage     = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val poster      = ogImage?.takeIf { it.contains("/posters/") }
            ?: fixUrlNull(document.select("div[hidden] img, main img").firstOrNull { it.attr("src").contains("/posters/") }?.attr("src"))
            ?: ogImage
        val description = document.selectFirst("h2:containsOwn(Konusu) + p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year        = document.bilgi("Yıl")?.text()?.trim()?.toIntOrNull()
        val score       = document.bilgi("IMDb Puanı")?.text()?.trim()?.split(" ")?.firstOrNull()
        val tags        = document.bilgi("Türler")?.select("a")?.map { it.text().trim() }
        val actors      = document.select("a[href^='/oyuncu/']").mapNotNull { a ->
            val img = a.selectFirst("img") ?: return@mapNotNull null
            Actor(img.attr("alt").trim(), fixUrlNull(img.attr("src")))
        }.distinctBy { it.name }

        if (gercekUrl.contains("/filmler/")) {
            return newMovieLoadResponse(title, gercekUrl, TvType.Movie, gercekUrl) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.score     = Score.from10(score)
                addActors(actors)
            }
        }

        val episodes = document.select("a[href^='/bolumler/']").mapNotNull { a ->
            val epHref  = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val bilgi   = a.selectFirst("span")?.text()?.trim() ?: ""
            val epName  = a.select("span").getOrNull(1)?.text()?.trim()
            val season  = Regex("""(\d+)\s*\.\s*Sezon""").find(bilgi)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-s(\d+)b\d+""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            val episode = Regex("""(\d+)\s*\.\s*Bölüm""").find(bilgi)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-s\d+b(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epHref) {
                this.name    = epName
                this.season  = season
                this.episode = episode
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, gercekUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.score     = Score.from10(score)
            addActors(actors)
        }
    }

    private fun Document.bilgi(etiket: String): Element? {
        return this.select("table.detail-meta tr").firstOrNull {
            it.selectFirst("td")?.text()?.trim()?.equals(etiket, ignoreCase = true) == true
        }?.select("td")?.getOrNull(1)
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZPO", "data » $data")
        val document = app.get(data, referer = "${mainUrl}/").document

        val iframes = (document.select("iframe[src]").map { it.attr("src") } +
            listOfNotNull(document.selectFirst("meta[property=og:video]")?.attr("content")))
            .filter { it.isNotBlank() }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .distinct()

        iframes.forEach { iframe ->
            Log.d("DZPO", "iframe » $iframe")
            runCatching {
                if (VideoPlays.uygunMu(iframe)) {
                    VideoPlays().getUrl(iframe, "${mainUrl}/", subtitleCallback, callback)
                } else {
                    loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
                }
            }
        }

        return iframes.isNotEmpty()
    }

    data class AramaYanit(
        @JsonProperty("success") val success: Boolean?        = null,
        @JsonProperty("data")    val data: List<AramaItem>?   = null
    )

    data class AramaItem(
        @JsonProperty("title")       val title: String?  = null,
        @JsonProperty("slug")        val slug: String?   = null,
        @JsonProperty("year")        val year: Int?      = null,
        @JsonProperty("poster_url")  val poster: String? = null,
        @JsonProperty("imdb_rating") val imdb: Double?   = null,
        @JsonProperty("type")        val type: String?   = null
    )
}

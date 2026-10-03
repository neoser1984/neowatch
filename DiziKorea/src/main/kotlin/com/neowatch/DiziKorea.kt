package com.neowatch

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.Vidmoly
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiziKorea : MainAPI() {
    override var mainUrl              = "https://dizikorea3.com"
    override var name                 = "DiziKorea"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.AsianDrama, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/kore-dizileri-izle-dq1" to "Kore Dizileri",
        "${mainUrl}/kore-filmleri-izle-dq"  to "Kore Filmleri",
        "${mainUrl}/cin-dizileri"           to "Çin Dizileri",
        "${mainUrl}/japon-dizileri"         to "Japon Dizileri",
        "${mainUrl}/tayland-dizileri"       to "Tayland Dizileri",
        "${mainUrl}/tayvan-dizileri"        to "Tayvan Dizileri",
        "${mainUrl}/filmler"                to "Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = if (page <= 1) request.data else "${request.data}/sayfa/${page}"
        val document = app.get(url).document
        val home     = document.select("div.content-grid a.poster-card").mapNotNull { it.toSearchResult() }
        val hasNext  = document.selectFirst("a[href*='/sayfa/${page + 1}']") != null

        return newHomePageResponse(request.name, home, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("span.poster-card-title")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val score     = this.selectFirst("span.poster-card-rating span:last-child")?.text()?.trim()

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score     = Score.from10(score)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = posterUrl
                this.score     = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/ara",
            params  = mapOf("q" to query),
            referer = "${mainUrl}/",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<AraResponse>() ?: return emptyList()

        return response.items.mapNotNull { item ->
            val title  = item.title ?: return@mapNotNull null
            val href   = fixUrlNull(item.url) ?: return@mapNotNull null
            val poster = fixUrlNull(item.poster)

            if (item.type == "movie" || href.contains("/film/")) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year      = item.year
                    this.score     = Score.from10(item.imdbRating)
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                    this.posterUrl = poster
                    this.year      = item.year
                    this.score     = Score.from10(item.imdbRating)
                }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        return if (url.contains("/film/")) loadMovie(url, document) else loadSeries(url, document)
    }

    private fun Document.actors(): List<Pair<Actor, String?>> {
        return this.select("div.series-cast-grid a.cast-card").mapNotNull {
            val name = it.selectFirst("span.cast-name")?.text()?.trim() ?: return@mapNotNull null
            Actor(name, it.selectFirst("img")?.attr("src")) to it.selectFirst("span.cast-role")?.text()?.trim()
        }
    }

    private suspend fun loadSeries(url: String, document: Document): LoadResponse? {
        val title       = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.series-hero-poster img")?.attr("src"))
        val background  = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("div.series-about-body")?.text()?.trim()
        val badges      = document.select("div.series-meta span.meta-badge").map { it.text().trim() }
        val year        = badges.firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()
        val score       = document.selectFirst("span.meta-rating")?.text()?.replace("★", "")?.trim()
        val tags        = document.select("div.series-meta a.meta-badge").map { it.text().trim() }
        val trailer     = document.selectFirst("button.btn-trailer")?.attr("data-trailer")

        val episodes = mutableListOf<Episode>()
        document.select("div.episode-list").forEach { list ->
            val season = list.attr("data-season").toIntOrNull()

            list.select("a.episode-item").forEach ep@{ item ->
                val epHref    = fixUrlNull(item.attr("href")) ?: return@ep
                val epNumber  = item.selectFirst("span.ep-number")?.text()?.trim()?.toIntOrNull()
                    ?: Regex("""bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = season ?: Regex("""sezon-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                val epTitle   = item.selectFirst("span.ep-title")?.text()?.trim()
                val epDate    = item.selectFirst("span.ep-date")?.text()?.trim()

                episodes.add(newEpisode(epHref) {
                    this.name    = epTitle
                    this.season  = epSeason
                    this.episode = epNumber
                    this.addDate(epDate, "dd.MM.yyyy")
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl           = poster
            this.backgroundPosterUrl = background
            this.year                = year
            this.plot                = description
            this.tags                = tags
            this.score               = Score.from10(score)
            addActors(document.actors())
            addTrailer(trailer)
        }
    }

    private suspend fun loadMovie(url: String, document: Document): LoadResponse? {
        val title       = document.selectFirst("h1.watch-title, h1.series-title")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("img.sidebar-poster, div.series-hero-poster img")?.attr("src"))
        val background  = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("div.series-about-body")?.text()?.trim()
        val year        = document.selectFirst("span.watch-ep-date")?.text()?.trim()?.toIntOrNull()
        val badges      = document.select("div.watch-meta-row span.meta-badge").map { it.text().trim() }
        val duration    = badges.firstOrNull { it.endsWith("dk") }?.replace("dk", "")?.trim()?.toIntOrNull()
        val score       = document.selectFirst("span.meta-rating")?.text()?.replace("★", "")?.trim()
        val tags        = document.select("div.watch-meta-row a.meta-badge").map { it.text().replace(" Filmleri", "").trim() }
        val trailer     = document.selectFirst("button.btn-trailer")?.attr("data-trailer")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl           = poster
            this.backgroundPosterUrl = background
            this.year                = year
            this.plot                = description
            this.tags                = tags
            this.duration            = duration
            this.score               = Score.from10(score)
            addActors(document.actors())
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZK", "data » $data")
        val document = app.get(data).document

        val sources = document.select("div.player-source iframe").mapNotNull {
            fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") })
        }.distinct()

        sources.forEach { iframe ->
            Log.d("DZK", "iframe » $iframe")
            runCatching { linkCoz(iframe, subtitleCallback, callback) }
        }

        return sources.isNotEmpty()
    }

    private suspend fun linkCoz(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val host = url.substringAfter("://").substringBefore("/")

        when {
            FirePlayer.isFirePlayer(url) -> FirePlayer().getUrl(url, "${mainUrl}/", subtitleCallback, callback)
            host.contains("vidmoly")     -> Vidmoly().getUrl(url, "${mainUrl}/", subtitleCallback, callback)
            host.startsWith("byse")      -> ByseSX().getUrl(url, "${mainUrl}/", subtitleCallback, callback)
            else                         -> loadExtractor(url, "${mainUrl}/", subtitleCallback, callback)
        }
    }

    data class AraResponse(
        @JsonProperty("success") val success: Boolean?   = null,
        @JsonProperty("items")   val items: List<AraItem> = emptyList()
    )

    data class AraItem(
        @JsonProperty("title")       val title: String?      = null,
        @JsonProperty("url")         val url: String?        = null,
        @JsonProperty("poster")      val poster: String?     = null,
        @JsonProperty("year")        val year: Int?          = null,
        @JsonProperty("type")        val type: String?       = null,
        @JsonProperty("imdb_rating") val imdbRating: String? = null
    )
}

package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FilmMakinesi : MainAPI() {
    override var mainUrl              = "https://filmmakinesi.to"
    override var name                 = "FilmMakinesi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    // ! CloudFlare bypass
    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler-1/"                                to "Son Filmler",
        "${mainUrl}/yabanci-dizi-izle-1/"                      to "Diziler",
        "${mainUrl}/film-izle/olmeden-izlenmesi-gerekenler-fm1/" to "Ölmeden İzle",
        "${mainUrl}/tur/aksiyon-fmy54y/film/"                  to "Aksiyon",
        "${mainUrl}/tur/bilim-kurgu-fm3/film/"                 to "Bilim Kurgu",
        "${mainUrl}/tur/macera-fm1/film/"                      to "Macera",
        "${mainUrl}/tur/komedi-fm1/film/"                      to "Komedi",
        "${mainUrl}/tur/romantik-fm1/film/"                    to "Romantik",
        "${mainUrl}/tur/belgesel/film/"                        to "Belgesel",
        "${mainUrl}/tur/fantastik-fm1/film/"                   to "Fantastik",
        "${mainUrl}/tur/polisiye/film/"                        to "Polisiye Suç",
        "${mainUrl}/tur/korku-fm2/film/"                       to "Korku",
        "${mainUrl}/ulke/turkiye-fm4/"                         to "Yerli Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = if (page <= 1) request.data else "${request.data}sayfa/${page}/"
        val document = app.get(url, referer = "${mainUrl}/").document
        val home     = document.select("div.item-relative > a.item").mapNotNull { it.toSearchResult() }
        val hasNext  = document.selectFirst("a[href*='/sayfa/${page + 1}/']") != null

        return newHomePageResponse(request.name, home, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.attr("data-title").ifBlank { this.selectFirst("div.title")?.text() ?: "" }.trim()
        if (title.isBlank()) return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val score     = this.attr("data-score")
        val year      = this.selectFirst("div.info span")?.text()?.trim()?.take(4)?.toIntOrNull()

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
        val document = app.get("${mainUrl}/arama/", params = mapOf("s" to query), referer = "${mainUrl}/").document

        return document.select("div.item-relative > a.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "${mainUrl}/").document

        val title           = document.selectFirst("h1.title")?.ownText()?.removeSuffix(" izle")?.trim()
            ?: document.selectFirst("h1")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("img.cover-img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description     = document.selectFirst("div.info-description p")?.text()?.trim()
        val year            = document.selectFirst("h1.title span.date a")?.text()?.trim()?.toIntOrNull()
        val score           = document.selectFirst("div.imdb b")?.text()?.trim()
        val tags            = document.select("div.info div.type a").map { it.text().trim() }
        val duration        = document.selectFirst("div.info div.time")?.text()?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val trailer         = document.selectFirst("a.trailer-button")?.attr("data-video_url")?.takeIf { it.isNotBlank() }
            ?: document.select("iframe[data-src*='youtube']").attr("data-src").takeIf { it.isNotBlank() }
        val actors          = document.select("a.cast").mapNotNull {
            val name = it.selectFirst("div.cast-name")?.text()?.trim() ?: return@mapNotNull null
            Actor(name, fixUrlNull(it.selectFirst("img")?.attr("src")))
        }
        val recommendations = document.select("div.item-relative > a.item").mapNotNull { it.toSearchResult() }

        if (url.contains("/dizi/")) {
            val episodes = document.select("#seasons a.item-ep, a.item-ep").mapNotNull {
                val epHref  = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                if (!epHref.contains("/bolum-")) return@mapNotNull null
                val season  = Regex("""/sezon-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                val episode = Regex("""/bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = it.selectFirst("div.ep-details span")?.text()?.trim()
                        ?: it.selectFirst("div.ep-title")?.text()?.trim()
                    this.season  = season
                    this.episode = episode
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.duration        = duration
                this.score           = Score.from10(score)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.duration        = duration
            this.score           = Score.from10(score)
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FLMM", "data » $data")
        val document = app.get(data, referer = "${mainUrl}/").document

        val kaynaklar = (document.select("div.video-parts a[data-video_url]").map { it.attr("data-video_url") } +
            document.select("div.player--area iframe, div.player-div iframe").map { it.attr("data-src").ifBlank { it.attr("src") } })
            .filter { it.isNotBlank() && !it.contains("youtube") }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .distinct()

        kaynaklar.forEach { iframe ->
            Log.d("FLMM", "iframe » $iframe")
            runCatching {
                val host = iframe.substringAfter("://").substringBefore("/")
                if (host.startsWith("closeload") || host.startsWith("rapid")) {
                    GizliJsOynatici().getUrl(iframe, "${mainUrl}/", subtitleCallback, callback)
                } else {
                    loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
                }
            }
        }

        return kaynaklar.isNotEmpty()
    }
}

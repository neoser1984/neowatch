// ! Kaynak: https://github.com/hexated/cloudstream-extensions-hexated (Hdfilmcehennemi)

package com.neowatch

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Yeni Eklenen Filmler",
        "${mainUrl}/yabancidiziizle-5"                    to "Yeni Eklenen Diziler",
        "${mainUrl}/category/tavsiye-filmler-izle3"       to "Tavsiye Filmler",
        "${mainUrl}/imdb-7-puan-uzeri-filmler-2"          to "IMDB 7+ Filmler",
        "${mainUrl}/en-cok-yorumlananlar-1"               to "En Çok Yorumlananlar",
        "${mainUrl}/en-cok-begenilen-filmleri-izle"       to "En Çok Beğenilenler",
        "${mainUrl}/tur/aile-filmleri-izleyin-6"          to "Aile Filmleri",
        "${mainUrl}/tur/aksiyon-filmleri-izleyin-8"       to "Aksiyon Filmleri",
        "${mainUrl}/tur/animasyon-filmlerini-izleyin-4"   to "Animasyon Filmleri",
        "${mainUrl}/tur/belgesel-filmlerini-izle-1"       to "Belgesel Filmleri",
        "${mainUrl}/tur/bilim-kurgu-filmlerini-izleyin-2" to "Bilim Kurgu Filmleri",
        "${mainUrl}/tur/komedi-filmlerini-izleyin-1"      to "Komedi Filmleri",
        "${mainUrl}/tur/korku-filmlerini-izle-9"         to "Korku Filmleri",
        "${mainUrl}/tur/romantik-filmleri-izle-1"         to "Romantik Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val document = app.get(request.data, referer = "${mainUrl}/").document
        val home     = document.select("div.section-content a.poster").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("strong.poster-title")?.text() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response      = app.get(
            "${mainUrl}/search",
            params  = mapOf("q" to query),
            referer = "${mainUrl}/",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()
        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val title     = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href      = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))

            val poster    = posterUrl?.replace("/thumb/", "/list/")
            searchResults.add(
                if (href.contains("/dizi/") || document.selectFirst("span.type")?.text()?.contains("Dizi") == true) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
                }
            )
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val poster      = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags        = document.select("div.post-info-genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val rating      = document.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()
        val actors      = document.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")!!.text(), it.select("img").attr("data-src"))
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
                val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
                val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(it.selectFirst("img")?.attr("src"))

                newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                    this.posterUrl = recPosterUrl
                }
            }

        return if (tvType == TvType.TvSeries) {
            val trailer  = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName    = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score           = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score           = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit ): Boolean {
        Log.d("HDCH", "data » $data")
        val document = app.get(data, referer = "${mainUrl}/").document
        var bulundu  = false

        document.select("div.alternative-links").forEach { element ->
            val langCode = element.attr("data-lang").uppercase()

            element.select("button.alternative-link").forEach { button ->
                val source  = button.text().replace("(HDrip Xbet)", "").trim() + " $langCode"
                val videoID = button.attr("data-video").ifBlank { return@forEach }

                val apiGet = app.get(
                    "${mainUrl}/video/$videoID/",
                    headers = mapOf(
                        "Content-Type"     to "application/json",
                        "X-Requested-With" to "fetch"
                    ),
                    referer = data
                ).text

                val iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "") ?: return@forEach
                Log.d("HDCH", "$source » $videoID » $iframe")
                bulundu = true

                runCatching {
                    if (iframe.contains("/video/embed/") || iframe.contains("/rplayer/") || iframe.contains("/playerr/")) {
                        GizliJsOynatici().getUrl(iframe, "${mainUrl}/", subtitleCallback, callback)
                    } else {
                        loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
                    }
                }
            }
        }

        return bulundu
    }

    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )
}
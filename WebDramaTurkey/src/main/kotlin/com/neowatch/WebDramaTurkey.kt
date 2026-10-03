package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.Vidmoly
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class WebDramaTurkey : MainAPI() {
    override var mainUrl              = "https://webdramaturkey2.com"
    override var name                 = "WebDramaTurkey"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.AsianDrama, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler"   to "Diziler",
        "${mainUrl}/filmler"   to "Filmler",
        "${mainUrl}/animeler"  to "Animeler",
        "${mainUrl}/programlar" to "Programlar",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}?page=${page}", referer = "${mainUrl}/").document
        val home     = document.select("div.list-movie").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        val hasNext  = document.selectFirst("a[href*='page=${page + 1}']") != null

        return newHomePageResponse(request.name, home, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("a.list-title")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.selectFirst("a.list-media, a.list-title")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.media")?.attr("data-src"))

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val sorgu    = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val document = app.get("${mainUrl}/arama/${sorgu}", referer = "${mainUrl}/").document

        return document.select("div.list-movie").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Document.ozellik(etiket: String): String? {
        return this.select("div.featured-attr").firstOrNull {
            it.selectFirst("div.attr")?.text()?.trim()?.equals(etiket, ignoreCase = true) == true
        }?.selectFirst("div.text")?.text()?.trim()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "${mainUrl}/").document

        val title       = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.col-md-4 div.media")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("div.detail-attr div.text-content")?.text()?.trim()
        val year        = document.ozellik("Yayın yılı")?.toIntOrNull()
        val tags        = document.select("div.categories a").map { it.text().trim() }
        val actors      = document.select("#actorsList a").mapNotNull { a ->
            val name = a.selectFirst("div.list-caption")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Actor(name, fixUrlNull(a.selectFirst("div.media")?.attr("data-src")))
        }
        val recommendations = document.select("div.list-scrollable div.list-movie").mapNotNull { it.toSearchResult() }

        val episodes = document.select("div.episodes a[href]").mapNotNull { a ->
            val epHref  = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val bolum   = Regex("""/(\d+)-sezon/(\d+)-bolum""").find(epHref)
            val epName  = a.selectFirst("div.name")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("div.episode")?.text()?.trim()

            newEpisode(epHref) {
                this.name    = epName
                this.season  = bolum?.groupValues?.get(1)?.toIntOrNull()
                this.episode = bolum?.groupValues?.get(2)?.toIntOrNull()
            }
        }.distinctBy { it.data }

        if (episodes.isEmpty() || url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.recommendations = recommendations
                addActors(actors)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("WDT", "data » $data")
        val document = app.get(data, referer = "${mainUrl}/").document
        val kaynaklar = document.select("button.dropdown-source[data-embed]").map { it.attr("data-embed") to it.selectFirst("span.name")?.text()?.trim() }
            .ifEmpty { document.select("[data-embed]").map { it.attr("data-embed") to null } }
            .filter { it.first.isNotBlank() }
            .distinctBy { it.first }

        var bulundu = false
        for ((id, isim) in kaynaklar) {
            val yanit = app.post(
                "${mainUrl}/ajax/embed",
                data    = mapOf("id" to id),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            )
            if (yanit.code == 429) break // ? Çok fazla istek; kalan kaynaklar atlanır

            var iframe = Jsoup.parse(yanit.text).selectFirst("iframe")?.attr("src")?.let { fixUrl(it) } ?: continue

            // ? Site kendi video.php sayfası üzerinden asıl oynatıcıyı gömer
            if (iframe.contains("video.php")) {
                iframe = app.get(iframe, referer = data).document.selectFirst("iframe")?.attr("src")?.let { fixUrl(it) } ?: continue
            }
            Log.d("WDT", "$isim » $iframe")

            bulundu = true
            runCatching { linkCoz(iframe, subtitleCallback, callback) }
        }

        return bulundu
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
}

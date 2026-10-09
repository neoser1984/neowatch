package com.neowatch

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiziPalGuncel : MainAPI() {
    override var mainUrl              = "https://dizipal1588.com"
    override var name                 = "DiziPalGuncel"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // ! CloudFlare bypass
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/yeni-eklenen-dizi-bolumler" to "Son Bölümler",
        "${mainUrl}/yabanci-dizi-izle"          to "Yeni Diziler",
        "${mainUrl}/hd-film-izle"               to "Yeni Filmler",
        "${mainUrl}/anime"                      to "Anime",
        "${mainUrl}/kanal/netflix"              to "Netflix",
        "${mainUrl}/kanal/exxen"                to "Exxen",
        "${mainUrl}/kanal/disney"               to "Disney+",
        "${mainUrl}/kanal/amazon"               to "Amazon Prime",
        "${mainUrl}/kanal/apple-tv"             to "Apple TV+",
        "${mainUrl}/kanal/max"                  to "Max",
        "${mainUrl}/kanal/hulu"                 to "Hulu",
        "${mainUrl}/kanal/tod"                  to "TOD",
        "${mainUrl}/kanal/tabii"                to "Tabii",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Site sayfalamayı JavaScript ile yaptığı için yalnızca ilk sayfa listelenir
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val document = app.get(request.data, referer = "${mainUrl}/").document
        val home     = if (request.data.contains("/yeni-eklenen-dizi-bolumler")) {
            document.select("a[href*='/bolum/']").mapNotNull { it.sonBolumler() }.distinctBy { it.url }
        } else {
            document.select("div.prm-borderb > a").mapNotNull { it.toSearchResult() }
        }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.posterAl(): String? {
        val img = this.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("data-srcset").substringBefore(" ") }.ifBlank { img.attr("src") }
        return if (src.startsWith("data:")) null else fixUrlNull(src)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name    = this.selectFirst("h2")?.text()?.trim() ?: return null
        val episode = this.selectFirst("h2 + div")?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "")
        val href    = fixUrlNull(this.attr("href")) ?: return null

        return newTvSeriesSearchResponse(if (episode.isNullOrBlank()) name else "$name $episode", href, TvType.TvSeries) {
            this.posterUrl = this@sonBolumler.posterAl()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title").removeSuffix(" izle").trim().ifBlank { this.selectFirst("img")?.attr("alt")?.trim() ?: "" }
        if (title.isBlank()) return null
        val href  = fixUrlNull(this.attr("href")) ?: return null

        return if (href.contains("/movies/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = this@toSearchResult.posterAl() }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = this@toSearchResult.posterAl() }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val anaSayfa = app.get("${mainUrl}/", referer = "${mainUrl}/").document
        val form     = anaSayfa.selectFirst("form[data-action='/bg/searchcontent']") ?: return emptyList()
        val cKey     = form.selectFirst("input[name=cKey]")?.attr("value") ?: return emptyList()
        val cValue   = form.selectFirst("input[name=cValue]")?.attr("value") ?: return emptyList()

        val response = app.post(
            "${mainUrl}/bg/searchcontent",
            data    = mapOf("cKey" to cKey, "cValue" to cValue, "searchterm" to query),
            referer = "${mainUrl}/",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<AramaYanit>() ?: return emptyList()

        return response.data?.result.orEmpty().mapNotNull { item ->
            val title = item.name ?: return@mapNotNull null
            val slug  = item.slug ?: return@mapNotNull null
            val href  = fixUrl(if (slug.startsWith("/")) slug else "/$slug")

            if (item.type.equals("Movies", ignoreCase = true) || item.type.equals("Movie", ignoreCase = true) || slug.startsWith("movies")) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = item.poster
                    this.year      = item.year
                    this.score     = Score.from10(item.imdb)
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
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
        if (url.contains("/bolum/")) {
            val dizi = fixUrlNull(document.selectFirst("a[href*='/series/']")?.attr("href")) ?: return null
            gercekUrl = dizi
            document  = app.get(dizi, referer = "${mainUrl}/").document
        }

        return if (gercekUrl.contains("/movies/")) loadMovie(gercekUrl, document) else loadSeries(gercekUrl, document)
    }

    private fun Document.bilgi(etiket: String): Element? {
        return this.select("ul.rigth-content li").firstOrNull { it.text().trim().startsWith(etiket) }
    }

    private fun Document.oyuncular(): List<Pair<Actor, String?>> {
        return this.select("span.actor-item").mapNotNull {
            val name  = it.selectFirst("span.name")?.text()?.trim() ?: return@mapNotNull null
            val image = it.parent()?.selectFirst("img")?.attr("src")
            Actor(name, image) to it.selectFirst("span.role")?.text()?.trim()
        }
    }

    private suspend fun loadSeries(url: String, document: Document): LoadResponse? {
        val title       = document.selectFirst("#router-view h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("#router-view img")?.attr("src"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year        = document.bilgi("Gösterim Yılı")?.text()?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val score       = document.bilgi("IMDB Puanı")?.text()?.substringAfter("IMDB Puanı")?.trim()
        val duration    = document.bilgi("Süre")?.text()?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val tags        = document.bilgi("Kategoriler")?.let { li ->
            li.select("a").map { it.text().trim() }.ifEmpty { li.text().substringAfter("Kategoriler").trim().split(" ") }
        }
        val trailer     = document.selectFirst("a[href*='youtube.com'], a[href*='youtu.be']")?.attr("href")

        val episodes = document.select("a[href*='/bolum/']").filter { it.selectFirst("h2") != null }.mapNotNull {
            val epHref   = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
            val sxe      = Regex("""-(\d+)x(\d+)""").find(epHref)
            val epInfo   = it.selectFirst("h2 + div")?.text()?.trim()

            newEpisode(epHref) {
                this.name    = epInfo
                this.season  = sxe?.groupValues?.get(1)?.toIntOrNull()
                this.episode = sxe?.groupValues?.get(2)?.toIntOrNull()
            }
        }.distinctBy { it.data }

        val tvType = if (url.contains("/anime") || tags?.any { it.contains("Anime", true) } == true) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            this.score     = Score.from10(score)
            addActors(document.oyuncular())
            addTrailer(trailer)
        }
    }

    private suspend fun loadMovie(url: String, document: Document): LoadResponse? {
        val title       = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster      = Regex("""iframeBeforeVideoImage\s*=\s*'([^']+)'""").find(document.html())?.groupValues?.get(1)
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val score       = document.selectFirst("div.vote")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.score     = Score.from10(score)
            addActors(document.oyuncular())
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZPG", "data » $data")
        val document = app.get(data, referer = "${mainUrl}/").document
        val sifreli  = document.selectFirst("[data-rm-k]")?.text() ?: return false
        val cozulmus = RmkCoz.coz(sifreli) ?: return false
        val iframe   = if (cozulmus.startsWith("//")) "https:$cozulmus" else cozulmus
        Log.d("DZPG", "iframe » $iframe")

        // dplayer / pichive tipi oynatıcılar (iframe.php?v=...) ContentX altyapısını kullanır
        if (ContentXGenel.uygunMu(iframe)) {
            ContentXGenel().getUrl(iframe, "${mainUrl}/", subtitleCallback, callback)
            return true
        }

        val iSource = app.get(iframe, referer = "${mainUrl}/").text
        val m3uLink = Regex("""file\s*:\s*"([^"]+)""").find(iSource)?.groupValues?.get(1)
        if (m3uLink == null) {
            return loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        Regex(""""subtitle"\s*:\s*"([^"]+)""").find(iSource)?.groupValues?.get(1)?.split(",")?.forEach {
            val subLang = it.substringAfter("[").substringBefore("]")
            val subUrl  = it.replace("[${subLang}]", "").trim()
            if (subUrl.isNotBlank()) subtitleCallback.invoke(newSubtitleFile(subLang, fixUrl(subUrl)))
        }

        callback.invoke(
            newExtractorLink(this.name, this.name, m3uLink, if (m3uLink.contains(".m3u8")) ExtractorLinkType.M3U8 else null) {
                this.referer = "${mainUrl}/"
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }

    data class AramaYanit(
        @JsonProperty("data") val data: AramaData? = null
    )

    data class AramaData(
        @JsonProperty("state")  val state: Boolean?          = null,
        @JsonProperty("result") val result: List<AramaItem>? = null
    )

    data class AramaItem(
        @JsonProperty("object_name")               val name: String?   = null,
        @JsonProperty("used_slug")                 val slug: String?   = null,
        @JsonProperty("used_type")                 val type: String?   = null,
        @JsonProperty("object_release_year")       val year: Int?      = null,
        @JsonProperty("object_related_imdb_point") val imdb: Double?   = null,
        @JsonProperty("object_poster_url")         val poster: String? = null
    )
}

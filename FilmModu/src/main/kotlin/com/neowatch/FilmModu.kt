package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FilmModu : MainAPI() {
    override var mainUrl              = "https://filmmodu.news"
    override var name                 = "FilmModu"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler"                  to "Son Filmler",
        "${mainUrl}/diziler"                  to "Son Diziler",
        "${mainUrl}/tur/aile"                 to "Aile",
        "${mainUrl}/tur/aksiyon"              to "Aksiyon",
        "${mainUrl}/tur/animasyon"            to "Animasyon",
        "${mainUrl}/tur/belgesel"             to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu"          to "Bilim-Kurgu",
        "${mainUrl}/tur/dram"                 to "Dram",
        "${mainUrl}/tur/fantastik"            to "Fantastik",
        "${mainUrl}/tur/gerilim"              to "Gerilim",
        "${mainUrl}/tur/gizem"                to "Gizem",
        "${mainUrl}/tur/komedi"               to "Komedi",
        "${mainUrl}/tur/korku"                to "Korku",
        "${mainUrl}/tur/macera"               to "Macera",
        "${mainUrl}/tur/muzik"                to "Müzik",
        "${mainUrl}/tur/olmeden-once-izle"    to "Ölmeden Önce İzle",
        "${mainUrl}/tur/romantik"             to "Romantik",
        "${mainUrl}/tur/savas"                to "Savaş",
        "${mainUrl}/tur/suc"                  to "Suç",
        "${mainUrl}/tur/tarih"                to "Tarih",
        "${mainUrl}/tur/tv-film"              to "TV film",
        "${mainUrl}/tur/western"              to "Vahşi Batı",
        "${mainUrl}/platform/netflix"         to "Netflix",
        "${mainUrl}/platform/amazon-prime"    to "Amazon Prime",
        "${mainUrl}/platform/disney"          to "Disney+",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sayfali = request.data.endsWith("/filmler") || request.data.endsWith("/diziler")
        if (!sayfali && page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val url      = if (page > 1) "${request.data}?page=${page}" else request.data
        val document = app.get(url, referer = "${mainUrl}/").document
        val home     = document.select("a.group.block[href*='/film/'], a.group.block[href*='/dizi/']").mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }
        val hasNext  = sayfali && document.selectFirst("a[href*='page=${page + 1}']") != null

        return newHomePageResponse(request.name, home, hasNext)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("h3")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.removeSuffix(" izle")?.removeSuffix(" Film")?.removeSuffix(" Dizi")?.trim()
            ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val year      = this.selectFirst("h3 + p")?.text()?.trim()?.take(4)?.toIntOrNull()
        val score     = this.selectFirst("span.badge-rating")?.text()?.trim()

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
        val document = app.get("${mainUrl}/ara", params = mapOf("q" to query), referer = "${mainUrl}/").document

        return document.select("a.group.block[href*='/film/'], a.group.block[href*='/dizi/']").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Document.bilgi(etiket: String): String? {
        return this.select("div.rounded-card").firstOrNull {
            it.selectFirst("p")?.text()?.trim()?.equals(etiket, ignoreCase = true) == true
        }?.select("p")?.getOrNull(1)?.text()?.trim()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "${mainUrl}/").document

        val title       = document.selectFirst("h1")?.ownText()?.trim()?.ifBlank { null }
            ?: document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.select("img").firstOrNull { it.attr("src").contains("/posters/") }?.attr("src"))
        val background  = fixUrlNull(document.select("img").firstOrNull { it.attr("src").contains("/backdrops/") }?.attr("src"))
        val description = document.selectFirst("p.mt-5")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val tags        = document.select("a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct().take(6)
        val score       = document.bilgi("IMDB Puanı")
        val duration    = document.bilgi("Süre")?.let { sure ->
            val saat   = Regex("""(\d+)\s*sa""").find(sure)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val dakika = Regex("""(\d+)\s*dk""").find(sure)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            (saat * 60 + dakika).takeIf { it > 0 }
        }
        val actors      = document.select("#cast-grid a[href*='/oyuncu/']").mapNotNull { a ->
            val ps   = a.select("p")
            val name = ps.getOrNull(0)?.text()?.trim() ?: return@mapNotNull null
            Actor(name, fixUrlNull(a.selectFirst("img")?.attr("src"))) to ps.getOrNull(1)?.text()?.trim()
        }

        if (url.contains("/dizi/")) {
            val sezonlar = document.select("a[href*='?sezon=']").mapNotNull { fixUrlNull(it.attr("href"))?.substringBefore("#") }.distinct()
            val sayfalar = if (sezonlar.isEmpty()) listOf(document) else sezonlar.map { sezonUrl ->
                if (sezonUrl.endsWith("sezon=1")) document else app.get(sezonUrl, referer = url).document
            }

            val episodes = sayfalar.flatMap { sayfa ->
                sayfa.select("a[href*='/bolum-']").filter { it.selectFirst("p") != null }.mapNotNull { a ->
                    val epHref  = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                    newEpisode(epHref) {
                        this.name      = a.selectFirst("p")?.text()?.trim()
                        this.season    = Regex("""/sezon-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                        this.episode   = Regex("""/bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                        this.posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src"))
                    }
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = background
                this.plot                = description
                this.tags                = tags
                this.score               = Score.from10(score)
                addActors(actors)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl           = poster
            this.backgroundPosterUrl = background
            this.plot                = description
            this.tags                = tags
            this.duration            = duration
            this.score               = Score.from10(score)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FLMMD", "data » $data")
        val document = app.get(data, referer = "${mainUrl}/").document
        var bulundu  = false

        // ? Pilavyer oynatıcısı: <div data-pv="..."> + .../assets/js/core.js
        val oynaticiBase = document.select("script[src]").map { it.attr("src") }
            .firstOrNull { it.contains("/assets/js/core.js") || it.contains("/e/c.js") }
            ?.replace(Regex("""/(?:e/c|assets/js/core)\.js.*$"""), "")

        document.select("[data-pv]").forEach { div ->
            val slug = div.attr("data-pv").ifBlank { return@forEach }
            val base = oynaticiBase ?: return@forEach
            bulundu  = true
            runCatching { Pilavyer().getUrl("$base/assets/js/s.php?s=$slug", "${mainUrl}/", subtitleCallback, callback) }
        }

        // ? Diğer gömülü oynatıcılar
        document.select("div[data-player] iframe[src], iframe[data-src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") }) ?: return@forEach
            if (src.contains("youtube")) return@forEach
            bulundu = true
            runCatching { loadExtractor(src, "${mainUrl}/", subtitleCallback, callback) }
        }

        return bulundu
    }
}

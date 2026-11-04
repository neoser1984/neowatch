package com.neowatch

import android.util.Log
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import android.util.Base64

class KultFilmler : MainAPI() {
    override var mainUrl              = "https://kultfilmler.net"
    override var name                 = "KultFilmler"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    // ! Site yavaş; ana sayfa kategorilerini sırayla yükle
    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

    override val mainPage = mainPageOf(
        "${mainUrl}/film-arsivi/?"                              to "Son Filmler",
        "${mainUrl}/dizi-arsivi/?"                              to "Diziler",
        "${mainUrl}/film-arsivi/?tur=aksiyon-filmleri-izle&"     to "Aksiyon",
        "${mainUrl}/film-arsivi/?tur=bilim-kurgu-filmleri-izle&" to "Bilim Kurgu",
        "${mainUrl}/film-arsivi/?tur=dram-filmleri-izle&"        to "Dram",
        "${mainUrl}/film-arsivi/?tur=gerilim-filmleri-izle&"     to "Gerilim",
        "${mainUrl}/film-arsivi/?tur=gizem-filmleri-izle&"       to "Gizem",
        "${mainUrl}/film-arsivi/?tur=kara-filmleri-izle&"        to "Kara Film",
        "${mainUrl}/film-arsivi/?tur=komedi-filmleri-izle&"      to "Komedi",
        "${mainUrl}/film-arsivi/?tur=korku-filmleri-izle&"       to "Korku",
        "${mainUrl}/film-arsivi/?tur=macera-filmleri-izle&"      to "Macera",
        "${mainUrl}/film-arsivi/?tur=romantik-filmleri-izle&"    to "Romantik",
        "${mainUrl}/film-arsivi/?tur=savas-filmleri-izle&"       to "Savaş",
        "${mainUrl}/film-arsivi/?tur=suc-filmleri-izle&"         to "Suç",
        "${mainUrl}/film-arsivi/?tur=yerli-filmleri-izle&"       to "Yerli",
        "${mainUrl}/film-arsivi/?tur=kisa-film-izle&"            to "Kısa Metrajlı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}sayfa=${page}", referer = "${mainUrl}/").document
        val home     = document.select("a.mcard, div.movie-box").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(request.name, home, home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Yeni tema: <a class="mcard" href> … <h3>Başlık</h3> ; eski tema: div.movie-box
        val a         = if (this.tagName() == "a") this else this.selectFirst("div.name a, a[href]") ?: return null
        val title     = (this.selectFirst("h3")?.text() ?: this.selectFirst("div.name a")?.text())?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href      = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        val year      = this.selectFirst("span.pyear")?.text()?.trim()?.toIntOrNull()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl; this.year = year }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl; this.year = year }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val sorgu    = URLEncoder.encode(query, "UTF-8")
        val document = app.get("${mainUrl}/?s=${sorgu}", referer = "${mainUrl}/").document

        return document.select("a.mcard, div.movie-box").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Document.ldNesne(vararg tipler: String): String? =
        this.select("script[type=application/ld+json]").map { it.data() }
            .firstOrNull { d -> tipler.any { Regex(""""@type"\s*:\s*"$it"""").containsMatchIn(d) } }

    private fun ldAlan(ld: String?, alan: String): String? =
        ld?.let { Regex(""""$alan"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(it)?.groupValues?.get(1)?.replace("\\/", "/")?.replace("\\\"", "\"") }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "${mainUrl}/").document
        val diziMi   = url.contains("/dizi/")
        val ld       = document.ldNesne(if (diziMi) "TVSeries" else "Movie")

        val title       = document.selectFirst("h1.vtitle, h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: ldAlan(ld, "name") ?: return null
        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content")) ?: fixUrlNull(ldAlan(ld, "image"))
        val description = ldAlan(ld, "description") ?: document.selectFirst("div.description")?.text()?.trim()
        val year        = ldAlan(ld, "datePublished")?.take(4)?.toIntOrNull()
            ?: document.selectFirst("a[href*='/yapim/']")?.text()?.trim()?.toIntOrNull()
        val rating      = ld?.let { Regex(""""ratingValue"\s*:\s*"?([\d.]+)""").find(it)?.groupValues?.get(1) }
        val tags        = ld?.let { l ->
            Regex(""""genre"\s*:\s*\[([^\]]*)]""").find(l)?.groupValues?.get(1)?.let { g -> Regex(""""([^"]+)"""").findAll(g).map { it.groupValues[1] }.toList() }
                ?: ldAlan(l, "genre")?.let { listOf(it) }
        } ?: document.select("a[href*='/dizi-kategori/'], a[href*='/category/']").map { it.text().trim() }.distinct()
        val actors      = document.select("a[href*='/oyuncular/']").map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }.distinct().map { Actor(it) }
        val recommendations = document.select("a.mcard").mapNotNull { it.toSearchResult() }.filter { it.url != url }.distinctBy { it.url }

        if (diziMi) {
            val episodes = document.select("div.eps a.ep, a.ep[href*='/bolum/']").mapNotNull { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val baslik = a.selectFirst("h4")?.text()?.trim() ?: a.text().trim()
                val m      = Regex("""(\d+)-sezon-(\d+)-bolum""").find(epHref)
                val sezon  = a.closest("[data-season]")?.attr("data-season")?.toIntOrNull()
                    ?: m?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Sezon""").find(baslik)?.groupValues?.get(1)?.toIntOrNull()
                val bolum  = m?.groupValues?.get(2)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Bölüm""").find(baslik)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = baslik
                    this.season  = sezon
                    this.episode = bolum
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score           = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.score           = Score.from10(rating)
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    // Eski tema: iframe base64 ("PHA+...") içinde saklanıyordu
    private fun eskiIframe(sourceCode: String): String? {
        val atob       = Regex("""PHA\+[0-9a-zA-Z+/=]*""").find(sourceCode)?.value ?: return null
        val padding    = 4 - atob.length % 4
        val atobPadded = if (padding < 4) atob.padEnd(atob.length + padding, '=') else atob

        return runCatching { Jsoup.parse(String(Base64.decode(atobPadded, Base64.DEFAULT), Charsets.UTF_8)).selectFirst("iframe")?.attr("src") }.getOrNull()
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("KLT", "data » $data")
        val document = app.get(data, referer = "${mainUrl}/").document
        val iframes  = linkedMapOf<String, String>()

        // ? Yeni tema: <script id="kf-srcdata" type="application/json">[{"name":"KULT","langLabel":"Altyazılı","html":"<iframe src=…>"}]
        document.selectFirst("script#kf-srcdata")?.data()?.let { json ->
            jsonOku<List<KaynakVeri>>(json)?.forEach { k ->
                val src = Jsoup.parse(k.html ?: "").selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() } ?: return@forEach
                iframes[fixUrl(src)] = listOfNotNull(k.name, k.langLabel).joinToString(" ")
            }
        }

        document.select("iframe[src]").forEach { f ->
            val src = f.attr("src")
            if (src.isNotBlank() && !src.startsWith("about:") && !src.contains("ads") && !iframes.containsKey(fixUrl(src))) iframes[fixUrl(src)] = ""
        }
        eskiIframe(document.html())?.let { if (it.isNotBlank()) iframes.putIfAbsent(fixUrl(it), "") }

        var bulundu = false
        for ((iframe, etiket) in iframes) {
            Log.d("KLT", "$etiket » $iframe")
            bulundu = true
            runCatching {
                when {
                    FirePlayer.isFirePlayer(iframe) -> FirePlayer().getUrl(iframe, "${mainUrl}/", subtitleCallback, callback)
                    iframe.contains("vidmoly")      -> vidmoly(iframe, callback)
                    else                            -> loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
                }
            }
        }

        return bulundu
    }

    private suspend fun vidmoly(iframe: String, callback: (ExtractorLink) -> Unit) {
        val headers  = mapOf(
            "User-Agent"     to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36",
            "Sec-Fetch-Dest" to "iframe"
        )
        val iSource = app.get(iframe, headers = headers, referer = "${mainUrl}/").text
        val m3uLink = Regex("""file\s*:\s*['"]([^'"]+)['"]""").find(iSource)?.groupValues?.get(1) ?: return

        callback.invoke(
            newExtractorLink(source = "VidMoly", name = "VidMoly", url = m3uLink) {
                this.referer = "https://vidmoly.to/"
                this.quality = Qualities.Unknown.value
            }
        )
    }

    data class KaynakVeri(
        @JsonProperty("name")      val name: String?      = null,
        @JsonProperty("langLabel") val langLabel: String? = null,
        @JsonProperty("html")      val html: String?      = null
    )
}

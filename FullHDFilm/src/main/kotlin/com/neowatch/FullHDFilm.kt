package com.neowatch

import android.util.Log
import android.util.Base64
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

// ! fullhdfilm.site kapandı; site "HDFilm.us / Fullhdfilm" adıyla hdfilm.us'ta (WordPress keremiya teması) devam ediyor.
class FullHDFilm : MainAPI() {
    override var mainUrl              = "https://hdfilm.us"
    override var name                 = "FullHDFilm"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/"                                       to "Son Eklenenler",
        "${mainUrl}/tur/turkce-dublaj-film-izle/"           to "Türkçe Dublaj",
        "${mainUrl}/tur/turkce-altyazili-film-izle/"        to "Türkçe Altyazılı",
        "${mainUrl}/tur/yerli-film-izle/"                   to "Yerli Filmler",
        "${mainUrl}/tur/netflix-filmleri-izle/"             to "Netflix",
        "${mainUrl}/category/aile-filmleri-izle/"           to "Aile",
        "${mainUrl}/category/aksiyon-filmleri-izle/"        to "Aksiyon",
        "${mainUrl}/category/animasyon-filmleri-izle/"      to "Animasyon",
        "${mainUrl}/category/belgesel-filmleri-izle/"       to "Belgesel",
        "${mainUrl}/category/bilim-kurgu-filmleri-izle/"    to "Bilim Kurgu",
        "${mainUrl}/category/biyografi-filmleri-izle/"      to "Biyografi",
        "${mainUrl}/category/dram-filmleri-izle/"           to "Dram",
        "${mainUrl}/category/fantastik-filmler-izle/"       to "Fantastik",
        "${mainUrl}/category/gerilim-filmleri-izle/"        to "Gerilim",
        "${mainUrl}/category/gizem-filmleri-izle/"          to "Gizem",
        "${mainUrl}/category/komedi-filmleri-izle/"         to "Komedi",
        "${mainUrl}/category/korku-filmleri-izle/"          to "Korku",
        "${mainUrl}/category/macera-filmleri-izle/"         to "Macera",
        "${mainUrl}/category/romantik-filmler-izle/"        to "Romantik",
        "${mainUrl}/category/savas-filmleri-izle/"          to "Savaş",
        "${mainUrl}/category/suc-filmleri-izle/"            to "Suç",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val adres    = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/${page}/"
        val document = app.get(adres, referer = "${mainUrl}/").document
        // Ana sayfadaki "Editörün Önerileri" kaydırıcısı atlanır
        val kartlar  = document.select("div.movie-preview").filter { it.closest("div.slider-content, div.owl-carousel") == null }
            .ifEmpty { document.select("div.movie-preview") }
        val home     = kartlar.mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(request.name, home, home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a         = this.selectFirst("div.movie-poster a[href], a[href]") ?: return null
        val href      = fixUrlNull(a.attr("href")) ?: return null
        val img       = this.selectFirst("img")
        val title     = (this.selectFirst(".movie-title a, .movie-title, h2, h3")?.text()?.takeIf { it.isNotBlank() } ?: img?.attr("alt"))
            ?.substringBefore(" izle")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val posterUrl = fixUrlNull(img?.let { it.attr("data-src").ifBlank { it.attr("src") } })
            ?.replace(Regex("""-\d+x\d+(\.\w+)$"""), "$1")

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${URLEncoder.encode(query, "UTF-8")}", referer = "${mainUrl}/").document

        return document.select("div.movie-preview").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Document.meta(ad: String): String? =
        (this.selectFirst("meta[property=$ad]") ?: this.selectFirst("meta[name=$ad]"))?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "${mainUrl}/").document

        val title       = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.meta("og:title")?.substringBefore(" İzle")?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.meta("og:image"))
            ?: fixUrlNull(document.selectFirst("div.movie-poster img, div.poster img")?.attr("src"))
        val description = document.selectFirst("div.excerpt, div.description, div[itemprop=description], div.post-content p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.meta("og:description") ?: document.meta("description")
        val year        = document.selectFirst("small.release a, a[href*='/yapim/']")?.text()?.trim()?.toIntOrNull()
        val tags        = document.select("header a[href*='/category/'], div.movie a[href*='/category/'], a[rel='tag'][href*='/category/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val rating      = document.selectFirst("div.imdb, .imdb")?.text()?.let { Regex("""(\d+(?:[.,]\d+)?)""").find(it)?.groupValues?.get(1)?.replace(",", ".") }
            ?: document.selectFirst("span.average")?.text()?.trim()
        val actors      = document.select("a[href*='/oyuncu/'], a[href*='/oyuncular/']").map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }.distinct().map { Actor(it) }
        val recommendations = document.select("div.movie-preview").mapNotNull { it.toSearchResult() }.filter { it.url != url }.distinctBy { it.url }

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

    // ? Oynatıcı, kaynağı base64 olan bir <script src="data:text/javascript;base64,…"> ile
    //   ...innerHTML = window.atob("…<iframe src=…>") şeklinde sayfaya yerleştiriliyor.
    private fun iframeler(html: String): List<String> {
        val sonuc = mutableListOf<String>()
        val betikler = mutableListOf(html)
        Regex("""data:text/javascript;base64,([A-Za-z0-9+/=]+)""").findAll(html).forEach { m ->
            runCatching { betikler.add(String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)) }
        }
        for (b in betikler) {
            Regex("""atob\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""").findAll(b).forEach { m ->
                val ic = runCatching { String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return@forEach
                Jsoup.parse(ic).select("iframe[src]").forEach { sonuc.add(it.attr("src")) }
            }
        }
        Jsoup.parse(html).select("div.video-content iframe[src], div.video-container iframe[src]").forEach { sonuc.add(it.attr("src")) }
        return sonuc.map { if (it.startsWith("//")) "https:$it" else it }.filter { it.startsWith("http") }.distinct()
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FHDF", "data » $data")
        val ilk      = app.get(data, referer = "${mainUrl}/").text
        val document = Jsoup.parse(ilk, data)

        // Alternatif kaynaklar: #action-parts a[href="?page=N"]
        val sayfalar = linkedMapOf(data to "")
        document.select("#action-parts a[href]").forEach { a ->
            val adres = a.attr("abs:href").ifBlank { return@forEach }
            if (a.text().contains("Fragman", true)) return@forEach
            sayfalar.putIfAbsent(adres, a.text().trim())
        }

        var bulundu = false
        val gorulen = mutableSetOf<String>()
        for ((sayfa, etiket) in sayfalar) {
            val html = if (sayfa == data) ilk else runCatching { app.get(sayfa, referer = data).text }.getOrNull() ?: continue
            for (iframe in iframeler(html)) {
                if (!gorulen.add(iframe)) continue
                Log.d("FHDF", "$etiket » $iframe")
                val toplanan = mutableListOf<ExtractorLink>()
                runCatching {
                    if (FirePlayer.isFirePlayer(iframe)) FirePlayer().getUrl(iframe, "${mainUrl}/", subtitleCallback) { toplanan.add(it) }
                    else loadExtractor(iframe, "${mainUrl}/", subtitleCallback) { toplanan.add(it) }
                }
                for (link in toplanan) {
                    bulundu = true
                    if (etiket.isBlank()) { callback.invoke(link); continue }
                    callback.invoke(
                        newExtractorLink(link.source, "${link.name} - $etiket", link.url, link.type) {
                            this.referer = link.referer
                            this.quality = link.quality
                            this.headers = link.headers
                        }
                    )
                }
            }
        }

        return bulundu
    }
}

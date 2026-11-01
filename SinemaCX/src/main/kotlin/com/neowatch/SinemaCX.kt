package com.neowatch

import android.util.Log
import java.net.URLEncoder
import android.util.Base64
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class SinemaCX : MainAPI() {
    override var mainUrl              = "https://sinemacc.com"
    override var name                 = "SinemaCX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile-filmleri/"        to "Aile Filmleri",
        "${mainUrl}/tur/aksiyon-filmleri/"     to "Aksiyon Filmleri",
        "${mainUrl}/tur/animasyon-filmleri/"   to "Animasyon Filmleri",
        "${mainUrl}/tur/belgesel/"             to "Belgesel Filmleri",
        "${mainUrl}/tur/bilim-kurgu-filmleri/" to "Bilim Kurgu Filmler",
        "${mainUrl}/tur/biyografi/"            to "Biyografi Filmleri",
        "${mainUrl}/tur/dram-filmleri/"        to "Dram Filmleri",
        "${mainUrl}/tur/fantastik-filmler/"    to "Fantastik Filmler",
        "${mainUrl}/tur/gerilim-filmleri/"     to "Gerilim Filmleri",
        "${mainUrl}/tur/gizem-filmleri/"       to "Gizem Filmleri",
        "${mainUrl}/tur/komedi-filmleri/"      to "Komedi Filmleri",
        "${mainUrl}/tur/korku-filmleri/"       to "Korku Filmleri",
        "${mainUrl}/tur/macera-filmleri/"      to "Macera Filmleri",
        "${mainUrl}/tur/romantik-filmler/"     to "Romantik Filmler",
        "${mainUrl}/tur/suc-filmleri/"         to "Suç Filmleri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val adres    = if (page <= 1) request.data else "${request.data}page/${page}/"
        val document = app.get(adres, referer = "${mainUrl}/").document
        val home     = document.select("div.film_kutusu").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a         = this.selectFirst("a[href*='/film/']") ?: return null
        val title     = this.selectFirst("span.title span.text")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: a.attr("title").substringBefore(" izle").trim().takeIf { it.isNotBlank() } ?: return null
        val href      = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        val year      = this.selectFirst("span.years")?.text()?.trim()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year      = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val sorgu    = URLEncoder.encode(query, "UTF-8")
        val document = app.get("${mainUrl}/arama/?s=${sorgu}", referer = "${mainUrl}/").document

        return document.select("div.film_kutusu").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Document.ldMovie(): String? =
        this.select("script[type=application/ld+json]").map { it.data() }.firstOrNull { Regex(""""@type"\s*:\s*"Movie"""").containsMatchIn(it) }

    private fun ldAlan(ld: String?, alan: String): String? =
        ld?.let { Regex(""""$alan"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(it)?.groupValues?.get(1)?.replace("\\/", "/")?.replace("\\\"", "\"") }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "${mainUrl}/").document
        val ld       = document.ldMovie()

        val title       = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: ldAlan(ld, "name") ?: return null
        val poster      = fixUrlNull(ldAlan(ld, "image"))
            ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("link[rel='image_src']")?.attr("href"))
        val description = document.selectFirst("div.description")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: ldAlan(ld, "description")
        val year        = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(document.title())?.groupValues?.get(1)?.toIntOrNull()
        val tags        = document.select("a[href*='/tur/']")
            .filter { it.closest("header, nav, footer, .menu, .menuler") == null }
            .map { it.text().trim() }.filter { it.isNotBlank() && it.length < 30 }.distinct()
        val rating      = ld?.let { Regex(""""ratingValue"\s*:\s*"?([\d.]+)""").find(it)?.groupValues?.get(1) }
        val duration    = ld?.let { Regex(""""duration"\s*:\s*"PT(\d+)M""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val actors      = ld?.let { l ->
            Regex(""""actor"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(l)?.groupValues?.get(1)?.let { a ->
                Regex(""""name"\s*:\s*"([^"]+)"""").findAll(a).map { Actor(it.groupValues[1]) }.toList()
            }
        } ?: emptyList()
        val trailer     = document.select("button.part_button").firstOrNull { it.text().contains("Fragman", true) }
            ?.attr("rel")?.let { coz(it) }
        val recommendations = document.select("div.film_kutusu").mapNotNull { it.toSearchResult() }.filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.score           = Score.from10(rating)
            this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    // ? Oynatıcı adresleri base64 ile saklanıyor (data-vsrc / button rel)
    private fun coz(veri: String): String? {
        val temiz = veri.trim()
        if (temiz.isBlank()) return null
        if (temiz.startsWith("http") || temiz.startsWith("//")) return fixUrl(temiz)
        return runCatching { String(Base64.decode(temiz, Base64.DEFAULT), Charsets.UTF_8).trim() }.getOrNull()
            ?.takeIf { it.startsWith("http") || it.startsWith("//") }?.let { fixUrl(it) }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("SCX", "data » $data")
        val document = app.get(data, referer = "${mainUrl}/").document

        val kaynaklar = mutableListOf<Pair<String, String>>()
        document.select("button.part_button[rel]").forEach { b ->
            val isim = b.text().trim()
            if (isim.contains("Fragman", true)) return@forEach
            coz(b.attr("rel"))?.let { kaynaklar.add(isim to it) }
        }
        document.select("iframe[data-vsrc]").forEach { f -> coz(f.attr("data-vsrc"))?.let { kaynaklar.add("" to it) } }

        var bulundu = false
        for ((isim, iframe) in kaynaklar.distinctBy { it.second }) {
            if (iframe.contains("youtube.com")) continue
            Log.d("SCX", "$isim » $iframe")
            bulundu = true

            runCatching {
                if (FirePlayer.isFirePlayer(iframe)) {
                    val liste = mutableListOf<ExtractorLink>()
                    FirePlayer().getUrl(iframe, "${mainUrl}/", subtitleCallback) { liste.add(it) }
                    for (link in liste) {
                        callback.invoke(if (isim.isBlank()) link else link.kopya("${link.name} - $isim"))
                    }
                } else {
                    loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
                }
            }
        }

        return bulundu
    }

    private suspend fun ExtractorLink.kopya(yeniAd: String): ExtractorLink {
        val eski = this
        return newExtractorLink(eski.source, yeniAd, eski.url, eski.type) {
            this.referer = eski.referer
            this.quality = eski.quality
            this.headers = eski.headers
        }
    }
}

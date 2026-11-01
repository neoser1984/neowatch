package com.neowatch

import android.util.Log
import android.util.Base64
import java.net.URLEncoder
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CizgiMax : MainAPI() {
    override var mainUrl              = "https://cizgimax.online"
    override var name                 = "CizgiMax"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Cartoon, TvType.Anime)

    override val mainPage = mainPageOf(
        "${mainUrl}/ara/?siralama=yeni&"                          to "Son Eklenenler",
        "${mainUrl}/ara/?siralama=yeni&kategori%5B%5D=cizgi-film&" to "Çizgi Filmler",
        "${mainUrl}/ara/?siralama=yeni&kategori%5B%5D=anime&"      to "Animeler",
        "${mainUrl}/ara/?siralama=yeni&kategori%5B%5D=dizi&"       to "Diziler",
        "${mainUrl}/tur/aile/?"                                    to "Aile",
        "${mainUrl}/tur/aksiyon/?"                                 to "Aksiyon",
        "${mainUrl}/tur/macera/?"                                  to "Macera",
        "${mainUrl}/tur/komedi/?"                                  to "Komedi",
        "${mainUrl}/tur/bilim-kurgu/?"                             to "Bilim Kurgu",
        "${mainUrl}/tur/fantezi/?"                                 to "Fantezi",
        "${mainUrl}/tur/cocuk/?"                                   to "Çocuk",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}page=${page}", referer = "${mainUrl}/").document
        val home     = document.select("div.inner").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(request.name, home, home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a         = this.selectFirst("a.film-name") ?: return null
        val title     = a.text().trim().takeIf { it.isNotBlank() } ?: return null
        val href      = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("a.poster img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        val tur       = if (this.selectFirst("span.corner-tag")?.className()?.contains("anime") == true) TvType.Anime else TvType.Cartoon

        return newTvSeriesSearchResponse(title, href, tur) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val sorgu    = URLEncoder.encode(query, "UTF-8")
        val document = app.get("${mainUrl}/ara?q=${sorgu}", referer = "${mainUrl}/").document

        return document.select("div.inner").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "${mainUrl}/").document

        val ld          = document.select("script[type=application/ld+json]").map { it.data() }.firstOrNull { it.contains("TVSeries") }
        fun ldAlan(alan: String) = ld?.let { Regex(""""$alan"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(it)?.groupValues?.get(1)?.replace("\\/", "/")?.replace("\\\"", "\"") }

        val title       = document.selectFirst("a.anime-title-link, h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: ldAlan("name") ?: return null
        val poster      = fixUrlNull(ldAlan("image")) ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("p.anime-desc")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: ldAlan("description")
        val year        = ldAlan("datePublished")?.take(4)?.toIntOrNull()
        val rating      = document.selectFirst("span.ep-score-value")?.text()?.trim()?.replace(",", ".")
            ?: ld?.let { Regex(""""ratingValue"\s*:\s*"?([\d.]+)""").find(it)?.groupValues?.get(1) }
        val tags        = document.select("div.anime-info-box a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val animeMi     = document.selectFirst("div.anime-info-box")?.text()?.contains("Kategori: Anime") == true

        val episodes = document.select("a.ep-num-btn").mapNotNull { a ->
            val epHref  = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val m       = Regex("""-(\d+)-sezon-(\d+)-bolum""").find(epHref)
            val sezon   = a.closest("[data-season-pane]")?.attr("data-season-pane")?.toIntOrNull() ?: m?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val bolum   = a.selectFirst("span.ep-num-label")?.text()?.trim()?.toIntOrNull() ?: m?.groupValues?.get(2)?.toIntOrNull()
            val baslik  = a.attr("title").substringAfter(" : ", "").trim().ifBlank { null }

            newEpisode(epHref) {
                this.name    = baslik
                this.season  = sezon
                this.episode = bolum
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, if (animeMi) TvType.Anime else TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.score     = Score.from10(rating)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("CZGM", "data » $data")
        val metin = app.get(data, referer = "${mainUrl}/").text

        // ? Kaynaklar: var servers = JSON.parse(atob("…"))  →  [{"type":"iframe","src":"/oynat/?t=…","label":"…"}]
        //   /oynat/ adresi asıl oynatıcıya (sibnet, drive, cizgiduo …) yönlendiriyor.
        val b64      = Regex("""servers\s*=\s*JSON\.parse\(atob\(["']([^"']+)["']\)\)""").find(metin)?.groupValues?.get(1)?.replace("\\/", "/")
        val sunucular = b64?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() }
            ?.let { jsonOku<List<Sunucu>>(it) }.orEmpty()

        var bulundu = false
        for (s in sunucular) {
            val src = s.src?.takeIf { it.isNotBlank() } ?: continue
            val tam = fixUrl(src)
            val hedef = if (tam.contains("/oynat/")) {
                runCatching { app.get(tam, referer = data, allowRedirects = false) }.getOrNull()?.let { r ->
                    r.headers["location"]?.let { fixUrl(it) } ?: runCatching { app.get(tam, referer = data).url }.getOrNull()
                }
            } else tam
            if (hedef.isNullOrBlank() || hedef.contains("/oynat/")) continue
            Log.d("CZGM", "${s.label} » $hedef")

            bulundu = true
            runCatching { loadExtractor(hedef, "${mainUrl}/", subtitleCallback, callback) }
        }

        // Eski tema yedeği
        if (!bulundu) {
            org.jsoup.Jsoup.parse(metin).select("ul.linkler li a[data-frame]").forEach {
                val iframe = fixUrlNull(it.attr("data-frame")) ?: return@forEach
                bulundu = true
                runCatching { loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback) }
            }
        }

        return bulundu
    }

    data class Sunucu(
        @JsonProperty("type")    val type: String?    = null,
        @JsonProperty("src")     val src: String?     = null,
        @JsonProperty("label")   val label: String?   = null,
        @JsonProperty("embedId") val embedId: Any?    = null
    )
}

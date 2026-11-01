package com.neowatch

import android.util.Log
import android.util.Base64
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// ! Eski turkanime.co alan adı reklam sitesine yönleniyor; eklenti "Türk Anime TV" (turkanime.live) sitesini kullanır.
class TurkAnime : MainAPI() {
    override var mainUrl              = "https://turkanime.live"
    override var name                 = "TurkAnime"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "${mainUrl}/animeler?"                            to "Tüm Animeler",
        "${mainUrl}/animeler?sort=rating&"                to "En Yüksek Puanlılar",
        "${mainUrl}/animeler?genre=aksiyon-macera&"       to "Aksiyon & Macera",
        "${mainUrl}/animeler?genre=bilim-kurgu-fantazi&"  to "Bilim Kurgu & Fantazi",
        "${mainUrl}/animeler?genre=komedi&"               to "Komedi",
        "${mainUrl}/animeler?genre=dram&"                 to "Dram",
        "${mainUrl}/animeler?genre=gizem&"                to "Gizem",
        "${mainUrl}/animeler?genre=romantik&"             to "Romantik",
        "${mainUrl}/animeler?genre=suc&"                  to "Suç",
        "${mainUrl}/filmler?"                             to "Anime Filmleri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}page=${page}", referer = "${mainUrl}/").document
        val home     = document.select("a.group.block").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(request.name, home, home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href      = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/dizi/") && !href.contains("/film/")) return null
        val title     = this.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: this.selectFirst("img")?.attr("alt")?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })

        return if (href.contains("/film/")) newAnimeSearchResponse(title, href, TvType.AnimeMovie) { this.posterUrl = posterUrl }
               else newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val sorgu = URLEncoder.encode(query, "UTF-8")
        val yanit = app.get("${mainUrl}/api/search.php?q=${sorgu}", referer = "${mainUrl}/").parsedSafe<AramaYaniti>() ?: return emptyList()

        return yanit.results.orEmpty().mapNotNull { r ->
            val slug  = r.slug?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val film  = r.type == "movie"
            val adres = "${mainUrl}/${if (film) "film" else "dizi"}/${slug}"
            val poster = r.poster?.let { if (it.startsWith("http")) it else "${mainUrl}/${it.removePrefix("/")}" }

            newAnimeSearchResponse(r.title ?: slug, adres, if (film) TvType.AnimeMovie else TvType.Anime) { this.posterUrl = poster }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Document.ldNesne(vararg tipler: String): String? =
        this.select("script[type=application/ld+json]").map { it.data() }
            .firstOrNull { d -> tipler.any { Regex(""""@type"\s*:\s*"$it"""").containsMatchIn(d) } }

    private fun ldAlan(ld: String?, alan: String): String? =
        ld?.let { Regex(""""$alan"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(it)?.groupValues?.get(1)?.replace("\\/", "/")?.replace("\\\"", "\"") }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "${mainUrl}/").document
        val filmMi   = url.contains("/film/")
        val ld       = document.ldNesne("TVSeries", "Movie")

        val title       = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: ldAlan(ld, "name") ?: return null
        val poster      = fixUrlNull(ldAlan(ld, "image")) ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = ldAlan(ld, "description") ?: document.selectFirst("meta[name=description]")?.attr("content")
        val year        = ldAlan(ld, "datePublished")?.take(4)?.toIntOrNull()
        val rating      = ld?.let { Regex(""""ratingValue"\s*:\s*"?([\d.]+)""").find(it)?.groupValues?.get(1) }
        val tags        = ld?.let { l ->
            Regex(""""genre"\s*:\s*\[([^\]]*)]""").find(l)?.groupValues?.get(1)?.let { g -> Regex(""""([^"]+)"""").findAll(g).map { it.groupValues[1] }.toList() }
        } ?: emptyList()

        if (filmMi) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
                this.score     = Score.from10(rating)
            }
        }

        val episodes = document.select("a[href*='/bolum/']").mapNotNull { a ->
            val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val baslik = a.selectFirst("h4")?.text()?.trim() ?: a.text().trim()
            val sezon  = a.closest("[id^=season-]")?.id()?.substringAfter("season-")?.toIntOrNull()
                ?: Regex("""(\d+)\.\s*Sezon""").find(baslik)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-(\d+)-sezon-""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            val bolum  = Regex("""(\d+)\.\s*Bölüm""").find(baslik)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-sezon-(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epHref) {
                this.name    = baslik.takeIf { it.isNotBlank() }
                this.season  = sezon
                this.episode = bolum
            }
        }.distinctBy { it.data }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.score     = Score.from10(rating)
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("TRANM", "data » $data")
        val metin = app.get(data, referer = "${mainUrl}/").text

        // ? Oynatıcı loadPlayer() içinde base64 olarak saklı: const encodedContent = '…'  →  <iframe src="https://streamcorecdn.com/dizi/<tmdb>/<s>/<b>?sid=…">
        val gomululer = mutableListOf<String>()
        Regex("""encodedContent\s*=\s*['"]([A-Za-z0-9+/=]+)['"]""").findAll(metin).forEach { m ->
            val html = runCatching { String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return@forEach
            Jsoup.parse(html).select("iframe[src]").forEach { gomululer.add(it.attr("src")) }
        }
        Jsoup.parse(metin).select("iframe[src]").forEach { f -> f.attr("src").takeIf { it.startsWith("http") }?.let { gomululer.add(it) } }

        var bulundu = false
        for (iframe in gomululer.distinct()) {
            Log.d("TRANM", "iframe » $iframe")
            if (oynaticiCoz(iframe, subtitleCallback, callback)) bulundu = true
        }

        return bulundu
    }

    private suspend fun oynaticiCoz(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val tam = if (url.startsWith("//")) "https:$url" else url
        // Bilinen bir çıkarıcı varsa önce onu dene
        var bulundu = false
        runCatching { bulundu = loadExtractor(tam, "${mainUrl}/", subtitleCallback) { callback.invoke(it); bulundu = true } }
        if (bulundu) return true

        val yanit = runCatching { app.get(tam, referer = "${mainUrl}/", headers = mapOf("Sec-Fetch-Dest" to "iframe")) }.getOrNull() ?: return false
        val metin = yanit.text
        val base  = Regex("""^(https?://[^/]+)""").find(yanit.url)?.groupValues?.get(1) ?: return false
        val host  = base.substringAfter("://")

        // ? streamcorecdn oynatıcısı: const src = '/play.m3u8?id=…&m=master&token=…&expires=…'  (hls.loadSource(src))
        //   Sayfadaki diğer m3u8 adresleri reklam (VAST) videoları; onlar atlanır.
        val asil = Regex("""\bconst\s+src\s*=\s*['"]([^'"]+?\.m3u8[^'"]*)['"]""").find(metin)?.groupValues?.get(1)
        val adaylar = if (asil != null) listOf(asil) else
            Regex("""["']((?:https?:\\?/\\?/|/)[^"'\s]+?\.(?:m3u8|mp4)[^"'\s]*)["']""").findAll(metin).map { it.groupValues[1].replace("\\/", "/") }
                .filter { a -> listOf("bsky", "vast", "/ads", "reklam").none { a.contains(it, true) } }.distinct().toList()

        adaylar.forEach { a ->
            val video = when {
                a.startsWith("http") -> a
                a.startsWith("//")   -> "https:$a"
                a.startsWith("/")    -> "$base$a"
                else                 -> "$base/$a"
            }
            bulundu = true
            callback.invoke(newExtractorLink(this.name, this.name, video, if (video.contains(".mp4") && !video.contains(".m3u8")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                this.referer = tam
                this.headers = mapOf("Origin" to base)
                this.quality = Qualities.Unknown.value
            })
        }
        if (bulundu) return true

        Jsoup.parse(metin, base).select("iframe[src]").forEach { f ->
            val src = f.absUrl("src").ifBlank { return@forEach }
            if (src == tam) return@forEach
            runCatching { loadExtractor(src, "$base/", subtitleCallback) { callback.invoke(it); bulundu = true } }
        }

        return bulundu
    }

    data class AramaYaniti(@JsonProperty("results") val results: List<AramaSonuc>? = null)
    data class AramaSonuc(
        @JsonProperty("id")     val id: Int?       = null,
        @JsonProperty("title")  val title: String? = null,
        @JsonProperty("type")   val type: String?  = null,
        @JsonProperty("slug")   val slug: String?  = null,
        @JsonProperty("poster") val poster: String? = null
    )
}

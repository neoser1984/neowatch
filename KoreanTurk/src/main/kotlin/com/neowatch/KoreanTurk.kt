package com.neowatch

import android.util.Log
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.Vidmoly

// Eski koreanturk.com / .net kapandı (Cloudflare 522/523, Ekim 2026). Site koreanturk.tv'de WordPress
// tabanlı yeni bir temayla devam ediyor: diziler /dizi/<ad>/, bölümler /bolum/<ad>-<n>-bolum/, filmler /<ad>/.
class KoreanTurk : MainAPI() {
    override var mainUrl              = "https://koreanturk.tv"
    override var name                 = "KoreanTurk"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.AsianDrama, TvType.Movie)

    private val tarayici = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/"                                          to "Son Eklenen Bölümler",
        "${mainUrl}/dizi-arsivi/"                              to "Diziler",
        "${mainUrl}/film-arsivi/"                              to "Filmler",
        "${mainUrl}/dizi-kategori/aile-dizileri-izle/"         to "Aile",
        "${mainUrl}/dizi-kategori/aksiyon-dizileri-izle/"      to "Aksiyon",
        "${mainUrl}/dizi-kategori/bilim-kurgu-dizileri-izle/"  to "Bilim Kurgu",
        "${mainUrl}/dizi-kategori/bl-dizileri-izle/"           to "BL",
        "${mainUrl}/dizi-kategori/dram-dizileri-izle/"         to "Dram",
        "${mainUrl}/dizi-kategori/fantastik-dizileri-izle/"    to "Fantastik",
        "${mainUrl}/dizi-kategori/genclik-dizileri-izle/"      to "Gençlik",
        "${mainUrl}/dizi-kategori/gerilim-dizileri-izle/"      to "Gerilim",
        "${mainUrl}/dizi-kategori/gizem-dizileri-izle/"        to "Gizem",
        "${mainUrl}/dizi-kategori/hukuk-dizileri-izle/"        to "Hukuk",
        "${mainUrl}/dizi-kategori/is-dizileri-izle/"           to "İş",
        "${mainUrl}/dizi-kategori/komedi-dizileri-izle/"       to "Komedi",
        "${mainUrl}/dizi-kategori/korku-dizileri-izle/"        to "Korku",
        "${mainUrl}/dizi-kategori/macera-dizileri-izle/"       to "Macera",
        "${mainUrl}/dizi-kategori/muzikal-dizileri-izle/"      to "Müzikal",
        "${mainUrl}/dizi-kategori/okul-dizileri-izle/"         to "Okul",
        "${mainUrl}/dizi-kategori/psikolojik-dizileri-izle/"   to "Psikolojik",
        "${mainUrl}/dizi-kategori/romantik-dizileri-izle/"     to "Romantik",
        "${mainUrl}/dizi-kategori/savas-dizileri-izle/"        to "Savaş",
        "${mainUrl}/dizi-kategori/suc-dizileri-izle/"          to "Suç",
        "${mainUrl}/dizi-kategori/tarih-dizileri-izle/"        to "Tarih",
        "${mainUrl}/dizi-kategori/tip-dizileri-izle/"          to "Tıp",
    )

    private fun sayfaAdresi(data: String, page: Int): String =
        if (page <= 1) data else "${data.trimEnd('/')}/page/$page/"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(sayfaAdresi(request.data, page), headers = tarayici).document
        val sonBolumler = request.data.trimEnd('/') == mainUrl

        val home = if (sonBolumler) {
            document.select("div.ep-box").mapNotNull { it.bolumSonucu() }
        } else {
            document.select("div.series-box, div.film-box").mapNotNull { it.toSearchResult() }
        }.distinctBy { it.url }

        val sonraki = document.select("a[href*=/page/${page + 1}/]").isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext = sonraki && home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a         = this.selectFirst("div.movie-box > a[href], a[href]") ?: return null
        val href      = a.attr("abs:href").ifBlank { return null }
        val title     = this.selectFirst("div.name a, div.name")?.text()?.trim()?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null } ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        val puan      = this.selectFirst("div.rating")?.text()?.trim()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = posterUrl
                this.score     = Score.from10(puan)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score     = Score.from10(puan)
            }
        }
    }

    // Son bölümler: kart bölüme gider; load() bölüm sayfasından diziye geçer
    private fun Element.bolumSonucu(): SearchResponse? {
        val a      = this.selectFirst("a[href*=/bolum/]") ?: return null
        val href   = a.attr("abs:href").ifBlank { return null }
        val dizi   = this.selectFirst("span.serietitle")?.text()?.trim()?.ifBlank { null } ?: return null
        val sezon  = this.selectFirst("span.episodetitle")?.text()?.trim()
        val bolum  = Regex("""-(\d+)-bolum""").find(href)?.groupValues?.get(1)
        // episodetitle genelde "1. Sezon 6. Bölüm" biçiminde; bölüm numarası yoksa adresten eklenir
        val ek     = when {
            sezon.isNullOrBlank()        -> bolum?.let { "$it. Bölüm" } ?: ""
            sezon.contains("Bölüm")      -> sezon
            else                         -> listOfNotNull(sezon, bolum?.let { "$it. Bölüm" }).joinToString(" ")
        }

        return newTvSeriesSearchResponse(if (ek.isBlank()) dizi else "$dizi - $ek", href, TvType.AsianDrama) {
            this.posterUrl = fixUrlNull(this@bolumSonucu.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/", params = mapOf("s" to query), headers = tarayici).document

        return document.select("div.series-box, div.film-box").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Document.ortakBilgi(): Triple<String?, Int?, String?> {
        val puan   = this.selectFirst(".imdb-count")?.text()?.let { Regex("""\d+(?:[.,]\d+)?""").find(it)?.value?.replace(',', '.') }
        val yil    = this.select("a[href*=/yapim/]").map { it.text().trim() }.firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()
        val poster = fixUrlNull(this.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: fixUrlNull(this.select("div.poster img").firstOrNull { it.parents().none { p -> p.hasClass("ep-box") } }?.attr("src"))
        return Triple(puan, yil, poster)
    }

    override suspend fun load(url: String): LoadResponse? {
        var document = app.get(url, headers = tarayici).document
        var adres    = url

        // Bölüm sayfasından gelindiyse diziye geç
        if (url.contains("/bolum/")) {
            val dizi = document.select("a[href*=/dizi/]").map { it.attr("abs:href") }.firstOrNull { Regex("""/dizi/[^/]+/?$""").containsMatchIn(it) }
                ?: return null
            adres    = dizi
            document = app.get(dizi, headers = tarayici).document
        }

        val title       = document.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: return null
        val description = document.selectFirst("div.description")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val (puan, yil, poster) = document.ortakBilgi()

        if (!adres.contains("/dizi/")) {
            val tags = document.select("a[href*=/category/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            return newMovieLoadResponse(title, adres, TvType.Movie, adres) {
                this.posterUrl = poster
                this.year      = yil
                this.plot      = description
                this.tags      = tags
                this.score     = Score.from10(puan)
            }
        }

        val tags = document.select("a[href*=/dizi-kategori/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val episodes = document.select("div.ep-box").mapNotNull { kutu ->
            val href  = kutu.selectFirst("a[href*=/bolum/]")?.attr("abs:href")?.ifBlank { null } ?: return@mapNotNull null
            val no    = Regex("""-(\d+)-bolum""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            val sezon = kutu.selectFirst("span.episodetitle")?.text()?.let { Regex("""(\d+)\.\s*Sezon""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 1
            newEpisode(href) {
                this.name      = no?.let { "$it. Bölüm" }
                this.season    = sezon
                this.episode   = no
                this.posterUrl = fixUrlNull(kutu.selectFirst("img")?.attr("src"))
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, adres, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.year      = yil
            this.plot      = description
            this.tags      = tags
            this.score     = Score.from10(puan)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("KRT", "data » $data")
        val document = app.get(data, headers = tarayici).document
        val iframes  = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") }) }.distinct()
        if (iframes.isEmpty()) return false

        for (iframe in iframes) {
            Log.d("KRT", "iframe » $iframe")
            runCatching {
                // Kendi oynatıcıları: player.html?v=..&video=/storage/media/<ad>/<ad>.m3u8 → akış = oynatıcı kökü + video
                // (m3u8 ve .ts parçaları oynatıcı kökünden Referer ister, yoksa 403)
                val video = Regex("""[?&]video=([^&]+)""").find(iframe)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                if (iframe.contains("/player/player.html") && video != null) {
                    val kok  = Regex("""^(https?://[^/]+)""").find(iframe)?.groupValues?.get(1) ?: return@runCatching
                    val akis = if (video.startsWith("http")) video else kok + "/" + video.trimStart('/')
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name   = this.name,
                            url    = akis,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$kok/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("Origin" to kok)
                        }
                    )
                } else if (iframe.contains("vidmoly")) {
                    Vidmoly().getUrl(iframe, "${mainUrl}/", subtitleCallback, callback)
                } else {
                    loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
                }
            }
        }

        return true
    }
}

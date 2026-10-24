package com.neowatch

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.extractors.Vidmoly
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import android.webkit.CookieManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal2142.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/bolumler"                 to "Son Bölümler",
        "${mainUrl}/diziler"                  to "Yeni Diziler",
        "${mainUrl}/filmler"                  to "Yeni Filmler",
        "${mainUrl}/platform/netflix"         to "Netflix",
        "${mainUrl}/platform/exxen"           to "Exxen",
        "${mainUrl}/platform/blutv"           to "BluTV",
        "${mainUrl}/platform/disney-plus"     to "Disney+",
        "${mainUrl}/platform/prime-video"     to "Amazon Prime",
        "${mainUrl}/platform/max"             to "Max",
        "${mainUrl}/platform/gain"            to "Gain",
        "${mainUrl}/platform/tabii"           to "Tabii",
        "${mainUrl}/kategori/bilim-kurgu"     to "Bilimkurgu",
        "${mainUrl}/kategori/komedi"          to "Komedi",
        "${mainUrl}/kategori/belgesel"        to "Belgesel",
    )

    // Ekim 2026: bölüm (/bolum/) ve film (/film/) sayfaları önce sitenin kendi "Doğrulama · DiziPAL" ara sayfasını
    // gösteriyor (JS ile çalışan bir tarayıcı kontrolü). Bu kontrolü uygulamanın gömülü tarayıcısı (WebView) sayfayı
    // normal bir tarayıcı gibi açarak geçer; oluşan oturum çerezleri sonraki isteklerde kullanılır.
    private var dogrulamaCerezleri: Map<String, String> = emptyMap()
    private var sonDogrulama       = 0L
    private var dogrulamaBasarili  = false
    private val dogrulamaKilidi    = Mutex()

    private fun Document.dogrulamaSayfasi(): Boolean =
        this.selectFirst("meta[name=_bc]") != null || this.title().startsWith("Doğrulama")

    private fun tarayiciCerezleri(): Map<String, String> = runCatching {
        CookieManager.getInstance().getCookie(mainUrl).orEmpty().split(";").mapNotNull {
            val parca = it.trim().split("=", limit = 2)
            if (parca.size == 2 && parca[0].isNotBlank()) parca[0] to parca[1] else null
        }.toMap()
    }.getOrDefault(emptyMap())

    private suspend fun dogrula(url: String): Boolean = dogrulamaKilidi.withLock {
        // Son bir dakikada denendiyse WebView yeniden açılmaz (başarısızsa her tıklamada beklenmesin)
        if (System.currentTimeMillis() - sonDogrulama < 60_000L) return@withLock dogrulamaBasarili
        runCatching {
            WebViewResolver(
                interceptUrl = Regex("""/public/assets/js/main\.js"""),  // yalnızca asıl sayfa yükler
                userAgent    = USER_AGENT,
                useOkhttp    = false,
                timeout      = 25_000L
            ).resolveUsingWebView(url, referer = "${mainUrl}/")
        }.onFailure { Log.w("DZP", "doğrulama » ${it.message}") }
        val cerezler = tarayiciCerezleri()
        if (cerezler.isNotEmpty()) dogrulamaCerezleri = cerezler
        sonDogrulama      = System.currentTimeMillis()
        dogrulamaBasarili = cerezler.isNotEmpty()
        dogrulamaBasarili
    }

    // Gömülü tarayıcı CloudStream'in DNS over HTTPS ayarını kullanmaz; site DNS ile engelliyse doğrulama sayfası açılamaz
    private var uyarildi = false
    private fun dogrulamaUyarisi() {
        if (uyarildi) return
        uyarildi = true
        runCatching {
            CommonActivity.showToast(
                "DiziPal: sitenin doğrulama sayfası açılamadı. Telefonda Ayarlar → Özel DNS → dns.google yapıp tekrar deneyin.",
                1
            )
        }
    }

    private suspend fun sayfaGetir(url: String, referer: String = "${mainUrl}/"): NiceResponse {
        if (dogrulamaCerezleri.isEmpty()) dogrulamaCerezleri = tarayiciCerezleri()
        val basliklar = mapOf("User-Agent" to USER_AGENT)
        var yanit = app.get(url, referer = referer, headers = basliklar, cookies = dogrulamaCerezleri)
        if (yanit.document.dogrulamaSayfasi()) {
            Log.d("DZP", "doğrulama sayfası » $url")
            if (dogrula(url)) {
                yanit = app.get(url, referer = referer, headers = basliklar, cookies = dogrulamaCerezleri)
                dogrulamaBasarili = !yanit.document.dogrulamaSayfasi()
            }
        }
        return yanit
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sayfali  = request.data.endsWith("/bolumler") || request.data.endsWith("/diziler") || request.data.endsWith("/filmler")
        if (!sayfali && page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val url      = if (page > 1) "${request.data}?page=${page}" else request.data
        val document = app.get(url, referer = "${mainUrl}/").document

        val home = if (request.data.endsWith("/bolumler")) {
            document.select("a.episode-list-item").mapNotNull { it.sonBolumler() }
        } else {
            document.select(".content-card a.card-link").mapNotNull { it.toSearchResult() }
        }

        return newHomePageResponse(request.name, home, hasNext = sayfali && home.isNotEmpty())
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name      = this.selectFirst("span.ep-title")?.text()?.trim() ?: return null
        val episode   = this.selectFirst("span.ep-info")?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "")
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })

        // Bölüm adresi dizi adresinden türetilemiyor (slug farklı olabiliyor); load() içinde dizi sayfasına geçilir
        return newTvSeriesSearchResponse(if (episode != null) "$name $episode" else name, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("h3.card-title")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        val score     = this.selectFirst("span.card-rating")?.text()?.trim()
        val year      = this.selectFirst("span.card-year")?.text()?.trim()?.toIntOrNull()

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year      = year
                this.score     = Score.from10(score)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year      = year
                this.score     = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama", params = mapOf("q" to query), referer = "${mainUrl}/").document

        return document.select(".content-card a.card-link").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/film/")) {
            val document = sayfaGetir(url).document
            if (document.dogrulamaSayfasi()) { dogrulamaUyarisi(); return null }
            return loadMovie(url, document)
        }
        if (!url.contains("/bolum/")) return loadSeries(url, app.get(url, referer = "${mainUrl}/").document)

        // Son Bölümler kartı bölüme gider: önce adresten türetilen dizi sayfası denenir (bölüm sayfası doğrulama ister),
        // olmazsa bölüm sayfasındaki "Tüm Bölümler" bağlantısı kullanılır
        val tahmin = Regex("""/bolum/(.+?)-\d+-sezon-\d+-bolum""").find(url)?.groupValues?.get(1)?.let { "${mainUrl}/dizi/$it" }
        if (tahmin != null) {
            val yanit = app.get(tahmin, referer = "${mainUrl}/")
            if (yanit.isSuccessful) loadSeries(tahmin, yanit.document)?.let { return it }
        }

        val bolumSayfasi = sayfaGetir(url).document
        val dizi = fixUrlNull(
            bolumSayfasi.selectFirst("a.ep-nav-all[href*='/dizi/'], a.btn-watch-first[href*='/dizi/'], a[href*='/dizi/']:containsOwn(Tüm Bölümler)")?.attr("href")
        ) ?: return null
        return loadSeries(dizi, app.get(dizi, referer = url).document)
    }

    private fun Document.bilgi(label: String): Element? {
        return this.select("div.sidebar-info div.info-row").firstOrNull {
            it.selectFirst("span.info-label")?.text()?.trim().equals(label, ignoreCase = true)
        }?.selectFirst("span.info-value")
    }

    private suspend fun loadSeries(url: String, document: Document): LoadResponse? {
        val title       = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val background  = document.selectFirst("div.series-hero")?.attr("style")
            ?.substringAfter("url('", "")?.substringBefore("')")?.takeIf { it.isNotBlank() }
        val description = document.selectFirst("p.series-description")?.text()?.trim()
        val year        = document.bilgi("Yıl")?.text()?.trim()?.toIntOrNull()
        val score       = document.bilgi("IMDB")?.text()?.trim()
        val tags        = document.bilgi("Kategoriler")?.select("a")?.map { it.text().trim() }
        val actors      = document.select("div.sidebar-cast div.cast-item").mapNotNull {
            val name = it.selectFirst("span.actor-name")?.text()?.trim() ?: return@mapNotNull null
            Actor(name) to it.selectFirst("span.actor-role")?.text()?.trim()
        }

        val episodes = document.select("a.detail-episode-item").mapNotNull {
            val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
            val epInfo    = it.selectFirst("div.detail-episode-subtitle")?.text()?.trim() ?: ""
            val epSeason  = Regex("""(\d+)\.\s*Sezon""").find(epInfo)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-(\d+)-sezon""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            val epEpisode = Regex("""(\d+)\.\s*Bölüm""").find(epInfo)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epHref) {
                this.name    = "${epEpisode ?: ""}. Bölüm"
                this.season  = epSeason
                this.episode = epEpisode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl           = poster
            this.backgroundPosterUrl = background
            this.year                = year
            this.plot                = description
            this.tags                = tags
            this.score               = Score.from10(score)
            addActors(actors)
        }
    }

    private suspend fun loadMovie(url: String, document: Document): LoadResponse? {
        val title       = document.selectFirst("h1.film-title, div.watch-title-top h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.film-poster img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val meta        = document.select("div.film-meta span").map { it.text().trim() }
        val year        = meta.firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()
        val duration    = meta.firstOrNull { it.endsWith("dk") }?.replace("dk", "")?.trim()?.toIntOrNull()
        val score       = document.selectFirst("div.film-meta span.rating")?.text()?.replace("IMDB", "")?.trim()
        val description = document.selectFirst("div.film-description")?.text()?.trim()
        val tags        = document.select("div.film-categories a.category-tag").map { it.text().trim() }
        val actors      = document.select("div.film-cast span.cast-tag").map { Actor(it.text().trim()) }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = year
            this.duration  = duration
            this.plot      = description
            this.tags      = tags
            this.score     = Score.from10(score)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZP", "data » $data")
        val sayfa    = sayfaGetir(data)
        val document = sayfa.document
        if (document.dogrulamaSayfasi()) {
            dogrulamaUyarisi()
            return false
        }
        val cfg      = document.selectFirst("#videoContainer")?.attr("data-cfg")?.takeIf { it.isNotBlank() } ?: return false

        // data-cfg anahtarı oturuma bağlı: sayfanın verdiği çerezler (PHPSESSID) olmadan "Invalid token" döner
        val config = app.post(
            "${mainUrl}/ajax-player-config",
            data    = mapOf("cfg" to cfg),
            referer = data,
            cookies = dogrulamaCerezleri + sayfa.cookies,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Origin" to mainUrl, "User-Agent" to USER_AGENT)
        ).parsedSafe<PlayerConfig>() ?: return false
        if (config.success == false) Log.d("DZP", "ajax-player-config başarısız » cfg=$cfg")

        val video = config.enc?.let { coz(it) } ?: config.config?.v
        if (video.isNullOrBlank()) return false
        Log.d("DZP", "video » $video")

        val iframe = if (video.contains("<iframe")) {
            Regex("""src=["']([^"']+)["']""").find(video)?.groupValues?.get(1) ?: return false
        } else {
            video
        }

        when {
            config.config?.t == "m3u8" || iframe.contains(".m3u8") -> {
                callback.invoke(
                    newExtractorLink(this.name, this.name, iframe, ExtractorLinkType.M3U8) {
                        this.referer = "${mainUrl}/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            iframe.contains("vidmoly") -> {
                Vidmoly().getUrl(iframe, "${mainUrl}/", subtitleCallback, callback)
            }
            Regex("""/embed-[a-z0-9]{12}\.html""").containsMatchIn(iframe) -> {
                // formationfeed.net vb. XFileSharing oynatıcıları
                var bulundu = false
                JwKaynak().getUrl(iframe, "${mainUrl}/", subtitleCallback) { bulundu = true; callback.invoke(it) }
                if (!bulundu) loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            }
            else -> loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }

    /** Oynatıcı adresi AES-256-CBC ile şifrelenmiş halde gelir; anahtar k1 XOR k2 ile elde edilir. */
    private fun coz(enc: EncData): String? {
        return runCatching {
            val k1  = base64DecodeArray(enc.k1 ?: return null)
            val k2  = base64DecodeArray(enc.k2 ?: return null)
            val key = ByteArray(minOf(k1.size, k2.size)) { (k1[it].toInt() xor k2[it].toInt()).toByte() }
            val iv  = base64DecodeArray(enc.iv ?: return null)
            val ct  = base64DecodeArray(enc.c ?: return null)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    data class PlayerConfig(
        @JsonProperty("success") val success: Boolean?     = null,
        @JsonProperty("config")  val config: ConfigData?   = null,
        @JsonProperty("enc")     val enc: EncData?         = null
    )

    data class ConfigData(
        @JsonProperty("v") val v: String? = null,
        @JsonProperty("t") val t: String? = null,
        @JsonProperty("p") val p: String? = null
    )

    data class EncData(
        @JsonProperty("c")  val c: String?  = null,
        @JsonProperty("iv") val iv: String? = null,
        @JsonProperty("k1") val k1: String? = null,
        @JsonProperty("k2") val k2: String? = null
    )
}

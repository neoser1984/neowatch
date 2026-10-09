package com.neowatch

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SelcukFlix : MainAPI() {
    override var mainUrl              = "https://selcukflix.app"
    override var name                 = "SelcukFlix"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler" to "Son Bölümler",
        "${mainUrl}/film-izle"    to "Yeni Filmler",
        "${mainUrl}/kesfet"       to "Yeni Diziler",
        "${mainUrl}/trend"        to "Trend Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Site listeleri JavaScript ile sayfaladığı için yalnızca ilk sayfa alınır
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val document = app.get(request.data, referer = "${mainUrl}/").document
        val home     = if (request.data.endsWith("/tum-bolumler")) {
            document.select("a[href*='/bolum-']").filter { it.selectFirst("h3") != null }.mapNotNull { it.sonBolumler() }
        } else {
            document.select("a[href^='/dizi/'], a[href^='dizi/'], a[href^='/film/'], a[href^='film/']")
                .filter { it.selectFirst("img") != null && !it.attr("href").contains("/sezon-") }
                .mapNotNull { it.toSearchResult() }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title").removeSuffix(" izle").trim().ifBlank {
            this.selectFirst("h3, h2")?.text()?.trim()
                ?: this.selectFirst("img")?.attr("alt")?.removeSuffix(" izle")?.replace(Regex("""\s\d{4}$"""), "")?.trim()
                ?: ""
        }
        if (title.isBlank()) return null

        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name   = this.selectFirst("h3")?.text()?.trim() ?: return null
        val epName = this.selectFirst("h3 + div")?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "")
        val href   = fixUrlNull(this.attr("href")) ?: return null

        return newTvSeriesSearchResponse(if (epName.isNullOrBlank()) name else "$name - $epName", href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(this@sonBolumler.selectFirst("img")?.attr("src"))
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val yanit = app.post(
            "${mainUrl}/api/bg/searchContent",
            params  = mapOf("searchterm" to query),
            referer = "${mainUrl}/",
            headers = mapOf("Accept" to "application/json, text/plain, */*")
        ).parsedSafe<SifreliYanit>() ?: return emptyList()

        val veri = jsonOku<AramaSonuc>(coz(yanit.response ?: return emptyList()) ?: return emptyList()) ?: return emptyList()

        return veri.result.orEmpty().mapNotNull { item ->
            val title = item.name ?: return@mapNotNull null
            val slug  = item.slug ?: return@mapNotNull null
            val href  = fixUrl("/${slug.trimStart('/')}")

            if (item.type.equals("Movies", true) || slug.startsWith("film/")) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = resim(item.poster)
                    this.year      = item.year
                    this.score     = Score.from10(item.imdb)
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = resim(item.poster)
                    this.year      = item.year
                    this.score     = Score.from10(item.imdb)
                }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        var veri = sayfaVerisi(app.get(url, referer = "${mainUrl}/").document)?.sonuc ?: return null

        // Son bölümler listesinden gelen bölüm bağlantısını dizi sayfasına çevir
        if (veri.findedType == "Episodes") {
            val diziSlug = veri.related?.seriesDetail?.result?.slug ?: return null
            veri = sayfaVerisi(app.get(fixUrl("/${diziSlug.trimStart('/')}"), referer = "${mainUrl}/").document)?.sonuc ?: return null
        }

        val detay = veri.findedResult?.result ?: return null
        val title = detay.cultureTitle?.takeIf { it.isNotBlank() && it != detay.title }?.let { "${detay.title} - $it" } ?: detay.title ?: return null
        val href  = fixUrl("/${(detay.slug ?: url.substringAfter(mainUrl)).trimStart('/')}")

        val castlar = (veri.related?.casts?.result ?: veri.related?.movieCasts?.result).orEmpty().mapNotNull { cast ->
            Actor(cast.name ?: return@mapNotNull null, resim(cast.image)) to cast.role
        }
        val tags = veri.related?.categories?.result?.mapNotNull { it.name }
            ?: detay.categories?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

        if (veri.findedType == "Movies") {
            return newMovieLoadResponse(title, href, TvType.Movie, href) {
                this.posterUrl           = resim(detay.poster)
                this.backgroundPosterUrl = resim(detay.back)
                this.year                = detay.year
                this.plot                = detay.description
                this.tags                = tags
                this.duration            = detay.minutes?.takeIf { it > 0 }
                this.score               = Score.from10(detay.imdb)
                addActors(castlar)
            }
        }

        val episodes = veri.related?.seasons?.result.orEmpty().flatMap { sezon ->
            sezon.episodes.orEmpty().mapNotNull { bolum ->
                val slug = bolum.slug ?: return@mapNotNull null
                newEpisode(fixUrl("/${slug.trimStart('/')}")) {
                    this.name        = bolum.subtitle?.takeIf { it != bolum.text } ?: bolum.text
                    this.season      = bolum.season ?: sezon.season
                    this.episode     = bolum.episode
                    this.description = bolum.description
                    this.addDate(bolum.releaseDate?.substringBefore("T"))
                }
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, href, TvType.TvSeries, episodes) {
            this.posterUrl           = resim(detay.poster)
            this.backgroundPosterUrl = resim(detay.back)
            this.year                = detay.year
            this.plot                = detay.description
            this.tags                = tags
            this.duration            = detay.minutes?.takeIf { it > 0 }
            this.score               = Score.from10(detay.imdb)
            addActors(castlar)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("SCF", "data » $data")
        val ham = sayfaVerisi(app.get(data, referer = "${mainUrl}/").document)?.ham ?: return false

        // ? Bölüm (getEpisodeSources) ve film (getMoviePartSourcesById_*) kaynaklarının tamamı
        val iframes = Regex("""source_content"\s*:\s*"<iframe[^>]*?src=\\"([^"\\]+)\\"""").findAll(ham)
            .map { it.groupValues[1] }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .distinct()
            .toList()

        iframes.forEach { iframe ->
            Log.d("SCF", "iframe » $iframe")
            runCatching {
                if (ContentXGenel.uygunMu(iframe)) {
                    ContentXGenel().getUrl(iframe, "${mainUrl}/", subtitleCallback, callback)
                } else {
                    loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
                }
            }
        }

        return iframes.isNotEmpty()
    }

    private data class SayfaVeri(val ham: String, val sonuc: SayfaSonuc?)

    // ! __NEXT_DATA__ içindeki "secureData" alanı AES-256-CBC ile şifrelenmiştir
    private fun sayfaVerisi(document: Document): SayfaVeri? {
        val nextData = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        val secure   = jsonOku<NextData>(nextData)?.props?.pageProps?.secureData ?: return null
        val cozulmus = coz(secure) ?: return null

        return SayfaVeri(cozulmus, jsonOku<SecureData>(cozulmus)?.content?.result)
    }

    private fun coz(sifreli: String): String? {
        return runCatching {
            val hash    = MessageDigest.getInstance("SHA-256").digest("!!22xx!!90!!".toByteArray(Charsets.UTF_8))
            val anahtar = base64Encode(hash).substring(0, 32).toByteArray(Charsets.UTF_8)
            val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(anahtar, "AES"), IvParameterSpec(ByteArray(16)))
            String(cipher.doFinal(base64DecodeArray(sifreli)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun resim(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.replace("images-macellan-online.cdn.ampproject.org/i/s/", "")
    }

    // ? JSON modelleri
    data class SifreliYanit(@JsonProperty("response") val response: String? = null)

    data class AramaSonuc(@JsonProperty("result") val result: List<AramaItem>? = null)

    data class AramaItem(
        @JsonProperty("object_name")               val name: String?   = null,
        @JsonProperty("used_slug")                 val slug: String?   = null,
        @JsonProperty("used_type")                 val type: String?   = null,
        @JsonProperty("object_release_year")       val year: Int?      = null,
        @JsonProperty("object_related_imdb_point") val imdb: Double?   = null,
        @JsonProperty("object_poster_url")         val poster: String? = null
    )

    data class NextData(@JsonProperty("props") val props: NextProps? = null)
    data class NextProps(@JsonProperty("pageProps") val pageProps: PageProps? = null)
    data class PageProps(@JsonProperty("secureData") val secureData: String? = null)

    data class SecureData(@JsonProperty("content") val content: SecureContent? = null)
    data class SecureContent(@JsonProperty("result") val result: SayfaSonuc? = null)

    data class SayfaSonuc(
        @JsonProperty("FindedType")     val findedType: String?        = null,
        @JsonProperty("FindedResult")   val findedResult: DetaySonuc?  = null,
        @JsonProperty("RelatedResults") val related: Related?          = null
    )

    data class DetaySonuc(@JsonProperty("result") val result: Detay? = null)

    data class Detay(
        @JsonProperty("original_title") val title: String?        = null,
        @JsonProperty("culture_title")  val cultureTitle: String? = null,
        @JsonProperty("description")    val description: String?  = null,
        @JsonProperty("release_year")   val year: Int?            = null,
        @JsonProperty("imdb_point")     val imdb: Double?         = null,
        @JsonProperty("total_minutes")  val minutes: Int?         = null,
        @JsonProperty("poster_url")     val poster: String?       = null,
        @JsonProperty("back_url")       val back: String?         = null,
        @JsonProperty("categories")     val categories: String?   = null,
        @JsonProperty("used_slug")      val slug: String?         = null
    )

    data class Related(
        @JsonProperty("getSerieCastsById")         val casts: CastListe?          = null,
        @JsonProperty("getMovieCastsById")         val movieCasts: CastListe?     = null,
        @JsonProperty("getSerieCategoriesById")    val categories: KategoriListe? = null,
        @JsonProperty("getSerieSeasonAndEpisodes") val seasons: SezonListe?       = null,
        @JsonProperty("getSeriesDetail")           val seriesDetail: DetaySonuc?  = null
    )

    data class CastListe(@JsonProperty("result") val result: List<Cast>? = null)
    data class Cast(
        @JsonProperty("name")       val name: String?  = null,
        @JsonProperty("cast_image") val image: String? = null,
        @JsonProperty("role_name")  val role: String?  = null
    )

    data class KategoriListe(@JsonProperty("result") val result: List<Kategori>? = null)
    data class Kategori(@JsonProperty("name") val name: String? = null)

    data class SezonListe(@JsonProperty("result") val result: List<Sezon>? = null)
    data class Sezon(
        @JsonProperty("season_no") val season: Int?           = null,
        @JsonProperty("episodes")  val episodes: List<Bolum>? = null
    )
    data class Bolum(
        @JsonProperty("season_no")           val season: Int?         = null,
        @JsonProperty("episode_no")          val episode: Int?        = null,
        @JsonProperty("episode_text")        val text: String?        = null,
        @JsonProperty("episode_subtitle")    val subtitle: String?    = null,
        @JsonProperty("episode_description") val description: String? = null,
        @JsonProperty("release_date")        val releaseDate: String? = null,
        @JsonProperty("used_slug")           val slug: String?        = null
    )
}

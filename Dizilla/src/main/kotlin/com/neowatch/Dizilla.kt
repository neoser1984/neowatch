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

class Dizilla : MainAPI() {
    override var mainUrl              = "https://dizilla.now"
    override var name                 = "Dizilla"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    // ! CloudFlare bypass
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/"                      to "Son Bölümler",
        "${mainUrl}/yabanci-dizi-izle"     to "Yabancı Diziler",
        "${mainUrl}/anime-izle"            to "Anime",
        "${mainUrl}/kdrama-izle"           to "Kore Dizileri",
        "${mainUrl}/dizi-turu/aile"        to "Aile",
        "${mainUrl}/dizi-turu/aksiyon"     to "Aksiyon",
        "${mainUrl}/dizi-turu/bilim-kurgu" to "Bilim Kurgu",
        "${mainUrl}/dizi-turu/romantik"    to "Romantik",
        "${mainUrl}/dizi-turu/komedi"      to "Komedi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Site listeleri JavaScript ile sayfaladığı için yalnızca ilk sayfa alınır
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val document = app.get(request.data, referer = "${mainUrl}/").document
        val home     = if (request.data == "${mainUrl}/") {
            document.select("a[href]").filter { Regex("""-sezon-\d+-bolum""").containsMatchIn(it.attr("href")) && it.selectFirst("h3") != null }
                .mapNotNull { it.sonBolumler() }.distinctBy { it.url }
        } else {
            document.select("a[href^='/dizi/'], a[href^='dizi/']").filter { it.selectFirst("img") != null }
                .mapNotNull { it.diziler() }.distinctBy { it.url }
        }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.diziler(): SearchResponse? {
        val kart      = this.closest("span.prm-borderb") ?: this
        val title     = this.attr("title").removeSuffix(" izle").trim().ifBlank {
            kart.selectFirst("h3, span.line-clamp-1")?.text()?.trim() ?: this.selectFirst("img")?.attr("alt")?.trim() ?: ""
        }
        if (title.isBlank()) return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val score     = this.selectFirst("h4")?.text()?.trim()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.score     = Score.from10(score)
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

            newTvSeriesSearchResponse(title, fixUrl("/${slug.trimStart('/')}"), TvType.TvSeries) {
                this.posterUrl = resim(item.poster)
                this.year      = item.year
                this.score     = Score.from10(item.imdb)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        var veri = sayfaVerisi(app.get(url, referer = "${mainUrl}/").document) ?: return null

        // Son bölümler listesinden gelen bölüm bağlantısını dizi sayfasına çevir
        if (veri.findedType == "Episodes") {
            val diziSlug = veri.related?.seriesDetail?.result?.slug ?: return null
            veri = sayfaVerisi(app.get(fixUrl("/${diziSlug.trimStart('/')}"), referer = "${mainUrl}/").document) ?: return null
        }

        val dizi        = veri.findedResult?.result ?: return null
        val title       = dizi.title ?: return null
        val diziUrl     = fixUrl("/${(dizi.slug ?: url.substringAfter(mainUrl)).trimStart('/')}")
        val tags        = veri.related?.categories?.result?.mapNotNull { it.name }
        val actors      = veri.related?.casts?.result?.mapNotNull { cast ->
            Actor(cast.name ?: return@mapNotNull null, resim(cast.image)) to cast.role
        }

        val episodes = veri.related?.seasons?.result.orEmpty().flatMap { sezon ->
            sezon.episodes.orEmpty().mapNotNull { bolum ->
                val slug = bolum.slug ?: return@mapNotNull null
                newEpisode(fixUrl("/${slug.trimStart('/')}")) {
                    this.name        = listOfNotNull(bolum.subtitle?.takeIf { it != bolum.text }, bolum.language?.takeIf { it.contains("Dublaj") })
                        .joinToString(" - ").ifBlank { bolum.text }
                    this.season      = bolum.season ?: sezon.season
                    this.episode     = bolum.episode
                    this.description = bolum.description
                    this.addDate(bolum.releaseDate?.substringBefore("T"))
                }
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, diziUrl, TvType.TvSeries, episodes) {
            this.posterUrl           = resim(dizi.poster)
            this.backgroundPosterUrl = resim(dizi.back)
            this.year                = dizi.year
            this.plot                = dizi.description
            this.tags                = tags
            this.duration            = dizi.minutes?.takeIf { it > 0 }
            this.score               = Score.from10(dizi.imdb)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZL", "data » $data")
        val veri     = sayfaVerisi(app.get(data, referer = "${mainUrl}/").document) ?: return false
        val kaynaklar = veri.related?.episodeSources?.result.orEmpty()

        val iframes = kaynaklar.mapNotNull { kaynak ->
            val src = Regex("""src=["']([^"']+)["']""").find(kaynak.content ?: "")?.groupValues?.get(1) ?: return@mapNotNull null
            if (src.startsWith("//")) "https:$src" else src
        }.distinct()

        iframes.forEach { iframe ->
            Log.d("DZL", "iframe » $iframe")
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

    // ! __NEXT_DATA__ içindeki "secureData" alanı AES-256-CBC ile şifrelenmiştir
    private fun sayfaVerisi(document: Document): SayfaSonuc? {
        val nextData = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        val secure   = jsonOku<NextData>(nextData)?.props?.pageProps?.secureData ?: return null
        val cozulmus = coz(secure) ?: return null

        return jsonOku<SecureData>(cozulmus)?.content?.result
    }

    private fun coz(sifreli: String): String? {
        return runCatching {
            val hash   = MessageDigest.getInstance("SHA-256").digest("!!22xx!!90!!".toByteArray(Charsets.UTF_8))
            val anahtar = base64Encode(hash).substring(0, 32).toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
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
        @JsonProperty("FindedType")     val findedType: String?       = null,
        @JsonProperty("FindedResult")   val findedResult: DiziSonuc?  = null,
        @JsonProperty("RelatedResults") val related: Related?         = null
    )

    data class DiziSonuc(@JsonProperty("result") val result: DiziDetay? = null)

    data class DiziDetay(
        @JsonProperty("original_title") val title: String?       = null,
        @JsonProperty("description")    val description: String? = null,
        @JsonProperty("release_year")   val year: Int?           = null,
        @JsonProperty("imdb_point")     val imdb: Double?        = null,
        @JsonProperty("total_minutes")  val minutes: Int?        = null,
        @JsonProperty("poster_url")     val poster: String?      = null,
        @JsonProperty("back_url")       val back: String?        = null,
        @JsonProperty("used_slug")      val slug: String?        = null
    )

    data class Related(
        @JsonProperty("getSerieCastsById")         val casts: CastListe?          = null,
        @JsonProperty("getSerieCategoriesById")    val categories: KategoriListe? = null,
        @JsonProperty("getSerieSeasonAndEpisodes") val seasons: SezonListe?       = null,
        @JsonProperty("getEpisodeSources")         val episodeSources: KaynakListe? = null,
        @JsonProperty("getSeriesDetail")           val seriesDetail: DiziSonuc?   = null
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
        @JsonProperty("season_no") val season: Int?             = null,
        @JsonProperty("episodes")  val episodes: List<Bolum>?   = null
    )
    data class Bolum(
        @JsonProperty("season_no")             val season: Int?         = null,
        @JsonProperty("episode_no")            val episode: Int?        = null,
        @JsonProperty("episode_text")          val text: String?        = null,
        @JsonProperty("episode_subtitle")      val subtitle: String?    = null,
        @JsonProperty("episode_description")   val description: String? = null,
        @JsonProperty("episode_language_name") val language: String?    = null,
        @JsonProperty("release_date")          val releaseDate: String? = null,
        @JsonProperty("used_slug")             val slug: String?        = null
    )

    data class KaynakListe(@JsonProperty("result") val result: List<Kaynak>? = null)
    data class Kaynak(
        @JsonProperty("source_name")    val name: String?     = null,
        @JsonProperty("source_content") val content: String?  = null,
        @JsonProperty("language_name")  val language: String? = null
    )
}

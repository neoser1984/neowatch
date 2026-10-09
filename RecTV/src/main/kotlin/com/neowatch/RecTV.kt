package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RecTV
 *
 * - API adresi uygulamanın Firebase Remote Config'inden ("api_url") okunur; okunamazsa [mainUrl] kullanılır.
 * - Bölüm (season) ve arama uçları HMAC imzası ister:
 *   X-HMAC = HMAC-SHA256(anahtar, "METOT\nYOL\nZAMAN\nNONCE\nsha256(gövde)")
 * - Kaynaklarda düz "url" yoksa "enc_url" AES-256-GCM ile çözülür.
 */
class RecTV : MainAPI() {
    override var mainUrl              = "https://a.psrectv80.xyz"
    override var name                 = "RecTV"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.Live, TvType.TvSeries)

    companion object {
        private const val SW_KEY      = "4F5A9C3D9A86FA54EACEDDD635185/c3c5bd17-e37b-4b94-a944-8a3688a30452"
        private const val HMAC_KEY    = "3508611138826751fdf77beaa6f93eb93fd27e6a5acb910e7aad22665513dd6e"
        private const val AES_KEY_HEX = "666482389dc76bfa57068407418f7dac9f6c14b6868856b169165b9fac7d812e"
        private const val APP_VERSION = "157"
        private const val CLIENT_ID   = "rectv-android"
        private const val API_UA      = "googleusercontent"
        private const val REFERER     = "https://twitter.com/"

        private const val FIREBASE_URL     = "https://firebaseremoteconfig.googleapis.com/v1/projects/791583031279/namespaces/firebase:fetch"
        private const val FIREBASE_KEY     = "AIzaSyBbhpzG8Ecohu9yArfCO5tF13BQLhjLahc"
        private const val FIREBASE_PACKAGE = "com.rectv.shot"
    }

    @Volatile private var apiTabani: String? = null

    override val mainPage = mainPageOf(
        "/api/channel/by/filtres/0/0/SAYFA/"      to "Canlı",
        "/api/movie/by/filtres/0/created/SAYFA/"  to "Son Filmler",
        "/api/serie/by/filtres/0/created/SAYFA/"  to "Son Diziler",
        "/api/movie/by/filtres/14/created/SAYFA/" to "Aile",
        "/api/movie/by/filtres/1/created/SAYFA/"  to "Aksiyon",
        "/api/movie/by/filtres/13/created/SAYFA/" to "Animasyon",
        "/api/movie/by/filtres/19/created/SAYFA/" to "Belgesel",
        "/api/movie/by/filtres/4/created/SAYFA/"  to "Bilim Kurgu",
        "/api/movie/by/filtres/2/created/SAYFA/"  to "Dram",
        "/api/movie/by/filtres/10/created/SAYFA/" to "Fantastik",
        "/api/movie/by/filtres/3/created/SAYFA/"  to "Komedi",
        "/api/movie/by/filtres/8/created/SAYFA/"  to "Korku",
        "/api/movie/by/filtres/17/created/SAYFA/" to "Macera",
        "/api/movie/by/filtres/5/created/SAYFA/"  to "Romantik"
    )

    // ? API adresi (Firebase Remote Config → api_url)
    private suspend fun apiAdresi(): String {
        apiTabani?.let { return it }

        val bulunan = runCatching {
            val govde = JSONObject()
                .put("appBuild", "81")
                .put("appInstanceId", "evON8ZdeSr-0wUYxf0qs68")
                .put("appId", "1:791583031279:android:1")
                .toString()

            val yanit = app.post(
                FIREBASE_URL,
                headers     = mapOf(
                    "X-Goog-Api-Key"    to FIREBASE_KEY,
                    "X-Android-Package" to FIREBASE_PACKAGE,
                    "User-Agent"        to "Dalvik/2.1.0 (Linux; U; Android 12)"
                ),
                requestBody = govde.toRequestBody("application/json; charset=utf-8".toMediaType())
            ).text

            JSONObject(yanit).optJSONObject("entries")?.optString("api_url")
                ?.trim()?.removeSuffix("/")?.removeSuffix("/api")
                ?.takeIf { it.startsWith("http") }
        }.getOrNull()

        val taban = bulunan ?: mainUrl
        Log.d("RCTV", "API adresi » $taban")
        apiTabani = taban
        mainUrl   = taban
        return taban
    }

    // ? İmzalı istek başlıkları
    private fun imzaliBasliklar(metot: String, yol: String, govde: String = ""): Map<String, String> {
        val zaman = (System.currentTimeMillis() / 1000L).toString()
        val nonce = UUID.randomUUID().toString()
        val imza  = hmacHex("$metot\n$yol\n$zaman\n$nonce\n${sha256Hex(govde)}")

        return mapOf(
            "User-Agent"    to API_UA,
            "Referer"       to REFERER,
            "Accept"        to "application/json",
            "X-Timestamp"   to zaman,
            "X-Nonce"       to nonce,
            "X-HMAC"        to imza,
            "X-Signature"   to imza,
            "X-App-Version" to APP_VERSION,
            "X-Client-Id"   to CLIENT_ID
        )
    }

    private suspend fun apiGet(yol: String): String {
        val taban = apiAdresi()
        return app.get("$taban$yol", headers = imzaliBasliklar("GET", yol)).text
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val yol   = request.data.replace("SAYFA", "${page - 1}") + "$SW_KEY/"
        val liste = jsonOku<List<RecItem>>(apiGet(yol)).orEmpty()

        val icerik = liste.map { item ->
            val veri = jacksonObjectMapper().writeValueAsString(item)

            if (item.label != "CANLI" && item.label != "Canlı") {
                if (item.type == "serie") {
                    newTvSeriesSearchResponse(item.title, veri, TvType.TvSeries) { this.posterUrl = item.image }
                } else {
                    newMovieSearchResponse(item.title, veri, TvType.Movie) { this.posterUrl = item.image }
                }
            } else {
                newLiveSearchResponse(item.title, veri, TvType.Live) { this.posterUrl = item.image }
            }
        }

        return newHomePageResponse(request.name, icerik, hasNext = liste.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val kodlu   = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val veriler = jsonOku<RecSearch>(apiGet("/api/search/$kodlu/$SW_KEY/")) ?: return emptyList()

        val sonuclar = mutableListOf<SearchResponse>()

        veriler.channels?.forEach { item ->
            val veri = jacksonObjectMapper().writeValueAsString(item)
            sonuclar.add(newLiveSearchResponse(item.title, veri, TvType.Live) { this.posterUrl = item.image })
        }

        veriler.posters?.forEach { item ->
            val veri = jacksonObjectMapper().writeValueAsString(item)
            if (item.type == "serie") {
                sonuclar.add(newTvSeriesSearchResponse(item.title, veri, TvType.TvSeries) { this.posterUrl = item.image })
            } else {
                sonuclar.add(newMovieSearchResponse(item.title, veri, TvType.Movie) { this.posterUrl = item.image })
            }
        }

        return sonuclar
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val veri = jsonOku<RecItem>(url) ?: return null

        if (veri.type == "serie") {
            val sezonlar = jsonOku<List<RecDizi>>(apiGet("/api/season/by/serie/${veri.id}/$SW_KEY/")) ?: return null

            val episodes    = mutableMapOf<DubStatus, MutableList<Episode>>()
            val numberRegex = Regex("\\d+")

            for (sezon in sezonlar) {
                val dublaj = when {
                    sezon.title.contains("altyazı", ignoreCase = true) -> DubStatus.Subbed
                    sezon.title.contains("dublaj", ignoreCase = true)  -> DubStatus.Dubbed
                    else                                               -> DubStatus.None
                }

                for (bolum in sezon.episodes) {
                    val kaynak = bolum.sources.firstNotNullOfOrNull { kaynakAdresi(it) } ?: continue

                    episodes.getOrPut(dublaj) { mutableListOf() }.add(newEpisode(kaynak) {
                        this.name        = bolum.title
                        this.season      = numberRegex.find(sezon.title)?.value?.toIntOrNull()
                        this.episode     = numberRegex.find(bolum.title)?.value?.toIntOrNull()
                        this.description = sezon.title.substringAfter(".S ")
                        this.posterUrl   = veri.image
                    })
                }
            }

            return newAnimeLoadResponse(name = veri.title, url = url, type = TvType.TvSeries, comingSoonIfNone = false) {
                this.episodes  = episodes.mapValues { it.value.toList() }.toMutableMap()
                this.posterUrl = veri.image
                this.plot      = veri.description
                this.year      = veri.year
                this.tags      = veri.genres?.map { it.title }
                this.score     = Score.from10("${veri.rating}")
            }
        }

        return if (veri.label != "CANLI" && veri.label != "Canlı") {
            newMovieLoadResponse(veri.title, url, TvType.Movie, url) {
                this.posterUrl = veri.image
                this.plot      = veri.description
                this.year      = veri.year
                this.tags      = veri.genres?.map { it.title }
                this.score     = Score.from10("${veri.rating}")
            }
        } else {
            newLiveStreamLoadResponse(veri.title, url, url) {
                this.posterUrl = veri.image
                this.plot      = veri.description
                this.tags      = veri.genres?.map { it.title }
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (data.startsWith("http")) {
            Log.d("RCTV", "data » $data")
            callback.invoke(baglanti(this.name, data, null))
            return true
        }

        val veri    = jsonOku<RecItem>(data) ?: return false
        var bulundu = false

        for (source in veri.sources) {
            val adres = kaynakAdresi(source) ?: continue
            Log.d("RCTV", "source » ${source.type} » $adres")
            callback.invoke(baglanti("${this.name} - ${source.title ?: source.type ?: "Kaynak"}", adres, source.type))
            bulundu = true
        }

        return bulundu
    }

    private suspend fun baglanti(isim: String, adres: String, tur: String?): ExtractorLink {
        val linkTuru = when {
            tur == "mp4" || adres.substringBefore("?").endsWith(".mp4") && !adres.contains(".m3u8") -> ExtractorLinkType.VIDEO
            else                                                                                       -> ExtractorLinkType.M3U8
        }

        return newExtractorLink(source = this.name, name = isim, url = adres, type = linkTuru) {
            this.referer = REFERER
            this.quality = Qualities.Unknown.value
            this.headers = mapOf("User-Agent" to API_UA, "Referer" to REFERER)
        }
    }

    private fun kaynakAdresi(source: Source): String? {
        source.url?.takeIf { it.startsWith("http") }?.let { return it }
        return source.encUrl?.let { encCoz(it) }?.takeIf { it.startsWith("http") }
    }

    // ? enc_url: base64( iv[12] + şifreli + etiket[16] ), AES-256-GCM
    private fun encCoz(sifreli: String): String? = runCatching {
        val ham    = base64DecodeArray(sifreli)
        if (ham.size < 28) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(AES_KEY_HEX), "AES"), GCMParameterSpec(128, ham.copyOfRange(0, 12)))
        String(cipher.doFinal(ham.copyOfRange(12, ham.size)), Charsets.UTF_8)
    }.getOrNull()

    private fun sha256Hex(metin: String): String =
        MessageDigest.getInstance("SHA-256").digest(metin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun hmacHex(mesaj: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(mesaj.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val istek = chain.request().newBuilder()
                .removeHeader("If-None-Match")
                .header("User-Agent", API_UA)
                .header("Referer", REFERER)
                .build()
            chain.proceed(istek)
        }
    }
}

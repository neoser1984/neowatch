package com.neowatch

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// Ekim 2026: site Next.js tabanlı yeni bir arayüze geçti (dizipal1592). Kartlar a.tc (dizi/film) ve a.ecl (bölüm);
// dizi/film bilgileri, sezon-bölüm listesi ve şifreli oynatıcı adresi sayfadaki RSC verisinde (self.__next_f.push) geliyor.
class DiziPalGuncel : MainAPI() {
    override var mainUrl              = "https://dizipal1592.com"
    override var name                 = "DiziPalGuncel"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // ! CloudFlare bypass
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/yeni-eklenen-dizi-bolumler" to "Son Bölümler",
        "${mainUrl}/kesfet"                     to "Diziler",
        "${mainUrl}/film-izle"                  to "Filmler",
        "${mainUrl}/trendler"                   to "Trendler",
        "${mainUrl}/anime"                      to "Anime",
        "${mainUrl}/kanal/netflix"              to "Netflix",
        "${mainUrl}/kanal/exxen"                to "Exxen",
        "${mainUrl}/kanal/disney"               to "Disney+",
        "${mainUrl}/kanal/amazon"               to "Amazon Prime",
        "${mainUrl}/kanal/apple-tv"             to "Apple TV+",
        "${mainUrl}/kanal/hbo-max"              to "HBO Max",
        "${mainUrl}/kanal/tabii"                to "Tabii",
        "${mainUrl}/kanal/gain"                 to "Gain",
        "${mainUrl}/kategori/aksiyon"           to "Aksiyon",
        "${mainUrl}/kategori/bilim-kurgu"       to "Bilim Kurgu",
        "${mainUrl}/kategori/dram"              to "Dram",
        "${mainUrl}/kategori/gerilim"           to "Gerilim",
        "${mainUrl}/kategori/komedi"            to "Komedi",
        "${mainUrl}/kategori/korku"             to "Korku",
        "${mainUrl}/kategori/romantik"          to "Romantik",
        "${mainUrl}/kategori/suc"               to "Suç",
        "${mainUrl}/kategori/belgesel"          to "Belgesel",
    )

    // Sayfalama yalnızca bu listelerde var (?page=N)
    private fun sayfali(adres: String) =
        adres.contains("/kesfet") || adres.contains("/film-izle") || adres.contains("/kategori/")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sayfaVar = sayfali(request.data)
        if (!sayfaVar && page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val adres    = if (sayfaVar) "${request.data}?page=$page" else request.data
        val document = app.get(adres, referer = "${mainUrl}/").document
        val anime    = request.data.contains("/anime")

        val home = (document.select("a.ecl").mapNotNull { it.bolumKarti() } +
                    document.select("a.tc").mapNotNull { it.icerikKarti(anime) }).distinctBy { it.url }

        return newHomePageResponse(request.name, home, hasNext = sayfaVar && home.isNotEmpty())
    }

    private fun Element.posterAl(): String? {
        val img = this.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("data-srcset").substringBefore(" ") }
            .ifBlank { img.attr("srcset").substringBefore(" ") }.ifBlank { img.attr("src") }
        return if (src.isBlank() || src.startsWith("data:")) null else fixUrlNull(src)
    }

    // Bölüm kartı: div.ec > a.ecl (resim) + div > span.tn (dizi adı) + span (sezon/bölüm)
    private fun Element.bolumKarti(): SearchResponse? {
        val href  = fixUrlNull(this.attr("href")) ?: return null
        val kutu  = this.parent() ?: this
        val dizi  = kutu.selectFirst("span.tn")?.text()?.trim()?.ifBlank { null }
            ?: this.attr("aria-label").substringBefore(",").trim().ifBlank { null } ?: return null
        val bilgi = kutu.selectFirst("span.tn + span")?.text()?.trim()
            ?.replace(". Sezon ", "x")?.replace(". Bölüm", "")?.ifBlank { null }

        return newTvSeriesSearchResponse(if (bilgi == null) dizi else "$dizi $bilgi", href, TvType.TvSeries) {
            this.posterUrl = this@bolumKarti.posterAl()
        }
    }

    // Dizi/film kartı: a.tc > span.fr (resim) + span > span.tt|span.ft1 (ad) + span (yıl / türler · yıl)
    private fun Element.icerikKarti(anime: Boolean = false): SearchResponse? {
        val href  = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("span.tt, span.ft1")?.text()?.trim()?.ifBlank { null }
            ?: this.attr("aria-label").substringBefore(",").trim().ifBlank { null } ?: return null
        val year  = Regex("""\b(19|20)\d{2}\b""").find(this.selectFirst("span.tt + span, span.ft1 + span")?.text().orEmpty())?.value?.toIntOrNull()

        return if (href.contains("/movies/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = this@icerikKarti.posterAl()
                this.year      = year
            }
        } else {
            newTvSeriesSearchResponse(title, href, if (anime) TvType.Anime else TvType.TvSeries) {
                this.posterUrl = this@icerikKarti.posterAl()
                this.year      = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val govde = """{"action":"searchContent","params":[${mapper.writeValueAsString(query)}]}"""
        val yanit = app.post(
            "${mainUrl}/api/mj",
            requestBody = govde.toRequestBody("application/json".toMediaType()),
            referer     = "${mainUrl}/",
            headers     = mapOf("Origin" to mainUrl, "Accept" to "application/json")
        ).text

        return jsonOku<AramaYanit>(yanit)?.result.orEmpty().mapNotNull { item ->
            val title = item.name ?: return@mapNotNull null
            val slug  = item.slug ?: return@mapNotNull null
            val href  = fixUrl(if (slug.startsWith("/")) slug else "/$slug")

            if (item.type.equals("Movies", ignoreCase = true) || item.type.equals("Movie", ignoreCase = true) || slug.startsWith("movies")) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = item.poster
                    this.year      = item.year
                    this.score     = Score.from10(item.imdb)
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = item.poster
                    this.year      = item.year
                    this.score     = Score.from10(item.imdb)
                }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        var adres = url
        var html  = app.get(url, referer = "${mainUrl}/").text
        var rsc   = Rsc.metin(html)

        // Son bölümler listesinden gelen bölüm bağlantısını dizi sayfasına çevir
        if (url.contains("/bolum/")) {
            val dizi = (rsc.jsonDeger("seriesHref") ?: rsc.jsonDeger("mjSeriesHref"))?.let { fixUrl(it) }
                ?: Regex("""href="(/series/[^"]+)"""").find(html)?.groupValues?.get(1)?.let { fixUrl(it) }
                ?: return null
            adres = dizi
            html  = app.get(dizi, referer = "${mainUrl}/").text
            rsc   = Rsc.metin(html)
        }

        val baslik = rsc.jsonDeger("mjTitle")?.trim()?.ifBlank { null }
            ?: Regex("""<h1[^>]*>(.*?)</h1>""").find(html)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()?.ifBlank { null }
            ?: return null
        val poster  = rsc.jsonDeger("mjPoster")?.ifBlank { null }
        val yil     = rsc.jsonDeger("mjYear")?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        val konu    = rsc.jsonDeger("mjOverview")?.trim()?.ifBlank { null }
        val puan    = rsc.jsonDeger("mjImdb")?.trim()?.ifBlank { null }
        val sure    = rsc.jsonDeger("mjRuntime")?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val turler  = rsc.etiketler("mjGenres")
        val oyuncu  = rsc.etiketler("mjCast").map { Actor(it) }

        if (adres.contains("/movies/")) {
            return newMovieLoadResponse(baslik, adres, TvType.Movie, adres) {
                this.posterUrl = poster
                this.year      = yil
                this.plot      = konu
                this.tags      = turler
                this.duration  = sure
                this.score     = Score.from10(puan)
                addActors(oyuncu)
            }
        }

        val episodes = Rsc.bolumler(rsc).map { b ->
            newEpisode(fixUrl(b.href)) {
                this.name    = b.baslik?.ifBlank { null } ?: b.no?.let { "$it. Bölüm" }
                this.season  = b.sezon
                this.episode = b.no
            }
        }.distinctBy { it.data }

        val tvType = if (turler.any { it.contains("Anime", true) || it.contains("Animasyon", true) }) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(baslik, adres, tvType, episodes) {
            this.posterUrl = poster
            this.year      = yil
            this.plot      = konu
            this.tags      = turler
            this.score     = Score.from10(puan)
            addActors(oyuncu)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZPG", "data » $data")
        val html = app.get(data, referer = "${mainUrl}/").text
        val rsc  = Rsc.metin(html)

        // Altyazı / dublaj kaynakları ayrı sayfalar (?player=...); her biri kendi şifreli adresini taşır
        val kaynaklar = mutableListOf<Pair<String?, String>>()   // etiket → oynatıcı adresi
        oynaticiCoz(rsc, html)?.let { kaynaklar.add(Rsc.etiket(rsc, Rsc.yol(data)) to it) }

        Rsc.kaynaklar(rsc).filter { Rsc.yol(fixUrl(it.second)) != Rsc.yol(data) }.forEach { (etiket, href) ->
            runCatching {
                val diger = app.get(fixUrl(href), referer = data).text
                oynaticiCoz(Rsc.metin(diger), diger)?.let { kaynaklar.add(etiket to it) }
            }
        }

        var bulundu = false
        for ((etiket, adres) in kaynaklar.distinctBy { it.second }) {
            val iframe = if (adres.startsWith("//")) "https:$adres" else adres
            Log.d("DZPG", "iframe » $etiket » $iframe")
            val ek = etiket?.substringAfter("·")?.trim()?.ifBlank { null }

            val toplanan = mutableListOf<ExtractorLink>()
            runCatching {
                if (ContentXGenel.uygunMu(iframe)) {
                    ContentXGenel().getUrl(iframe, "${mainUrl}/", subtitleCallback) { toplanan.add(it) }
                } else {
                    loadExtractor(iframe, "${mainUrl}/", subtitleCallback) { toplanan.add(it) }
                }
            }.onFailure { Log.w("DZPG", "oynatıcı hatası » ${it.message}") }

            for (link in toplanan) {
                bulundu = true
                if (ek == null) {
                    callback.invoke(link)
                } else {
                    callback.invoke(
                        newExtractorLink(link.source, "${link.name} · $ek", link.url, link.type) {
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

    /** RSC'deki player.enc {ciphertext, iv, salt} + "k" parolasıyla iframe adresini çözer; eski [data-rm-k] yedektir. */
    private fun oynaticiCoz(rsc: String, html: String): String? {
        val oyuncu = rsc.indexOf("\"player\":{").takeIf { it >= 0 } ?: 0
        val enc    = Rsc.nesne(rsc, "\"enc\":", oyuncu)
        if (enc != null) {
            val parola = rsc.jsonDeger("k", rsc.indexOf("\"enc\":", oyuncu)) ?: RmkCoz.PAROLA
            RmkCoz.coz(enc, parola)?.let { return it.trim() }
            RmkCoz.coz(enc)?.let { return it.trim() }
        }
        val eski = Regex("""data-rm-k[^>]*>([^<]+)<""").find(html)?.groupValues?.get(1)?.replace("&quot;", "\"")
        return eski?.let { RmkCoz.coz(it) }?.trim()
    }

    private fun String.jsonDeger(anahtar: String, baslangic: Int = 0): String? = Rsc.deger(this, anahtar, baslangic)
    private fun String.etiketler(anahtar: String): List<String> = Rsc.etiketListesi(this, anahtar)

    data class AramaYanit(
        @JsonProperty("ok")     val ok: Boolean?             = null,
        @JsonProperty("result") val result: List<AramaItem>? = null
    )

    data class AramaItem(
        @JsonProperty("object_name")               val name: String?   = null,
        @JsonProperty("used_slug")                 val slug: String?   = null,
        @JsonProperty("used_type")                 val type: String?   = null,
        @JsonProperty("object_release_year")       val year: Int?      = null,
        @JsonProperty("object_related_imdb_point") val imdb: Double?   = null,
        @JsonProperty("object_poster_url")         val poster: String? = null
    )
}

/** Next.js RSC verisini (self.__next_f.push([1,"..."])) birleştirip içinden alan okuyan yardımcılar. */
internal object Rsc {
    data class Bolum(val href: String, val no: Int?, val sezon: Int?, val baslik: String?)

    private const val ISARET = "self.__next_f.push([1,\""

    fun metin(html: String): String {
        val sb = StringBuilder()
        var i  = html.indexOf(ISARET)
        while (i >= 0) {
            val (parca, son) = dizeOku(html, i + ISARET.length)
            sb.append(parca)
            i = html.indexOf(ISARET, son)
        }
        return sb.toString()
    }

    /** [bas] konumundan kapanış tırnağına kadar JSON dizesini okur; (metin, bitiş konumu) döndürür. */
    fun dizeOku(s: String, bas: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var j  = bas
        while (j < s.length) {
            val c = s[j]
            if (c == '\\' && j + 1 < s.length) {
                when (val n = s[j + 1]) {
                    'n'  -> sb.append('\n')
                    't'  -> sb.append('\t')
                    'r'  -> sb.append('\r')
                    'b'  -> sb.append('\u0008')
                    'f'  -> sb.append('\u000c')
                    'u'  -> {
                        val kod = if (j + 6 <= s.length) s.substring(j + 2, j + 6).toIntOrNull(16) else null
                        if (kod != null) { sb.append(kod.toChar()); j += 4 } else sb.append('u')
                    }
                    else -> sb.append(n)
                }
                j += 2
            } else if (c == '"') {
                return sb.toString() to j + 1
            } else {
                sb.append(c); j++
            }
        }
        return sb.toString() to j
    }

    fun deger(s: String, anahtar: String, baslangic: Int = 0): String? {
        val desen = "\"$anahtar\":\""
        val i = s.indexOf(desen, maxOf(0, baslangic)).takeIf { it >= 0 } ?: return null
        return dizeOku(s, i + desen.length).first
    }

    /** "anahtar":{ ... } ya da "anahtar":[ ... ] bölümünü (dizelerin içini sayarak) döndürür. */
    fun nesne(s: String, anahtar: String, baslangic: Int = 0): String? {
        val i = s.indexOf(anahtar, maxOf(0, baslangic)).takeIf { it >= 0 } ?: return null
        var j = i + anahtar.length
        while (j < s.length && s[j] != '{' && s[j] != '[') { if (!s[j].isWhitespace()) return null; j++ }
        if (j >= s.length) return null
        val bas = j
        var derinlik = 0
        while (j < s.length) {
            when (s[j]) {
                '"'      -> { j = dizeOku(s, j + 1).second; continue }
                '{', '[' -> derinlik++
                '}', ']' -> { derinlik--; if (derinlik == 0) return s.substring(bas, j + 1) }
            }
            j++
        }
        return null
    }

    fun etiketListesi(s: String, anahtar: String): List<String> {
        val dizi = nesne(s, "\"$anahtar\":") ?: return emptyList()
        return Regex(""""label":"((?:[^"\\]|\\.)*)"""").findAll(dizi).map { dizeOku(it.groupValues[1] + "\"", 0).first.trim() }
            .filter { it.isNotBlank() }.distinct().toList()
    }

    fun bolumler(s: String): List<Bolum> {
        val sezonlar = nesne(s, "\"seasons\":") ?: return emptyList()
        val sezonKonum = Regex(""""no":(\d+),"label":""").findAll(sezonlar).map { it.range.first to it.groupValues[1].toInt() }.toList()
        return Regex(""""n":(\d+),"title":"((?:[^"\\]|\\.)*)"[^{}]*?"href":"(/bolum/[^"]+)"""").findAll(sezonlar).map { m ->
            val href   = m.groupValues[3]
            val sxe    = Regex("""-(\d+)x(\d+)(?:-c\d+)?$""").find(href.substringBefore("?"))
            val sezon  = sxe?.groupValues?.get(1)?.toIntOrNull() ?: sezonKonum.lastOrNull { it.first < m.range.first }?.second
            Bolum(
                href   = href,
                no     = m.groupValues[1].toIntOrNull() ?: sxe?.groupValues?.get(2)?.toIntOrNull(),
                sezon  = sezon,
                baslik = dizeOku(m.groupValues[2] + "\"", 0).first
            )
        }.toList()
    }

    /** player.sources → (etiket, href) */
    fun kaynaklar(s: String): List<Pair<String?, String>> {
        val oyuncu = s.indexOf("\"player\":{").takeIf { it >= 0 } ?: return emptyList()
        val dizi   = nesne(s, "\"sources\":", oyuncu) ?: return emptyList()
        return Regex("""\{[^{}]*\}""").findAll(dizi).mapNotNull { m ->
            val nesne = m.value
            val href  = deger(nesne, "href") ?: return@mapNotNull null
            deger(nesne, "label") to href
        }.toList()
    }

    /** Geçerli sayfanın kaynak etiketi (ör. "PUB+ · Türkçe Altyazı") */
    fun etiket(s: String, yol: String): String? = kaynaklar(s).firstOrNull { yol(it.second) == yol }?.first

    fun yol(adres: String): String = adres.substringAfter("://", adres).let { if (adres.contains("://")) "/" + it.substringAfter("/") else it }
}

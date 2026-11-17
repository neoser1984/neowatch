package com.neowatch

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// Kasım 2026: site DiziPalGuncel ile aynı Next.js altyapısına geçti. __NEXT_DATA__/secureData ve /api/bg/* uçları kalktı.
// Kartlar a.tc (dizi/film) ve div.ec > a.ecl (bölüm); bilgiler, sezon-bölüm listesi ve şifreli oynatıcı adresi RSC verisinde.
class SelcukFlix : MainAPI() {
    override var mainUrl              = "https://selcukflix.app"
    override var name                 = "SelcukFlix"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // ! CloudFlare bypass
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler"                to "Son Bölümler",
        "${mainUrl}/dizi-izle"                   to "Diziler",
        "${mainUrl}/film-izle"                   to "Filmler",
        "${mainUrl}/yabanci-film-izle"           to "Yabancı Filmler",
        "${mainUrl}/turkce-dublaj-dizi-izle"     to "Türkçe Dublaj Diziler",
        "${mainUrl}/turkce-altyazili-dizi-izle"  to "Türkçe Altyazılı Diziler",
        "${mainUrl}/kdrama-izle"                 to "Kore Dizileri",
        "${mainUrl}/anime-izle"                  to "Anime",
        "${mainUrl}/imdb-top-250-film"           to "IMDb Top 250",
        "${mainUrl}/kesfet"                      to "Keşfet",
        "${mainUrl}/kanal/netflix"               to "Netflix",
        "${mainUrl}/kanal/disney"                to "Disney+",
        "${mainUrl}/kanal/amazon"                to "Amazon Prime",
        "${mainUrl}/kanal/hbo-max"               to "HBO Max",
        "${mainUrl}/kanal/apple-tv"              to "Apple TV+",
    )

    // Kanal sayfaları sayfalı değil; diğer listeler ?page=N
    private fun sayfali(adres: String) = !adres.contains("/kanal/")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sayfaVar = sayfali(request.data)
        if (!sayfaVar && page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val adres    = if (sayfaVar && page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(adres, referer = "${mainUrl}/").document
        val anime    = request.data.contains("/anime")

        val home = (document.select("div.ec > a.ecl, a.ecl").mapNotNull { it.bolumKarti() } +
                    document.select("a.tc").mapNotNull { it.icerikKarti(anime) }).distinctBy { it.url }

        return newHomePageResponse(request.name, home, hasNext = sayfaVar && home.isNotEmpty())
    }

    private fun Element.posterAl(): String? {
        val img = this.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("data-srcset").substringBefore(" ") }
            .ifBlank { img.attr("srcset").substringBefore(" ") }.ifBlank { img.attr("src") }
        return if (src.isBlank() || src.startsWith("data:")) null else fixUrlNull(src)
    }

    // Bölüm kartı: div.ec > a.ecl + span (resim) + div > span.tn (dizi adı) + span (sezon/bölüm)
    private fun Element.bolumKarti(): SearchResponse? {
        val href  = fixUrlNull(this.attr("href")) ?: return null
        val kutu  = this.parent() ?: this
        val dizi  = kutu.selectFirst("span.tn")?.text()?.trim()?.ifBlank { null }
            ?: this.attr("aria-label").substringBefore(",").trim().ifBlank { null } ?: return null
        val bilgi = kutu.selectFirst("span.tn + span")?.text()?.trim()
            ?.replace(". Sezon ", "x")?.replace(". Bölüm", "")?.ifBlank { null }

        return newTvSeriesSearchResponse(if (bilgi == null) dizi else "$dizi $bilgi", href, TvType.TvSeries) {
            this.posterUrl = kutu.posterAl() ?: this@bolumKarti.posterAl()
        }
    }

    // Dizi/film kartı: a.tc > span.fr (resim) + span.tt|span.ft1 (ad) + span (yıl / türler · yıl)
    private fun Element.icerikKarti(anime: Boolean = false): SearchResponse? {
        val href  = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("span.tt, span.ft1")?.text()?.trim()?.ifBlank { null }
            ?: this.attr("aria-label").substringBefore(",").trim().ifBlank { null } ?: return null
        val year  = Regex("""\b(19|20)\d{2}\b""").find(this.selectFirst("span.tt + span, span.ft1 + span")?.text().orEmpty())?.value?.toIntOrNull()

        return if (href.contains("/film/")) {
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
            val slug  = item.slug?.trim()?.trimStart('/') ?: return@mapNotNull null
            val film  = item.type.equals("Movies", true) || item.type.equals("Movie", true) || slug.startsWith("film")

            // Film bağlantıları izleme sayfasına (/film/<slug>/izle) gider
            val yol   = if (film && !slug.endsWith("/izle")) "/${slug.trimEnd('/')}/izle" else "/$slug"
            val href  = fixUrl(yol)

            if (film) {
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
        var adres = url.substringBefore("?")
        // Eski sürümden kalan film bağlantıları (/film/<slug>) izleme sayfasına çevrilir
        if (Regex("""/film/[^/]+/?$""").containsMatchIn(adres)) adres = adres.trimEnd('/') + "/izle"

        var html = app.get(adres, referer = "${mainUrl}/").text
        var rsc  = Rsc.metin(html)

        // Son bölümler listesinden gelen bölüm bağlantısını dizi sayfasına çevir
        if (adres.contains("/sezon-") && adres.contains("/bolum-")) {
            val dizi = rsc.jsonDeger("seriesHref")?.let { fixUrl(it) }
                ?: Regex("""(/dizi/[^/]+)/sezon-""").find(adres)?.groupValues?.get(1)?.let { fixUrl(it) }
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

        if (adres.contains("/film/")) {
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

        val tvType = if (turler.any { it.contains("Anime", true) }) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(baslik, adres, tvType, episodes) {
            this.posterUrl = poster
            this.year      = yil
            this.plot      = konu
            this.tags      = turler
            this.duration  = sure
            this.score     = Score.from10(puan)
            addActors(oyuncu)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("SCF", "data » $data")
        val html = app.get(data, referer = "${mainUrl}/").text
        val rsc  = Rsc.metin(html)

        // Her kaynak (PUB+, Uptobox, dublaj…) kendi sayfasında kendi şifreli oynatıcı adresini taşır
        val kaynaklar = mutableListOf<Pair<String?, String>>()   // etiket → oynatıcı adresi
        oynaticiCoz(rsc)?.let { kaynaklar.add(Rsc.etiket(rsc, Rsc.yol(data)) to it) }

        Rsc.kaynaklar(rsc).filter { Rsc.yol(fixUrl(it.second)) != Rsc.yol(data) }.forEach { (etiket, href) ->
            runCatching {
                val diger = app.get(fixUrl(href), referer = data).text
                oynaticiCoz(Rsc.metin(diger))?.let { kaynaklar.add(etiket to it) }
            }
        }

        Log.d("SCF", "sayfa uzunluk=${html.length} rsc=${rsc.length} player=${rsc.contains("\"player\":{")} kaynak=${kaynaklar.size}")
        var bulundu = false
        for ((etiket, adres) in kaynaklar.distinctBy { it.second }) {
            val iframe = if (adres.startsWith("//")) "https:$adres" else adres
            if (!iframe.startsWith("http")) continue
            Log.d("SCF", "iframe » $etiket » $iframe")
            val ek = etiket?.trim()?.ifBlank { null }

            val toplanan = mutableListOf<ExtractorLink>()
            runCatching {
                if (ContentXGenel.uygunMu(iframe)) {
                    ContentXGenel().getUrl(iframe, "${mainUrl}/", subtitleCallback) { toplanan.add(it) }
                } else {
                    loadExtractor(iframe, "${mainUrl}/", subtitleCallback) { toplanan.add(it) }
                }
            }.onFailure { Log.w("SCF", "oynatıcı hatası » ${it.message}") }

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

    /** RSC'deki player.enc {ciphertext, iv, salt} + "k" parolasıyla iframe adresini çözer. */
    private fun oynaticiCoz(rsc: String): String? {
        val oyuncu = rsc.indexOf("\"player\":{").takeIf { it >= 0 } ?: return null
        val enc    = Rsc.nesne(rsc, "\"enc\":", oyuncu) ?: return null
        val parola = rsc.jsonDeger("k", rsc.indexOf("\"enc\":", oyuncu)) ?: RmkCoz.PAROLA
        return (RmkCoz.coz(enc, parola) ?: RmkCoz.coz(enc))?.trim()?.ifBlank { null }
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

    /** "seasons":[{"no":1,"label":…,"episodes":[{"n":1,"title":…,"href":"/dizi/<slug>/sezon-1/bolum-1"}]}] */
    fun bolumler(s: String): List<Bolum> {
        val sezonlar = nesne(s, "\"seasons\":") ?: return emptyList()
        val sezonKonum = Regex(""""no":(\d+),"label":""").findAll(sezonlar).map { it.range.first to it.groupValues[1].toInt() }.toList()
        return Regex(""""n":(\d+),"title":"((?:[^"\\]|\\.)*)"[^{}]*?"href":"([^"]+)"""").findAll(sezonlar).map { m ->
            val href  = m.groupValues[3]
            val sxe   = Regex("""/sezon-(\d+)/bolum-(\d+)""").find(href)
            val sezon = sxe?.groupValues?.get(1)?.toIntOrNull() ?: sezonKonum.lastOrNull { it.first < m.range.first }?.second
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

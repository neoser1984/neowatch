package com.neowatch

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

// Eski superfilmgeldi.* alan adlarının hepsi park/kapalı (Ekim 2026). Site superfilmizle.org olarak
// yeni bir altyapıyla (film + dizi + anime) devam ediyor; eklenti adı aynı kaldı.
class SuperFilmGeldi : MainAPI() {
    override var mainUrl              = "https://superfilmizle.org"
    override var name                 = "SuperFilmGeldi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tarayici = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/film-izle/?sirala=yeni"              to "Yeni Eklenen Filmler",
        "${mainUrl}/dizi-izle/?sirala=yeni"              to "Yeni Eklenen Diziler",
        "${mainUrl}/film-izle/"                          to "Filmler",
        "${mainUrl}/dizi-izle/"                          to "Diziler",
        "${mainUrl}/animes/"                             to "Animeler",
        "${mainUrl}/category/editor-secim/"              to "Editörün Seçimi",
        "${mainUrl}/category/aksiyon-filmleri/"          to "Aksiyon Filmleri",
        "${mainUrl}/category/komedi-filmleri/"           to "Komedi Filmleri",
        "${mainUrl}/category/korku-filmleri/"            to "Korku Filmleri",
        "${mainUrl}/category/bilim-kurgu/"               to "Bilim Kurgu Filmleri",
        "${mainUrl}/category/gerilim-filmleri/"          to "Gerilim Filmleri",
        "${mainUrl}/category/dram-filmleri/"             to "Dram Filmleri",
        "${mainUrl}/category/macera-filmleri/"           to "Macera Filmleri",
        "${mainUrl}/category/fantastik-filmler/"         to "Fantastik Filmler",
        "${mainUrl}/category/romantik-filmler/"          to "Romantik Filmler",
        "${mainUrl}/category/suc-filmleri/"              to "Suç Filmleri",
        "${mainUrl}/category/gizem-filmleri/"            to "Gizem Filmleri",
        "${mainUrl}/category/animasyon-filmleri/"        to "Animasyon Filmleri",
        "${mainUrl}/category/aile-filmleri/"             to "Aile",
        "${mainUrl}/category/belgesel-filmleri/"         to "Belgesel",
        "${mainUrl}/category/savas-filmleri/"            to "Savaş Filmleri",
        "${mainUrl}/category/tarih-filmleri/"            to "Tarih Filmleri",
        "${mainUrl}/category/biyografi-filmleri/"        to "Biyografi Filmleri",
        "${mainUrl}/category/western-filmleri/"          to "Western Filmleri",
        "${mainUrl}/category/muzikal-filmler/"           to "Müzikal Filmler",
        "${mainUrl}/category/hint-filmleri/"             to "Hint Filmleri",
        "${mainUrl}/dizi-kategori/aksiyon-dizileri/"     to "Aksiyon Dizileri",
        "${mainUrl}/dizi-kategori/komedi-dizileri/"      to "Komedi Dizileri",
        "${mainUrl}/dizi-kategori/dram-dizileri/"        to "Dram Dizileri",
        "${mainUrl}/dizi-kategori/suc-dizileri/"         to "Suç Dizileri",
        "${mainUrl}/dizi-kategori/bilim-kurgu-dizileri/" to "Bilim Kurgu Dizileri",
        "${mainUrl}/dizi-kategori/fantastik-diziler/"    to "Fantastik Diziler",
        "${mainUrl}/dizi-kategori/macera-dizileri/"      to "Macera Dizileri",
        "${mainUrl}/dizi-kategori/savas-dizileri/"       to "Savaş Dizileri",
        "${mainUrl}/dizi-kategori/animasyon-dizileri/"   to "Animasyon Dizileri",
        "${mainUrl}/film-izle/?platform=netflix"         to "Netflix Filmleri",
        "${mainUrl}/dizi-izle/?platform=netflix"         to "Netflix Dizileri",
        "${mainUrl}/film-izle/?platform=amazon-prime"    to "Amazon Prime Filmleri",
        "${mainUrl}/dizi-izle/?platform=amazon-prime"    to "Amazon Prime Dizileri",
        "${mainUrl}/film-izle/?platform=disney"          to "Disney+ Filmleri",
        "${mainUrl}/dizi-izle/?platform=disney"          to "Disney+ Dizileri",
        "${mainUrl}/film-izle/?platform=hbo-max"         to "HBO Max Filmleri",
        "${mainUrl}/dizi-izle/?platform=hbo-max"         to "HBO Max Dizileri",
        "${mainUrl}/dizi-izle/?platform=exxen"           to "Exxen Dizileri",
        "${mainUrl}/dizi-izle/?platform=blutv"           to "BluTV Dizileri",
    )

    // Liste sayfaları /film-izle/page/2/?platform=netflix biçiminde sayfalanır (?page=2 çalışmaz).
    private fun sayfaAdresi(data: String, page: Int): String {
        if (page <= 1) return data
        val yol   = data.substringBefore("?")
        val sorgu = data.substringAfter("?", "")
        return "${yol.trimEnd('/')}/page/$page/" + (if (sorgu.isNotBlank()) "?$sorgu" else "")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(sayfaAdresi(request.data, page), headers = tarayici).document
        val home     = kartlar(document)
        val sonraki  = document.select("link[rel=next], a[rel=next]").isNotEmpty()

        return newHomePageResponse(request.name, home, hasNext = sonraki)
    }

    private val icerikDisi = setOf(
        "category", "platform", "dizi-kategori", "locale", "film-izle", "dizi-izle", "animes", "kesfet", "takvim",
        "forum", "uye-girisi", "trend", "koleksiyonlar", "ara", "page", "bolum", "oyuncu", "hesabim", "profil"
    )

    private fun icerikAdresiMi(href: String): Boolean {
        if (!href.startsWith(mainUrl)) return false
        val parca = href.removePrefix(mainUrl).trim('/').substringBefore("?").split("/")
        if (parca.firstOrNull().isNullOrBlank() || parca.first() in icerikDisi) return false
        return when (parca.first()) {
            "dizi", "anime" -> parca.size == 2
            else            -> parca.size == 1
        }
    }

    private fun kartlar(document: Document): List<SearchResponse> =
        document.select("a.group[href]")
            .filter { icerikAdresiMi(it.attr("abs:href")) && it.selectFirst("img") != null && it.selectFirst("h3") != null }
            .distinctBy { it.attr("abs:href") }
            .mapNotNull { it.toSearchResult() }

    private fun turBul(href: String): TvType = when {
        href.contains("/anime/") -> TvType.Anime
        href.contains("/dizi/")  -> TvType.TvSeries
        else                     -> TvType.Movie
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href      = this.attr("abs:href").ifBlank { return null }
        val title     = this.selectFirst("h3")?.text()?.trim()?.ifBlank { null } ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val puan      = this.selectFirst("span.badge-rating")?.text()?.trim()
        val yil       = this.selectFirst("h3 + p")?.text()?.trim()?.toIntOrNull()

        return when (val tur = turBul(href)) {
            TvType.Movie -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year      = yil
                this.score     = Score.from10(puan)
            }
            else -> newTvSeriesSearchResponse(title, href, tur) {
                this.posterUrl = posterUrl
                this.year      = yil
                this.score     = Score.from10(puan)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/ara", params = mapOf("q" to query), headers = tarayici).document

        return kartlar(document)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ld+json içindeki Movie / TVSeries düğümünü bulur
    private fun yapisalVeri(document: Document): JSONObject? {
        for (betik in document.select("script[type=application/ld+json]")) {
            val kok = runCatching { JSONObject(betik.data()) }.getOrNull() ?: continue
            val dugumler = kok.optJSONArray("@graph") ?: JSONArray().put(kok)
            for (i in 0 until dugumler.length()) {
                val d = dugumler.optJSONObject(i) ?: continue
                if (d.optString("@type") in setOf("Movie", "TVSeries")) return d
            }
        }
        return null
    }

    private fun JSONObject.adlar(alan: String): List<String> {
        val dizi = optJSONArray(alan) ?: return optString(alan).takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
        return (0 until dizi.length()).mapNotNull { i ->
            dizi.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() } ?: dizi.optString(i).takeIf { it.isNotBlank() && !it.startsWith("{") }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = tarayici).document
        val veri     = yapisalVeri(document)

        val title       = veri?.optString("name")?.ifBlank { null } ?: document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(veri?.optString("image")?.ifBlank { null } ?: document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = veri?.optString("description")?.ifBlank { null } ?: document.selectFirst("meta[name=description]")?.attr("content")
        val year        = (veri?.optString("datePublished")?.ifBlank { null } ?: veri?.optString("startDate"))?.take(4)?.toIntOrNull()
        val tags        = veri?.adlar("genre").orEmpty()
        val actors      = veri?.adlar("actor").orEmpty().map { Actor(it) }
        val rating      = veri?.optJSONObject("aggregateRating")?.optString("ratingValue")
        val sure        = veri?.optString("duration")?.let { Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""").find(it) }
            ?.let { (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0) }?.takeIf { it > 0 }
        val fragman     = veri?.optJSONObject("trailer")?.optString("embedUrl")?.ifBlank { null }
        val oneriler    = kartlar(document).filter { it.url.trimEnd('/') != url.trimEnd('/') }
        val tur         = turBul(url)

        if (tur == TvType.Movie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score           = Score.from10(rating)
                this.duration        = sure
                this.recommendations = oneriler
                addActors(actors)
                addTrailer(fragman)
            }
        }

        val sezonlar = document.select("a[href*=?sezon=]").mapNotNull {
            Regex("""[?&]sezon=(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.distinct().sorted().ifEmpty { listOf(1) }

        val bolumler = mutableListOf<Episode>()
        for (sezon in sezonlar) {
            val sezonSayfasi = if (sezon == sezonlar.first()) document else app.get("${url.trimEnd('/')}/?sezon=$sezon", headers = tarayici).document
            sezonSayfasi.select("section#bolumler a[href]").forEach { a ->
                val href = a.attr("abs:href")
                val sb   = Regex("""-(\d+)-sezon-(\d+)-bolum""").find(href) ?: Regex("""/sezon-(\d+)/bolum-(\d+)""").find(href) ?: return@forEach
                val s    = sb.groupValues[1].toIntOrNull() ?: sezon
                val e    = sb.groupValues[2].toIntOrNull()
                if (bolumler.any { it.data == href }) return@forEach
                bolumler.add(newEpisode(href) {
                    this.name        = a.selectFirst("p.font-semibold, p")?.text()?.trim()?.ifBlank { null }
                    this.season      = s
                    this.episode     = e
                    this.posterUrl   = fixUrlNull(a.selectFirst("img")?.attr("src"))
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, tur, bolumler.sortedWith(compareBy({ it.season }, { it.episode }))) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.score           = Score.from10(rating)
            this.recommendations = oneriler
            addActors(actors)
            addTrailer(fragman)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("SFG", "data » $data")
        val html = app.get(data, headers = tarayici).text

        // Oynatıcı (Pilavyer): <div data-pv="SLUG"> + <script src="https://SUNUCU/assets/js/core.js">
        // core.js iframe'i SUNUCU/assets/js/s.php?s=SLUG olarak kurar; sunucu Referer'ı (site kökeni) denetler.
        val slug = Regex("""data-pv="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return false
        val base = Regex("""src="(https://[^"]+?)/(?:assets/js/core|e/c)\.js""").find(html)?.groupValues?.get(1) ?: return false
        val embed = "$base/assets/js/s.php?s=$slug"
        Log.d("SFG", "embed » $embed")

        val oynatici = app.get(embed, headers = tarayici + mapOf("Referer" to "${mainUrl}/")).text
        val json     = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL).find(oynatici)?.groupValues?.get(1) ?: return false
        val ayar     = JSONObject(json)
        val akis     = ayar.optString("stream").ifBlank { return false }

        val altyazilar = ayar.optJSONArray("subs") ?: JSONArray()
        for (i in 0 until altyazilar.length()) {
            val a = altyazilar.optJSONObject(i) ?: continue
            val src = a.optString("src").ifBlank { null } ?: continue
            subtitleCallback.invoke(newSubtitleFile(a.optString("label").ifBlank { a.optString("lang") }, src))
        }

        val sesler = ayar.optJSONArray("audios")
        val sesAdi = if (sesler != null && sesler.length() > 1) " (Dublaj + Orijinal)" else
            sesler?.optJSONObject(0)?.optString("label")?.ifBlank { null }?.let { " ($it)" } ?: ""

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = "Pilavyer$sesAdi",
                url    = akis,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = embed
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Origin" to base)
            }
        )

        // Bazı servis sağlayıcılar CDN alan adlarını (pilavyerN.top) engelliyor; oynatıcının kendi yedeği
        // olan "cdn=0" ile parçalar doğrudan oynatıcı sunucusundan gelir.
        if (!akis.contains("cdn=0")) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = "Pilavyer Yedek$sesAdi",
                    url    = akis + (if (akis.contains("?")) "&" else "?") + "cdn=0",
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = embed
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Origin" to base)
                }
            )
        }

        return true
    }
}

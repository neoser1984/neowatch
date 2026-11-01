package com.neowatch

import android.util.Log
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class JetFilmizle : MainAPI() {
    override var mainUrl              = "https://jetfilmizle.app"
    override var name                 = "JetFilmizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler"          to "Son Filmler",
        "${mainUrl}/diziler"          to "Diziler",
        "${mainUrl}/turkce-dublaj"    to "Türkçe Dublaj",
        "${mainUrl}/turkce-altyazili" to "Türkçe Altyazılı",
        "${mainUrl}/yerli-filmler"    to "Yerli Filmler",
        "${mainUrl}/tur/aksiyon"      to "Aksiyon",
        "${mainUrl}/tur/komedi"       to "Komedi",
        "${mainUrl}/tur/korku"        to "Korku",
        "${mainUrl}/tur/bilim-kurgu"  to "Bilim Kurgu",
        "${mainUrl}/tur/animasyon"    to "Animasyon",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val adres    = if (page <= 1) request.data else "${request.data}/sayfa-${page}"
        val document = app.get(adres, referer = "${mainUrl}/").document
        val home     = document.select("div.film-card-wrapper").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(request.name, home, home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a         = this.selectFirst("a[href*='/film/'], a[href*='/dizi/']") ?: return null
        val href      = fixUrlNull(a.attr("href")) ?: return null
        val title     = (this.selectFirst("h3.fc-title, h3")?.text() ?: a.attr("title")).substringBefore(" izle").trim().takeIf { it.isNotBlank() } ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        val year      = this.selectFirst("span.fc-year")?.text()?.trim()?.take(4)?.toIntOrNull()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl; this.year = year }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl; this.year = year }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val sorgu    = URLEncoder.encode(query, "UTF-8")
        val document = app.get("${mainUrl}/arama?q=${sorgu}", referer = "${mainUrl}/").document

        return document.select("div.film-card-wrapper").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Document.ldNesne(vararg tipler: String): String? =
        this.select("script[type=application/ld+json]").map { it.data() }
            .firstOrNull { d -> tipler.any { Regex(""""@type"\s*:\s*"$it"""").containsMatchIn(d) } }

    private fun ldAlan(ld: String?, alan: String): String? =
        ld?.let { Regex(""""$alan"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(it)?.groupValues?.get(1)?.replace("\\/", "/")?.replace("\\\"", "\"") }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "${mainUrl}/").document
        val diziMi   = url.contains("/dizi/")
        val ld       = document.ldNesne("Movie", "TVSeries")

        val title       = document.selectFirst("h1.film-title, h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: ldAlan(ld, "name") ?: return null
        val poster      = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content")) ?: fixUrlNull(ldAlan(ld, "image"))
        val description = ldAlan(ld, "description") ?: document.selectFirst("div.film-description")?.ownText()?.trim()
        val year        = Regex("""-(\d{4})/?$""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: (ldAlan(ld, "datePublished") ?: ldAlan(ld, "dateCreated"))?.take(4)?.toIntOrNull()
        val tags        = ld?.let { l ->
            Regex(""""genre"\s*:\s*\[([^\]]*)]""").find(l)?.groupValues?.get(1)?.let { g -> Regex(""""([^"]+)"""").findAll(g).map { it.groupValues[1] }.toList() }
                ?: ldAlan(l, "genre")?.let { listOf(it) }
        } ?: emptyList()
        val rating      = ld?.let { Regex(""""ratingValue"\s*:\s*"?([\d.]+)""").find(it)?.groupValues?.get(1) }
        val duration    = ld?.let { Regex(""""duration"\s*:\s*"PT(?:(\d+)H)?(\d+)M""").find(it)?.let { m -> (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0) } }
        val actors      = document.select("a[href*='/oyuncu']").map { it.text().trim() }.filter { it.isNotBlank() && it.length < 40 }.distinct().map { Actor(it) }
        val recommendations = document.select("div.film-card-wrapper").mapNotNull { it.toSearchResult() }.filter { it.url != url }.distinctBy { it.url }
        val filmId      = document.selectFirst("input[name=film_id]")?.attr("value") ?: return null

        if (diziMi) {
            // ? Bölümler: <button class="player-source-btn episode" data-source-index data-player-type data-season data-episode>
            val gruplar = document.select("button[data-season][data-episode][data-source-index]")
                .groupBy { (it.attr("data-season").toIntOrNull() ?: 1) to (it.attr("data-episode").toIntOrNull() ?: 0) }
            val episodes = gruplar.mapNotNull { (anahtar, butonlar) ->
                val kaynaklar = butonlar.map { JetKaynak(it.attr("data-source-index"), it.attr("data-player-type"), it.text().trim()) }
                    .filter { !it.etiket.contains("Fragman", true) }.distinctBy { it.index + it.tip }
                if (kaynaklar.isEmpty() || anahtar.second == 0) return@mapNotNull null
                newEpisode(JetVeri(filmId, url, kaynaklar).toJson()) {
                    this.season  = anahtar.first
                    this.episode = anahtar.second
                    this.name    = "${anahtar.second}. Bölüm"
                }
            }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score           = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
            }
        }

        val kaynaklar = document.select("button.player-source-btn[data-source-index]")
            .map { JetKaynak(it.attr("data-source-index"), it.attr("data-player-type"), it.text().trim()) }
            .filter { !it.etiket.contains("Fragman", true) }.distinctBy { it.index + it.tip }

        return newMovieLoadResponse(title, url, TvType.Movie, JetVeri(filmId, url, kaynaklar).toJson()) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.score           = Score.from10(rating)
            this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("JTF", "data » $data")
        val veri = jsonOku<JetVeri>(data) ?: return false

        var bulundu = false
        for (k in veri.kaynaklar) {
            val yanit = runCatching {
                app.post(
                    "${mainUrl}/jetplayer",
                    data    = mapOf("film_id" to veri.filmId, "source_index" to k.index, "player_type" to k.tip),
                    referer = veri.sayfa,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text
            }.getOrNull() ?: continue

            val iframe = Jsoup.parse(yanit).selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: continue
            Log.d("JTF", "${k.etiket} » $iframe")

            val etiket = listOf(k.etiket, if (k.tip.contains("altyazi", true)) "Altyazılı" else if (k.tip.contains("dublaj", true)) "Dublaj" else "")
                .filter { it.isNotBlank() }.joinToString(" ")
            runCatching {
                if (iframe.contains("videopark.")) {
                    if (VideoPark.coz(iframe, etiket, veri.sayfa, callback)) bulundu = true
                } else if (iframe.contains("playerx.")) {
                    if (PlayerX.coz(iframe, etiket, veri.sayfa, subtitleCallback, callback)) bulundu = true
                } else {
                    if (loadExtractor(iframe, "${mainUrl}/", subtitleCallback) { callback.invoke(it); bulundu = true }) bulundu = true
                }
            }
        }

        return bulundu
    }

    data class JetKaynak(
        @JsonProperty("index")  val index: String,
        @JsonProperty("tip")    val tip: String,
        @JsonProperty("etiket") val etiket: String
    )

    data class JetVeri(
        @JsonProperty("filmId")    val filmId: String,
        @JsonProperty("sayfa")     val sayfa: String,
        @JsonProperty("kaynaklar") val kaynaklar: List<JetKaynak>
    )
}

/**
 * videopark.top oynatıcıları (Cloudflare Worker üzerinden):
 *  - /oplayer/?id=…  : sayfada satır içi JSON  {"hlsSource":{"file":…},"mp4Sources":[…]}
 *  - /vip/?id=…      : WORKER_URL + /api/stream?pubId=…&title=…&publisherId=…  → aynı JSON
 *  - /go/?id=…       : WORKER_BASE + /v/<id>/info → {"qualities":[1080,720]}  → /v/<id>?q=<q> (mp4)
 *  - /jetembed/tt…   : başka bir siteye iframe
 */
object VideoPark {
    private fun temizle(s: String) = s.replace("\\/", "/")

    suspend fun coz(url: String, etiket: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val metin  = app.get(url, referer = referer).text
        val kaynak = "VideoPark" + if (etiket.isBlank()) "" else " - $etiket"
        var bulundu = false

        suspend fun jsonIsle(json: String) {
            Regex(""""hlsSource"\s*:\s*\{[^}]*?"file"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.let { hls ->
                bulundu = true
                callback.invoke(newExtractorLink(kaynak, kaynak, temizle(hls), ExtractorLinkType.M3U8) {
                    this.referer = "https://videopark.top/"
                    this.quality = Qualities.Unknown.value
                })
            }
            Regex(""""mp4Sources"\s*:\s*\[(.*?)]""").find(json)?.groupValues?.get(1)?.let { dizi ->
                Regex("""\{[^{}]*?"file"\s*:\s*"([^"]+)"[^{}]*?"label"\s*:\s*"([^"]*)"""").findAll(dizi).forEach { m ->
                    bulundu = true
                    callback.invoke(newExtractorLink(kaynak, "$kaynak ${m.groupValues[2]}", temizle(m.groupValues[1]), ExtractorLinkType.VIDEO) {
                        this.referer = "https://videopark.top/"
                        this.quality = getQualityFromName(m.groupValues[2])
                    })
                }
            }
        }

        // 1) Satır içi JSON
        if (metin.contains("\"hlsSource\"") || metin.contains("\"mp4Sources\"")) jsonIsle(metin)

        // 2) WORKER_URL + /api/stream
        if (!bulundu) {
            val worker = Regex("""WORKER_URL\s*=\s*['"]([^'"]+)['"]""").find(metin)?.groupValues?.get(1)?.let(::temizle)
            val pubId  = Regex("""PUB_ID\s*=\s*['"]([^'"]*)['"]""").find(metin)?.groupValues?.get(1)
            val yayinci = Regex("""PUBLISHER_ID\s*=\s*['"]([^'"]*)['"]""").find(metin)?.groupValues?.get(1)
            val baslik = Regex("""VIDEO_TITLE\s*=\s*['"]([^'"]*)['"]""").find(metin)?.groupValues?.get(1)
            val vidId  = Regex("""VIDEO_ID\s*=\s*['"]?([^'";\s]+)""").find(metin)?.groupValues?.get(1)
            if (worker != null) {
                val api = if (!pubId.isNullOrBlank()) {
                    "$worker/api/stream?pubId=${URLEncoder.encode(pubId, "UTF-8")}&title=${URLEncoder.encode(baslik ?: vidId ?: "", "UTF-8")}" +
                        (if (!yayinci.isNullOrBlank()) "&publisherId=${URLEncoder.encode(yayinci, "UTF-8")}" else "")
                } else "$worker/api/video?id=${URLEncoder.encode(vidId ?: "", "UTF-8")}"
                runCatching { app.get(api, referer = url).text }.getOrNull()?.let { jsonIsle(it) }
            }
        }

        // 3) WORKER_BASE + /v/<id>
        if (!bulundu) {
            val base  = Regex("""WORKER_BASE\s*=\s*['"]([^'"]+)['"]""").find(metin)?.groupValues?.get(1)?.let(::temizle)?.trimEnd('/')
            val vidId = Regex("""VIDEO_ID\s*=\s*['"]?([^'";\s]+)""").find(metin)?.groupValues?.get(1)
            if (base != null && vidId != null) {
                val kaliteler = runCatching { app.get("$base/v/$vidId/info", referer = url).text }.getOrNull()
                    ?.let { Regex(""""qualities"\s*:\s*\[([^\]]*)]""").find(it)?.groupValues?.get(1) }
                    ?.split(",")?.map { it.trim().trim('"') }?.filter { it.isNotBlank() }.orEmpty()
                if (kaliteler.isEmpty()) {
                    bulundu = true
                    callback.invoke(newExtractorLink(kaynak, kaynak, "$base/v/$vidId", ExtractorLinkType.VIDEO) {
                        this.referer = "https://videopark.top/"
                        this.quality = Qualities.Unknown.value
                    })
                } else kaliteler.forEach { q ->
                    bulundu = true
                    callback.invoke(newExtractorLink(kaynak, "$kaynak ${q}p", "$base/v/$vidId?q=$q", ExtractorLinkType.VIDEO) {
                        this.referer = "https://videopark.top/"
                        this.quality = getQualityFromName("${q}p")
                    })
                }
            }
        }

        // 4) İç içe iframe
        if (!bulundu) {
            Jsoup.parse(metin, url).select("iframe[src]").forEach { f ->
                val src = f.absUrl("src").ifBlank { return@forEach }
                runCatching { if (loadExtractor(src, "https://videopark.top/", {}, callback)) bulundu = true }
            }
        }

        return bulundu
    }
}

/**
 * playerx.info: sayfadaki  var initialPlayback = {"item":{"sources":[{"file":"watch/<id>/<token>","type":"hls"}],"tracks":[…]}}
 */
object PlayerX {
    suspend fun coz(url: String, etiket: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val metin  = app.get(url, referer = referer).text
        val base   = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: return false
        val json   = Regex("""initialPlayback\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(metin)?.groupValues?.get(1) ?: return false
        val kaynak = "PlayerX" + if (etiket.isBlank()) "" else " - $etiket"
        fun tam(a: String): String {
            val t = a.replace("\\/", "/")
            return when {
                t.startsWith("http") -> t
                t.startsWith("//")   -> "https:$t"
                t.startsWith("/")    -> "$base$t"
                else                 -> "$base/$t"
            }
        }

        Regex(""""tracks"\s*:\s*\[(.*?)]""").find(json)?.groupValues?.get(1)?.let { izler ->
            Regex("""\{[^{}]*?"file"\s*:\s*"([^"]+)"[^{}]*?"label"\s*:\s*"([^"]*)"[^{}]*?}""").findAll(izler).forEach { m ->
                subtitleCallback.invoke(newSubtitleFile(m.groupValues[2].ifBlank { "Türkçe" }, tam(m.groupValues[1])))
            }
        }

        var bulundu = false
        Regex(""""sources"\s*:\s*\[(.*?)]""").find(json)?.groupValues?.get(1)?.let { liste ->
            Regex("""\{[^{}]*?"file"\s*:\s*"([^"]+)"([^{}]*?)}""").findAll(liste).forEach { m ->
                val adres = tam(m.groupValues[1])
                val hls   = m.groupValues[2].contains("hls") || adres.contains(".m3u8") || adres.contains("/watch/")
                val etk   = Regex(""""label"\s*:\s*"([^"]*)"""").find(m.value)?.groupValues?.get(1)
                bulundu = true
                callback.invoke(newExtractorLink(kaynak, if (etk.isNullOrBlank()) kaynak else "$kaynak $etk", adres, if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = url
                    this.headers = mapOf("Origin" to base)
                    this.quality = getQualityFromName(etk)
                })
            }
        }

        return bulundu
    }
}

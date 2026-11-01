package com.neowatch

import android.util.Log
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class DiziMom : MainAPI() {
    override var mainUrl              = "https://ddizimom.net"
    override var name                 = "DiziMom"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler"                 to "Diziler",
        "${mainUrl}/filmler"                 to "Filmler",
        "${mainUrl}/animes"                  to "Animeler",
        "${mainUrl}/platform/netflix"        to "Netflix",
        "${mainUrl}/platform/disney"         to "Disney+",
        "${mainUrl}/platform/amazon-prime"   to "Amazon Prime",
        "${mainUrl}/platform/hbo-max"        to "HBO Max",
        "${mainUrl}/platform/apple-tv"       to "Apple TV+",
        "${mainUrl}/tur/aksiyon"             to "Aksiyon",
        "${mainUrl}/tur/komedi"              to "Komedi",
        "${mainUrl}/tur/dram"                to "Dram",
        "${mainUrl}/tur/suc"                 to "Suç",
        "${mainUrl}/tur/bilim-kurgu"         to "Bilim Kurgu",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}?page=${page}", referer = "${mainUrl}/").document
        val home     = document.select("a.group.block").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(request.name, home, home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val tur       = Regex("""^https?://[^/]+/(dizi|film|anime)/[^/?#]+/?$""").find(href)?.groupValues?.get(1) ?: return null
        val title     = this.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")?.substringBefore(" izle")?.substringBefore(" Dizi")?.trim()
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })

        return when (tur) {
            "film"  -> newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
            "anime" -> newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
            else    -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val sorgu    = URLEncoder.encode(query, "UTF-8")
        val document = app.get("${mainUrl}/ara?q=${sorgu}", referer = "${mainUrl}/").document

        return document.select("a.group.block").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ld+json @graph içinden istenen türdeki nesnenin ham metnini döndürür
    private fun Document.ldNesne(tip: String): String? {
        return this.select("script[type=application/ld+json]").map { it.data() }
            .firstOrNull { Regex(""""@type"\s*:\s*"$tip"""").containsMatchIn(it) }
    }

    private fun ldAlan(ld: String?, alan: String): String? =
        ld?.let { Regex(""""$alan"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(it)?.groupValues?.get(1)?.replace("\\/", "/")?.replace("\\\"", "\"") }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "${mainUrl}/").document
        val temiz    = url.substringBefore("?").substringBefore("#").trimEnd('/')
        val filmMi   = temiz.contains("/film/")
        val animeMi  = temiz.contains("/anime/")

        val ld          = document.ldNesne(if (filmMi) "Movie" else "TVSeries")
        val title       = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: ldAlan(ld, "name") ?: return null
        val poster      = fixUrlNull(ldAlan(ld, "image")) ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = ldAlan(ld, "description") ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year        = (ldAlan(ld, "startDate") ?: ldAlan(ld, "datePublished") ?: ldAlan(ld, "dateCreated"))?.take(4)?.toIntOrNull()
        val rating      = ld?.let { Regex(""""ratingValue"\s*:\s*"?([\d.]+)""").find(it)?.groupValues?.get(1) }
        val tags        = ld?.let { l ->
            Regex(""""genre"\s*:\s*\[([^\]]*)]""").find(l)?.groupValues?.get(1)?.let { g -> Regex(""""([^"]+)"""").findAll(g).map { it.groupValues[1] }.toList() }
                ?: ldAlan(l, "genre")?.let { listOf(it) }
        } ?: emptyList()
        val actors      = ld?.let { l ->
            Regex(""""actor"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(l)?.groupValues?.get(1)?.let { a ->
                Regex(""""name"\s*:\s*"([^"]+)"""").findAll(a).map { Actor(it.groupValues[1]) }.toList()
            }
        } ?: emptyList()
        val recommendations = document.select("a.group.block").mapNotNull { it.toSearchResult() }
            .filter { it.url.trimEnd('/') != temiz }.distinctBy { it.url }

        if (filmMi) {
            return newMovieLoadResponse(title, temiz, TvType.Movie, temiz) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score           = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
            }
        }

        // ? Sezonlar "?sezon=N#bolumler" bağlantılarıyla ayrı sayfalarda listeleniyor
        val sezonlar = document.select("a[href*='sezon=']").mapNotNull { a ->
            Regex("""sezon=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.distinct().sorted()

        val sayfalar = mutableListOf(document)
        for (s in sezonlar) {
            if (s == 1 && sezonlar.size == 1) break
            if (document.select("a[href*='/sezon-$s/bolum-']").isNotEmpty()) continue
            runCatching { app.get("$temiz?sezon=$s", referer = temiz).document }.getOrNull()?.let { sayfalar.add(it) }
        }

        val episodes = sayfalar.flatMap { doc ->
            doc.select("a[href*='/sezon-'][href*='/bolum-']").mapNotNull { a ->
                val epHref = fixUrlNull(a.attr("href"))?.substringBefore("?")?.substringBefore("#") ?: return@mapNotNull null
                if (!epHref.startsWith(temiz)) return@mapNotNull null
                val m      = Regex("""/sezon-(\d+)/bolum-(\d+)""").find(epHref) ?: return@mapNotNull null
                val ad     = a.selectFirst("p.truncate, p.mt-2")?.text()?.trim()?.takeIf { it.isNotBlank() }

                newEpisode(epHref) {
                    this.name    = ad
                    this.season  = m.groupValues[1].toIntOrNull()
                    this.episode = m.groupValues[2].toIntOrNull()
                    this.posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src"))
                }
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, temiz, if (animeMi) TvType.Anime else TvType.TvSeries, episodes) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.score           = Score.from10(rating)
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZM", "data » $data")
        val metin = app.get(data, referer = "${mainUrl}/").text

        // ? Oynatıcı bir <template> içinde: <div data-pv="SLUG"></div><script src="https://<sunucu>/assets/js/core.js">
        //   core.js bu div'e  <sunucu>/assets/js/s.php?s=SLUG  adresli iframe kuruyor (Referer doğrulamalı)
        val sunucular = Regex("""src="((?:https?:)?//[^"]+?)/(?:assets/js/core|e/c)\.js""").findAll(metin)
            .map { it.groupValues[1].let { s -> if (s.startsWith("//")) "https:$s" else s } }.toList().distinct()
        val sluglar   = Regex("""data-pv="([^"]+)"""").findAll(metin).map { it.groupValues[1] }.toList().distinct()

        var bulundu = false
        for (slug in sluglar) {
            for (sunucu in sunucular.ifEmpty { listOf("https://pilavyerplay.top") }) {
                val oynatici = "$sunucu/assets/js/s.php?s=$slug"
                Log.d("DZM", "oynatıcı » $oynatici")
                if (pilavCoz(oynatici, data, subtitleCallback, callback)) { bulundu = true; break }
            }
        }

        // Yedek: sayfada doğrudan iframe varsa
        Jsoup.parse(metin).select("iframe[src], iframe[data-src]").forEach { f ->
            val src = f.attr("data-src").ifBlank { f.attr("src") }
            if (src.isBlank() || src.startsWith("about:")) return@forEach
            bulundu = true
            runCatching { loadExtractor(fixUrl(src), "${mainUrl}/", subtitleCallback, callback) }
        }

        return bulundu
    }

    private suspend fun pilavCoz(url: String, sayfa: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val yanit = runCatching { app.get(url, referer = "${mainUrl}/", headers = mapOf("Sec-Fetch-Dest" to "iframe")) }.getOrNull() ?: return false
        val metin = yanit.text
        val base  = Regex("""^(https?://[^/]+)""").find(yanit.url)?.groupValues?.get(1) ?: Regex("""^(https?://[^/]+)""").find(url)!!.groupValues[1]
        val host  = base.substringAfter("://")
        var bulundu = false

        // ? Pilavyer oynatıcısı: window.__PLAYER__ = {"stream":"…/api/stream.php?v=…&token=…","subs":[{"label":…,"src":…}], …}
        //   stream.php doğrudan HLS listesi döndürüyor; CDN erişilemezse "&cdn=0" ile asıl sunucuya düşülüyor.
        Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL).find(metin)?.groupValues?.get(1)?.let { json ->
            fun cz(s: String) = s.replace("\\u0026", "&").replace("\\/", "/")
            Regex(""""sid"\s*:\s*"[^"]*"\s*,\s*"lang"\s*:\s*"([^"]*)"\s*,\s*"label"\s*:\s*"([^"]*)".*?"src"\s*:\s*"([^"]+)"""").findAll(json).forEach { m ->
                subtitleCallback.invoke(newSubtitleFile(m.groupValues[2].ifBlank { m.groupValues[1] }, cz(m.groupValues[3])))
            }
            Regex(""""stream"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.let { s ->
                val akis = cz(s)
                bulundu = true
                callback.invoke(newExtractorLink("Pilavyer", "Pilavyer", akis, ExtractorLinkType.M3U8) {
                    this.referer = "$base/"
                    this.headers = mapOf("Origin" to base)
                    this.quality = Qualities.Unknown.value
                })
                callback.invoke(newExtractorLink("Pilavyer", "Pilavyer (Yedek)", if (akis.contains("?")) "$akis&cdn=0" else "$akis?cdn=0", ExtractorLinkType.M3U8) {
                    this.referer = "$base/"
                    this.headers = mapOf("Origin" to base)
                    this.quality = Qualities.Unknown.value
                })
            }
        }
        if (bulundu) return true

        // Altyazılar
        Regex("""\{[^{}]*?"?(?:file|src)"?\s*:\s*"([^"]+\.(?:vtt|srt)[^"]*)"[^{}]*?"?(?:label|lang)"?\s*:\s*"([^"]*)"""").findAll(metin).forEach { m ->
            subtitleCallback.invoke(newSubtitleFile(m.groupValues[2].ifBlank { "Türkçe" }, tamAdres(m.groupValues[1].replace("\\/", "/"), base)))
        }

        // Doğrudan video kaynakları (m3u8 / mp4)
        Regex("""["'](https?:\\?/\\?/[^"'\s]+?\.(?:m3u8|mp4)[^"'\s]*)["']""").findAll(metin).map { it.groupValues[1].replace("\\/", "/") }.distinct().forEach { video ->
            bulundu = true
            callback.invoke(
                newExtractorLink(host, host, video, if (video.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = "$base/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        // Göreli m3u8 (ör. "/hls/xyz/master.m3u8")
        if (!bulundu) {
            Regex("""["'](/[^"'\s]+?\.m3u8[^"'\s]*)["']""").findAll(metin).map { it.groupValues[1] }.distinct().forEach { video ->
                bulundu = true
                callback.invoke(newExtractorLink(host, host, "$base$video", ExtractorLinkType.M3U8) {
                    this.referer = "$base/"
                    this.quality = Qualities.Unknown.value
                })
            }
        }

        // İç içe iframe / FirePlayer
        Jsoup.parse(metin, base).select("iframe[src]").forEach { f ->
            val src = f.absUrl("src").ifBlank { f.attr("src") }
            if (src.isBlank() || src.startsWith("about:")) return@forEach
            bulundu = true
            runCatching {
                if (FirePlayer.isFirePlayer(src)) FirePlayer().getUrl(src, "$base/", subtitleCallback, callback)
                else loadExtractor(src, "$base/", subtitleCallback, callback)
            }
        }

        if (!bulundu && FirePlayer.isFirePlayer(yanit.url)) {
            FirePlayer().getUrl(yanit.url, "${mainUrl}/", subtitleCallback, callback)
            bulundu = true
        }

        return bulundu
    }

    private fun tamAdres(adres: String, base: String): String = when {
        adres.startsWith("//") -> "https:$adres"
        adres.startsWith("/")  -> "$base$adres"
        else                   -> adres
    }
}

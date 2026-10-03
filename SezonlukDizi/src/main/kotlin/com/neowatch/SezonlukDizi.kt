package com.neowatch

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.extractors.ByseSX

class SezonlukDizi : MainAPI() {
    override var mainUrl              = "https://sezonlukdizi.cc"
    override var name                 = "SezonlukDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler.asp?siralama_tipi=id&s="          to "Son Eklenenler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=mini&s=" to "Mini Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=2&s="    to "Yerli Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=1&s="    to "Yabancı Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=3&s="    to "Asya Dizileri",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=4&s="    to "Animasyonlar",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=5&s="    to "Animeler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=6&s="    to "Belgeseller",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}", referer = "${mainUrl}/").document
        val home     = document.select("div.afis a").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.description")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "${mainUrl}/diziler.asp",
            params  = mapOf("siralama_tipi" to "id", "adi" to query, "s" to "1"),
            referer = "${mainUrl}/"
        ).document

        return document.select("div.afis a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.header")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.image img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        val year        = document.selectFirst("div.extra span")?.text()?.trim()?.split("-")?.first()?.toIntOrNull()
        val description = document.selectFirst("span#tartismayorum-konu")?.text()?.trim()
        val tags        = document.select("div.labels a[href*='tur']").mapNotNull { it.text().trim() }
        val rating      = document.selectFirst("div.dizipuani a div")?.text()?.trim()?.replace(",", ".")
        val duration    = document.selectXpath("//span[contains(text(), 'Dk.')]").text().trim().substringBefore(" Dk.").toIntOrNull()

        val endpoint    = url.split("/").last()

        val actorsReq  = app.get("${mainUrl}/oyuncular/${endpoint}").document
        val actors     = actorsReq.select("div.doubling div.ui").map {
            Actor(
                it.selectFirst("div.header")?.text()?.trim() ?: "",
                fixUrlNull(it.selectFirst("img")?.attr("src"))
            )
        }.filter { it.name.isNotBlank() }


        val episodesReq = app.get("${mainUrl}/bolumler/${endpoint}").document
        val episodes    = mutableListOf<Episode>()
        for (sezon in episodesReq.select("table.unstackable")) {
            for (bolum in sezon.select("tbody tr")) {
                val epName    = bolum.selectFirst("td:nth-of-type(4) a")?.text()?.trim() ?: continue
                val epHref    = fixUrlNull(bolum.selectFirst("td:nth-of-type(4) a")?.attr("href")) ?: continue
                val epEpisode = bolum.selectFirst("td:nth-of-type(3)")?.text()?.substringBefore(".Bölüm")?.trim()?.toIntOrNull()
                val epSeason  = bolum.selectFirst("td:nth-of-type(2)")?.text()?.substringBefore(".Sezon")?.trim()?.toIntOrNull()

                episodes.add(newEpisode(epHref) {
                    this.name    = epName
                    this.season  = epSeason
                    this.episode = epEpisode
                })
            }
        }


        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.score     = Score.from10(rating)
            this.duration  = duration
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("SZD", "data » $data")
        val document = app.get(data, referer = "${mainUrl}/").document
        val aspData  = getAspData()
        val bid      = document.selectFirst("div#dilsec")?.attr("data-id") ?: return false
        Log.d("SZD", "bid » $bid")

        var bulundu = false

        // ? dil=1 → Altyazı, dil=0 → Dublaj
        for ((dil, etiket) in listOf("1" to "AltYazı", "0" to "Dublaj")) {
            val yanit = app.post(
                "${mainUrl}/ajax/dataAlternatif${aspData.alternatif}.asp",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = data,
                data    = mapOf("bid" to bid, "dil" to dil)
            ).parsedSafe<Kaynak>()

            yanit?.takeIf { it.status == "success" }?.data?.forEach { veri ->
                Log.d("SZD", "dil»$dil | veri.baslik » ${veri.baslik}")

                val veriResponse = app.post(
                    "${mainUrl}/ajax/dataEmbed${aspData.embed}.asp",
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    referer = data,
                    data    = mapOf("id" to "${veri.id}")
                ).document

                val iframe = fixUrlNull(veriResponse.selectFirst("iframe")?.attr("src")) ?: return@forEach
                // Cloudflare Turnstile ile korunan kaynaklar atlanır
                if (iframe.contains("reCAPTCHA", ignoreCase = true)) return@forEach
                Log.d("SZD", "dil»$dil | iframe » $iframe")

                val bulunanlar = mutableListOf<ExtractorLink>()
                runCatching {
                    val host = iframe.substringAfter("://").substringBefore("/")
                    if (host.startsWith("byse")) {
                        ByseSX().getUrl(iframe, "${mainUrl}/", subtitleCallback) { bulunanlar.add(it) }
                    } else {
                        loadExtractor(iframe, "${mainUrl}/", subtitleCallback) { bulunanlar.add(it) }
                    }
                }

                bulunanlar.forEach { link ->
                    bulundu = true
                    callback.invoke(
                        newExtractorLink("$etiket - ${veri.baslik}", "$etiket - ${veri.baslik}", link.url, link.type) {
                            this.referer       = link.referer
                            this.quality       = link.quality
                            this.headers       = link.headers
                            this.extractorData = link.extractorData
                        }
                    )
                }
            }
        }

        return bulundu
    }

    //Helper function for getting the number (probably some kind of version?) after the dataAlternatif and dataEmbed
    private suspend fun getAspData() : AspData{
        val websiteCustomJavascript = app.get("${this.mainUrl}/js/site.min.js", referer = "${mainUrl}/")
        val dataAlternatifAsp = Regex("""dataAlternatif(.*?).asp""").find(websiteCustomJavascript.text)?.groupValues?.get(1)
            .toString()
        val dataEmbedAsp = Regex("""dataEmbed(.*?).asp""").find(websiteCustomJavascript.text)?.groupValues?.get(1)
            .toString()
        return AspData(dataAlternatifAsp,dataEmbedAsp)
    }
}

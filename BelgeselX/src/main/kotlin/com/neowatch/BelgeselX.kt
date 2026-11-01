package com.neowatch

import java.util.Locale
import java.net.URLEncoder
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class BelgeselX : MainAPI() {
    override var mainUrl              = "https://belgeselx.com"
    override var name                 = "BelgeselX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Documentary)

    // ? Kategori sayfasının ilk sayfası düz HTML, sonraki sayfalar "ajax_konukat.php" ile geliyor.
    override val mainPage = mainPageOf(
        "turk-tarihi-belgeselleri" to "Türk Tarihi",
        "tarih-belgeselleri"       to "Tarih",
        "seyehat-belgeselleri"     to "Seyahat",
        "seri-belgeseller"         to "Seri",
        "savas-belgeselleri"       to "Savaş",
        "sanat-belgeselleri"       to "Sanat",
        "psikoloji-belgeselleri"   to "Psikoloji",
        "polisiye-belgeselleri"    to "Polisiye",
        "otomobil-belgeselleri"    to "Otomobil",
        "nazi-belgeselleri"        to "Nazi",
        "muhendislik-belgeselleri" to "Mühendislik",
        "kultur-din-belgeselleri"  to "Kültür Din",
        "kozmik-belgeseller"       to "Kozmik",
        "hayvan-belgeselleri"      to "Hayvan",
        "eski-tarih-belgeselleri"  to "Eski Tarih",
        "egitim-belgeselleri"      to "Eğitim",
        "dunya-belgeselleri"       to "Dünya",
        "doga-belgeselleri"        to "Doğa",
        "bilim-belgeselleri"       to "Bilim",
        "cizgi-film"               to "Çizgi Film"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val adres = if (page <= 1) "${mainUrl}/konu/${request.data}"
                    else "${mainUrl}/ajax_konukat.php?url=${request.data}&page=${page}"
        val document = app.get(adres, referer = "${mainUrl}/konu/${request.data}").document
        val home     = document.select("a.px-card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, home.isNotEmpty())
    }

    private fun String.toTitleCase(): String {
        val locale = Locale("tr", "TR")
        return this.split(" ").joinToString(" ") { word ->
            word.lowercase(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.px-card-title")?.text()?.trim()?.takeIf { it.isNotBlank() }?.toTitleCase() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.px-card-img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.Documentary) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cx = "016376594590146270301:iwmy65ijgrm" // ! Site aramayı hâlâ Google Programmable Search ile yapıyor

        val tokenResponse = app.get("https://cse.google.com/cse.js?cx=${cx}")
        val cseLibVersion = Regex("""cselibVersion"\s*:\s*"([^"]+)"""").find(tokenResponse.text)?.groupValues?.get(1)
        val cseToken      = Regex("""cse_token"\s*:\s*"([^"]+)"""").find(tokenResponse.text)?.groupValues?.get(1)
        val sorgu         = URLEncoder.encode(query, "UTF-8")

        val response = app.get(
            "https://cse.google.com/cse/element/v1?rsz=filtered_cse&num=20&hl=tr&source=gcsc&cselibv=${cseLibVersion}&cx=${cx}&q=${sorgu}&safe=off&cse_tok=${cseToken}&oq=${sorgu}&callback=google.search.cse.api9969&rurl=https%3A%2F%2Fbelgeselx.com%2F",
            referer = "${mainUrl}/"
        ).text

        // ? Sonuç nesnelerinde başlık ile url'nin sırası belirsiz; her url için öncesindeki ve sonrasındaki başlıktan
        //   bağlantı adına (slug) en çok benzeyeni seçilir.
        fun duz(m: String) = m.lowercase(Locale("tr", "TR")).replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i')
            .replace('ö', 'o').replace('ş', 's').replace('ü', 'u').replace(Regex("[^a-z0-9]+"), " ").trim()

        val basliklar = Regex(""""titleNoFormatting"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(response).map { it.range.first to it.groupValues[1] }.toList()
        val adresler  = Regex(""""url"\s*:\s*"(https?://belgeselx\.com/belgesel(?:dizi)?/[^"?#]+)"""").findAll(response).toList()
        val gorseller = Regex(""""(?:ogImage|og:image)"\s*:\s*"(https?://[^"]+)"""").findAll(response).map { it.range.first to it.groupValues[1] }.toList()

        val sonuclar = mutableListOf<SearchResponse>()
        val gorulen  = mutableSetOf<String>()
        for (m in adresler) {
            val slug = m.groupValues[1].trimEnd('/').substringAfterLast("/")
            if (!gorulen.add(slug)) continue
            val poz      = m.range.first
            val adaylar  = listOfNotNull(basliklar.lastOrNull { it.first < poz }, basliklar.firstOrNull { it.first > poz })
            val parcalar = slug.split("-").filter { it.length > 1 }
            val secilen  = adaylar.maxByOrNull { aday -> val d = duz(aday.second); parcalar.count { d.contains(it) } }
            val uygun    = secilen != null && parcalar.any { duz(secilen.second).contains(it) }
            val baslik   = (if (uygun) secilen!!.second else slug.replace("-", " "))
                .substringBefore(" İzle").substringBefore(" izle").substringBefore(" — ").trim()
            val poster   = gorseller.firstOrNull { it.first > poz }?.second

            sonuclar.add(newTvSeriesSearchResponse(baslik.toTitleCase(), "${mainUrl}/belgeseldizi/${slug}", TvType.Documentary) {
                this.posterUrl = poster
            })
        }

        return sonuclar
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        // ? Arama sonuçları izleme sayfasını (/belgesel/x) gösterebilir; bilgi sayfasına çevir
        val diziUrl  = url.replace("/belgesel/", "/belgeseldizi/")
        val document = app.get(diziUrl).document

        val ld          = document.select("script[type=application/ld+json]").map { it.data() }.firstOrNull { it.contains("TVSeries") }
        val ldIsim      = ld?.let { Regex(""""name"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        val ldResim     = ld?.let { Regex(""""image"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        val ldAciklama  = ld?.let { Regex(""""description"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(it)?.groupValues?.get(1) }
        val ldTarih     = ld?.let { Regex(""""datePublished"\s*:\s*"(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val title       = (document.selectFirst("h1.px-hero-title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: ldIsim)?.toTitleCase() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.px-dizi-card-poster img")?.attr("src")) ?: fixUrlNull(ldResim)
        val description = ldAciklama?.replace("\\\"", "\"")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val tags        = document.select("a[href*='/belgeselkanali/'], a[href*='/konu/']")
            .filter { it.closest("nav, header, #px-header, .px-dd, footer") == null }
            .map { it.text().trim().toTitleCase() }
            .filter { it.isNotBlank() }
            .distinct()

        // ? Bölüm listesi izleme sayfasında: onclick="diziGetir('id','ic1','ic2','ic3','başlık','hit','tarih','sezon','bölüm',...)"
        val izlemeUrl = fixUrlNull(document.selectFirst("a.px-ep-card[href*='/belgesel/'], a[href*='/belgesel/']")?.attr("href"))
            ?: diziUrl.replace("/belgeseldizi/", "/belgesel/")
        val izlemeDoc = app.get(izlemeUrl, referer = diziUrl).document

        var sayac    = 0
        val episodes = izlemeDoc.select("[onclick*=diziGetir]").mapNotNull { el ->
            val arg = Regex("""diziGetir\((.*)\)""").find(el.attr("onclick"))?.groupValues?.get(1) ?: return@mapNotNull null
            val p   = arg.trim().removePrefix("'").substringBeforeLast("'").split("','")
            if (p.size < 9) return@mapNotNull null

            val bolumId = p[0].trim().takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: return@mapNotNull null
            sayac++

            newEpisode(BlxBolum(bolumId, p[1].trim(), p[2].trim(), p[3].trim(), izlemeUrl).toJson()) {
                this.name    = p[4].replace("\\'", "'").trim().ifBlank { null }
                this.season  = p[7].trim().toIntOrNull() ?: 1
                this.episode = p[8].trim().toIntOrNull() ?: sayac
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, diziUrl, TvType.Documentary, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = ldTarih
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("BLX", "data » $data")
        val bolum = jsonOku<BlxBolum>(data) ?: return false
        val srcMap = mapOf("0" to "new5", "2" to "new1", "5" to "new4", "3" to "new2", "4" to "new3")

        var bulundu = false
        listOf(bolum.ic1, bolum.ic2, bolum.ic3).forEachIndexed { i, ic ->
            if (ic.isBlank()) return@forEachIndexed
            val dosya  = srcMap[ic] ?: "default"
            val kaynak = "${mainUrl}/video/data/${dosya}.php?id=${bolum.id}&sira=${i + 1}"
            Log.d("BLX", "kaynak » $kaynak")

            val metin = runCatching { app.get(kaynak, referer = bolum.sayfa).text }.getOrNull() ?: return@forEachIndexed

            // jwplayer kaynakları: {file:"...", label: "720p", ...}
            Regex("""file\s*:\s*"([^"]*)"\s*,\s*label\s*:\s*"([^"]*)"""").findAll(metin).forEach { m ->
                val videoUrl = m.groupValues[1].trim()
                if (videoUrl.isBlank()) return@forEach
                var kalite   = m.groupValues[2]
                var isim     = this.name
                if (kalite.equals("FULL", true)) { kalite = "1080p"; isim = "Google" }

                bulundu = true
                callback.invoke(
                    newExtractorLink(source = isim, name = "$isim ${m.groupValues[2]}", url = fixUrl(videoUrl), type = ExtractorLinkType.VIDEO) {
                        this.referer = "${mainUrl}/"
                        this.quality = getQualityFromName(kalite)
                    }
                )
            }

            // Gömülü oynatıcılar (ok.ru, dailymotion, yandex vb.)
            Jsoup.parse(metin).select("iframe").forEach { f ->
                val src = f.attr("src").ifBlank { f.attr("data-src") }.trim()
                if (src.isBlank() || src.startsWith("about:")) return@forEach
                val tam = if (src.startsWith("//")) "https:$src" else fixUrl(src)
                Log.d("BLX", "iframe » $tam")
                bulundu = true
                runCatching { loadExtractor(tam, "${mainUrl}/", subtitleCallback, callback) }
            }
        }

        return bulundu
    }

    data class BlxBolum(
        @JsonProperty("id")    val id: String,
        @JsonProperty("ic1")   val ic1: String,
        @JsonProperty("ic2")   val ic2: String,
        @JsonProperty("ic3")   val ic3: String,
        @JsonProperty("sayfa") val sayfa: String
    )
}

package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class InatBox : MainAPI() {
    override var name                 = "InatBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)
    override var sequentialMainPage   = false

    private val urlToSearchResponse = mutableMapOf<String, SearchResponse>()

    // ! Adlar uygulamanın kategori dizinindeki adlarla aynıdır; güncel adresler çalışma anında
    // ! InatIstek.kategoriAdresi() ile dizinden alınır, buradaki adresler yalnızca yedektir.
    override val mainPage = mainPageOf(
        "https://sprboxs.bar/CDN/001/SPR/v2/spor_v3.php"                          to "Spor",
        "https://sprboxs.bar/CDN/001/SPR/v2/derbiler.php"                         to "Derbiler",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/list1.php"              to "Liste 1 - TR",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/list2.php"              to "Liste 2 - GLB",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/list3.php"              to "Liste 3 - TR",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/sinema.php"             to "Sinema",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/belgesel.php"           to "Belgesel",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/ulusal.php"             to "Ulusal",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/haber.php"              to "Haber",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/cocuk.php"              to "Çocuk",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/dini.php"               to "Dini",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/ex/index.php"              to "EXXEN",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/ga/index.php"              to "Gain",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/nf/index.php"              to "Netflix",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/hb/index.php"              to "HBO Max - (BLUTV)",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/dsny/index.php"            to "Disney+",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/amz/index.php"             to "Amazon Prime",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tbi/index.php"             to "Tabii",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/film/mubi.php"             to "Mubi",
        "https://sprboxs.bar/CDN/001/SPR/v2/ccc/a/index.php"                      to "TOD",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/yabanci-dizi/index.php"    to "Yabancı Diziler",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/yerli-dizi/index.php"      to "Yerli Diziler",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/film/yerli-filmler.php"    to "Yerli Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val adres = InatIstek.kategoriAdresi(request.name, request.data)
        val jsonResponse =
            makeInatRequest(adres) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val searchResults = getSearchResponseList(jsonResponse, request.name)

        for (searchResponse in searchResults) {
            val url = searchResponse.url
            if (!urlToSearchResponse.containsKey(url)) {
                urlToSearchResponse[url] = searchResponse
            }
        }

        // Return a HomePageResponse with the parsed results
        return newHomePageResponse(request.name, searchResults, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (urlToSearchResponse.isEmpty()) {
            for (pageData in mainPage) {
                val url = InatIstek.kategoriAdresi(pageData.name, pageData.data)
                val jsonResponse = makeInatRequest(url) ?: continue

                val searchResults = getSearchResponseList(jsonResponse, pageData.name)

                for (searchResponse in searchResults) {
                    val contentUrl = searchResponse.url
                    if (!urlToSearchResponse.containsKey(contentUrl)) {
                        urlToSearchResponse[contentUrl] = searchResponse
                    }
                }
            }
        }

        val matchingResults = mutableListOf<SearchResponse>()

        val regex = try {
            Regex(query, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        }

        for ((_, searchResponse) in urlToSearchResponse) {
            if (regex.containsMatchIn(searchResponse.name)) {
                matchingResults.add(searchResponse)
            }
        }

        return matchingResults.distinctBy { it.name }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val item = JSONObject(url)

        if (!inatContentAllowed(item)) {
            return null
        }

        if (item.has("diziType")) {
            val type = tipAl(item)

            return when (type) {
                "dizi" -> parseTvSeriesResponse(item)
                "film" -> parseMovieResponse(item)
                else -> null
            }

        } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
            return if (canliMi(item)) parseLiveStreamLoadResponse(item) else parseMovieResponse(item)
        } else {
            return null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("InatBox", "data: $data")
        return try {
            if (data.startsWith("[")) {
                val chContentJsonArray = JSONArray(data)
                for (i in 0 until chContentJsonArray.length()) {
                    val chContentJsonObject = chContentJsonArray.getJSONObject(i)
                    val chContent = parseToChContent(chContentJsonObject)
                    loadChContentLinks(chContent, subtitleCallback, callback)
                }
            } else {
                val chContentJsonArray = JSONObject(data)
                val chContent = parseToChContent(chContentJsonArray)
                loadChContentLinks(chContent, subtitleCallback, callback)
            }
            true
        } catch (e: Exception) {
            Log.e("InatBox", "Error on loadLinks:${e::class.simpleName} - ${e.message}")
            false
        }
    }

    private suspend fun parseTvSeriesResponse(item: JSONObject, tvType: TvType = TvType.TvSeries): LoadResponse? {
        val episodes = mutableMapOf<DubStatus, MutableList<Episode>>()
        val seasonDataList = mutableListOf<SeasonData>()

        val name = item.getString("diziName")
        val url = item.getString("diziUrl")
        val plot = item.getString("diziDetay")

        val jsonResponse = makeInatRequest(url) ?: return null
        val jsonArray = JSONArray(jsonResponse)

        try {
            for (i in 0 until jsonArray.length()) {
                val seasonItem = jsonArray.getJSONObject(i)
                val seasonName = seasonItem.getString("diziName")
                val seasonData = SeasonData(season = (i + 1), name = seasonName)
                seasonDataList.add(seasonData)

                val seasonUrl = seasonItem.getString("diziUrl")

                // Fetch the episode data for this season
                val episodeResponse = makeInatRequest(seasonUrl) ?: continue
                val episodeArray = try {
                    JSONArray(episodeResponse)
                } catch (e: Exception) {
                    Log.e("InatBox", "Failed to parse episode JSON for season: $seasonName", e)
                    continue
                }

                for (j in 0 until episodeArray.length()) {
                    try {
                        val episodeItem = episodeArray.getJSONObject(j)
                        val episodeName = episodeItem.getString("chName")
                        val episodePoster = episodeItem.getString("chImg")
                        episodes.getOrPut(DubStatus.None) { mutableListOf() }.add(
                            newEpisode(episodeItem.toString()) {
                                this.name = episodeName
                                this.posterUrl = episodePoster
                                this.season = i + 1
                                this.episode = j + 1
                            }
                        )
                    } catch (e: JSONException) {
                        continue
                    }
                }
            }

            // Get the poster URL from the first season
            val firstSeason = jsonArray.getJSONObject(0)
            val posterUrl = firstSeason.getString("diziImg")

            return newAnimeLoadResponse(
                name = name,
                url = item.toString(),
                type = tvType,
                comingSoonIfNone = false
            ) {
                this.episodes = episodes.mapValues { it.value.toList() }.toMutableMap()
                this.posterUrl = posterUrl
                this.plot = plot
                this.seasonNames = seasonDataList
            }
        } catch (e: Exception) {
            Log.e(
                "InatBox",
                "Failed to parse TV series response: ${e.message}\nStacktrace:${
                    e.stackTrace.joinToString("\n")
                }"
            )
            return null
        }
    }

    private suspend fun parseMovieResponse(item: JSONObject): LoadResponse? {
        try {
            if (item.has("diziType")) {
                val name = item.getString("diziName")
                val url = item.getString("diziUrl")
                val posterUrl = item.getString("diziImg")
                val plot = item.getString("diziDetay")

                val jsonResponse = makeInatRequest(url) ?: return null
                val jsonArray = JSONArray(jsonResponse)

                return newMovieLoadResponse(name = name,url = item.toString(), type = TvType.Movie, dataUrl = jsonArray.toString()){
                    this.posterUrl = posterUrl
                    this.plot = plot
                }
            } else {
                val name = item.getString("chName")
                item.getString("chUrl")
                val posterUrl = item.getString("chImg")
                return newMovieLoadResponse(name, item.toString(), TvType.Movie, item.toString()) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse movie response: ${e.message}")
            return null
        }
    }

    private suspend fun parseLiveSportsStreamLoadResponse(item: JSONObject): LiveStreamLoadResponse? {
        try {
            val chContent = parseToChContent(item)
            val posterUrl = chContent.chImg

            return newLiveStreamLoadResponse(name, item.toString(), item.toString()) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse sports live stream response: ${e.message}")
            return null
        }
    }

    private suspend fun parseLiveStreamLoadResponse(item: JSONObject): LiveStreamLoadResponse? {
        try {
            val chContent = parseToChContent(item)
            val name = chContent.chName
            val posterUrl = chContent.chImg

            return newLiveStreamLoadResponse(name, item.toString(), item.toString()) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse movie response: ${e.message}")
            return null
        }
    }

    /** "tekli_regex_mode" → "tekli_regex" (tipler 2026'dan beri "_mode" ekiyle geliyor). */
    private fun tipAl(item: JSONObject): String {
        val ham = if (item.has("diziType")) item.optString("diziType") else item.optString("chType")
        return ham.removeSuffix("_mode")
    }

    private fun inatContentAllowed(item: JSONObject): Boolean =
        tipAl(item) !in setOf("link", "web", "destek")

    private fun canliMi(item: JSONObject): Boolean {
        if (item.optBoolean("_canli", false)) return true
        val tip = tipAl(item)
        return tip.startsWith("live_url") || tip.startsWith("cable")
    }

    private fun String.vkSourceFix(): String {
        if (this.startsWith("act")) {
            return "https://vk.com/al_video.php?${this}"
        }
        return this
    }

    private fun parseToChContent(item: JSONObject): ChContent {
        return ChContent(
            chName    = item.optString("chName"),
            chUrl     = item.optString("chUrl").vkSourceFix(),
            chImg     = item.optString("chImg"),
            chHeaders = item.opt("chHeaders")?.toString() ?: "null",
            chReg     = item.opt("chReg")?.toString() ?: "null",
            chType    = item.optString("chType")
        )
    }

    /** chHeaders / chReg: "null", "{...}" ya da "[{...}]" olabilir; ilk nesneyi döndürür. */
    private fun ilkNesne(ham: String?): JSONObject? {
        val metin = ham?.trim().orEmpty()
        if (metin.isEmpty() || metin == "null") return null
        return runCatching {
            when {
                metin.startsWith("[") -> JSONArray(metin).optJSONObject(0)
                metin.startsWith("{") -> JSONObject(metin)
                else                  -> null
            }
        }.getOrNull()
    }

    /** Kayıttaki başlık adlarını gerçek HTTP başlık adlarına çevirir (UserAgent → User-Agent …). */
    private fun basliklariOku(ch: ChContent): MutableMap<String, String> {
        val basliklar = mutableMapOf<String, String>()
        ilkNesne(ch.chHeaders)?.let { nesne ->
            for (ad in nesne.keys()) {
                val deger = nesne.optString(ad)
                if (deger.isBlank() || deger == "null") continue
                val gercekAd = when (ad.lowercase().replace("-", "").replace("_", "")) {
                    "useragent"      -> "User-Agent"
                    "xrequestedwith" -> "X-Requested-With"
                    "referer", "referrer" -> "Referer"
                    "origin"         -> "Origin"
                    "cookie"         -> "Cookie"
                    else             -> ad
                }
                basliklar[gercekAd] = deger
            }
        }
        ilkNesne(ch.chReg)?.optString("playSH2")?.takeIf { it.isNotBlank() && it != "null" }?.let { basliklar["Cookie"] = it }
        return basliklar
    }

    private fun adresTemizle(adres: String): String =
        adres.trim().replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
            .let { if (it.startsWith("//")) "https:$it" else it }

    private fun dogrudanYayinMi(adres: String): Boolean {
        val k = adres.lowercase().substringBefore("?")
        return k.endsWith(".m3u8") || k.endsWith(".mpd") || k.endsWith(".mp4") || k.endsWith(".mkv") || k.endsWith(".webm") ||
            adres.contains(".m3u8", true) || k.contains("/hls/")
    }

    /** Sayfayı (gerekirse POST ile) açıp Regex1'in ilk grubunu yayın adresi olarak alır. */
    private suspend fun regexIleCoz(adres: String, basliklar: Map<String, String>, desen: String?, postGovde: String? = null): String? {
        if (desen.isNullOrBlank() || desen == "null") return adres
        val metin = runCatching {
            if (postGovde != null) {
                app.post(adres, headers = basliklar, requestBody = postGovde.toRequestBody("application/x-www-form-urlencoded".toMediaType())).text
            } else {
                app.get(adres, headers = basliklar).text
            }
        }.onFailure { Log.w("InatBox", "regex sayfası açılamadı » $adres » ${it.message}") }.getOrNull() ?: return null

        val bulunan = runCatching { Regex(desen).find(metin)?.groupValues?.getOrNull(1) }.getOrNull()
        if (bulunan.isNullOrBlank()) {
            Log.w("InatBox", "regex eşleşmedi » $adres")
            return null
        }
        return adresTemizle(bulunan)
    }

    /** tekli_regex_lb_sh_3: imzalı GET → iki katman AES (Regex1, Regex2/Regex2p) → {"chUrl": ...} */
    private suspend fun lbIleCoz(adres: String, basliklar: Map<String, String>, ayar: JSONObject?): String? {
        val anahtarlar = listOf("Regex1", "Regex2", "Regex2p")
            .mapNotNull { ayar?.optString(it)?.takeIf { a -> a.isNotBlank() && a != "null" } }

        val json = InatIstek.imzaliGet(adres, basliklar)?.let { InatIstek.coz(it, anahtarlar) }
            ?: InatIstek.istek(adres, anahtarlar)
            ?: return null

        return runCatching {
            val metin = json.trim()
            val nesne = if (metin.startsWith("[")) JSONArray(metin).getJSONObject(0) else JSONObject(metin)
            nesne.optString("chUrl").takeIf { it.isNotBlank() }?.let { adresTemizle(it) }
        }.getOrNull()
    }

    private suspend fun loadChContentLinks(ch: ChContent, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val tip       = ch.chType.removeSuffix("_mode")
        val basliklar = basliklariOku(ch)
        val ayar      = ilkNesne(ch.chReg)
        val regex1    = ayar?.optString("Regex1")

        val adres: String? = when {
            tip.startsWith("tekli_regex_lb_sh") -> lbIleCoz(ch.chUrl, basliklar, ayar)
            tip.startsWith("tekli_regex")       -> regexIleCoz(ch.chUrl, basliklar, regex1)
            tip.startsWith("nok5")              -> {
                // VK: al_video.php'ye kaydın sorgusu POST gövdesi olarak gönderilir
                val sorgu = ch.chUrl.substringAfter("al_video.php?", "")
                if (sorgu.isNotBlank()) regexIleCoz("https://vk.com/al_video.php", basliklar, regex1, sorgu)
                else regexIleCoz(ch.chUrl, basliklar, regex1)
            }
            else -> adresTemizle(ch.chUrl)
        }

        if (adres.isNullOrBlank()) {
            Log.w("InatBox", "yayın adresi çözülemedi » ${ch.chName} (${ch.chType})")
            return
        }
        Log.d("InatBox", "yayın » ${ch.chName} » $adres")

        if (dogrudanYayinMi(adres)) {
            if (basliklar["Referer"].isNullOrBlank()) {
                runCatching { URI(adres) }.getOrNull()?.let { u -> basliklar["Referer"] = "${u.scheme}://${u.host}/" }
            }
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = ch.chName,
                    url    = adres,
                    type   = when {
                        adres.contains(".mpd", true)                                 -> ExtractorLinkType.DASH
                        adres.contains(".m3u8", true) || adres.contains("/hls/", true) -> ExtractorLinkType.M3U8
                        else                                                          -> ExtractorLinkType.VIDEO
                    }
                ) {
                    this.referer = basliklar["Referer"].orEmpty()
                    this.quality = Qualities.Unknown.value
                    this.headers = basliklar
                }
            )
            return
        }

        // Doğrudan yayın değilse (Yandex Disk, VK sayfası, Dzen …) çıkarıcılara bırak
        val bulunanlar = mutableListOf<ExtractorLink>()
        val cikariciVar = loadExtractor(adres, basliklar["Referer"], subtitleCallback) { bulunanlar.add(it) }
        bulunanlar.forEach { link ->
            callback.invoke(
                newExtractorLink(source = link.source, name = ch.chName, url = link.url, type = link.type) {
                    this.referer = link.referer
                    this.quality = link.quality
                    this.headers = link.headers
                }
            )
        }

        if (!cikariciVar) {
            callback.invoke(
                newExtractorLink(source = this.name, name = ch.chName, url = adres, type = ExtractorLinkType.VIDEO) {
                    this.referer = basliklar["Referer"].orEmpty()
                    this.quality = Qualities.Unknown.value
                    this.headers = basliklar
                }
            )
        }
    }

    private suspend fun makeInatRequest(url: String): String? = InatIstek.istek(url)

    private val canliKategoriler = setOf(
        "Spor", "Liste 1 - TR", "Liste 2 - GLB", "Liste 3 - TR", "Sinema", "Belgesel", "Ulusal", "Haber", "Çocuk", "Dini"
    )

    private fun getSearchResponseList(jsonResponse: String, kategori: String? = null): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val kategoriCanli = kategori != null && kategori in canliKategoriler
        try {
            val jsonArray = JSONArray(jsonResponse)

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(i) ?: continue

                if (!inatContentAllowed(item)) continue

                if (item.has("diziType")) {
                    val name      = item.optString("diziName")
                    val posterUrl = item.optString("diziImg")

                    val searchResponse = when (tipAl(item)) {
                        "dizi" -> newTvSeriesSearchResponse(name, item.toString()) { this.posterUrl = posterUrl }
                        "film" -> newMovieSearchResponse(name, item.toString()) { this.posterUrl = posterUrl }
                        else   -> null
                    }
                    searchResponse?.let { searchResults.add(it) }
                } else if (item.has("chName") && item.has("chUrl")) {
                    if (kategoriCanli) item.put("_canli", true)
                    val name      = item.optString("chName")
                    val posterUrl = item.optString("chImg")

                    val searchResponse = if (canliMi(item)) {
                        newLiveSearchResponse(name, item.toString(), TvType.Live) { this.posterUrl = posterUrl }
                    } else {
                        newMovieSearchResponse(name, item.toString()) { this.posterUrl = posterUrl }
                    }
                    searchResults.add(searchResponse)
                }
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse JSON response: ${e.message}")
        }

        return searchResults
    }
}

package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

        val searchResults = getSearchResponseList(jsonResponse)

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

                val searchResults = getSearchResponseList(jsonResponse)

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
            item.getString("diziName")
            val type = item.getString("diziType")

            return when (type) {
                "dizi" -> parseTvSeriesResponse(item)
                "film" -> parseMovieResponse(item)
                else -> null
            }

        } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
            val chType = item.optString("chType")

            val loadResponse = when (chType) {
                "live_url", "live_url_mode", "cable_sh" -> parseLiveStreamLoadResponse(item)
                "tekli_regex_lb_sh_3" -> parseLiveSportsStreamLoadResponse(item)
                else -> parseMovieResponse(item)
            }
            return loadResponse
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

    private fun inatContentAllowed(item: JSONObject): Boolean {
        val type: String = if (item.has("diziType")) {
            item.getString("diziType")
        } else {
            item.optString("chType")
        }

        return when (type) {
            "link", "web" -> false
            else -> true
        }
    }

    private fun String.vkSourceFix(): String {
        if (this.startsWith("act")) {
            return "https://vk.com/al_video.php?${this}"
        }
        return this
    }

    private fun parseToChContent(item: JSONObject): ChContent {
        return ChContent(
            chName = item.optString("chName"),
            chUrl = item.optString("chUrl").vkSourceFix(),
            chImg = item.optString("chImg"),
            chHeaders = item.optString("chHeaders", "null"),
            chReg = item.optString("chReg", "null"),
            chType = item.optString("chType")
        )
    }

    private suspend fun loadChContentLinks(chContent: ChContent, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit){
        val chType = chContent.chType
        val contentToProcess : ChContent

        if(chType == "tekli_regex_lb_sh_3"){
            val name = chContent.chName
            val url = chContent.chUrl
            val posterUrl = chContent.chImg
            val headers = chContent.chHeaders
            val reg = chContent.chReg
            val type = chContent.chType

            val satirAnahtari = runCatching { JSONArray(reg).getJSONObject(0).optString("Regex1") }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != "null" }
            val jsonResponse = runCatching { InatIstek.istek(url, satirAnahtari) }.getOrNull()
                ?: InatIstek.coz(runCatching { app.get(url).text }.getOrNull(), listOfNotNull(satirAnahtari, InatIstek.VARSAYILAN_ANAHTAR))
                ?: return
            val firstItem = jsonResponse.trim().let { if (it.startsWith("[")) JSONArray(it).getJSONObject(0) else JSONObject(it) }
            firstItem.put("chHeaders", headers)
            firstItem.put("chReg", reg)
            firstItem.put("chName",name)
            firstItem.put("chImg",posterUrl)
            firstItem.put("chType",type)
            contentToProcess = parseToChContent(firstItem)
        } else{
            contentToProcess = chContent
        }

        val sourceUrl = contentToProcess.chUrl

        val headers: MutableMap<String, String> = mutableMapOf()
        try {
            val chHeaders = contentToProcess.chHeaders
            val chReg = contentToProcess.chReg
            if (chHeaders != "null") {
                val jsonHeaders = JSONArray(chHeaders).getJSONObject(0)
                for (entry in jsonHeaders.keys()) {
                    headers[entry] = jsonHeaders[entry].toString()
                }
            }
            if (chReg != "null") {
                val jsonReg = JSONArray(chReg).getJSONObject(0)
                val cookie = jsonReg.getString("playSH2")
                headers["Cookie"] = cookie
            }
        } catch (_: Exception) {

        }

        val bulunanlar = mutableListOf<ExtractorLink>()
        val extractorFound =
            loadExtractor(sourceUrl, headers["Referer"], subtitleCallback) { bulunanlar.add(it) }

        bulunanlar.forEach { link ->
            callback.invoke(
                newExtractorLink(
                    source = link.source,
                    name   = contentToProcess.chName,
                    url    = link.url,
                    type   = link.type
                ) {
                    this.referer = link.referer
                    this.quality = link.quality
                    this.headers = link.headers
                }
            )
        }

        //When no extractor found, try to load as generic
        if (!extractorFound) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = contentToProcess.chName,
                    url    = sourceUrl,
                    type   = if(sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else if(sourceUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.VIDEO
                ) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }

    private suspend fun makeInatRequest(url: String): String? = InatIstek.istek(url)

    private fun getSearchResponseList(jsonResponse: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        try {
            val jsonArray = JSONArray(jsonResponse)

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)

                if (!inatContentAllowed(item)) {
                    continue
                }

                //Let's pass item directly to the next step
                if (item.has("diziType")) {
                    val name = item.getString("diziName")
                    val type = item.getString("diziType")
                    val posterUrl = item.getString("diziImg")

                    val searchResponse = when (type) {
                        "dizi" -> newTvSeriesSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }

                        "film" -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }

                        else -> null // Ignore unsupported types
                    }
                    searchResponse?.let { searchResults.add(it) }
                } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
                    // Handle the case where diziType is missing but chName, chUrl, and chImg are present
                    val name = item.getString("chName")
                    val posterUrl = item.getString("chImg")
                    val chType = item.optString("chType")

                    val searchResponse = when (chType) {
                        "live_url", "live_url_mode", "cable_sh", "tekli_regex_lb_sh_3" -> newLiveSearchResponse(name, item.toString(), TvType.Live) {
                            this.posterUrl = posterUrl
                        }

                        else -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }
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
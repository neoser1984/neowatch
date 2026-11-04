package com.neowatch

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * FirePlayer tabanlı oynatıcılar için genel çözücü.
 * Örnekler: https://<host>/video/<32 haneli hash>, https://<host>/tv/video/<hash>
 *
 * Oynatıcı sayfası, kendi adresine göreli "?do=getVideo" isteği atar
 * (bazı sunucularda bu "/player/index.php?data=<hash>&do=getVideo" adresine yönlenir).
 * Yanıt iki biçimde gelebilir:
 *   - {"hls":true,"videoSource":"...","securedLink":"..."}
 *   - {"videoSources":[{"file":"...m3u8","label":"HD","type":"hls"}],"videoSrc":"<iframe adresi>"}
 */
open class FirePlayer : ExtractorApi() {
    override val name            = "FirePlayer"
    override val mainUrl         = "https://playerdkorea.xyz"
    override val requiresReferer = true

    companion object {
        private val hashRegex = Regex("""/video/([a-fA-F0-9]{32})""")

        fun isFirePlayer(url: String): Boolean = hashRegex.containsMatchIn(url)
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base   = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: return
        val hash   = hashRegex.find(url)?.groupValues?.get(1) ?: return
        val host   = base.substringAfter("://")
        val extRef = referer ?: "$base/"
        val sayfa  = url.substringBefore("?").substringBefore("#")

        val basliklar = mapOf(
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin"           to base
        )

        val adresler = listOf(
            "$sayfa?do=getVideo",
            "$base/player/index.php?data=$hash&do=getVideo"
        ).distinct()

        var yanit: FireResponse? = null
        for (adres in adresler) {
            yanit = runCatching {
                app.post(
                    adres,
                    data    = mapOf("hash" to hash, "r" to extRef, "s" to ""),
                    referer = url,
                    headers = basliklar
                ).parsedSafe<FireResponse>()
            }.getOrNull()

            if (yanit?.doluMu() == true) break
        }
        val r = yanit ?: return

        // Altyazılar (varsa)
        (r.videoSubtitles as? List<*>)?.forEach { sub ->
            val harita = sub as? Map<*, *> ?: return@forEach
            val dosya  = (harita["file"] as? String)?.takeIf { it.isNotBlank() } ?: return@forEach
            subtitleCallback.invoke(newSubtitleFile((harita["label"] as? String) ?: "Türkçe", tamAdres(dosya, base)))
        }

        // Eski biçim
        val eski = r.securedLink?.takeIf { it.isNotBlank() } ?: r.videoSource?.takeIf { it.isNotBlank() }
        if (eski != null) {
            Log.d("NeO_FirePlayer", "$host » $eski")
            callback.invoke(
                newExtractorLink(host, host, tamAdres(eski, base), if (r.hls == true || eski.contains(".m3u8")) ExtractorLinkType.M3U8 else null) {
                    this.referer = "$base/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // Yeni biçim: kaynak listesi
        r.videoSources?.forEach { kaynak ->
            val dosya = kaynak.file?.takeIf { it.isNotBlank() }?.replace("GenYoutube.net", host) ?: return@forEach
            val adres = tamAdres(dosya, base)
            Log.d("NeO_FirePlayer", "$host » $adres")

            callback.invoke(
                newExtractorLink(
                    source = host,
                    name   = if (kaynak.label.isNullOrBlank()) host else "$host - ${kaynak.label}",
                    url    = adres,
                    type   = if (kaynak.type == "hls" || adres.contains(".m3u8")) ExtractorLinkType.M3U8 else null
                ) {
                    this.referer = "$base/"
                    this.quality = getQualityFromName(kaynak.label)
                }
            )
        }

        // Başka bir oynatıcıya yönlendirme
        r.videoSrc?.takeIf { it.isNotBlank() }?.let { gomulu ->
            Log.d("NeO_FirePlayer", "$host iframe » $gomulu")
            loadExtractor(tamAdres(gomulu, base), "$base/", subtitleCallback, callback)
        }
    }

    private fun tamAdres(adres: String, base: String): String = when {
        adres.startsWith("//") -> "https:$adres"
        adres.startsWith("/")  -> "$base$adres"
        else                   -> adres
    }

    data class FireResponse(
        @JsonProperty("hls")            val hls: Boolean?               = null,
        @JsonProperty("videoSource")    val videoSource: String?        = null,
        @JsonProperty("securedLink")    val securedLink: String?        = null,
        @JsonProperty("videoSrc")       val videoSrc: String?           = null,
        @JsonProperty("videoSources")   val videoSources: List<Kaynak>? = null,
        @JsonProperty("videoSubtitles") val videoSubtitles: Any?          = null
    ) {
        fun doluMu(): Boolean = !securedLink.isNullOrBlank() || !videoSource.isNullOrBlank() ||
            !videoSrc.isNullOrBlank() || !videoSources.isNullOrEmpty()
    }

    data class Kaynak(
        @JsonProperty("file")  val file: String?  = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type")  val type: String?  = null
    )
}

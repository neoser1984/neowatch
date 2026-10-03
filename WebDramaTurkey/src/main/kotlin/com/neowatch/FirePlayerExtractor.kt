package com.neowatch

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * FirePlayer tabanlı oynatıcılar için genel çözücü.
 * Örnek: https://<host>/video/<32 haneli hash>
 * Oynatıcı, "/player/index.php?data=<hash>&do=getVideo" adresine POST isteği ile m3u8 bağlantısını döndürür.
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

        val response = app.post(
            "$base/player/index.php?data=$hash&do=getVideo",
            data    = mapOf("hash" to hash, "r" to extRef, "s" to ""),
            referer = url,
            headers = mapOf(
                "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<FireResponse>() ?: return

        val link = response.securedLink?.takeIf { it.isNotBlank() } ?: response.videoSource?.takeIf { it.isNotBlank() } ?: return
        Log.d("NeO_FirePlayer", "$host » $link")

        callback.invoke(
            newExtractorLink(
                source = host,
                name   = host,
                url    = link,
                type   = if (response.hls == true || link.contains(".m3u8")) ExtractorLinkType.M3U8 else null
            ) {
                this.referer = "$base/"
                this.quality = Qualities.Unknown.value
            }
        )
    }

    data class FireResponse(
        @JsonProperty("hls")         val hls: Boolean?        = null,
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )
}

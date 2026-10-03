package com.neowatch

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * Dizipal Orijinal'in kullandığı HLS oynatıcı.
 * Örnek: https://videoplays.cfd/player.html?video=3043
 * Akış: /api/videos/{id}/token?embed_referrer=<site> → playlist_url (m3u8), /api/videos/{id} → altyazılar
 */
open class VideoPlays : ExtractorApi() {
    override val name            = "VideoPlays"
    override val mainUrl         = "https://videoplays.cfd"
    override val requiresReferer = true

    companion object {
        fun uygunMu(url: String): Boolean = url.contains("player.html?video=")
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base    = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: return
        val videoId = Regex("""video=(\d+)""").find(url)?.groupValues?.get(1) ?: return
        val extRef  = referer ?: "$base/"

        val video = app.get("$base/api/videos/$videoId", referer = url).parsedSafe<VideoBilgi>()
        video?.subtitles?.forEach { sub ->
            val subUrl = sub.url ?: return@forEach
            subtitleCallback.invoke(newSubtitleFile(sub.lang ?: "Türkçe", subUrl))
        }

        val token = app.get(
            "$base/api/videos/$videoId/token",
            params  = mapOf("embed_referrer" to extRef),
            referer = url
        ).parsedSafe<TokenBilgi>() ?: return

        val playlist = token.playlistUrl?.takeIf { it.isNotBlank() }
            ?: token.token?.let { "$base/api/videos/$videoId/master.m3u8?token=$it" }
            ?: return
        Log.d("NeO_VideoPlays", "playlist » $playlist")

        callback.invoke(
            newExtractorLink(this.name, this.name, playlist, ExtractorLinkType.M3U8) {
                this.referer = "$base/"
                this.quality = Qualities.Unknown.value
            }
        )
    }

    data class VideoBilgi(
        @JsonProperty("title")     val title: String?           = null,
        @JsonProperty("subtitles") val subtitles: List<Altyazi>? = null
    )

    data class Altyazi(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("url")  val url: String?  = null
    )

    data class TokenBilgi(
        @JsonProperty("token")        val token: String?       = null,
        @JsonProperty("playlist_url") val playlistUrl: String? = null
    )
}

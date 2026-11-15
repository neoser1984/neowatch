package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Byse (bysejikuar.com, byse.sx …) oynatıcısı.
 * Ayrıntı uç noktası gömen sitenin Referer'ını istiyor (yoksa "embedding from this domain is not allowed");
 * CloudStream'in yerleşik ByseSX çıkarıcısı Referer göndermediği için başarısız oluyor.
 * API ile alınamazsa sayfa gömülü WebView'da açılıp HLS isteği yakalanır.
 */
open class ByseNeo : ExtractorApi() {
    override val name            = "Byse"
    override val mainUrl         = "https://byse.sx"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base   = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: return
        val parent = referer ?: "$base/"

        val akis = runCatching { apiIle(url, base, parent) }.onFailure { Log.d("NeO_Byse", "api » ${it.message}") }.getOrNull()
            ?: runCatching { tarayiciIle(url, parent) }.onFailure { Log.d("NeO_Byse", "WebView » ${it.message}") }.getOrNull()
            ?: return
        Log.d("NeO_Byse", "akış » $akis")

        callback.invoke(
            newExtractorLink(name, name, akis, ExtractorLinkType.M3U8) {
                this.referer = "$base/"
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun kod(adres: String) = adres.substringBefore("?").trimEnd('/').substringAfterLast('/')

    private suspend fun apiIle(url: String, base: String, parent: String): String? {
        val ayrinti = app.get(
            "$base/api/videos/${kod(url)}/embed/details",
            referer = parent,
            headers = mapOf("Accept" to "application/json", "x-embed-parent" to parent)
        ).text
        val cerceve = JSONObject(ayrinti).optString("embed_frame_url").ifBlank { null } ?: return null
        val cBase   = Regex("""^(https?://[^/]+)""").find(cerceve)?.groupValues?.get(1) ?: return null

        val yanit = app.get(
            "$cBase/api/videos/${kod(cerceve)}/embed/playback",
            referer = cerceve,
            headers = mapOf("Accept" to "*/*", "x-embed-parent" to parent)
        ).text
        val oynat = JSONObject(yanit).optJSONObject("playback") ?: return null

        val parcalar = oynat.optJSONArray("key_parts") ?: return null
        val anahtar  = b64(parcalar.getString(0)) + b64(parcalar.getString(1))
        val cipher   = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(anahtar, "AES"), GCMParameterSpec(128, b64(oynat.getString("iv"))))
        val duz = String(cipher.doFinal(b64(oynat.getString("payload"))), Charsets.UTF_8).removePrefix("﻿")

        return JSONObject(duz).optJSONArray("sources")?.optJSONObject(0)?.optString("url")?.ifBlank { null }
    }

    private suspend fun tarayiciIle(url: String, parent: String): String? {
        val (istek, _) = WebViewResolver(
            interceptUrl = Regex("""\.m3u8"""),
            useOkhttp    = false,
            timeout      = 20_000L
        ).resolveUsingWebView(url, referer = parent)
        return istek?.url?.toString()
    }

    private fun b64(s: String): ByteArray {
        val d = s.replace('-', '+').replace('_', '/')
        return base64DecodeArray(d + "=".repeat((4 - d.length % 4) % 4))
    }
}

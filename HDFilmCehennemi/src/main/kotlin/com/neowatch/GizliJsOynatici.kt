package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable

/**
 * CloseLoad / Rapidrame / Rapid tipi JWPlayer oynatıcıları.
 * Video adresi her istekte değişen gizlenmiş JavaScript fonksiyonları ile üretilir
 * (bazen P.A.C.K.E.R. ile paketlenmiş). İlgili betikler Rhino ile çalıştırılıp
 * `sources: [{file: X}]` değişkeninin değeri okunur.
 */
open class GizliJsOynatici : ExtractorApi() {
    override val name            = "CloseLoad"
    override val mainUrl         = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base   = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: return
        val host   = base.substringAfter("://")
        val isim   = when {
            url.contains("/rplayer/") || url.contains("/playerr/") -> "Rapidrame"
            host.startsWith("rapid")                               -> "Rapid"
            else                                                   -> "CloseLoad"
        }
        val extRef = referer ?: "$base/"

        val html = app.get(
            url,
            referer = extRef,
            headers = mapOf("Sec-Fetch-Dest" to "iframe", "Sec-Fetch-Mode" to "navigate")
        ).text

        // ? Altyazılar
        Regex("""\{[^{}]*?"file"\s*:\s*"([^"]+?\.(?:vtt|srt)[^"]*)"[^{}]*?"label"\s*:\s*"([^"]*)"[^{}]*?\}""").findAll(html).forEach {
            val (dosya, etiket) = it.destructured
            val subUrl = dosya.replace("\\/", "/").let { s -> if (s.startsWith("http")) s else "$base/${s.trimStart('/')}" }
            subtitleCallback.invoke(newSubtitleFile(GizliJs.unicodeCoz(etiket), subUrl))
        }

        val degisken = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*([A-Za-z_$][\w$]*)\s*[,}]""").find(html)?.groupValues?.get(1)
        var link     = if (degisken != null) GizliJs.degiskenCoz(html, degisken) else null

        // Eski tip: file_link="base64"
        if (link.isNullOrBlank()) {
            val fileLink = Regex("""file_link\s*=\s*"([^"]+)"""").find(getAndUnpack(html))?.groupValues?.get(1)
            link = fileLink?.let { runCatching { base64Decode(it) }.getOrNull() }
        }

        if (link.isNullOrBlank() || !link.startsWith("http")) {
            Log.d("NeO_$isim", "video adresi çözülemedi » $url")
            return
        }
        Log.d("NeO_$isim", "link » $link")

        callback.invoke(
            newExtractorLink(isim, isim, link, ExtractorLinkType.M3U8) {
                this.referer = "$base/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

object GizliJs {
    fun unicodeCoz(metin: String): String {
        return Regex("""\\u([0-9a-fA-F]{4})""").replace(metin) { it.groupValues[1].toInt(16).toChar().toString() }
    }

    /** Sayfadaki gizlenmiş betikleri (gerekirse paketten çıkararak) çalıştırıp [degisken] değerini döndürür. */
    fun degiskenCoz(html: String, degisken: String): String? {
        val betikler = Jsoup.parse(html).select("script").map { it.data() }.filter {
            it.contains("eval(function(p,a,c,k,e,d)") || it.contains(".join('')") || it.contains(".join(\"\")")
        }
        if (betikler.isEmpty()) return null

        val cx = SinirliFabrika.enterContext()
        return try {
            val scope: Scriptable = cx.initSafeStandardObjects()
            cx.putThreadLocal("baslangic", System.currentTimeMillis())
            cx.evaluateString(scope, POLYFILL, "polyfill", 1, null)

            betikler.forEach { betik ->
                val kod = if (betik.contains("eval(function(p,a,c,k,e,d)")) paketAc(cx, scope, betik) else betik
                if (kod != null) runCatching { cx.evaluateString(scope, kod, "betik", 1, null) }
            }

            val deger = scope.get(degisken, scope)
            if (deger == null || deger == Scriptable.NOT_FOUND) null else Context.toString(deger)
        } catch (e: Throwable) {
            Log.d("NeO_GizliJs", "js hata » ${e.message}")
            null
        } finally {
            Context.exit()
        }
    }

    /** P.A.C.K.E.R. paketini Rhino ile açar, olmazsa CloudStream'in JsUnpacker'ını dener. */
    private fun paketAc(cx: Context, scope: Scriptable, betik: String): String? {
        val paket = getPacked(betik) ?: return null
        val rhino = runCatching {
            Context.toString(cx.evaluateString(scope, paket.removePrefix("eval"), "paket", 1, null))
        }.getOrNull()

        return rhino?.takeIf { it.isNotBlank() && it != "undefined" } ?: JsUnpacker(paket).unpack()
    }

    /** Sonsuz döngülere karşı süre sınırlı Rhino bağlamı. */
    private object SinirliFabrika : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.setInterpretedMode(true)
            cx.instructionObserverThreshold = 10_000
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val baslangic = cx.getThreadLocal("baslangic") as? Long ?: return
            if (System.currentTimeMillis() - baslangic > 5_000) throw Error("JS zaman aşımı")
        }
    }

    // Rhino'da tarayıcıya özgü atob / btoa yok
    private const val POLYFILL = """
        var window = this; var self = this; var document = {}; var navigator = { userAgent: "" };
        var _b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
        function atob(input) {
            var str = String(input).replace(/[=]+$/, "").replace(/[^A-Za-z0-9+\/]/g, "");
            var output = "";
            for (var bc = 0, bs = 0, buffer, idx = 0; (buffer = str.charAt(idx++)); ) {
                buffer = _b64.indexOf(buffer);
                if (~buffer) { bs = bc % 4 ? bs * 64 + buffer : buffer; if (bc++ % 4) output += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6))); }
            }
            return output;
        }
        function btoa(input) {
            var str = String(input), output = "";
            for (var block, charCode, idx = 0, map = _b64; str.charAt(idx | 0) || ((map = "="), idx % 1); output += map.charAt(63 & (block >> (8 - (idx % 1) * 8)))) {
                charCode = str.charCodeAt((idx += 3 / 4));
                block = (block << 8) | charCode;
            }
            return output;
        }
    """
}

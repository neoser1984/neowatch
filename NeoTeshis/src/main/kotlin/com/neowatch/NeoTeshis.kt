package com.neowatch

import android.content.Context
import android.os.Build
import android.preference.PreferenceManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * NeO Teşhis: logcat'e ulaşmanın zor olduğu cihazlarda (TV) eklentilerin uygulama içindeki gerçek
 * davranışını ekranda gösterir. Her kategori bir testtir:
 *  - "Sistem ve ağ": Android/uygulama sürümü, DoH ayarı, sistem DNS'i ve uygulama istemcisiyle site/oynatıcı erişimi
 *  - Eklenti adları: o eklentinin ana sayfa → ayrıntı → loadLinks zinciri, hata ayrıntısı ve o sıradaki logcat satırları
 * Kartın adı özet, karta tıklayınca açılan sayfanın açıklaması tam rapordur.
 */
@Suppress("DEPRECATION")
class NeoTeshis : MainAPI() {
    override var mainUrl              = "https://neowatch.teshis"
    override var name                 = "NeO Teşhis"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)
    override var sequentialMainPage   = true

    companion object {
        var baglam: Context? = null
        private val raporlar = ConcurrentHashMap<String, String>()

        private val EKLENTILER = listOf(
            "SetFilmIzle", "RecTV", "FullHDFilm", "DiziKorea", "SelcukFlix",
            "DiziPalGuncel", "HDFilmCehennemi", "FilmMakinesi", "FullHDFilmizlesene"
        )

        // InatBox alanları bilerek yok (Cloudflare IP yasağı riski)
        private val SUNUCULAR = listOf(
            "www.google.com", "selcukflix.app", "four.pichive.online", "dizipal1595.com", "four.dplayer82.site",
            "www.setfilmizle.ltd", "setplay.sbs", "www.hdfilmcehennemi.nl", "hdfilmcehennemi.mobi",
            "filmmakinesi.to", "www.fullhdfilmizlesene.now", "rapidvid.org", "hdfilm.us", "vidpapi.xyz", "vidmoly.org"
        )

        private val ETIKETLER = Regex(
            """NeO_|/(?:STF|SCF|DZPG|HDCH|FLMM|FHD|FHDF|SFG|SZD|RTV|RecTV|DKR)\b|Exception|Error|Throwable|FATAL|CloudStream|APIRepository|loadLinks""",
            RegexOption.IGNORE_CASE
        )
    }

    override val mainPage = mainPageOf(
        "sistem" to "Sistem ve ağ",
        *EKLENTILER.map { it to "Eklenti: $it" }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val satirlar = runCatching {
            if (request.data == "sistem") sistemTesti() else eklentiTesti(request.data)
        }.getOrElse { listOf("✖ test çöktü" to "Teşhis testinin kendisi hata verdi:\n${hataMetni(it)}") }

        val ogeler = satirlar.mapIndexed { i, (baslik, ayrinti) ->
            val anahtar = "${request.data}-$i"
            raporlar[anahtar] = "$baslik\n\n$ayrinti"
            newMovieSearchResponse(baslik, "$mainUrl/$anahtar", TvType.Movie)
        }
        return newHomePageResponse(request.name, ogeler, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        val anahtar = url.substringAfterLast("/")
        val metin   = raporlar[anahtar] ?: "Bu rapor bellekte yok. Ana sayfada NeO Teşhis'i yeniden yükleyin."
        return newMovieLoadResponse(metin.lineSequence().first().take(60), url, TvType.Movie, url) {
            this.plot = metin
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean = false

    // ------------------------------------------------------------------------------------------------------------------

    private suspend fun sistemTesti(): List<Pair<String, String>> {
        val sonuc = mutableListOf<Pair<String, String>>()
        val ctx   = baglam

        val surum = runCatching { ctx?.let { it.packageManager.getPackageInfo(it.packageName, 0).versionName } }.getOrNull() ?: "?"
        val webview = runCatching {
            if (Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } else null
        }.getOrNull() ?: "?"
        val dns = runCatching {
            ctx?.let { PreferenceManager.getDefaultSharedPreferences(it).getInt("dns_key", -1) }
        }.getOrNull()
        val dnsAdi = when (dns) {
            0 -> "Yok"; 1 -> "Google"; 2 -> "Cloudflare"; 4 -> "AdGuard"; 5 -> "DNS.WATCH"; 6 -> "Quad9"; 7 -> "DNS.SB"; 8 -> "Canadian Shield"
            null, -1 -> "okunamadı"; else -> "kod $dns"
        }

        sonuc.add(
            "Uygulama $surum · Android ${Build.VERSION.RELEASE}" to
                "CloudStream: $surum\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}\nWebView: $webview\nDNS over HTTPS ayarı: $dnsAdi"
        )
        sonuc.add("DoH: $dnsAdi" to "Uygulamanın DNS over HTTPS ayarı (dns_key): $dnsAdi")

        for (sunucu in SUNUCULAR) {
            val sistemDns = runCatching { InetAddress.getAllByName(sunucu).joinToString { it.hostAddress ?: "?" } }
                .getOrElse { "HATA ${it.javaClass.simpleName}: ${it.message}" }
            val baslangic = System.currentTimeMillis()
            val http = runCatching {
                val y = app.get("https://$sunucu/", timeout = 15L)
                val baslik = Regex("""<title[^>]*>([^<]{0,80})""", RegexOption.IGNORE_CASE).find(y.text)?.groupValues?.get(1)?.trim().orEmpty()
                "${y.code} '$baslik'"
            }.getOrElse { "HATA ${it.javaClass.simpleName}: ${it.message?.take(150)}" }
            val sure = System.currentTimeMillis() - baslangic

            val iyi = !http.startsWith("HATA") && !http.contains("Attention Required") && !http.startsWith("403")
            sonuc.add(
                "${if (iyi) "✔" else "✖"} $sunucu ${http.substringBefore(" ").take(12)}" to
                    "Sunucu: $sunucu\nSistem DNS: $sistemDns\nUygulama isteği (${sure} ms): $http"
            )
        }
        return sonuc
    }

    private suspend fun eklentiTesti(ad: String): List<Pair<String, String>> {
        val api = APIHolder.getApiFromNameNull(ad)
            ?: return listOf("✖ $ad yüklü değil" to "Bu eklenti uygulamada bulunamadı (yüklü değil ya da devre dışı).")

        val baslangicZamani = Date()
        val sonuc  = mutableListOf<Pair<String, String>>()
        val adimlar = StringBuilder()

        // 1) Ana sayfa (ilk kategori)
        val kat = api.mainPage.firstOrNull()
        var hedef: SearchResponse? = null
        if (kat != null) {
            val r = runCatching { withTimeout(40_000) { api.getMainPage(1, MainPageRequest(kat.name, kat.data, kat.horizontalImages)) } }
            val liste = r.getOrNull()?.items?.flatMap { it.list }.orEmpty()
            hedef = liste.firstOrNull { it.type == TvType.Movie } ?: liste.firstOrNull()
            adimlar.append("1) Ana sayfa '${kat.name}': ${liste.size} öğe").append(r.exceptionOrNull()?.let { "\n   ${hataMetni(it)}" } ?: "").append('\n')
        }

        // 2) Ayrıntı
        var veri: String? = null
        val secilen = hedef
        if (secilen != null) {
            val y  = runCatching { withTimeout(40_000) { api.load(secilen.url) } }
            val yy = y.getOrNull()
            veri = when (yy) {
                is MovieLoadResponse      -> yy.dataUrl
                is TvSeriesLoadResponse   -> yy.episodes.firstOrNull()?.data
                is AnimeLoadResponse      -> yy.episodes.values.flatten().firstOrNull()?.data
                is LiveStreamLoadResponse -> yy.dataUrl
                else                      -> null
            }
            adimlar.append("2) Ayrıntı '${secilen.name.take(40)}': ${yy?.type ?: "YOK"}, veri=${veri?.take(90) ?: "YOK"}")
                .append(y.exceptionOrNull()?.let { "\n   ${hataMetni(it)}" } ?: "").append('\n')
        }

        // 3) loadLinks
        val linkler = mutableListOf<ExtractorLink>()
        val altyazi = mutableListOf<SubtitleFile>()
        var linkHata: Throwable? = null
        var donus: Boolean? = null
        val veriSabit = veri
        if (veriSabit != null) {
            val bas = System.currentTimeMillis()
            val ok = runCatching { withTimeout(70_000) { api.loadLinks(veriSabit, false, { altyazi.add(it) }, { linkler.add(it) }) } }
            donus    = ok.getOrNull()
            linkHata = ok.exceptionOrNull()
            adimlar.append("3) loadLinks (${System.currentTimeMillis() - bas} ms): dönüş=$donus, ${linkler.size} bağlantı, ${altyazi.size} altyazı")
                .append(linkHata?.let { "\n   ${hataMetni(it)}" } ?: "").append('\n')
        }

        // 4) Bağlantıların kendisi açılıyor mu?
        for ((i, l) in linkler.take(4).withIndex()) {
            val dene = runCatching {
                val y = app.get(l.url, referer = l.referer.ifBlank { null }, headers = l.headers + mapOf("Range" to "bytes=0-2000"), timeout = 20L)
                "${y.code} ${y.headers["content-type"] ?: ""} '${y.text.take(40).replace("\n", " ")}'"
            }.getOrElse { "HATA ${it.javaClass.simpleName}: ${it.message?.take(120)}" }
            adimlar.append("4.${i + 1}) ${l.name} [${l.type}] kalite=${l.quality}\n   ${l.url.take(110)}\n   ref=${l.referer.take(60)} başlık=${l.headers.keys}\n   deneme: $dene\n")
        }

        val kayit = logcatSatirlari(baslangicZamani)

        val hataSabit = linkHata
        val ozet = when {
            veriSabit == null -> "✖ $ad: ayrıntı/veri yok"
            hataSabit != null -> "✖ $ad: HATA ${hataSabit.javaClass.simpleName}"
            linkler.isEmpty() -> "✖ $ad: 0 bağlantı"
            else              -> "✔ $ad: ${linkler.size} bağlantı"
        }
        sonuc.add(ozet to adimlar.toString())
        sonuc.add("Kayıt ($ad, ${kayit.size} satır)" to (if (kayit.isEmpty()) "Bu test sırasında ilgili logcat satırı yok." else kayit.joinToString("\n")))
        hataSabit?.let { sonuc.add("Hata yığını ($ad)" to it.stackTraceToString().lines().take(30).joinToString("\n")) }
        return sonuc
    }

    private fun hataMetni(t: Throwable): String {
        val sb = StringBuilder("${t.javaClass.name}: ${t.message?.take(200)}")
        var neden = t.cause
        var derinlik = 0
        while (neden != null && derinlik < 3) {
            sb.append("\n   neden: ${neden.javaClass.name}: ${neden.message?.take(160)}")
            neden = neden.cause; derinlik++
        }
        return sb.toString()
    }

    /** Uygulamanın kendi logcat'inden test başladığından beri yazılmış, ilgili (eklenti etiketli ya da hata) satırlar. */
    private fun logcatSatirlari(baslangic: Date): List<String> = runCatching {
        val zaman  = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(baslangic)
        val surec  = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-T", zaman))
        val satirlar = BufferedReader(InputStreamReader(surec.inputStream)).use { it.readLines() }
        satirlar.filter { ETIKETLER.containsMatchIn(it) }
            .filterNot { it.contains("DynamiteModule") || it.contains("cast.framework") }
            .map { it.substringAfter(' ').take(220) }
            .takeLast(45)
    }.getOrElse { listOf("logcat okunamadı: ${it.javaClass.simpleName}: ${it.message}") }
}

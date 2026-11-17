package com.neowatch

import android.content.Context
import android.os.Build
import android.preference.PreferenceManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    // Kategoriler aynı anda çalışır; uygulamanın ana sayfa zaman aşımı (varsayılan 120 sn) uzatılır
    override var sequentialMainPage   = false
    override val getMainPageTimeoutMs: Long = 300_000L

    companion object {
        var baglam: Context? = null
        private val raporlar = ConcurrentHashMap<String, String>()

        private val EKLENTILER = listOf(
            "SetFilmIzle", "RecTV", "FullHDFilm", "DiziKorea", "SelcukFlix",
            "DiziPalGuncel", "HDFilmCehennemi", "FilmMakinesi", "FullHDFilmizlesene"
        )

        // InatBox alanları bilerek yok (Cloudflare IP yasağı riski)
        // Kök sayfası tarayıcıya da 403 veren oynatıcı sunucuları (setplay, dplayer82, pichive) burada yok;
        // onlar eklenti testlerindeki gerçek bağlantı zinciriyle sınanıyor.
        private val SUNUCULAR = listOf(
            "https://www.google.com/", "https://selcukflix.app/", "https://dizipal1595.com/", "https://www.setfilmizle.ltd/",
            "https://www.hdfilmcehennemi.nl/", "https://filmmakinesi.to/", "https://www.fullhdfilmizlesene.now/",
            "https://hdfilm.us/", "https://superfilmizle.icu/", "https://sezonlukdizi.cc/", "https://www.dizibox.live/"
        )

        // Sistem testinde engellenen adresler (Cloudflare denemesi kullanır)
        private val engelliler = java.util.concurrent.ConcurrentLinkedQueue<String>()

        // Kategoriler aynı anda çalıştığı için kayıt satırları eklentinin kendi etiketlerine göre ayrılır
        private val ETIKET_HARITASI = mapOf(
            "SetFilmIzle"        to listOf("/STF", "NeO_StfKopru"),
            "RecTV"              to listOf("/RCTV", "RecTV"),
            "FullHDFilm"         to listOf("/FHDF", "NeO_FirePlayer"),
            "DiziKorea"          to listOf("/DZK", "NeO_VidMoly"),
            "SelcukFlix"         to listOf("/SCF", "NeO_ContentX", "pichive"),
            "DiziPalGuncel"      to listOf("/DZPG", "NeO_ContentX", "dplayer"),
            "HDFilmCehennemi"    to listOf("/HDCH", "NeO_CloseLoad", "NeO_Rapidrame", "NeO_Rapid", "NeO_GizliJs", "WebViewResolver", "hdfilmcehennemi"),
            "FilmMakinesi"       to listOf("/FLMM", "NeO_CloseLoad", "NeO_Rapid", "NeO_GizliJs", "filmmakinesi"),
            "FullHDFilmizlesene" to listOf("/FHD", "NeO_RapidVid", "rapidvid")
        )

        private val ETIKETLER = Regex(
            """NeO_|/(?:STF|SCF|DZPG|HDCH|FLMM|FHD|FHDF|SFG|SZD|RTV|RecTV|DKR)\b|Exception|Error|Throwable|FATAL|CloudStream|APIRepository|loadLinks""",
            RegexOption.IGNORE_CASE
        )
    }

    override val mainPage = mainPageOf(
        "sistem" to "Sistem ve ağ",
        *EKLENTILER.map { it to "Eklenti: $it" }.toTypedArray(),
        "cloudflare" to "Cloudflare denemesi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val satirlar = mutableListOf<Pair<String, String>>()
        val sinir = when (request.data) { "sistem" -> 70_000L; "cloudflare" -> 150_000L; else -> 160_000L }
        val bitti = runCatching {
            withTimeoutOrNull(sinir) {
                when (request.data) {
                    "sistem"     -> sistemTesti(satirlar)
                    "cloudflare" -> cloudflareTesti(satirlar)
                    else         -> eklentiTesti(request.data, satirlar)
                }
                true
            }
        }.getOrElse { satirlar.add("✖ test çöktü" to "Teşhis testinin kendisi hata verdi:\n${hataMetni(it)}"); true }
        if (bitti == null) satirlar.add("⏱ zaman aşımı" to "Bu test ${sinir / 1000} saniyede bitmedi; buraya kadarki sonuçlar yukarıda.")

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

    private suspend fun sistemTesti(sonuc: MutableList<Pair<String, String>>) {
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

        for (adres in SUNUCULAR) {
            val sunucu    = adres.substringAfter("://").substringBefore("/")
            val sistemDns = runCatching { InetAddress.getAllByName(sunucu).joinToString { it.hostAddress ?: "?" } }
                .getOrElse { "HATA ${it.javaClass.simpleName}: ${it.message}" }
            val (ilk, engel) = istek(adres, emptyMap(), null)
            if (engel) engelliler.add(adres)
            sonuc.add("${if (engel) "✖" else "✔"} $sunucu ${ilk.substringBefore(" ")}".take(60) to "Adres: $adres\nSistem DNS: $sistemDns\nUygulama isteği: $ilk")
        }
    }

    /** Sistem testinde engellenen siteler (yoksa filmmakinesi) için farklı istek yöntemleri: hangisinin geçtiği çözümü belirler. */
    private suspend fun cloudflareTesti(sonuc: MutableList<Pair<String, String>>) {
        // Sistem testi aynı anda çalıştığı için kısa bir süre onun bulduğu engelleri bekle
        repeat(20) { if (engelliler.isEmpty()) kotlinx.coroutines.delay(1_000) }
        val hedefler = (engelliler.toList().ifEmpty { listOf("https://filmmakinesi.to/") }).distinct().take(3)
        val webviewUa = runCatching { baglam?.let { android.webkit.WebSettings.getDefaultUserAgent(it) } }.getOrNull()

        for (adres in hedefler) {
            val sunucu  = adres.substringAfter("://").substringBefore("/")
            val ayrinti = StringBuilder("Adres: $adres")
            var gecen: String? = null
            val denemeler = listOf(
                "Varsayılan" to emptyMap<String, String>(),
                "Dürüst UA (okhttp)" to mapOf("User-Agent" to "okhttp/4.12.0"),
                "WebView UA + dil" to mapOf(
                    "User-Agent" to (webviewUa ?: "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"),
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                )
            )
            for ((ad, basliklar) in denemeler) {
                val (metin, engel) = istek(adres, basliklar, null)
                ayrinti.append("\n$ad: $metin")
                if (!engel && gecen == null) gecen = ad
            }
            // CloudStream'in yerleşik Cloudflare çözücüsü (gizli WebView); süre sınırlı
            val cf = withTimeoutOrNull(45_000) { istek(adres, emptyMap(), com.lagradost.cloudstream3.network.CloudflareKiller()) }
            ayrinti.append("\nCloudflareKiller: ${cf?.first ?: "45 sn'de bitmedi"}")
            if (cf != null && !cf.second && gecen == null) gecen = "CloudflareKiller"
            ayrinti.append("\nSonuç: ${gecen?.let { "$it ile geçiyor" } ?: "hiçbir yöntem geçmedi"}")
            sonuc.add("${if (gecen != null) "◐" else "✖"} $sunucu: ${gecen ?: "geçilemedi"}".take(60) to ayrinti.toString())
        }
    }

    private suspend fun eklentiTesti(ad: String, sonuc: MutableList<Pair<String, String>>) {
        val api = APIHolder.getApiFromNameNull(ad)
        if (api == null) { sonuc.add("✖ $ad yüklü değil" to "Bu eklenti uygulamada bulunamadı (yüklü değil ya da devre dışı)."); return }

        val baslangicZamani = Date()
        val adimlar = StringBuilder()

        // 1) Ana sayfa (ilk kategori)
        val kat = api.mainPage.firstOrNull()
        var hedef: SearchResponse? = null
        if (kat != null) {
            val r = runCatching { withTimeout(30_000) { api.getMainPage(1, MainPageRequest(kat.name, kat.data, kat.horizontalImages)) } }
            val liste = r.getOrNull()?.items?.flatMap { it.list }.orEmpty()
            hedef = liste.firstOrNull { it.type == TvType.Movie } ?: liste.firstOrNull()
            adimlar.append("1) Ana sayfa '${kat.name}': ${liste.size} öğe").append(r.exceptionOrNull()?.let { "\n   ${hataMetni(it)}" } ?: "").append('\n')
        }

        // 2) Ayrıntı
        var veri: String? = null
        val secilen = hedef
        if (secilen != null) {
            val y  = runCatching { withTimeout(30_000) { api.load(secilen.url) } }
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
        for ((i, l) in linkler.take(3).withIndex()) {
            val dene = runCatching {
                val y = app.get(l.url, referer = l.referer.ifBlank { null }, headers = l.headers + mapOf("Range" to "bytes=0-2000"), timeout = 8L)
                "${y.code} ${y.headers["content-type"] ?: ""} '${y.text.take(40).replace("\n", " ")}'"
            }.getOrElse { "HATA ${it.javaClass.simpleName}: ${it.message?.take(120)}" }
            adimlar.append("4.${i + 1}) ${l.name} [${l.type}] kalite=${l.quality}\n   ${l.url.take(110)}\n   ref=${l.referer.take(60)} başlık=${l.headers.keys}\n   deneme: $dene\n")
        }

        val kayit = logcatSatirlari(baslangicZamani, ETIKET_HARITASI[ad].orEmpty())

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
    }

    /** (özet, engelli mi) — engel: HTTP 403/503/429, Cloudflare sayfası ya da hata */
    private suspend fun istek(adres: String, basliklar: Map<String, String>, kesici: okhttp3.Interceptor?): Pair<String, Boolean> {
        val bas = System.currentTimeMillis()
        return runCatching {
            val y = if (kesici != null) app.get(adres, headers = basliklar, interceptor = kesici, timeout = 40L)
                    else app.get(adres, headers = basliklar, timeout = 15L)
            val metin  = y.text
            val baslik = Regex("""<title[^>]*>([^<]{0,70})""", RegexOption.IGNORE_CASE).find(metin)?.groupValues?.get(1)?.trim().orEmpty()
            val cfKod  = Regex("""(?:error code:?|Error|cf-error-code">)\s*(10\d\d|20\d\d)""", RegexOption.IGNORE_CASE).find(metin)?.groupValues?.get(1)
            val cf     = metin.contains("Attention Required") || metin.contains("Just a moment") || metin.contains("cf-error-details") ||
                         metin.contains("you have been blocked") || y.headers["cf-mitigated"] != null
            val engel  = y.code == 403 || y.code == 503 || y.code == 429 || cf
            val ek     = listOfNotNull(cfKod?.let { "CF hata $it" }, y.headers["cf-mitigated"]?.let { "cf-mitigated=$it" }, y.headers["server"]?.let { "server=$it" })
            "${y.code} '$baslik' ${ek.joinToString(" ")} (${System.currentTimeMillis() - bas} ms)" to engel
        }.getOrElse { "HATA ${it.javaClass.simpleName}: ${it.message?.take(120)} (${System.currentTimeMillis() - bas} ms)" to true }
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
    private fun logcatSatirlari(baslangic: Date, etiketler: List<String>): List<String> = runCatching {
        val zaman  = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(baslangic)
        val surec  = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-T", zaman))
        val satirlar = BufferedReader(InputStreamReader(surec.inputStream)).use { it.readLines() }
        satirlar.filter { satir ->
            etiketler.any { satir.contains(it, ignoreCase = true) } ||
                (etiketler.isEmpty() && ETIKETLER.containsMatchIn(satir)) ||
                (satir.contains("Exception") && !satir.contains("DynamiteModule") && !satir.contains("cast.framework"))
        }
            .map { it.substringAfter(' ').take(220) }
            .takeLast(45)
    }.getOrElse { listOf("logcat okunamadı: ${it.javaClass.simpleName}: ${it.message}") }
}

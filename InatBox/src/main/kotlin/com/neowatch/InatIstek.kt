package com.neowatch

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * İnatBox sunucularıyla konuşma katmanı.
 *
 * Güncel protokol (2026):
 *  - Gövde: "1=<rastgele16>&0=<rastgele16>" (POST)
 *  - İmza: X-Sg = HMAC-SHA256(imza anahtarı, "METOT\nYOL\nX-Ts\nX-Nc\nsha256(gövde)")
 *  - Yanıt: iki katman AES-CBC, her katman "şifreli_b64:iv_b64" biçiminde; anahtar istekte gönderilen rastgele değer.
 *  - Sunucu saati farklıysa 403 + "x-st" başlığı döner; saat farkı düzeltilip tekrar denenir.
 *
 * Kategori adresleri uygulamanın kendi uzak ayarından (mtlshash/cert → DC10/DC2) okunur,
 * böylece alan adı değişince eklentinin güncellenmesi gerekmez.
 */
object InatIstek {
    const val VARSAYILAN_ANAHTAR = "ywevqtjrurkwtqgz"
    private const val IMZA_ANAHTARI = "x7kkk0qmqz63kj68tla5i7u26192v7zqnnddhjgm"

    private val AYAR_ADRESLERI = listOf(
        "https://raw.githubusercontent.com/mtlshash/cert/main/hash",
        "https://cdn.jsdelivr.net/gh/mtlshash/cert@main/hash"
    )
    private const val YEDEK_DIZIN = "https://static.staticsave.com/fast/ctls.js"
    private const val KATEGORI_OMRU_MS = 30 * 60 * 1000L
    private const val HATA_OMRU_MS     = 5 * 60 * 1000L

    private val rastgele = SecureRandom()

    @Volatile private var zamanFarki      = 0L
    @Volatile private var kategoriler: Map<String, String>? = null
    @Volatile private var kategoriZamani  = 0L
    private val kategoriKilidi = Mutex()

    // ---------------------------------------------------------------- istek

    private val istekKilidi = Mutex()
    @Volatile private var sonIstekZamani = 0L
    private const val ISTEK_ARALIGI_MS = 300L

    /**
     * İçerik adresine uygulamanın yaptığı gibi tek bir imzalı POST atar ve çözülmüş JSON'u döndürür.
     * İstekler sırayla ve aralıklı gönderilir; farklı biçimlerde tekrar denenmez
     * (sunucu, uygulamaya benzemeyen istekleri IP yasağıyla cezalandırabiliyor).
     */
    suspend fun istek(url: String, ekAnahtar: String? = null): String? =
        runCatching { imzali(url, ekAnahtar) }
            .onFailure { Log.w("InatBox", "istek hatası » $url » ${it.message}") }
            .getOrNull()

    private suspend fun imzali(url: String, ekAnahtar: String?): String? {
        val anahtar = rastgeleMetin(16)
        val govde   = "1=$anahtar&0=$anahtar"
        val yol     = runCatching { URI(url).rawPath }.getOrNull()?.ifEmpty { "/" } ?: "/"

        suspend fun gonder() = istekKilidi.withLock {
            val bekle = ISTEK_ARALIGI_MS - (System.currentTimeMillis() - sonIstekZamani)
            if (bekle > 0) delay(bekle)
            sonIstekZamani = System.currentTimeMillis()

            val zaman = (System.currentTimeMillis() / 1000L + zamanFarki).toString()
            val nonce = hex(ByteArray(16).also { rastgele.nextBytes(it) })
            val imza  = hmacHex("POST\n$yol\n$zaman\n$nonce\n${sha256Hex(govde)}")

            app.post(
                url,
                headers     = mapOf(
                    "User-Agent"       to "speedrestapi",
                    "X-Requested-With" to "com.bp.box",
                    "Referer"          to "https://speedrestapi.com/",
                    "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Cache-Control"    to "no-cache",
                    "X-Ts"             to zaman,
                    "X-Nc"             to nonce,
                    "X-Sg"             to imza
                ),
                requestBody = govde.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
            )
        }

        var yanit = gonder()

        // Saat farkı: sunucu 403 ile birlikte kendi saatini (x-st) bildirir; bir kez düzeltip tekrar dene
        val sunucuZamani = yanit.headers["x-st"]?.toLongOrNull()
        if (yanit.code == 403 && sunucuZamani != null && sunucuZamani > 0) {
            zamanFarki = sunucuZamani - System.currentTimeMillis() / 1000L
            yanit      = gonder()
        }

        if (!yanit.isSuccessful) {
            Log.w("InatBox", "istek ${yanit.code} » $url")
            return null
        }

        val cozulmus = coz(yanit.text, listOfNotNull(anahtar, ekAnahtar, VARSAYILAN_ANAHTAR))
        if (cozulmus == null) Log.w("InatBox", "yanıt çözülemedi (${yanit.text.take(60)}) » $url")
        return cozulmus
    }

    // ---------------------------------------------------------------- çözme

    /** Katmanlı AES-CBC şifrelemeyi çözer; JSON bulunursa döndürür. */
    fun coz(metin: String?, anahtarlar: List<String> = listOf(VARSAYILAN_ANAHTAR)): String? {
        var veri = metin?.replace("-----BEGIN CERTIFICATE-----", "")?.replace("-----END CERTIFICATE-----", "")?.trim() ?: return null

        repeat(5) {
            jsonAyikla(veri)?.let { return it }
            veri = katmanCoz(veri, anahtarlar) ?: return null
        }

        return jsonAyikla(veri)
    }

    private fun katmanCoz(veri: String, anahtarlar: List<String>): String? {
        val ayrac  = veri.indexOf(':')
        val sifre  = (if (ayrac >= 0) veri.substring(0, ayrac) else veri).trim()
        val ek     = if (ayrac >= 0) veri.substring(ayrac + 1).trim() else ""
        val sifreB = runCatching { base64DecodeArray(sifre) }.getOrNull() ?: return null
        val ekB    = if (ek.isNotEmpty()) runCatching { base64DecodeArray(ek) }.getOrNull() else null

        val denemeler = mutableListOf<Pair<ByteArray, ByteArray>>()
        for (a in anahtarlar) {
            val ab = anahtarBaytlari(a)
            if (ekB != null && ekB.size == 16) denemeler.add(ab to ekB)   // anahtar + gelen IV
            denemeler.add(ab to ab.copyOf(16))                             // anahtar = IV
        }
        if (ekB != null && ekB.size in setOf(16, 24, 32)) denemeler.add(ekB to ekB.copyOf(16)) // ek kısım anahtar
        if (ek.length in setOf(16, 24, 32)) {
            val eb = ek.toByteArray(Charsets.UTF_8)
            denemeler.add(eb to eb.copyOf(16))
        }

        for ((anahtar, iv) in denemeler) {
            val sonuc = runCatching {
                val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(anahtar, "AES"), IvParameterSpec(iv))
                String(c.doFinal(sifreB), Charsets.UTF_8).trim()
            }.getOrNull() ?: continue

            if (makulMu(sonuc)) return sonuc
        }

        return null
    }

    /** Doğru anahtarla çözülen veri ya JSON ya da yeni bir base64 katmanı olmalı. */
    private fun makulMu(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.startsWith("[") || s.startsWith("{")) return true
        return s.length >= 16 && s.all { it.isLetterOrDigit() || it in "+/=:-_\r\n" }
    }

    private fun jsonAyikla(veri: String): String? {
        val s = veri.trim()
        if (!(s.startsWith("[") || s.startsWith("{"))) return null
        val son = maxOf(s.lastIndexOf(']'), s.lastIndexOf('}'))
        return if (son > 0) s.substring(0, son + 1) else s
    }

    private fun anahtarBaytlari(anahtar: String): ByteArray {
        val b = anahtar.toByteArray(Charsets.UTF_8)
        return when {
            b.size in setOf(16, 24, 32) -> b
            b.size < 16                 -> b.copyOf(16)
            b.size < 24                 -> b.copyOf(16)
            b.size < 32                 -> b.copyOf(24)
            else                        -> b.copyOf(32)
        }
    }

    // ---------------------------------------------------------------- kategoriler

    /** Kategori adına göre güncel adresi döndürür; bulunamazsa [varsayilan]. */
    suspend fun kategoriAdresi(ad: String, varsayilan: String): String {
        return kategoriHaritasi()[sadelestir(ad)] ?: varsayilan
    }

    suspend fun kategoriHaritasi(): Map<String, String> = kategoriKilidi.withLock {
        val simdi = System.currentTimeMillis()
        kategoriler?.let { onbellek ->
            val omur = if (onbellek.isEmpty()) HATA_OMRU_MS else KATEGORI_OMRU_MS
            if (simdi - kategoriZamani < omur) return@withLock onbellek
        }

        val harita = kategoriDiziniGetir()
        when {
            harita.isNotEmpty() -> { kategoriler = harita; kategoriZamani = simdi }
            kategoriler == null -> { kategoriler = emptyMap(); kategoriZamani = simdi }
            // eski liste korunur, HATA_OMRU_MS sonra yeniden denenir
            else                -> kategoriZamani = simdi - KATEGORI_OMRU_MS + HATA_OMRU_MS
        }
        kategoriler.orEmpty()
    }

    private suspend fun kategoriDiziniGetir(): Map<String, String> {
        val adaylar = mutableListOf<String>()
        for (adres in AYAR_ADRESLERI) {
            val ayar = runCatching { coz(app.get(adres, timeout = 15).text)?.let { JSONObject(it) } }.getOrNull() ?: continue
            // DC2: uygulamanın canlı kategori ucu (imzalı istek), DC10: statik yedek liste
            listOf("DC2", "DC10").mapNotNull { ayar.optString(it).takeIf { u -> u.startsWith("http") } }.forEach { adaylar.add(it) }
            if (adaylar.isNotEmpty()) break
        }
        if (YEDEK_DIZIN !in adaylar) adaylar.add(YEDEK_DIZIN)

        for (aday in adaylar) {
            val ham = runCatching {
                if (aday.substringBefore("?").endsWith(".php")) istek(aday) else coz(app.get(aday, timeout = 15).text)
            }.getOrNull() ?: continue

            val dizi   = runCatching { JSONArray(ham) }.getOrNull() ?: continue
            val harita = mutableMapOf<String, String>()
            for (i in 0 until dizi.length()) {
                val k   = dizi.optJSONObject(i) ?: continue
                val url = k.optString("catUrl")
                if (!url.startsWith("http")) continue
                harita[sadelestir(k.optString("catName"))] = url
            }

            if (harita.isNotEmpty()) {
                Log.d("InatBox", "kategori dizini » $aday (${harita.size})")
                return harita
            }
        }

        return emptyMap()
    }

    private fun sadelestir(ad: String): String =
        ad.lowercase(Locale.forLanguageTag("tr")).filter { it.isLetterOrDigit() }

    // ---------------------------------------------------------------- yardımcılar

    private fun rastgeleMetin(uzunluk: Int): String {
        val harfler = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString(uzunluk) { repeat(uzunluk) { append(harfler[rastgele.nextInt(harfler.length)]) } }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun sha256Hex(metin: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(metin.toByteArray(Charsets.UTF_8)))

    private fun hmacHex(mesaj: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(IMZA_ANAHTARI.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hex(mac.doFinal(mesaj.toByteArray(Charsets.UTF_8)))
    }
}

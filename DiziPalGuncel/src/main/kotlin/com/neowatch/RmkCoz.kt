package com.neowatch

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.base64DecodeArray
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sayfadaki `[data-rm-k]` içindeki {"ciphertext","iv","salt"} verisini çözer.
 * CryptoJS: PBKDF2(SHA512, 999 tur, 256 bit anahtar) + AES-CBC.
 */
object RmkCoz {
    // Sitenin app-dizipals.js dosyasındaki parola (site güncellenirse değişebilir)
    const val PAROLA = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

    data class RmkVeri(
        @JsonProperty("ciphertext") val ciphertext: String? = null,
        @JsonProperty("iv")         val iv: String?         = null,
        @JsonProperty("salt")       val salt: String?       = null
    )

    fun coz(json: String, parola: String = PAROLA): String? {
        val veri = jsonOku<RmkVeri>(json) ?: return null

        return runCatching {
            val salt = hexToBytes(veri.salt ?: return null)
            val iv   = hexToBytes(veri.iv ?: return null)
            val ct   = base64DecodeArray(veri.ciphertext ?: return null)
            val key  = pbkdf2Sha512(parola.toByteArray(Charsets.UTF_8), salt, 999, 32)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val temiz = hex.trim()
        return ByteArray(temiz.length / 2) { temiz.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Eski Android sürümlerinde PBKDF2WithHmacSHA512 olmadığı için elle hesaplanır. */
    private fun pbkdf2Sha512(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))

        val sonuc = ByteArray(keyLength)
        var blok  = 1
        var konum = 0

        while (konum < keyLength) {
            mac.update(salt)
            mac.update(byteArrayOf((blok ushr 24).toByte(), (blok ushr 16).toByte(), (blok ushr 8).toByte(), blok.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()

            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }

            val kopya = minOf(t.size, keyLength - konum)
            System.arraycopy(t, 0, sonuc, konum, kopya)
            konum += kopya
            blok++
        }

        return sonuc
    }
}

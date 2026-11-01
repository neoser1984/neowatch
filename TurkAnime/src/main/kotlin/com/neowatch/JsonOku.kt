package com.neowatch

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.mapper

// CloudStream'in AppUtils.tryParseJson / parseJson fonksiyonları artık JVM 11 hedefiyle derleniyor
// ve JVM 1.8 hedefli eklentilerin içine satır içi (inline) alınamıyor. Aynı işi yapan yerel karşılıklar:

/** JSON metnini [T] tipine çevirir; hata olursa null döner (AppUtils.tryParseJson karşılığı). */
internal inline fun <reified T> jsonOku(metin: String?): T? {
    return try {
        mapper.readValue(metin ?: return null, object : TypeReference<T>() {})
    } catch (_: Exception) {
        null
    }
}

/** JSON metnini [T] tipine çevirir; hata olursa istisna fırlatır (AppUtils.parseJson karşılığı). */
internal inline fun <reified T> jsonCevir(metin: String): T =
    mapper.readValue(metin, object : TypeReference<T>() {})

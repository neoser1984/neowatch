package com.neowatch

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecItem(
    @JsonProperty("id")          val id: Int,
    @JsonProperty("type")        val type: String?           = null,
    @JsonProperty("title")       val title: String,
    @JsonProperty("label")       val label: String?          = null,
    @JsonProperty("sublabel")    val sublabel: String?       = null,
    @JsonProperty("description") val description: String?    = null,
    @JsonProperty("year")        val year: Int?              = null,
    @JsonProperty("imdb")        val imdb: Double?           = null,
    @JsonProperty("rating")      val rating: Float?          = null,
    @JsonProperty("duration")    val duration: String?       = null,
    @JsonProperty("image")       val image: String?          = null,
    @JsonProperty("genres")      val genres: List<Genre>?    = null,
    @JsonProperty("trailer")     val trailer: Trailer?       = null,
    @JsonProperty("sources")     val sources: List<Source>   = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Genre(
    @JsonProperty("id")    val id: Int,
    @JsonProperty("title") val title: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Trailer(
    @JsonProperty("id")    val id: Int?     = null,
    @JsonProperty("type")  val type: String? = null,
    @JsonProperty("url")   val url: String?  = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Source(
    @JsonProperty("id")      val id: Int?        = null,
    @JsonProperty("title")   val title: String?  = null,
    @JsonProperty("type")    val type: String?   = null,
    @JsonProperty("url")     val url: String?    = null,
    @JsonProperty("enc_url") val encUrl: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecSearch(
    @JsonProperty("channels") val channels: List<RecItem>? = emptyList(),
    @JsonProperty("posters")  val posters: List<RecItem>?  = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecDizi(
    @JsonProperty("id")       val id: Int,
    @JsonProperty("title")    val title: String,
    @JsonProperty("episodes") val episodes: List<RecEpisode> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecEpisode(
    @JsonProperty("id")       val id: Int,
    @JsonProperty("title")    val title: String,
    @JsonProperty("sources")  val sources: List<Source> = emptyList()
)

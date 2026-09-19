package com.uchiharepo.netmirrortv

import com.fasterxml.jackson.annotation.JsonProperty

data class NetMirrorTokenResponse(
    @JsonProperty("token_hash") val token_hash: String? = null,
    @JsonProperty("doms") val doms: String? = null,
    @JsonProperty("var") val version: String? = null
)

data class NetMirrorCatalogResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("data") val data: List<NetMirrorCatalogSection>? = null
)

data class NetMirrorCatalogSection(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("items") val items: List<NetMirrorCatalogItem>? = null
)

data class NetMirrorCatalogItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("rating") val rating: String? = null
)

data class NetMirrorSearchResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("data") val data: List<NetMirrorSearchItem>? = null
)

data class NetMirrorSearchItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("year") val year: String? = null
)

data class NetMirrorPostResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("data") val data: NetMirrorPostData? = null
)

data class NetMirrorPostData(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("player_id") val playerId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("desc") val desc: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("seasons") val seasons: List<NetMirrorSeason>? = null
)

data class NetMirrorSeason(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("name") val name: String? = null
)

data class NetMirrorEpisodeResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("total_pages") val totalPages: Int? = null,
    @JsonProperty("data") val data: List<NetMirrorEpisodeItem>? = null
)

data class NetMirrorEpisodeItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("thumb") val thumb: String? = null,
    @JsonProperty("desc") val desc: String? = null
)

data class NetMirrorPlayerResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("data") val data: NetMirrorPlayerData? = null
)

data class NetMirrorPlayerData(
    @JsonProperty("stream_url") val streamUrl: String? = null,
    @JsonProperty("hls") val hls: String? = null,
    @JsonProperty("mp4") val mp4: String? = null,
    @JsonProperty("servers") val servers: List<NetMirrorServer>? = null,
    @JsonProperty("subtitles") val subtitles: List<NetMirrorSubtitle>? = null
)

data class NetMirrorServer(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null
)

data class NetMirrorSubtitle(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("file") val file: String? = null
)

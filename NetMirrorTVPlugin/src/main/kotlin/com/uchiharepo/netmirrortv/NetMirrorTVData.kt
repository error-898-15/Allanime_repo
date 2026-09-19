package com.uchiharepo.netmirrortv

import com.fasterxml.jackson.annotation.JsonProperty

data class NetMirrorSearchData(
    @JsonProperty("head") val head: String? = null,
    @JsonProperty("searchResult") val searchResult: List<NetMirrorSearchResult>? = null,
    @JsonProperty("nextPage") val nextPage: Int? = null
)

data class NetMirrorSearchResult(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("t") val title: String? = null,
    @JsonProperty("i") val image: String? = null,
    @JsonProperty("type") val type: String? = null
)

data class NetMirrorEpisodesData(
    @JsonProperty("episodes") val episodes: List<NetMirrorEpItem>? = null,
    @JsonProperty("nextPage") val nextPage: Int? = null,
    @JsonProperty("nextPageSeason") val nextPageSeason: String? = null
)

data class NetMirrorEpItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("ep") val ep: String? = null,
    @JsonProperty("t") val title: String? = null,
    @JsonProperty("img") val image: String? = null
)

data class NetMirrorPlayList(
    @JsonProperty("sources") val sources: List<NetMirrorStreamSource>? = null,
    @JsonProperty("tracks") val tracks: List<NetMirrorSubTrack>? = null
)

data class NetMirrorStreamSource(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("type") val type: String? = null
)

data class NetMirrorSubTrack(
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("language") val language: String? = null
)

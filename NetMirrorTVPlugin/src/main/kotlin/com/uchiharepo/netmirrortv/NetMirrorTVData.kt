package com.uchiharepo.netmirrortv

import com.fasterxml.jackson.annotation.JsonProperty

data class NetMirrorSearchData(
    @JsonProperty("searchResult") val searchResult: List<NetMirrorSearchResult>? = null
)

data class NetMirrorSearchResult(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("t") val title: String? = null,
    @JsonProperty("img") val image: String? = null
)

data class NewTvTokenResponse(
    @JsonProperty("token_hash") val tokenHash: String? = null
)

data class NewTvPlayerResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("video_link") val videoLink: String? = null,
    @JsonProperty("referer") val referer: String? = null,
    @JsonProperty("title") val title: String? = null
)

data class NewTvPostData(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("desc") val desc: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("ua") val ua: String? = null,
    @JsonProperty("runtime") val runtime: String? = null,
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("episodes") val episodes: List<NewTvEpisode>? = null,
    @JsonProperty("nextPageShow") val nextPageShow: Int? = null,
    @JsonProperty("nextPageSeason") val nextPageSeason: String? = null
)

data class NewTvEpisode(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("t") val title: String? = null,
    @JsonProperty("ep") val ep: String? = null,
    @JsonProperty("ep_desc") val epDesc: String? = null,
    @JsonProperty("info") val info: List<String>? = null
)

data class NetMirrorPlayList(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("sources") val sources: List<NetMirrorSource>? = null,
    @JsonProperty("tracks") val tracks: List<NetMirrorTrack>? = null
)

data class NetMirrorSource(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("type") val type: String? = null
)

data class NetMirrorTrack(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("kind") val kind: String? = null
)

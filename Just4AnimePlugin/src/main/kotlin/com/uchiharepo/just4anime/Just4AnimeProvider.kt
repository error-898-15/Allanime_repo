package com.uchiharepo.just4anime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class Just4AnimeProvider : MainAPI() {
    override var mainUrl = "https://just4anime.online"
    private val apiUrl = "https://api.just4anime.online/api"

    override var name = "Just4Anime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$apiUrl/v1/meta/anilist/trending?page=" to "Trending Now",
        "$apiUrl/v1/meta/anilist/top-airing?page=" to "Top Airing",
        "$apiUrl/v1/meta/anilist/popular?page=" to "Most Popular",
        "$apiUrl/v1/meta/anilist/top-rated?page=" to "Top Rated",
        "$apiUrl/v1/meta/anilist/recent?page=" to "Recently Updated",
        "$apiUrl/v1/meta/anilist/upcoming?page=" to "Upcoming Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}$page&perPage=20"
        val res = app.get(
            url,
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "User-Agent" to USER_AGENT
            )
        )
        val json = parseJson<AnilistPageResponse>(res.text)
        val animeList = json.data?.results?.mapNotNull { item ->
            item.toSearchResponse()
        } ?: emptyList()

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = animeList,
                isHorizontalImages = false
            ),
            hasNext = json.data?.hasNextPage == true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$apiUrl/v1/meta/anilist/search?query=$encoded&page=1&perPage=25"
        val res = app.get(
            url,
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "User-Agent" to USER_AGENT
            )
        )
        val json = parseJson<AnilistPageResponse>(res.text)
        return json.data?.results?.mapNotNull { item ->
            item.toSearchResponse()
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast("/anime/").substringBefore("?").substringBefore("/")

        // Fetch anime metadata
        val episodesUrl = "$apiUrl/v1/meta/anilist/info/$animeId"
        val metaRes = try {
            app.get(
                episodesUrl,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl,
                    "User-Agent" to USER_AGENT
                )
            )
        } catch (e: Exception) {
            null
        }

        val parsedMeta = metaRes?.text?.let {
            try { parseJson<AnilistInfoResponse>(it) } catch (e: Exception) { null }
        }

        val anilist = parsedMeta?.data?.anilist
        val title = anilist?.title?.english
            ?: anilist?.title?.romaji
            ?: anilist?.title?.userPreferred
            ?: "Anime $animeId"

        val posterUrl = anilist?.image ?: anilist?.cover
        val bannerUrl = anilist?.cover ?: anilist?.image
        val description = anilist?.description
        val totalEps = anilist?.totalEpisodes ?: 1

        val episodesList = mutableListOf<Episode>()
        for (i in 1..totalEps) {
            val epData = "$animeId|$i"
            episodesList.add(
                newEpisode(epData) {
                    this.name = "Episode $i"
                    this.episode = i
                    this.posterUrl = posterUrl
                }
            )
        }

        val formatStr = anilist?.format?.uppercase()
        val isMovie = formatStr == "MOVIE" || (totalEps == 1 && formatStr != "TV")
        val tvType = when {
            formatStr == "MOVIE" -> TvType.AnimeMovie
            formatStr in listOf("OVA", "ONA", "SPECIAL") -> TvType.OVA
            else -> TvType.Anime
        }

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, "$animeId|1") {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.plot = description
                this.tags = anilist?.genres
                this.year = anilist?.releaseDate
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType, episodesList) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.plot = description
                this.tags = anilist?.genres
                this.year = anilist?.releaseDate
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val animeId = parts[0]
        val episodeNum = parts.getOrNull(1)?.toIntOrNull() ?: 1

        // 1. Fetch available servers from target API
        val availUrl = "$apiUrl/v1/meta/availability/$animeId/servers"
        val availRes = try {
            app.get(
                availUrl,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl,
                    "User-Agent" to USER_AGENT
                ),
                timeout = 8
            )
        } catch (e: Exception) {
            null
        }

        val parsedAvail = availRes?.text?.let {
            try { parseJson<ServerAvailabilityResponse>(it) } catch (e: Exception) { null }
        }

        // Use servers returned or resilient fallback list
        val serverList = parsedAvail?.data?.servers?.ifEmpty { null }
            ?: listOf(
                ServerItem(code = "rin", displayName = "Rin", types = listOf("sub", "dub")),
                ServerItem(code = "meg", displayName = "Meg", types = listOf("sub", "dub")),
                ServerItem(code = "jin", displayName = "Jin", types = listOf("sub", "dub")),
                ServerItem(code = "chan", displayName = "Chan", types = listOf("sub", "dub")),
                ServerItem(code = "levi", displayName = "Levi", types = listOf("sub")),
                ServerItem(code = "kai", displayName = "Kai", types = listOf("embed", "h-sub")),
                ServerItem(code = "mai", displayName = "Mai", types = listOf("dub")),
                ServerItem(code = "sai", displayName = "Sai", types = listOf("dub")),
                ServerItem(code = "zeke", displayName = "Zeke", types = listOf("dub"))
            )

        var loadedAny = false

        // 2. Fetch streams for each server
        for (server in serverList) {
            val code = server.code ?: continue
            val displayName = server.displayName ?: code.replaceFirstChar { it.uppercase() }
            val providerAnimeId = server.animeId
            val typesToFetch = server.types?.ifEmpty { null } ?: listOf("sub")

            for (type in typesToFetch) {
                val queryParams = mutableListOf(
                    "provider=$code",
                    "num=$episodeNum",
                    "type=${if (type == "embed") "sub" else type}"
                )
                if (!providerAnimeId.isNullOrBlank()) {
                    queryParams.add("providerAnimeId=$providerAnimeId")
                }

                val sourceUrl = "$apiUrl/v1/meta/sources/$animeId?${queryParams.joinToString("&")}"

                try {
                    val sourceRes = app.get(
                        sourceUrl,
                        headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "Origin" to mainUrl,
                            "User-Agent" to USER_AGENT
                        ),
                        timeout = 10
                    )

                    val sourceData = parseJson<SourceResponse>(sourceRes.text)
                    if (sourceData.success == true && sourceData.data != null) {
                        val result = sourceData.data

                        // Subtitle extraction
                        result.subtitles?.forEach { sub ->
                            val subUrl = sub.url ?: return@forEach
                            val lang = sub.lang ?: sub.language ?: "English"
                            subtitleCallback.invoke(SubtitleFile(lang, subUrl))
                        }

                        // Direct streaming sources
                        result.sources?.forEach { src ->
                            val streamUrl = src.url ?: return@forEach
                            val serverLabel = "Just4Anime [$displayName] [${type.uppercase()}]"
                            val isM3u8 = src.isM3U8 == true || streamUrl.contains(".m3u8") || streamUrl.contains("/proxy/e/")
                            val referer = src.headers?.get("referer") ?: "$mainUrl/"

                            if (isM3u8) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = serverLabel,
                                        url = streamUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = referer
                                        this.headers = (src.headers ?: emptyMap()) + mapOf(
                                            "User-Agent" to USER_AGENT,
                                            "Origin" to (src.headers?.get("origin") ?: mainUrl)
                                        )
                                        this.quality = getQualityFromName(src.quality)
                                    }
                                )
                                loadedAny = true

                                try {
                                    M3u8Helper.generateM3u8(
                                        source = this.name,
                                        streamUrl = streamUrl,
                                        referer = referer,
                                        headers = (src.headers ?: emptyMap()) + mapOf("User-Agent" to USER_AGENT),
                                        name = serverLabel
                                    ).forEach { link ->
                                        callback.invoke(link)
                                    }
                                } catch (e: Exception) {
                                    // Primary master link already passed
                                }
                            } else {
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = serverLabel,
                                        url = streamUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = referer
                                        this.headers = (src.headers ?: emptyMap()) + mapOf("User-Agent" to USER_AGENT)
                                        this.quality = getQualityFromName(src.quality)
                                    }
                                )
                                loadedAny = true
                            }
                        }

                        // Embed / Iframe extraction
                        if (!result.iframe.isNullOrBlank()) {
                            try {
                                if (loadExtractor(result.iframe, data, subtitleCallback, callback)) {
                                    loadedAny = true
                                }
                            } catch (e: Exception) {
                                // Ignore extractor failures
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Continue with other available servers
                }
            }
        }

        return loadedAny
    }
}

// ----------------- Helper & Data Models -----------------

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private fun getQualityFromName(quality: String?): Int {
    return when (quality?.lowercase()) {
        "1080p", "1080" -> Qualities.P1080.value
        "720p", "720" -> Qualities.P720.value
        "480p", "480" -> Qualities.P480.value
        "360p", "360" -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

data class AnilistPageResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("data") val data: AnilistPageData? = null
)

data class AnilistPageData(
    @JsonProperty("currentPage") val currentPage: Int? = null,
    @JsonProperty("hasNextPage") val hasNextPage: Boolean? = null,
    @JsonProperty("results") val results: List<AnilistItem>? = null
)

data class AnilistItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("malId") val malId: Int? = null,
    @JsonProperty("title") val title: AnilistTitle? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("cover") val cover: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("releaseDate") val releaseDate: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null
) {
    fun toSearchResponse(): SearchResponse? {
        val animeId = id ?: return null
        val titleStr = title?.english ?: title?.romaji ?: title?.userPreferred ?: title?.native ?: return null
        val tvType = when (format?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        return newAnimeSearchResponse(titleStr, "https://just4anime.online/anime/$animeId", tvType) {
            this.posterUrl = image
        }
    }
}

data class AnilistTitle(
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("english") val english: String? = null,
    @JsonProperty("native") val native: String? = null,
    @JsonProperty("userPreferred") val userPreferred: String? = null
)

data class ServerAvailabilityResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("data") val data: ServerAvailabilityData? = null
)

data class ServerAvailabilityData(
    @JsonProperty("animeId") val animeId: String? = null,
    @JsonProperty("malId") val malId: Int? = null,
    @JsonProperty("servers") val servers: List<ServerItem>? = null
)

data class ServerItem(
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("displayName") val displayName: String? = null,
    @JsonProperty("animeId") val animeId: String? = null,
    @JsonProperty("types") val types: List<String>? = null
)

data class SourceResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("data") val data: SourceData? = null,
    @JsonProperty("error") val error: String? = null
)

data class SourceData(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("sources") val sources: List<StreamSourceItem>? = null,
    @JsonProperty("subtitles") val subtitles: List<SubtitleItem>? = null,
    @JsonProperty("iframe") val iframe: String? = null
)

data class StreamSourceItem(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("isM3U8") val isM3U8: Boolean? = null,
    @JsonProperty("headers") val headers: Map<String, String>? = null
)

data class SubtitleItem(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("format") val format: String? = null
)

data class AnilistInfoResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("data") val data: AnilistInfoData? = null
)

data class AnilistInfoData(
    @JsonProperty("anilist") val anilist: AnilistItem? = null
)

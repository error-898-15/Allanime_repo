package com.uchiharepo.just4anime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class Just4AnimeProvider : MainAPI() {
    override var mainUrl = "https://just4anime.online"
    override var name = "Just4Anime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        const val API_URL = "https://api.just4anime.online/api"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://just4anime.online/",
            "Origin" to "https://just4anime.online"
        )
    }

    override val mainPage = mainPageOf(
        "$API_URL/v1/meta/anilist/trending" to "Trending Now",
        "$API_URL/v1/meta/anilist/popular" to "Most Popular",
        "$API_URL/v1/meta/anilist/advanced-search?sort=[\"FAVOURITES_DESC\"]" to "Top Favorited",
        "$API_URL/v1/meta/anilist/advanced-search?format=MOVIE" to "Anime Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val pageUrl = if (request.data.contains("?")) {
            "${request.data}&page=$page"
        } else {
            "${request.data}?page=$page"
        }

        val res = app.get(pageUrl, headers = HEADERS).text
        val parsed = parseJson<AnilistListResponse>(res)
        val list = parsed.data?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        return newHomePageResponse(request.name, list)
    }

    private fun AnilistAnimeItem.toSearchResponse(): SearchResponse? {
        val animeId = this.id?.toString() ?: return null
        val displayTitle = this.title?.english?.takeIf { it.isNotBlank() }
            ?: this.title?.romaji?.takeIf { it.isNotBlank() }
            ?: this.title?.userPreferred?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = this.image ?: this.cover
        val isMovie = this.type.equals("MOVIE", ignoreCase = true)
        val linkUrl = "$mainUrl/watch/$animeId"

        return if (isMovie) {
            newMovieSearchResponse(displayTitle, linkUrl, TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(displayTitle, linkUrl, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$API_URL/v1/meta/anilist/search?query=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val res = app.get(searchUrl, headers = HEADERS).text
        val parsed = parseJson<AnilistListResponse>(res)
        return parsed.data?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast("/").substringBefore("?").trim()
        if (animeId.isBlank()) return null

        // Fetch rich anime metadata & episodes from the official Next.js backend endpoint
        val episodesApiUrl = "$mainUrl/api/episodes/$animeId"
        val res = app.get(episodesApiUrl, headers = HEADERS).text
        val details = parseJson<Just4AnimeEpisodesResponse>(res).data ?: return null

        val title = details.title?.takeIf { it.isNotBlank() }
            ?: details.titleRomaji?.takeIf { it.isNotBlank() }
            ?: "Anime"

        val posterUrl = details.images?.firstOrNull { it.coverType.equals("Poster", ignoreCase = true) }?.url
            ?: details.images?.firstOrNull()?.url
        val bannerUrl = details.images?.firstOrNull { it.coverType.equals("Banner", ignoreCase = true) || it.coverType.equals("Fanart", ignoreCase = true) }?.url

        val isMovie = details.format.equals("MOVIE", ignoreCase = true)
        val episodesList = details.episodes ?: emptyList()

        if (isMovie && (episodesList.isEmpty() || episodesList.size == 1)) {
            val epNum = episodesList.firstOrNull()?.number ?: 1
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, "$animeId,$epNum") {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.plot = details.description
                this.tags = details.genres
                this.year = details.year
            }
        }

        val episodes = episodesList.map { ep ->
            val epNum = ep.number ?: 1
            val epTitle = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
            Episode(
                data = "$animeId,$epNum",
                name = epTitle,
                season = ep.season ?: 1,
                episode = epNum,
                posterUrl = ep.image,
                description = ep.description,
                rating = ep.rating?.toDoubleOrNull()?.toInt()
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = bannerUrl
            this.plot = details.description
            this.tags = details.genres
            this.year = details.year
            this.showStatus = when (details.status?.lowercase()) {
                "finished", "completed" -> ShowStatus.Completed
                "releasing", "airing" -> ShowStatus.Ongoing
                else -> null
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split(",")
        val animeId = parts.getOrNull(0) ?: return false
        val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1

        var loadedAny = false

        // 1. Discover active streaming servers from server availability endpoint
        val availabilityUrl = "$API_URL/v1/meta/availability/$animeId/servers"
        val availableServers = try {
            val res = app.get(availabilityUrl, headers = HEADERS).text
            parseJson<AvailabilityResponse>(res).data?.servers?.filter { it.hasEpisode != false } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // Fallback default servers if availability discovery fails or empty
        val targetServers = if (availableServers.isNotEmpty()) {
            availableServers
        } else {
            listOf(
                ServerInfo(code = "meg", displayName = "Meg", types = listOf("sub", "dub")),
                ServerInfo(code = "rin", displayName = "Rin", types = listOf("sub", "dub")),
                ServerInfo(code = "chan", displayName = "Chan", types = listOf("sub", "dub")),
                ServerInfo(code = "levi", displayName = "Levi", types = listOf("sub")),
                ServerInfo(code = "jin", displayName = "Jin", types = listOf("sub", "dub"))
            )
        }

        val requestHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        // 2. Query sources for each server and type (Sub / Dub)
        for (server in targetServers) {
            val serverCode = server.code ?: continue
            val types = if (server.types.isNullOrEmpty()) listOf("sub") else server.types

            for (streamType in types) {
                try {
                    val sourcesUrl = "$API_URL/v1/meta/sources/$animeId?provider=$serverCode&num=$epNum&type=$streamType"
                    val res = app.get(sourcesUrl, headers = HEADERS).text
                    val sourcesData = parseJson<SourcesResponse>(res).data ?: continue

                    // Parse subtitles
                    sourcesData.subtitles?.forEach { sub ->
                        val subUrl = sub.url ?: return@forEach
                        val lang = sub.lang ?: sub.language ?: "English"
                        try {
                            subtitleCallback(SubtitleFile(lang, subUrl))
                        } catch (_: Exception) {}
                    }

                    // Parse video streams
                    sourcesData.sources?.forEach { src ->
                        val streamUrl = src.url ?: return@forEach
                        val isDub = src.isDub == true || streamType.equals("dub", ignoreCase = true)
                        val langTag = if (isDub) "Dub" else "Sub"
                        val displayName = server.displayName ?: serverCode.replaceFirstChar { it.uppercase() }
                        val serverName = "Just4Anime - $displayName ($langTag)"

                        val effectiveHeaders = src.headers?.toMutableMap() ?: mutableMapOf()
                        if (!effectiveHeaders.containsKey("Referer") && !effectiveHeaders.containsKey("referer")) {
                            effectiveHeaders["Referer"] = "$mainUrl/"
                        }
                        if (!effectiveHeaders.containsKey("Origin") && !effectiveHeaders.containsKey("origin")) {
                            effectiveHeaders["Origin"] = mainUrl
                        }
                        if (!effectiveHeaders.containsKey("User-Agent") && !effectiveHeaders.containsKey("user-agent")) {
                            effectiveHeaders["User-Agent"] = USER_AGENT
                        }

                        // 1. Emit direct master M3U8 link
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "$serverName (Auto)",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = effectiveHeaders["Referer"] ?: effectiveHeaders["referer"] ?: "$mainUrl/"
                                this.headers = effectiveHeaders
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        loadedAny = true

                        // 2. Generate multi-quality sub-streams (1080p, 720p, 480p, 360p) for ExoPlayer
                        try {
                            M3u8Helper.generateM3u8(
                                source = this.name,
                                streamUrl = streamUrl,
                                referer = effectiveHeaders["Referer"] ?: effectiveHeaders["referer"] ?: "$mainUrl/",
                                quality = Qualities.Unknown.value,
                                headers = effectiveHeaders,
                                name = serverName
                            ).forEach { link ->
                                callback.invoke(link)
                                loadedAny = true
                            }
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    // Try next server/type
                }
            }
        }

        return loadedAny
    }

    // JSON Data Classes
    data class AnilistListResponse(
        @JsonProperty("data") val data: AnilistListData? = null
    )

    data class AnilistListData(
        @JsonProperty("results") val results: List<AnilistAnimeItem>? = null
    )

    data class AnilistAnimeItem(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("title") val title: AnilistTitle? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("rating") val rating: Any? = null,
        @JsonProperty("releaseDate") val releaseDate: Any? = null
    )

    data class AnilistTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null,
        @JsonProperty("userPreferred") val userPreferred: String? = null
    )

    data class Just4AnimeEpisodesResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: Just4AnimeAnimeData? = null
    )

    data class Just4AnimeAnimeData(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("titleRomaji") val titleRomaji: String? = null,
        @JsonProperty("titleJa") val titleJa: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("images") val images: List<Just4AnimeImage>? = null,
        @JsonProperty("episodes") val episodes: List<Just4AnimeEpisodeItem>? = null
    )

    data class Just4AnimeImage(
        @JsonProperty("coverType") val coverType: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class Just4AnimeEpisodeItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("rating") val rating: String? = null
    )

    data class AvailabilityResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: AvailabilityData? = null
    )

    data class AvailabilityData(
        @JsonProperty("servers") val servers: List<ServerInfo>? = null
    )

    data class ServerInfo(
        @JsonProperty("code") val code: String? = null,
        @JsonProperty("displayName") val displayName: String? = null,
        @JsonProperty("hasEpisode") val hasEpisode: Boolean? = null,
        @JsonProperty("types") val types: List<String>? = null
    )

    data class SourcesResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: SourcesData? = null
    )

    data class SourcesData(
        @JsonProperty("sources") val sources: List<SourceItem>? = null,
        @JsonProperty("subtitles") val subtitles: List<SubtitleItem>? = null
    )

    data class SourceItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("isM3U8") val isM3U8: Boolean? = null,
        @JsonProperty("isDub") val isDub: Boolean? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null
    )

    data class SubtitleItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("language") val language: String? = null
    )
}

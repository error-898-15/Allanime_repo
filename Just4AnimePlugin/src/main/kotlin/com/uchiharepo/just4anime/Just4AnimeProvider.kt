package com.uchiharepo.just4anime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class Just4AnimeProvider : MainAPI() {
    override var mainUrl = "https://just4anime.online"
    override var name = "Just4Anime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        private const val API_BASE = "https://api.just4anime.online/api"
        private const val SITE_API = "https://just4anime.online/api"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val DEFAULT_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json"
        )
    }

    override val mainPage = mainPageOf(
        "TRENDING_DESC" to "Trending Now",
        "POPULARITY_DESC" to "Most Popular",
        "SCORE_DESC" to "Top Rated",
        "UPDATED_AT_DESC" to "Recently Updated",
        "START_DATE_DESC" to "New Releases",
        "FAVOURITES_DESC" to "Most Favorited"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val sortParam = URLEncoder.encode("[\"${request.data}\"]", "UTF-8")
        val url = "$SITE_API/advanced-search?sort=$sortParam&page=$page&perPage=20"
        val responseText = app.get(url, headers = DEFAULT_HEADERS).text
        val searchResponse = parseJson<SearchResponse>(responseText)
        val items = searchResponse.data?.results ?: emptyList()

        val home = items.mapNotNull { it.toSearchResponse() }
        val hasNext = searchResponse.data?.hasNextPage ?: (items.size >= 20)
        return newHomePageResponse(request.name, home, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$SITE_API/advanced-search?query=$encodedQuery&page=1&perPage=25"
        val responseText = app.get(url, headers = DEFAULT_HEADERS).text
        val searchResponse = parseJson<SearchResponse>(responseText)
        return searchResponse.data?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.removeSuffix("/").substringAfterLast("/")
        val epApiUrl = "$SITE_API/episodes/$animeId"
        val responseText = app.get(epApiUrl, headers = DEFAULT_HEADERS).text
        val detailResp = parseJson<EpisodesApiResponse>(responseText)
        val data = detailResp.data ?: throw ErrorLoadingException("Failed to load anime details")

        val title = data.title?.takeIf { it.isNotBlank() }
            ?: data.titleRomaji?.takeIf { it.isNotBlank() }
            ?: "Unknown"

        val isMovie = data.format?.equals("MOVIE", ignoreCase = true) == true
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        val episodes = (data.episodes ?: emptyList()).map { ep ->
            val num = ep.number ?: 1
            val epName = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $num"
            newEpisode(EpisodeLinkData(animeId, num, epName).toJson()) {
                this.name = epName
                this.episode = num
                this.season = ep.season ?: 1
                this.posterUrl = ep.image
                this.description = ep.description
                this.rating = ep.rating?.toString()?.toDoubleOrNull()?.times(1000)?.toInt()
            }
        }

        val poster = data.images?.poster
        val banner = data.images?.banner
        val plot = data.description
        val genres = data.genres
        val year = when (val y = data.year) {
            is Number -> y.toInt()
            is String -> y.toIntOrNull()
            else -> null
        }

        return if (isMovie && episodes.size <= 1) {
            val singleEpNum = episodes.firstOrNull()?.episode ?: 1
            newMovieLoadResponse(
                title,
                url,
                TvType.AnimeMovie,
                EpisodeLinkData(animeId, singleEpNum, title).toJson()
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = try {
            parseJson<EpisodeLinkData>(data)
        } catch (_: Exception) {
            val clean = data.trim().removePrefix("$mainUrl/anime/").removePrefix("$mainUrl/watch/")
            val parts = clean.split("/", "$", "?ep=")
            val id = parts.firstOrNull() ?: clean
            val num = parts.getOrNull(1)?.substringBefore("&")?.toIntOrNull() ?: 1
            EpisodeLinkData(id, num)
        }

        var loadedAny = false
        val animeId = linkData.animeId
        val epNum = linkData.number

        // 1. Discover available servers from metadata availability API
        val subProviders = mutableListOf<String>()
        val dubProviders = mutableListOf<String>()

        try {
            val availUrl = "$API_BASE/v1/meta/availability/$animeId"
            val availJson = app.get(availUrl, headers = DEFAULT_HEADERS).text
            val availResp = parseJson<AvailabilityApiResponse>(availJson)
            availResp.data?.sub?.providers?.forEach { p ->
                p.code?.let { subProviders.add(it) }
            }
            availResp.data?.dub?.providers?.forEach { p ->
                p.code?.let { dubProviders.add(it) }
            }
        } catch (_: Throwable) {}

        // Fallbacks if server discovery returned empty list
        if (subProviders.isEmpty()) {
            subProviders.addAll(listOf("rin", "chan", "levi", "mai", "meg"))
        }
        if (dubProviders.isEmpty()) {
            dubProviders.addAll(listOf("rin", "chan", "levi", "mai", "meg"))
        }

        // Build server queries for Sub and Dub streams
        val queries = mutableListOf<Pair<String, String>>()
        subProviders.distinct().forEach { code ->
            queries.add(code to "sub")
        }
        dubProviders.distinct().forEach { code ->
            queries.add(code to "dub")
        }

        for ((provCode, provType) in queries) {
            try {
                val srcUrl = "$API_BASE/v1/meta/sources/$animeId?provider=$provCode&num=$epNum&type=$provType"
                val srcJson = app.get(srcUrl, headers = DEFAULT_HEADERS).text
                val srcResp = parseJson<SourcesApiResponse>(srcJson)
                val srcData = srcResp.data ?: continue

                val typeUpper = if (provType == "dub") "Dub" else "Sub"
                val provCapitalized = provCode.replaceFirstChar { it.uppercase() }

                // 2. Extract HLS Direct Streams
                srcData.sources?.forEach { stream ->
                    val streamUrl = stream.url?.trim() ?: return@forEach
                    if (streamUrl.isBlank()) return@forEach

                    val serverName = stream.server?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
                        ?: provCapitalized
                    val serverLabel = "Just4Anime - $serverName ($typeUpper)"

                    val reqHeaders = (stream.headers ?: emptyMap()).toMutableMap()
                    if (!reqHeaders.containsKey("User-Agent")) {
                        reqHeaders["User-Agent"] = USER_AGENT
                    }
                    val referer = reqHeaders["referer"] ?: reqHeaders["Referer"] ?: "$mainUrl/"

                    // Primary ExtractorLink
                    callback.invoke(
                        newExtractorLink(
                            source = serverLabel,
                            name = serverLabel,
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.headers = reqHeaders
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    loadedAny = true

                    // Resolution Variants via M3u8Helper
                    try {
                        M3u8Helper.generateM3u8(
                            source = serverLabel,
                            streamUrl = streamUrl,
                            referer = referer,
                            quality = Qualities.Unknown.value,
                            headers = reqHeaders,
                            name = serverLabel
                        ).forEach { link ->
                            callback.invoke(link)
                            loadedAny = true
                        }
                    } catch (_: Throwable) {}
                }

                // 3. Extract Subtitles
                srcData.subtitles?.forEach { sub ->
                    val subUrl = sub.url?.trim() ?: return@forEach
                    if (subUrl.isBlank()) return@forEach
                    val langName = sub.language?.takeIf { it.isNotBlank() }
                        ?: sub.lang?.takeIf { it.isNotBlank() }
                        ?: "English"
                    val subLabel = "$langName ($typeUpper)"
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = subLabel,
                            url = subUrl
                        )
                    )
                }

                // 4. Extract Embed Iframes
                srcData.iframe?.forEach { iframe ->
                    val iframeUrl = iframe.url?.trim() ?: return@forEach
                    if (iframeUrl.isBlank()) return@forEach
                    val ref = iframe.headers?.get("referer") ?: "$mainUrl/"
                    val loaded = loadExtractor(
                        url = iframeUrl,
                        referer = ref,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    if (loaded) loadedAny = true
                }
            } catch (_: Throwable) {}
        }

        return loadedAny
    }

    private fun AnimeItem.toSearchResponse(): SearchResponse? {
        val targetId = this.id ?: return null
        val displayTitle = this.title?.english?.takeIf { it.isNotBlank() }
            ?: this.title?.userPreferred?.takeIf { it.isNotBlank() }
            ?: this.title?.romaji?.takeIf { it.isNotBlank() }
            ?: return null

        val isMovie = this.type?.equals("MOVIE", ignoreCase = true) == true
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(displayTitle, "$mainUrl/anime/$targetId", tvType) {
            this.posterUrl = this@toSearchResponse.image
            this.posterHeaders = DEFAULT_HEADERS
        }
    }

    // JSON Data Models
    data class SearchResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: SearchData? = null
    )

    data class SearchData(
        @JsonProperty("currentPage") val currentPage: Int? = null,
        @JsonProperty("hasNextPage") val hasNextPage: Boolean? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
        @JsonProperty("totalResults") val totalResults: Int? = null,
        @JsonProperty("results") val results: List<AnimeItem>? = null
    )

    data class AnimeItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("malId") val malId: Any? = null,
        @JsonProperty("title") val title: AnimeTitle? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("rating") val rating: Any? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("currentEpisode") val currentEpisode: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null
    )

    data class AnimeTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null,
        @JsonProperty("userPreferred") val userPreferred: String? = null
    )

    data class EpisodesApiResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: AnimeDetailData? = null
    )

    data class AnimeDetailData(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("malId") val malId: Any? = null,
        @JsonProperty("tmdbId") val tmdbId: Any? = null,
        @JsonProperty("tmdbType") val tmdbType: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("titleRomaji") val titleRomaji: String? = null,
        @JsonProperty("titleJa") val titleJa: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("year") val year: Any? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("images") val images: DetailImages? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null
    )

    data class DetailImages(
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("banner") val banner: String? = null
    )

    data class EpisodeItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("airDate") val airDate: String? = null,
        @JsonProperty("rating") val rating: Any? = null,
        @JsonProperty("season") val season: Int? = null
    )

    data class EpisodeLinkData(
        @JsonProperty("animeId") val animeId: String,
        @JsonProperty("number") val number: Int,
        @JsonProperty("title") val title: String? = null
    )

    data class AvailabilityApiResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: AvailabilityData? = null
    )

    data class AvailabilityData(
        @JsonProperty("animeId") val animeId: String? = null,
        @JsonProperty("malId") val malId: Any? = null,
        @JsonProperty("sub") val sub: ProviderGroup? = null,
        @JsonProperty("dub") val dub: ProviderGroup? = null
    )

    data class ProviderGroup(
        @JsonProperty("total") val total: Int? = null,
        @JsonProperty("providers") val providers: List<ProviderInfo>? = null
    )

    data class ProviderInfo(
        @JsonProperty("code") val code: String? = null,
        @JsonProperty("displayName") val displayName: String? = null,
        @JsonProperty("animeId") val animeId: String? = null,
        @JsonProperty("cached") val cached: Boolean? = null,
        @JsonProperty("hasEpisode") val hasEpisode: Boolean? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null
    )

    data class SourcesApiResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: SourcesData? = null
    )

    data class SourcesData(
        @JsonProperty("episode") val episode: SourceEpisode? = null,
        @JsonProperty("isDub") val isDub: Boolean? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("sources") val sources: List<SourceStream>? = null,
        @JsonProperty("subtitles") val subtitles: List<SourceSubtitle>? = null,
        @JsonProperty("iframe") val iframe: List<SourceIframe>? = null
    )

    data class SourceEpisode(
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("id") val id: String? = null
    )

    data class SourceStream(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("isM3U8") val isM3U8: Boolean? = null,
        @JsonProperty("isDub") val isDub: Boolean? = null,
        @JsonProperty("server") val server: String? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null,
        @JsonProperty("proxied") val proxied: Boolean? = null
    )

    data class SourceSubtitle(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null,
        @JsonProperty("origin") val origin: String? = null,
        @JsonProperty("proxied") val proxied: Boolean? = null
    )

    data class SourceIframe(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("server") val server: String? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null
    )
}

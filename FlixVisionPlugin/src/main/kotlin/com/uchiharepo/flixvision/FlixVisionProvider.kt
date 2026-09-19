package com.uchiharepo.flixvision

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class FlixVisionProvider : MainAPI() {
    override var mainUrl = "https://api.themoviedb.org/3"
    override var name = "FlixVision"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        const val TMDB_API_KEY = "2f3cb5763db1117fcba3948632f8aad9"
        const val TMDB_IMG_W500 = "https://image.tmdb.org/t/p/w500"
        const val TMDB_IMG_ORIGINAL = "https://image.tmdb.org/t/p/original"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/trending/movie/day?api_key=$TMDB_API_KEY" to "Trending Movies",
        "$mainUrl/trending/tv/day?api_key=$TMDB_API_KEY" to "Trending TV Series",
        "$mainUrl/movie/popular?api_key=$TMDB_API_KEY" to "Popular Movies",
        "$mainUrl/tv/popular?api_key=$TMDB_API_KEY" to "Popular TV Shows",
        "$mainUrl/movie/top_rated?api_key=$TMDB_API_KEY" to "Top Rated Movies",
        "$mainUrl/tv/top_rated?api_key=$TMDB_API_KEY" to "Top Rated TV Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}&page=$page"
        val response = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).text
        val parsed = parseJson<TmdbPageResponse>(response)
        val homeItems = parsed.results?.mapNotNull { item ->
            item.toSearchResponse()
        } ?: emptyList()

        return newHomePageResponse(request.name, homeItems)
    }

    private fun TmdbMediaItem.toSearchResponse(): SearchResponse? {
        val tmdbId = this.id ?: return null
        val titleText = this.title ?: this.name ?: return null
        val poster = this.posterPath?.let { "$TMDB_IMG_W500$it" }
        val isTv = this.name != null || this.firstAirDate != null

        val loadData = MediaLinkData(
            id = tmdbId,
            type = if (isTv) "tv" else "movie",
            title = titleText
        ).toJson()

        return if (isTv) {
            newTvSeriesSearchResponse(titleText, loadData, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(titleText, loadData, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/search/multi?api_key=$TMDB_API_KEY&query=$encoded&include_adult=false"
        val response = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
        val parsed = parseJson<TmdbPageResponse>(response)
        return parsed.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val linkData = try {
            parseJson<MediaLinkData>(url)
        } catch (e: Exception) {
            val idOnly = url.filter { it.isDigit() }.toIntOrNull() ?: return null
            val isTv = url.contains("tv", ignoreCase = true)
            MediaLinkData(id = idOnly, type = if (isTv) "tv" else "movie")
        }

        val tmdbId = linkData.id
        val isTv = linkData.type == "tv"
        val detailsUrl = "$mainUrl/${linkData.type}/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=credits,external_ids"

        val response = app.get(detailsUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
        val details = parseJson<TmdbDetailsResponse>(response)

        val title = details.title ?: details.name ?: linkData.title ?: "FlixVision"
        val poster = details.posterPath?.let { "$TMDB_IMG_W500$it" }
        val backdrop = details.backdropPath?.let { "$TMDB_IMG_ORIGINAL$it" }
        val plot = details.overview
        val tags = details.genres?.mapNotNull { it.name } ?: emptyList()
        val year = (details.releaseDate ?: details.firstAirDate)?.take(4)?.toIntOrNull()

        if (!isTv) {
            val movieData = MediaPlayData(
                id = tmdbId,
                type = "movie",
                title = title
            ).toJson()
            return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val numSeasons = details.numberOfSeasons ?: 1

            for (s in 1..numSeasons) {
                try {
                    val seasonUrl = "$mainUrl/tv/$tmdbId/season/$s?api_key=$TMDB_API_KEY"
                    val sRes = app.get(seasonUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
                    val seasonData = parseJson<TmdbSeasonResponse>(sRes)
                    seasonData.episodes?.forEach { ep ->
                        val epNum = ep.episodeNumber ?: return@forEach
                        val epData = MediaPlayData(
                            id = tmdbId,
                            type = "tv",
                            season = s,
                            episode = epNum,
                            title = title
                        ).toJson()
                        episodes.add(
                            newEpisode(epData) {
                                this.name = ep.name ?: "Episode $epNum"
                                this.season = s
                                this.episode = epNum
                                this.posterUrl = ep.stillPath?.let { "$TMDB_IMG_W500$it" }
                            }
                        )
                    }
                } catch (e: Exception) {
                    // Skip season on error
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.tags = tags
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
        val playData = try {
            parseJson<MediaPlayData>(data)
        } catch (e: Exception) {
            return false
        }

        val tmdbId = playData.id
        val isTv = playData.type == "tv"
        val season = playData.season ?: 1
        val episode = playData.episode ?: 1
        var loadedAny = false

        // 1. [FVSTREAM 4] · [DIRECT] (Cloudnestra / VSEmbed Realtime API)
        try {
            val vsembedApi = if (isTv) {
                "https://vsembed.ru/vs_src.php?type=tv&id=$tmdbId&s=$season&e=$episode"
            } else {
                "https://vsembed.ru/vs_src.php?type=movie&id=$tmdbId"
            }
            val vsRes = app.get(
                vsembedApi,
                headers = mapOf(
                    "Referer" to "https://vsembed.ru/",
                    "User-Agent" to USER_AGENT,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text

            if (vsRes.contains("\"src\"")) {
                val vsData = parseJson<VsSourceResponse>(vsRes)
                val directSrc = vsData.src
                if (!directSrc.isNullOrBlank()) {
                    if (directSrc.contains(".m3u8")) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "1080p - [FVSTREAM 4] · [DIRECT]",
                                url = directSrc,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://vsembed.ru/"
                                this.headers = mapOf(
                                    "Referer" to "https://vsembed.ru/",
                                    "User-Agent" to USER_AGENT
                                )
                                this.quality = Qualities.P1080.value
                            }
                        )
                        loadedAny = true
                    } else {
                        if (loadExtractor(directSrc, "https://vsembed.ru/", subtitleCallback, callback)) {
                            loadedAny = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Suppress Server 4 exception
        }

        // 2. [FVSTREAM 1] · [VIDSRC] · [DIRECT]
        try {
            val vidsrcUrl = if (isTv) {
                "https://vidsrc.to/embed/tv/$tmdbId/$season/$episode"
            } else {
                "https://vidsrc.to/embed/movie/$tmdbId"
            }
            if (loadExtractor(vidsrcUrl, "https://vidsrc.to/", subtitleCallback, callback)) {
                loadedAny = true
            }
        } catch (e: Exception) {
            // Suppress VidSrc exception
        }

        // 3. [SMASHYSTREAM] · [DIRECT]
        try {
            val smashyUrl = if (isTv) {
                "https://embed.smashystream.com/playere.php?tmdb=$tmdbId&season=$season&episode=$episode"
            } else {
                "https://embed.smashystream.com/playere.php?tmdb=$tmdbId"
            }
            if (loadExtractor(smashyUrl, "https://embed.smashystream.com/", subtitleCallback, callback)) {
                loadedAny = true
            }
        } catch (e: Exception) {
            // Suppress SmashyStream exception
        }

        // 4. [AUTOEMBED] · [DIRECT]
        try {
            val autoembedUrl = if (isTv) {
                "https://player.autoembed.co/embed/tv/$tmdbId/$season/$episode"
            } else {
                "https://player.autoembed.co/embed/movie/$tmdbId"
            }
            if (loadExtractor(autoembedUrl, "https://autoembed.co/", subtitleCallback, callback)) {
                loadedAny = true
            }
        } catch (e: Exception) {
            // Suppress AutoEmbed exception
        }

        // 5. [VIDSRC-EMBED] · [DIRECT]
        try {
            val vsEmbedUrl = if (isTv) {
                "https://vidsrc-embed.ru/embed/tv/$tmdbId/$season/$episode"
            } else {
                "https://vidsrc-embed.ru/embed/movie/$tmdbId"
            }
            if (loadExtractor(vsEmbedUrl, "https://vidsrc-embed.ru/", subtitleCallback, callback)) {
                loadedAny = true
            }
        } catch (e: Exception) {
            // Suppress vsEmbed exception
        }

        // 6. 1080p · [FVSTREAM 2] · [DIRECT] · English (VixCloud)
        try {
            val vixUrl = "https://vixcloud.co/embed/$tmdbId"
            if (loadExtractor(vixUrl, "https://vixcloud.co/", subtitleCallback, callback)) {
                loadedAny = true
            }
        } catch (e: Exception) {
            // Suppress VixCloud exception
        }

        // 7. 1080p · [FVSTREAM 3] · [DIRECT] · English (CloseLoad)
        try {
            val closeUrl = "https://closeload.top/embed/$tmdbId"
            if (loadExtractor(closeUrl, "https://closeload.top/", subtitleCallback, callback)) {
                loadedAny = true
            }
        } catch (e: Exception) {
            // Suppress CloseLoad exception
        }

        return loadedAny
    }

    // JSON models
    data class MediaLinkData(
        @JsonProperty("id") val id: Int,
        @JsonProperty("type") val type: String,
        @JsonProperty("title") val title: String? = null
    )

    data class MediaPlayData(
        @JsonProperty("id") val id: Int,
        @JsonProperty("type") val type: String,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("title") val title: String? = null
    )

    data class TmdbPageResponse(
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("results") val results: List<TmdbMediaItem>? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null
    )

    data class TmdbMediaItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("media_type") val mediaType: String? = null
    )

    data class TmdbDetailsResponse(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("number_of_seasons") val numberOfSeasons: Int? = null,
        @JsonProperty("number_of_episodes") val numberOfEpisodes: Int? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null
    )

    data class TmdbGenre(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null
    )

    data class TmdbSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TmdbEpisodeItem>? = null
    )

    data class TmdbEpisodeItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null
    )

    data class VsSourceResponse(
        @JsonProperty("src") val src: String? = null
    )
}

package com.uchiharepo.flixvision

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import java.net.URI

class FlixVisionProvider : MainAPI() {
    override var mainUrl = "https://flixvision.top"
    override var name = "FlixVision"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Live
    )

    private val tmdbApiKey = "45dbdd59a87121d50c845d04840eaf18"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val imgBase = "https://image.tmdb.org/t/p/w500"

    // Live TV Catalog Data
    private val liveTvChannels = listOf(
        // Sports
        LiveChannel("live_redbull", "Red Bull TV Sports HD", "https://images.unsplash.com/photo-1517649763962-0c623266ddc0?w=200", "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8", "sports", "Extreme Sports & Live Events"),
        LiveChannel("live_nasa", "NASA TV Space Live HD", "https://images.unsplash.com/photo-1451187580459-43490279c0fa?w=200", "https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8", "sports", "Science & Space Live"),
        
        // Hindi & Indian
        LiveChannel("live_aajtak", "Aaj Tak Live HD (Hindi News)", "https://images.unsplash.com/photo-1585829365295-ab7cd400c167?w=200", "https://feeds.intoday.in/aajtak/api/aajtakhd/master.m3u8", "hindi", "Hindi News & Breaking"),
        LiveChannel("live_9xm", "9XM Bollywood Hits HD", "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=200", "https://d2q8p4pe5spbak.cloudfront.net/bpk-tv/9XM/default/index.m3u8", "hindi", "Bollywood & Hindi Hits"),
        LiveChannel("live_9xjalwa", "9X Jalwa Classic Cinema Hits", "https://images.unsplash.com/photo-1485846234645-a62644f84728?w=200", "https://d2q8p4pe5spbak.cloudfront.net/bpk-tv/9XJalwa/default/index.m3u8", "hindi", "Retro Hindi & Cinema"),

        // News 24/7
        LiveChannel("live_bloomberg", "Bloomberg Live 24/7", "https://images.unsplash.com/photo-1611974789855-9c2a0a7236a3?w=200", "https://bloomberg.com/media-manifest/streams/us.m3u8", "news", "Finance & Global Markets"),
        LiveChannel("live_dw", "DW News 24/7 English", "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=200", "https://dwamdstream102.akamaized.net/hls/live/2015525/dl_live_1_en/master.m3u8", "news", "Global Breaking News"),
        LiveChannel("live_10tv", "10 TV News Live HD", "https://images.unsplash.com/photo-1495020689067-958852a7765e?w=200", "https://livemt.vidgyor.com/tentv/liveabr/tentv/livemt/chunks.m3u8", "news", "Regional & National News"),

        // Movies & Cinema
        LiveChannel("live_fv_cinema", "FlixVision Action Cinema Live", "https://images.unsplash.com/photo-1536440136628-849c177e76a1?w=200", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4", "movies", "24/7 Action & Movies")
    )

    data class LiveChannel(
        val id: String,
        val name: String,
        val poster: String,
        val streamUrl: String,
        val category: String,
        val description: String
    )

    override val mainPage = mainPageOf(
        "$tmdbApi/trending/movie/day?api_key=$tmdbApiKey" to "Trending Movies",
        "$tmdbApi/movie/popular?api_key=$tmdbApiKey" to "Popular Movies",
        "$tmdbApi/trending/tv/day?api_key=$tmdbApiKey" to "Trending TV Series",
        "$tmdbApi/tv/popular?api_key=$tmdbApiKey" to "Popular TV Series",
        "$tmdbApi/movie/top_rated?api_key=$tmdbApiKey" to "Top Rated Movies",
        "livetv:sports" to "Live Sports Channels",
        "livetv:hindi" to "Hindi & Indian Channels",
        "livetv:news" to "Live News 24/7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith("livetv:")) {
            val cat = request.data.removePrefix("livetv:")
            val channels = if (cat == "all") liveTvChannels else liveTvChannels.filter { it.category == cat }
            val list = channels.map { ch ->
                newLiveSearchResponse(ch.name, "livetv://${ch.id}", TvType.Live) {
                    this.posterUrl = ch.poster
                }
            }
            return newHomePageResponse(request.name, list, hasNext = false)
        }

        val json = app.get("${request.data}&page=$page").text
        val response = tryParseJson<TmdbPageResult>(json) ?: return newHomePageResponse(request.name, emptyList())
        val homeList = response.results.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val isMovie = item.title != null || item.mediaType == "movie"
            val type = if (isMovie) TvType.Movie else TvType.TvSeries
            val url = "$tmdbApi/${if (isMovie) "movie" else "tv"}/${item.id}?api_key=$tmdbApiKey&append_to_response=external_ids"
            
            newMovieSearchResponse(title, url, type) {
                this.posterUrl = item.posterPath?.let { "$imgBase$it" }
                this.year = item.releaseDate?.take(4)?.toIntOrNull() ?: item.firstAirDate?.take(4)?.toIntOrNull()
            }
        }
        return newHomePageResponse(request.name, homeList, hasNext = response.page < response.totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$tmdbApi/search/multi?api_key=$tmdbApiKey&query=${query.encodeUri()}"
        val json = app.get(searchUrl).text
        val response = tryParseJson<TmdbPageResult>(json) ?: return emptyList()

        return response.results.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val isMovie = item.mediaType == "movie" || (item.mediaType == null && item.title != null)
            val type = if (isMovie) TvType.Movie else TvType.TvSeries
            val url = "$tmdbApi/${if (isMovie) "movie" else "tv"}/${item.id}?api_key=$tmdbApiKey&append_to_response=external_ids"

            newMovieSearchResponse(title, url, type) {
                this.posterUrl = item.posterPath?.let { "$imgBase$it" }
                this.year = item.releaseDate?.take(4)?.toIntOrNull() ?: item.firstAirDate?.take(4)?.toIntOrNull()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // Handle Live TV stream loading
        if (url.startsWith("livetv://")) {
            val id = url.removePrefix("livetv://")
            val channel = liveTvChannels.find { it.id == id } ?: liveTvChannels.first()
            return newLiveStreamLoadResponse(channel.name, url, channel.streamUrl) {
                this.posterUrl = channel.poster
                this.plot = channel.description
            }
        }

        val json = app.get(url).text
        val isMovie = url.contains("/movie/")
        
        if (isMovie) {
            val item = tryParseJson<TmdbMovieDetails>(json) ?: throw ErrorLoadingException("Failed to load Movie details")
            val title = item.title ?: "Unknown"
            val tmdbId = item.id
            val imdbId = item.externalIds?.imdbId
            val dataUrl = "flixvision://movie?id=$tmdbId&imdb=$imdbId&title=${title.encodeUri()}"

            return newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
                this.posterUrl = item.posterPath?.let { "$imgBase$it" }
                this.backgroundPosterUrl = item.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
                this.year = item.releaseDate?.take(4)?.toIntOrNull()
                this.plot = item.overview
                this.rating = item.voteAverage?.times(1000)?.toInt()
                this.tags = item.genres?.mapNotNull { it.name }
                this.duration = item.runtime
                addTMDbId(tmdbId.toString())
                if (!imdbId.isNullOrBlank()) addImdbId(imdbId)
            }
        } else {
            val item = tryParseJson<TmdbTvDetails>(json) ?: throw ErrorLoadingException("Failed to load TV details")
            val title = item.name ?: "Unknown"
            val tmdbId = item.id
            val imdbId = item.externalIds?.imdbId

            val episodes = mutableListOf<Episode>()
            item.seasons?.forEach { season ->
                val sNum = season.seasonNumber ?: return@forEach
                if (sNum <= 0) return@forEach
                try {
                    val seasonJson = app.get("$tmdbApi/tv/$tmdbId/season/$sNum?api_key=$tmdbApiKey").text
                    val seasonData = tryParseJson<TmdbSeasonDetails>(seasonJson)
                    seasonData?.episodes?.forEach { ep ->
                        val epNum = ep.episodeNumber ?: return@forEach
                        val dataUrl = "flixvision://tv?id=$tmdbId&imdb=$imdbId&s=$sNum&e=$epNum&title=${title.encodeUri()}"
                        episodes.add(
                            newEpisode(dataUrl) {
                                this.name = ep.name
                                this.season = sNum
                                this.episode = epNum
                                this.posterUrl = ep.stillPath?.let { "$imgBase$it" }
                                this.description = ep.overview
                                this.rating = ep.voteAverage?.times(1000)?.toInt()
                            }
                        )
                    }
                } catch (_: Exception) { }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = item.posterPath?.let { "$imgBase$it" }
                this.backgroundPosterUrl = item.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
                this.year = item.firstAirDate?.take(4)?.toIntOrNull()
                this.plot = item.overview
                this.rating = item.voteAverage?.times(1000)?.toInt()
                this.tags = item.genres?.mapNotNull { it.name }
                addTMDbId(tmdbId.toString())
                if (!imdbId.isNullOrBlank()) addImdbId(imdbId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Direct Live TV stream callback
        if (data.startsWith("http://") || data.startsWith("https://")) {
            callback.invoke(
                newExtractorLink(
                    "FlixVision Live HD",
                    "FlixVision Live TV",
                    data,
                    referer = "",
                    quality = Qualities.P1080.value,
                    type = if (data.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
            return true
        }

        val uri = URI(data)
        val isMovie = uri.host == "movie"
        val params = uri.query?.split("&")?.associate {
            val parts = it.split("=")
            parts[0] to (parts.getOrNull(1) ?: "")
        } ?: emptyMap()

        val tmdbId = params["id"] ?: return false
        val imdbId = params["imdb"]?.takeIf { it.isNotBlank() && it != "null" }
        val season = params["s"]?.toIntOrNull() ?: 1
        val episode = params["e"]?.toIntOrNull() ?: 1
        val title = params["title"] ?: ""

        val fetchJobs = mutableListOf<suspend () -> Unit>()

        // 1. FlixVision Direct FVSTREAM (Multi-Server)
        fetchJobs.add {
            extractFVStream(tmdbId, imdbId, isMovie, season, episode, subtitleCallback, callback)
        }

        // 2. Multi-Language & Dual-Audio Server (AutoEmbed Hindi + English)
        fetchJobs.add {
            extractAutoEmbed(tmdbId, isMovie, season, episode, subtitleCallback, callback)
        }

        // 3. SmashyStream Multi-Server (Dual Audio & Multi-Quality)
        fetchJobs.add {
            extractSmashyStream(tmdbId, imdbId, isMovie, season, episode, subtitleCallback, callback)
        }

        // 4. VidSrc.buzz Multi-Server with Audio Stream Tagging
        fetchJobs.add {
            extractVidSrcBuzz(tmdbId, isMovie, season, episode, subtitleCallback, callback)
        }

        // 5. 2Embed Multi-Language Mirror
        fetchJobs.add {
            extract2Embed(tmdbId, isMovie, season, episode, subtitleCallback, callback)
        }

        // 6. SuperEmbed Multi-Server Extractor
        fetchJobs.add {
            val embedUrl = if (isMovie) {
                "https://multiembed.mov/?video_id=$tmdbId&tmdb=1"
            } else {
                "https://multiembed.mov/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode"
            }
            loadExtractor(embedUrl, subtitleCallback, callback)
        }

        fetchJobs.amap { it.invoke() }
        return true
    }

    // Server 1: FlixVision Direct FVSTREAM / Vidsrc Core (Fix for Error 2004)
    private suspend fun extractFVStream(
        tmdbId: String,
        imdbId: String?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val targetId = imdbId ?: tmdbId
        val fvUrls = listOf(
            if (isMovie) "https://vidsrc.me/embed/movie?id=$targetId" else "https://vidsrc.me/embed/tv?id=$targetId&s=$season&e=$episode",
            if (isMovie) "https://vidsrc.in/embed/movie?id=$targetId" else "https://vidsrc.in/embed/tv?id=$targetId&s=$season&e=$episode",
            if (isMovie) "https://vidsrc.pm/embed/movie?id=$targetId" else "https://vidsrc.pm/embed/tv?id=$targetId&s=$season&e=$episode",
            if (isMovie) "https://vsembed.ru/embed/movie/$tmdbId" else "https://vsembed.ru/embed/tv/$tmdbId/$season/$episode"
        )

        for (embedUrl in fvUrls) {
            try {
                val res = app.get(embedUrl, headers = mapOf("Referer" to "https://vidsrc.me/")).text
                val doc = Jsoup.parse(res)
                
                // Inspect iframe sources
                val iframeSrc = doc.select("iframe#player_iframe").attr("src")
                    .ifEmpty { doc.select("iframe").attr("src") }

                if (iframeSrc.isNotBlank()) {
                    val fullSrc = fixUrl(iframeSrc, embedUrl)
                    // If it's a web player embed, delegate safely to loadExtractor
                    if (fullSrc.contains("/embed/") || fullSrc.contains("/e/") || !fullSrc.contains(".m3u8")) {
                        loadExtractor(fullSrc, embedUrl, subtitleCallback, callback)
                    } else {
                        // Direct verified HLS/MP4 link
                        callback.invoke(
                            newExtractorLink(
                                "FVSTREAM Direct V4",
                                "FVSTREAM · 1080p Direct [English]",
                                fullSrc,
                                referer = embedUrl,
                                quality = Qualities.P1080.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // Server 2: AutoEmbed Multi-Language & Dual-Audio
    private suspend fun extractAutoEmbed(
        tmdbId: String,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val autoUrl = if (isMovie) {
                "https://player.autoembed.cc/embed/movie/$tmdbId"
            } else {
                "https://player.autoembed.cc/embed/tv/$tmdbId/$season/$episode"
            }

            val html = app.get(autoUrl, headers = mapOf("Referer" to "https://autoembed.cc")).text
            val doc = Jsoup.parse(html)
            
            // Extract servers including Hindi/Dual-Audio
            doc.select("a[data-src], button[data-src], iframe").forEach { el ->
                val src = el.attr("data-src").ifEmpty { el.attr("src") }
                if (src.isNotBlank()) {
                    val fullUrl = fixUrl(src, autoUrl)
                    val label = el.text().trim().ifEmpty { "Multi-Audio Server" }
                    val isHindi = label.contains("Hindi", true) || fullUrl.contains("hindi", true)
                    
                    if (fullUrl.contains(".m3u8") || fullUrl.contains(".mp4")) {
                        callback.invoke(
                            newExtractorLink(
                                "FlixVision AutoEmbed",
                                "FlixVision · ${if (isHindi) "[Hindi + Dual]" else "[Multi-Audio]"} $label",
                                fullUrl,
                                referer = autoUrl,
                                quality = Qualities.P1080.value,
                                type = if (fullUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                    } else {
                        loadExtractor(fullUrl, autoUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) { }
    }

    // Server 3: SmashyStream Multi-Server
    private suspend fun extractSmashyStream(
        tmdbId: String,
        imdbId: String?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val smashyUrl = if (isMovie) {
                "https://embed.smashystream.com/playere.php?tmdb=$tmdbId"
            } else {
                "https://embed.smashystream.com/playere.php?tmdb=$tmdbId&season=$season&episode=$episode"
            }
            loadExtractor(smashyUrl, "https://smashystream.com", subtitleCallback, callback)
        } catch (_: Exception) { }
    }

    // Server 4: VidSrc.buzz Multi-Server Extractor
    private suspend fun extractVidSrcBuzz(
        tmdbId: String,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val buzzUrl = if (isMovie) {
                "https://vidsrc.buzz/embed/movie/$tmdbId"
            } else {
                "https://vidsrc.buzz/embed/tv/$tmdbId/$season/$episode"
            }

            val html = app.get(buzzUrl, headers = mapOf("Referer" to "https://vidsrc.buzz")).text
            val doc = Jsoup.parse(html)

            doc.select("div.servers-list a, .server-item").forEachIndexed { index, el ->
                val serverName = el.text().trim().ifEmpty { "Server ${index + 1}" }
                val dataHash = el.attr("data-hash").ifEmpty { el.attr("href") }
                
                if (dataHash.isNotBlank()) {
                    val fullUrl = if (dataHash.startsWith("http")) dataHash else "https://vidsrc.buzz$dataHash"
                    loadExtractor(fullUrl, buzzUrl, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) { }
    }

    // Server 5: 2Embed Multi-Language Mirror
    private suspend fun extract2Embed(
        tmdbId: String,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val twoEmbedUrl = if (isMovie) {
                "https://www.2embed.cc/embed/$tmdbId"
            } else {
                "https://www.2embed.cc/embedtv/$tmdbId&s=$season&e=$episode"
            }
            loadExtractor(twoEmbedUrl, "https://www.2embed.cc", subtitleCallback, callback)
        } catch (_: Exception) { }
    }

    private fun fixUrl(url: String, domain: String): String {
        return if (url.startsWith("//")) {
            "https:$url"
        } else if (url.startsWith("/")) {
            val base = domain.substringBeforeLast("/")
            "$base$url"
        } else {
            url
        }
    }

    private fun String.encodeUri(): String = java.net.URLEncoder.encode(this, "UTF-8")

    // TMDB DTOs
    data class TmdbPageResult(
        val page: Int,
        val results: List<TmdbItem>,
        @JsonProperty("total_pages") val totalPages: Int,
        @JsonProperty("total_results") val totalResults: Int
    )

    data class TmdbItem(
        val id: Int,
        val title: String?,
        val name: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("media_type") val mediaType: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        val overview: String?,
        @JsonProperty("vote_average") val voteAverage: Double?
    )

    data class TmdbMovieDetails(
        val id: Int,
        val title: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        val overview: String?,
        val runtime: Int?,
        @JsonProperty("vote_average") val voteAverage: Double?,
        val genres: List<TmdbGenre>?,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds?
    )

    data class TmdbTvDetails(
        val id: Int,
        val name: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        val overview: String?,
        @JsonProperty("vote_average") val voteAverage: Double?,
        val genres: List<TmdbGenre>?,
        val seasons: List<TmdbSeason>?,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds?
    )

    data class TmdbSeason(
        val id: Int?,
        @JsonProperty("season_number") val seasonNumber: Int?,
        val name: String?,
        @JsonProperty("episode_count") val episodeCount: Int?
    )

    data class TmdbSeasonDetails(
        val id: Int?,
        @JsonProperty("season_number") val seasonNumber: Int?,
        val episodes: List<TmdbEpisode>?
    )

    data class TmdbEpisode(
        val id: Int,
        val name: String?,
        @JsonProperty("episode_number") val episodeNumber: Int?,
        @JsonProperty("season_number") val seasonNumber: Int?,
        val overview: String?,
        @JsonProperty("still_path") val stillPath: String?,
        @JsonProperty("vote_average") val voteAverage: Double?
    )

    data class TmdbGenre(val id: Int, val name: String?)
    data class TmdbExternalIds(@JsonProperty("imdb_id") val imdbId: String?)
}

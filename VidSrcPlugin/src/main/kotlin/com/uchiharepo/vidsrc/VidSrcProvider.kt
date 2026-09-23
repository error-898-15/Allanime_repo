package com.uchiharepo.vidsrc

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder

class VidSrcProvider : MainAPI() {
    override var mainUrl = "https://vidsrc.sbs"
    override var name = "VidSrc"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        const val TMDB_API = "https://api.themoviedb.org/3"
        const val TMDB_IMG = "https://image.tmdb.org/t/p/w500"
        const val TMDB_KEY = "4152ea09a44140809f82d68a9b2b0024"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val mapper = ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    override val mainPage = mainPageOf(
        "trending/movie/day" to "Trending Movies",
        "movie/popular" to "Popular Movies",
        "movie/top_rated" to "Top Rated Movies",
        "trending/tv/day" to "Trending TV Series",
        "tv/popular" to "Popular TV Shows",
        "tv/top_rated" to "Top Rated TV Series",
        "movie/now_playing" to "Now Playing In Theaters",
        "discover/movie?with_genres=28" to "Action Blockbusters",
        "discover/movie?with_genres=878" to "Sci-Fi & Fantasy",
        "discover/movie?with_genres=27" to "Horror & Thrillers"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val path = request.data
        val sep = if (path.contains("?")) "&" else "?"
        val url = "$TMDB_API/$path${sep}api_key=$TMDB_KEY&page=$page"

        return try {
            val res = app.get(url, headers = mapOf("User-Agent" to USER_AGENT))
            if (!res.isSuccessful) return newHomePageResponse(request.name, emptyList())

            val root = mapper.readTree(res.text)
            val resultsNode = root.path("results")
            val items = mutableListOf<SearchResponse>()

            if (resultsNode.isArray) {
                for (item in resultsNode) {
                    val id = item.path("id").asInt()
                    val isTv = item.has("name") || path.contains("tv")
                    val title = if (isTv) item.path("name").asText("Untitled") else item.path("title").asText("Untitled")
                    val posterPath = item.path("poster_path").asText("")
                    val poster = if (posterPath.isNotBlank()) "$TMDB_IMG$posterPath" else null
                    val score = item.path("vote_average").asDouble(0.0)

                    if (isTv) {
                        items.add(newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) {
                            this.posterUrl = poster
                            this.rating = (score * 10).toInt()
                        })
                    } else {
                        items.add(newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) {
                            this.posterUrl = poster
                            this.rating = (score * 10).toInt()
                        })
                    }
                }
            }
            newHomePageResponse(request.name, items)
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$encoded&include_adult=false"
            val res = app.get(url, headers = mapOf("User-Agent" to USER_AGENT))
            if (!res.isSuccessful) return emptyList()

            val root = mapper.readTree(res.text)
            val resultsNode = root.path("results")
            val items = mutableListOf<SearchResponse>()

            if (resultsNode.isArray) {
                for (item in resultsNode) {
                    val mediaType = item.path("media_type").asText("")
                    if (mediaType != "movie" && mediaType != "tv") continue

                    val id = item.path("id").asInt()
                    val isTv = mediaType == "tv"
                    val title = if (isTv) item.path("name").asText("Untitled") else item.path("title").asText("Untitled")
                    val posterPath = item.path("poster_path").asText("")
                    val poster = if (posterPath.isNotBlank()) "$TMDB_IMG$posterPath" else null
                    val score = item.path("vote_average").asDouble(0.0)

                    if (isTv) {
                        items.add(newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) {
                            this.posterUrl = poster
                            this.rating = (score * 10).toInt()
                        })
                    } else {
                        items.add(newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) {
                            this.posterUrl = poster
                            this.rating = (score * 10).toInt()
                        })
                    }
                }
            }
            items
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val isTv = url.contains("/tv/")
        val idStr = url.substringAfterLast("/").trim()
        val id = idStr.toIntOrNull() ?: return null

        val endpoint = if (isTv) "tv" else "movie"
        val tmdbUrl = "$TMDB_API/$endpoint/$id?api_key=$TMDB_KEY&append_to_response=external_ids"
        val res = app.get(tmdbUrl, headers = mapOf("User-Agent" to USER_AGENT))

        if (!res.isSuccessful) return null

        val json = mapper.readTree(res.text)
        val title = if (isTv) json.path("name").asText("Untitled") else json.path("title").asText("Untitled")
        val plot = json.path("overview").asText("")
        val posterPath = json.path("poster_path").asText("")
        val poster = if (posterPath.isNotBlank()) "$TMDB_IMG$posterPath" else null
        val backdropPath = json.path("backdrop_path").asText("")
        val backdrop = if (backdropPath.isNotBlank()) "$TMDB_IMG$backdropPath" else null
        val releaseDate = if (isTv) json.path("first_air_date").asText("") else json.path("release_date").asText("")
        val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4).toIntOrNull() else null
        val rating = (json.path("vote_average").asDouble(0.0) * 10).toInt()
        val imdbId = json.path("external_ids").path("imdb_id").asText("")

        val genres = mutableListOf<String>()
        val genresNode = json.path("genres")
        if (genresNode.isArray) {
            for (g in genresNode) {
                val gname = g.path("name").asText("")
                if (gname.isNotBlank()) genres.add(gname)
            }
        }

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            val numSeasons = json.path("number_of_seasons").asInt(1)

            for (s in 1..numSeasons) {
                try {
                    val sUrl = "$TMDB_API/tv/$id/season/$s?api_key=$TMDB_KEY"
                    val sRes = app.get(sUrl, headers = mapOf("User-Agent" to USER_AGENT))
                    if (sRes.isSuccessful) {
                        val sJson = mapper.readTree(sRes.text)
                        val epsNode = sJson.path("episodes")
                        if (epsNode.isArray) {
                            for (ep in epsNode) {
                                val epNum = ep.path("episode_number").asInt()
                                val epName = ep.path("name").asText("Episode $epNum")
                                val stillPath = ep.path("still_path").asText("")
                                val epPoster = if (stillPath.isNotBlank()) "$TMDB_IMG$stillPath" else poster
                                val epPlot = ep.path("overview").asText("")

                                val payload = "{\"type\":\"tv\",\"tmdbId\":\"$id\",\"imdbId\":\"$imdbId\",\"season\":\"$s\",\"episode\":\"$epNum\"}"

                                val episodeObj = newEpisode(payload) {
                                    this.name = epName
                                    this.season = s
                                    this.episode = epNum
                                    this.posterUrl = epPoster
                                    this.description = epPlot
                                }
                                episodes.add(episodeObj)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = genres
                this.rating = rating
            }
        } else {
            val payload = "{\"type\":\"movie\",\"tmdbId\":\"$id\",\"imdbId\":\"$imdbId\"}"

            return newMovieLoadResponse(title, url, TvType.Movie, payload) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = genres
                this.rating = rating
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataNode = try {
            mapper.readTree(data)
        } catch (_: Exception) {
            return false
        }

        val type = dataNode.path("type").asText("movie")
        val isTv = type == "tv"
        val tmdbId = dataNode.path("tmdbId").asText("")
        val imdbId = dataNode.path("imdbId").asText("")
        val season = dataNode.path("season").asText("1")
        val episode = dataNode.path("episode").asText("1")

        if (tmdbId.isBlank() && imdbId.isBlank()) return false

        val serverList = mutableListOf<String>()

        if (isTv) {
            // Pro Multi (vidsrc.sbs primary)
            serverList.add("https://web.nxsha.app/embed/tv/$tmdbId/$season/$episode?server=AwsPly-[Multi-Lang]")
            // CineSrc HD
            serverList.add("https://cinesrc.st/embed/tv/$tmdbId?s=$season&e=$episode&color=FF1493&autoplay=true&autonext=true")
            // Videasy 4K
            serverList.add("https://player.videasy.net/tv/$tmdbId/$season/$episode")
            // VidSrc.to Embed
            serverList.add("https://vidsrc.to/embed/tv/$tmdbId/$season/$episode")
            if (imdbId.isNotBlank()) {
                serverList.add("https://vidsrc.me/embed/tv?imdb=$imdbId&season=$season&episode=$episode")
            }
            serverList.add("https://vidsrc.me/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode")
            serverList.add("https://vidsrc.cc/v2/embed/tv/$tmdbId/$season/$episode")
            serverList.add("https://vidsrc.xyz/embed/tv/$tmdbId/$season/$episode")
            serverList.add("https://vidsrc.pm/embed/tv/$tmdbId/$season/$episode")
            serverList.add("https://vidsrc.pro/embed/tv/$tmdbId/$season/$episode")
            serverList.add("https://vidsrc.vip/embed/tv/$tmdbId/$season/$episode")
            serverList.add("https://vidsrc.sbs/embed/tv/$tmdbId/$season/$episode")
        } else {
            // Pro Multi (vidsrc.sbs primary)
            serverList.add("https://web.nxsha.app/embed/movie/$tmdbId?server=AwsPly-[Multi-Lang]")
            // CineSrc HD
            serverList.add("https://cinesrc.st/embed/movie/$tmdbId")
            // Videasy 4K
            serverList.add("https://player.videasy.net/movie/$tmdbId")
            // VidSrc.to Embed
            serverList.add("https://vidsrc.to/embed/movie/$tmdbId")
            if (imdbId.isNotBlank()) {
                serverList.add("https://vidsrc.me/embed/movie?imdb=$imdbId")
            }
            serverList.add("https://vidsrc.me/embed/movie?tmdb=$tmdbId")
            serverList.add("https://vidsrc.cc/v2/embed/movie/$tmdbId")
            serverList.add("https://vidsrc.xyz/embed/movie/$tmdbId")
            serverList.add("https://vidsrc.pm/embed/movie/$tmdbId")
            serverList.add("https://vidsrc.pro/embed/movie/$tmdbId")
            serverList.add("https://vidsrc.vip/embed/movie/$tmdbId")
            serverList.add("https://vidsrc.sbs/embed/movie/$tmdbId")
        }

        var foundLinks = false

        for (embedUrl in serverList) {
            try {
                if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) {
                    foundLinks = true
                }
            } catch (_: Exception) {}
        }

        return foundLinks
    }
}

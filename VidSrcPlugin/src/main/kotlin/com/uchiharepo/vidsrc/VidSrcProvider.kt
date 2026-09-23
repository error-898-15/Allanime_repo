package com.uchiharepo.vidsrc

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.util.regex.Matcher
import java.util.regex.Pattern

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
        // Official TMDB API credentials and endpoints used by vidsrc.sbs
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

                    if (isTv) {
                        items.add(newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) {
                            this.posterUrl = poster
                        })
                    } else {
                        items.add(newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
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

                    if (isTv) {
                        items.add(newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) {
                            this.posterUrl = poster
                        })
                    } else {
                        items.add(newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
            items
        } catch (e: Exception) {
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
                } catch (e: Exception) {}
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = genres
            }
        } else {
            val payload = "{\"type\":\"movie\",\"tmdbId\":\"$id\",\"imdbId\":\"$imdbId\"}"

            return newMovieLoadResponse(title, url, TvType.Movie, payload) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = genres
            }
        }
    }

    private fun unpackDeanEdwards(script: String): String? {
        return try {
            val regex = Regex("""}('(.*?)',s*(d+),s*(d+),s*'(.*?)'.split('|')""")
            val match = regex.find(script) ?: return null
            var p = match.groupValues[1]
            val a = match.groupValues[2].toIntOrNull() ?: return null
            val c = match.groupValues[3].toIntOrNull() ?: return null
            val k = match.groupValues[4].split("|")

            fun baseN(num: Int, base: Int): String {
                val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                var n = num
                var res = ""
                while (n > 0) {
                    res = chars[n % base] + res
                    n /= base
                }
                return if (res.isEmpty()) "0" else res
            }

            for (i in c - 1 downTo 0) {
                val key = baseN(i, a)
                val value = if (i < k.size && k[i].isNotBlank()) k[i] else key
                p = p.replace(Regex("\b" + Regex.escape(key) + "\b"), Matcher.quoteReplacement(value))
            }
            p
        } catch (e: Exception) {
            null
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
        } catch (e: Exception) {
            return false
        }

        val type = dataNode.path("type").asText("movie")
        val isTv = type == "tv"
        val tmdbId = dataNode.path("tmdbId").asText("")
        val imdbId = dataNode.path("imdbId").asText("")
        val season = dataNode.path("season").asText("1")
        val episode = dataNode.path("episode").asText("1")

        if (tmdbId.isBlank() && imdbId.isBlank()) return false

        var foundLinks = false

        // 1. Direct HLS Stream & Multi-Host Extraction via 2Embed / Streamsrcs
        try {
            val twoEmbedUrl = if (isTv) {
                "https://www.2embed.cc/embedtv/$tmdbId&s=$season&e=$episode"
            } else {
                "https://www.2embed.cc/embed/$tmdbId"
            }

            val twoEmbedRes = app.get(
                twoEmbedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://2embed.cc/"
                )
            )

            if (twoEmbedRes.isSuccessful) {
                val twoEmbedHtml = twoEmbedRes.text

                // 1A. Direct StreamWish & 2vcdn.skin Unpacker
                val swishRegex = Regex("""swish?id=([a-zA-Z0-9]+)""")
                val swishMatch = swishRegex.find(twoEmbedHtml)
                if (swishMatch != null) {
                    val swishId = swishMatch.groupValues[1]

                    // Direct M3U8 Master Extraction from 2vcdn
                    try {
                        val vcdnRes = app.get(
                            "https://2vcdn.skin/e/$swishId",
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://streamsrcs.2embed.cc/"
                            )
                        )
                        if (vcdnRes.isSuccessful) {
                            val vcdnHtml = vcdnRes.text
                            val unpacked = unpackDeanEdwards(vcdnHtml) ?: vcdnHtml
                            val m3u8Regex = Regex("""(https?://[^s"'<>]+.(?:m3u8|txt)[^s"'<>]*)""")
                            val matches = m3u8Regex.findAll(unpacked)
                            for (m in matches) {
                                val streamUrl = m.groupValues[1]
                                callback.invoke(
                                    newExtractorLink(
                                        source = "VidSrc (StreamWish HLS)",
                                        name = "VidSrc Server 1 - 1080p (Multi HLS)",
                                        url = streamUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "https://streamsrcs.2embed.cc/"
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                                foundLinks = true
                            }
                        }
                    } catch (e: Exception) {}

                    // Built-in StreamWish / Flashwish extractors
                    try {
                        for (swUrl in listOf("https://streamwish.to/e/$swishId", "https://awish.pro/e/$swishId", "https://flaswish.com/e/$swishId")) {
                            if (loadExtractor(swUrl, "https://streamsrcs.2embed.cc/", subtitleCallback, callback)) {
                                foundLinks = true
                            }
                        }
                    } catch (e: Exception) {}
                }

                // 1B. Dropdown Servers (Vsrc, Videm, Vcr)
                val goRegex = Regex("""onclick=["']go(['"]([^'"]+)['"])""")
                for (gm in goRegex.findAll(twoEmbedHtml)) {
                    val targetUrl = gm.groupValues[1]
                    if (targetUrl.startsWith("http")) {
                        try {
                            if (loadExtractor(targetUrl, "https://2embed.cc/", subtitleCallback, callback)) {
                                foundLinks = true
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {}

        // 2. Official VidSrc.sbs Target Site Scraper
        try {
            val vidsrcSbsUrl = if (isTv) {
                "$mainUrl/embed/tv/$tmdbId/$season/$episode"
            } else {
                "$mainUrl/embed/movie/$tmdbId"
            }

            val sbsRes = app.get(
                vidsrcSbsUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            )

            if (sbsRes.isSuccessful) {
                val sbsHtml = sbsRes.text
                val srvJsonRegex = Regex("""servers:s*([{.*?}])s*[,;]""", RegexOption.DOT_MATCHES_ALL)
                val srvMatch = srvJsonRegex.find(sbsHtml)
                if (srvMatch != null) {
                    val serversTree = mapper.readTree(srvMatch.groupValues[1])
                    if (serversTree.isArray) {
                        for (srvNode in serversTree) {
                            val srvName = srvNode.path("name").asText("Server")
                            val tpl = if (isTv) srvNode.path("tv_url").asText("") else srvNode.path("movie_url").asText("")
                            if (tpl.isBlank()) continue
                            val embedUrl = tpl.replace("{tmdb_id}", tmdbId)
                                .replace("{season}", season)
                                .replace("{episode}", episode)

                            try {
                                if (loadExtractor(embedUrl, vidsrcSbsUrl, subtitleCallback, callback)) {
                                    foundLinks = true
                                }
                            } catch (e: Exception) {}

                            // Direct iframe/m3u8 scraper for nested servers
                            try {
                                val pageRes = app.get(
                                    embedUrl,
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to vidsrcSbsUrl
                                    )
                                )
                                if (pageRes.isSuccessful) {
                                    val pageText = pageRes.text
                                    val m3u8Matches = Regex("""(https?://[^s"'<>]+.m3u8[^s"'<>]*)""").findAll(pageText)
                                    for (m in m3u8Matches) {
                                        callback.invoke(
                                            newExtractorLink(
                                                source = "VidSrc ($srvName)",
                                                name = "VidSrc $srvName (Auto)",
                                                url = m.groupValues[1],
                                                type = ExtractorLinkType.M3U8
                                            ) {
                                                this.referer = embedUrl
                                                this.quality = Qualities.P1080.value
                                            }
                                        )
                                        foundLinks = true
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        // 3. Fallback Multi-Server Direct Cluster
        val fallbackUrls = mutableListOf<String>()
        if (isTv) {
            fallbackUrls.add("https://vidsrc.buzz/embed/tv/$tmdbId/$season/$episode")
            fallbackUrls.add("https://videm.xyz/embed/tv/$tmdbId/$season/$episode")
            fallbackUrls.add("https://streamsrcs.2embed.cc/vcr-tv?tmdb=$tmdbId&s=$season&e=$episode")
            fallbackUrls.add("https://autoembed.co/tv/tmdb/$tmdbId-$season-$episode")
            fallbackUrls.add("https://vidlink.pro/tv/$tmdbId/$season/$episode")
            fallbackUrls.add("https://cinesrc.st/embed/tv/$tmdbId?s=$season&e=$episode")
            fallbackUrls.add("https://player.videasy.net/tv/$tmdbId/$season/$episode")
            fallbackUrls.add("https://vidsrc.to/embed/tv/$tmdbId/$season/$episode")
            fallbackUrls.add("https://vidsrc.me/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode")
        } else {
            fallbackUrls.add("https://vidsrc.buzz/embed/movie/$tmdbId")
            fallbackUrls.add("https://videm.xyz/embed/movie/$tmdbId")
            fallbackUrls.add("https://streamsrcs.2embed.cc/vcr?tmdb=$tmdbId")
            fallbackUrls.add("https://autoembed.co/movie/tmdb/$tmdbId")
            fallbackUrls.add("https://vidlink.pro/movie/$tmdbId")
            fallbackUrls.add("https://cinesrc.st/embed/movie/$tmdbId")
            fallbackUrls.add("https://player.videasy.net/movie/$tmdbId")
            fallbackUrls.add("https://vidsrc.to/embed/movie/$tmdbId")
            fallbackUrls.add("https://vidsrc.me/embed/movie?tmdb=$tmdbId")
        }

        for (fUrl in fallbackUrls) {
            try {
                if (loadExtractor(fUrl, mainUrl, subtitleCallback, callback)) {
                    foundLinks = true
                }
            } catch (e: Exception) {}
        }

        return foundLinks
    }
}

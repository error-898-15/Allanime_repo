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
import org.jsoup.Jsoup
import java.net.URLEncoder

class FlixVisionProvider : MainAPI() {
    override var mainUrl = "https://api.themoviedb.org/3"
    override var name = "Flix Vision"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    companion object {
        // Official TMDB API Key extracted from Flix Vision APK v3.8
        const val TMDB_API_KEY = "2f3cb5763db1117fcba3948632f8aad9"
        const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
        const val TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/original"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        const val GOOGLE_REFERER = "https://www.google.com/"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/trending/all/week" to "Trending This Week",
        "$mainUrl/movie/popular" to "Popular Movies",
        "$mainUrl/tv/popular" to "Popular Series",
        "$mainUrl/discover/movie?with_original_language=hi|ta|te|ml|bn&sort_by=popularity.desc" to "Bollywood & Regional Movies",
        "$mainUrl/discover/tv?with_original_language=ko|ja|zh&sort_by=popularity.desc" to "Asian Dramas & Anime",
        "$mainUrl/movie/top_rated" to "Top Rated Movies",
        "$mainUrl/tv/top_rated" to "Top Rated Series",
        "$mainUrl/movie/now_playing" to "Now Playing Movies",
        "$mainUrl/tv/on_the_air" to "On The Air Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val connector = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${connector}api_key=$TMDB_API_KEY&page=$page"
        val response = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).text
        val tmdbPage = parseJson<TmdbPageResult>(response)

        val items = tmdbPage.results.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
            val isMovie = item.mediaType == "movie" || (item.mediaType == null && request.data.contains("/movie"))
            val poster = item.posterPath?.let { "$TMDB_IMAGE_BASE$it" }

            if (isMovie) {
                newMovieSearchResponse(title, "/movie/$id", TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, "/tv/$id", TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/search/multi?api_key=$TMDB_API_KEY&query=$encodedQuery&include_adult=false"
        val response = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
        val tmdbResult = parseJson<TmdbPageResult>(response)

        return tmdbResult.results.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
            val isMovie = item.mediaType == "movie" || item.title != null
            val poster = item.posterPath?.let { "$TMDB_IMAGE_BASE$it" }

            if (isMovie) {
                newMovieSearchResponse(title, "/movie/$id", TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, "/tv/$id", TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val isMovie = url.contains("/movie/")
        val id = url.substringAfter("/movie/").substringAfter("/tv/").substringBefore("?").trim().toIntOrNull()
            ?: return null
        val type = if (isMovie) "movie" else "tv"
        val detailUrl = "$mainUrl/$type/$id?api_key=$TMDB_API_KEY&append_to_response=credits,recommendations,external_ids"
        val response = app.get(detailUrl, headers = mapOf("User-Agent" to USER_AGENT)).text

        if (isMovie) {
            val movie = parseJson<TmdbMovieDetail>(response)
            val title = movie.title ?: movie.originalTitle ?: "FlixVision Movie"
            val poster = movie.posterPath?.let { "$TMDB_IMAGE_BASE$it" }
            val backdrop = movie.backdropPath?.let { "$TMDB_BACKDROP_BASE$it" }
            val year = movie.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            val tags = movie.genres?.mapNotNull { it.name }
            val rating = movie.voteAverage?.let { (it * 10).toInt() }
            val imdbId = movie.externalIds?.imdbId

            val linkData = FlixVisionLinkData(
                id = id,
                imdbId = imdbId,
                isMovie = true,
                title = title,
                year = year
            ).toJson()

            return newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = movie.overview
                this.rating = rating
                this.tags = tags
                this.duration = movie.runtime
                this.actors = movie.credits?.cast?.take(15)?.mapNotNull { cast ->
                    cast.name?.let { actorName ->
                        ActorData(
                            Actor(actorName, cast.profilePath?.let { "$TMDB_IMAGE_BASE$it" }),
                            roleString = cast.character
                        )
                    }
                }
                this.recommendations = movie.recommendations?.results?.mapNotNull { rec ->
                    val recId = rec.id ?: return@mapNotNull null
                    val recTitle = rec.title ?: rec.name ?: return@mapNotNull null
                    newMovieSearchResponse(recTitle, "/movie/$recId", TvType.Movie) {
                        this.posterUrl = rec.posterPath?.let { "$TMDB_IMAGE_BASE$it" }
                    }
                }
            }
        } else {
            val tv = parseJson<TmdbTvDetail>(response)
            val title = tv.name ?: tv.originalName ?: "FlixVision Series"
            val poster = tv.posterPath?.let { "$TMDB_IMAGE_BASE$it" }
            val backdrop = tv.backdropPath?.let { "$TMDB_BACKDROP_BASE$it" }
            val year = tv.firstAirDate?.split("-")?.firstOrNull()?.toIntOrNull()
            val tags = tv.genres?.mapNotNull { it.name }
            val rating = tv.voteAverage?.let { (it * 10).toInt() }
            val imdbId = tv.externalIds?.imdbId

            val episodes = mutableListOf<Episode>()
            tv.seasons?.filter { (it.seasonNumber ?: 0) > 0 }?.forEach { season ->
                val sNum = season.seasonNumber ?: return@forEach
                try {
                    val seasonUrl = "$mainUrl/tv/$id/season/$sNum?api_key=$TMDB_API_KEY"
                    val seasonRes = app.get(seasonUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
                    val seasonData = parseJson<TmdbSeasonDetail>(seasonRes)

                    seasonData.episodes?.forEach { ep ->
                        val epNum = ep.episodeNumber ?: return@forEach
                        val epTitle = ep.name ?: "Episode $epNum"
                        val epLinkData = FlixVisionLinkData(
                            id = id,
                            imdbId = imdbId,
                            season = sNum,
                            episode = epNum,
                            isMovie = false,
                            title = title,
                            year = year
                        ).toJson()

                        episodes.add(
                            newEpisode(epLinkData) {
                                this.name = epTitle
                                this.season = sNum
                                this.episode = epNum
                                this.posterUrl = ep.stillPath?.let { "$TMDB_IMAGE_BASE$it" }
                                this.description = ep.overview
                                this.rating = ep.voteAverage?.let { (it * 10).toInt() }
                            }
                        )
                    }
                } catch (_: Exception) {
                    // Continue with other seasons if one fails
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = tv.overview
                this.rating = rating
                this.tags = tags
                this.actors = tv.credits?.cast?.take(15)?.mapNotNull { cast ->
                    cast.name?.let { actorName ->
                        ActorData(
                            Actor(actorName, cast.profilePath?.let { "$TMDB_IMAGE_BASE$it" }),
                            roleString = cast.character
                        )
                    }
                }
                this.recommendations = tv.recommendations?.results?.mapNotNull { rec ->
                    val recId = rec.id ?: return@mapNotNull null
                    val recTitle = rec.name ?: rec.title ?: return@mapNotNull null
                    newTvSeriesSearchResponse(recTitle, "/tv/$recId", TvType.TvSeries) {
                        this.posterUrl = rec.posterPath?.let { "$TMDB_IMAGE_BASE$it" }
                    }
                }
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
            parseJson<FlixVisionLinkData>(data)
        } catch (_: Exception) {
            return false
        }

        val tmdbId = linkData.id
        val imdbId = linkData.imdbId
        val season = linkData.season ?: 1
        val episode = linkData.episode ?: 1
        val isMovie = linkData.isMovie
        val title = linkData.title ?: ""
        var loadedAny = false

        // 1. [FLIXVISION 1] - SmashyStream Server & Direct
        val smashyUrls = if (isMovie) {
            listOf(
                "https://embed.smashystream.com/playere.php?tmdb=$tmdbId",
                "https://smashystream.xyz/playere.php?tmdb=$tmdbId"
            )
        } else {
            listOf(
                "https://embed.smashystream.com/playere.php?tmdb=$tmdbId&season=$season&episode=$episode",
                "https://smashystream.xyz/playere.php?tmdb=$tmdbId&season=$season&episode=$episode"
            )
        }
        for (url in smashyUrls) {
            try {
                if (loadExtractor(url, "https://embed.smashystream.com/", subtitleCallback, callback)) loadedAny = true
                extractEmbeddedStreams(url, "1080p - 720p - 480p - [FLIXVISION1]", subtitleCallback, callback)
            } catch (_: Exception) { }
        }

        // 2. [FLIXVISION 2] - MultiEmbed DirectStream
        val multiEmbedUrl = if (isMovie) {
            "https://multiembed.mov/directstream.php?video_id=$tmdbId&tmdb=1"
        } else {
            "https://multiembed.mov/directstream.php?video_id=$tmdbId&tmdb=1&s=$season&e=$episode"
        }
        try {
            if (loadExtractor(multiEmbedUrl, "https://multiembed.mov/", subtitleCallback, callback)) loadedAny = true
            extractEmbeddedStreams(multiEmbedUrl, "1080p - 720p - 480p [FLIXVISION2]", subtitleCallback, callback)
        } catch (_: Exception) { }

        // 3. [FLIXVISION 3] - AutoEmbed & 2Embed VIP
        val autoEmbedUrl = if (isMovie) {
            "https://autoembed.co/movie/tmdb/$tmdbId"
        } else {
            "https://autoembed.co/tv/tmdb/$tmdbId-$season-$episode"
        }
        try {
            if (loadExtractor(autoEmbedUrl, "https://autoembed.co/", subtitleCallback, callback)) loadedAny = true
            extractEmbeddedStreams(autoEmbedUrl, "1080p - 720p - 480p-[FLIXVISION3]", subtitleCallback, callback)
        } catch (_: Exception) { }

        val twoEmbedUrls = mutableListOf<String>()
        if (isMovie) {
            twoEmbedUrls.add("https://www.2embed.cc/embed/$tmdbId")
            if (!imdbId.isNullOrBlank()) twoEmbedUrls.add("https://www.2embed.cc/embed/$imdbId")
        } else {
            twoEmbedUrls.add("https://www.2embed.cc/embedtv/$tmdbId&s=$season&e=$episode")
            if (!imdbId.isNullOrBlank()) twoEmbedUrls.add("https://www.2embed.cc/embedtv/$imdbId&s=$season&e=$episode")
        }
        for (u2 in twoEmbedUrls) {
            try {
                if (loadExtractor(u2, "https://www.2embed.cc/", subtitleCallback, callback)) loadedAny = true
                extractEmbeddedStreams(u2, "1080p - 720p - 480p-[FLIXVISION3] 2Embed", subtitleCallback, callback)
            } catch (_: Exception) { }
        }

        // 4. [FLIXVISION 5] - VSEmbed (Cloudnestra)
        val vsEmbedUrl = if (isMovie) {
            "https://vsembed.ru/embed/movie/$tmdbId"
        } else {
            "https://vsembed.ru/embed/tv/$tmdbId/$season/$episode"
        }
        try {
            if (loadExtractor(vsEmbedUrl, "https://vsembed.ru/", subtitleCallback, callback)) loadedAny = true
            extractEmbeddedStreams(vsEmbedUrl, "1080p - 720p - 480p [FLIXVISION5]", subtitleCallback, callback)
        } catch (_: Exception) { }

        // 5. [VIDSRC] - VidSrc Multi-Mirror
        val vidsrcUrls = mutableListOf<String>()
        if (isMovie) {
            vidsrcUrls.add("https://vidsrc.to/embed/movie/$tmdbId")
            vidsrcUrls.add("https://vidsrc.me/embed/movie?tmdb=$tmdbId")
            vidsrcUrls.add("https://vidsrc-embed.ru/embed/movie/$tmdbId")
            vidsrcUrls.add("https://vidsrc.xyz/embed/movie/$tmdbId")
            vidsrcUrls.add("https://vidsrc.in/embed/movie/$tmdbId")
            vidsrcUrls.add("https://vidsrc.pm/embed/movie/$tmdbId")
            if (!imdbId.isNullOrBlank()) vidsrcUrls.add("https://vidsrc.me/embed/movie?imdb=$imdbId")
        } else {
            vidsrcUrls.add("https://vidsrc.to/embed/tv/$tmdbId/$season/$episode")
            vidsrcUrls.add("https://vidsrc.me/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode")
            vidsrcUrls.add("https://vidsrc-embed.ru/embed/tv/$tmdbId/$season/$episode")
            vidsrcUrls.add("https://vidsrc.xyz/embed/tv/$tmdbId/$season/$episode")
            vidsrcUrls.add("https://vidsrc.in/embed/tv/$tmdbId/$season/$episode")
            vidsrcUrls.add("https://vidsrc.pm/embed/tv/$tmdbId/$season/$episode")
            if (!imdbId.isNullOrBlank()) vidsrcUrls.add("https://vidsrc.me/embed/tv?imdb=$imdbId&season=$season&episode=$episode")
        }
        for (vUrl in vidsrcUrls) {
            try {
                if (loadExtractor(vUrl, "https://vidsrc.to/", subtitleCallback, callback)) loadedAny = true
                extractEmbeddedStreams(vUrl, "1080p - [VIDSRC] - [DIRECT]", subtitleCallback, callback)
            } catch (_: Exception) { }
        }

        // 6. [FVSTREAM 1] - AllMovieLand (English & Hindi Dubbed Direct Streams)
        try {
            val queryClean = title.replace(Regex("""[^a-zA-Z0-9\s]"""), " ").trim()
            val allMovieLandUrls = listOf("https://allmovieland.you", "https://allmovieland.fun")
            for (domain in allMovieLandUrls) {
                try {
                    val searchUrl = "$domain/?s=${URLEncoder.encode(queryClean, "UTF-8")}"
                    val searchDoc = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to GOOGLE_REFERER)).document
                    val targetLink = searchDoc.select("a[href*='/play/'], a[href*='/movie/'], .ml-item a").firstOrNull()?.attr("href")
                    if (!targetLink.isNullOrBlank()) {
                        val playDoc = app.get(targetLink, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to domain)).document
                        playDoc.select("iframe[src]").forEach { iframe ->
                            val src = iframe.attr("src")
                            val cleanSrc = if (src.startsWith("//")) "https:$src" else src
                            loadExtractor(cleanSrc, domain, subtitleCallback, callback)
                        }
                        extractEmbeddedStreams(targetLink, "1080p - [FVSTREAM 1] · [DIRECT] · Hindi/English", subtitleCallback, callback)
                        break
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // 7. [FVSTREAM 2] - RidoMovies / Closeload Direct
        try {
            val ridoUrls = listOf(
                "https://ridomovies.tv/movies/${title.lowercase().replace(" ", "-")}",
                "https://closeload.top/embed/$tmdbId"
            )
            for (rUrl in ridoUrls) {
                try {
                    if (loadExtractor(rUrl, "https://closeload.top/", subtitleCallback, callback)) loadedAny = true
                    extractEmbeddedStreams(rUrl, "1080p · [FVSTREAM 2] · [DIRECT] · English", subtitleCallback, callback)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // 8. [FVSTREAM 3] - VixCloud & StreamingUnity Direct
        try {
            val vixUrls = listOf(
                "https://vixcloud.co/playlist/$tmdbId",
                "https://streamingunity.dog/en/search?q=${URLEncoder.encode(title, "UTF-8")}"
            )
            for (vix in vixUrls) {
                try {
                    if (loadExtractor(vix, "https://vixcloud.co/", subtitleCallback, callback)) loadedAny = true
                    extractEmbeddedStreams(vix, "1080p · [FVSTREAM 3] · [DIRECT] · English", subtitleCallback, callback)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // 9. [FVSTREAM 4 & 5] - Movies123 & Noxx TV
        try {
            val m123Url = "https://movies123.pk/?s=${URLEncoder.encode(title, "UTF-8")}"
            extractEmbeddedStreams(m123Url, "1080p - [FVSTREAM 4] · [DIRECT] · English", subtitleCallback, callback)
        } catch (_: Exception) { }

        try {
            val noxxUrl = if (isMovie) "https://noxx.to/movie/${title.lowercase().replace(" ", "-")}" else "https://noxx.to/tv/${title.lowercase().replace(" ", "-")}/season/$season/episode/$episode"
            extractEmbeddedStreams(noxxUrl, "1080p · [FVSTREAM 5] · [DIRECT] · English", subtitleCallback, callback)
        } catch (_: Exception) { }

        // 10. [MOFLIX] - Direct Stream (English / German Multi-Audio)
        try {
            val moflixUrl = if (isMovie) {
                "https://moflix-stream.xyz/api/v1/titles/tmdb|movie|$tmdbId?loader=titlePage"
            } else {
                "https://moflix-stream.xyz/api/v1/titles/tmdb|series|$tmdbId?loader=titlePage"
            }
            val res = app.get(moflixUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://moflix-stream.xyz/")).text
            extractStreamsFromJson(res, "1080p - [MOFLIX] - [DIRECT] - English/Multi", callback)
        } catch (_: Exception) { }

        // 11. [FLIXVISION HINDI 1] - HindiLinks4U (Hindi & Bollywood Audio)
        try {
            val hindiQuery = URLEncoder.encode(title, "UTF-8")
            val hindiLinksHosts = listOf("https://hindilinks4u.guru", "https://hindilinks4u.cam", "https://hindilinks4u.to")
            for (hHost in hindiLinksHosts) {
                try {
                    val searchUrl = "$hHost/?s=$hindiQuery"
                    val searchDoc = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to GOOGLE_REFERER)).document
                    val movieHref = searchDoc.select(".ml-item a, article a, h2 a").firstOrNull()?.attr("href")
                    if (!movieHref.isNullOrBlank()) {
                        val movieDoc = app.get(movieHref, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to hHost)).document
                        movieDoc.select("iframe[src], .movieplay iframe").forEach { iframe ->
                            val src = iframe.attr("src")
                            val cleanSrc = if (src.startsWith("//")) "https:$src" else src
                            loadExtractor(cleanSrc, hHost, subtitleCallback, callback)
                        }
                        extractEmbeddedStreams(movieHref, "1080p - [FLIXVISION HINDI 1] HindiLinks4U", subtitleCallback, callback)
                        break
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // 12. [FLIXVISION HINDI 2] - HindiMoviesTV (Bollywood & Dual Audio)
        try {
            val hmtvUrl = "https://www.hindimoviestv.com/?s=${URLEncoder.encode(title, "UTF-8")}"
            val searchDoc = app.get(hmtvUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to GOOGLE_REFERER)).document
            val itemHref = searchDoc.select(".ml-item a, article a").firstOrNull()?.attr("href")
            if (!itemHref.isNullOrBlank()) {
                val detailDoc = app.get(itemHref, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://www.hindimoviestv.com/")).document
                detailDoc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src")
                    val cleanSrc = if (src.startsWith("//")) "https:$src" else src
                    loadExtractor(cleanSrc, "https://www.hindimoviestv.com/", subtitleCallback, callback)
                }
                extractEmbeddedStreams(itemHref, "1080p - [FLIXVISION HINDI 2] HindiMoviesTV", subtitleCallback, callback)
            }
        } catch (_: Exception) { }

        // 13. [FLIXVISION REGIONAL] - MovieRulz (Tamil, Telugu, Malayalam, Kannada, Bengali)
        try {
            val mrMirrors = listOf("https://ww9.watchmovierulz.ws", "https://www.movierulz.cr", "https://movierulz.com.ci")
            for (mrHost in mrMirrors) {
                try {
                    val searchUrl = "$mrHost/search_movies?s=${URLEncoder.encode(title, "UTF-8")}"
                    val doc = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to GOOGLE_REFERER)).document
                    val movieLink = doc.select(".cont_display a, article a, .boxed a").firstOrNull()?.attr("href")
                    if (!movieLink.isNullOrBlank()) {
                        val pageDoc = app.get(movieLink, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mrHost)).document
                        pageDoc.select("iframe[src]").forEach { iframe ->
                            val src = iframe.attr("src")
                            val cleanSrc = if (src.startsWith("//")) "https:$src" else src
                            loadExtractor(cleanSrc, mrHost, subtitleCallback, callback)
                        }
                        extractEmbeddedStreams(movieLink, "1080p - [FLIXVISION MULTI] MovieRulz (Regional)", subtitleCallback, callback)
                        break
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // 14. [FLIXVISION ASIAN] - KissAsian (Asian Dramas)
        try {
            val kissHosts = listOf("https://kissasiantv.to", "https://kissasian.pe")
            for (kHost in kissHosts) {
                try {
                    val searchUrl = "$kHost/search?keyword=${URLEncoder.encode(title, "UTF-8")}"
                    val doc = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to GOOGLE_REFERER)).document
                    val dramaLink = doc.select(".barContentgenres a, .item a").firstOrNull()?.attr("href")
                    if (!dramaLink.isNullOrBlank()) {
                        val fullLink = if (dramaLink.startsWith("http")) dramaLink else "$kHost$dramaLink"
                        extractEmbeddedStreams(fullLink, "1080p - [FLIXVISION ASIAN] KissAsian", subtitleCallback, callback)
                        break
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        return loadedAny
    }

    private suspend fun extractEmbeddedStreams(
        pageUrl: String,
        sourcePrefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(pageUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to pageUrl))
            val text = res.text
            val doc = res.document

            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                val clean = if (src.startsWith("//")) "https:$src" else src
                if (clean.startsWith("http")) {
                    try {
                        loadExtractor(clean, pageUrl, subtitleCallback, callback)
                    } catch (_: Exception) { }
                }
            }

            val m3u8Regex = Regex("""https?://[^"'<>\s]+\.m3u8[^"'<>\s]*""")
            m3u8Regex.findAll(text).forEach { match ->
                val streamUrl = match.value
                callback.invoke(
                    newExtractorLink(
                        source = sourcePrefix,
                        name = "$sourcePrefix (HLS)",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.P1080.value
                    }
                )
            }

            val mp4Regex = Regex("""https?://[^"'<>\s]+\.(?:mp4|mkv)[^"'<>\s]*""")
            mp4Regex.findAll(text).forEach { match ->
                val streamUrl = match.value
                callback.invoke(
                    newExtractorLink(
                        source = sourcePrefix,
                        name = "$sourcePrefix (Direct)",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (_: Exception) { }
    }

    private fun extractStreamsFromJson(
        jsonString: String,
        sourcePrefix: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val streamRegex = Regex("""https?://[^"'<>\s]+\.(?:m3u8|mp4)[^"'<>\s]*""")
            streamRegex.findAll(jsonString).forEach { match ->
                val url = match.value
                val isHls = url.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = sourcePrefix,
                        name = "$sourcePrefix (${if (isHls) "HLS" else "MP4"})",
                        url = url,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (_: Exception) { }
    }
}

data class FlixVisionLinkData(
    @JsonProperty("id") val id: Int,
    @JsonProperty("imdbId") val imdbId: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("isMovie") val isMovie: Boolean = true,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null
)

data class TmdbPageResult(
    @JsonProperty("results") val results: List<TmdbItem> = emptyList(),
    @JsonProperty("page") val page: Int = 1,
    @JsonProperty("total_pages") val totalPages: Int = 1
)

data class TmdbItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null
)

data class TmdbMovieDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
    @JsonProperty("external_ids") val externalIds: TmdbExternalIds? = null,
    @JsonProperty("credits") val credits: TmdbCredits? = null,
    @JsonProperty("recommendations") val recommendations: TmdbPageResult? = null
)

data class TmdbTvDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
    @JsonProperty("seasons") val seasons: List<TmdbSeason>? = null,
    @JsonProperty("external_ids") val externalIds: TmdbExternalIds? = null,
    @JsonProperty("credits") val credits: TmdbCredits? = null,
    @JsonProperty("recommendations") val recommendations: TmdbPageResult? = null
)

data class TmdbSeasonDetail(
    @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null
)

data class TmdbSeason(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("name") val name: String? = null
)

data class TmdbEpisode(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null
)

data class TmdbGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null
)

data class TmdbExternalIds(
    @JsonProperty("imdb_id") val imdbId: String? = null
)

data class TmdbCredits(
    @JsonProperty("cast") val cast: List<TmdbCast>? = null
)

data class TmdbCast(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null
)

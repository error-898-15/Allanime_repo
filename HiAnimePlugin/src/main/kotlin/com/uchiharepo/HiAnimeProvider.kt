package com.uchiharepo

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addDub
import com.lagradost.cloudstream3.LoadResponse.Companion.addSub
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.regex.Pattern

class HiAnimeProvider : MainAPI() {
    override var mainUrl = "https://hianimes.se"
    override var name = "HiAnime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val API_URL = "https://animehot.cc/api"
        private const val FALLBACK_API_URL = "https://anitv.cfd/api"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
        private const val ZOKO_DECRYPT_KEY = "otaku-embed-v1"
        private val DEFAULT_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "https://hianimes.se/"
        )
    }

    override val mainPage = mainPageOf(
        "$API_URL/anime/trending" to "Trending Anime",
        "$API_URL/anime/popular" to "Most Popular",
        "$API_URL/latest/anime" to "Latest Anime",
        "$API_URL/anime/upcoming" to "Upcoming Anime",
        "$API_URL/latest/episode" to "Latest Episodes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        val requestUrl = if (request.data.contains("latest/episode")) {
            "${request.data}?page=$page&limit=20"
        } else {
            request.data
        }

        val jsonText = try {
            app.get(requestUrl, headers = DEFAULT_HEADERS).text
        } catch (e: Exception) {
            val fallbackUrl = requestUrl.replace(API_URL, FALLBACK_API_URL)
            try {
                app.get(fallbackUrl, headers = DEFAULT_HEADERS).text
            } catch (e2: Exception) {
                null
            }
        }

        if (!jsonText.isNullOrBlank()) {
            try {
                if (request.data.contains("/latest/episode")) {
                    val wrapper = parseJson<LatestEpisodesWrapper>(jsonText)
                    wrapper.episodes?.forEach { ep ->
                        ep.animeInfo?.toSearchResponse()?.let { items.add(it) }
                    }
                } else if (request.data.contains("/anime/upcoming")) {
                    val wrapper = parseJson<UpcomingWrapper>(jsonText)
                    wrapper.data?.forEach { item ->
                        item.toSearchResponse()?.let { items.add(it) }
                    }
                } else {
                    val wrapper = parseJson<AnimeListWrapper>(jsonText)
                    wrapper.animes?.forEach { item ->
                        item.toSearchResponse()?.let { items.add(it) }
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors on empty or modified payloads
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val searchBody = mapOf("title" to trimmed)
        val jsonText = try {
            app.post(
                "$API_URL/search",
                headers = DEFAULT_HEADERS,
                json = searchBody
            ).text
        } catch (e: Exception) {
            try {
                app.post(
                    "$FALLBACK_API_URL/search",
                    headers = DEFAULT_HEADERS,
                    json = searchBody
                ).text
            } catch (e2: Exception) {
                null
            }
        } ?: return emptyList()

        return try {
            val list = parseJson<List<SearchItem>>(jsonText)
            list.mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        val titleText = this.title ?: this.english ?: this.japanese ?: return null
        val itemSlug = this.slug ?: this.slugs?.filterNotNull()?.firstOrNull() ?: return null
        val poster = this.image ?: this.landScapeImage
        val isMovie = this.type?.equals("Movie", ignoreCase = true) == true
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(
            name = titleText,
            url = "$mainUrl/details/$itemSlug",
            type = tvType
        ) {
            this.posterUrl = poster
            this.addSub(this@toSearchResponse.totalSubbed)
            this.addDub(this@toSearchResponse.totalDubbed)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.removeSuffix("/").substringAfterLast("/")
        val cleanSlug = if (slug.contains("?")) slug.substringBefore("?") else slug

        val jsonText = try {
            app.get("$API_URL/anime/$cleanSlug", headers = DEFAULT_HEADERS).text
        } catch (e: Exception) {
            try {
                app.get("$FALLBACK_API_URL/anime/$cleanSlug", headers = DEFAULT_HEADERS).text
            } catch (e2: Exception) {
                null
            }
        } ?: return null

        val detailWrapper = try {
            parseJson<AnimeDetailWrapper>(jsonText)
        } catch (e: Exception) {
            return null
        }
        val anime = detailWrapper.anime ?: return null

        val title = anime.title ?: anime.english ?: anime.japanese ?: "Anime"
        val poster = anime.image ?: anime.landScapeImage
        val backdrop = anime.landScapeImage ?: anime.image
        val plot = anime.synopsis
        val year = anime.aired?.take(4)?.toIntOrNull()
            ?: anime.premiered?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val ratingInt = anime.score?.toDoubleOrNull()?.let { (it * 1000).toInt() }
            ?: anime.scoreAlt?.toDoubleOrNull()?.let { (it * 1000).toInt() }
        val genres = anime.genres
        val showStatus = when (anime.status?.lowercase()) {
            "currently airing" -> ShowStatus.Ongoing
            "finished airing" -> ShowStatus.Completed
            else -> null
        }

        var episodeList = anime.episodes.orEmpty()
        // If episodes list is empty in detail response, attempt to fetch range
        if (episodeList.isEmpty() && !anime.id.isNullOrBlank()) {
            try {
                val epJson = app.get(
                    "$API_URL/episodes/${anime.id}?start=1&end=1000",
                    headers = DEFAULT_HEADERS
                ).text
                val epResponse = parseJson<EpisodesRangeResponse>(epJson)
                episodeList = epResponse.episodes.orEmpty()
            } catch (e: Exception) {
                // Continue with what we have
            }
        }

        val isMovie = anime.type?.equals("Movie", ignoreCase = true) == true &&
                (anime.totalEpisodes == 1 || episodeList.size <= 1)

        if (isMovie) {
            val firstEp = episodeList.firstOrNull()
            val passData = EpisodePassData(
                title = title,
                episodeNumber = 1,
                sub = firstEp?.link?.sub.orEmpty(),
                dub = firstEp?.link?.dub.orEmpty(),
                animeId = anime.id,
                malId = anime.malId
            ).toJson()

            return newMovieLoadResponse(title, url, TvType.AnimeMovie, passData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.rating = ratingInt
                this.tags = genres
                this.addDub(anime.totalDubbed)
                this.addSub(anime.totalSubbed)
            }
        }

        val episodes = ArrayList<Episode>()
        if (episodeList.isNotEmpty()) {
            episodeList.forEachIndexed { index, ep ->
                val epNum = ep.episodeNumber ?: (index + 1)
                val epTitle = ep.title ?: "Episode $epNum"
                val passData = EpisodePassData(
                    title = epTitle,
                    episodeNumber = epNum,
                    sub = ep.link?.sub.orEmpty(),
                    dub = ep.link?.dub.orEmpty(),
                    animeId = anime.id,
                    malId = anime.malId
                ).toJson()

                episodes.add(
                    newEpisode(passData) {
                        this.name = epTitle
                        this.episode = epNum
                        this.posterUrl = poster
                    }
                )
            }
        } else {
            val passData = EpisodePassData(
                title = "Episode 1",
                episodeNumber = 1,
                sub = emptyList(),
                dub = emptyList(),
                animeId = anime.id,
                malId = anime.malId
            ).toJson()

            episodes.add(
                newEpisode(passData) {
                    this.name = "Episode 1"
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.rating = ratingInt
            this.tags = genres
            this.showStatus = showStatus
            this.addDub(anime.totalDubbed)
            this.addSub(anime.totalSubbed)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val passData = try {
            parseJson<EpisodePassData>(data)
        } catch (e: Exception) {
            return false
        }

        var subLinks = passData.sub.orEmpty()
        var dubLinks = passData.dub.orEmpty()

        // Fallback: If links are missing, query the episodes range endpoint
        if (subLinks.isEmpty() && dubLinks.isEmpty() && !passData.animeId.isNullOrBlank()) {
            try {
                val epNum = passData.episodeNumber ?: 1
                val epJson = app.get(
                    "$API_URL/episodes/${passData.animeId}?start=$epNum&end=$epNum",
                    headers = DEFAULT_HEADERS
                ).text
                val rangeRes = parseJson<EpisodesRangeResponse>(epJson)
                val targetEp = rangeRes.episodes?.firstOrNull { it.episodeNumber == epNum }
                    ?: rangeRes.episodes?.firstOrNull()
                if (targetEp != null) {
                    subLinks = targetEp.link?.sub.orEmpty()
                    dubLinks = targetEp.link?.dub.orEmpty()
                }
            } catch (e: Exception) {
                // Ignore fallback error
            }
        }

        var anyFound = false

        // 1. Process SUB servers (HiAnime Sub 1, Sub 2, etc.)
        subLinks.forEachIndexed { index, linkUrl ->
            val trimmedUrl = linkUrl.trim()
            if (trimmedUrl.isBlank()) return@forEachIndexed
            val serverNum = index + 1
            val serverName = if (trimmedUrl.contains("zokoanime.video")) {
                "HiAnime - Sub $serverNum (Zoko)"
            } else if (trimmedUrl.contains("megaplay.buzz")) {
                "HiAnime - Sub $serverNum (MegaPlay)"
            } else {
                "HiAnime - Sub $serverNum"
            }

            if (trimmedUrl.contains("zokoanime.video")) {
                val extracted = extractZokoStream(
                    streamUrl = trimmedUrl,
                    serverName = serverName,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                if (extracted) anyFound = true
            } else {
                try {
                    loadExtractor(trimmedUrl, "$mainUrl/", subtitleCallback, callback)
                    anyFound = true
                } catch (e: Exception) {
                    // Ignored
                }
            }
        }

        // 2. Process DUB servers (HiAnime Dub 1, Dub 2, etc.)
        dubLinks.forEachIndexed { index, linkUrl ->
            val trimmedUrl = linkUrl.trim()
            if (trimmedUrl.isBlank()) return@forEachIndexed
            val serverNum = index + 1
            val serverName = if (trimmedUrl.contains("zokoanime.video")) {
                "HiAnime - Dub $serverNum (Zoko)"
            } else if (trimmedUrl.contains("megaplay.buzz")) {
                "HiAnime - Dub $serverNum (MegaPlay)"
            } else {
                "HiAnime - Dub $serverNum"
            }

            if (trimmedUrl.contains("zokoanime.video")) {
                val extracted = extractZokoStream(
                    streamUrl = trimmedUrl,
                    serverName = serverName,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                if (extracted) anyFound = true
            } else {
                try {
                    loadExtractor(trimmedUrl, "$mainUrl/", subtitleCallback, callback)
                    anyFound = true
                } catch (e: Exception) {
                    // Ignored
                }
            }
        }

        return anyFound
    }

    private suspend fun extractZokoStream(
        streamUrl: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val embedHtml = app.get(
                streamUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).text

            val matcher = Pattern.compile("""window\.__P\s*=\s*"([^"]+)"""").matcher(embedHtml)
            if (!matcher.find()) return false

            val blob = matcher.group(1)
            val rawBytes = Base64.decode(blob, Base64.DEFAULT)
            val keyBytes = ZOKO_DECRYPT_KEY.toByteArray(Charsets.UTF_8)
            val decryptedBytes = ByteArray(rawBytes.size) { i ->
                (rawBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            val jsonText = String(decryptedBytes, Charsets.UTF_8)
            val zokoData = parseJson<ZokoResponse>(jsonText)

            // 1. Dispatch subtitles
            zokoData.subtitles?.forEach { sub ->
                val subSrc = sub.src?.trim()
                if (!subSrc.isNullOrBlank()) {
                    val label = sub.label?.trim() ?: "English"
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = label,
                            url = subSrc
                        )
                    )
                }
            }

            val masterM3u8Url = zokoData.src?.trim() ?: return false
            val zokoReferer = "https://zokoanime.video/"

            // 2. Fetch master playlist and emit individual qualities (1080p, 720p, 360p, etc.)
            var qualityFound = false
            try {
                val masterPlaylist = app.get(
                    masterM3u8Url,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to zokoReferer
                    )
                ).text

                val baseUrl = masterM3u8Url.substringBeforeLast("/") + "/"
                val lines = masterPlaylist.split("\n")
                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val resMatcher = Pattern.compile("RESOLUTION=\\d+x(\\d+)").matcher(line)
                        val res = if (resMatcher.find()) resMatcher.group(1) else ""
                        val nextLine = lines.getOrNull(i + 1)?.trim() ?: continue
                        if (nextLine.isNotBlank() && !nextLine.startsWith("#")) {
                            val qualityUrl =
                                if (nextLine.startsWith("http")) nextLine else baseUrl + nextLine
                            val qualityInt = when (res) {
                                "1080" -> Qualities.P1080.value
                                "720" -> Qualities.P720.value
                                "480" -> Qualities.P480.value
                                "360" -> Qualities.P360.value
                                else -> Qualities.Unknown.value
                            }
                            val qualityLabel = if (res.isNotBlank()) "${res}p" else "Multi"

                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "$serverName ($qualityLabel)",
                                    url = qualityUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = zokoReferer
                                    this.quality = qualityInt
                                }
                            )
                            qualityFound = true
                        }
                    }
                }
            } catch (e: Exception) {
                // If playlist parsing fails, fallback to direct master link below
            }

            // 3. Always also provide the master HLS link (Auto quality)
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "$serverName (Auto)",
                    url = masterM3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = zokoReferer
                    this.quality = Qualities.Unknown.value
                }
            )

            true
        } catch (e: Exception) {
            false
        }
    }

    data class EpisodePassData(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("sub") val sub: List<String>? = null,
        @JsonProperty("dub") val dub: List<String>? = null,
        @JsonProperty("animeId") val animeId: String? = null,
        @JsonProperty("malId") val malId: Int? = null
    )

    data class SearchItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("English") val english: String? = null,
        @JsonProperty("Japanese") val japanese: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("landScapeImage") val landScapeImage: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("slugs") val slugs: List<String?>? = null,
        @JsonProperty("mal_id") val malId: Int? = null,
        @JsonProperty("Type") val type: String? = null,
        @JsonProperty("Score") val score: String? = null,
        @JsonProperty("Status") val status: String? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("totalSubbed") val totalSubbed: Int? = null,
        @JsonProperty("totalDubbed") val totalDubbed: Int? = null
    )

    data class AnimeListWrapper(
        @JsonProperty("animes") val animes: List<SearchItem>? = null
    )

    data class UpcomingWrapper(
        @JsonProperty("data") val data: List<SearchItem>? = null
    )

    data class LatestEpisodesWrapper(
        @JsonProperty("episodes") val episodes: List<LatestEpisodeItem>? = null
    )

    data class LatestEpisodeItem(
        @JsonProperty("anime_info") val animeInfo: SearchItem? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("title") val title: String? = null
    )

    data class AnimeDetailWrapper(
        @JsonProperty("anime") val anime: AnimeDetail? = null
    )

    data class AnimeDetail(
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("English") val english: String? = null,
        @JsonProperty("Japanese") val japanese: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("landScapeImage") val landScapeImage: String? = null,
        @JsonProperty("Type") val type: String? = null,
        @JsonProperty("Status") val status: String? = null,
        @JsonProperty("Score") val score: String? = null,
        @JsonProperty("score") val scoreAlt: String? = null,
        @JsonProperty("Premiered") val premiered: String? = null,
        @JsonProperty("Aired") val aired: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("totalSubbed") val totalSubbed: Int? = null,
        @JsonProperty("totalDubbed") val totalDubbed: Int? = null,
        @JsonProperty("mal_id") val malId: Int? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("slugs") val slugs: List<String?>? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null
    )

    data class EpisodeItem(
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("slugs") val slugs: List<String?>? = null,
        @JsonProperty("link") val link: EpisodeLink? = null
    )

    data class EpisodeLink(
        @JsonProperty("sub") val sub: List<String>? = null,
        @JsonProperty("dub") val dub: List<String>? = null
    )

    data class EpisodesRangeResponse(
        @JsonProperty("total") val total: Int? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null
    )

    data class ZokoResponse(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("subtitles") val subtitles: List<ZokoSubtitle>? = null
    )

    data class ZokoSubtitle(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("label") val label: String? = null
    )
}

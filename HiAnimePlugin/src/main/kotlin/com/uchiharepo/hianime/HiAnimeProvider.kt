package com.uchiharepo.hianime

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
        private const val OBF_KEY = "otaku-embed-v1"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    // --- Data Models ---
    data class HomeApiResponse(
        @JsonProperty("trending") val trending: AnimeListWrapper? = null,
        @JsonProperty("popular") val popular: AnimeListWrapper? = null,
        @JsonProperty("currentlyAiring") val currentlyAiring: AnimeListWrapper? = null,
        @JsonProperty("latestAnime") val latestAnime: AnimeListWrapper? = null,
        @JsonProperty("finishedAiring") val finishedAiring: AnimeListWrapper? = null
    )

    data class AnimeListWrapper(
        @JsonProperty("animes") val animes: List<AnimeItem>? = null
    )

    data class AnimeItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("English") val english: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("landScapeImage") val landScapeImage: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("slugs") val slugs: List<String>? = null,
        @JsonProperty("Type") val type: String? = null,
        @JsonProperty("Score") val score: String? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Any? = null
    )

    data class AnimeDetailWrapper(
        @JsonProperty("anime") val anime: AnimeDetail? = null
    )

    data class AnimeDetail(
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("English") val english: String? = null,
        @JsonProperty("Japanese") val japanese: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("landScapeImage") val landScapeImage: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("Score") val score: String? = null,
        @JsonProperty("Rating") val rating: String? = null,
        @JsonProperty("Status") val status: String? = null,
        @JsonProperty("Aired") val aired: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("Type") val type: String? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Any? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("slugs") val slugs: List<String>? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeEntry>? = null
    )

    data class EpisodeEntry(
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("link") val link: EpisodeLinkWrapper? = null
    )

    data class EpisodeLinkWrapper(
        @JsonProperty("sub") val sub: List<String>? = null,
        @JsonProperty("dub") val dub: List<String>? = null
    )

    data class EpisodePassData(
        @JsonProperty("subLinks") val subLinks: List<String>? = null,
        @JsonProperty("dubLinks") val dubLinks: List<String>? = null
    )

    data class ZokoPayload(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("subtitles") val subtitles: List<ZokoSubtitle>? = null
    )

    data class ZokoSubtitle(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("default") val default: Boolean? = null
    )

    // --- Main Page ---
    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "popular" to "Most Popular",
        "currentlyAiring" to "Top Airing",
        "latestAnime" to "Latest Anime",
        "finishedAiring" to "Completed"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )

        val homeJson = try {
            app.get("$API_URL/home", headers = headers).text
        } catch (e: Exception) {
            app.get("$FALLBACK_API_URL/home", headers = headers).text
        }

        val homeData = parseJson<HomeApiResponse>(homeJson)
        val animeList = when (request.data) {
            "trending" -> homeData.trending?.animes
            "popular" -> homeData.popular?.animes
            "currentlyAiring" -> homeData.currentlyAiring?.animes
            "latestAnime" -> homeData.latestAnime?.animes
            "finishedAiring" -> homeData.finishedAiring?.animes
            else -> homeData.trending?.animes
        } ?: emptyList()

        val homeItems = animeList.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, homeItems)
    }

    // --- Search ---
    override suspend fun search(query: String): List<SearchResponse> {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Content-Type" to "application/json",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        val payload = toJson(mapOf("title" to query))
        val response = try {
            app.post("$API_URL/search", headers = headers, data = payload).text
        } catch (e: Exception) {
            app.post("$FALLBACK_API_URL/search", headers = headers, data = payload).text
        }

        val items = parseJson<List<AnimeItem>>(response)
        return items.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun AnimeItem.toSearchResponse(): SearchResponse? {
        val displayTitle = this.title ?: this.english ?: return null
        val chosenSlug = this.slug ?: this.slugs?.firstOrNull() ?: return null
        val poster = this.image ?: this.landScapeImage
        val itemUrl = "$mainUrl/anime/$chosenSlug"

        return newAnimeSearchResponse(displayTitle, itemUrl, TvType.Anime) {
    this.posterUrl = poster
        }

    // --- Details & Load ---
    override suspend fun load(url: String): LoadResponse {
        val cleanSlug = when {
            url.contains("/anime/") -> url.substringAfterLast("/anime/").trimEnd('/')
            url.contains("/") -> url.substringAfterLast('/').trimEnd('/')
            else -> url.trim()
        }

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )

        val apiUrl = "$API_URL/anime/$cleanSlug"
        val responseText = try {
            app.get(apiUrl, headers = headers).text
        } catch (e: Exception) {
            app.get("$FALLBACK_API_URL/anime/$cleanSlug", headers = headers).text
        }

        val detailWrapper = parseJson<AnimeDetailWrapper>(responseText)
        val anime = detailWrapper.anime ?: throw ErrorLoadingException("Anime not found")

        val displayTitle = anime.title ?: anime.english ?: cleanSlug
        val poster = anime.image ?: anime.landScapeImage
        val isMovie = anime.type.equals("Movie", ignoreCase = true)

        val episodes = anime.episodes?.mapNotNull { ep ->
            val subLinks = ep.link?.sub.orEmpty()
            val dubLinks = ep.link?.dub.orEmpty()
            if (subLinks.isEmpty() && dubLinks.isEmpty()) return@mapNotNull null

            val passData = EpisodePassData(
                subLinks = subLinks,
                dubLinks = dubLinks
            )

            newEpisode(toJson(passData)) {
                this.name = ep.title ?: "Episode ${ep.episodeNumber ?: 1}"
                this.episode = ep.episodeNumber
                this.posterUrl = poster
            }
        } ?: emptyList()

        val year = parseYear(anime.aired)
        val status = when {
            anime.status?.contains("Currently", ignoreCase = true) == true -> ShowStatus.Ongoing
            anime.status?.contains("Finished", ignoreCase = true) == true -> ShowStatus.Completed
            else -> null
        }

        if (isMovie) {
            val moviePassData = episodes.firstOrNull()?.data ?: ""
            return newMovieLoadResponse(displayTitle, url, TvType.AnimeMovie, moviePassData) {
                this.posterUrl = poster
                this.plot = anime.synopsis
                this.tags = anime.genres
                this.year = year
                if (!anime.score.isNullOrBlank()) {
                    this.rating = ratingStr?.toDoubleOrNull()?.let { (it * 1000).toInt() }
                }
            }
        }

        return newTvSeriesLoadResponse(displayTitle, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = anime.synopsis
            this.tags = anime.genres
            this.year = year
            this.showStatus = status
            if (!anime.score.isNullOrBlank()) {
                this.rating = ratingStr?.toDoubleOrNull()?.let { (it * 1000).toInt() }
            }
        }
    }

    // --- Load Links & Streams ---
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

        var foundAny = false

        // 1. Process Subbed Streams
        val subLinks = passData.subLinks.orEmpty()
        for ((index, streamPageUrl) in subLinks.withIndex()) {
            val serverName = if (subLinks.size > 1) "HD-${index + 1} (Sub)" else "HD-1 (Sub)"
            val ok = extractZokoStream(streamPageUrl, serverName, subtitleCallback, callback)
            if (ok) foundAny = true
        }

        // 2. Process Dubbed Streams
        val dubLinks = passData.dubLinks.orEmpty()
        for ((index, streamPageUrl) in dubLinks.withIndex()) {
            val serverName = if (dubLinks.size > 1) "HD-${index + 1} (Dub)" else "HD-1 (Dub)"
            val ok = extractZokoStream(streamPageUrl, serverName, subtitleCallback, callback)
            if (ok) foundAny = true
        }

        return foundAny
    }

    private suspend fun extractZokoStream(
        pageUrl: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
            val html = app.get(pageUrl, headers = headers).text

            val matcher = Pattern.compile("window\\.__P\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html)
            if (!matcher.find()) return false

            val blob = matcher.group(1) ?: return false
            val payload = decryptZoko(blob) ?: return false
            val m3u8Url = payload.src ?: return false

            // Extract subtitles
            payload.subtitles?.forEach { sub ->
                val subUrl = sub.src
                if (!subUrl.isNullOrBlank()) {
                    val label = sub.label ?: sub.lang ?: "English"
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = label,
                            url = subUrl
                        )
                    )
                }
            }

            // Extract M3U8 multi-quality video streams
            try {
                val m3u8Links = M3u8Helper.generateM3u8(
                    source = this.name,
                    name = serverName,
                    url = m3u8Url,
                    referer = "https://zokoanime.video/"
                )
                if (m3u8Links.isNotEmpty()) {
                    m3u8Links.forEach { callback.invoke(it) }
                    return true
                }
            } catch (e: Exception) {
                // Ignore and use direct fallback link
            }

            // Fallback direct HLS ExtractorLink
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = serverName,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://zokoanime.video/"
                }
            )

            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun decryptZoko(blob: String): ZokoPayload? {
        return try {
            val rawBytes = Base64.decode(blob, Base64.DEFAULT)
            val rawStr = String(rawBytes, StandardCharsets.ISO_8859_1)
            val sb = StringBuilder()
            for (i in rawStr.indices) {
                val c = rawStr[i].code xor OBF_KEY[i % OBF_KEY.length].code
                sb.append(c.toChar())
            }
            val decoded = URLDecoder.decode(sb.toString(), "UTF-8")
            parseJson<ZokoPayload>(decoded)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseYear(aired: String?): Int? {
        if (aired.isNullOrBlank()) return null
        val matcher = Pattern.compile("(\\b20\\d{2}\\b|\\b19\\d{2}\\b)").matcher(aired)
        return if (matcher.find()) matcher.group(1)?.toIntOrNull() else null
    }
}

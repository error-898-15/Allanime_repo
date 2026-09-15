package com.uchiharepo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.regex.Pattern

class BlakiteAnimeProvider : MainAPI() {
    override var mainUrl = "https://www.blakiteanime.buzz"
    override var name = "Blakite Anime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "hi"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon
    )

    companion object {
        private const val API_URL = "https://blakiteapi.xyz"
        private const val BASE_STREAM_URL = "https://hugh.cdn.rumble.cloud/video/"
        private val QUALITY_CODES = mapOf(
            "240p" to "oaa",
            "360p" to "baa",
            "480p" to "caa",
            "720p" to "gaa",
            "1080p" to "haa"
        )
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    data class AnimeCatalogResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: CatalogData? = null
    )

    data class CatalogData(
        @JsonProperty("movies") val movies: Map<String, AnimeItem>? = null,
        @JsonProperty("series") val series: Map<String, AnimeItem>? = null,
        @JsonProperty("dramas") val dramas: Map<String, AnimeItem>? = null
    )

    data class TmdbData(
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null
    )

    data class ImagesData(
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("backdrop") val backdrop: String? = null
    )

    data class SeasonInfo(
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("status") val status: String? = null
    )

    data class AnimeItem(
        @JsonProperty("tmdbId") val tmdbId: String? = null,
        @JsonProperty("originalTmdbId") val originalTmdbId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("TMDB_DATA") val tmdbData: TmdbData? = null,
        @JsonProperty("IMAGES") val images: ImagesData? = null,
        @JsonProperty("seasons") val seasons: Map<String, SeasonInfo>? = null
    )

    data class StreamResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: StreamData? = null
    )

    data class StreamData(
        @JsonProperty("animeTitle") val animeTitle: String? = null,
        @JsonProperty("tmdbId") val tmdbId: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("dataId") val dataId: String? = null,
        @JsonProperty("qid") val qid: Int? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("ranges") val ranges: String? = null,
        @JsonProperty("poster") val poster: String? = null
    )

    data class EpisodePassData(
        val tmdbId: String,
        val season: Int,
        val episode: Int,
        val isMovie: Boolean
    )

    private suspend fun fetchCatalog(): CatalogData? {
        val response = app.get(
            "$API_URL/api/getAllAnime.php",
            headers = mapOf("User-Agent" to USER_AGENT)
        ).text
        return parseJson<AnimeCatalogResponse>(response).data
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val catalog = fetchCatalog() ?: return newHomePageResponse(emptyList())
        val homeSections = ArrayList<HomePageList>()

        // 1. Latest Anime Series
        val seriesList = catalog.series?.values?.mapNotNull { it.toSearchResponse() }
        if (!seriesList.isNullOrEmpty()) {
            homeSections.add(HomePageList("Latest Anime Series", seriesList.take(25)))
        }

        // 2. Anime Movies
        val moviesList = catalog.movies?.values?.mapNotNull { it.toSearchResponse() }
        if (!moviesList.isNullOrEmpty()) {
            homeSections.add(HomePageList("Anime Movies", moviesList.take(25)))
        }

        // 3. Hindi Dubbed & Subbed Anime
        val allItems = (catalog.series?.values.orEmpty() + catalog.movies?.values.orEmpty())
        val hindiList = allItems.filter {
            it.language?.contains("Hindi", ignoreCase = true) == true
        }.mapNotNull { it.toSearchResponse() }
        if (hindiList.isNotEmpty()) {
            homeSections.add(HomePageList("Hindi Dubbed & Subbed", hindiList.take(25)))
        }

        // 4. English Dubbed & Subbed Anime
        val englishList = allItems.filter {
            it.language?.contains("English", ignoreCase = true) == true
        }.mapNotNull { it.toSearchResponse() }
        if (englishList.isNotEmpty()) {
            homeSections.add(HomePageList("English Dubbed & Subbed", englishList.take(25)))
        }

        return newHomePageResponse(homeSections)
    }

    private fun AnimeItem.toSearchResponse(): SearchResponse? {
        val id = tmdbId ?: return null
        val itemTitle = title ?: "Anime $id"
        val poster = images?.poster
        val isMovie = type.equals("Movie", ignoreCase = true)
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newMovieSearchResponse(
            name = itemTitle,
            url = "$mainUrl/2026/09/streaming.html?id=$id&type=${type ?: "Series"}",
            type = tvType
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val catalog = fetchCatalog() ?: return emptyList()
        val allItems = catalog.series?.values.orEmpty() + catalog.movies?.values.orEmpty() + catalog.dramas?.values.orEmpty()
        val trimmedQuery = query.trim().lowercase()

        return allItems.filter { item ->
            val titleMatches = item.title?.lowercase()?.contains(trimmedQuery) == true
            val idMatches = item.tmdbId == query.trim() || item.originalTmdbId == query.trim()
            titleMatches || idMatches
        }.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val idRegex = Pattern.compile("[?&]id=([^&]+)")
        val matcher = idRegex.matcher(url)
        val tmdbId = if (matcher.find()) matcher.group(1) else url.substringAfterLast("/")

        val catalog = fetchCatalog() ?: return null
        val allItems = catalog.series.orEmpty() + catalog.movies.orEmpty() + catalog.dramas.orEmpty()
        val animeItem = allItems[tmdbId] ?: allItems.values.firstOrNull { it.tmdbId == tmdbId }
            ?: return null

        val title = animeItem.title ?: "Blakite Anime"
        val isMovie = animeItem.type.equals("Movie", ignoreCase = true)
        val poster = animeItem.images?.poster
        val backdrop = animeItem.images?.backdrop
        val synopsis = animeItem.tmdbData?.synopsis ?: animeItem.tmdbData?.overview
        val year = animeItem.tmdbData?.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
        val ratingStr = animeItem.tmdbData?.rating
        val genres = animeItem.tmdbData?.genres

        if (isMovie) {
            val passData = EpisodePassData(
                tmdbId = animeItem.tmdbId ?: tmdbId,
                season = 1,
                episode = 1,
                isMovie = true
            ).toJson()

            return newMovieLoadResponse(title, url, TvType.AnimeMovie, passData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = synopsis
                this.year = year
                this.score = Score.from10(ratingStr)
                this.tags = genres
            }
        }

        // Series with seasons and episodes
        val episodes = ArrayList<Episode>()
        val seasonsMap = animeItem.seasons.orEmpty()

        if (seasonsMap.isNotEmpty()) {
            seasonsMap.forEach { (seasonKey, seasonObj) ->
                val seasonNum = seasonObj.seasonNumber ?: seasonKey.toIntOrNull() ?: 1
                val totalEps = seasonObj.totalEpisodes ?: 1

                for (epNum in 1..totalEps) {
                    val passData = EpisodePassData(
                        tmdbId = animeItem.tmdbId ?: tmdbId,
                        season = seasonNum,
                        episode = epNum,
                        isMovie = false
                    ).toJson()

                    episodes.add(
                        newEpisode(passData) {
                            this.name = "Season $seasonNum Episode $epNum"
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = poster
                        }
                    )
                }
            }
        } else {
            // Fallback single episode
            val passData = EpisodePassData(
                tmdbId = animeItem.tmdbId ?: tmdbId,
                season = 1,
                episode = 1,
                isMovie = false
            ).toJson()

            episodes.add(
                newEpisode(passData) {
                    this.name = "Episode 1"
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = synopsis
            this.year = year
            this.score = Score.from10(ratingStr)
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val passData = parseJson<EpisodePassData>(data)
        val tmdbId = passData.tmdbId
        val season = passData.season
        val episode = passData.episode
        val isMovie = passData.isMovie

        val apiUrl = if (isMovie) {
            "$API_URL/api/get.php?tmdbId=$tmdbId"
        } else {
            "$API_URL/api/get.php?id=$season-$episode&tmdbId=$tmdbId"
        }

        val referer = if (isMovie) {
            "$API_URL/embed/$tmdbId"
        } else {
            "$API_URL/embed/$tmdbId/$season-$episode"
        }

        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*"
        )

        val responseText = try {
            app.get(apiUrl, headers = headers).text
        } catch (e: Exception) {
            return false
        }

        val streamObj = try {
            parseJson<StreamResponse>(responseText).data ?: return false
        } catch (e: Exception) {
            return false
        }

        val dataId = streamObj.dataId ?: return false
        val rangesStr = streamObj.ranges.orEmpty()
        val format = streamObj.format ?: "M3U8"

        // 1. Parse M3U8 multi-quality ranges and invoke HLS ExtractorLinks
        if (format == "M3U8" && rangesStr.isNotBlank()) {
            val rangeLines = rangesStr.split("\n")
            val rangeRegex = Pattern.compile("^(\\d+-\\d+)\\s*\\(([^)]+)\\)")

            for (line in rangeLines) {
                val trimmedLine = line.trim()
                val matcher = rangeRegex.matcher(trimmedLine)
                if (matcher.find()) {
                    val range = matcher.group(1)
                    val label = matcher.group(2).trim() // e.g. 720p, 480p
                    val code = QUALITY_CODES[label] ?: "gaa"
                    val streamUrl =
                        "$BASE_STREAM_URL$dataId.$code.tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl&r_range=$range"

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "Blakite Cloud HLS ($label)",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.quality = getQualityInt(label)
                        }
                    )
                }
            }
        }

        // 2. Direct MP4 fallback link
        val mp4Url = "$BASE_STREAM_URL$dataId.gaa.mp4"
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Blakite Direct MP4 (720p)",
                url = mp4Url,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.P720.value
            }
        )

        return true
    }

    private fun getQualityInt(quality: String): Int {
        return when {
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            quality.contains("360") -> Qualities.P360.value
            quality.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }
}

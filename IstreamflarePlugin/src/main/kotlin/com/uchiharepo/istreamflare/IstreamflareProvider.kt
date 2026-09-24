package com.uchiharepo.istreamflare

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class IstreamflareProvider : MainAPI() {
    override var mainUrl = "https://desi.hippitunes.pro/android"
    override var name = "iStreamFlare"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "hi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        private const val API_KEY = "kC7V1f8QRaZyvYnh"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
        private const val REFERER = "https://desi.hippitunes.pro/"
    }

    data class EncryptedEnvelope(
        @JsonProperty("encrypted") val encrypted: Boolean? = null,
        @JsonProperty("data") val data: String? = null
    )

    data class CustomTag(
        @JsonProperty("custom_tags_name") val name: String? = null,
        @JsonProperty("background_color") val bgColor: String? = null,
        @JsonProperty("text_color") val textColor: String? = null
    )

    data class ContentItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("TMDB_ID") val tmdbId: String? = null,
        @JsonProperty("name") val name: String,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("genres") val genres: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("runtime") val runtime: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("banner") val banner: String? = null,
        @JsonProperty("youtube_trailer") val youtubeTrailer: String? = null,
        @JsonProperty("content_type") val contentType: String? = null,
        @JsonProperty("custom_tag") val customTag: CustomTag? = null
    )

    data class StreamLinkItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )

    data class SeasonItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("Session_Name") val sessionName: String? = null,
        @JsonProperty("season_order") val seasonOrder: String? = null,
        @JsonProperty("web_series_id") val webSeriesId: String? = null
    )

    data class EpisodeItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("Episoade_Name") val episodeName: String,
        @JsonProperty("episoade_image") val episodeImage: String? = null,
        @JsonProperty("episoade_description") val episodeDescription: String? = null,
        @JsonProperty("episoade_order") val episodeOrder: String? = null,
        @JsonProperty("season_id") val seasonId: String? = null,
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("url") val url: String
    )

    data class EpisodeLinkPassData(
        val url: String,
        val type: String?,
        val name: String
    )

    private suspend fun apiGet(endpoint: String): String? {
        val fullUrl = "$mainUrl/$endpoint"
        val response = try {
            app.get(
                fullUrl,
                headers = mapOf(
                    "x-api-key" to API_KEY,
                    "User-Agent" to USER_AGENT
                )
            ).text
        } catch (e: Exception) {
            return null
        }

        if (response.trim().startsWith("<")) return null

        return try {
            val json = parseJson<EncryptedEnvelope>(response)
            if (json.encrypted == true && !json.data.isNullOrEmpty()) {
                CryptoHelper.decrypt(json.data)
            } else {
                response
            }
        } catch (e: Exception) {
            response
        }
    }

    override val mainPage = mainPageOf(
        "getMovieImageSlider" to "Featured Premiere",
        "getTrending" to "Trending Now",
        "getRecentContentList/Movies" to "Recent Movies",
        "getRecentContentList/WebSeries" to "Recent Web Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val decrypted = apiGet(request.data) ?: return null
        val items = try {
            parseJson<List<ContentItem>>(decrypted)
        } catch (e: Exception) {
            return null
        }
        val homeList = items.map { it.toSearchResponse() }
        val section = HomePageList(
            name = request.name,
            list = homeList,
            isHorizontalImages = request.data == "getMovieImageSlider"
        )
        return newHomePageResponse(listOf(section), hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.trim()
        val decrypted = apiGet("searchContent/$encodedQuery/0") ?: return emptyList()
        val items = try {
            parseJson<List<ContentItem>>(decrypted)
        } catch (e: Exception) {
            return emptyList()
        }
        return items.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val isMovie = url.startsWith("movie/")
        val id = url.substringAfter("/")

        return if (isMovie) {
            val decrypted = apiGet("getMovieDetails/$id") ?: return null
            val movie = try {
                parseJson<ContentItem>(decrypted)
            } catch (e: Exception) {
                return null
            }

            newMovieLoadResponse(movie.name, url, TvType.Movie, id) {
                this.posterUrl = movie.poster ?: movie.banner
                this.backgroundPosterUrl = movie.banner ?: movie.poster
                this.plot = movie.description
                this.year = movie.releaseDate?.take(4)?.toIntOrNull()
                this.tags = movie.genres?.split(",")?.map { it.trim() }
                movie.youtubeTrailer?.takeIf { it.isNotBlank() }?.let { trailer ->
                    val trailerUrl = if (trailer.startsWith("http")) trailer else "https://www.youtube.com/watch?v=$trailer"
                    addTrailer(trailerUrl)
                }
            }
        } else {
            val detailsDecrypted = apiGet("getWebSeriesDetails/$id") ?: return null
            val series = try {
                parseJson<ContentItem>(detailsDecrypted)
            } catch (e: Exception) {
                return null
            }

            val seasonsDecrypted = apiGet("getSeasons/$id")
            val seasonList = if (!seasonsDecrypted.isNullOrEmpty()) {
                try {
                    parseJson<List<SeasonItem>>(seasonsDecrypted)
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()

            val episodes = mutableListOf<Episode>()
            for (season in seasonList) {
                val sOrder = season.seasonOrder?.toIntOrNull() ?: 1
                val epDecrypted = apiGet("getEpisodes/${season.id}/0") ?: continue
                val epList = try {
                    parseJson<List<EpisodeItem>>(epDecrypted)
                } catch (e: Exception) {
                    continue
                }

                for (ep in epList) {
                    val passData = toJson(
                        EpisodeLinkPassData(
                            url = ep.url,
                            type = ep.source ?: "Dash",
                            name = ep.episodeName
                        )
                    )

                    episodes.add(
                        newEpisode(passData) {
                            this.name = ep.episodeName
                            this.season = sOrder
                            this.episode = ep.episodeOrder?.toIntOrNull()
                            this.posterUrl = ep.episodeImage ?: series.poster
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(series.name, url, TvType.TvSeries, episodes) {
                this.posterUrl = series.poster ?: series.banner
                this.backgroundPosterUrl = series.banner ?: series.poster
                this.plot = series.description
                this.year = series.releaseDate?.take(4)?.toIntOrNull()
                this.tags = series.genres?.split(",")?.map { it.trim() }
                series.youtubeTrailer?.takeIf { it.isNotBlank() }?.let { trailer ->
                    val trailerUrl = if (trailer.startsWith("http")) trailer else "https://www.youtube.com/watch?v=$trailer"
                    addTrailer(trailerUrl)
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
        if (data.startsWith("{") && data.contains("url")) {
            val epData = try {
                parseJson<EpisodeLinkPassData>(data)
            } catch (e: Exception) {
                return false
            }

            val isDash = epData.type?.contains("dash", ignoreCase = true) == true
            val isM3u8 = epData.type?.contains("m3u8", ignoreCase = true) == true

            val streamType = when {
                isDash -> ExtractorLinkType.DASH
                isM3u8 -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = epData.name,
                    url = epData.url,
                    type = streamType
                ) {
                    this.referer = REFERER
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        val movieId = data
        val decrypted = apiGet("getMoviePlayLinks/$movieId/0") ?: return false
        val streamLinks = try {
            parseJson<List<StreamLinkItem>>(decrypted)
        } catch (e: Exception) {
            return false
        }

        for (link in streamLinks) {
            val url = link.url ?: continue
            val linkName = link.name ?: "Stream Server"
            val isDash = link.type?.contains("dash", ignoreCase = true) == true
            val isM3u8 = link.type?.contains("m3u8", ignoreCase = true) == true

            val streamType = when {
                isDash -> ExtractorLinkType.DASH
                isM3u8 -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = linkName,
                    url = url,
                    type = streamType
                ) {
                    this.referer = REFERER
                    this.quality = getQualityInt(linkName + " " + (link.quality ?: ""))
                }
            )
        }

        return streamLinks.isNotEmpty()
    }

    private fun getQualityInt(qualityStr: String): Int {
        val q = qualityStr.lowercase()
        return when {
            q.contains("4k") || q.contains("2160") -> Qualities.P2160.value
            q.contains("1080") -> Qualities.P1080.value
            q.contains("720") -> Qualities.P720.value
            q.contains("480") -> Qualities.P480.value
            q.contains("360") -> Qualities.P360.value
            else -> Qualities.P1080.value
        }
    }

    private fun ContentItem.toSearchResponse(): SearchResponse {
        val isSeries = contentType == "2"
        val loadUrl = if (isSeries) "series/$id" else "movie/$id"
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(name, loadUrl, tvType) {
            this.posterUrl = poster ?: banner
        }
    }
}

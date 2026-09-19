package com.uchiharepo.aniflix

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder

class AniflixProvider : MainAPI() {
    override var mainUrl = "https://aniflix.uno"
    override var name = "Aniflix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/api/anime/search?q=" to "Trending & Popular Anime",
        "$mainUrl/api/anime/search?q=action" to "Action & Adventure",
        "$mainUrl/api/anime/search?q=fantasy" to "Fantasy & Magic",
        "$mainUrl/api/anime/search?q=comedy" to "Comedy & Fun",
        "$mainUrl/api/anime/search?q=romance" to "Romance & Drama",
        "$mainUrl/api/anime/search?q=dub" to "Multi-Audio & Dubbed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val searchData = try {
            val res = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).text
            parseJson<AniflixSearchResponse>(res)
        } catch (e: Exception) {
            null
        }

        val animeList = searchData?.media?.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title?.english
                ?: item.title?.romaji
                ?: item.title?.userPreferred
                ?: return@mapNotNull null
            val poster = item.coverImage?.large
                ?: item.coverImage?.extraLarge
                ?: item.coverImage?.medium
                ?: item.image
            val aid = item.aid ?: ""

            newAnimeSearchResponse(
                title,
                "$mainUrl/anime?id=$id&aid=$aid&title=${URLEncoder.encode(title, "UTF-8")}",
                TvType.Anime
            ) {
                this.posterUrl = poster
                this.otherName = item.title?.romaji
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, animeList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val targetUrl = "$mainUrl/api/anime/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val searchData = try {
            val res = app.get(
                targetUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).text
            parseJson<AniflixSearchResponse>(res)
        } catch (e: Exception) {
            null
        }

        return searchData?.media?.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title?.english
                ?: item.title?.romaji
                ?: item.title?.userPreferred
                ?: return@mapNotNull null
            val poster = item.coverImage?.large
                ?: item.coverImage?.extraLarge
                ?: item.coverImage?.medium
                ?: item.image
            val aid = item.aid ?: ""

            newAnimeSearchResponse(
                title,
                "$mainUrl/anime?id=$id&aid=$aid&title=${URLEncoder.encode(title, "UTF-8")}",
                TvType.Anime
            ) {
                this.posterUrl = poster
                this.otherName = item.title?.romaji
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val anilistId: String
        val aid: String
        val title: String

        if (url.contains("?")) {
            val queryStr = url.substringAfter("?")
            val params = queryStr.split("&").associate { param ->
                val parts = param.split("=")
                val k = URLDecoder.decode(parts[0], "UTF-8")
                val v = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                k to v
            }
            anilistId = params["id"] ?: url.substringAfterLast("/")
            aid = params["aid"] ?: ""
            title = params["title"] ?: "Anime"
        } else {
            anilistId = url.substringAfterLast("/")
            aid = ""
            title = "Anime"
        }

        val episodesApiUrl = StringBuilder("$mainUrl/api/anime/episodes?anilistId=$anilistId")
        if (title.isNotBlank()) {
            episodesApiUrl.append("&animeTitle=${URLEncoder.encode(title, "UTF-8")}")
        }
        if (aid.isNotBlank()) {
            episodesApiUrl.append("&aid=$aid")
        }

        val epData = try {
            val epRes = app.get(
                episodesApiUrl.toString(),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).text
            parseJson<AniflixEpisodesResponse>(epRes)
        } catch (e: Exception) {
            null
        }

        val animeInfo = epData?.anime
        val episodesList = mutableListOf<Episode>()

        animeInfo?.episodes?.forEach { ep ->
            val epNumber = ep.ep_no ?: return@forEach
            val epTitle = ep.ep_title ?: "Episode $epNumber"
            val thumb = ep.thumbnail_default?.takeIf { it.isNotBlank() }
                ?: ep.thumbnail_tvdb?.takeIf { it.isNotBlank() }

            val payload = EpisodePayload(
                anilistId = anilistId,
                malId = animeInfo.mal_id?.toString() ?: anilistId,
                aid = aid,
                epNo = epNumber,
                title = animeInfo.anime_name ?: title,
                hindiServers = ep.servers?.hindi ?: emptyList(),
                subServers = ep.servers?.sub ?: ep.servers?.eng ?: emptyList(),
                japServers = ep.servers?.jap ?: emptyList(),
                desidubDid = ep.servers?.hindi?.firstOrNull { it.stream_provider == "desidub" }?.did
            )

            episodesList.add(
                newEpisode(payload.toJson()) {
                    this.name = epTitle
                    this.episode = epNumber
                    this.posterUrl = thumb
                    this.description = ep.synopsis
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = animeInfo?.episodes?.firstOrNull()?.thumbnail_default
            this.episodes = mutableMapOf(DubStatus.Subbed to episodesList)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = try {
            parseJson<EpisodePayload>(data)
        } catch (e: Exception) {
            return false
        }

        var loadedAny = false
        val anilistId = payload.anilistId
        val malId = payload.malId.ifBlank { anilistId }
        val epNo = payload.epNo

        // 1. ORIGINAL SERVER: Anixo (Server Alias: Madara) - Direct HLS Master Stream
        try {
            val anixoUrl = "https://anixo.buzz/embed/ani/$anilistId/$epNo/sub?color=%23ff0000"
            val anixoRes = app.get(
                anixoUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).text
            val streamUrlMatch = Regex(""""streamUrl"\s*:\s*"([^"]+)"""").find(anixoRes)
            if (streamUrlMatch != null) {
                val streamUrl = streamUrlMatch.groupValues[1].replace("\\u0026", "&")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Anixo (Madara - Master HLS)",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://anixo.buzz/"
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "https://anixo.buzz/"
                        )
                        this.quality = Qualities.P1080.value
                    }
                )
                loadedAny = true

                try {
                    M3u8Helper.generateM3u8(
                        source = this.name,
                        streamUrl = streamUrl,
                        referer = "https://anixo.buzz/",
                        quality = Qualities.Unknown.value,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "https://anixo.buzz/"
                        ),
                        name = "Anixo (Madara)"
                    ).forEach { link ->
                        callback.invoke(link)
                        loadedAny = true
                    }
                } catch (e: Exception) {
                    // Sub-streams fallback
                }
            }
        } catch (e: Exception) {
            // Proceed to next original server
        }

        // 2. ORIGINAL SERVER: MegaVid (Server Alias: Saitama) - Direct HLS Stream
        try {
            val megavidSourceUrl = "https://megavid.buzz/mal/$malId/$epNo/sub/source"
            val megavidRes = app.get(
                megavidSourceUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://megavid.buzz/mal/$malId/$epNo/sub?color=%23ff0000"
                )
            ).text
            val megavidData = parseJson<MegaVidResponse>(megavidRes)
            val hlsSource = megavidData.source
            if (!hlsSource.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "MegaVid (Saitama - Master HLS)",
                        url = hlsSource,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://megavid.buzz/"
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "https://megavid.buzz/"
                        )
                        this.quality = Qualities.P1080.value
                    }
                )
                loadedAny = true

                try {
                    M3u8Helper.generateM3u8(
                        source = this.name,
                        streamUrl = hlsSource,
                        referer = "https://megavid.buzz/",
                        quality = Qualities.Unknown.value,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "https://megavid.buzz/"
                        ),
                        name = "MegaVid (Saitama)"
                    ).forEach { link ->
                        callback.invoke(link)
                        loadedAny = true
                    }
                } catch (e: Exception) {
                    // Sub-streams fallback
                }
            }
        } catch (e: Exception) {
            // Proceed to next original server
        }

        // 3. ORIGINAL SERVER: DesiDub (Server Alias: Greed - Hindi & Multi-Audio)
        val desidubDid = payload.desidubDid
            ?: payload.hindiServers.firstOrNull { it.stream_provider == "desidub" }?.did
        if (!desidubDid.isNullOrBlank()) {
            try {
                val desidubApi = "$mainUrl/api/anime/episode-embeds?provider=desidub&did=$desidubDid"
                val desiRes = app.get(
                    desidubApi,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/"
                    )
                ).text
                val desiData = parseJson<DesiDubEmbedResponse>(desiRes)
                desiData.allServers?.forEach { srv ->
                    val srvUrl = srv.url ?: return@forEach
                    try {
                        if (loadExtractor(srvUrl, "$mainUrl/", subtitleCallback, callback)) {
                            loadedAny = true
                        }
                    } catch (e: Exception) {
                        // Skip unresolvable mirror
                    }
                }
            } catch (e: Exception) {
                // Continue
            }
        }

        // 4. ORIGINAL SERVER: Filemoon (Server Alias: Igris) & Direct Server Embeds
        val allServersList = payload.hindiServers + payload.subServers + payload.japServers
        for (server in allServersList) {
            val serverLink = server.link ?: server.embed_url ?: server.raw_link
            if (!serverLink.isNullOrBlank() && serverLink.startsWith("http")) {
                try {
                    if (loadExtractor(serverLink, "$mainUrl/", subtitleCallback, callback)) {
                        loadedAny = true
                    }
                } catch (e: Exception) {
                    // Skip unsupported link
                }
            }
        }

        // 5. ORIGINAL SERVER: VidNest (Server Alias: Rudeus)
        try {
            val vidnestUrl = "https://vidnest.fun/anime/$anilistId/$epNo/sub"
            if (loadExtractor(vidnestUrl, "$mainUrl/", subtitleCallback, callback)) {
                loadedAny = true
            }
        } catch (e: Exception) {
            // Continue
        }

        return loadedAny
    }

    // JSON Data Transfer Models
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniflixSearchResponse(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("media") val media: List<AniflixMediaItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniflixMediaItem(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("aid") val aid: Any? = null,
        @JsonProperty("title") val title: AniflixTitleData? = null,
        @JsonProperty("coverImage") val coverImage: AniflixCoverImageData? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("status") val status: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniflixTitleData(
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("userPreferred") val userPreferred: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniflixCoverImageData(
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("extraLarge") val extraLarge: String? = null,
        @JsonProperty("medium") val medium: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniflixEpisodesResponse(
        @JsonProperty("anime") val anime: AniflixAnimeDetails? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniflixAnimeDetails(
        @JsonProperty("anilist_id") val anilist_id: Any? = null,
        @JsonProperty("mal_id") val mal_id: Any? = null,
        @JsonProperty("anime_name") val anime_name: String? = null,
        @JsonProperty("episodes") val episodes: List<AniflixEpisodeData>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniflixEpisodeData(
        @JsonProperty("ep_no") val ep_no: Int? = null,
        @JsonProperty("ep_title") val ep_title: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("thumbnail_default") val thumbnail_default: String? = null,
        @JsonProperty("thumbnail_tvdb") val thumbnail_tvdb: String? = null,
        @JsonProperty("servers") val servers: AniflixEpisodeServers? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniflixEpisodeServers(
        @JsonProperty("hindi") val hindi: List<AniflixServerEntry>? = null,
        @JsonProperty("sub") val sub: List<AniflixServerEntry>? = null,
        @JsonProperty("eng") val eng: List<AniflixServerEntry>? = null,
        @JsonProperty("jap") val jap: List<AniflixServerEntry>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniflixServerEntry(
        @JsonProperty("server_name") val server_name: String? = null,
        @JsonProperty("server_alias") val server_alias: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("raw_link") val raw_link: String? = null,
        @JsonProperty("embed_url") val embed_url: String? = null,
        @JsonProperty("stream_provider") val stream_provider: String? = null,
        @JsonProperty("did") val did: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodePayload(
        @JsonProperty("anilistId") val anilistId: String,
        @JsonProperty("malId") val malId: String,
        @JsonProperty("aid") val aid: String,
        @JsonProperty("epNo") val epNo: Int,
        @JsonProperty("title") val title: String,
        @JsonProperty("hindiServers") val hindiServers: List<AniflixServerEntry> = emptyList(),
        @JsonProperty("subServers") val subServers: List<AniflixServerEntry> = emptyList(),
        @JsonProperty("japServers") val japServers: List<AniflixServerEntry> = emptyList(),
        @JsonProperty("desidubDid") val desidubDid: String? = null
    ) {
        fun toJson(): String {
            return com.lagradost.cloudstream3.utils.AppUtils.toJson(this)
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaVidResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("source") val source: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DesiDubEmbedResponse(
        @JsonProperty("available") val available: Boolean? = null,
        @JsonProperty("allServers") val allServers: List<DesiDubServer>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DesiDubServer(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("alias") val alias: String? = null,
        @JsonProperty("url") val url: String? = null
    )
}

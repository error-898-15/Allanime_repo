package com.uchiharepo.aniflix

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
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

        val animeList = mutableListOf<SearchResponse>()
        searchData?.media?.forEach { item ->
            val id = item.id?.toString() ?: return@forEach
            val title = item.title?.english?.takeIf { it.isNotBlank() }
                ?: item.title?.userPreferred?.takeIf { it.isNotBlank() }
                ?: item.title?.romaji
                ?: return@forEach
            val poster = item.coverImage?.extraLarge
                ?: item.coverImage?.large
                ?: item.image

            val aid = item.aid?.toString() ?: ""
            val link = "$mainUrl/api/anime/episodes?anilistId=$id&aid=$aid&animeTitle=${URLEncoder.encode(title, "UTF-8")}"
            
            animeList.add(
                newAnimeSearchResponse(title, link, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, animeList)),
            hasNext = false
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/api/anime/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val res = try {
            app.get(
                searchUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).text
        } catch (e: Exception) {
            return emptyList()
        }

        val searchData = try {
            parseJson<AniflixSearchResponse>(res)
        } catch (e: Exception) {
            return emptyList()
        }

        val results = mutableListOf<SearchResponse>()
        searchData.media?.forEach { item ->
            val id = item.id?.toString() ?: return@forEach
            val title = item.title?.english?.takeIf { it.isNotBlank() }
                ?: item.title?.userPreferred?.takeIf { it.isNotBlank() }
                ?: item.title?.romaji
                ?: return@forEach
            val poster = item.coverImage?.extraLarge
                ?: item.coverImage?.large
                ?: item.image

            val aid = item.aid?.toString() ?: ""
            val link = "$mainUrl/api/anime/episodes?anilistId=$id&aid=$aid&animeTitle=${URLEncoder.encode(title, "UTF-8")}"
            
            results.add(
                newAnimeSearchResponse(title, link, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val anilistId: String
        val aid: String
        val title: String

        if (url.startsWith("http")) {
            anilistId = url.substringAfter("anilistId=").substringBefore("&")
            aid = url.substringAfter("aid=").substringBefore("&")
            title = url.substringAfter("animeTitle=").substringBefore("&").let {
                try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
            }
        } else {
            val parts = url.split(";")
            anilistId = parts.getOrNull(0) ?: ""
            aid = parts.getOrNull(1) ?: ""
            title = parts.getOrNull(2) ?: "Anime"
        }

        val apiUrl = if (aid.isNotBlank() && aid != "null") {
            "$mainUrl/api/anime/episodes?anilistId=$anilistId&aid=$aid"
        } else {
            "$mainUrl/api/anime/episodes?anilistId=$anilistId"
        }

        val res = app.get(
            apiUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).text

        val epData = parseJson<AniflixEpisodesResponse>(res)
        val animeInfo = epData.anime ?: throw ErrorLoadingException("Failed to load anime details")
        val episodesList = mutableListOf<Episode>()

        animeInfo.episodes?.forEach { ep ->
            val epNumber = ep.ep_no ?: return@forEach
            val epTitle = ep.ep_title ?: "Episode $epNumber"
            val thumb = ep.thumbnail_default?.takeIf { it.isNotBlank() }
                ?: ep.thumbnail_tvdb?.takeIf { it.isNotBlank() }

            val allServers = (ep.servers?.hindi ?: emptyList()) +
                    (ep.servers?.sub ?: emptyList()) +
                    (ep.servers?.eng ?: emptyList()) +
                    (ep.servers?.jap ?: emptyList())

            val fileCode = allServers.firstOrNull { !it.file_code.isNullOrBlank() }?.file_code
                ?: allServers.firstOrNull { it.link?.contains("/e/") == true }?.link?.substringAfter("/e/")?.substringBefore("?")
                ?: allServers.firstOrNull { it.raw_link?.contains("/e/") == true }?.raw_link?.substringAfter("/e/")?.substringBefore("?")

            val desiDid = ep.servers?.hindi?.firstOrNull { it.stream_provider == "desidub" }?.did

            val anivexaLinks = allServers.mapNotNull {
                val raw = it.raw_link ?: it.link
                if (raw != null && raw.contains("/api/anime/sources")) raw else null
            }.distinct()

            val payload = EpisodeData(
                id = anilistId,
                malId = animeInfo.mal_id?.toString() ?: anilistId,
                ep = epNumber,
                title = animeInfo.anime_name ?: title,
                desiDid = desiDid,
                fileCode = fileCode,
                anivexa = anivexaLinks
            )

            val jsonPayload = toJson(payload)
            episodesList.add(
                newEpisode(jsonPayload) {
                    this.name = epTitle
                    this.episode = epNumber
                    this.posterUrl = thumb
                    this.description = ep.synopsis
                }
            )
        }

        val poster = animeInfo.cover_image?.takeIf { it.isNotBlank() }
            ?: animeInfo.episodes?.firstOrNull()?.thumbnail_default

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodesList) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = animeInfo.description ?: animeInfo.episodes?.firstOrNull()?.synopsis
            this.tags = animeInfo.genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = try {
            parseJson<EpisodeData>(data)
        } catch (e: Exception) {
            val parts = data.split(";")
            if (parts.size >= 2) {
                EpisodeData(id = parts[0], malId = parts[0], ep = parts[1].toIntOrNull() ?: 1)
            } else {
                return false
            }
        }

        var loadedAny = false
        val malId = payload.malId.ifBlank { payload.id }
        val epNo = payload.ep

        // 1. ORIGINAL PRIMARY SERVER: MegaVid (Saitama HLS for Sub & Dub)
        listOf("sub", "dub").forEach { variant ->
            try {
                val srcUrl = "https://megavid.buzz/mal/$malId/$epNo/$variant/source"
                val res = app.get(
                    srcUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://megavid.buzz/mal/$malId/$epNo/$variant?color=%23ff0000"
                    )
                ).text
                val json = parseJson<MegaVidSourceResponse>(res)
                val hlsUrl = json.source
                if (!hlsUrl.isNullOrBlank() && hlsUrl.startsWith("http")) {
                    val label = if (variant == "dub") "MegaVid (Saitama - Dub HLS)" else "MegaVid (Saitama - Sub HLS)"
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = label,
                            url = hlsUrl,
                            referer = "https://megavid.buzz/",
                            quality = Qualities.P1080.value,
                            isM3u8 = true,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://megavid.buzz/"
                            )
                        )
                    )
                    loadedAny = true

                    json.tracks?.forEach { track ->
                        val trackFile = track.file
                        if (!trackFile.isNullOrBlank()) {
                            subtitleCallback.invoke(
                                SubtitleFile(
                                    track.label ?: "English (${variant.uppercase()})",
                                    trackFile
                                )
                            )
                        }
                    }

                    try {
                        M3u8Helper.generateM3u8(
                            source = this.name,
                            streamUrl = hlsUrl,
                            referer = "https://megavid.buzz/",
                            quality = Qualities.Unknown.value,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://megavid.buzz/"
                            ),
                            name = label
                        ).forEach { link ->
                            callback.invoke(link)
                            loadedAny = true
                        }
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
            }
        }

        // 2. ORIGINAL PRIMARY SERVER: Anixo (Madara HLS Master Stream)
        listOf("sub", "dub").forEach { variant ->
            try {
                val anixoUrl = "https://anixo.buzz/embed/ani/${payload.id}/$epNo/$variant?color=%23ff0000"
                val anixoRes = app.get(
                    anixoUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/"
                    )
                ).text

                val m3u8Matches = Regex("""https://anixo\.buzz/api/stream/m3u8\?t=[^"'\s&]+""").findAll(anixoRes).map { it.value }.toSet()
                m3u8Matches.forEachIndexed { idx, m3u8Url ->
                    val label = "Anixo (Madara - ${variant.uppercase()}" + (if (idx > 0) " Backup $idx)" else ")")
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = label,
                            url = m3u8Url,
                            referer = "https://anixo.buzz/",
                            quality = Qualities.P1080.value,
                            isM3u8 = true,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://anixo.buzz/"
                            )
                        )
                    )
                    loadedAny = true
                }
            } catch (e: Exception) {
            }
        }

        // 3. ANIFLIX OFFICIAL SOURCES: Anivexa / Anikoto / AniNeko / Sukuna / ReAnime
        payload.anivexa.take(4).forEach { anivexaPath ->
            try {
                val apiUrl = if (anivexaPath.startsWith("http")) anivexaPath else "$mainUrl$anivexaPath"
                val anivexaRes = app.get(
                    apiUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/"
                    )
                ).text
                val anivexaData = parseJson<AnivexaResponse>(anivexaRes)
                val allSources = (anivexaData.sources?.jap ?: emptyList()) + (anivexaData.sources?.eng ?: emptyList())
                allSources.forEach { src ->
                    val streamUrl = src.proxiedUrl ?: src.proxyUrl ?: src.streamUrl ?: src.url ?: src.rawUrl
                    if (!streamUrl.isNullOrBlank()) {
                        val fullUrl = if (streamUrl.startsWith("/")) "$mainUrl$streamUrl" else streamUrl
                        val serverName = src.server ?: src.provider ?: "Anivexa"
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = "Aniflix - $serverName (HLS)",
                                url = fullUrl,
                                referer = src.referer ?: "$mainUrl/",
                                quality = Qualities.P1080.value,
                                isM3u8 = true,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to (src.referer ?: "$mainUrl/")
                                )
                            )
                        )
                        loadedAny = true
                    }
                }

                anivexaData.subtitles?.forEach { sub ->
                    val subUrl = sub.url ?: sub.src ?: sub.file
                    if (!subUrl.isNullOrBlank()) {
                        val fullSubUrl = if (subUrl.startsWith("/")) "$mainUrl$subUrl" else subUrl
                        subtitleCallback.invoke(
                            SubtitleFile(sub.label ?: "English", fullSubUrl)
                        )
                    }
                }
            } catch (e: Exception) {
            }
        }

        // 4. ORIGINAL SERVER: Filemoon (Igris 1080p)
        if (!payload.fileCode.isNullOrBlank()) {
            try {
                val filemoonUrl = "https://filemoon.sx/e/${payload.fileCode}"
                if (loadExtractor(filemoonUrl, "$mainUrl/", subtitleCallback, callback)) {
                    loadedAny = true
                }
            } catch (e: Exception) {
            }
        }

        // 5. ORIGINAL SERVER: DesiDub (Greed - Hindi Dub & Multi-Audio)
        if (!payload.desiDid.isNullOrBlank()) {
            try {
                val desiApi = "$mainUrl/api/anime/episode-embeds?provider=desidub&did=${payload.desiDid}"
                val desiRes = app.get(
                    desiApi,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/"
                    )
                ).text
                val desiData = parseJson<DesiDubEmbedResponse>(desiRes)
                desiData.allServers?.forEach { server ->
                    val embed = server.url
                    if (!embed.isNullOrBlank() && embed.startsWith("http")) {
                        try {
                            if (loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)) {
                                loadedAny = true
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        return loadedAny
    }

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
        @JsonProperty("aid") val aid: Any? = null,
        @JsonProperty("anime_name") val anime_name: String? = null,
        @JsonProperty("cover_image") val cover_image: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
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
        @JsonProperty("did") val did: String? = null,
        @JsonProperty("file_code") val file_code: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeData(
        @JsonProperty("id") val id: String,
        @JsonProperty("malId") val malId: String,
        @JsonProperty("ep") val ep: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("desiDid") val desiDid: String? = null,
        @JsonProperty("fileCode") val fileCode: String? = null,
        @JsonProperty("anivexa") val anivexa: List<String> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaVidSourceResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("tracks") val tracks: List<MegaVidTrack>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaVidTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnivexaResponse(
        @JsonProperty("available") val available: Boolean? = null,
        @JsonProperty("sources") val sources: AnivexaSourcesContainer? = null,
        @JsonProperty("subtitles") val subtitles: List<AnivexaSubtitle>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnivexaSourcesContainer(
        @JsonProperty("jap") val jap: List<AnivexaStreamItem>? = null,
        @JsonProperty("eng") val eng: List<AnivexaStreamItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnivexaStreamItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("streamUrl") val streamUrl: String? = null,
        @JsonProperty("rawUrl") val rawUrl: String? = null,
        @JsonProperty("proxiedUrl") val proxiedUrl: String? = null,
        @JsonProperty("proxyUrl") val proxyUrl: String? = null,
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("server") val server: String? = null,
        @JsonProperty("referer") val referer: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnivexaSubtitle(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null
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

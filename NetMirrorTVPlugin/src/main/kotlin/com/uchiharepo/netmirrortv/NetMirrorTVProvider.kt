package com.uchiharepo.netmirrortv

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class NetMirrorTVProvider : MainAPI() {
    override var name = "NetMirror TV"
    override var mainUrl = "https://tv.imgcdn.kim"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val userAgentString = "okhttp/4.9.2"
    private val defaultReferer = "https://net52.cc"

    private fun getHeaders(ott: String = "nf"): Map<String, String> {
        return mapOf(
            "User-Agent" to userAgentString,
            "Accept" to "*/*",
            "Ott" to ott,
            "Referer" to "$mainUrl/"
        )
    }

    private suspend fun resolveLiveDomain(): String {
        return try {
            val res = app.get("$mainUrl/checknewtv.php", headers = mapOf("User-Agent" to userAgentString)).text
            if (res.isNotBlank()) {
                val decoded = String(Base64.decode(res.trim(), Base64.DEFAULT)).trim()
                if (decoded.startsWith("http")) decoded else mainUrl
            } else mainUrl
        } catch (e: Exception) {
            mainUrl
        }
    }

    override val mainPage = mainPageOf(
        "nf" to "Netflix Trending",
        "pv" to "Prime Video Popular",
        "hs" to "Hotstar & Disney+ Specials"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val ott = request.data
        val activeDomain = resolveLiveDomain()
        val url = "$activeDomain/newtv/main.php"
        val res = app.get(url, headers = getHeaders(ott)).text
        val response = try { parseJson<NetMirrorCatalogResponse>(res) } catch (e: Exception) { null }

        val searchResponses = mutableListOf<SearchResponse>()
        response?.data?.forEach { section ->
            section.items?.forEach { item ->
                val id = item.id ?: return@forEach
                val title = item.title ?: "Untitled"
                val poster = item.poster?.let { fixUrl(it, activeDomain) }
                val type = if (item.type?.contains("series", ignoreCase = true) == true || item.type?.contains("tv", ignoreCase = true) == true) {
                    TvType.TvSeries
                } else {
                    TvType.Movie
                }

                searchResponses.add(
                    newMovieSearchResponse(title, "$activeDomain/newtv/post.php?id=$id&ott=$ott", type) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        return newHomePageResponse(request.name, searchResponses)
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val activeDomain = resolveLiveDomain()
        val ottList = listOf("nf", "pv", "hs")
        val cleanQuery = query.trim()

        val results = ottList.map { ott ->
            async {
                try {
                    val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                    val searchUrl = "$activeDomain/newtv/search.php?s=$encoded"
                    val res = app.get(searchUrl, headers = getHeaders(ott)).text
                    val resp = try { parseJson<NetMirrorSearchResponse>(res) } catch (e: Exception) { null }
                    resp?.data?.mapNotNull { item ->
                        val id = item.id ?: return@mapNotNull null
                        val title = item.title ?: return@mapNotNull null
                        val poster = item.poster?.let { fixUrl(it, activeDomain) }
                        val type = if (item.type?.contains("series", ignoreCase = true) == true) TvType.TvSeries else TvType.Movie
                        val ottBadge = when (ott) {
                            "nf" -> "[Netflix] "
                            "pv" -> "[Prime] "
                            "hs" -> "[Hotstar] "
                            else -> ""
                        }

                        newMovieSearchResponse("$ottBadge$title", "$activeDomain/newtv/post.php?id=$id&ott=$ott", type) {
                            this.posterUrl = poster
                        }
                    } ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.map { it.await() }.flatten()

        return@coroutineScope results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val activeDomain = resolveLiveDomain()
        val ott = if (url.contains("ott=pv")) "pv" else if (url.contains("ott=hs")) "hs" else "nf"
        val res = app.get(url, headers = getHeaders(ott)).text
        val resp = try { parseJson<NetMirrorPostResponse>(res) } catch (e: Exception) { null }
        val data = resp?.data ?: return null

        val title = data.title ?: "NetMirror TV"
        val poster = data.poster?.let { fixUrl(it, activeDomain) }
        val plot = data.desc
        val year = data.year?.toIntOrNull()
        val genres = data.genre?.split(",")?.map { it.trim() }

        val isSeries = data.type?.contains("series", ignoreCase = true) == true || !data.seasons.isNullOrEmpty()

        if (isSeries) {
            val episodesList = mutableListOf<Episode>()
            data.seasons?.forEach { season ->
                val seasonNum = season.season ?: 1
                val seasonId = season.id ?: data.id ?: ""
                var page = 1
                var hasNext = true

                while (hasNext && page <= 10) {
                    try {
                        val epUrl = "$activeDomain/newtv/episodes.php?id=$seasonId&page=$page"
                        val epRes = app.get(epUrl, headers = getHeaders(ott)).text
                        val epResp = try { parseJson<NetMirrorEpisodeResponse>(epRes) } catch (e: Exception) { null }
                        val eps = epResp?.data ?: break

                        eps.forEach { epItem ->
                            val epNum = epItem.episode ?: 1
                            val epId = epItem.id ?: return@forEach
                            episodesList.add(
                                newEpisode("$activeDomain/newtv/player.php?id=$epId&ott=$ott") {
                                    this.name = epItem.title ?: "Episode $epNum"
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = epItem.thumb?.let { fixUrl(it, activeDomain) }
                                    this.description = epItem.desc
                                }
                            )
                        }

                        val totalPages = epResp.totalPages ?: 1
                        hasNext = page < totalPages
                        page++
                    } catch (e: Exception) {
                        break
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
            }
        } else {
            val mediaId = data.playerId ?: data.id ?: ""
            return newMovieLoadResponse(title, url, TvType.Movie, "$activeDomain/newtv/player.php?id=$mediaId&ott=$ott") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ott = if (data.contains("ott=pv")) "pv" else if (data.contains("ott=hs")) "hs" else "nf"
        val res = app.get(data, headers = getHeaders(ott)).text
        val playerResp = try { parseJson<NetMirrorPlayerResponse>(res) } catch (e: Exception) { null }
        val playerData = playerResp?.data ?: return false

        playerData.subtitles?.forEach { sub ->
            val subUrl = sub.file ?: return@forEach
            subtitleCallback(
                SubtitleFile(
                    lang = sub.label ?: "English",
                    url = fixUrl(subUrl, mainUrl)
                )
            )
        }

        val candidateStreams = mutableListOf<Pair<String, String>>()
        playerData.streamUrl?.let { candidateStreams.add("Auto (HLS)" to it) }
        playerData.hls?.let { candidateStreams.add("Master HLS" to it) }
        playerData.mp4?.let { candidateStreams.add("Direct MP4" to it) }
        playerData.servers?.forEach { srv ->
            val url = srv.url ?: return@forEach
            val label = srv.name ?: "Server"
            candidateStreams.add(label to url)
        }

        var foundLinks = false
        candidateStreams.forEach { (name, linkUrl) ->
            if (linkUrl.isNotBlank()) {
                val fullUrl = fixUrl(linkUrl, mainUrl)
                if (fullUrl.contains(".m3u8")) {
                    try {
                        M3u8Helper.generateM3u8(
                            source = this.name,
                            streamUrl = fullUrl,
                            referer = defaultReferer,
                            quality = Qualities.Unknown.value,
                            headers = mapOf(
                                "User-Agent" to userAgentString,
                                "Referer" to defaultReferer
                            ),
                            name = "$name (HLS)"
                        ).forEach { link ->
                            callback.invoke(link)
                            foundLinks = true
                        }
                    } catch (e: Exception) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "$name (HLS)",
                                url = fullUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = defaultReferer
                                this.headers = mapOf(
                                    "User-Agent" to userAgentString,
                                    "Referer" to defaultReferer
                                )
                            }
                        )
                        foundLinks = true
                    }
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = name,
                            url = fullUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = defaultReferer
                            this.headers = mapOf(
                                "User-Agent" to userAgentString,
                                "Referer" to defaultReferer
                            )
                        }
                    )
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }

    private fun fixUrl(url: String, domain: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        return "$domain/${url.trimStart('/')}"
    }
}

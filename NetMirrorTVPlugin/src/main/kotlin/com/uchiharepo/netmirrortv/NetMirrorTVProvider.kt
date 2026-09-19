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
        TvType.Anime,
        TvType.AsianDrama
    )

    private val userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0"
    private val defaultReferer = "https://net52.cc"

    private fun getHeaders(ott: String = "nf"): Map<String, String> {
        return mapOf(
            "User-Agent" to userAgentString,
            "Accept" to "*/*",
            "Ott" to ott,
            "X-Requested-With" to "NetmirrorNewTV v1.0",
            "Referer" to "$mainUrl/"
        )
    }

    private val fallbackDomains = listOf(
        "https://tv.imgcdn.kim",
        "https://mobiledetects.com",
        "https://mobiledetect.app",
        "https://mobidetect.cc",
        "https://net52.cc",
        "https://net11.cc"
    )

    private suspend fun resolveLiveDomain(): String {
        for (dom in fallbackDomains) {
            try {
                val res = app.get("$dom/checknewtv.php", headers = mapOf("User-Agent" to userAgentString), timeout = 4).text
                if (res.isNotBlank()) {
                    if (res.contains("token_hash")) {
                        val tokenResponse = try { parseJson<NetMirrorTokenResponse>(res) } catch (e: Exception) { null }
                        val token = tokenResponse?.token_hash
                        if (!token.isNullOrBlank()) {
                            val decoded = String(Base64.decode(token.trim(), Base64.DEFAULT)).trim().trimEnd('/')
                            if (decoded.startsWith("http")) return decoded
                        }
                    }
                    val decoded = try { String(Base64.decode(res.trim(), Base64.DEFAULT)).trim().trimEnd('/') } catch (e: Exception) { "" }
                    if (decoded.startsWith("http")) return decoded
                }
            } catch (e: Exception) {
                // continue to next domain
            }
        }
        return mainUrl
    }

    // 8 Major OTT Platforms Supported by NetMirror
    override val mainPage = mainPageOf(
        "nf" to "Netflix Trending & Hits",
        "pv" to "Prime Video Popular",
        "hs" to "Disney+ Hotstar Specials",
        "sn" to "SonyLIV Originals & Shows",
        "ze" to "Zee5 Movies & Series",
        "jc" to "JioCinema Blockbusters",
        "ap" to "Apple TV+ Hits",
        "cr" to "Crunchyroll & Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val ott = request.data
        val activeDomain = resolveLiveDomain()
        val searchResponses = mutableListOf<SearchResponse>()

        // 1. First try direct catalog API
        try {
            val url = "$activeDomain/newtv/main.php"
            val res = app.get(url, headers = getHeaders(ott), timeout = 8).text
            val response = try { parseJson<NetMirrorCatalogResponse>(res) } catch (e: Exception) { null }

            response?.data?.forEach { section ->
                section.items?.forEach { item ->
                    val id = item.id ?: return@forEach
                    val title = item.title ?: "Untitled"
                    val poster = item.poster?.let { fixUrl(it, activeDomain) }
                        ?: "https://imgcdn.kim/poster/v/$id.jpg"
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
        } catch (e: Exception) {
            // Ignore failure, fall back to keyword query
        }

        // 2. Fallback query if catalog is empty
        if (searchResponses.isEmpty()) {
            val queryKeyword = when (ott) {
                "nf" -> "netflix"
                "pv" -> "prime"
                "hs" -> "hotstar"
                "sn" -> "sonyliv"
                "ze" -> "zee5"
                "jc" -> "jiocinema"
                "ap" -> "apple"
                "cr" -> "anime"
                else -> request.name.split(" ").firstOrNull() ?: "popular"
            }

            try {
                val searchUrl = "$activeDomain/newtv/search.php?s=${URLEncoder.encode(queryKeyword, "UTF-8")}"
                val res = app.get(searchUrl, headers = getHeaders(ott), timeout = 8).text
                val resp = try { parseJson<NetMirrorSearchResponse>(res) } catch (e: Exception) { null }

                resp?.data?.forEach { item ->
                    val id = item.id ?: return@forEach
                    val title = item.title ?: "Untitled"
                    val poster = item.poster?.let { fixUrl(it, activeDomain) }
                        ?: "https://imgcdn.kim/poster/v/$id.jpg"
                    val type = if (item.type?.contains("series", ignoreCase = true) == true) TvType.TvSeries else TvType.Movie

                    searchResponses.add(
                        newMovieSearchResponse(title, "$activeDomain/newtv/post.php?id=$id&ott=$ott", type) {
                            this.posterUrl = poster
                        }
                    )
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        return newHomePageResponse(request.name, searchResponses.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val activeDomain = resolveLiveDomain()
        val ottList = listOf("nf", "pv", "hs", "sn", "ze", "jc", "ap", "cr")
        val cleanQuery = query.trim()

        val results = ottList.map { ott ->
            async {
                try {
                    val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                    val searchUrl = "$activeDomain/newtv/search.php?s=$encoded"
                    val res = app.get(searchUrl, headers = getHeaders(ott), timeout = 8).text
                    val resp = try { parseJson<NetMirrorSearchResponse>(res) } catch (e: Exception) { null }
                    resp?.data?.mapNotNull { item ->
                        val id = item.id ?: return@mapNotNull null
                        val title = item.title ?: return@mapNotNull null
                        val poster = item.poster?.let { fixUrl(it, activeDomain) }
                            ?: "https://imgcdn.kim/poster/v/$id.jpg"
                        val type = if (item.type?.contains("series", ignoreCase = true) == true) TvType.TvSeries else TvType.Movie
                        val ottBadge = when (ott) {
                            "nf" -> "[Netflix] "
                            "pv" -> "[Prime] "
                            "hs" -> "[Hotstar] "
                            "sn" -> "[SonyLIV] "
                            "ze" -> "[Zee5] "
                            "jc" -> "[JioCinema] "
                            "ap" -> "[AppleTV+] "
                            "cr" -> "[Crunchyroll] "
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
        val ott = extractOttParam(url)
        val res = app.get(url, headers = getHeaders(ott), timeout = 10).text
        val resp = try { parseJson<NetMirrorPostResponse>(res) } catch (e: Exception) { null }
        val data = resp?.data ?: return null

        val title = data.title ?: "NetMirror TV"
        val poster = data.poster?.let { fixUrl(it, activeDomain) }
            ?: (data.id?.let { "https://imgcdn.kim/poster/v/$it.jpg" })
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
                        val epRes = app.get(epUrl, headers = getHeaders(ott), timeout = 8).text
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
        val ott = extractOttParam(data)
        val res = app.get(data, headers = getHeaders(ott), timeout = 10).text
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

    private fun extractOttParam(url: String): String {
        return when {
            url.contains("ott=pv") -> "pv"
            url.contains("ott=hs") -> "hs"
            url.contains("ott=sn") -> "sn"
            url.contains("ott=ze") -> "ze"
            url.contains("ott=jc") -> "jc"
            url.contains("ott=ap") -> "ap"
            url.contains("ott=cr") -> "cr"
            else -> "nf"
        }
    }

    private fun fixUrl(url: String, domain: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        return "$domain/${url.trimStart('/')}"
    }
}

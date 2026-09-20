package com.uchiharepo.netmirrortv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.regex.Pattern

class NetMirrorTVProvider : MainAPI() {
    override var name = "NetMirror TV"
    override var mainUrl = "https://net52.cc"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private val newTvBaseHeaders = mapOf(
        "Cache-Control" to "no-cache, no-store, must-revalidate",
        "Pragma" to "no-cache",
        "Expires" to "0",
        "X-Requested-With" to "NetmirrorNewTV v1.0",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
        "Accept" to "application/json, text/plain, */*",
        "Ott" to "nf"
    )

    private val newTvDomains = listOf(
        "aHR0cHM6Ly9tb2JpbGVkZXRlY3RzLmNvbQ==",
        "aHR0cHM6Ly9tb2JpbGVkZXRlY3QuYXBw",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFydA==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNj",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNsaWNr",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0Lmluaw==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmxpdmU=",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LnBybw=="
    )

    private var cachedApiUrl = "https://tv.imgcdn.kim"

    override val mainPage = mainPageOf(
        "CATEGORY_ANIME" to "⛩️ Anime & Animation Collection",
        "CATEGORY_MOVIES" to "🎬 Popular Movies & Blockbusters",
        "CATEGORY_SERIES" to "📺 Top TV Series & Web Shows",
        "CATEGORY_NETFLIX" to "🍿 Netflix Originals & Hits",
        "CATEGORY_PRIME" to "📦 Prime Video Specials",
        "CATEGORY_DISNEY" to "🌟 Disney+ & Marvel Universe",
        "CATEGORY_KDRAMA" to "🎎 K-Drama & Asian Series",
        "CATEGORY_BOLLYWOOD" to "🇮🇳 Bollywood & South Cinema"
    )

    private fun getCategoryKeywords(category: String): List<String> {
        return when (category) {
            "CATEGORY_ANIME" -> listOf("naruto", "titan", "dragon", "jujutsu", "demon", "piece", "solo")
            "CATEGORY_MOVIES" -> listOf("movie", "action", "war", "man", "night", "dark", "fast")
            "CATEGORY_SERIES" -> listOf("series", "house", "game", "stranger", "witcher", "vikings", "crown")
            "CATEGORY_NETFLIX" -> listOf("netflix", "money", "squid", "wednesday", "bridgerton", "lupin")
            "CATEGORY_PRIME" -> listOf("prime", "boys", "rings", "reacher", "fallout", "invincible")
            "CATEGORY_DISNEY" -> listOf("marvel", "avengers", "star", "disney", "spider", "loki")
            "CATEGORY_KDRAMA" -> listOf("korean", "kdrama", "queen", "glory", "vincenzo", "crash")
            "CATEGORY_BOLLYWOOD" -> listOf("hindi", "jawan", "pathaan", "kalki", "kgf", "animal", "salaar")
            else -> listOf(category)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val keywords = getCategoryKeywords(request.data)
        val seenIds = mutableSetOf<String>()
        val items = mutableListOf<SearchResponse>()

        for (k in keywords) {
            val url = "$mainUrl/search.php?s=${URLEncoder.encode(k, "UTF-8")}"
            val res = try {
                app.get(url, headers = commonHeaders, timeout = 6)
            } catch (_: Throwable) {
                null
            } ?: continue

            val data = tryParseJson<NetMirrorSearchData>(res.text) ?: continue
            data.searchResult.orEmpty().forEach { r ->
                val id = r.id ?: return@forEach
                if (seenIds.add(id)) {
                    val rawTitle = r.title ?: "Unknown"
                    val cleanTitle = cleanDisplayTitle(rawTitle)
                    val poster = r.image?.toHttps()?.takeIf { it.isNotBlank() }
                        ?: "https://imgcdn.kim/poster/v/$id.jpg"

                    val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
                    val targetUrl = "$mainUrl/watch?id=$id&title=$encodedTitle"

                    val tvType = when (request.data) {
                        "CATEGORY_ANIME" -> TvType.Anime
                        "CATEGORY_KDRAMA" -> TvType.AsianDrama
                        "CATEGORY_SERIES" -> TvType.TvSeries
                        else -> TvType.Movie
                    }

                    items.add(newMovieSearchResponse(cleanTitle, targetUrl, tvType) {
                        this.posterUrl = poster
                    })
                }
            }

            if (items.size >= 25) break
        }

        if (items.isEmpty()) return null
        val section = HomePageList(request.name, items, isHorizontalImages = true)
        return newHomePageResponse(listOf(section), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val url = "$mainUrl/search.php?s=${URLEncoder.encode(cleanQuery, "UTF-8")}"
        val res = try {
            app.get(url, headers = commonHeaders, timeout = 10)
        } catch (_: Throwable) {
            return emptyList()
        }

        val data = tryParseJson<NetMirrorSearchData>(res.text) ?: return emptyList()
        return data.searchResult.orEmpty().mapNotNull { r ->
            val id = r.id ?: return@mapNotNull null
            val rawTitle = r.title ?: "Unknown"
            val cleanTitle = cleanDisplayTitle(rawTitle)
            val poster = r.image?.toHttps()?.takeIf { it.isNotBlank() }
                ?: "https://imgcdn.kim/poster/v/$id.jpg"

            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val targetUrl = "$mainUrl/watch?id=$id&title=$encodedTitle"

            newMovieSearchResponse(cleanTitle, targetUrl, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val postId = when {
            url.contains("id=") -> url.substringAfter("id=").substringBefore("&").trim()
            url.contains("|") -> url.substringAfterLast("|").trim()
            else -> url.substringAfterLast("/").trim()
        }

        val parsedTitle = when {
            url.contains("title=") -> {
                try {
                    URLDecoder.decode(url.substringAfter("title=").substringBefore("&"), "UTF-8")
                } catch (_: Throwable) {
                    "NetMirror Title"
                }
            }
            url.contains("|") -> url.substringBefore("|").trim()
            else -> "NetMirror Title"
        }

        val fallbackTitle = cleanDisplayTitle(parsedTitle)
        val posterUrl = "https://imgcdn.kim/poster/v/$postId.jpg"
        val backdropUrl = "https://imgcdn.kim/poster/h/$postId.jpg"

        val apiBase = getApiBaseUrl()

        val postUrl = "$apiBase/newtv/post.php?id=$postId"
        val res = try {
            app.get(postUrl, headers = newTvBaseHeaders, timeout = 8)
        } catch (_: Throwable) {
            null
        }

        val postData = res?.text?.let { tryParseJson<NewTvPostData>(it) }
        if (postData != null && postData.status == "ok") {
            val realTitle = postData.title?.takeIf { it.isNotBlank() }?.let { cleanDisplayTitle(it) } ?: fallbackTitle
            val allEpisodes = mutableListOf<Episode>()
            val seenEpIds = mutableSetOf<String>()

            val seasonList = postData.season.orEmpty().filterNotNull().filter { !it.id.isNullOrBlank() }
            if (seasonList.isNotEmpty()) {
                seasonList.forEachIndexed { sIdx, seasonItem ->
                    val seasonId = seasonItem.id ?: return@forEachIndexed
                    val seasonNumMatch = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE).find(seasonItem.s ?: "")
                    val seasonNum = seasonNumMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (sIdx + 1)

                    var currentPage = 1
                    var hasMorePages = true

                    while (hasMorePages && currentPage <= 20) {
                        val epUrl = "$apiBase/newtv/episodes.php?id=$seasonId&page=$currentPage"
                        val epRes = try {
                            app.get(epUrl, headers = newTvBaseHeaders, timeout = 6)
                        } catch (_: Throwable) {
                            null
                        }

                        val epPageData = epRes?.text?.let { tryParseJson<NewTvPostData>(it) }
                        val pageEpisodes = epPageData?.episodes.orEmpty().filterNotNull().filter { !it.id.isNullOrBlank() }

                        if (pageEpisodes.isEmpty()) break

                        pageEpisodes.forEachIndexed { _, ep ->
                            val epId = ep.id ?: return@forEachIndexed
                            if (seenEpIds.add(epId)) {
                                val epNum = ep.ep?.toIntOrNull() ?: (allEpisodes.size + 1)
                                val epName = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum"

                                allEpisodes.add(newEpisode(epId) {
                                    this.name = epName
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = "https://imgcdn.kim/epimg/150/$epId.jpg"
                                    this.description = ep.epDesc
                                })
                            }
                        }

                        if (epPageData?.nextPageShow == 1) {
                            currentPage++
                        } else {
                            hasMorePages = false
                        }
                    }
                }
            }

            if (allEpisodes.isEmpty()) {
                val directEpisodes = postData.episodes.orEmpty().filterNotNull().filter { !it.id.isNullOrBlank() }
                directEpisodes.forEachIndexed { idx, ep ->
                    val epId = ep.id ?: return@forEachIndexed
                    if (seenEpIds.add(epId)) {
                        val epNum = ep.ep?.toIntOrNull() ?: (idx + 1)
                        val seasonNum = ep.info?.filterNotNull()
                            ?.firstOrNull { it.startsWith("S", ignoreCase = true) }
                            ?.substring(1)?.toIntOrNull() ?: 1

                        allEpisodes.add(newEpisode(epId) {
                            this.name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = "https://imgcdn.kim/epimg/150/$epId.jpg"
                            this.description = ep.epDesc
                        })
                    }
                }
            }

            if (allEpisodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(realTitle, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backdropUrl
                    this.plot = postData.desc
                    this.year = postData.year?.toIntOrNull()
                }
            } else {
                return newMovieLoadResponse(realTitle, url, TvType.Movie, postId) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backdropUrl
                    this.plot = postData.desc
                    this.year = postData.year?.toIntOrNull()
                }
            }
        }

        return newMovieLoadResponse(fallbackTitle, url, TvType.Movie, postId) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backdropUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = when {
            data.contains("id=") -> data.substringAfter("id=").substringBefore("&").trim()
            data.contains("|") -> data.substringAfterLast("|").trim()
            else -> data.trim().trim('/')
        }

        var foundAny = false

        // 1. Primary Source: NewTV Player API (Master Multi-Audio HLS Stream)
        try {
            val apiBase = getApiBaseUrl()
            val playerUrl = "$apiBase/newtv/player.php?id=$episodeId"
            val res = app.get(playerUrl, headers = newTvBaseHeaders, timeout = 8)
            val playerData = tryParseJson<NewTvPlayerResponse>(res.text)
            val videoLink = playerData?.videoLink

            if (!videoLink.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name [Server 1 - Auto Multi-Audio]",
                        url = videoLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = "https://net52.cc"
                    }
                )
                foundAny = true

                // Extract and safely encode WebVTT Subtitles
                try {
                    val m3u8Res = app.get(
                        videoLink,
                        headers = mapOf("Referer" to "https://net52.cc"),
                        timeout = 5
                    )
                    val subPattern = Pattern.compile("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="([^"]+)".*?URI="([^"]+)"""", Pattern.CASE_INSENSITIVE)
                    val subMatcher = subPattern.matcher(m3u8Res.text)
                    while (subMatcher.find()) {
                        val subName = subMatcher.group(1) ?: continue
                        var subUri = subMatcher.group(2) ?: continue
                        if (subUri.endsWith(".m3u8")) {
                            subUri = subUri.substringBeforeLast(".m3u8") + ".vtt"
                        }
                        val safeSubUri = subUri.replace("[", "%5B").replace("]", "%5D")
                        subtitleCallback(SubtitleFile(subName, safeSubUri))
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        // 2. Secondary Source: Native Net77 Play & Playlist Flow (Direct HD + SRT Subtitles)
        try {
            val playRes = app.post(
                "https://net77.cc/play.php",
                data = mapOf("id" to episodeId),
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "https://net77.cc/home",
                    "Origin" to "https://net77.cc"
                ),
                timeout = 8
            )
            val playData = tryParseJson<PlayResponse>(playRes.text)
            val hToken = playData?.h

            if (!hToken.isNullOrBlank()) {
                val tm = (System.currentTimeMillis() / 1000).toString()
                val playlistUrl = "https://net52.cc/playlist.php?id=$episodeId&tm=$tm&h=${URLEncoder.encode(hToken, "UTF-8")}"
                val plRes = app.get(
                    playlistUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Referer" to "https://net77.cc/home",
                        "Origin" to "https://net77.cc",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    timeout = 8
                )

                val playlistText = plRes.text.trim()
                val playlists: List<NetMirrorPlayList>? = if (playlistText.startsWith("[")) {
                    tryParseJson<List<NetMirrorPlayList>>(playlistText)
                } else {
                    tryParseJson<NetMirrorPlayList>(playlistText)?.let { listOf(it) }
                }

                playlists?.forEach { playlist ->
                    // Register direct SRT subtitles
                    playlist.tracks?.forEach { track ->
                        val subFile = track.file ?: return@forEach
                        val subUrl = when {
                            subFile.startsWith("//") -> "https:$subFile"
                            subFile.startsWith("http") -> subFile
                            else -> "https://subscdn.top$subFile"
                        }
                        val label = track.label ?: "English"
                        subtitleCallback(SubtitleFile(label, subUrl))
                    }

                    // Register video streams (Full HD, Mid HD, Low HD)
                    playlist.sources?.forEach { source ->
                        val rawFile = source.file ?: return@forEach
                        val streamUrl = if (rawFile.startsWith("http")) {
                            rawFile
                        } else {
                            "https://net77.cc$rawFile"
                        }

                        val label = source.label ?: "HD"
                        val qualityVal = when {
                            label.contains("Full", ignoreCase = true) || label.contains("1080") -> Qualities.P1080.value
                            label.contains("Mid", ignoreCase = true) || label.contains("720") -> Qualities.P720.value
                            label.contains("Low", ignoreCase = true) || label.contains("480") -> Qualities.P480.value
                            else -> Qualities.P720.value
                        }

                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name [Server 2 - $label]",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = qualityVal
                                this.referer = "https://net52.cc"
                            }
                        )
                        foundAny = true
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return foundAny
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val original = chain.request()
                val url = original.url

                // 1. Critical Domain Rewrite: nm-cdn fails DNS lookup, rewrite host to working freecdn host
                val newUrl = if (url.host.contains("nm-cdn")) {
                    val fixedHost = url.host.replace("nm-cdn", "freecdn")
                    url.newBuilder().host(fixedHost).build()
                } else {
                    url
                }

                // 2. NetMirror CDN requires Referer: https://net52.cc on all segment and media requests
                val referer = when {
                    url.host.contains("freecdn") || url.host.contains("nm-cdn") ||
                    url.host.contains("imgcdn") || url.host.contains("subscdn") -> "https://net52.cc"
                    !extractorLink.referer.isNullOrBlank() -> extractorLink.referer!!
                    else -> "https://net52.cc"
                }

                val newRequest = original.newBuilder()
                    .url(newUrl)
                    .removeHeader("User-Agent")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .removeHeader("Referer")
                    .header("Referer", referer)
                    .build()

                return chain.proceed(newRequest)
            }
        }
    }

    private suspend fun getApiBaseUrl(): String {
        if (cachedApiUrl.isNotBlank()) return cachedApiUrl
        for (encoded in newTvDomains) {
            val base = decodeBase64(encoded).trimEnd('/')
            try {
                val res = app.get("$base/checknewtv.php", headers = newTvBaseHeaders, timeout = 4)
                val token = tryParseJson<NewTvTokenResponse>(res.text)?.tokenHash
                if (!token.isNullOrBlank()) {
                    cachedApiUrl = decodeBase64(token).trimEnd('/')
                    return cachedApiUrl
                }
            } catch (_: Throwable) {
            }
        }
        return "https://tv.imgcdn.kim"
    }

    private fun cleanDisplayTitle(raw: String): String {
        return raw.trim()
            .replace(Regex("^https?://[^/]+/?"), "")
            .replace(Regex("^/+"), "")
            .trim()
    }

    private fun decodeBase64(value: String): String {
        return try {
            String(Base64.getDecoder().decode(value))
        } catch (_: Throwable) {
            value
        }
    }

    private fun String.toHttps(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http:") -> "https${substring(4)}"
        else -> this
    }
}

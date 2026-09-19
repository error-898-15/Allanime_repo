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
        "Referer" to "$mainUrl/"
    )

    private val newTvBaseHeaders = mapOf(
        "Cache-Control" to "no-cache, no-store, must-revalidate",
        "Pragma" to "no-cache",
        "Expires" to "0",
        "X-Requested-With" to "NetmirrorNewTV v1.0",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
        "Accept" to "application/json, text/plain, */*"
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
            "CATEGORY_ANIME" -> listOf("naruto", "shippuden", "titan", "dragon", "jujutsu", "demon", "piece", "bleach", "solo", "hunter", "ghoul", "death", "hero", "anime")
            "CATEGORY_MOVIES" -> listOf("movie", "action", "war", "man", "night", "dark", "dead", "fast", "love", "world", "super", "king")
            "CATEGORY_SERIES" -> listOf("series", "house", "game", "stranger", "bad", "boys", "witcher", "vikings", "crown", "last", "money", "dark")
            "CATEGORY_NETFLIX" -> listOf("netflix", "money", "witcher", "crown", "squid", "stranger", "wednesday", "bridgerton", "lupin", "ozark")
            "CATEGORY_PRIME" -> listOf("prime", "boys", "rings", "reacher", "fallout", "invincible", "jack", "fleabag", "citadel", "terminal")
            "CATEGORY_DISNEY" -> listOf("marvel", "avengers", "star", "disney", "spider", "batman", "loki", "mandalorian", "wandavision", "guardians")
            "CATEGORY_KDRAMA" -> listOf("korean", "kdrama", "drama", "queen", "glory", "vincenzo", "crash", "sweet", "all of us", "business")
            "CATEGORY_BOLLYWOOD" -> listOf("hindi", "tamil", "telugu", "jawan", "pathaan", "kalki", "kgf", "animal", "salaar", "dangal")
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
                app.get(url, headers = commonHeaders, timeout = 8)
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

                    val tvType = when {
                        request.data == "CATEGORY_ANIME" -> TvType.Anime
                        request.data == "CATEGORY_KDRAMA" -> TvType.AsianDrama
                        request.data == "CATEGORY_SERIES" -> TvType.TvSeries
                        else -> TvType.Movie
                    }

                    items.add(newMovieSearchResponse(cleanTitle, targetUrl, tvType) {
                        this.posterUrl = poster
                    })
                }
            }

            if (items.size >= 40) break
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
            app.get(url, headers = commonHeaders, timeout = 12)
        } catch (e: Throwable) {
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

        // Try rich NewTV API metadata
        for (ott in listOf("nf", "pv", "hs")) {
            val postUrl = "$apiBase/newtv/post.php?id=$postId"
            val headers = newTvBaseHeaders.toMutableMap().apply { this["Ott"] = ott }
            val res = try {
                app.get(postUrl, headers = headers, timeout = 8)
            } catch (_: Throwable) {
                null
            }

            val postData = res?.text?.let { tryParseJson<NewTvPostData>(it) }
            if (postData != null && postData.status == "ok") {
                val realTitle = postData.title?.takeIf { it.isNotBlank() }?.let { cleanDisplayTitle(it) } ?: fallbackTitle
                val episodes = postData.episodes.orEmpty().filterNotNull().filter { !it.id.isNullOrBlank() }

                if (episodes.isNotEmpty()) {
                    val csEpisodes = episodes.mapIndexedNotNull { idx, ep ->
                        val epId = ep.id ?: return@mapIndexedNotNull null
                        val epNum = ep.ep?.toIntOrNull() ?: (idx + 1)
                        val seasonNum = ep.info?.filterNotNull()
                            ?.firstOrNull { it.startsWith("S", ignoreCase = true) }
                            ?.substring(1)?.toIntOrNull() ?: 1

                        newEpisode(epId) {
                            this.name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = "https://imgcdn.kim/epimg/150/$epId.jpg"
                            this.description = ep.epDesc
                        }
                    }

                    return newTvSeriesLoadResponse(realTitle, url, TvType.TvSeries, csEpisodes) {
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
        }

        // Fallback to single movie load if NewTV post.php didn't return
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

        val apiBase = getApiBaseUrl()
        var foundAny = false

        // 1. Primary High-Speed NewTV Player API
        for (ott in listOf("nf", "pv", "hs")) {
            val playerUrl = "$apiBase/newtv/player.php?id=$episodeId"
            val headers = newTvBaseHeaders.toMutableMap().apply {
                this["Ott"] = ott
                this["Usertoken"] = ""
            }

            val res = try {
                app.get(playerUrl, headers = headers, timeout = 8)
            } catch (_: Throwable) {
                continue
            }

            val playerData = tryParseJson<NewTvPlayerResponse>(res.text) ?: continue
            val videoLink = playerData.videoLink
            if (videoLink.isNullOrBlank()) continue

            val refererHeader = playerData.referer ?: mainUrl

            // 1a. Emit Master M3U8 stream
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name [Auto/Master]",
                    url = videoLink,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.P1080.value
                    this.referer = refererHeader
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl
                    )
                }
            )
            foundAny = true

            // 1b. Inspect Master M3U8 to extract specific qualities and subtitles
            try {
                val m3u8Res = app.get(
                    videoLink,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Referer" to "$mainUrl/"
                    ),
                    timeout = 5
                )
                val m3u8Content = m3u8Res.text

                // Extract Subtitles
                val subPattern = Pattern.compile("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="([^"]+)".*?URI="([^"]+)"""", Pattern.CASE_INSENSITIVE)
                val subMatcher = subPattern.matcher(m3u8Content)
                while (subMatcher.find()) {
                    val subName = subMatcher.group(1) ?: continue
                    val subUri = subMatcher.group(2) ?: continue
                    subtitleCallback(SubtitleFile(subName, subUri))
                }

                // Extract Resolution Streams (e.g., 1080p, 720p, 480p)
                val streamPattern = Pattern.compile("""RESOLUTION=(\d+x\d+).*?\r?\n(https?://[^\r\n]+)""", Pattern.CASE_INSENSITIVE)
                val streamMatcher = streamPattern.matcher(m3u8Content)
                while (streamMatcher.find()) {
                    val resText = streamMatcher.group(1) ?: ""
                    val streamUrl = streamMatcher.group(2) ?: continue
                    val qualityVal = when {
                        resText.contains("1080") -> Qualities.P1080.value
                        resText.contains("720") -> Qualities.P720.value
                        resText.contains("480") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                    val label = when {
                        resText.contains("1080") -> "1080p Full HD"
                        resText.contains("720") -> "720p HD"
                        resText.contains("480") -> "480p SD"
                        else -> resText
                    }

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name [$label]",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = qualityVal
                            this.referer = "$mainUrl/"
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                                "Referer" to "$mainUrl/",
                                "Origin" to mainUrl
                            )
                        }
                    )
                }
            } catch (_: Throwable) {
            }

            break
        }

        // 2. Fallback to direct web playlist if needed
        if (!foundAny) {
            val playlistUrl = "$mainUrl/playlist.php?id=$episodeId"
            val res = try {
                app.get(playlistUrl, headers = commonHeaders, timeout = 8)
            } catch (_: Throwable) {
                null
            }

            val playlists = res?.text?.let { tryParseJson<List<NetMirrorPlayList>>(it) }
            playlists?.forEach { playlist ->
                playlist.tracks?.forEach { track ->
                    val subFile = track.file ?: return@forEach
                    val subUrl = if (subFile.startsWith("http")) subFile else "$mainUrl$subFile"
                    val subLabel = track.label ?: "Sub"
                    subtitleCallback(SubtitleFile(subLabel, subUrl))
                }

                playlist.sources?.forEach { source ->
                    val rawFile = source.file ?: return@forEach
                    val fileUrl = if (rawFile.startsWith("http")) rawFile else "$mainUrl$rawFile"
                    val label = source.label ?: "HD"

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name [$label]",
                            url = fileUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.P1080.value
                            this.referer = "$mainUrl/"
                            this.headers = commonHeaders
                        }
                    )
                    foundAny = true
                }
            }
        }

        return foundAny
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val original = chain.request()
                val newRequest = original.newBuilder()
                    .removeHeader("User-Agent")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .removeHeader("Referer")
                    .header("Referer", "$mainUrl/")
                    .removeHeader("Origin")
                    .header("Origin", mainUrl)
                    .removeHeader("Cookie")
                    .header("Cookie", "hd=on")
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

package com.uchiharepo.netmirrortv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

object NetmirrorThrottler {
    private const val MIN_INTERVAL_MS = 1200L
    private var lastRequest = 0L
    private val mutex = Mutex()

    suspend fun throttle() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val wait = MIN_INTERVAL_MS - (now - lastRequest)
            if (wait > 0) delay(wait)
            lastRequest = System.currentTimeMillis()
        }
    }
}

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
        "Origin" to mainUrl,
        "Cookie" to "hd=on"
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
    private var cookieValue = ""
    private val nativeCookies = mutableMapOf<String, String>()

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
                                val epPayload = LoadData(
                                    id = epId,
                                    title = realTitle,
                                    season = seasonNum,
                                    episode = epNum
                                ).toJson()

                                allEpisodes.add(newEpisode(epPayload) {
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

                        val epPayload = LoadData(
                            id = epId,
                            title = realTitle,
                            season = seasonNum,
                            episode = epNum
                        ).toJson()

                        allEpisodes.add(newEpisode(epPayload) {
                            this.name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = "https://imgcdn.kim/epimg/150/$epId.jpg"
                            this.description = ep.epDesc
                        })
                    }
                }
            }

            val moviePayload = LoadData(id = postId, title = realTitle).toJson()

            if (allEpisodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(realTitle, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backdropUrl
                    this.plot = postData.desc
                    this.year = postData.year?.toIntOrNull()
                }
            } else {
                return newMovieLoadResponse(realTitle, url, TvType.Movie, moviePayload) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backdropUrl
                    this.plot = postData.desc
                    this.year = postData.year?.toIntOrNull()
                }
            }
        }

        val fallbackPayload = LoadData(id = postId, title = fallbackTitle).toJson()
        return newMovieLoadResponse(fallbackTitle, url, TvType.Movie, fallbackPayload) {
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
        val loadData = tryParseJson<LoadData>(data) ?: run {
            val cleanId = when {
                data.contains("id=") -> data.substringAfter("id=").substringBefore("&").trim()
                data.contains("|") -> data.substringAfterLast("|").trim()
                else -> data.trim().trim('/')
            }
            LoadData(id = cleanId, title = "")
        }

        val episodeId = loadData.id
        val title = loadData.title
        var foundAny = false

        // 1. Check NewTV API Flow (Primary fast direct HLS)
        try {
            NetmirrorThrottler.throttle()
            val apiBase = getApiBaseUrl()
            val playerUrl = "$apiBase/newtv/player.php?id=$episodeId"
            val res = app.get(playerUrl, headers = newTvBaseHeaders, timeout = 7)
            val playerData = tryParseJson<NewTvPlayerResponse>(res.text)

            // CRITICAL CHECK: Only use if status is "ok"!
            // When status is "otp", NetMirror returns the honeypot rate-limit video 220884.
            if (playerData != null && playerData.status == "ok" && !playerData.videoLink.isNullOrBlank()) {
                val videoLink = playerData.videoLink
                val refererUrl = playerData.referer ?: apiBase

                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name [Direct Master HD]",
                        url = videoLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = refererUrl
                    }
                )
                foundAny = true
                return true
            }
        } catch (_: Throwable) {
        }

        // 2. Native Web Flow (play.php + playlist.php)
        try {
            NetmirrorThrottler.throttle()
            ensureNativeCookies(episodeId)

            val cookies = mutableMapOf<String, String>()
            cookies["hd"] = "on"
            cookies["ott"] = "nf"
            if (cookieValue.isNotEmpty()) cookies["t_hash_t"] = cookieValue
            cookies.putAll(nativeCookies)

            val playResp = app.post(
                "https://net77.cc/play.php",
                data = mapOf("id" to episodeId),
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin" to "https://net77.cc",
                    "Referer" to "https://net77.cc/home",
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ),
                cookies = cookies
            ).text

            val h = tryParseJson<PlayResponse>(playResp)?.h

            if (!h.isNullOrBlank()) {
                NetmirrorThrottler.throttle()
                val tm = (System.currentTimeMillis() / 1000).toString()
                val encTitle = URLEncoder.encode(title.ifBlank { "Video" }, "UTF-8")
                val encH = URLEncoder.encode(h, "UTF-8")
                val playlistUrl = "https://net77.cc/playlist.php?id=$episodeId&t=$encTitle&tm=$tm&h=$encH"

                val plRes = app.get(
                    playlistUrl,
                    headers = mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Referer" to "https://net77.cc/home",
                        "Origin" to "https://net77.cc",
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    ),
                    cookies = cookies
                ).text.trim()

                val playlist = if (plRes.startsWith("[")) {
                    tryParseJson<List<NetMirrorPlayList>>(plRes)?.firstOrNull()
                } else {
                    tryParseJson<NetMirrorPlayList>(plRes)
                }

                playlist?.sources?.forEach { source ->
                    val file = source.file ?: return@forEach
                    val streamUrl = if (file.startsWith("http")) file else "https://net77.cc$file"
                    val label = source.label ?: "HD"
                    val quality = when {
                        label.contains("1080", true) || label.contains("Full", true) -> Qualities.P1080.value
                        label.contains("720", true) || label.contains("Mid", true) -> Qualities.P720.value
                        label.contains("480", true) || label.contains("Low", true) -> Qualities.P480.value
                        else -> Qualities.P720.value
                    }

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name [$label]",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = quality
                            this.referer = "https://net77.cc/home"
                        }
                    )
                    foundAny = true
                }

                playlist?.tracks?.forEach { track ->
                    val file = track.file ?: return@forEach
                    if (track.kind.isNullOrBlank() || track.kind.equals("captions", true) || track.kind.equals("subtitles", true)) {
                        val subUrl = when {
                            file.startsWith("//") -> "https:$file"
                            !file.startsWith("http") -> "https://subscdn.top$file"
                            else -> file
                        }
                        val subLabel = track.label?.takeIf { it.isNotBlank() } ?: "English"
                        subtitleCallback(SubtitleFile(subLabel, subUrl))
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // 3. Fallback TMDB Embed Flow (hakunaymatata CDN)
        if (!foundAny && !loadData.tmdbId.isNullOrBlank()) {
            try {
                NetmirrorThrottler.throttle()
                val isMovie = loadData.season == null
                val embedUrl = if (isMovie) {
                    "https://net27.cc/api/embed-tmdb/${loadData.tmdbId}"
                } else {
                    "https://net27.cc/api/embed-tmdb/${loadData.tmdbId}?type=tv&s=${loadData.season}&e=${loadData.episode ?: 1}"
                }

                val net27Res = app.get(
                    embedUrl,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Referer" to "https://videodownloader.site/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                ).text
                val resObj = tryParseJson<Net27Response>(net27Res)

                if (resObj?.ok == true) {
                    resObj.streams?.forEach { s ->
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name [Direct ${s.resolution}p]",
                                url = s.url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://videodownloader.site/"
                                this.quality = s.resolution
                            }
                        )
                        foundAny = true
                    }

                    if (resObj.streams.isNullOrEmpty() && !resObj.mp4.isNullOrBlank()) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name [Direct MP4]",
                                url = resObj.mp4,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://videodownloader.site/"
                                this.quality = resObj.resolution?.toIntOrNull() ?: Qualities.P720.value
                            }
                        )
                        foundAny = true
                    }

                    resObj.captions?.forEach { cap ->
                        subtitleCallback(SubtitleFile(cap.name, cap.url))
                    }
                }
            } catch (_: Throwable) {
            }
        }

        return foundAny
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val originalRequest = chain.request()
                val url = originalRequest.url

                // 1. Rewrite dead nm-cdn DNS hosts to active freecdn hosts
                val newUrl = if (url.host.contains("nm-cdn")) {
                    url.newBuilder().host(url.host.replace("nm-cdn", "freecdn")).build()
                } else {
                    url
                }

                val host = newUrl.host
                val isNativeHost = host.contains("net52") ||
                    host.contains("net77") ||
                    host.contains("net22") ||
                    host.contains("net27") ||
                    host.contains("freecdn")

                // 2. Use link's own referer (crucial: videodownloader.site for net27 vs net77 for native)
                val referer = extractorLink.referer?.takeIf { it.isNotBlank() } ?: "https://net77.cc/home"

                val builder = originalRequest.newBuilder()
                    .url(newUrl)
                    .removeHeader("Referer")
                    .header("Referer", referer)

                // 3. Attach Origin and Cookies for native endpoints
                if (isNativeHost) {
                    builder.removeHeader("Origin")
                    builder.header("Origin", "https://net77.cc")

                    val cookies = mutableMapOf<String, String>()
                    cookies["hd"] = "on"
                    if (cookieValue.isNotEmpty()) cookies["t_hash_t"] = cookieValue
                    cookies.putAll(nativeCookies)

                    builder.removeHeader("Cookie")
                    builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }

                return chain.proceed(builder.build())
            }
        }
    }

    private suspend fun bypass(): String {
        return try {
            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin" to "https://net77.cc",
                "Referer" to "https://net77.cc/verify2",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
            )
            val formBody = FormBody.Builder()
                .add("g-recaptcha-response", UUID.randomUUID().toString())
                .build()
            val client = app.baseClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val request = Request.Builder()
                .url("https://net52.cc/verify.php")
                .post(formBody)
                .apply {
                    headers.forEach { (k, v) -> addHeader(k, v) }
                }
                .build()
            client.newCall(request).execute().use { response ->
                response.headers.values("Set-Cookie")
                    .firstOrNull { it.startsWith("t_hash_t=") }
                    ?.substringAfter("t_hash_t=")
                    ?.substringBefore(";")
                    .orEmpty()
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private suspend fun ensureNativeCookies(contentId: String) {
        if (cookieValue.isEmpty()) {
            cookieValue = bypass()
        }
        if (nativeCookies.containsKey("user_token") &&
            nativeCookies.containsKey("t_hash_p")
        ) {
            return
        }
        try {
            val homeResp = app.get(
                "https://net77.cc/home",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
            homeResp.headers.values("Set-Cookie").forEach { cookieStr ->
                val keyValue = cookieStr.split(";").firstOrNull()?.trim() ?: return@forEach
                val parts = keyValue.split("=", limit = 2)
                if (parts.size == 2) {
                    nativeCookies[parts[0]] = parts[1]
                }
            }
        } catch (_: Throwable) {
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

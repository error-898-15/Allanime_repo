package com.uchiharepo.cinevood

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class CinevoodProvider : MainAPI() {
    override var mainUrl = "https://cinevood.net"
    override var name = "CineVood"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val cfKiller = CloudflareKiller()

    companion object {
        private const val FAST_TIMEOUT = 10L
        private const val BACKUP_URL = "https://cinevood.rocks"

        val DEFAULT_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            "Referer" to "https://cinevood.net/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8"
        )
    }

    private suspend fun getDoc(url: String): Document {
        // Step 1: Direct fast fetch with proper browser headers (takes ~200ms without Cloudflare WebView)
        try {
            val res = app.get(url, headers = DEFAULT_HEADERS, timeout = FAST_TIMEOUT)
            val doc = res.document
            val title = doc.title().trim()
            if (title.isNotBlank() && !title.contains("Just a moment", ignoreCase = true) && !title.contains("Attention Required", ignoreCase = true)) {
                val hasArticles = doc.select("article, div.entry-content, div.thecontent, div.post-single-content, h1.title, h1").isNotEmpty()
                if (hasArticles) return doc
            }
        } catch (e: Exception) {
            // Proceed to backup or cfKiller
        }

        // Step 2: Try backup mirror directly (https://cinevood.rocks)
        val backupUrl = if (url.startsWith("http")) {
            url.replace(Regex("""^https?://[^/]+"""), BACKUP_URL)
        } else {
            "$BACKUP_URL/${url.removePrefix("/")}"
        }

        try {
            val res = app.get(backupUrl, headers = DEFAULT_HEADERS + mapOf("Referer" to "$BACKUP_URL/"), timeout = FAST_TIMEOUT)
            val doc = res.document
            val title = doc.title().trim()
            if (title.isNotBlank() && !title.contains("Just a moment", ignoreCase = true)) {
                return doc
            }
        } catch (e: Exception) {
            // Proceed to cfKiller
        }

        // Step 3: If blocked by Cloudflare challenge, invoke cfKiller with strict timeout (max 12s)
        val cfDoc = withTimeoutOrNull(12000L) {
            try {
                val res = app.get(url, headers = DEFAULT_HEADERS, interceptor = cfKiller, timeout = FAST_TIMEOUT)
                res.document
            } catch (e: Exception) {
                try {
                    val res = app.get(backupUrl, headers = DEFAULT_HEADERS, interceptor = cfKiller, timeout = FAST_TIMEOUT)
                    res.document
                } catch (ex: Exception) {
                    null
                }
            }
        }

        return cfDoc ?: Document(url)
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/bollywood/" to "Bollywood Movies",
        "$mainUrl/hollywood/" to "Hollywood Movies",
        "$mainUrl/hindi-dubbed/south-dubbed/" to "South Hindi Dubbed",
        "$mainUrl/hindi-dubbed/hollywood-dubbed/" to "Hollywood Dubbed",
        "$mainUrl/web-series/" to "Web Series",
        "$mainUrl/punjabi/" to "Punjabi Movies",
        "$mainUrl/bengali/" to "Bengali Movies",
        "$mainUrl/tv-shows/" to "TV Shows"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            val cleanBase = request.data.removeSuffix("/")
            "$cleanBase/page/$page/"
        }

        return try {
            val doc = getDoc(url)
            val home = doc.select("article.latestPost, article").mapNotNull {
                it.toSearchResult()
            }
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().replace(" ", "+")
        val searchUrl = "$mainUrl/?s=$cleanQuery"

        return try {
            val doc = getDoc(searchUrl)
            doc.select("article.latestPost, article").mapNotNull {
                it.toSearchResult()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun Element.extractCleanPoster(): String? {
        val img = this.selectFirst("div.featured-thumbnail img, div.entry-content img, div.post-single-content img, article img, img") ?: return null
        val candidates = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("srcset").substringBefore(" ").trim(),
            img.attr("src")
        )
        val raw = candidates.firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }?.trim() ?: return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$mainUrl$raw"
            else -> raw
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("header h2.title a, h2.title a, a.post-image") ?: return null
        val rawTitle = titleElement.attr("title").ifBlank { titleElement.text() }.trim()
        if (rawTitle.isBlank() || rawTitle.contains("Just a moment", ignoreCase = true)) return null
        val href = titleElement.attr("href").ifBlank { this.selectFirst("a")?.attr("href") } ?: return null

        val poster = this.extractCleanPoster()

        val isSeries = rawTitle.contains("Season", ignoreCase = true) ||
                rawTitle.contains("S0", ignoreCase = true) ||
                rawTitle.contains("Complete", ignoreCase = true) ||
                rawTitle.contains("Episode", ignoreCase = true) ||
                href.contains("web-series") ||
                href.contains("tv-shows")

        val cleanTitle = rawTitle.replace(Regex("""(?i)\s*CineVood.*"""), "").trim()
        val year = Regex("""\((19\d\d|20\d\d)\)""").find(cleanTitle)?.groupValues?.get(1) ?: ""

        val safeMeta = URLEncoder.encode("$cleanTitle|||$poster|||$year", "UTF-8")
        val finalUrl = "$href#cvmeta=$safeMeta"

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, finalUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(cleanTitle, finalUrl, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val rawUrl = url.substringBefore("#cvmeta=")
        val metaParam = url.substringAfter("#cvmeta=", "")
        var cachedTitle = ""
        var cachedPoster = ""
        var cachedYear: Int? = null

        if (metaParam.isNotBlank()) {
            try {
                val parts = URLDecoder.decode(metaParam, "UTF-8").split("|||")
                if (parts.isNotEmpty() && parts[0].isNotBlank()) cachedTitle = parts[0]
                if (parts.size > 1 && parts[1].isNotBlank()) cachedPoster = parts[1]
                if (parts.size > 2) cachedYear = parts[2].toIntOrNull()
            } catch (e: Exception) {
                // Ignore parse error
            }
        }

        val doc = getDoc(rawUrl)

        var parsedTitle = doc.selectFirst("h1.title, header h1, h1, .post-title")?.text()?.trim() ?: ""
        if (parsedTitle.contains("Just a moment", ignoreCase = true)) parsedTitle = ""

        if (parsedTitle.isBlank()) {
            val docTitle = doc.title().trim()
            if (!docTitle.contains("Just a moment", ignoreCase = true)) {
                parsedTitle = docTitle
            }
        }
        parsedTitle = parsedTitle.replace(Regex("""(?i)\s*CineVood.*"""), "").trim()

        val slugTitle = try {
            rawUrl.removeSuffix("/").substringAfterLast("/")
                .split("-")
                .filter { it.isNotBlank() && !it.matches(Regex("""(?i)brrip|webrip|web-dl|hdts|1080p|720p|480p|hevc|x264|x265|aac|esub|msub|cinevood|hindi|english|tamil|telugu|malayalam|bengali|hq|amzn|nf|snxt""")) }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        } catch (e: Exception) { "" }

        val finalTitle = when {
            parsedTitle.isNotBlank() -> parsedTitle
            cachedTitle.isNotBlank() -> cachedTitle
            slugTitle.isNotBlank() -> slugTitle
            else -> "CineVood Video"
        }

        val poster = doc.extractCleanPoster() ?: cachedPoster

        val plot = doc.selectFirst("div.entry-content p:matches((?i)storyline|synopsis|plot)")?.text()
            ?: doc.select("div.entry-content p, div.thecontent p, div.post-single-content p").firstOrNull { it.text().length > 35 && !it.text().contains("download", ignoreCase = true) }?.text()
            ?: "Watch $finalTitle on CineVood with multi-server high speed streaming."

        val year = Regex("""\((19\d\d|20\d\d)\)""").find(finalTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: cachedYear

        val tags = doc.select("div.tags a, p.post-meta a, span.category a, .thecategory a").map { it.text().trim() }.distinct()

        val isSeries = finalTitle.contains("Season", ignoreCase = true) ||
                finalTitle.contains("S0", ignoreCase = true) ||
                finalTitle.contains("Episode", ignoreCase = true) ||
                rawUrl.contains("web-series") ||
                rawUrl.contains("tv-shows")

        // Parse download / watch links
        val contentLinks = doc.select("div.entry-content a, div.post-single-content a, article a, div.thecontent a, div#content a, main a, a.btn, a.button, a[href]")
        val directServers = mutableListOf<CineServer>()
        val episodeMap = mutableMapOf<Int, MutableList<CineServer>>()

        for (link in contentLinks) {
            val href = link.attr("href").trim()
            val text = link.text().trim()
            val parentText = link.parent()?.text()?.trim() ?: ""

            if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript")) {
                continue
            }

            val lowerHref = href.lowercase()
            if (lowerHref.contains("telegram") || lowerHref.contains("facebook") ||
                lowerHref.contains("twitter") || lowerHref.contains("whatsapp") ||
                lowerHref.contains("instagram") || lowerHref.contains("pinterest") ||
                lowerHref.contains("winexch") || lowerHref.contains("tinyurl")
            ) {
                continue
            }

            val epMatch = Regex("""(?i)(?:Episode|EP|E)[\s\-_]*0*(\d+)""").find(text.ifBlank { parentText })
            if (epMatch != null) {
                val epNum = epMatch.groupValues[1].toIntOrNull() ?: 1
                val list = episodeMap.getOrPut(epNum) { mutableListOf() }
                list.add(CineServer(name = text.ifBlank { "Episode $epNum Server" }, url = href))
            } else {
                val isServer = lowerHref.contains("hubcloud") || lowerHref.contains("fastdl") ||
                    lowerHref.contains("drive") || lowerHref.contains("gdflix") ||
                    lowerHref.contains("pixel") || lowerHref.contains("link") ||
                    lowerHref.contains("download") || lowerHref.contains("stream") ||
                    lowerHref.contains("watch") || lowerHref.contains("gofile") ||
                    lowerHref.contains("filemoon") || lowerHref.contains("katfile") ||
                    lowerHref.contains("gadgetsweb") || lowerHref.contains("hblinks") ||
                    lowerHref.contains("gamerxyt") ||
                    text.contains("download", ignoreCase = true) || text.contains("server", ignoreCase = true) ||
                    text.contains("1080p", ignoreCase = true) || text.contains("720p", ignoreCase = true) ||
                    text.contains("480p", ignoreCase = true) || text.contains("4k", ignoreCase = true) ||
                    text.contains("watch", ignoreCase = true) || text.contains("stream", ignoreCase = true)

                if (isServer) {
                    val quality = when {
                        text.contains("2160p", ignoreCase = true) || text.contains("4k", ignoreCase = true) || lowerHref.contains("2160p") || lowerHref.contains("4k") -> "4K 2160p"
                        text.contains("1080p", ignoreCase = true) || lowerHref.contains("1080p") -> "FHD 1080p"
                        text.contains("720p", ignoreCase = true) || lowerHref.contains("720p") -> "HD 720p"
                        text.contains("480p", ignoreCase = true) || lowerHref.contains("480p") -> "SD 480p"
                        else -> "Direct Stream"
                    }
                    val label = if (text.isNotBlank() && text.length < 40) text else "CineVood $quality Server"
                    directServers.add(CineServer(name = label, url = href))
                }
            }
        }

        // TV Series Handling
        if (isSeries && episodeMap.isNotEmpty()) {
            val episodes = episodeMap.map { (epNum, servers) ->
                val epData = CineEpisodeData(
                    name = "Episode $epNum",
                    servers = servers
                ).toJson()
                newEpisode(epData) {
                    this.name = "Episode $epNum"
                    this.season = 1
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }
            return newTvSeriesLoadResponse(finalTitle, rawUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        // Movie handling
        val passData = CineMovieData(
            title = finalTitle,
            servers = directServers
        ).toJson()

        return newMovieLoadResponse(finalTitle, rawUrl, TvType.Movie, passData) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val servers = try {
            val movie = parseJson<CineMovieData>(data)
            movie.servers
        } catch (e: Exception) {
            try {
                val ep = parseJson<CineEpisodeData>(data)
                ep.servers
            } catch (ex: Exception) {
                emptyList()
            }
        }

        var loadedAny = false

        for (server in servers) {
            val serverUrl = server.url
            try {
                val lowerUrl = serverUrl.lowercase()

                if (lowerUrl.contains("hubcloud") || lowerUrl.contains("gadgetsweb") ||
                    lowerUrl.contains("hblinks") || lowerUrl.contains("gamerxyt")
                ) {
                    if (resolveHubCloud(serverUrl, subtitleCallback, callback)) loadedAny = true
                } else if (lowerUrl.contains("pixeldrain.com")) {
                    val id = serverUrl.substringAfterLast("/u/").substringAfterLast("/").substringBefore("?")
                    if (id.isNotBlank()) {
                        val directUrl = "https://pixeldrain.com/api/file/$id"
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${server.name} [PixelDrain Direct CDN]",
                                url = directUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://pixeldrain.com/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                        loadedAny = true
                    }
                } else if (lowerUrl.contains("storage.googleapis.com") || lowerUrl.contains("r2.dev") || lowerUrl.contains("workers.dev")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${server.name} [Cloud Direct Video]",
                            url = serverUrl,
                            type = if (lowerUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    loadedAny = true
                } else if (lowerUrl.endsWith(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${server.name} [HLS Master Stream]",
                            url = serverUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    loadedAny = true
                } else if (lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".mkv")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${server.name} [Direct Stream]",
                            url = serverUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    loadedAny = true
                } else if (lowerUrl.contains("cinevood")) {
                    // Fallback: server is an article link -> inspect page for download/stream buttons
                    val pageDoc = getDoc(serverUrl)
                    val allPageLinks = pageDoc.select("a[href]")

                    for (l in allPageLinks) {
                        val h = l.attr("href").trim()
                        val lowerH = h.lowercase()
                        if (lowerH.contains("hubcloud") || lowerH.contains("gadgetsweb") ||
                            lowerH.contains("hblinks") || lowerH.contains("gamerxyt")
                        ) {
                            if (resolveHubCloud(h, subtitleCallback, callback)) loadedAny = true
                        } else if (lowerH.contains("pixeldrain.com")) {
                            val id = h.substringAfterLast("/u/").substringAfterLast("/").substringBefore("?")
                            if (id.isNotBlank()) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${l.text().ifBlank { "PixelDrain CDN" }}",
                                        url = "https://pixeldrain.com/api/file/$id",
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://pixeldrain.com/"
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                                loadedAny = true
                            }
                        } else if (lowerH.contains("storage.googleapis.com") || lowerH.contains("r2.dev")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${l.text().ifBlank { "Direct Cloud Stream" }}",
                                    url = h,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = serverUrl
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            loadedAny = true
                        } else if (lowerH.contains("fastdl") || lowerH.contains("drive") || lowerH.contains("gofile") ||
                                   lowerH.contains("filemoon") || lowerH.contains("streamtape") || lowerH.contains("dood")) {
                            if (loadExtractor(h, "$mainUrl/", subtitleCallback, callback)) loadedAny = true
                        }
                    }

                    // Also search raw page HTML with regex for hidden redirect/storage links
                    val rawHtml = pageDoc.html()
                    Regex("""https?://[^\s"'<>\\]+?(?:hubcloud|gadgetsweb|hblinks|gamerxyt)[^\s"'<>\\]*""").findAll(rawHtml).forEach { r ->
                        val target = r.value.trimEnd('"', '\'', '\\', ')')
                        if (resolveHubCloud(target, subtitleCallback, callback)) loadedAny = true
                    }
                } else {
                    // General CloudStream extractor
                    if (loadExtractor(serverUrl, "$mainUrl/", subtitleCallback, callback)) {
                        loadedAny = true
                    }
                }
            } catch (e: Exception) {
                // Continue to next server
            }
        }
        return loadedAny
    }

    private suspend fun resolveHubCloud(
        hubUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anyLoaded = false
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(hubUrl)

        var hops = 0
        while (queue.isNotEmpty() && hops < 6) {
            hops++
            val currentUrl = queue.removeFirst()
            if (visited.contains(currentUrl)) continue
            visited.add(currentUrl)

            val currentDoc = try {
                app.get(
                    currentUrl,
                    headers = mapOf("Referer" to "$mainUrl/"),
                    interceptor = cfKiller,
                    timeout = 15
                ).document
            } catch (e: Exception) {
                continue
            }

            val currentHtml = currentDoc.html()

            // 1. PixelDrain links anywhere in document
            val pixelDrainRegex = Regex("""https?://(?:www\.)?pixeldrain\.com/(?:u|api/file)/([a-zA-Z0-9_-]+)""")
            pixelDrainRegex.findAll(currentHtml).forEach { match ->
                val id = match.groupValues[1]
                if (id.isNotBlank() && !visited.contains("pixel_$id")) {
                    visited.add("pixel_$id")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "HubCloud -> PixelDrain 1080p Direct CDN",
                            url = "https://pixeldrain.com/api/file/$id",
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://pixeldrain.com/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    anyLoaded = true
                }
            }

            // 2. Direct Cloud Storage / Googleapis / R2 / Workers CDN links
            val directStorageRegex = Regex("""https?://[^\s"'<>\\]*?(?:storage\.googleapis\.com|[a-zA-Z0-9_-]+\.r2\.dev|[a-zA-Z0-9_-]+\.workers\.dev)[^\s"'<>\\]+""")
            directStorageRegex.findAll(currentHtml).forEach { match ->
                val directUrl = match.value.trimEnd('"', '\'', '\\', ')')
                if (directUrl.isNotBlank() && !visited.contains(directUrl)) {
                    visited.add(directUrl)
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "HubCloud Fast Cloud CDN",
                            url = directUrl,
                            type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = currentUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                    anyLoaded = true
                }
            }

            // 3. Media streams (.m3u8, .mp4, .mkv) in HTML/scripts
            val mediaRegex = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mkv)(?:\?[^\s"'<>\\]*)?""")
            mediaRegex.findAll(currentHtml).forEach { match ->
                val mediaUrl = match.value.trimEnd('"', '\'', '\\', ')')
                if (mediaUrl.isNotBlank() && !visited.contains(mediaUrl)) {
                    visited.add(mediaUrl)
                    val isM3u8 = mediaUrl.contains(".m3u8")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "HubCloud Master Stream",
                            url = mediaUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = currentUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                    anyLoaded = true
                }
            }

            // 4. Anchor tags (buttons, intermediate redirects, and download links)
            val allLinks = currentDoc.select("a[href]")
            for (link in allLinks) {
                val href = link.attr("href").trim()
                val text = link.text().trim()
                val cls = link.attr("class").trim()

                if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript")) continue

                val fullHref = if (href.startsWith("http")) href else {
                    try {
                        val baseUri = currentDoc.baseUri().ifBlank { currentUrl }
                        URI(baseUri).resolve(href).toString()
                    } catch (e: Exception) { "" }
                }
                if (fullHref.isBlank() || visited.contains(fullHref)) continue

                val lowerHref = fullHref.lowercase()
                if (lowerHref.contains("telegram") || lowerHref.contains("facebook") ||
                    lowerHref.contains("twitter") || lowerHref.contains("whatsapp") ||
                    lowerHref.contains("instagram") || lowerHref.contains("winexch") ||
                    lowerHref.contains("tinyurl") || lowerHref.contains("one.one.one.one") ||
                    lowerHref.contains("a-ads") || lowerHref.contains("google.com/search") ||
                    lowerHref.contains("/admin") || lowerHref.contains("/login")
                ) {
                    continue
                }

                // Intermediate hops (gamerxyt, hubcloud.php, /drive/, /video/, gadgetsweb, hblinks)
                val isIntermediate = lowerHref.contains("gamerxyt.com") ||
                    lowerHref.contains("hubcloud.php") ||
                    lowerHref.contains("/drive/") ||
                    lowerHref.contains("/video/") ||
                    lowerHref.contains("gadgetsweb") ||
                    lowerHref.contains("hblinks") ||
                    text.contains("generate direct download link", ignoreCase = true) ||
                    text.contains("click here to download", ignoreCase = true)

                if (isIntermediate) {
                    if (!visited.contains(fullHref)) {
                        queue.add(fullHref)
                    }
                    continue
                }

                // Final download / streaming buttons
                val isDownload = cls.contains("btn") ||
                    cls.contains("download") ||
                    lowerHref.contains("pixeldrain") ||
                    lowerHref.contains("storage.googleapis") ||
                    lowerHref.contains("r2.dev") ||
                    lowerHref.contains("workers.dev") ||
                    lowerHref.contains("fastdl") ||
                    lowerHref.contains("gofile") ||
                    lowerHref.contains("filemoon") ||
                    lowerHref.contains("streamtape") ||
                    lowerHref.contains("dood") ||
                    text.contains("download", ignoreCase = true) ||
                    text.contains("server", ignoreCase = true) ||
                    text.contains("zipdisk", ignoreCase = true) ||
                    text.contains("fast", ignoreCase = true) ||
                    text.contains("direct", ignoreCase = true) ||
                    text.contains("watch", ignoreCase = true) ||
                    text.contains("stream", ignoreCase = true)

                if (isDownload) {
                    visited.add(fullHref)

                    if (lowerHref.contains("pixeldrain.com")) {
                        val id = fullHref.substringAfterLast("/u/").substringAfterLast("/").substringBefore("?")
                        if (id.isNotBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "HubCloud -> PixelDrain 1080p CDN",
                                    url = "https://pixeldrain.com/api/file/$id",
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://pixeldrain.com/"
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            anyLoaded = true
                        }
                    } else if (lowerHref.contains("gofile") || lowerHref.contains("filemoon") ||
                               lowerHref.contains("streamtape") || lowerHref.contains("dood")) {
                        if (loadExtractor(fullHref, currentUrl, subtitleCallback, callback)) {
                            anyLoaded = true
                        }
                    } else {
                        val isM3u8 = lowerHref.contains(".m3u8")
                        val cleanName = if (text.isNotBlank() && text.length < 50) text else "HubCloud Fast Server"
                        val quality = when {
                            text.contains("1080") || fullHref.contains("1080") -> Qualities.P1080.value
                            text.contains("720") || fullHref.contains("720") -> Qualities.P720.value
                            text.contains("480") || fullHref.contains("480") -> Qualities.P480.value
                            text.contains("2160") || text.contains("4k") || fullHref.contains("4k") -> Qualities.P2160.value
                            else -> Qualities.P1080.value
                        }
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "HubCloud ($cleanName)",
                                url = fullHref,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = currentUrl
                                this.quality = quality
                            }
                        )
                        anyLoaded = true
                    }
                }
            }

            // 5. Embedded video iframes
            currentDoc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("ads") && !src.contains("banner")) {
                    val resolvedEmbed = if (src.startsWith("//")) "https:$src" else src
                    if (loadExtractor(resolvedEmbed, currentUrl, subtitleCallback, callback)) {
                        anyLoaded = true
                    }
                }
            }
        }

        return anyLoaded
    }
}

data class CineServer(
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String
)

data class CineMovieData(
    @JsonProperty("title") val title: String,
    @JsonProperty("servers") val servers: List<CineServer>
)

data class CineEpisodeData(
    @JsonProperty("name") val name: String,
    @JsonProperty("servers") val servers: List<CineServer>
)

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class CinevoodProvider : MainAPI() {
    override var mainUrl = "https://cinevood.vip"
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
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        val MIRRORS = listOf(
            "https://cinevood.vip",
            "https://cinevood.rocks",
            "https://cinevood.net",
            "https://cinevood.cv"
        )
    }

    private suspend fun getDoc(url: String): Document {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        // 1. Try URL with CloudflareKiller
        try {
            val res = app.get(url, headers = headers, interceptor = cfKiller)
            val doc = res.document
            val title = doc.title().trim()
            if (title.isNotBlank() && !title.contains("Just a moment", ignoreCase = true)) {
                return doc
            }
        } catch (e: Exception) {
            // Fall through to mirrors
        }

        // 2. Try alternate mirrors if first had Cloudflare challenge or network error
        for (mirror in MIRRORS) {
            val mirrorUrl = if (url.startsWith("http")) {
                url.replace(Regex("""https?://[^/]+"""), mirror)
            } else {
                "$mirror$url"
            }
            if (mirrorUrl == url) continue
            try {
                val res = app.get(mirrorUrl, headers = headers, interceptor = cfKiller)
                val doc = res.document
                val title = doc.title().trim()
                if (title.isNotBlank() && !title.contains("Just a moment", ignoreCase = true)) {
                    return doc
                }
            } catch (e: Exception) {
                // Try next mirror
            }
        }

        // 3. Fallback: app.get directly with interceptor
        return app.get(url, headers = headers, interceptor = cfKiller).document
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Releases",
        "$mainUrl/bollywood/page/" to "Bollywood Movies",
        "$mainUrl/hollywood/page/" to "Hollywood Movies",
        "$mainUrl/hindi-dubbed/south-dubbed/page/" to "South Hindi Dubbed",
        "$mainUrl/hindi-dubbed/hollywood-dubbed/page/" to "Hollywood Hindi Dubbed",
        "$mainUrl/web-series/page/" to "Web Series",
        "$mainUrl/punjabi/page/" to "Punjabi Movies",
        "$mainUrl/bengali/page/" to "Bengali Movies",
        "$mainUrl/tv-shows/page/" to "TV Shows",
        "$mainUrl/others/page/" to "Others"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            request.data.removeSuffix("page/")
        } else {
            "${request.data}$page/"
        }

        val doc = getDoc(url)
        val home = doc.select("article.latestPost, article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().replace(" ", "+")
        val searchUrl = "$mainUrl/?s=$cleanQuery"

        val doc = getDoc(searchUrl)
        return doc.select("article.latestPost, article").mapNotNull {
            it.toSearchResult()
        }
    }

    // Posters fix: filters out base64 placeholders and gets real poster from data-src / data-lazy-src
    private fun Element.extractCleanPoster(): String? {
        val img = this.selectFirst("div.featured-thumbnail img, div.entry-content img, div.post-single-content img, article img, img") ?: return null
        val candidates = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("srcset").substringBefore(" ").trim(),
            img.attr("src")
        )
        return candidates.firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }
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
            ?: doc.select("div.entry-content p, div.thecontent p").firstOrNull { it.text().length > 35 && !it.text().contains("download", ignoreCase = true) }?.text()
            ?: "Watch $finalTitle on CineVood with multi-server high speed streaming."

        val year = Regex("""\((19\d\d|20\d\d)\)""").find(finalTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: cachedYear

        val tags = doc.select("div.tags a, p.post-meta a, span.category a, .thecategory a").map { it.text().trim() }.distinct()

        val isSeries = finalTitle.contains("Season", ignoreCase = true) ||
                finalTitle.contains("S0", ignoreCase = true) ||
                finalTitle.contains("Episode", ignoreCase = true) ||
                rawUrl.contains("web-series") ||
                rawUrl.contains("tv-shows")

        val contentLinks = doc.select("div.entry-content a, div.post-single-content a, article a, div.thecontent a, a.btn, a.button")
        val directServers = mutableListOf<CineServer>()
        val episodeMap = mutableMapOf<Int, MutableList<CineServer>>()

        for (link in contentLinks) {
            val href = link.attr("href").trim()
            val text = link.text().trim()
            if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript") ||
                href.contains("telegram") || href.contains("facebook") || href.contains("twitter") ||
                href.contains("whatsapp") || href.contains("instagram") || href.contains("pinterest")
            ) {
                continue
            }

            val epMatch = Regex("""(?i)(?:Episode|EP|E)[\s\-_]*0*(\d+)""").find(text)
            if (epMatch != null) {
                val epNum = epMatch.groupValues[1].toIntOrNull() ?: 1
                val list = episodeMap.getOrPut(epNum) { mutableListOf() }
                list.add(CineServer(name = text.ifBlank { "Episode $epNum" }, url = href))
            } else if (
                href.contains("hubcloud") || href.contains("fastdl") || href.contains("drive") ||
                href.contains("gdflix") || href.contains("pixel") || href.contains("link") ||
                href.contains("download") || href.contains("stream") || href.contains("watch") ||
                href.contains("gofile") || href.contains("filemoon") || href.contains("katfile")
            ) {
                val quality = when {
                    text.contains("2160p", ignoreCase = true) || text.contains("4k", ignoreCase = true) -> "4K 2160p"
                    text.contains("1080p", ignoreCase = true) -> "FHD 1080p"
                    text.contains("720p", ignoreCase = true) -> "HD 720p"
                    text.contains("480p", ignoreCase = true) -> "SD 480p"
                    else -> "Direct Stream"
                }
                val label = if (text.isNotBlank() && text.length < 40) text else "CineVood $quality Server"
                directServers.add(CineServer(name = label, url = href))
            }
        }

        if (directServers.isEmpty()) {
            for (link in contentLinks) {
                val href = link.attr("href").trim()
                if (href.startsWith("http") && !href.contains("cinevood") && !href.contains("wordpress") && !href.contains("gravatar")) {
                    directServers.add(CineServer(name = link.text().ifBlank { "High Speed Cloud Server" }, url = href))
                }
            }
        }

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

        val passData = CineMovieData(
            title = finalTitle,
            servers = directServers.ifEmpty { listOf(CineServer("CineVood Direct Server", rawUrl)) }
        ).toJson()

        return newMovieLoadResponse(finalTitle, rawUrl, TvType.Movie, passData) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // Links resolver fix: multi-server extraction with PixelDrain 1080p, FastDL, HLS & Drive redirection
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
                if (serverUrl.contains("hubcloud") || serverUrl.contains("gadgetsweb") || serverUrl.contains("hblinks")) {
                    if (resolveHubCloud(serverUrl, subtitleCallback, callback)) loadedAny = true
                } else if (serverUrl.contains("pixeldrain.com")) {
                    val id = serverUrl.substringAfterLast("/u/").substringAfterLast("/")
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
                } else if (serverUrl.contains("fastdl") || serverUrl.contains("gofile") || serverUrl.contains("streamtape") || serverUrl.contains("filemoon") || serverUrl.contains("dood")) {
                    if (loadExtractor(serverUrl, "$mainUrl/", subtitleCallback, callback)) {
                        loadedAny = true
                    }
                } else if (serverUrl.endsWith(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${server.name} [HLS Stream]",
                            url = serverUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    loadedAny = true
                } else if (serverUrl.endsWith(".mp4") || serverUrl.endsWith(".mkv")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${server.name} [Direct MP4]",
                            url = serverUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P720.value
                        }
                    )
                    loadedAny = true
                } else if (serverUrl.contains("cinevood")) {
                    val pageDoc = getDoc(serverUrl)
                    val links = pageDoc.select("div.entry-content a, div.thecontent a, a.btn, a.button")
                    for (l in links) {
                        val h = l.attr("href")
                        if (h.contains("hubcloud") || h.contains("gadgetsweb") || h.contains("hblinks")) {
                            if (resolveHubCloud(h, subtitleCallback, callback)) loadedAny = true
                        } else if (h.contains("pixeldrain")) {
                            val id = h.substringAfterLast("/u/").substringAfterLast("/")
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
                        } else if (h.contains("fastdl") || h.contains("drive") || h.contains("stream") || h.contains("gofile") || h.contains("filemoon")) {
                            if (loadExtractor(h, "$mainUrl/", subtitleCallback, callback)) loadedAny = true
                        }
                    }
                } else {
                    if (loadExtractor(serverUrl, "$mainUrl/", subtitleCallback, callback)) {
                        loadedAny = true
                    }
                }
            } catch (e: Exception) {
                // Continue
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
        val hubDoc = try {
            app.get(
                hubUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                ),
                interceptor = cfKiller
            ).document
        } catch (e: Exception) {
            return false
        }

        // Intermediate drive / redirect pages resolver
        val intermediateLinks = hubDoc.select("a[href*='/drive/'], a[href*='/video/'], a[href*='hubcloud'], a.btn-success, a.btn-primary")
        for (iLink in intermediateLinks) {
            val href = iLink.attr("href")
            if (href.isNotBlank() && href != hubUrl && (href.contains("/drive/") || href.contains("/video/"))) {
                try {
                    val subDoc = app.get(
                        href,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to hubUrl
                        ),
                        interceptor = cfKiller
                    ).document

                    subDoc.select("a[href*='pixeldrain.com']").forEach { pLink ->
                        val pUrl = pLink.attr("href")
                        val id = pUrl.substringAfterLast("/u/").substringAfterLast("/")
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
                    }

                    subDoc.select("a.btn, a[href*='download'], a[href*='r2.dev'], a[href*='workers.dev'], a[href*='fastdl'], a[href*='.mp4'], a[href*='.m3u8']").forEach { fLink ->
                        val fHref = fLink.attr("href")
                        val fText = fLink.text().trim()
                        if (fHref.isNotBlank() && !fHref.startsWith("#") && !fHref.startsWith("javascript") && !fHref.contains("pixeldrain")) {
                            if (fHref.endsWith(".m3u8") || fHref.endsWith(".mp4")) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "HubCloud Stream (${fText.ifBlank { "Fast Server" }})",
                                        url = fHref,
                                        type = if (fHref.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = href
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                                anyLoaded = true
                            } else {
                                if (loadExtractor(fHref, href, subtitleCallback, callback)) anyLoaded = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Continue
                }
            }
        }

        // Direct PixelDrain resolution
        hubDoc.select("a[href*='pixeldrain.com']").forEach { pLink ->
            val href = pLink.attr("href")
            val id = href.substringAfterLast("/u/").substringAfterLast("/")
            if (id.isNotBlank()) {
                val streamUrl = "https://pixeldrain.com/api/file/$id"
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "HubCloud -> PixelDrain 1080p CDN",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://pixeldrain.com/"
                        this.quality = Qualities.P1080.value
                    }
                )
                anyLoaded = true
            }
        }

        // Video Player embed
        hubDoc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("ads")) {
                val resolvedEmbed = if (src.startsWith("//")) "https:$src" else src
                if (loadExtractor(resolvedEmbed, hubUrl, subtitleCallback, callback)) {
                    anyLoaded = true
                }
            }
        }

        // Fast Server & Direct Streams
        val fastLinks = hubDoc.select("a.btn, a[href*='download'], a[href*='r2.dev'], a[href*='workers.dev'], a[href*='fastdl']")
        for (fLink in fastLinks) {
            val href = fLink.attr("href")
            val text = fLink.text().trim()
            if (href.isNotBlank() && !href.startsWith("#") && !href.startsWith("javascript")) {
                if (href.contains("pixeldrain")) continue
                if (loadExtractor(href, hubUrl, subtitleCallback, callback)) {
                    anyLoaded = true
                } else if (href.endsWith(".m3u8") || href.endsWith(".mp4")) {
                    val isM3u8 = href.endsWith(".m3u8")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "HubCloud Fast Server (${text.ifBlank { "Direct Stream" }})",
                            url = href,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = hubUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                    anyLoaded = true
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

package com.uchiharepo.cinevood

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class CineVoodProvider : MainAPI() {
    override var mainUrl = "https://cinevood.rocks"
    override var name = "CineVood"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // Search engine referer bypasses Cloudflare anti-scraping WAF
        const val BYPASS_REFERER = "https://www.google.com/"

        val MIRROR_DOMAINS = listOf(
            "https://cinevood.rocks",
            "https://cinevood.bingo",
            "https://cinevood.cv",
            "https://new1.cinevood.cv",
            "https://1cinevood.eu",
            "https://cinevood.net",
            "https://cinevood.site"
        )
    }

    private suspend fun getDocument(url: String, referer: String = BYPASS_REFERER): Document {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        return try {
            app.get(url, headers = headers).document
        } catch (e: Exception) {
            val currentDomain = try { URI(url).host } catch (_: Exception) { null }
            var lastErr: Exception = e
            for (mirror in MIRROR_DOMAINS) {
                val mirrorHost = try { URI(mirror).host } catch (_: Exception) { null }
                if (currentDomain != null && mirrorHost != null && mirrorHost.equals(currentDomain, ignoreCase = true)) continue
                val fallbackUrl = if (currentDomain != null) url.replace("https://$currentDomain", mirror) else mirror
                try {
                    return app.get(fallbackUrl, headers = headers).document
                } catch (err: Exception) {
                    lastErr = err
                }
            }
            throw lastErr
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/bollywood/" to "Bollywood Movies",
        "$mainUrl/hollywood/" to "Hollywood Movies",
        "$mainUrl/hindi-dubbed/hollywood-dubbed/" to "Hollywood Hindi Dubbed",
        "$mainUrl/hindi-dubbed/south-dubbed/" to "South Hindi Dubbed",
        "$mainUrl/punjabi/" to "Punjabi Movies",
        "$mainUrl/bengali/" to "Bengali Movies",
        "$mainUrl/web-series/" to "All Web Series",
        "$mainUrl/web-series/netflix-web-series/" to "Netflix Web Series",
        "$mainUrl/web-series/amazon-web-series-webshow/" to "Amazon Web Series",
        "$mainUrl/web-series/hotstar-web-series/" to "Hotstar Web Series",
        "$mainUrl/web-series/zee5-web-series/" to "Zee5 Web Series",
        "$mainUrl/tv-shows/" to "TV Shows"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = if (request.data.endsWith("/")) request.data else "${request.data}/"
        val url = if (page <= 1) baseUrl else "${baseUrl}page/$page/"

        val items = try {
            val document = getDocument(url)
            val results = document.select("article.latestPost, article.post, article.excerpt, div.latestPost, article").mapNotNull {
                it.toSearchResult()
            }
            if (results.isEmpty()) {
                document.select("div.post-cards article, .featured-thumbnail a").mapNotNull {
                    it.toSearchResult()
                }
            } else results
        } catch (_: Exception) {
            emptyList()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private data class SearchJsonItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("img") val img: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("year") val year: String? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        // 1. Primary: CineVood native live search JSON endpoint
        try {
            val encodedQuery = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8.name())
            val jsonUrl = "$mainUrl/wp-json/dooplay/search/?keyword=$encodedQuery&nonce="
            val res = app.get(
                jsonUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text

            if (res.isNotBlank() && res.startsWith("{") || res.startsWith("[")) {
                val parsed = try {
                    parseJson<Map<String, SearchJsonItem>>(res).values.toList()
                } catch (_: Exception) {
                    try {
                        parseJson<List<SearchJsonItem>>(res)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                if (parsed.isNotEmpty()) {
                    return parsed.mapNotNull { item ->
                        val itemUrl = item.url?.trim() ?: return@mapNotNull null
                        val itemTitle = cleanTitle(item.title ?: return@mapNotNull null)
                        val poster = item.img ?: item.image ?: item.poster
                        newMovieSearchResponse(itemTitle, itemUrl, TvType.Movie) {
                            this.posterUrl = poster?.let { cleanPosterUrl(it) }
                            this.year = item.year?.toIntOrNull()
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        // 2. Fallback: WordPress standard search page (?s=query)
        return try {
            val searchUrl = "$mainUrl/?s=" + URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8.name())
            val document = getDocument(searchUrl)
            document.select("article.latestPost, article.post, article.excerpt, div.latestPost, article").mapNotNull {
                it.toSearchResult()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = this.selectFirst("h2 a, h3 a, a.post-title, a[rel='bookmark']") ?: this.selectFirst("a[href*='/']") ?: return null
        val href = linkElem.attr("href").trim()
        if (href.isBlank() || href == "#" || href.contains("/category/") || href.contains("/tag/")) return null

        val rawTitle = linkElem.text().ifBlank {
            this.selectFirst("h2, h3, .title, .entry-title")?.text() ?: linkElem.attr("title")
        }
        if (rawTitle.isBlank()) return null
        val finalTitle = cleanTitle(rawTitle)

        val imgElem = this.selectFirst("img")
        val rawPoster = imgElem?.let {
            it.attr("data-src").ifBlank {
                it.attr("data-lazy-src").ifBlank {
                    it.attr("srcset").substringBefore(" ").ifBlank {
                        it.attr("src")
                    }
                }
            }
        }
        val finalPoster = rawPoster?.let { cleanPosterUrl(it) }

        val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(rawTitle)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = rawTitle.contains("Season", ignoreCase = true) ||
                rawTitle.contains("S0", ignoreCase = true) ||
                rawTitle.contains("S1", ignoreCase = true) ||
                rawTitle.contains("S2", ignoreCase = true) ||
                rawTitle.contains("Complete", ignoreCase = true) ||
                rawTitle.contains("Series", ignoreCase = true) ||
                rawTitle.contains("Episode", ignoreCase = true)

        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(finalTitle, href, type) {
                this.posterUrl = finalPoster
                this.year = year
            }
        } else {
            newMovieSearchResponse(finalTitle, href, type) {
                this.posterUrl = finalPoster
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)

        val rawTitle = document.selectFirst("h1.entry-title, h1.title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Unknown Title"
        val finalTitle = cleanTitle(rawTitle)

        val rawPoster = document.selectFirst("div.entry-content img, .post-thumbnail img, meta[property='og:image']")?.let {
            it.attr("data-src").ifBlank {
                it.attr("data-lazy-src").ifBlank {
                    it.attr("src").ifBlank {
                        it.attr("content")
                    }
                }
            }
        }
        val posterUrl = rawPoster?.let { cleanPosterUrl(it) }

        val plot = document.selectFirst("div.entry-content p, meta[name='description'], meta[property='og:description']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()

        val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(rawTitle)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select("div.entry-content a[href*='/category/'], div.entry-content a[href*='/tag/'], .tags a").map {
            it.text().trim()
        }.filter { it.isNotBlank() }

        // Determine if TV Series / Web Series with multiple episodes
        val isSeries = rawTitle.contains("Season", ignoreCase = true) ||
                rawTitle.contains("S0", ignoreCase = true) ||
                rawTitle.contains("S1", ignoreCase = true) ||
                rawTitle.contains("Series", ignoreCase = true) ||
                rawTitle.contains("Episode", ignoreCase = true) ||
                document.select("a[href*='episode'], a:contains(Episode), a:contains(EP)").isNotEmpty()

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val allLinks = document.select("div.entry-content a[href], .post-content a[href]")

            var epIndex = 1
            for (link in allLinks) {
                val href = link.attr("href").trim()
                val text = link.text().trim()
                if (href.isBlank() || href.startsWith("#") || href.contains("telegram") || href.contains("how-to-download")) continue

                val isEpLink = Regex("""(?:Episode|EP|E)\s*(\d+)""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
                        Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).containsMatchIn(href)

                if (isEpLink) {
                    val sMatch = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(text) ?: Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
                    val eMatch = Regex("""(?:Episode|EP|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
                        ?: Regex("""(?:Episode|EP|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(href)

                    val season = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epNum = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: epIndex

                    episodes.add(
                        newEpisode(href) {
                            this.name = text.ifBlank { "Episode $epNum" }
                            this.season = season
                            this.episode = epNum
                            this.posterUrl = posterUrl
                        }
                    )
                    epIndex++
                }
            }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                }
            }
        }

        // Single Movie load response
        return newMovieLoadResponse(finalTitle, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
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
        return try {
            val targetUrl = data.trim()
            val doc = getDocument(targetUrl)

            // 1. Gather all potential download / stream links from the CineVood post
            val candidateLinks = doc.select("a[href]").mapNotNull { link ->
                val href = link.attr("href").trim()
                val text = link.text().trim()
                if (href.isBlank() || href.startsWith("#") || href.contains("telegram") || href.contains("how-to-download")) {
                    null
                } else {
                    Pair(href, text)
                }
            }

            var linksFound = false

            for ((href, text) in candidateLinks) {
                val quality = determineQuality(text.ifBlank { href })

                when {
                    // HubCloud & ViFix Server
                    href.contains("hubcloud") || href.contains("vifix.site") -> {
                        val ok = extractHubCloud(href, quality, subtitleCallback, callback)
                        if (ok) linksFound = true
                    }

                    // OxxFile / FilePress Server
                    href.contains("oxxfile") || href.contains("filepress") -> {
                        val ok = extractOxxFile(href, quality, subtitleCallback, callback)
                        if (ok) linksFound = true
                    }

                    // GDFlix Server
                    href.contains("gdflix") -> {
                        val ok = extractGDFlix(href, quality, subtitleCallback, callback)
                        if (ok) linksFound = true
                    }

                    // PixelDrain Direct
                    href.contains("pixeldrain.com") || href.contains("pixeldrain.dev") -> {
                        val id = href.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                        if (id.isNotBlank() && !id.equals("negn6f", ignoreCase = true)) {
                            val streamUrl = "https://pixeldrain.com/api/file/$id"
                            callback.invoke(
                                newExtractorLink(
                                    source = "$name - PixelDrain",
                                    name = "$name - PixelDrain (${quality.first})",
                                    url = streamUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://pixeldrain.com/"
                                    this.quality = quality.second
                                }
                            )
                            linksFound = true
                        }
                    }

                    // Direct Video files (.mp4 / .mkv / .m3u8)
                    href.endsWith(".mp4") || href.endsWith(".mkv") || href.contains(".m3u8") -> {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - Direct Stream",
                                name = "$name - Direct (${quality.first})",
                                url = href,
                                type = if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = targetUrl
                                this.quality = quality.second
                            }
                        )
                        linksFound = true
                    }

                    // Standard Extractor fallback (e.g. Streamwish, Filemoon, Doodstream, etc.)
                    else -> {
                        try {
                            loadExtractor(href, targetUrl, subtitleCallback, callback)
                        } catch (_: Exception) { }
                    }
                }
            }

            linksFound
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractHubCloud(
        url: String,
        quality: Pair<String, Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var targetUrl = url.trim()
            // Normalize any hubcloud mirror or vifix link to the active hubcloud.ist domain
            if (targetUrl.contains("/drive/")) {
                val id = targetUrl.substringAfter("/drive/").substringBefore("?").substringBefore("/").trim()
                targetUrl = "https://hubcloud.ist/drive/$id"
            } else if (targetUrl.contains("vifix.site/hubcloud/")) {
                val id = targetUrl.substringAfter("hubcloud/").substringBefore("?").substringBefore("/").trim()
                targetUrl = "https://hubcloud.ist/drive/$id"
            }

            val doc1 = app.get(
                targetUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to BYPASS_REFERER
                )
            ).document

            var extracted = parseHubCloudLinks(doc1, targetUrl, quality, subtitleCallback, callback)

            // Step 2: HubCloud landing page redirects through gamerxyt or hubcloud.php for direct stream links
            val scriptUrl = Regex("""var\s+url\s*=\\s*['"]([^'"]+)['"]""").find(doc1.html())?.groupValues?.getOrNull(1)
                ?: Regex("""https?://gamerxyt\.com/hubcloud\.php[^'"\s<>]+""").find(doc1.html())?.value
            val downloadBtn = doc1.selectFirst("a#download, a.btn-success, a.btn-primary, a[href*='hubcloud.php'], a[href*='gamerxyt'], a[href*='/download'], a:contains(Generate Direct Download Link), a:contains(Download)")
            val nextUrl = (scriptUrl ?: downloadBtn?.attr("href")) ?: targetUrl
            val fullNextUrl = when {
                nextUrl.startsWith("http") -> nextUrl
                nextUrl.startsWith("//") -> "https:$nextUrl"
                nextUrl.startsWith("/") -> {
                    val uri = URI(targetUrl)
                    "${uri.scheme}://${uri.host}$nextUrl"
                }
                else -> nextUrl
            }

            if (fullNextUrl.isNotBlank() && fullNextUrl.startsWith("http") && fullNextUrl != targetUrl) {
                val doc2 = app.get(
                    fullNextUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://hubcloud.ist/"
                    )
                ).document
                val ok2 = parseHubCloudLinks(doc2, fullNextUrl, quality, subtitleCallback, callback)
                if (ok2) extracted = true
            }

            extracted
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractOxxFile(
        url: String,
        quality: Pair<String, Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val cleanUrl = url.trim()
            val code = cleanUrl.substringAfter("/s/").substringBefore("/").substringBefore("?").trim()
            if (code.isBlank()) return false

            // Try rotating OxxFile mirrors (new10, new8)
            val hosts = listOf("https://new10.oxxfile.info", "https://new8.oxxfile.info", "https://oxxfile.info")
            var extracted = false

            for (host in hosts) {
                try {
                    val apiUrl = "$host/api/s/$code/hubcloud/"
                    val doc = app.get(
                        apiUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$host/s/$code/"
                        )
                    ).document

                    // 1. Parse direct links inside the OxxFile response
                    val ok1 = parseHubCloudLinks(doc, apiUrl, quality, subtitleCallback, callback)
                    if (ok1) extracted = true

                    // 2. Extract gamerxyt / hubcloud link from script or a#download
                    val scriptUrl = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""").find(doc.html())?.groupValues?.getOrNull(1)
                        ?: Regex("""https?://gamerxyt\.com/hubcloud\.php[^'"\s<>]+""").find(doc.html())?.value
                    val downloadBtnUrl = doc.selectFirst("a#download, a.btn-primary, a[href*='gamerxyt']")?.attr("href")
                    val targetGamerUrl = (scriptUrl ?: downloadBtnUrl)?.trim() ?: ""

                    if (targetGamerUrl.startsWith("http")) {
                        val docGamer = app.get(
                            targetGamerUrl,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://hubcloud.ist/"
                            )
                        ).document
                        val okGamer = parseHubCloudLinks(docGamer, targetGamerUrl, quality, subtitleCallback, callback)
                        if (okGamer) extracted = true
                    }

                    // 3. Check for direct hubcloud drive link inside page
                    val driveLink = Regex("""https?://hubcloud\.[a-z]+/drive/[a-zA-Z0-9]+""").find(doc.html())?.value
                    if (driveLink != null) {
                        val okDrive = extractHubCloud(driveLink, quality, subtitleCallback, callback)
                        if (okDrive) extracted = true
                    }

                    if (extracted) break
                } catch (_: Exception) {
                    continue
                }
            }

            extracted
        } catch (_: Exception) {
            false
        }
    }

    private fun parseHubCloudLinks(
        doc: Document,
        refererUrl: String,
        quality: Pair<String, Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val htmlContent = doc.html()
        val links = doc.select("a[href]")

        // 1. Scan anchor tags
        for (link in links) {
            val href = link.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") || href.contains("youtube.com") || href.contains("youtu.be")) continue

            // A. Direct Cloudflare R2 / FastCDN (Highest Speed, zero buffering)
            if (href.contains("r2.cloudflarestorage.com") || href.contains("r2.dev") || href.contains("fastcloud")) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - FastCDN",
                        name = "$name - FastCDN (${quality.first})",
                        url = href,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://gamerxyt.com/"
                        this.quality = quality.second
                    }
                )
                found = true
            } else if (href.contains("workers.dev")) {
                val streamUrl = if (href.contains("?url=")) href.substringAfter("?url=").substringBefore("&") else href
                callback.invoke(
                    newExtractorLink(
                        source = "$name - Worker FastCDN",
                        name = "$name - Worker CDN (${quality.first})",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://gamerxyt.com/"
                        this.quality = quality.second
                    }
                )
                found = true
            } else if (href.contains("vdplay.pages.dev") && href.contains("?u=")) {
                // B. VDPlay web player embeds base64 encoded direct stream
                try {
                    val b64 = href.substringAfter("?u=").substringBefore("&").trim()
                    val decodedBytes = Base64.decode(b64, Base64.DEFAULT)
                    val streamUrl = String(decodedBytes, StandardCharsets.UTF_8).trim()
                    if (streamUrl.startsWith("http")) {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - VDPlay",
                                name = "$name - Direct Stream (${quality.first})",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://gamerxyt.com/"
                                this.quality = quality.second
                            }
                        )
                        found = true
                    }
                } catch (_: Exception) { }
            } else if (href.contains("pixeldrain.com") || href.contains("pixeldrain.dev")) {
                // C. PixelDrain Direct API
                val id = href.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                if (id.isNotBlank() && !id.equals("negn6f", ignoreCase = true)) {
                    val streamUrl = "https://pixeldrain.com/api/file/$id"
                    callback.invoke(
                        newExtractorLink(
                            source = "$name - PixelDrain",
                            name = "$name - PixelDrain (${quality.first})",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://pixeldrain.com/"
                            this.quality = quality.second
                        }
                    )
                    found = true
                }
            } else if (href.endsWith(".mp4") || href.endsWith(".mkv") || href.contains(".m3u8")) {
                // D. Direct video files
                callback.invoke(
                    newExtractorLink(
                        source = "$name - Direct Stream",
                        name = "$name - Direct (${quality.first})",
                        url = href,
                        type = if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = refererUrl
                        this.quality = quality.second
                    }
                )
                found = true
            } else if (href.contains("streamwish") || href.contains("filemoon") || href.contains("dood")) {
                try {
                    loadExtractor(href, refererUrl, subtitleCallback, callback)
                    found = true
                } catch (_: Exception) { }
            }
        }

        // 2. Regex scan HTML/scripts for any embedded R2 / Worker / VDPlay stream URLs
        if (!found) {
            val r2Match = Regex("""https?://[a-zA-Z0-9.-]+\.r2\.cloudflarestorage\.com/hub/[^'"\s<>]+""").find(htmlContent)?.value
            if (r2Match != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - FastCDN",
                        name = "$name - FastCDN (${quality.first})",
                        url = r2Match,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://gamerxyt.com/"
                        this.quality = quality.second
                    }
                )
                found = true
            }

            val vdplayMatch = Regex("""https?://vdplay\.pages\.dev/\?u=([a-zA-Z0-9+/=]+)""").find(htmlContent)?.groupValues?.getOrNull(1)
            if (vdplayMatch != null) {
                try {
                    val decoded = String(Base64.decode(vdplayMatch, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
                    if (decoded.startsWith("http")) {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - VDPlay",
                                name = "$name - Direct Stream (${quality.first})",
                                url = decoded,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://gamerxyt.com/"
                                this.quality = quality.second
                            }
                        )
                        found = true
                    }
                } catch (_: Exception) { }
            }
        }

        return found
    }

    private suspend fun extractGDFlix(
        url: String,
        quality: Pair<String, Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = getDocument(url, referer = BYPASS_REFERER)
            var extracted = false

            val links = doc.select("a.btn, a[href*='drive.google'], a[href*='hubcloud'], a[href*='pixeldrain'], a[href*='gofile']")
            for (link in links) {
                val streamHref = link.attr("href").trim()
                if (streamHref.contains("pixeldrain")) {
                    val id = streamHref.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                    if (id.isNotBlank() && !id.equals("negn6f", ignoreCase = true)) {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - PixelDrain",
                                name = "$name - PixelDrain (${quality.first})",
                                url = "https://pixeldrain.com/api/file/$id",
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://pixeldrain.com/"
                                this.quality = quality.second
                            }
                        )
                        extracted = true
                    }
                } else if (streamHref.contains("hubcloud") || streamHref.contains("vifix.site")) {
                    val ok = extractHubCloud(streamHref, quality, subtitleCallback, callback)
                    if (ok) extracted = true
                } else if (streamHref.contains("drive.google") || streamHref.contains("gofile.io")) {
                    try {
                        loadExtractor(streamHref, url, subtitleCallback, callback)
                        extracted = true
                    } catch (_: Exception) { }
                } else if (streamHref.endsWith(".mp4") || streamHref.endsWith(".mkv") || streamHref.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "$name - GDFlix Direct",
                            name = "$name - Direct (${quality.first})",
                            url = streamHref,
                            type = if (streamHref.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = quality.second
                        }
                    )
                    extracted = true
                }
            }
            extracted
        } catch (_: Exception) {
            false
        }
    }

    private fun determineQuality(text: String): Pair<String, Int> {
        val lower = text.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Pair("4K 2160p", Qualities.P2160.value)
            lower.contains("1080") -> Pair("1080p FHD", Qualities.P1080.value)
            lower.contains("720") -> Pair("720p HD", Qualities.P720.value)
            lower.contains("480") -> Pair("480p SD", Qualities.P480.value)
            lower.contains("360") -> Pair("360p", Qualities.P360.value)
            else -> Pair("HD", Qualities.P720.value)
        }
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""Download\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""CineVood(?:\.rocks|\.net|\.cv|\.eu|\.bingo|\.site)?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[\(\[\{]?(?:480p|720p|1080p|2160p|4K|HD|FHD|WEB-DL|HDRip|WEBRip|BluRay|HEVC|x264|x265|ESub|Hindi|Dual Audio|Multi Audio)[\)\]\}]?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', ':')
    }

    private fun cleanPosterUrl(url: String): String {
        var clean = url.trim()
        if (clean.startsWith("//")) clean = "https:$clean"
        // Remove WordPress resized thumbnail dimensions (e.g., -300x450.jpg -> .jpg) to get high quality original
        return clean.replace(Regex("""-\d+x\d+(\.[a-zA-Z]+)$"""), "$1")
    }
}

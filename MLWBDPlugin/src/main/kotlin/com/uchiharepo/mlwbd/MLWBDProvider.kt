package com.uchiharepo.mlwbd

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

class MLWBDProvider : MainAPI() {
    override var mainUrl = "https://fojik.site"
    override var name = "MLWBD"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
        TvType.AsianDrama
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val MIRROR_DOMAINS = listOf(
            "https://fojik.site",
            "https://mlwbd.click",
            "https://mlwbd.cc",
            "https://mlwbd.is",
            "https://mlwbd.app"
        )
    }

    private suspend fun getDocument(url: String, referer: String = mainUrl): Document {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8"
        )
        return try {
            app.get(url, headers = headers, timeout = 15).document
        } catch (e: Exception) {
            val currentDomain = try { URI(url).host } catch (e2: Exception) { null }
            var lastErr: Exception = e
            for (mirror in MIRROR_DOMAINS) {
                val mirrorHost = try { URI(mirror).host } catch (e2: Exception) { null }
                if (currentDomain != null && mirrorHost != null && mirrorHost.equals(currentDomain, ignoreCase = true)) continue
                val fallbackUrl = if (currentDomain != null) url.replace("https://$currentDomain", mirror) else mirror
                try {
                    return app.get(fallbackUrl, headers = headers, timeout = 15).document
                } catch (err: Exception) {
                    lastErr = err
                }
            }
            throw lastErr
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movie/page/" to "Latest Movies",
        "$mainUrl/tvshows/page/" to "TV Series",
        "$mainUrl/genre/anime/page/" to "Anime & Animation",
        "$mainUrl/genre/hindi-dubbed-anime/page/" to "Hindi Dubbed Anime",
        "$mainUrl/genre/cartoon/page/" to "Cartoons",
        "$mainUrl/trending/page/" to "Trending Now",
        "$mainUrl/genre/bengali-dubbed/page/" to "Bengali Dubbed",
        "$mainUrl/genre/hindi-dubbed/page/" to "Hindi Dubbed",
        "$mainUrl/genre/south-indian/page/" to "South Indian (Hindi)",
        "$mainUrl/genre/hollywood/page/" to "Hollywood Movies",
        "$mainUrl/genre/bollywood/page/" to "Bollywood Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}$page/"
        val document = getDocument(url)
        val home = document.select("article.item, article.post, div.result-item, .items article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // --- High-Resolution Poster & Image Sanitizer ---
    private fun cleanImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var clean = url.trim()
        if (clean.startsWith("data:image") || (clean.contains("cropped-", ignoreCase = true) && clean.contains("icon", ignoreCase = true))) {
            return null
        }
        if (clean.contains("mlwbd.png", ignoreCase = true) || clean.contains("logo.png", ignoreCase = true)) {
            return null
        }
        if (clean.startsWith("//")) {
            clean = "https:$clean"
        } else if (clean.startsWith("/")) {
            clean = "$mainUrl$clean"
        }
        return clean.replace("/w185/", "/w500/")
                    .replace("/w342/", "/w500/")
                    .replace("/w300/", "/w500/")
                    .replace("/w780/", "/w500/")
                    .replace(Regex("""-\d+x\d+\.(jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE), ".$1")
    }

    private fun extractImageUrl(element: Element?): String? {
        if (element == null) return null
        val img = if (element.tagName().equals("img", ignoreCase = true)) element else element.selectFirst("img")
        val raw = if (img != null) {
            val dSrc = img.attr("data-src").trim()
            val dLazy = img.attr("data-lazy-src").trim()
            val dOrig = img.attr("data-original").trim()
            val dSrcset = img.attr("data-srcset").substringBefore(" ").trim()
            val srcset = img.attr("srcset").substringBefore(" ").trim()
            val src = img.attr("src").trim()

            when {
                dSrc.isNotBlank() && !dSrc.startsWith("data:image") -> dSrc
                dLazy.isNotBlank() && !dLazy.startsWith("data:image") -> dLazy
                dOrig.isNotBlank() && !dOrig.startsWith("data:image") -> dOrig
                dSrcset.isNotBlank() && !dSrcset.startsWith("data:image") -> dSrcset
                srcset.isNotBlank() && !srcset.startsWith("data:image") -> srcset
                src.isNotBlank() && !src.startsWith("data:image") -> src
                else -> null
            }
        } else {
            val directSrc = element.attr("src").ifEmpty { element.attr("data-src") }.trim()
            if (directSrc.isNotBlank() && !directSrc.startsWith("data:image")) directSrc else null
        }
        return cleanImageUrl(raw)
    }

    private fun extractPoster(document: Document): String? {
        val metaSelectors = listOf(
            "meta[property='og:image']",
            "meta[name='twitter:image']",
            "meta[property='og:image:secure_url']",
            "link[rel='image_src']"
        )
        for (sel in metaSelectors) {
            val content = document.selectFirst(sel)?.attr("content") ?: document.selectFirst(sel)?.attr("href")
            val cleaned = cleanImageUrl(content)
            if (!cleaned.isNullOrBlank()) return cleaned
        }

        val selectors = listOf(
            "div.poster img",
            ".sheader .poster img",
            ".data .poster img",
            "img[itemprop='image']",
            "img.wp-post-image",
            "div#info img",
            "div.wp-content img[src*='tmdb.org']",
            "div.entry-content img[src*='tmdb.org']",
            "div.wp-content img",
            "div.entry-content img"
        )
        for (sel in selectors) {
            val img = document.selectFirst(sel)
            val url = extractImageUrl(img)
            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    private fun extractBackdrop(document: Document): String? {
        val styleAttr = document.selectFirst("div.sheader[style*='url'], div.bgholder[style*='url'], div.backdrop[style*='url']")?.attr("style")
        if (!styleAttr.isNullOrBlank()) {
            val match = Regex("""url\(['"]?(.*?)['"]?\)""").find(styleAttr)
            val bgUrl = match?.groupValues?.get(1)
            val cleanedBg = cleanImageUrl(bgUrl)
            if (!cleanedBg.isNullOrBlank()) return cleanedBg
        }
        val bgImg = document.selectFirst("div.bgholder img, div.backdrop img")
        return extractImageUrl(bgImg)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3, .title a, .entry-title, h2")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        if (title.isBlank()) return null

        val href = this.selectFirst("a[href]")?.attr("href") ?: return null
        if (!href.startsWith("http") || href.contains("/category/") || href.contains("/genre/") || href.contains("/tag/")) {
            return null
        }

        val posterUrl = extractImageUrl(this)
        val isMovie = href.contains("/movie/") || (!href.contains("/tvshows/") && !href.contains("/series/"))
        val isAnime = href.contains("/anime/") || title.contains("Anime", ignoreCase = true)

        val targetType = when {
            isAnime -> TvType.Anime
            isMovie -> TvType.Movie
            else -> TvType.TvSeries
        }

        return if (isMovie) {
            newMovieSearchResponse(title, href, targetType) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, targetType) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encoded"
        val document = getDocument(searchUrl)
        return document.select("div.result-item, article.item, article.post, .items article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)
        val title = document.selectFirst("div.data h1, h1.entry-title, h1")?.text()?.trim()
            ?: "Unknown Title"
        val posterUrl = extractPoster(document)
        val backdropUrl = extractBackdrop(document)
        val plot = document.selectFirst("div.wp-content p, div#info .sinopsis p, .sinopsis, div.entry-content p")?.text()?.trim()
        val yearText = document.selectFirst(".extra span.date, .extra span.country + span, .date")?.text()
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find("$yearText $title")?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select("div.sheader div.sgenres a, div.data div.sgenres a, div.custom_fields a[href*='/genre/'], .wp-content a[rel='category tag'], div.tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Movies", ignoreCase = true) && !it.equals("TV Shows", ignoreCase = true) }
            .distinct()

        val isAnime = url.contains("/anime/") || tags.any { it.contains("Anime", ignoreCase = true) }
        val targetType = when {
            isAnime -> TvType.Anime
            url.contains("/tvshows/") || url.contains("/series/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        val episodeElements = document.select("#seasons .episodios li, .episodios li")
        return if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epHref = ep.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
                val epNum = ep.selectFirst(".numerando")?.text()?.trim() ?: ""
                val sMatch = Regex("""(\d+)\s*-\s*(\d+)""").find(epNum)
                val season = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = sMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1
                val epTitle = ep.selectFirst(".episodiotitle a, a")?.text()?.trim()
                val epThumb = extractImageUrl(ep) ?: posterUrl

                newEpisode(epHref) {
                    this.name = epTitle
                    this.season = season
                    this.episode = episode
                    this.posterUrl = epThumb
                }
            }

            newTvSeriesLoadResponse(title, url, targetType, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, targetType, url) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    // =========================================================================
    // HIGH-SPEED, NON-BLOCKING STREAM & LINK EXTRACTOR
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(data)
        var anyFound = false
        val processedUrls = mutableSetOf<String>()

        // 1. FAST TARGETED FORM EXTRACTION (Single-pass)
        val forms = document.select("form[action*='dld.php'], form[action*='blog.php'], form:has(input[name='FU']), form:has(input[name='token'])")
        for (form in forms.take(4)) {
            val action = form.attr("action").trim()
            val fullAction = when {
                action.startsWith("http") -> action
                action.startsWith("//") -> "https:$action"
                action.startsWith("/") -> "$mainUrl$action"
                action.isBlank() -> data
                else -> action
            }

            val formData = mutableMapOf<String, String>()
            form.select("input[name]").forEach { input ->
                val name = input.attr("name")
                val value = input.attr("value")
                if (name.isNotBlank()) formData[name] = value
            }

            if (formData.isNotEmpty()) {
                try {
                    val formDoc = app.post(
                        fullAction,
                        data = formData,
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data),
                        timeout = 10
                    ).document

                    for (a in formDoc.select("a[href]")) {
                        val h = a.attr("href").trim()
                        if (h.isBlank() || h.startsWith("#") || h.contains("youtube.com") || h.contains("youtu.be")) continue
                        if (processedUrls.contains(h)) continue
                        processedUrls.add(h)

                        if (h.contains("hubcloud") || h.contains("vifix.site") || h.contains("fastcloud") || h.contains("hubdrive")) {
                            if (extractHubCloud(h, Pair("1080p FHD", Qualities.P1080.value), subtitleCallback, callback)) anyFound = true
                        } else if (h.contains("pixeldrain.com")) {
                            if (emitPixelDrain(h, "1080p FHD", Qualities.P1080.value, callback)) anyFound = true
                        } else if (h.contains("gdflix") || h.contains("fastdrive")) {
                            if (extractGDFlix(h, Pair("1080p FHD", Qualities.P1080.value), subtitleCallback, callback)) anyFound = true
                        }
                    }
                } catch (e: Exception) { }
            }
        }

        // 2. TARGETED VIDEO & DOWNLOAD LINKS ACROSS THE PAGE
        val targetServerKeywords = listOf(
            "hubcloud", "vifix.site", "hubdrive", "fastcloud", "drivebuzz",
            "gdflix", "fastdrive", "driveseed", "drivelinks", "pixeldrain",
            "gofile.io", "filepress", "filelions", "streamtape", "vidhide",
            "streamwish", "dood", "mega.nz", "freethemesy", "technews24", "sharelink"
        )

        val allAnchorTags = document.select("a[href]")
        for (btn in allAnchorTags) {
            val href = btn.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") || href.contains("facebook.com") || href.contains("t.me") || href.contains("telegram")) continue
            if (href.contains("youtube.com") || href.contains("youtu.be")) continue

            val isTarget = targetServerKeywords.any { href.contains(it, ignoreCase = true) } ||
                           href.endsWith(".mp4") || href.endsWith(".mkv") || href.contains(".m3u8")

            if (!isTarget) continue
            if (processedUrls.contains(href)) continue
            processedUrls.add(href)

            val btnText = btn.text().trim()
            val parentText = btn.parent()?.text()?.trim() ?: ""
            val quality = determineQuality("$btnText $parentText")

            // Server A: HubCloud / HubDrive / Vifix / FastCloud
            if (href.contains("hubcloud") || href.contains("vifix.site") || href.contains("hubdrive") || href.contains("fastcloud") || href.contains("drivebuzz")) {
                val ok = extractHubCloud(href, quality, subtitleCallback, callback)
                if (ok) anyFound = true
            }
            // Server B: PixelDrain Direct Stream API
            else if (href.contains("pixeldrain.com")) {
                val ok = emitPixelDrain(href, quality.first, quality.second, callback)
                if (ok) anyFound = true
            }
            // Server C: GDFlix / FastDrive / DriveSeed / DriveLinks
            else if (href.contains("gdflix") || href.contains("fastdrive") || href.contains("driveseed") || href.contains("drivelinks")) {
                val ok = extractGDFlix(href, quality, subtitleCallback, callback)
                if (ok) anyFound = true
            }
            // Server D: Direct Video Streams
            else if (href.endsWith(".mp4") || href.endsWith(".mkv") || href.endsWith(".m4v") || href.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - Direct Stream (${quality.first})",
                        url = href,
                        type = if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = quality.second
                    }
                )
                anyFound = true
            }
            // Server E: Generic Multi-Host Extractors (StreamTape, Gofile, FilePress, VidHide, etc.)
            else {
                try {
                    loadExtractor(href, data, subtitleCallback, callback)
                    anyFound = true
                } catch (e: Exception) { }
            }
        }

        // 3. DOOPLAY ONLINE PLAYER OPTIONS (EXCLUDING TRAILERS)
        val playerOptions = document.select("li.dooplay_player_option, ul#playeroptions li, .options li")
        for (option in playerOptions) {
            val post = option.attr("data-post").trim()
            val nume = option.attr("data-nume").trim()
            val type = option.attr("data-type").trim()
            val optText = option.text().trim()

            if (type.equals("trailer", ignoreCase = true) || nume.equals("trailer", ignoreCase = true) || optText.contains("trailer", ignoreCase = true)) {
                continue
            }

            if (post.isNotEmpty() && nume.isNotEmpty()) {
                var embedUrl: String? = null

                // 1. WP-JSON API
                try {
                    val playerApiUrl = "$mainUrl/wp-json/dooplayer/v2/$post/$type/$nume"
                    val resJson = app.get(
                        playerApiUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to data,
                            "Accept" to "application/json, text/javascript, */*"
                        ),
                        timeout = 8
                    ).text
                    val dooRes = parseJson<DooPlayerResponse>(resJson)
                    embedUrl = dooRes.embed_url
                } catch (e: Exception) { }

                // 2. Admin-Ajax fallback
                if (embedUrl.isNullOrBlank()) {
                    try {
                        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                        val ajaxRes = app.post(
                            ajaxUrl,
                            data = mapOf(
                                "action" to "doo_player_ajax",
                                "post" to post,
                                "nume" to nume,
                                "type" to type
                            ),
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to data,
                                "X-Requested-With" to "XMLHttpRequest"
                            ),
                            timeout = 8
                        ).text
                        val dooRes = parseJson<DooPlayerResponse>(ajaxRes)
                        embedUrl = dooRes.embed_url
                    } catch (e: Exception) { }
                }

                if (!embedUrl.isNullOrBlank()) {
                    val cleanedEmbed = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl
                    if (cleanedEmbed.contains("youtube.com") || cleanedEmbed.contains("youtu.be")) continue

                    if (cleanedEmbed.contains("hubcloud") || cleanedEmbed.contains("vifix.site") || cleanedEmbed.contains("fastcloud")) {
                        val ok = extractHubCloud(cleanedEmbed, Pair("1080p FHD", Qualities.P1080.value), subtitleCallback, callback)
                        if (ok) anyFound = true
                    } else {
                        try {
                            loadExtractor(cleanedEmbed, mainUrl, subtitleCallback, callback)
                            anyFound = true
                        } catch (e: Exception) { }
                    }
                }
            }
        }

        return anyFound
    }

    // --- PixelDrain Direct Stream Emitter ---
    private fun emitPixelDrain(
        url: String,
        qualityName: String,
        qualityValue: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = url.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
        if (id.isEmpty()) return false
        val streamUrl = "https://pixeldrain.com/api/file/$id"
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "$name - PixelDrain ($qualityName)",
                url = streamUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://pixeldrain.com/"
                this.quality = qualityValue
            }
        )
        return true
    }

    // --- High-Speed HubCloud Direct Stream Extractor ---
    private suspend fun extractHubCloud(
        url: String,
        quality: Pair<String, Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var targetUrl = url
            if (targetUrl.contains("vifix.site/hubcloud/")) {
                val id = targetUrl.substringAfter("hubcloud/").substringBefore("?").trim()
                targetUrl = "https://hubcloud.one/drive/$id"
            }

            val doc1 = app.get(
                targetUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                timeout = 10
            ).document

            // Check page 1 links first
            var extracted = parseHubCloudLinks(doc1, targetUrl, quality, subtitleCallback, callback)

            // Follow Download / Generate link button to Page 2
            val downloadBtn = doc1.selectFirst("a#download, a.btn-success, a.btn-primary, a[href*='hubcloud.php'], a[href*='/download/'], a[href*='/file/'], a[href*='/video/']")
            val nextUrl = downloadBtn?.attr("href") ?: ""
            if (nextUrl.isNotBlank()) {
                val fullNextUrl = when {
                    nextUrl.startsWith("http") -> nextUrl
                    nextUrl.startsWith("//") -> "https:$nextUrl"
                    nextUrl.startsWith("/") -> {
                        try {
                            val base = URI(targetUrl)
                            "${base.scheme}://${base.host}$nextUrl"
                        } catch (e: Exception) {
                            nextUrl
                        }
                    }
                    else -> nextUrl
                }

                if (fullNextUrl != targetUrl) {
                    val doc2 = app.get(
                        fullNextUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to targetUrl,
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                        ),
                        timeout = 10
                    ).document
                    val ok2 = parseHubCloudLinks(doc2, fullNextUrl, quality, subtitleCallback, callback)
                    if (ok2) extracted = true
                }
            }

            extracted
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun parseHubCloudLinks(
        doc: Document,
        refererUrl: String,
        quality: Pair<String, Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val links = doc.select("a[href]")
        for (link in links) {
            val href = link.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") || href.contains("youtube.com") || href.contains("youtu.be")) continue

            // 1. FastCDN / Cloudflare R2 / FastCloud
            if (href.contains("r2.dev") || href.contains("cloudflare") || href.contains("fastcloud") || href.contains("fastcdn")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - FastCDN (${quality.first})",
                        url = href,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = refererUrl
                        this.quality = quality.second
                    }
                )
                found = true
            }
            // 2. PixelDrain Direct API Stream
            else if (href.contains("pixeldrain.com")) {
                if (emitPixelDrain(href, quality.first, quality.second, callback)) found = true
            }
            // 3. Worker CDN
            else if (href.contains("workers.dev")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - Worker CDN (${quality.first})",
                        url = href,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = refererUrl
                        this.quality = quality.second
                    }
                )
                found = true
            }
            // 4. Direct Video Files
            else if (href.endsWith(".mp4") || href.endsWith(".mkv") || href.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - Direct (${quality.first})",
                        url = href,
                        type = if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = refererUrl
                        this.quality = quality.second
                    }
                )
                found = true
            }
            // 5. External Providers
            else if (href.contains("gofile.io") || href.contains("mega.nz") || href.contains("streamtape") || href.contains("vidhide")) {
                try {
                    loadExtractor(href, refererUrl, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) { }
            }
        }
        return found
    }

    // --- GDFlix / FastDrive Extractor ---
    private suspend fun extractGDFlix(
        url: String,
        quality: Pair<String, Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl), timeout = 10).document
            var extracted = false

            val links = doc.select("a[href]")
            for (link in links) {
                val streamHref = link.attr("href").trim()
                if (streamHref.isBlank() || streamHref.startsWith("#") || streamHref.contains("youtube.com") || streamHref.contains("youtu.be")) continue

                if (streamHref.contains("pixeldrain.com")) {
                    if (emitPixelDrain(streamHref, quality.first, quality.second, callback)) extracted = true
                } else if (streamHref.contains("hubcloud") || streamHref.contains("vifix.site") || streamHref.contains("fastcloud")) {
                    val ok = extractHubCloud(streamHref, quality, subtitleCallback, callback)
                    if (ok) extracted = true
                } else if (streamHref.contains("drive.google") || streamHref.contains("gofile.io")) {
                    try {
                        loadExtractor(streamHref, url, subtitleCallback, callback)
                        extracted = true
                    } catch (e: Exception) { }
                } else if (streamHref.endsWith(".mp4") || streamHref.endsWith(".mkv") || streamHref.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
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
        } catch (e: Exception) {
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

    data class DooPlayerResponse(
        val embed_url: String? = null,
        val type: String? = null
    )
}

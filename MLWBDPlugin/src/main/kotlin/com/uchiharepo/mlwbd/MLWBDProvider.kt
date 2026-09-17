package com.uchiharepo.mlwbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
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
            "https://mlwbd.cc"
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
            app.get(url, headers = headers).document
        } catch (e: Exception) {
            val currentDomain = try { URI(url).host } catch (e2: Exception) { null }
            var lastErr: Exception = e
            for (mirror in MIRROR_DOMAINS) {
                val mirrorHost = try { URI(mirror).host } catch (e2: Exception) { null }
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
        // Upgrade TMDB image quality & strip WordPress thumbnail crops (-200x300.jpg, -185x278.jpg, etc.)
        return clean.replace("/w185/", "/w500/")
                    .replace("/w342/", "/w500/")
                    .replace("/w300/", "/w500/")
                    .replace("/w780/", "/w500/")
                    .replace(Regex("""-d+xd+.(jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE), ".$1")
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
        // 1. Check OpenGraph & Twitter Meta Tags
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

        // 2. Check DooPlay / WordPress Poster Containers
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
            val match = Regex("""url(['"]?(.*?)['"]?)""").find(styleAttr)
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

        return when {
            isAnime -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
            isMovie -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
            else -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
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

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)
        val title = document.selectFirst("div.data h1, h1.entry-title, h1")?.text()?.trim()
            ?: "Unknown Title"
        val posterUrl = extractPoster(document)
        val backdropUrl = extractBackdrop(document)
        val plot = document.selectFirst("div.wp-content p, div#info .sinopsis p, .sinopsis, div.entry-content p")?.text()?.trim()
        val yearText = document.selectFirst(".extra span.date, .extra span.country + span, .date")?.text()
        val year = Regex("""(19dd|20dd)""").find("$yearText $title")?.groupValues?.get(1)?.toIntOrNull()

        // Extract Movie-Specific Genres (Avoid matching sidebar/header menus)
        val tags = document.select("div.sheader div.sgenres a, div.data div.sgenres a, div.custom_fields a[href*='/genre/'], .wp-content a[rel='category tag'], div.tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Movies", ignoreCase = true) && !it.equals("TV Shows", ignoreCase = true) }
            .distinct()

        val rating = document.selectFirst(".dt_rating_vgs, .rating")?.text()?.trim()
        val isAnime = url.contains("/anime/") || tags.any { it.contains("Anime", ignoreCase = true) }

        val episodeElements = document.select("#seasons .episodios li, .episodios li")
        return if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epHref = ep.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
                val epNum = ep.selectFirst(".numerando")?.text()?.trim() ?: ""
                val sMatch = Regex("""(d+)s*-s*(d+)""").find(epNum)
                val season = sMatch?.groupValues?.get(1)?.toIntOrNull()
                val episode = sMatch?.groupValues?.get(2)?.toIntOrNull()
                val epTitle = ep.selectFirst(".episodiotitle a, a")?.text()?.trim()
                val epThumb = extractImageUrl(ep) ?: posterUrl

                newEpisode(epHref) {
                    this.name = epTitle
                    this.season = season
                    this.episode = episode
                    this.posterUrl = epThumb
                }
            }

            if (isAnime) {
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backdropUrl
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.score = Score.from10(rating)
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            } else {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backdropUrl
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.score = Score.from10(rating)
                }
            }
        } else {
            if (isAnime) {
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backdropUrl
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.score = Score.from10(rating)
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backdropUrl
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.score = Score.from10(rating)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(data)
        var anyFound = false

        // =========================================================================
        // 1. ALL SERVERS & BUTTONS PARSING (HubCloud, GDFlix, PixelDrain, Gofile, etc.)
        // =========================================================================
        val candidateSelectors = listOf(
            "a[href*='hubcloud']",
            "a[href*='vifix']",
            "a[href*='hubdrive']",
            "a[href*='fastcloud']",
            "a[href*='gdflix']",
            "a[href*='fastdrive']",
            "a[href*='driveseed']",
            "a[href*='drivelinks']",
            "a[href*='pixeldrain']",
            "a[href*='drive.google']",
            "a[href*='gofile']",
            "a[href*='filepress']",
            "a[href*='filelions']",
            "a[href*='streamtape']",
            "a[href*='vidhide']",
            "a[href*='dood']",
            "a[href*='streamwish']",
            "a[href*='mega.nz']",
            "a[href*='droplink']",
            "a[href*='dropvip']",
            "a.btn-download",
            "a.dlink",
            "div.download-links a",
            "div.wp-content a[href*='http']",
            "div.entry-content a[href*='http']",
            "div#download a",
            "div.links a",
            "table a[href]",
            "p a[href*='http']",
            "ul.links a",
            ".download a"
        )

        val processedUrls = mutableSetOf<String>()
        val linkButtons = document.select(candidateSelectors.joinToString(", "))

        for (btn in linkButtons) {
            val href = btn.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") || href.contains("facebook.com") || href.contains("t.me")) continue
            // FILTER OUT YOUTUBE TRAILERS
            if (href.contains("youtube.com") || href.contains("youtu.be")) continue
            if (processedUrls.contains(href)) continue
            processedUrls.add(href)

            val btnText = btn.text().trim()
            val parentText = btn.parent()?.text()?.trim() ?: ""
            val quality = determineQuality("$btnText $parentText")

            // --- Server 1: HubCloud / HubDrive / Vifix / FastCloud ---
            if (href.contains("hubcloud") || href.contains("vifix.site") || href.contains("hubdrive") || href.contains("fastcloud")) {
                val ok = extractHubCloud(href, quality, subtitleCallback, callback)
                if (ok) anyFound = true
            }
            // --- Server 2: GDFlix / FastDrive / DriveSeed / DriveLinks ---
            else if (href.contains("gdflix") || href.contains("fastdrive") || href.contains("driveseed") || href.contains("drivelinks")) {
                val ok = extractGDFlix(href, quality, subtitleCallback, callback)
                if (ok) anyFound = true
            }
            // --- Server 3: PixelDrain Direct High-Speed Stream ---
            else if (href.contains("pixeldrain.com")) {
                val id = href.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                if (id.isNotEmpty()) {
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
                    anyFound = true
                }
            }
            // --- Server 4: Direct Video File Links (MP4, MKV, M3U8) ---
            else if (href.endsWith(".mp4") || href.endsWith(".mkv") || href.endsWith(".m4v") || href.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - Direct",
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
            // --- Server 5: Gofile / FilePress / StreamTape / VidHide / Mega / Dood / Others ---
            else {
                try {
                    loadExtractor(href, data, subtitleCallback, callback)
                    anyFound = true
                } catch (e: Exception) { }
            }
        }

        // =========================================================================
        // 2. MLWBD SHORTENER & FORM TOKEN BYPASS (FU, FN, FU4, FU5, freethemesy, sharelink)
        // =========================================================================
        try {
            val fuInput = document.selectFirst("input[name='FU'], input[name='FU4'], input[name='FU5']")
            if (fuInput != null) {
                val bypassed = resolveMlwbdShortenerForm(document, data, subtitleCallback, callback)
                if (bypassed) anyFound = true
            }
        } catch (e: Exception) { }

        // =========================================================================
        // 3. DOOPLAY ONLINE PLAYER OPTIONS (Dual API: WP-JSON + Admin-Ajax) - EXCLUDES TRAILERS
        // =========================================================================
        val playerOptions = document.select("li.dooplay_player_option, ul#playeroptions li, .options li")
        for (option in playerOptions) {
            val post = option.attr("data-post").trim()
            val nume = option.attr("data-nume").trim()
            val type = option.attr("data-type").trim()
            val optText = option.text().trim()

            // FILTER OUT TRAILERS
            if (type.equals("trailer", ignoreCase = true) || nume.equals("trailer", ignoreCase = true) || optText.contains("trailer", ignoreCase = true)) {
                continue
            }

            if (post.isNotEmpty() && nume.isNotEmpty()) {
                var embedUrl: String? = null

                // Method A: WP-JSON dooplayer endpoint
                try {
                    val playerApiUrl = "$mainUrl/wp-json/dooplayer/v2/$post/$type/$nume"
                    val resJson = app.get(
                        playerApiUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to data,
                            "Accept" to "application/json, text/javascript, */*"
                        )
                    ).text
                    val dooRes = parseJson<DooPlayerResponse>(resJson)
                    embedUrl = dooRes.embed_url
                } catch (e: Exception) { }

                // Method B: admin-ajax.php fallback
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
                            )
                        ).text
                        val dooRes = parseJson<DooPlayerResponse>(ajaxRes)
                        embedUrl = dooRes.embed_url
                    } catch (e: Exception) { }
                }

                if (!embedUrl.isNullOrBlank()) {
                    val cleanedEmbed = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl
                    // Filter out YouTube trailers inside player options
                    if (cleanedEmbed.contains("youtube.com") || cleanedEmbed.contains("youtu.be")) {
                        continue
                    }
                    if (cleanedEmbed.contains("hubcloud") || cleanedEmbed.contains("vifix.site")) {
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

        // =========================================================================
        // 4. EMBEDDED IFRAMES PARSING (EXCLUDES YOUTUBE)
        // =========================================================================
        val iframes = document.select("iframe[src], iframe[data-src]")
        for (iframe in iframes) {
            val src = (iframe.attr("data-src").ifEmpty { iframe.attr("src") }).trim()
            if (src.isNotBlank() && !src.contains("facebook") && !src.contains("telegram") && !src.contains("youtube.com") && !src.contains("youtu.be")) {
                val fullSrc = if (src.startsWith("//")) "https:$src" else src
                try {
                    loadExtractor(fullSrc, data, subtitleCallback, callback)
                    anyFound = true
                } catch (e: Exception) { }
            }
        }

        return anyFound
    }

    // --- Original Server 1: HubCloud Direct Stream Extractor ---
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
                    "Referer" to mainUrl
                )
            ).document

            // Check page 1 links first
            var extracted = parseHubCloudLinks(doc1, targetUrl, quality, subtitleCallback, callback)

            val downloadBtn = doc1.selectFirst("a#download, a.btn-success, a.btn-primary, a[href*='hubcloud.php'], a[href*='/download'], a[href*='/file/']")
            val nextUrl = downloadBtn?.attr("href") ?: targetUrl
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
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to targetUrl)
                ).document
                val ok2 = parseHubCloudLinks(doc2, fullNextUrl, quality, subtitleCallback, callback)
                if (ok2) extracted = true
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

            if (href.contains("r2.dev") || href.contains("cloudflare") || href.contains("fastcloud")) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - HubCloud FastCDN",
                        name = "$name - FastCDN (${quality.first})",
                        url = href,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = refererUrl
                        this.quality = quality.second
                    }
                )
                found = true
            } else if (href.contains("pixeldrain.com")) {
                val id = href.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                if (id.isNotEmpty()) {
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
            } else if (href.contains("workers.dev")) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - Worker CDN",
                        name = "$name - Worker CDN (${quality.first})",
                        url = href,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = refererUrl
                        this.quality = quality.second
                    }
                )
                found = true
            } else if (href.endsWith(".mp4") || href.endsWith(".mkv") || href.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - HubCloud",
                        name = "$name - Direct (${quality.first})",
                        url = href,
                        type = if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = refererUrl
                        this.quality = quality.second
                    }
                )
                found = true
            } else if (href.contains("gofile.io") || href.contains("mega.nz") || href.contains("streamtape") || href.contains("vidhide")) {
                try {
                    loadExtractor(href, refererUrl, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) { }
            }
        }
        return found
    }

    // --- Original Server 2: GDFlix / FastDrive / DriveSeed Extractor ---
    private suspend fun extractGDFlix(
        url: String,
        quality: Pair<String, Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)).document
            var extracted = false

            val links = doc.select("a[href]")
            for (link in links) {
                val streamHref = link.attr("href").trim()
                if (streamHref.isBlank() || streamHref.startsWith("#") || streamHref.contains("youtube.com") || streamHref.contains("youtu.be")) continue

                if (streamHref.contains("pixeldrain.com")) {
                    val id = streamHref.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                    if (id.isNotEmpty()) {
                        val streamUrl = "https://pixeldrain.com/api/file/$id"
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - GDFlix (PixelDrain)",
                                name = "$name - PixelDrain (${quality.first})",
                                url = streamUrl,
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
                    } catch (e: Exception) { }
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
        } catch (e: Exception) {
            false
        }
    }

    // --- Shortener & Landing Page Form Bypasser ---
    private suspend fun resolveMlwbdShortenerForm(
        document: Document,
        originalUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fu = document.selectFirst("input[name='FU']")?.attr("value")
        val fn = document.selectFirst("input[name='FN']")?.attr("value")
        if (!fu.isNullOrBlank()) {
            val blogRes = app.post(
                "https://search.technews24.site/blog.php",
                data = mapOf("FU" to fu, "FN" to (fn ?: "")),
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to originalUrl)
            ).document
            val fu2 = blogRes.selectFirst("input[name='FU2']")?.attr("value")
            if (!fu2.isNullOrBlank()) {
                val dldRes = app.post(
                    "https://freethemesy.com/dld.php",
                    data = mapOf("FU2" to fu2),
                    headers = mapOf("User-Agent" to USER_AGENT)
                ).document
                for (a in dldRes.select("a[href]")) {
                    val linkHref = a.attr("href").trim()
                    if (linkHref.contains("hubcloud") || linkHref.contains("pixeldrain") || linkHref.contains("gdflix")) {
                        loadExtractor(linkHref, originalUrl, subtitleCallback, callback)
                        return true
                    }
                }
            }
        }
        return false
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

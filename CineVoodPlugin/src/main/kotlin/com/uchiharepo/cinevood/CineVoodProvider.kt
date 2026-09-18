package com.uchiharepo.cinevood

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
            "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        val MIRROR_DOMAINS = listOf(
            "https://cinevood.rocks",
            "https://cinevood.bingo",
            "https://cinevood.cv",
            "https://new1.cinevood.cv",
            "https://1cinevood.eu",
            "https://cinevood.net"
        )
    }

    private suspend fun getDocument(url: String, referer: String = mainUrl): Document {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8"
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
        "$mainUrl/page/" to "Latest Releases",
        "$mainUrl/bollywood/page/" to "Bollywood Movies",
        "$mainUrl/hollywood/page/" to "Hollywood Movies",
        "$mainUrl/hindi-dubbed/hollywood-dubbed/page/" to "Hollywood Hindi Dubbed",
        "$mainUrl/hindi-dubbed/south-dubbed/page/" to "South Hindi Dubbed",
        "$mainUrl/bengali/page/" to "Bengali Movies",
        "$mainUrl/punjabi/page/" to "Punjabi Movies",
        "$mainUrl/web-series/page/" to "All Web Series",
        "$mainUrl/web-series/netflix-web-series/page/" to "Netflix Web Series",
        "$mainUrl/web-series/amazon-web-series-webshow/page/" to "Amazon Prime",
        "$mainUrl/web-series/hotstar-web-series/page/" to "Disney+ Hotstar",
        "$mainUrl/web-series/sony-liv-web-show/page/" to "Sony LIV",
        "$mainUrl/tv-shows/page/" to "TV Shows"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) request.data.removeSuffix("/page/") else "${request.data}$page/"
        val document = getDocument(url)
        val home = document.select("article.latestPost, article.post, article.excerpt, div.result-item, .post-image").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun cleanImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var clean = url.trim()
        if (clean.startsWith("data:image") || clean.contains("cvlogo.png") || clean.contains("favicon")) {
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

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("h2.title a, .front-view-title a, h2 a, a.post-image, h3")
        val title = titleEl?.text()?.trim()
            ?: titleEl?.attr("title")?.trim()
            ?: this.selectFirst("img")?.attr("title")?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        if (title.isBlank()) return null
        val href = titleEl?.attr("href") ?: this.selectFirst("a[href]")?.attr("href") ?: return null
        if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) {
            return null
        }

        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        val posterUrl = extractImageUrl(this)
        val isSeries = fullUrl.contains("/web-series/") || fullUrl.contains("/tv-shows/") || title.contains("Season", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    data class CineVoodSearchHit(
        @JsonProperty("document") val document: CineVoodSearchDoc? = null
    )

    data class CineVoodSearchDoc(
        @JsonProperty("post_title") val postTitle: String? = null,
        @JsonProperty("permalink") val permalink: String? = null,
        @JsonProperty("post_thumbnail") val postThumbnail: String? = null
    )

    data class CineVoodSearchResponse(
        @JsonProperty("found") val found: Int? = null,
        @JsonProperty("hits") val hits: List<CineVoodSearchHit>? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        
        // 1. Primary: Dedicated fast CineVood JSON API
        try {
            val apiRes = app.get(
                "$mainUrl/search.php?q=$encoded&page=1",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/search.html",
                    "Accept" to "application/json, text/plain, */*"
                )
            ).parsedSafe<CineVoodSearchResponse>()

            val results = apiRes?.hits?.mapNotNull { hit ->
                val doc = hit.document ?: return@mapNotNull null
                val title = doc.postTitle?.trim() ?: return@mapNotNull null
                val permalink = doc.permalink?.trim() ?: return@mapNotNull null
                val fullUrl = if (permalink.startsWith("http")) permalink else "$mainUrl$permalink"
                val posterUrl = cleanImageUrl(doc.postThumbnail)
                val isSeries = fullUrl.contains("/web-series/") || fullUrl.contains("/tv-shows/") || title.contains("Season", ignoreCase = true)

                if (isSeries) {
                    newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    }
                } else {
                    newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                }
            }

            if (!results.isNullOrEmpty()) {
                return results
            }
        } catch (_: Exception) { }

        // 2. Fallback: Standard WordPress HTML search
        val document = getDocument("$mainUrl/?s=$encoded")
        return document.select("article.latestPost, article.post, article.excerpt, div.result-item, .movies-grid a").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)
        val title = document.selectFirst("h1.single-title, h1.entry-title, h1")?.text()?.trim()
            ?: "Unknown Title"

        val posterUrl = cleanImageUrl(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("meta[name='twitter:image']")?.attr("content")
                ?: extractImageUrl(document.selectFirst(".featured-thumbnail img, .post-single-content img, .entry-content img"))
        )

        val plot = document.selectFirst("div.post-single-content p, div.entry-content p, .thecontent p")?.text()?.trim()
        val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(title)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select("div.thecategory a, div.thetags a, a[rel='category tag'], div.tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Movies", ignoreCase = true) }
            .distinct()

        val isSeries = url.contains("/web-series/") || url.contains("/tv-shows/") || title.contains("Season", ignoreCase = true)

        val episodeLinks = document.select(".thecontent a, div.post-single-content a, div.entry-content a, a.maxbutton")
            .filter { a ->
                val t = a.text().trim()
                val href = a.attr("href").trim()
                href.isNotBlank() && (
                    t.contains("Episode", ignoreCase = true) ||
                    t.contains("EP ", ignoreCase = true) ||
                    t.contains("EP-", ignoreCase = true) ||
                    Regex("""EP\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
                    Regex("""E\d+""", RegexOption.IGNORE_CASE).containsMatchIn(t)
                )
            }

        if (isSeries && episodeLinks.isNotEmpty()) {
            val episodes = episodeLinks.mapIndexedNotNull { index, ep ->
                val epHref = ep.attr("href").trim()
                if (epHref.isBlank() || epHref.startsWith("#")) return@mapIndexedNotNull null
                val epText = ep.text().trim()
                val sMatch = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(epText)
                val eMatch = Regex("""(?:Episode|EP|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)
                val season = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)

                newEpisode(epHref) {
                    this.name = epText
                    this.season = season
                    this.episode = epNum
                    this.posterUrl = posterUrl
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val document = if (data.startsWith("http")) getDocument(data) else null
        val candidateLinks = mutableListOf<Pair<String, String>>()

        if (document != null) {
            val buttons = document.select("a.maxbutton, .maxbutton-1, .maxbutton-2, .maxbutton-6, .maxbutton-7, .thecontent a[href], .post-single-content a[href], div.entry-content a[href]")
            for (btn in buttons) {
                val href = btn.attr("href").trim()
                val text = btn.text().trim()
                if (href.isNotBlank() && !href.startsWith("#") && !href.contains("javascript:")) {
                    candidateLinks.add(Pair(href, text))
                }
            }
        } else if (data.startsWith("http")) {
            candidateLinks.add(Pair(data, "Direct"))
        }

        var foundAny = false

        for ((href, text) in candidateLinks) {
            val quality = determineQuality("$text $href")

            when {
                href.contains("hubcloud") || href.contains("vifix.site") || href.contains("fastdl") -> {
                    val ok = extractHubCloud(href, quality, subtitleCallback, callback)
                    if (ok) foundAny = true
                }
                href.contains("gdflix") || href.contains("fastdrive") || href.contains("driveseed") -> {
                    val ok = extractGDFlix(href, quality, subtitleCallback, callback)
                    if (ok) foundAny = true
                }
                href.contains("pixeldrain.com") -> {
                    val id = href.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                    if (id.isNotEmpty()) {
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
                        foundAny = true
                    }
                }
                href.endsWith(".mp4") || href.endsWith(".mkv") || href.contains(".m3u8") -> {
                    callback.invoke(
                        newExtractorLink(
                            source = "$name - Direct CDN",
                            name = "$name - Direct Stream (${quality.first})",
                            url = href,
                            type = if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = quality.second
                        }
                    )
                    foundAny = true
                }
                else -> {
                    try {
                        loadExtractor(href, mainUrl, subtitleCallback, callback)
                        foundAny = true
                    } catch (_: Exception) { }
                }
            }
        }

        return foundAny
    }

    private suspend fun extractHubCloud(
        url: String,
        quality: Pair<String, Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)).document
            var found = false
            val links = doc.select("a[href]")

            for (link in links) {
                val href = link.attr("href").trim()
                if (href.isBlank() || href.startsWith("#")) continue

                if (href.contains("r2.dev") || href.contains("cloudflare") || href.contains("fastcloud")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "$name - FastCDN",
                            name = "$name - FastCDN (${quality.first})",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = quality.second
                        }
                    )
                    found = true
                } else if (href.contains("pixeldrain.com")) {
                    val id = href.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                    if (id.isNotEmpty()) {
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
                            this.referer = url
                            this.quality = quality.second
                        }
                    )
                    found = true
                } else if (href.endsWith(".mp4") || href.endsWith(".mkv") || href.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "$name - HubCloud Direct",
                            name = "$name - Direct (${quality.first})",
                            url = href,
                            type = if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = quality.second
                        }
                    )
                    found = true
                } else if (href.contains("gofile.io") || href.contains("streamtape") || href.contains("vidhide")) {
                    try {
                        loadExtractor(href, url, subtitleCallback, callback)
                        found = true
                    } catch (_: Exception) { }
                }
            }
            found
        } catch (_: Exception) {
            false
        }
    }

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
                if (streamHref.isBlank() || streamHref.startsWith("#")) continue

                if (streamHref.contains("pixeldrain.com")) {
                    val id = streamHref.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                    if (id.isNotEmpty()) {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - GDFlix (PixelDrain)",
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
}

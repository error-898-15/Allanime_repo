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
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val BYPASS_REFERER = "https://cinevood.rocks/"

        private val MIRRORS = listOf(
            "https://cinevood.rocks",
            "https://cinevood.lat",
            "https://cinevood.bond",
            "https://cinevood.baby",
            "https://cinevood.surf",
            "https://cinevood.lol",
            "https://cinevood.vip",
            "https://cinevood.art",
            "https://cinevood.guru"
        )
    }

    private fun resolveUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            val currentDomain = try { URI(url).host } catch (e: Exception) { null }
            if (currentDomain != null && currentDomain.contains("cinevood")) {
                for (mirror in MIRRORS) {
                    val mirrorHost = try { URI(mirror).host } catch (e2: Exception) { null }
                    if (mirrorHost == currentDomain) {
                        return url.replaceFirst(mirror, mainUrl)
                    }
                }
            }
            return url
        }
        val cleanPath = if (url.startsWith("/")) url else "/$url"
        return "$mainUrl$cleanPath"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/category/bollywood-movies/" to "Bollywood Movies",
        "$mainUrl/category/hollywood-movies/" to "Hollywood (Hindi Dubbed)",
        "$mainUrl/category/south-indian-hindi/" to "South Hindi Dubbed",
        "$mainUrl/category/web-series/" to "Web Series",
        "$mainUrl/category/korean-drama/" to "Korean & Asian Dramas",
        "$mainUrl/category/4k-ultra-hd/" to "4K UHD Movies",
        "$mainUrl/category/1080p-movies/" to "1080p FHD Movies",
        "$mainUrl/category/720p-movies/" to "720p HD Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            "$base/page/$page/"
        }

        val document = try {
            app.get(
                targetUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to BYPASS_REFERER
                ),
                timeout = 25
            ).document
        } catch (e: Exception) {
            var workingDoc: Document? = null
            for (mirror in MIRRORS) {
                if (mirror == mainUrl) continue
                try {
                    val fallbackUrl = targetUrl.replace(mainUrl, mirror)
                    val res = app.get(
                        fallbackUrl,
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mirror/"),
                        timeout = 15
                    )
                    if (res.isSuccessful) {
                        mainUrl = mirror
                        workingDoc = res.document
                        break
                    }
                } catch (e2: Exception) { }
            }
            workingDoc ?: throw e
        }

        val items = parseListing(document)
        val hasNext = document.select("a.next, .pagination .next, a:contains(Next), a:contains(»)").isNotEmpty() || items.size >= 12
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val elements = document.select("article, .post-item, .item, .latest-post, div[id^=post-], .thumb.col-md-2, .col-md-2, .movie-poster, .result-item, .search-item")

        for (element in elements) {
            val linkElement = element.selectFirst("a[href*='/']:not([href*='category']):not([href*='tag']):not([href*='page'])")
                ?: element.selectFirst("h2 a, h3 a, h1 a, .entry-title a, .title a, a")
                ?: continue

            val href = linkElement.attr("href")
            if (href.isBlank() || href == "#" || href.contains("/category/") || href.contains("/tag/") || href.contains("/author/")) {
                continue
            }

            val title = linkElement.attr("title").ifBlank {
                element.selectFirst("h2, h3, .entry-title, .title, img[alt]")?.let {
                    it.attr("alt").ifBlank { it.text() }
                } ?: linkElement.text()
            }.cleanTitle()

            if (title.isBlank()) continue

            val posterUrl = element.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank {
                    img.attr("data-lazy-src").ifBlank {
                        img.attr("data-original").ifBlank {
                            img.attr("src")
                        }
                    }
                }
            }?.let { fixImageUrl(it) }

            val fullUrl = resolveUrl(href)
            val isTv = title.contains("Season", ignoreCase = true) ||
                    title.contains("S0", ignoreCase = true) ||
                    title.contains("Series", ignoreCase = true) ||
                    title.contains("Episode", ignoreCase = true) ||
                    fullUrl.contains("season", ignoreCase = true)

            if (isTv) {
                results.add(
                    newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    }
                )
            } else {
                results.add(
                    newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                )
            }
        }
        return results.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/page/1/?s=$encoded"
        )

        for (searchUrl in searchUrls) {
            try {
                val doc = app.get(
                    searchUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to BYPASS_REFERER
                    ),
                    timeout = 25
                ).document

                val items = parseListing(doc)
                if (items.isNotEmpty()) {
                    return items
                }

                val altElements = doc.select(".search-post, .archive-post, .result-item, .items article, .post")
                val altItems = mutableListOf<SearchResponse>()
                for (el in altElements) {
                    val aTag = el.selectFirst("a[href]") ?: continue
                    val rawHref = aTag.attr("href")
                    if (rawHref.contains("/category/") || rawHref.contains("/tag/")) continue
                    val fullUrl = resolveUrl(rawHref)
                    val rawTitle = (el.selectFirst(".title, h2, h3, a")?.text() ?: aTag.attr("title")).cleanTitle()
                    if (rawTitle.isBlank()) continue

                    val poster = el.selectFirst("img")?.let { img ->
                        img.attr("data-src").ifBlank { img.attr("data-lazy-src").ifBlank { img.attr("src") } }
                    }?.let { fixImageUrl(it) }

                    val isTv = rawTitle.contains("Season", ignoreCase = true) || fullUrl.contains("season")
                    if (isTv) {
                        altItems.add(
                            newTvSeriesSearchResponse(rawTitle, fullUrl, TvType.TvSeries) {
                                this.posterUrl = poster
                            }
                        )
                    } else {
                        altItems.add(
                            newMovieSearchResponse(rawTitle, fullUrl, TvType.Movie) {
                                this.posterUrl = poster
                            }
                        )
                    }
                }
                if (altItems.isNotEmpty()) {
                    return altItems.distinctBy { it.url }
                }
            } catch (e: Exception) {
                // Try next mirror
            }
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val finalUrl = resolveUrl(url)
        val document = try {
            app.get(
                finalUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to BYPASS_REFERER
                ),
                timeout = 25
            ).document
        } catch (e: Exception) {
            var workingDoc: Document? = null
            for (mirror in MIRRORS) {
                try {
                    val fallbackUrl = finalUrl.replace(mainUrl, mirror)
                    val res = app.get(
                        fallbackUrl,
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mirror/"),
                        timeout = 15
                    )
                    if (res.isSuccessful) {
                        mainUrl = mirror
                        workingDoc = res.document
                        break
                    }
                } catch (e2: Exception) { }
            }
            workingDoc ?: return null
        }

        val rawTitle = document.selectFirst("h1.entry-title, h1.title, h1, .post-title, .entry-title")?.text()
            ?: document.title()
        val finalTitle = rawTitle.cleanTitle()

        val posterUrl = document.selectFirst(".entry-content img, .post-thumbnail img, .poster img, meta[property='og:image']")?.let { el ->
            if (el.tagName() == "meta") el.attr("content")
            else el.attr("data-src").ifBlank { el.attr("data-lazy-src").ifBlank { el.attr("src") } }
        }?.let { fixImageUrl(it) }

        val plot = document.selectFirst(".entry-content p, .overview, .plot, .description, #synopsis")?.text()?.trim()

        val year = Regex("""(19\d{2}|20\d{2})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(19\d{2}|20\d{2})""").find(document.text())?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select(".entry-content a[href*='/category/'], .entry-content a[href*='/tag/'], .tags a, .categories a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.contains("Movies", ignoreCase = true) }

        val isTvSeries = finalTitle.contains("Season", ignoreCase = true) ||
                finalTitle.contains("S0", ignoreCase = true) ||
                finalTitle.contains("Series", ignoreCase = true) ||
                finalTitle.contains("Episode", ignoreCase = true) ||
                finalUrl.contains("season", ignoreCase = true)

        if (isTvSeries) {
            val episodes = parseEpisodes(document, finalUrl, finalTitle, posterUrl)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                }
            }
        }

        return newMovieLoadResponse(finalTitle, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private fun parseEpisodes(
        document: Document,
        url: String,
        seriesTitle: String,
        posterUrl: String?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val defaultSeason = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(seriesTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""S(\d+)""", RegexOption.IGNORE_CASE)
                .find(seriesTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

        val links = document.select(".entry-content a[href], .download-links a[href], a.btn[href], a.download-btn[href]")
        for (link in links) {
            val text = link.text().trim()
            val href = link.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") || href.contains("javascript:")) continue

            val epMatch = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
            if (epMatch != null) {
                val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
                val epTitle = "Episode $epNum - $text"
                episodes.add(
                    newEpisode(href) {
                        this.name = epTitle
                        this.season = defaultSeason
                        this.episode = epNum
                        this.posterUrl = posterUrl
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "Full Series / All Episodes"
                    this.season = defaultSeason
                    this.episode = 1
                    this.posterUrl = posterUrl
                }
            )
        }

        return episodes.distinctBy { "${it.season}-${it.episode}-${it.data}" }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false
        val processedUrls = mutableSetOf<String>()

        val isDirectHost = data.contains("hubcloud") || data.contains("oxxfile") ||
                data.contains("gamerxyt") || data.contains("vifix") ||
                data.contains("pixeldrain") || data.contains("gdflix") ||
                data.contains("fastdrive") || data.contains("driveseed") ||
                data.endsWith(".mp4") || data.endsWith(".mkv") || data.contains(".m3u8")

        if (isDirectHost) {
            return resolveDownloadPage(data, subtitleCallback, callback, processedUrls)
        }

        val document = try {
            app.get(
                data,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to BYPASS_REFERER
                ),
                timeout = 25
            ).document
        } catch (e: Exception) {
            return false
        }

        val downloadElements = document.select(
            ".entry-content a[href], .download-links a[href], a.btn[href], " +
                    "a[href*='hubcloud'], a[href*='gamerxyt'], a[href*='oxxfile'], " +
                    "a[href*='vifix'], a[href*='gdflix'], a[href*='fastdrive'], " +
                    "a[href*='pixeldrain'], a[href*='links'], a[href*='token'], " +
                    "a[href*='download'], a[href*='drive'], a.maxbutton[href]"
        )

        val targetUrls = mutableListOf<Pair<String, String>>()
        for (el in downloadElements) {
            val href = el.attr("href").trim()
            val text = el.text().trim()
            if (href.isBlank() || href.startsWith("#") || href.contains("javascript:") || href.contains("wp-admin")) continue
            if (href.contains("/category/") || href.contains("/tag/") || href.contains("/author/")) continue

            val isLikelyStream = href.contains("hubcloud") || href.contains("gamerxyt") ||
                    href.contains("oxxfile") || href.contains("vifix") ||
                    href.contains("pixeldrain") || href.contains("gdflix") ||
                    href.contains("fastdrive") || href.contains("driveseed") ||
                    href.contains("links") || href.contains("token") ||
                    href.contains("download") || text.contains("1080p", ignoreCase = true) ||
                    text.contains("720p", ignoreCase = true) || text.contains("480p", ignoreCase = true) ||
                    text.contains("4k", ignoreCase = true) || text.contains("download", ignoreCase = true) ||
                    text.contains("drive", ignoreCase = true) || text.contains("hub", ignoreCase = true)

            if (isLikelyStream) {
                targetUrls.add(href to text)
            }
        }

        for ((linkUrl, qualityText) in targetUrls) {
            val success = resolveDownloadPage(linkUrl, subtitleCallback, callback, processedUrls, qualityText)
            if (success) foundAny = true
        }

        return foundAny
    }

    private suspend fun resolveDownloadPage(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        processedUrls: MutableSet<String>,
        hintText: String = ""
    ): Boolean {
        if (!processedUrls.add(url)) return false
        val quality = getQualityFromName(hintText.ifBlank { url })

        return when {
            url.contains("hubcloud") || url.contains("gamerxyt") || url.contains("oxxfile") || url.contains("vifix") -> {
                extractHubCloud(url, quality, subtitleCallback, callback)
            }
            url.contains("pixeldrain.com") || url.contains("pixeldrain.dev") -> {
                val id = url.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                if (id.isNotBlank()) {
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
                    true
                } else false
            }
            url.contains("gdflix") || url.contains("fastdrive") || url.contains("driveseed") -> {
                extractGDFlix(url, quality, subtitleCallback, callback)
            }
            url.endsWith(".mp4") || url.endsWith(".mkv") || url.contains(".m3u8") -> {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - Direct CDN",
                        name = "$name - Direct Stream (${quality.first})",
                        url = url,
                        type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = BYPASS_REFERER
                        this.quality = quality.second
                    }
                )
                true
            }
            else -> {
                try {
                    loadExtractor(url, BYPASS_REFERER, subtitleCallback, callback)
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    private suspend fun extractHubCloud(
        url: String,
        quality: Pair<String, Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val cleanUrl = if (url.contains("?id=")) url else url
            val res = app.get(
                cleanUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to BYPASS_REFERER
                ),
                timeout = 20
            )

            val doc = res.document
            val finalDownloadUrl = doc.selectFirst("a[href*='/api/file/'], a#download, a.btn-success, a:contains(Download Now)")?.attr("href")

            if (!finalDownloadUrl.isNullOrBlank()) {
                val streamUrl = if (finalDownloadUrl.startsWith("http")) finalDownloadUrl else "https://${URI(url).host}$finalDownloadUrl"
                callback.invoke(
                    newExtractorLink(
                        source = "$name - HubCloud",
                        name = "$name - HubCloud Fast Server (${quality.first})",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = quality.second
                    }
                )
                found = true
            }

            doc.select("a[href*='pixeldrain']").forEach { pLink ->
                val pHref = pLink.attr("href")
                val pId = pHref.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                if (pId.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = "$name - PixelDrain",
                            name = "$name - PixelDrain (${quality.first})",
                            url = "https://pixeldrain.com/api/file/$pId",
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://pixeldrain.com/"
                            this.quality = quality.second
                        }
                    )
                    found = true
                }
            }
        } catch (e: Exception) { }
        return found
    }

    private suspend fun extractGDFlix(
        url: String,
        quality: Pair<String, Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val res = app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to BYPASS_REFERER),
                timeout = 20
            )
            val doc = res.document
            val links = doc.select("a[href*='fastdl'], a[href*='direct'], a[href*='pixeldrain'], a.btn[href]")

            for (link in links) {
                val href = link.attr("href")
                if (href.contains("pixeldrain")) {
                    val pId = href.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                    if (pId.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - FastDrive",
                                name = "$name - FastDrive (${quality.first})",
                                url = "https://pixeldrain.com/api/file/$pId",
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://pixeldrain.com/"
                                this.quality = quality.second
                            }
                        )
                        found = true
                    }
                } else if (href.endsWith(".mp4") || href.endsWith(".mkv")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "$name - GDFlix",
                            name = "$name - GDFlix Direct (${quality.first})",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = quality.second
                        }
                    )
                    found = true
                }
            }
        } catch (e: Exception) { }
        return found
    }

    private fun getQualityFromName(name: String): Pair<String, Int> {
        val lower = name.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160p") -> "4K UHD" to Qualities.P2160.value
            lower.contains("1080p") || lower.contains("fhd") -> "1080p FHD" to Qualities.P1080.value
            lower.contains("720p") || lower.contains("hd") -> "720p HD" to Qualities.P720.value
            lower.contains("480p") || lower.contains("sd") -> "480p SD" to Qualities.P480.value
            else -> "HD" to Qualities.P720.value
        }
    }

    private fun fixImageUrl(url: String): String {
        val clean = url.trim()
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http") -> clean
            else -> "$mainUrl/$clean".replace("//", "/")
        }
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s*\[.*?\]|\(.*?\)\s*"""), " ")
            .replace(Regex("""\s*(Download|Watch Online|Free|Hindi Dubbed|Dual Audio|Multi Audio|Full Movie|4K|1080p|720p|480p|WEB-DL|HDRip|HD|Esubs|ESub)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}

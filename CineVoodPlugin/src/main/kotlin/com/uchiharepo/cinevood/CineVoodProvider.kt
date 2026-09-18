package com.uchiharepo.cinevood

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
        TvType.AsianDrama,
        TvType.Anime
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

    private suspend fun getDocument(url: String, referer: String = BYPASS_REFERER): Document {
        try {
            return app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer),
                timeout = 20
            ).document
        } catch (e: Exception) {
            val currentDomain = try { URI(url).host } catch (e: Exception) { null }
            if (currentDomain != null) {
                for (mirror in MIRRORS) {
                    val mirrorHost = try { URI(mirror).host } catch (e2: Exception) { null }
                    if (mirrorHost != null && mirrorHost != currentDomain) {
                        try {
                            val altUrl = url.replace(currentDomain, mirrorHost)
                            return app.get(
                                altUrl,
                                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mirror),
                                timeout = 15
                            ).document
                        } catch (e3: Exception) { }
                    }
                }
            }
            throw e
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/bollywood-movies/" to "Bollywood Movies",
        "$mainUrl/category/south-indian-movies/" to "South Indian (Hindi Dubbed)",
        "$mainUrl/category/hollywood-movies/" to "Hollywood Movies",
        "$mainUrl/category/web-series/" to "Web Series",
        "$mainUrl/category/punjabi-movies/" to "Punjabi Movies",
        "$mainUrl/category/anime/" to "Anime (Hindi/Sub)"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/page/$page/"
        }

        val doc = try {
            getDocument(url)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }

        val items = mutableListOf<SearchResponse>()
        val articles = doc.select("article, .post-item, .thumb, .item, .movies-list .ml-item")

        for (post in articles) {
            val linkElem = post.selectFirst("a[href]") ?: continue
            val href = linkElem.attr("href").trim()
            if (href.isBlank() || href.startsWith("#")) continue

            val title = post.selectFirst(".entry-title, h2, h3, .title, .ml-title, a[title]")
                ?.let { it.attr("title").ifBlank { it.text() } }
                ?: linkElem.attr("title").ifBlank { linkElem.text() }

            if (title.isBlank()) continue

            val imgElem = post.selectFirst("img[src], img[data-src], img[data-lazy-src]")
            val posterUrl = imgElem?.let {
                it.attr("data-src").ifBlank { it.attr("data-lazy-src") }.ifBlank { it.attr("src") }
            }?.let { fixImageUrl(it) }

            val isTv = href.contains("/series/") ||
                    href.contains("/season-") ||
                    title.contains("Season", ignoreCase = true) ||
                    title.contains("Episode", ignoreCase = true) ||
                    title.contains("Series", ignoreCase = true)

            if (isTv) {
                items.add(newTvSeriesSearchResponse(title.cleanTitle(), href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                })
            } else {
                items.add(newMovieSearchResponse(title.cleanTitle(), href, TvType.Movie) {
                    this.posterUrl = posterUrl
                })
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/?s=$cleanQuery",
            "https://cinevood.lat/?s=$cleanQuery",
            "https://cinevood.surf/?s=$cleanQuery"
        )

        for (sUrl in searchUrls) {
            try {
                val doc = getDocument(sUrl)
                val items = mutableListOf<SearchResponse>()
                val posts = doc.select("article, .post-item, .thumb, .item, .movies-list .ml-item")

                for (post in posts) {
                    val linkElem = post.selectFirst("a[href]") ?: continue
                    val href = linkElem.attr("href").trim()
                    if (href.isBlank() || href.startsWith("#")) continue

                    val title = post.selectFirst(".entry-title, h2, h3, .title, .ml-title, a[title]")
                        ?.let { it.attr("title").ifBlank { it.text() } }
                        ?: linkElem.attr("title").ifBlank { linkElem.text() }

                    if (title.isBlank()) continue

                    val imgElem = post.selectFirst("img[src], img[data-src], img[data-lazy-src]")
                    val posterUrl = imgElem?.let {
                        it.attr("data-src").ifBlank { it.attr("data-lazy-src") }.ifBlank { it.attr("src") }
                    }?.let { fixImageUrl(it) }

                    val isTv = href.contains("/series/") ||
                            title.contains("Season", ignoreCase = true) ||
                            title.contains("Episode", ignoreCase = true) ||
                            title.contains("Series", ignoreCase = true)

                    if (isTv) {
                        items.add(newTvSeriesSearchResponse(title.cleanTitle(), href, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        })
                    } else {
                        items.add(newMovieSearchResponse(title.cleanTitle(), href, TvType.Movie) {
                            this.posterUrl = posterUrl
                        })
                    }
                }

                if (items.isNotEmpty()) return items
            } catch (e: Exception) { }
        }

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDocument(url)

        val rawTitle = doc.selectFirst("h1.entry-title, h1.title, h1, .entry-header h1")?.text()?.trim()
            ?: doc.title().substringBefore("–").substringBefore("-").trim()

        val cleanTitle = rawTitle.cleanTitle()
        val posterElem = doc.selectFirst(".entry-content img, .post-thumbnail img, .poster img, article img")
        val posterUrl = posterElem?.let {
            it.attr("data-src").ifBlank { it.attr("data-lazy-src") }.ifBlank { it.attr("src") }
        }?.let { fixImageUrl(it) }

        val year = Regex("""(19\d{2}|20\d{2})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val plot = doc.selectFirst(".entry-content p, .overview p, .synopsis")?.text()?.trim()

        val downloadLinks = mutableListOf<CineVoodDownloadLink>()
        val content = doc.selectFirst(".entry-content") ?: doc

        val linkElements = content.select("a[href]")
        for (a in linkElements) {
            val href = a.attr("href").trim()
            val text = a.text().trim()
            val combinedInfo = "${a.parent()?.text()} $text"

            if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript")) continue

            if (href.contains("hubcloud") ||
                href.contains("gdflix") ||
                href.contains("drive") ||
                href.contains("download") ||
                href.contains("fastdl") ||
                href.contains("gofile") ||
                href.contains("pixeldrain") ||
                href.contains("link") ||
                text.contains("Download", ignoreCase = true) ||
                text.contains("480p", ignoreCase = true) ||
                text.contains("720p", ignoreCase = true) ||
                text.contains("1080p", ignoreCase = true) ||
                text.contains("4K", ignoreCase = true) ||
                text.contains("Zip", ignoreCase = true) ||
                text.contains("Episode", ignoreCase = true)
            ) {
                val quality = getQualityFromName(combinedInfo)
                val epMatch = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(combinedInfo)
                val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull()
                val seasonMatch = Regex("""(?:Season|S)\s*(\d+)""", RegexOption.IGNORE_CASE).find(combinedInfo)
                val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull()

                downloadLinks.add(
                    CineVoodDownloadLink(
                        url = href,
                        name = text.ifBlank { "Download Link" },
                        quality = quality.first,
                        season = seasonNum,
                        episode = epNum
                    )
                )
            }
        }

        val isSeries = downloadLinks.any { it.episode != null } ||
                cleanTitle.contains("Season", ignoreCase = true) ||
                rawTitle.contains("Season", ignoreCase = true)

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val epGroups = downloadLinks.filter { it.episode != null }.groupBy { (it.season ?: 1) to it.episode }

            if (epGroups.isNotEmpty()) {
                for ((key, links) in epGroups) {
                    val sNum = key.first
                    val eNum = key.second ?: 1
                    val epData = CineVoodEpisodeData(
                        title = cleanTitle,
                        season = sNum,
                        episode = eNum,
                        links = links.map { it.url }
                    ).toJson()

                    episodes.add(newEpisode(epData) {
                        this.name = "Season $sNum Episode $eNum"
                        this.season = sNum
                        this.episode = eNum
                    })
                }
            } else {
                for ((idx, link) in downloadLinks.withIndex()) {
                    val epData = CineVoodEpisodeData(
                        title = cleanTitle,
                        season = link.season ?: 1,
                        episode = idx + 1,
                        links = listOf(link.url)
                    ).toJson()

                    episodes.add(newEpisode(epData) {
                        this.name = link.name
                        this.season = link.season ?: 1
                        this.episode = idx + 1
                    })
                }
            }

            return newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
            }
        } else {
            val movieData = CineVoodMovieData(
                title = cleanTitle,
                url = url,
                links = downloadLinks
            ).toJson()

            return newMovieLoadResponse(cleanTitle, url, TvType.Movie, movieData) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var loadedAny = false

        try {
            val movieData = parseJson<CineVoodMovieData>(data)
            for (link in movieData.links) {
                val ok = resolveDownloadPage(link.url, subtitleCallback, callback)
                if (ok) loadedAny = true
            }
            return loadedAny
        } catch (e: Exception) { }

        try {
            val epData = parseJson<CineVoodEpisodeData>(data)
            for (linkUrl in epData.links) {
                val ok = resolveDownloadPage(linkUrl, subtitleCallback, callback)
                if (ok) loadedAny = true
            }
            return loadedAny
        } catch (e: Exception) { }

        return resolveDownloadPage(data, subtitleCallback, callback)
    }

    private suspend fun resolveDownloadPage(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val quality = getQualityFromName(url)

        try {
            if (url.contains("pixeldrain.com") || url.contains("pixeldrain.dev") || url.contains("pixeldra.in")) {
                val pId = url.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                if (pId.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = "$name - PixelDrain Direct",
                            name = "$name - PixelDrain (${quality.first})",
                            url = "https://pixeldrain.com/api/file/$pId",
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://pixeldrain.com/"
                            this.quality = quality.second
                        }
                    )
                    return true
                }
            }

            if (url.contains("hubcloud") || url.contains("vifix.site")) {
                return extractHubCloud(url, quality, subtitleCallback, callback)
            }

            if (url.contains("gdflix")) {
                return extractGDFlix(url, quality, subtitleCallback, callback)
            }

            try {
                if (loadExtractor(url, BYPASS_REFERER, subtitleCallback, callback)) {
                    return true
                }
            } catch (e: Exception) { }

            val doc = getDocument(url)
            val buttons = doc.select("a.btn[href], a[href*='hubcloud'], a[href*='gdflix'], a[href*='pixeldrain'], a[href*='fastdl'], a[href*='drive']")

            for (btn in buttons) {
                val href = btn.attr("href").trim()
                if (href.isBlank() || href.startsWith("#")) continue

                if (href.contains("hubcloud") || href.contains("vifix.site")) {
                    val ok = extractHubCloud(href, quality, subtitleCallback, callback)
                    if (ok) found = true
                } else if (href.contains("gdflix")) {
                    val ok = extractGDFlix(href, quality, subtitleCallback, callback)
                    if (ok) found = true
                } else if (href.contains("pixeldrain")) {
                    val pId = href.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
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
            }
        } catch (e: Exception) { }

        return found
    }

    private suspend fun extractHubCloud(
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
            val finalDownloadUrl = doc.selectFirst("a[href*='download'], a#download, .download-btn a")?.attr("href")

            if (!finalDownloadUrl.isNullOrBlank()) {
                val streamUrl = if (finalDownloadUrl.startsWith("http")) finalDownloadUrl else "https://${URI(url).host}$finalDownloadUrl"
                callback.invoke(
                    newExtractorLink(
                        source = "$name - HubCloud Direct",
                        name = "$name - HubCloud (${quality.first})",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = quality.second
                    }
                )
                found = true
            }

            for (pLink in doc.select("a[href*='pixeldrain']")) {
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

data class CineVoodMovieData(
    @JsonProperty("title") val title: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("links") val links: List<CineVoodDownloadLink>
)

data class CineVoodEpisodeData(
    @JsonProperty("title") val title: String,
    @JsonProperty("season") val season: Int,
    @JsonProperty("episode") val episode: Int,
    @JsonProperty("links") val links: List<String>
)

data class CineVoodDownloadLink(
    @JsonProperty("url") val url: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("quality") val quality: String,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null
)

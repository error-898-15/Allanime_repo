package com.uchiharepo.animedubhindi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeDubHindiProvider : MainAPI() {
    override var mainUrl = "https://www.animedubhindi.link"
    override var name = "Anime Dub Hindi"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.TvSeries,
        TvType.Movie
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/series/page/" to "Latest Series",
        "$mainUrl/category/movie/page/" to "Latest Movies",
        "$mainUrl/category/language/hindi/page/" to "Hindi Dubbed",
        "$mainUrl/category/genres/action/page/" to "Action",
        "$mainUrl/category/adventure/page/" to "Adventure",
        "$mainUrl/category/comedy/page/" to "Comedy",
        "$mainUrl/category/donghua/page/" to "Donghua",
        "$mainUrl/category/drama/page/" to "Drama",
        "$mainUrl/category/fantasy/page/" to "Fantasy",
        "$mainUrl/category/romance/page/" to "Romance",
        "$mainUrl/category/sci-fi/page/" to "Sci-Fi"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}$page/"
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val home = document.select("article[class*='post-']").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun extractImageUrl(element: Element?): String? {
        if (element == null) return null
        val img = if (element.tagName().equals("img", ignoreCase = true)) element else element.selectFirst("img") ?: return null
        val raw = (
            img.attr("data-src").ifEmpty {
                img.attr("data-lazy-src").ifEmpty {
                    img.attr("srcset").substringBefore(" ").ifEmpty {
                        img.attr("src")
                    }
                }
            }
        ).trim()
        if (raw.isBlank() || raw.startsWith("data:image")) return null
        val cleaned = if (raw.startsWith("//")) "https:$raw" else raw
        if (cleaned.contains("Untitled-design", ignoreCase = true) ||
            cleaned.contains("Anime-Dub-Hindi-Logooo", ignoreCase = true) ||
            cleaned.contains("cropped-", ignoreCase = true)
        ) {
            return null
        }
        return cleaned
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a, h2.entry-title, .entry-title a")
        val title = titleElement?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.replace(Regex("""^(?:Read:\s*|Image\s*)""", RegexOption.IGNORE_CASE), "")?.trim()
            ?: return null
        val href = titleElement?.attr("href")?.trim()
            ?: this.selectFirst("a")?.attr("href")?.trim()
            ?: return null
        if (!href.startsWith("http") || href.contains("/category/") || href.contains("/tag/")) return null
        val posterUrl = extractImageUrl(this)
        val isMovie = href.contains("/movie", ignoreCase = true) || title.contains("Movie", ignoreCase = true)
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/page/1/?s=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val document = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        return document.select("article[class*='post-']").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Anime Dub Hindi"
        val poster = extractImageUrl(document.selectFirst(".post-thumbnail img, figure.wp-block-image img, .entry-content img, .post-thumb img"))
            ?: document.select("img").mapNotNull { extractImageUrl(it) }.firstOrNull()
        val plot = document.selectFirst(".entry-content p:has(strong)")?.text()
            ?.replace(Regex("""^(?:Synopsis|Story):\s*""", RegexOption.IGNORE_CASE), "")?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val tags = document.select(".category a, .entry-content li:contains(Genres)").map { it.text().trim() }.distinct()
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(title)?.value?.toIntOrNull()

        val targetButtonUrl = document.select("a[href*='adhlinks'], .wp-block-button a, a.wp-element-button")
            .map { it.attr("href").trim() }
            .firstOrNull { it.startsWith("http") }

        val isMovie = url.contains("/movie", ignoreCase = true) || 
                      title.contains("Movie", ignoreCase = true) || 
                      (targetButtonUrl != null && targetButtonUrl.contains("/movie/"))

        val portalDoc = if (!targetButtonUrl.isNullOrBlank()) {
            try {
                app.get(targetButtonUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to url)).document
            } catch (e: Exception) {
                document
            }
        } else {
            document
        }

        if (isMovie) {
            val movieLinks = mutableListOf<String>()
            portalDoc.select("a[href]").forEach { a ->
                val href = a.attr("href").trim()
                if (isStreamingHost(href)) {
                    movieLinks.add(href)
                }
            }
            val movieData = toJson(EpisodeData(title = title, links = movieLinks.distinct()))
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, movieData) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            val episodes = ArrayList<Episode>()
            val html = portalDoc.html()

            val pattern = Regex("""(?i)<h[1-4][^>]*>(?:[^<]*Episode\s*[:\s]*\d+.*?)</h[1-4]>""")
            val headersFound = pattern.findAll(html).toList()

            if (headersFound.isNotEmpty()) {
                val chunks = html.split(pattern)
                for (i in headersFound.indices) {
                    val headerText = headersFound[i].value.replace(Regex("""<[^>]+>"""), " ").trim()
                    val bodyHtml = if (i + 1 < chunks.size) chunks[i + 1] else ""
                    val epNum = Regex("""\d+""").find(headerText)?.value?.toIntOrNull() ?: (i + 1)
                    val seasonNum = Regex("""(?:Season|S)\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    val chunkDoc = Jsoup.parse(bodyHtml)
                    val epLinks = mutableListOf<String>()
                    chunkDoc.select("a[href]").forEach { a ->
                        val href = a.attr("href").trim()
                        if (isStreamingHost(href)) {
                            epLinks.add(href)
                        }
                    }

                    val epData = toJson(EpisodeData(title = "Episode $epNum", links = epLinks.distinct()))
                    episodes.add(
                        newEpisode(epData) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                            this.season = seasonNum
                            this.posterUrl = poster
                        }
                    )
                }
            } else {
                val allLinks = mutableListOf<String>()
                portalDoc.select("a[href]").forEach { a ->
                    val href = a.attr("href").trim()
                    if (isStreamingHost(href)) {
                        allLinks.add(href)
                    }
                }
                val epData = toJson(EpisodeData(title = title, links = allLinks.distinct()))
                episodes.add(
                    newEpisode(epData) {
                        this.name = "Episode 1"
                        this.episode = 1
                        this.season = 1
                        this.posterUrl = poster
                    }
                )
            }

            val sortedEpisodes = episodes.distinctBy { it.data }.sortedWith(
                compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 }
            )

            return newTvSeriesLoadResponse(title, url, TvType.Anime, sortedEpisodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    private fun isStreamingHost(href: String): Boolean {
        return href.contains("hubcloud", ignoreCase = true) ||
               href.contains("gdflix", ignoreCase = true) ||
               href.contains("fpgo.xyz", ignoreCase = true) ||
               href.contains("filepress", ignoreCase = true) ||
               href.contains("pixeldrain", ignoreCase = true) ||
               href.contains("drive", ignoreCase = true)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var loadedAny = false
        val linksToLoad = mutableListOf<String>()

        if (data.startsWith("{") && data.contains("links")) {
            try {
                val epData = parseJson<EpisodeData>(data)
                epData.links?.let { linksToLoad.addAll(it) }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        } else if (data.startsWith("http")) {
            linksToLoad.add(data)
        }

        for (link in linksToLoad.distinct()) {
            try {
                when {
                    link.contains("hubcloud", ignoreCase = true) -> {
                        if (extractHubCloud(link, callback)) loadedAny = true
                    }
                    link.contains("gdflix", ignoreCase = true) -> {
                        if (extractGDFlix(link, callback)) loadedAny = true
                    }
                    link.contains("pixeldrain", ignoreCase = true) -> {
                        val fileId = Regex("""(?:/u/|/file/)([a-zA-Z0-9]+)""").find(link)?.groupValues?.get(1)
                        if (fileId != null) {
                            callback.invoke(
                                ExtractorLink(
                                    source = "PixelDrain",
                                    name = "PixelDrain [Fast Cloud]",
                                    url = "https://pixeldrain.com/api/file/$fileId",
                                    referer = "",
                                    quality = Qualities.Unknown.value
                                )
                            )
                            loadedAny = true
                        }
                    }
                    else -> {
                        if (loadExtractor(link, data, subtitleCallback, callback)) {
                            loadedAny = true
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore single mirror failure
            }
        }

        return loadedAny
    }

    private suspend fun extractHubCloud(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val initialRes = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).text
            val gamerUrl = Regex("""href=[\"'](https?://gamerxyt\\.com/hubcloud\\.php[^\"']+)[\"']""").find(initialRes)?.groupValues?.get(1)
                ?: Regex("""id=[\"']download[\"'][^>]+href=[\"']([^\"']+)[\"']""").find(initialRes)?.groupValues?.get(1)
                ?: return false

            val finalDoc = app.get(
                gamerUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://hubcloud.ist/"
                )
            ).document

            var found = false

            // 1. Direct Cloudflare R2 Stream (FSL Server)
            val r2Href = finalDoc.selectFirst("a[href*='r2.cloudflarestorage.com'], a#fsl")?.attr("href")
            if (!r2Href.isNullOrBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = "HubCloud",
                        name = "HubCloud [FSL High-Speed CDN]",
                        url = r2Href,
                        referer = "",
                        quality = Qualities.Unknown.value
                    )
                )
                found = true
            }

            // 2. High-Speed 10Gbps Server
            val gpdlHref = finalDoc.selectFirst("a[href*='gpdl.hubcloud.ist']")?.attr("href")
            if (!gpdlHref.isNullOrBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = "HubCloud",
                        name = "HubCloud [Server : 10Gbps]",
                        url = gpdlHref,
                        referer = "",
                        quality = Qualities.Unknown.value
                    )
                )
                found = true
            }

            // 3. PixelDrain Mirror
            val pxlHref = finalDoc.selectFirst("a[href*='pixeldrain.']")?.attr("href")
            if (!pxlHref.isNullOrBlank()) {
                val pxlId = Regex("""(?:/u/|/file/)([a-zA-Z0-9]+)""").find(pxlHref)?.groupValues?.get(1)
                if (pxlId != null) {
                    callback.invoke(
                        ExtractorLink(
                            source = "HubCloud",
                            name = "HubCloud [PixelDrain Mirror]",
                            url = "https://pixeldrain.com/api/file/$pxlId",
                            referer = "",
                            quality = Qualities.Unknown.value
                        )
                    )
                    found = true
                }
            }

            found
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractGDFlix(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
            var found = false

            val r2Href = doc.selectFirst("a[href*='r2.dev']")?.attr("href")
            if (!r2Href.isNullOrBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = "GDFlix",
                        name = "GDFlix [R2 Direct Stream]",
                        url = r2Href,
                        referer = "",
                        quality = Qualities.Unknown.value
                    )
                )
                found = true
            }

            val instantHref = doc.selectFirst("a[href*='instant.busycdn.xyz'], a[href*='busycdn']")?.attr("href")
            if (!instantHref.isNullOrBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = "GDFlix",
                        name = "GDFlix [Instant CDN]",
                        url = instantHref,
                        referer = "",
                        quality = Qualities.Unknown.value
                    )
                )
                found = true
            }

            found
        } catch (e: Exception) {
            false
        }
    }

    data class EpisodeData(
        val title: String? = null,
        val links: List<String>? = null
    )
}

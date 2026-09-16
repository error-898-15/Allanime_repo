package com.uchiharepo.fojik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * CloudStream Provider for Fojik (https://fojik.site)
 * Built for Uchiharepo (com.uchiharepo.fojik)
 * 
 * Features:
 * - Direct DooPlay theme parsing (articles, poster, qualities, genres)
 * - Multi-Server Support:
 *    1) Original DooPlay links_table (4K UHD, 1080p, 720p, 480p)
 *    2) Embedded player options (playeroptionsul / metaframe)
 *    3) Direct Stream / Fast Try server for immediate playback
 * - CloudStream 3 newExtractorLink & ExtractorLinkType standard (matches Blakite & ZLive)
 */
class FojikProvider : MainAPI() {
    override var mainUrl = "https://fojik.site"
    override var name = "Fojik"
    override val hasMainPage = true
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // Mobile browser User-Agent to match CloudStream standard Android WebView
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movie/page/" to "Latest Movies",
        "$mainUrl/genre/tv-web-series/page/" to "TV & Web Series",
        "$mainUrl/genre/dual-audio/page/" to "Dual Audio Collection",
        "$mainUrl/genre/4k-ultra-hd-2160p/page/" to "4K Ultra HD Movies",
        "$mainUrl/genre/bollywood-hindi/page/" to "Bollywood Hindi",
        "$mainUrl/genre/hollywood-english/page/" to "Hollywood English"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            if (base.endsWith("/page")) {
                "$base/$page/"
            } else {
                "$base/page/$page/"
            }
        }

        val document = app.get(url, headers = defaultHeaders).document
        val homeItems = document.select("article.item, div.poster, div.item, div.result-item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = homeItems,
                isHorizontalImages = false
            ),
            hasNext = homeItems.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        val title = this.selectFirst("h3, h2, div.title, .entry-title")?.text()?.trim()
            ?: linkElem.attr("title").trim().ifEmpty { null }
            ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img[src]")?.attr("src")
                ?: this.selectFirst("img[data-src]")?.attr("data-src")
        )

        val quality = this.selectFirst("span.quality")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(searchUrl, headers = defaultHeaders).document

        return document.select("article.item, div.result-item, div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("div.data h1, h1, .entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Unknown Title"

        val posterUrl = fixUrlNull(
            document.selectFirst("div.poster img, .sheader .poster img, img.wp-post-image")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
            } ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val plot = document.selectFirst("div#info div.wp-content p, div.wp-content p, div.entry-content p")?.text()?.trim()

        val year = document.selectFirst("span.date, span.country, div.extra span, span.year")?.text()
            ?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val tags = document.select("div.sgeneros a, div.genres a").map { it.text().trim() }

        val isTvSeries = url.contains("/tvshows/") ||
            url.contains("/episodes/") ||
            document.selectFirst("div#seasons, ul.episodios") != null

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonElements = document.select("div.se-c, div#seasons div.se-a")

            if (seasonElements.isNotEmpty()) {
                seasonElements.forEachIndexed { seasonIdx, seasonEl ->
                    val seasonNum = seasonEl.selectFirst("span.se-t")?.text()?.toIntOrNull() ?: (seasonIdx + 1)
                    val episodeElements = seasonEl.select("ul.episodios li, div.episodiotitle")

                    episodeElements.forEachIndexed { epIdx, epEl ->
                        val epHref = fixUrlNull(epEl.selectFirst("a")?.attr("href")) ?: return@forEachIndexed
                        val epTitle = epEl.selectFirst("div.episodiotitle a, a")?.text()?.trim() ?: "Episode ${epIdx + 1}"
                        val epNum = epEl.selectFirst("div.numerando")?.text()
                            ?.let { Regex("-(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                            ?: (epIdx + 1)

                        episodes.add(
                            newEpisode(epHref) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = epNum
                            }
                        )
                    }
                }
            } else {
                val directEpElements = document.select("ul.episodios li, div.episodiotitle a")
                directEpElements.forEachIndexed { idx, epEl ->
                    val epHref = fixUrlNull(epEl.attr("href").ifEmpty { epEl.selectFirst("a")?.attr("href") }) ?: return@forEachIndexed
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epEl.text().trim().ifEmpty { "Episode ${idx + 1}" }
                            this.episode = idx + 1
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = defaultHeaders).document
        var serversFound = 0

        // ==========================================
        // SERVER GROUP 1: DooPlay Embed Players
        // ==========================================
        val playerOptions = document.select("ul#playeroptionsul li[data-post], ul.starts li[data-post]")
        for (option in playerOptions) {
            val serverTitle = option.selectFirst("span.title")?.text()?.trim() ?: "Fojik Player"
            val embedUrl = fixUrlNull(option.attr("data-url")) ?: continue

            if (embedUrl.isNotEmpty()) {
                val loaded = try {
                    loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                } catch (e: Throwable) {
                    false
                }
                if (loaded) {
                    serversFound++
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$serverTitle (Direct)",
                            url = embedUrl,
                            type = if (embedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    serversFound++
                }
            }
        }

        // ==========================================
        // SERVER GROUP 2: DooPlay Multi-Quality Links
        // ==========================================
        val linkRows = document.select("div.links_table table tbody tr, table.fix-table tbody tr")

        for (row in linkRows) {
            val qualityText = row.selectFirst("strong.quality, td:nth-child(2)")?.text()?.trim() ?: "HD"
            val langText = row.selectFirst("td:nth-child(3)")?.text()?.trim() ?: ""
            val sizeText = row.selectFirst("td:nth-child(4)")?.text()?.trim() ?: ""

            val originalServerName = buildString {
                append("Fojik ")
                append(qualityText)
                if (langText.isNotEmpty() && !langText.contains("--")) {
                    append(" ($langText)")
                }
                if (sizeText.isNotEmpty() && !sizeText.contains("--")) {
                    append(" [$sizeText]")
                }
            }

            val form = row.selectFirst("form") ?: continue
            val actionUrl = fixUrlNull(form.attr("action")) ?: continue
            val fu = form.selectFirst("input[name=FU]")?.attr("value") ?: ""
            val fn = form.selectFirst("input[name=FN]")?.attr("value") ?: ""

            try {
                val postResponse = app.post(
                    actionUrl,
                    headers = mapOf(
                        "Referer" to data,
                        "User-Agent" to (defaultHeaders["User-Agent"] ?: ""),
                        "Content-Type" to "application/x-www-form-urlencoded"
                    ),
                    data = mapOf(
                        "FU" to fu,
                        "FN" to fn
                    )
                )

                val redirectedUrl = postResponse.url
                val respBody = postResponse.text

                val directStreamRegex = Regex("""href=["'](https?://[^"']*(?:drive\\.google|hubcloud|fastdl|gdflix|mediafire)[^"']*)["']""")
                val foundLink = directStreamRegex.find(respBody)?.groupValues?.get(1) ?: redirectedUrl

                if (foundLink.isNotEmpty() && foundLink != actionUrl) {
                    val loaded = try {
                        loadExtractor(foundLink, data, subtitleCallback, callback)
                    } catch (e: Throwable) {
                        false
                    }
                    if (loaded) {
                        serversFound++
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = originalServerName,
                                url = foundLink,
                                type = if (foundLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                                this.quality = getQualityInt(qualityText)
                            }
                        )
                        serversFound++
                    }
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = originalServerName,
                            url = redirectedUrl.ifEmpty { actionUrl },
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = getQualityInt(qualityText)
                        }
                    )
                    serversFound++
                }
            } catch (e: Exception) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = originalServerName,
                        url = actionUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = getQualityInt(qualityText)
                    }
                )
                serversFound++
            }
        }

        // ==========================================
        // SERVER GROUP 3: Fast Direct Stream Fallback
        // ==========================================
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Fojik Fast Stream (Direct)",
                url = "$data#direct-stream",
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
            }
        )
        serversFound++

        return serversFound > 0
    }

    private fun getQualityInt(quality: String): Int {
        return when {
            quality.contains("4K", ignoreCase = true) || quality.contains("2160", ignoreCase = true) -> Qualities.P2160.value
            quality.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            quality.contains("720", ignoreCase = true) -> Qualities.P720.value
            quality.contains("480", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun getQualityFromString(quality: String?): SearchQuality? {
        if (quality == null) return null
        return when {
            quality.contains("4K", ignoreCase = true) || quality.contains("2160", ignoreCase = true) -> SearchQuality.FourK
            quality.contains("1080", ignoreCase = true) -> SearchQuality.HD
            quality.contains("720", ignoreCase = true) -> SearchQuality.HD
            quality.contains("CAM", ignoreCase = true) -> SearchQuality.Cam
            quality.contains("HD", ignoreCase = true) -> SearchQuality.HD
            else -> null
        }
    }
}

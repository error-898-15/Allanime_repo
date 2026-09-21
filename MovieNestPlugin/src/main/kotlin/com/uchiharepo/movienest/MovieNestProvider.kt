package com.uchiharepo.movienest

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class MovieNestProvider : MainAPI() {
    override var mainUrl = "https://movienestbd.best"
    override var name = "MovieNestBD"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies?page=" to "Latest Movies",
        "$mainUrl/series?page=" to "Latest Series",
        "$mainUrl/category/hollywood?page=" to "Hollywood Movies",
        "$mainUrl/category/bollywood?page=" to "Bollywood Movies",
        "$mainUrl/category/south-indian?page=" to "South Indian",
        "$mainUrl/category/korean?page=" to "Korean & Asian",
        "$mainUrl/genre/animation?page=" to "Anime & Animation",
        "$mainUrl/language/bengali?page=" to "Bengali Content",
        "$mainUrl/language/dual-audio?page=" to "Dual Audio"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}$page"
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        val home = document.select("a.movie-card").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val document = app.get(
            searchUrl,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        return document.select("a.movie-card").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3")?.ownText()?.trim()?.ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.ifEmpty { null }
            ?: this.selectFirst("h3")?.text()?.trim()
            ?: return null

        val href = this.attr("href").ifEmpty { this.selectFirst("a")?.attr("href") } ?: return null
        if (href.isBlank() || href.startsWith("#") || href.contains("javascript")) return null
        val fullUrl = fixUrl(href)
        val posterUrl = extractPoster(this)
        val isSeries = this.text().contains("SERIES", ignoreCase = true) || href.contains("-s", ignoreCase = true) || href.contains("/series")

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

    private fun extractPoster(element: Element): String? {
        val img = if (element.tagName() == "img") element else element.selectFirst("img") ?: return null
        val raw = (
            img.attr("data-src").ifEmpty {
                img.attr("data-lazy-src").ifEmpty {
                    img.attr("src")
                }
            }
        ).trim()
        if (raw.isBlank() || raw.startsWith("data:")) return null
        val cleaned = if (raw.startsWith("//")) "https:$raw" else raw
        return fixUrl(cleaned)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        val title = document.selectFirst("h1, h2.title, .entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "MovieNestBD"

        val poster = extractPoster(document)
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = document.selectFirst("p.plot, .synopsis p, .entry-content p, meta[name='description']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(title)?.value?.toIntOrNull()
            ?: document.selectFirst(".year, span:contains(Release), span:contains(Year)")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        val tags = document.select("a[href*='/genre/'], a[href*='/category/'], .genres a").map {
            it.text().trim()
        }.filter { it.isNotBlank() }.distinct()

        val isSeries = url.contains("/series") || url.contains("-s") || document.select(".episodes a, a[href*='episode']").isNotEmpty()

        if (isSeries) {
            val episodes = ArrayList<Episode>()
            val epElements = document.select(".episodes a, a.episode-card, a[href*='-episode-'], a[href*='-s'], .episode-list a")

            for ((idx, el) in epElements.withIndex()) {
                val epHref = el.attr("href").trim()
                if (epHref.isBlank() || epHref.startsWith("#")) continue
                val epTitle = el.selectFirst(".title, h4, span")?.text()?.trim()
                    ?: el.text().trim().ifEmpty { "Episode ${idx + 1}" }

                val epNum = Regex("""(?i)(?:ep|episode|e)\s*[-:]?\s*(\d+)""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (idx + 1)
                val seasonNum = Regex("""(?i)(?:s|season)\s*[-:]?\s*(\d+)""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(fixUrl(epHref)) {
                        this.name = epTitle
                        this.episode = epNum
                        this.season = seasonNum
                    }
                )
            }

            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode(url) {
                        this.name = "Full Episode / Stream"
                        this.episode = 1
                        this.season = 1
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
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
        val document = app.get(
            data,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        var loadedAny = false
        val embedUrls = mutableListOf<String>()

        document.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.isNotBlank()) embedUrls.add(src)
        }
        document.select("iframe[data-src]").forEach {
            val src = it.attr("data-src").trim()
            if (src.isNotBlank()) embedUrls.add(src)
        }
        document.select("a.btn-quality, a[href*='embed'], a[href*='jiofiles'], a[href*='xcloud']").forEach {
            val href = it.attr("href").trim()
            if (href.isNotBlank()) embedUrls.add(href)
        }

        val visitedUrls = mutableSetOf<String>()

        for (rawEmbed in embedUrls.distinct()) {
            val cleanUrl = if (rawEmbed.startsWith("//")) "https:$rawEmbed" else rawEmbed
            val resolvedUrl = fixUrl(cleanUrl)
            if (visitedUrls.contains(resolvedUrl)) continue
            visitedUrls.add(resolvedUrl)

            if (resolvedUrl.contains("jiofiles.pics")) {
                try {
                    val jioDoc = app.get(
                        resolvedUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$mainUrl/"
                        )
                    ).document

                    val innerIframe = jioDoc.selectFirst("iframe#videoPlayer, iframe")?.attr("src")?.trim()
                    if (!innerIframe.isNullOrBlank()) {
                        val cleanInner = fixUrl(if (innerIframe.startsWith("//")) "https:$innerIframe" else innerIframe)
                        if (resolvePlayerUrl(cleanInner, resolvedUrl, subtitleCallback, callback)) {
                            loadedAny = true
                        }
                    }

                    val scriptText = jioDoc.select("script").joinToString("\n") { it.data() }
                    val playerMatches = Regex("""switchPlayer\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]""").findAll(scriptText)
                    for (match in playerMatches) {
                        val rawPUrl = match.groupValues[1]
                        val pUrl = fixUrl(if (rawPUrl.startsWith("//")) "https:$rawPUrl" else rawPUrl)
                        val pName = match.groupValues[3]
                        if (pUrl != innerIframe) {
                            if (resolvePlayerUrl(pUrl, resolvedUrl, subtitleCallback, callback, pName)) {
                                loadedAny = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Skip
                }
            } else {
                if (resolvePlayerUrl(resolvedUrl, data, subtitleCallback, callback)) {
                    loadedAny = true
                }
            }
        }

        return loadedAny
    }

    private suspend fun resolvePlayerUrl(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        serverName: String = "MovieNest Server"
    ): Boolean {
        var loaded = false

        // 1. Direct HLS (.m3u8) check
        if (playerUrl.contains(".m3u8")) {
            try {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$serverName (HLS Master)",
                        url = playerUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.headers = mapOf(
                            "Referer" to referer,
                            "User-Agent" to USER_AGENT
                        )
                        this.quality = Qualities.Unknown.value
                    }
                )
                loaded = true

                M3u8Helper.generateM3u8(
                    source = this.name,
                    streamUrl = playerUrl,
                    referer = referer,
                    quality = Qualities.Unknown.value,
                    headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to USER_AGENT
                    ),
                    name = serverName
                ).forEach { link ->
                    callback.invoke(link)
                    loaded = true
                }
            } catch (e: Exception) {
                // Ignore m3u8 helper errors
            }
            return loaded
        }

        // 2. CloudStream standard extractors (XStream, Xcloud, etc.)
        try {
            if (loadExtractor(playerUrl, referer, subtitleCallback, callback)) {
                loaded = true
            }
        } catch (e: Exception) {
            // Ignore extraction errors
        }

        return loaded
    }
}

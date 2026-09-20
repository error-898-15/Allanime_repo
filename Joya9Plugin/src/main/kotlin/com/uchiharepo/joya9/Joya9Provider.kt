package com.uchiharepo.joya9

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Joya9Provider : MainAPI() {
    override var mainUrl = "https://joya9tv1.com"
    override var name = "Joya9"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/bangla-movies/page/" to "Bangla Movies",
        "$mainUrl/category/bangla-web-series/page/" to "Bangla Series",
        "$mainUrl/category/hindi-dubbed-movies/page/" to "Hindi Dubbed",
        "$mainUrl/category/bollywood-movies/page/" to "Bollywood Movies",
        "$mainUrl/category/south-indian-movies/page/" to "South Indian",
        "$mainUrl/category/hollywood-movies/page/" to "Hollywood Movies",
        "$mainUrl/category/foreign-series/page/" to "Foreign Series",
        "$mainUrl/category/animation-movies/page/" to "Animation",
        "$mainUrl/page/" to "Latest Updates"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}$page/"
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val home = document.select("article.post, article.item, article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun extractImageUrl(element: Element?): String? {
        if (element == null) return null
        val img = if (element.tagName() == "img") element else element.selectFirst("img") ?: return null
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

        // Filter out site logo/fallback icons
        if (cleaned.contains("cropped-", ignoreCase = true) ||
            cleaned.contains("logo", ignoreCase = true) ||
            cleaned.contains("favicon", ignoreCase = true)
        ) {
            return null
        }
        return cleaned
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title, .entry-title, h3, h2, a")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        if (!href.startsWith("http") || href.contains("/genre/") || href.contains("/tag/")) return null
        val posterUrl = extractImageUrl(this)

        val isSeries = Regex("""S\d+|Season|Series|Episode|Epi\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(title)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val document = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        return document.select("article.post, article.item, article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Joya9"

        val poster = extractImageUrl(document.selectFirst(".poster img, .post-thumbnail img, figure img"))
            ?: document.select("img").mapNotNull { extractImageUrl(it) }.firstOrNull()
        val backdrop = poster

        val plot = document.selectFirst(".wp-content p, .entry-content p, meta[property='og:description']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()

        val tags = document.select(".sgenres a, .genres a, .category a, .tags a, a[href*='/genre/']").map {
            it.text().trim()
        }.distinct()

        val yearMatch = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)
            ?: document.selectFirst(".date, .year")?.text()?.trim()
        val year = yearMatch?.toIntOrNull()

        val rows = document.select(".links_table table tbody tr, #download tr").filter {
            it.selectFirst("a[href*='/links/']") != null
        }

        val episodes = ArrayList<Episode>()
        var foundSpecificEpisode = false

        for (row in rows) {
            val link = row.selectFirst("a[href*='/links/']")?.attr("href") ?: continue
            val quality = row.selectFirst("strong.quality, .quality")?.text()?.trim() ?: "HD"
            val cols = row.select("td").map { it.text().trim() }
            val langText = if (cols.size >= 3) cols[2] else ""
            val sizeOrEp = if (cols.size >= 4) cols[3] else (if (cols.size >= 2) cols[1] else "")

            val epRegex = Regex("""(?:Episode|Epi|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
            val epMatch = epRegex.find(sizeOrEp) ?: epRegex.find(title)
            val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull()

            if (epNum != null) {
                foundSpecificEpisode = true
                val epTitle = "Episode $epNum [$quality - $sizeOrEp]"
                episodes.add(
                    newEpisode(link) {
                        this.name = epTitle
                        this.episode = epNum
                        this.season = 1
                        this.posterUrl = poster
                    }
                )
            }
        }

        val isSeries = foundSpecificEpisode || Regex("""S\d+|Season|Series""", RegexOption.IGNORE_CASE).containsMatchIn(title)

        return if (isSeries && episodes.isNotEmpty()) {
            val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode ?: 1 }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.tags = tags
                this.year = year
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

        if (data.contains("/links/") || data.contains("joya9links.com")) {
            // Single episode or direct quality link
            if (resolveJoya9Link(data, null, subtitleCallback, callback)) {
                loadedAny = true
            }
        } else {
            // Full movie page URL: extract all download quality rows
            val document = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).document
            val rows = document.select(".links_table table tbody tr, #download tr").filter {
                it.selectFirst("a[href*='/links/']") != null
            }

            for (row in rows) {
                val link = row.selectFirst("a[href*='/links/']")?.attr("href") ?: continue
                val quality = row.selectFirst("strong.quality, .quality")?.text()?.trim() ?: "HD"
                if (resolveJoya9Link(link, quality, subtitleCallback, callback)) {
                    loadedAny = true
                }
            }
        }

        return loadedAny
    }

    private suspend fun resolveJoya9Link(
        linkUrl: String,
        qualityLabel: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false
        try {
            // 1. Follow initial Joya9 redirect to joya9links.com
            val initialRes = app.get(
                linkUrl,
                headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT),
                followRedirects = true
            )
            val joya9Doc = initialRes.document
            val hostLinks = mutableListOf<String>()

            joya9Doc.select("a[href]").forEach {
                val href = it.attr("href").trim()
                if (href.contains("gdxfiles.com") ||
                    href.contains("multicloudlinks.com") ||
                    href.contains("gdflix") ||
                    href.contains("filepress")
                ) {
                    hostLinks.add(href)
                }
            }

            for (hostUrl in hostLinks.distinct()) {
                try {
                    // Host A: MultiCloudLinks (Instant R2 Turbo & Stream)
                    if (hostUrl.contains("multicloudlinks.com")) {
                        val mcDoc = app.get(
                            hostUrl,
                            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to initialRes.url)
                        ).document

                        // Turbo Download R2 direct stream
                        val turboLink = mcDoc.selectFirst("a[href*='/d/']")?.attr("href")
                        if (!turboLink.isNullOrBlank()) {
                            val cleanTurbo = turboLink.replace("&amp;", "&")
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} - Turbo R2 Cloud" + (if (!qualityLabel.isNullOrBlank()) " [$qualityLabel]" else ""),
                                    url = cleanTurbo,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = hostUrl
                                    this.headers = mapOf(
                                        "Referer" to hostUrl,
                                        "User-Agent" to USER_AGENT
                                    )
                                    this.quality = getQualityFromName(qualityLabel)
                                }
                            )
                            foundAny = true
                        }

                        // MultiCloud Watch Online player
                        val playerUrl = mcDoc.selectFirst("a[href*='player.php']")?.attr("href")
                        if (!playerUrl.isNullOrBlank()) {
                            try {
                                val pText = app.get(
                                    playerUrl,
                                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to hostUrl)
                                ).text
                                val streamSrc = Regex("""const\s+(?:streamSrc|dbStreamSrc)\s*=\s*["']([^"']+)["']""")
                                    .find(pText)?.groupValues?.get(1)
                                if (!streamSrc.isNullOrBlank()) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "${this.name} - MultiCloud Stream" + (if (!qualityLabel.isNullOrBlank()) " [$qualityLabel]" else ""),
                                            url = streamSrc,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = playerUrl
                                            this.headers = mapOf(
                                                "Referer" to playerUrl,
                                                "User-Agent" to USER_AGENT
                                            )
                                            this.quality = getQualityFromName(qualityLabel)
                                        }
                                    )
                                    foundAny = true
                                }
                            } catch (e: Exception) {
                                // Ignore player sub-error
                            }
                        }
                    }

                    // Host B: GDxFiles (PixelDrain CDN & MultiUp Mirror)
                    if (hostUrl.contains("gdxfiles.com")) {
                        val gdxDoc = app.get(
                            hostUrl,
                            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to initialRes.url)
                        ).document

                        // PixelDrain mirror resolution
                        val pxPage = gdxDoc.selectFirst("a[href*='/file/pixeldrain/']")?.attr("href")
                        if (!pxPage.isNullOrBlank()) {
                            try {
                                val pxDoc = app.get(
                                    pxPage,
                                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to hostUrl)
                                ).document
                                val pxDlUrl = pxDoc.selectFirst("a[href*='/download?']")?.attr("href")
                                if (!pxDlUrl.isNullOrBlank()) {
                                    val pxFinalRes = app.get(
                                        pxDlUrl,
                                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to pxPage),
                                        followRedirects = true
                                    )
                                    val pxId = pxFinalRes.url.substringAfterLast("/").substringBefore("?")
                                    if (pxId.isNotBlank() && pxId.length >= 4) {
                                        val streamUrl = "https://pixeldrain.com/api/filesystem/$pxId"
                                        callback.invoke(
                                            newExtractorLink(
                                                source = this.name,
                                                name = "${this.name} - PixelDrain Direct" + (if (!qualityLabel.isNullOrBlank()) " [$qualityLabel]" else ""),
                                                url = streamUrl,
                                                type = ExtractorLinkType.VIDEO
                                            ) {
                                                this.referer = "https://pixeldrain.com/"
                                                this.headers = mapOf(
                                                    "User-Agent" to USER_AGENT,
                                                    "Referer" to "https://pixeldrain.com/"
                                                )
                                                this.quality = getQualityFromName(qualityLabel)
                                            }
                                        )
                                        foundAny = true
                                    }
                                }
                            } catch (e: Exception) {
                                // Skip PixelDrain error
                            }
                        }

                        // MultiUp mirror resolution
                        val muPage = gdxDoc.selectFirst("a[href*='/file/multiup/']")?.attr("href")
                        if (!muPage.isNullOrBlank()) {
                            try {
                                val muDoc = app.get(
                                    muPage,
                                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to hostUrl)
                                ).document
                                val directMu = muDoc.selectFirst("a[href*='multiup.io/download/']")?.attr("href")
                                if (!directMu.isNullOrBlank()) {
                                    if (loadExtractor(directMu, hostUrl, subtitleCallback, callback)) {
                                        foundAny = true
                                    }
                                }
                            } catch (e: Exception) {
                                // Skip MultiUp error
                            }
                        }
                    }

                    // Host C & D: GDFlix & FilePress
                    if (hostUrl.contains("gdflix") || hostUrl.contains("filepress")) {
                        try {
                            if (loadExtractor(hostUrl, initialRes.url, subtitleCallback, callback)) {
                                foundAny = true
                            }
                        } catch (e: Exception) {
                            // Skip external extractor error
                        }
                    }
                } catch (e: Exception) {
                    // Ignore individual host resolution error
                }
            }
        } catch (e: Exception) {
            // Ignore joya9 link failure
        }
        return foundAny
    }

    private fun getQualityFromName(name: String?): Int {
        if (name == null) return Qualities.Unknown.value
        return when {
            name.contains("2160", ignoreCase = true) || name.contains("4K", ignoreCase = true) -> Qualities.P2160.value
            name.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            name.contains("720", ignoreCase = true) -> Qualities.P720.value
            name.contains("480", ignoreCase = true) -> Qualities.P480.value
            name.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}

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
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/"),
            timeout = 10
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
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/"),
            timeout = 10
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
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/"),
            timeout = 10
        ).document
        val fullHtml = document.html()

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

        val isSeries = fullHtml.contains("const isSeries =  true") ||
                fullHtml.contains("const isSeries = true") ||
                fullHtml.contains("isSeries = true") ||
                url.contains("/series") ||
                url.contains("-s") ||
                document.select(".episodes a, a[href*='episode']").isNotEmpty()

        if (isSeries) {
            val episodes = ArrayList<Episode>()

            // 1. Try parsing from JS variable rawEpisodes
            val rawEpisodesBlock = Regex("""(?:const|let|var)\s+rawEpisodes\s*=\s*(\[[\s\S]*?\]);""").find(fullHtml)?.groupValues?.get(1) ?: ""
            val epMatches = Regex("""\{\s*name:\s*"([^"]*)",\s*link:\s*"([^"]*)"(?:,\s*quality:\s*"([^"]*)")?(?:,\s*language:\s*"([^"]*)")?""").findAll(rawEpisodesBlock).toList()
            if (epMatches.isNotEmpty()) {
                var epIdx = 1
                for (m in epMatches) {
                    val epName = m.groupValues[1].trim()
                    var rawEpLink = m.groupValues[2].replace("\\/", "/").trim()
                    if (rawEpLink.isBlank() || rawEpLink.contains("youtube.com") || rawEpLink.contains("youtu.be")) continue

                    // Sanitize 24-character hex ID to avoid malformed links
                    val fidMatch = Regex("""([a-zA-Z0-9]{24})""").find(rawEpLink)
                    if (fidMatch != null) {
                        rawEpLink = "https://embed.jiofiles.pics/${fidMatch.groupValues[1]}"
                    }

                    val epNum = Regex("""(?i)(?:ep|episode|e)\s*[-:]?\s*(\d+)""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""(\d+)""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: epIdx
                    val seasonNum = Regex("""(?i)(?:s|season)\s*[-:]?\s*(\d+)""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

                    episodes.add(
                        newEpisode(rawEpLink) {
                            this.name = if (epName.isNotBlank()) epName else "Episode $epIdx"
                            this.episode = epNum
                            this.season = seasonNum
                        }
                    )
                    epIdx++
                }
            }

            // 2. Try parsing from JS variable rawLinks if rawEpisodes was empty
            if (episodes.isEmpty()) {
                val rawLinksBlock = Regex("""(?:const|let|var)\s+rawLinks\s*=\s*(\[[\s\S]*?\]);""").find(fullHtml)?.groupValues?.get(1) ?: ""
                val linkMatches = Regex("""\{\s*name:\s*"([^"]*)",\s*link:\s*"([^"]*)"(?:,\s*quality:\s*"([^"]*)")?(?:,\s*language:\s*"([^"]*)")?""").findAll(rawLinksBlock).toList()
                var linkIdx = 1
                for (m in linkMatches) {
                    val epName = m.groupValues[1].trim()
                    var rawEpLink = m.groupValues[2].replace("\\/", "/").trim()
                    if (rawEpLink.isBlank() || rawEpLink.contains("youtube.com") || rawEpLink.contains("youtu.be")) continue
                    val lower = epName.lowercase()
                    if (lower.contains("zip") || lower.contains("rar")) continue

                    // Sanitize 24-character hex ID to avoid malformed links
                    val fidMatch = Regex("""([a-zA-Z0-9]{24})""").find(rawEpLink)
                    if (fidMatch != null) {
                        rawEpLink = "https://embed.jiofiles.pics/${fidMatch.groupValues[1]}"
                    }

                    val epNum = Regex("""(?i)(?:ep|episode|e)\s*[-:]?\s*(\d+)""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""(\d+)""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: linkIdx
                    val seasonNum = Regex("""(?i)(?:s|season)\s*[-:]?\s*(\d+)""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

                    episodes.add(
                        newEpisode(rawEpLink) {
                            this.name = if (epName.isNotBlank()) epName else "Episode $linkIdx"
                            this.episode = epNum
                            this.season = seasonNum
                        }
                    )
                    linkIdx++
                }
            }

            // 3. Fallback to DOM elements if present
            if (episodes.isEmpty()) {
                val epElements = document.select(".episodes a, a.episode-card, a[href*='-episode-'], a[href*='-s'], .episode-list a")
                for ((idx, el) in epElements.withIndex()) {
                    var epHref = el.attr("href").trim()
                    if (epHref.isBlank() || epHref.startsWith("#") || epHref.contains("youtube.com") || epHref.contains("youtu.be")) continue

                    val fidMatch = Regex("""([a-zA-Z0-9]{24})""").find(epHref)
                    if (fidMatch != null) {
                        epHref = "https://embed.jiofiles.pics/${fidMatch.groupValues[1]}"
                    }

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
            }

            // 4. Default single episode pointing to movie/series page if still empty
            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode(url) {
                        this.name = "Full Stream / Play All"
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

    private fun getQualityFromName(quality: String?): Int {
        if (quality.isNullOrBlank()) return Qualities.Unknown.value
        val lower = quality.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160") -> Qualities.P2160.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private data class LinkCandidate(
        val url: String,
        val quality: String = "",
        val language: String = ""
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // STRICT FILTER 1: Never load YouTube as a streaming server
        if (data.contains("youtube.com", ignoreCase = true) || data.contains("youtu.be", ignoreCase = true)) {
            return false
        }

        var loadedAny = false
        val candidates = mutableListOf<LinkCandidate>()

        val isDirectEmbed = data.contains("jiofiles.") || data.contains("indbd.") || data.contains("seekplayer.")

        if (isDirectEmbed) {
            candidates.add(LinkCandidate(data))
        } else {
            // Fetch Movie/Series detail page
            try {
                val pageRes = app.get(
                    data,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/"),
                    timeout = 6
                ).text

                // 1. Extract from rawLinks JSON array with quality & language
                val rawLinksBlock = Regex("""(?:const|let|var)\s+rawLinks\s*=\s*(\[[\s\S]*?\]);""").find(pageRes)?.groupValues?.get(1) ?: ""
                val linkMatches = Regex("""\{\s*name:\s*"([^"]*)",\s*link:\s*"([^"]*)"(?:,\s*quality:\s*"([^"]*)")?(?:,\s*language:\s*"([^"]*)")?""").findAll(rawLinksBlock)
                for (lm in linkMatches) {
                    val rawLink = lm.groupValues[2].replace("\\/", "/").trim()
                    val lowerName = lm.groupValues[1].lowercase()
                    if (lowerName.contains("zip") || lowerName.contains("rar")) continue
                    if (rawLink.isNotBlank() && !rawLink.contains("youtube.com") && !rawLink.contains("youtu.be")) {
                        candidates.add(
                            LinkCandidate(
                                url = rawLink,
                                quality = lm.groupValues[3].trim(),
                                language = lm.groupValues[4].trim()
                            )
                        )
                    }
                }

                // 2. Extract from rawEpisodes if rawLinks was empty
                if (candidates.isEmpty()) {
                    val rawEpsBlock = Regex("""(?:const|let|var)\s+rawEpisodes\s*=\s*(\[[\s\S]*?\]);""").find(pageRes)?.groupValues?.get(1) ?: ""
                    val epMatches = Regex("""\{\s*name:\s*"([^"]*)",\s*link:\s*"([^"]*)"(?:,\s*quality:\s*"([^"]*)")?(?:,\s*language:\s*"([^"]*)")?""").findAll(rawEpsBlock)
                    for (em in epMatches) {
                        val rawLink = em.groupValues[2].replace("\\/", "/").trim()
                        if (rawLink.isNotBlank() && !rawLink.contains("youtube.com") && !rawLink.contains("youtu.be")) {
                            candidates.add(
                                LinkCandidate(
                                    url = rawLink,
                                    quality = em.groupValues[3].trim(),
                                    language = em.groupValues[4].trim()
                                )
                            )
                        }
                    }
                }

                // 3. Extract formatJioEmbed("...")
                val formatJioMatch = Regex("""formatJioEmbed\s*\(\s*"([^"]+)"\s*\)""").find(pageRes)
                if (formatJioMatch != null) {
                    val rawJio = formatJioMatch.groupValues[1].replace("\\/", "/").trim()
                    if (rawJio.isNotBlank() && !rawJio.contains("youtube")) {
                        candidates.add(LinkCandidate(url = rawJio))
                    }
                }

                // 4. Extract any embedded jiofiles URLs
                Regex("""https?:[\\/]+(?:embed\.|player\.)?jiofiles\.(?:pics|xyz)[\\/]+([a-zA-Z0-9]{24})""").findAll(pageRes).forEach {
                    candidates.add(LinkCandidate(url = "https://embed.jiofiles.pics/${it.groupValues[1]}"))
                }
            } catch (e: Exception) {
                // Ignore page fetch error
            }
        }

        val visitedUrls = mutableSetOf<String>()

        for (candidate in candidates) {
            val cleanUrl = candidate.url.replace("\\/", "/").trim()
            if (cleanUrl.isBlank()) continue

            // STRICT FILTER 2: Filter out all YouTube URLs completely
            if (cleanUrl.contains("youtube.com", ignoreCase = true) || cleanUrl.contains("youtu.be", ignoreCase = true)) {
                continue
            }

            // Sanitize 24-character hex ID (fixes malformed links like https://embed.https://jiofiles.pics/...)
            val fileIdMatch = Regex("""([a-zA-Z0-9]{24})""").find(cleanUrl)
            val resolvedCandidate = if (fileIdMatch != null) {
                "https://embed.jiofiles.pics/${fileIdMatch.groupValues[1]}"
            } else {
                fixUrl(cleanUrl)
            }

            if (visitedUrls.contains(resolvedCandidate)) continue
            visitedUrls.add(resolvedCandidate)

            val langLabel = if (candidate.language.isNotBlank()) "[${candidate.language}] " else ""
            val qualityLabel = if (candidate.quality.isNotBlank()) " ${candidate.quality}" else ""
            val qualityInt = getQualityFromName(candidate.quality)

            // CASE A: JioFiles embed
            if (resolvedCandidate.contains("jiofiles.pics") || resolvedCandidate.contains("jiofiles.xyz")) {
                val jioEmbedUrl = resolvedCandidate

                try {
                    val embedHtml = app.get(
                        jioEmbedUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$mainUrl/"
                        ),
                        timeout = 6
                    ).text

                    // Check for indbd.pages.dev embed player (Player 1 - SeekPlayer HLS)
                    val indbdMatch = Regex("""indbd\.pages\.dev/embed/([^/]+)/([^/?#"'\s]+)""").find(embedHtml)
                    if (indbdMatch != null) {
                        val domain = indbdMatch.groupValues[1]
                        val videoId = indbdMatch.groupValues[2]
                        val apiUrl = "https://indbd.pages.dev/api/info?url=$domain&id=$videoId"
                        val refererHeader = "https://$domain/"

                        try {
                            val apiRes = app.get(
                                apiUrl,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to "https://indbd.pages.dev/embed/$domain/$videoId"
                                ),
                                timeout = 6
                            ).text

                            val cfNative = Regex(""""cfNativeDirect"\s*:\s*"([^"]+)"""").find(apiRes)?.groupValues?.get(1)?.replace("\\/", "/")
                            val sourceDirect = Regex(""""sourceDirect"\s*:\s*"([^"]+)"""").find(apiRes)?.groupValues?.get(1)?.replace("\\/", "/")
                            val backupMatch = Regex(""""file"\s*:\s*"(/api/proxy\.m3u8\?[^"]+)"""").find(apiRes)

                            // 1. SeekPlayer Fast Cloud (Cloudflare CDN - Primary & Working)
                            if (!cfNative.isNullOrBlank()) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${this.name} - ${langLabel}Fast Cloud${qualityLabel}",
                                        url = cfNative,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = refererHeader
                                        this.headers = mapOf("Referer" to refererHeader, "User-Agent" to USER_AGENT)
                                        this.quality = qualityInt
                                    }
                                )
                                loadedAny = true

                                try {
                                    M3u8Helper.generateM3u8(
                                        source = this.name,
                                        streamUrl = cfNative,
                                        referer = refererHeader,
                                        quality = qualityInt,
                                        headers = mapOf("Referer" to refererHeader, "User-Agent" to USER_AGENT),
                                        name = "${this.name} - ${langLabel}Fast Cloud"
                                    ).forEach { link ->
                                        callback.invoke(link)
                                        loadedAny = true
                                    }
                                } catch (_: Exception) {}
                            }

                            // 2. Backup Cloudflare Stream (Always Available)
                            if (backupMatch != null) {
                                val backupUrl = "https://indbd.pages.dev${backupMatch.groupValues[1]}"
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${this.name} - ${langLabel}Backup Cloud${qualityLabel}",
                                        url = backupUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "https://indbd.pages.dev/"
                                        this.headers = mapOf("Referer" to "https://indbd.pages.dev/", "User-Agent" to USER_AGENT)
                                        this.quality = qualityInt
                                    }
                                )
                                loadedAny = true
                            }

                            // 3. Direct Server (FILTER OUT bare IP addresses to fix ERROR_CODE_IO_NETWORK_CONNECTION_FAILED 2001)
                            if (!sourceDirect.isNullOrBlank() && !sourceDirect.matches(Regex("""^https?://\d+\.\d+\.\d+\.\d+.*"""))) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${this.name} - ${langLabel}Direct Server${qualityLabel}",
                                        url = sourceDirect,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = refererHeader
                                        this.headers = mapOf("Referer" to refererHeader, "User-Agent" to USER_AGENT)
                                        this.quality = qualityInt
                                    }
                                )
                                loadedAny = true
                            }

                            // Subtitles extraction
                            val subMatches = Regex(""""([a-zA-Z]{2,4})"\s*:\s*"([^"]+\.vtt[^"]*)"""").findAll(apiRes)
                            for (subMatch in subMatches) {
                                val langCode = subMatch.groupValues[1].uppercase()
                                val rawSub = subMatch.groupValues[2].replace("\\/", "/")
                                val subUrl = if (rawSub.startsWith("http")) rawSub else "https://$domain$rawSub"
                                subtitleCallback.invoke(SubtitleFile(langCode, subUrl))
                            }
                        } catch (e: Exception) {
                            // Ignore indbd API error
                        }
                    }

                    // Check other players from switchPlayer function in embedHtml
                    val playerMatches = Regex("""switchPlayer\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]""").findAll(embedHtml)
                    for (pm in playerMatches) {
                        val pUrl = pm.groupValues[1].replace("\\/", "/").trim()
                        val pName = pm.groupValues[3].trim().ifEmpty { "MovieNest Server" }

                        // STRICT: Never load YouTube
                        if (pUrl.contains("youtube.com") || pUrl.contains("youtu.be")) continue

                        if (pUrl.contains(".m3u8")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} - ${langLabel}$pName${qualityLabel}",
                                    url = pUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = jioEmbedUrl
                                    this.quality = qualityInt
                                }
                            )
                            loadedAny = true
                        } else if (!pUrl.contains("indbd.pages.dev") && !pUrl.contains("xcloud.autos")) {
                            try {
                                if (loadExtractor(pUrl, jioEmbedUrl, subtitleCallback, callback)) {
                                    loadedAny = true
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    // Ignore embed page error
                }
            } else if (resolvedCandidate.contains(".m3u8")) {
                // CASE B: Direct HLS Stream
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} - ${langLabel}Direct HLS${qualityLabel}",
                        url = resolvedCandidate,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
                        this.quality = qualityInt
                    }
                )
                loadedAny = true

                try {
                    M3u8Helper.generateM3u8(
                        source = this.name,
                        streamUrl = resolvedCandidate,
                        referer = data,
                        quality = qualityInt,
                        name = "${this.name} - ${langLabel}HLS"
                    ).forEach { link ->
                        callback.invoke(link)
                        loadedAny = true
                    }
                } catch (_: Exception) {}
            } else {
                // CASE C: External player / extractor (excluding YouTube and bot-blocked xcloud)
                if (!resolvedCandidate.contains("xcloud.autos")) {
                    try {
                        if (loadExtractor(resolvedCandidate, data, subtitleCallback, callback)) {
                            loadedAny = true
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        return loadedAny
    }
}

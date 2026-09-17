package com.uchiharepo.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class CinevoodProvider : MainAPI() {
    override var mainUrl = "https://cinevood.bingo"
    override var name = "CineVood"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Cartoon
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/bollywood/" to "Bollywood Movies",
        "$mainUrl/hollywood/" to "Hollywood Movies",
        "$mainUrl/hindi-dubbed/hollywood-dubbed/" to "Hollywood (Hindi Dubbed)",
        "$mainUrl/hindi-dubbed/south-dubbed/" to "South Indian (Hindi Dubbed)",
        "$mainUrl/punjabi/" to "Punjabi Movies",
        "$mainUrl/tamil/" to "Tamil Movies",
        "$mainUrl/telugu/" to "Telugu Movies",
        "$mainUrl/malayalam/" to "Malayalam Movies",
        "$mainUrl/kannada/" to "Kannada Movies",
        "$mainUrl/bengali/" to "Bengali Movies",
        "$mainUrl/marathi/" to "Marathi Movies",
        "$mainUrl/gujarati/" to "Gujarati Movies",
        "$mainUrl/web-series/" to "Web Series",
        "$mainUrl/tv-shows/" to "TV Shows",
        "$mainUrl/web-series/netflix-web-series/" to "Netflix Series",
        "$mainUrl/web-series/amazon-web-series-webshow/" to "Amazon Prime Series",
        "$mainUrl/web-series/hotstar-web-series/" to "Hotstar Series",
        "$mainUrl/web-series/zee5-web-series/" to "Zee5 Series",
        "$mainUrl/web-series/sony-liv-web-show/" to "Sony LIV Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            "$base/page/$page/"
        }
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val items = document.select("article.latestPost, article.post, article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, items)
    }

    private fun cleanImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var clean = url.trim()
        if (clean.startsWith("data:image") || (clean.contains("cropped-") && clean.contains("icon"))) {
            return null
        }
        if (clean.startsWith("//")) {
            clean = "https:$clean"
        } else if (clean.startsWith("/")) {
            clean = "$mainUrl$clean"
        }
        return clean.replace(Regex("""-\d+x\d+\.(jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE), ".$1")
    }

    private fun extractImageUrl(element: Element?): String? {
        if (element == null) return null
        val img = if (element.tagName().equals("img", ignoreCase = true)) element else element.selectFirst("img")
        val raw = if (img != null) {
            val dSrc = img.attr("data-src").trim()
            val dLazy = img.attr("data-lazy-src").trim()
            val src = img.attr("src").trim()
            when {
                dSrc.isNotBlank() -> dSrc
                dLazy.isNotBlank() -> dLazy
                else -> src
            }
        } else null
        return cleanImageUrl(raw)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("h2.title.front-view-title a, h2.title a, h2.front-view-title a, h2 a") ?: return null
        val title = titleEl.text().trim().ifBlank { titleEl.attr("title").trim() }.ifBlank { return null }
        val href = titleEl.attr("abs:href").ifBlank { titleEl.attr("href") }.trim()
        if (href.isBlank()) return null

        if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) return null

        val poster = extractImageUrl(this)

        return if (isTvSeries(title, href, emptyList())) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encoded"
        val document = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        return document.select("article.latestPost, article.post, article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun isTvSeries(title: String, url: String, tags: List<String>): Boolean {
        val lowerTags = tags.map { it.lowercase() }
        val lowerTitle = title.lowercase()
        val lowerUrl = url.lowercase()
        val seriesKeywords = listOf("web series", "tv show", "series", "season")
        return lowerTags.any { tag -> seriesKeywords.any { tag.contains(it) } } ||
                lowerTitle.contains(Regex("""(?i)(season\s*\d+|s\d{1,2}|episode|complete)""")) ||
                lowerUrl.contains("web-series") || lowerUrl.contains("tv-shows") || lowerUrl.contains("season")
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val title = document.selectFirst("h1.title.single-title, h1.entry-title, h1")?.text()?.trim() ?: return null

        val poster = extractImageUrl(document.selectFirst("div.single_post, .featured-thumbnail, div.thecontent"))
            ?: cleanImageUrl(document.selectFirst("img.wp-post-image")?.attr("src"))

        val tags = document.select("div.thecategory a, div.post-info a").map { it.text().trim() }.filter { it.isNotBlank() }
        val plot = document.selectFirst("div.thecontent p")?.text()?.trim()?.takeIf { it.length > 25 }

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val isSeries = isTvSeries(title, url, tags)

        return if (isSeries) {
            val headings = document.select("div.thecontent h2, div.thecontent h3, div.thecontent h4, div.thecontent h5")
                .filter { it.text().contains(Regex("""(?i)(episode|ep\.?\s*\d+|E\d{2}|part)""")) }

            val episodes = if (headings.isEmpty()) {
                listOf(
                    newEpisode(url) {
                        this.name = "Watch / Download"
                        this.episode = 1
                        this.season = 1
                    }
                )
            } else {
                headings.mapIndexed { idx, el ->
                    val text = el.text().trim()
                    val epNum = Regex("""\d+""").find(text)?.value?.toIntOrNull() ?: (idx + 1)
                    val season = Regex("""(?i)season\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    newEpisode(url) {
                        this.name = text
                        this.episode = epNum
                        this.season = season
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val document = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).document
        var anyFound = false
        val processedUrls = mutableSetOf<String>()

        // 1. Embedded IFRAMES
        document.select("div.thecontent iframe").forEach { iframe ->
            val src = iframe.attr("src").trim().ifBlank { iframe.attr("data-src").trim() }
            if (src.isNotBlank() && src.startsWith("http") && !src.contains("youtube.com")) {
                if (processedUrls.add(src)) {
                    runCatching {
                        loadExtractor(src, data, subtitleCallback, callback)
                        anyFound = true
                    }
                }
            }
        }

        // 2. Button Links (OxxFile, HubCloud, FastCloud, PixelDrain, GDFlix, etc.)
        val buttons = document.select(
            "a.maxbutton-oxxfile, a.maxbutton, a.maxbutton-download, " +
            "a[href*=oxxfile], a[href*=oxi.file], a[href*=hubcloud], " +
            "a[href*=fastcloud], a[href*=gdflix], a[href*=pixeldrain]"
        )

        for (btn in buttons) {
            val href = btn.attr("href").trim()
            if (href.isBlank() || href == "#" || href.contains("telegram") || href.contains("t.me")) continue
            if (!processedUrls.add(href)) continue

            val btnText = btn.text().trim()
            val nextH6 = btn.nextElementSibling()?.takeIf { it.tagName().equals("h6", ignoreCase = true) }?.text() ?: ""
            val quality = determineQuality("$btnText $nextH6")

            if (href.contains("oxxfile") || href.contains("oxi.file")) {
                val resolved = resolveOxxFile(href)
                if (!resolved.isNullOrBlank() && processedUrls.add(resolved)) {
                    if (handleTargetServer(resolved, data, quality, subtitleCallback, callback)) {
                        anyFound = true
                    }
                }
            } else {
                if (handleTargetServer(href, data, quality, subtitleCallback, callback)) {
                    anyFound = true
                }
            }
        }

        // 3. Scan all anchor tags in content
        val allAnchors = document.select("div.thecontent a[href], .single_post a[href]")
        for (a in allAnchors) {
            val href = a.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") || href.contains("facebook.com") || href.contains("t.me") || href.contains("telegram")) continue
            if (href.contains("youtube.com") || href.contains("youtu.be")) continue
            if (!processedUrls.add(href)) continue

            val text = a.text().trim()
            val quality = determineQuality(text)

            if (handleTargetServer(href, data, quality, subtitleCallback, callback)) {
                anyFound = true
            }
        }

        return anyFound
    }

    private suspend fun handleTargetServer(
        url: String,
        refererUrl: String,
        quality: Pair<String, Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // HubCloud / FastCloud / Vifix / DriveBuzz
        if (url.contains("hubcloud") || url.contains("vifix.site") || url.contains("fastcloud") || url.contains("drivebuzz")) {
            return extractHubCloud(url, quality, subtitleCallback, callback)
        }

        // PixelDrain
        if (url.contains("pixeldrain.com")) {
            return emitPixelDrain(url, quality.first, quality.second, callback)
        }

        // GDFlix / FastDrive / DriveSeed
        if (url.contains("gdflix") || url.contains("fastdrive") || url.contains("driveseed") || url.contains("drivelinks")) {
            return extractGDFlix(url, quality, subtitleCallback, callback)
        }

        // Direct Video Files
        if (url.endsWith(".mp4") || url.endsWith(".mkv") || url.endsWith(".m4v") || url.contains(".m3u8")) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "$name - Direct (${quality.first})",
                    url = url,
                    type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = refererUrl
                    this.quality = quality.second
                }
            )
            return true
        }

        // Generic Hosters
        return try {
            loadExtractor(url, refererUrl, subtitleCallback, callback)
            true
        } catch (ignored: Exception) {
            false
        }
    }

    private suspend fun resolveOxxFile(url: String): String? {
        return runCatching {
            val response = app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
            )
            val finalUrl = response.url
            val checkKeywords = listOf(".mkv", ".mp4", "streamtape", "dood", "vidnest", "filelions", "hubcloud", "vidara", "fastcloud")
            if (checkKeywords.any { finalUrl.contains(it, ignoreCase = true) }) {
                return@runCatching finalUrl
            }

            val doc = response.document
            doc.selectFirst(
                "a#download-btn, a.btn-download, a[href*=hubcloud], a[href*=fastcloud], " +
                "a[href*=streamtape], a[href*=dood], a[href*=vidnest], a[href*=filelions], " +
                "a[href*=pixeldrain], a[href*=.mkv], a[href*=.mp4], a[href*=buzzheavier], a[href*=krakenfiles]"
            )?.attr("abs:href")?.ifBlank { null } ?: finalUrl
        }.getOrNull()
    }

    private suspend fun extractHubCloud(
        url: String,
        quality: Pair<String, Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)).document
            var extracted = parseHubCloudLinks(doc, url, quality, subtitleCallback, callback)

            if (!extracted) {
                val landingBtn = doc.selectFirst("a#download, a.btn-download, a:contains(Download), a:contains(Generate)")
                val nextUrl = landingBtn?.attr("href")?.trim()
                if (!nextUrl.isNullOrBlank() && nextUrl.startsWith("http")) {
                    val stepDoc = app.get(nextUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to url)).document
                    extracted = parseHubCloudLinks(stepDoc, nextUrl, quality, subtitleCallback, callback)
                }
            }
            extracted
        } catch (ignored: Exception) {
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
            } else if (href.contains("pixeldrain.com")) {
                if (emitPixelDrain(href, quality.first, quality.second, callback)) found = true
            } else if (href.contains("workers.dev")) {
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
            } else if (href.endsWith(".mp4") || href.endsWith(".mkv") || href.contains(".m3u8")) {
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
            } else if (href.contains("gofile.io") || href.contains("streamtape") || href.contains("vidhide") || href.contains("buzzheavier") || href.contains("krakenfiles")) {
                try {
                    loadExtractor(href, refererUrl, subtitleCallback, callback)
                    found = true
                } catch (ignored: Exception) { }
            }
        }
        return found
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
                    if (emitPixelDrain(streamHref, quality.first, quality.second, callback)) extracted = true
                } else if (streamHref.contains("hubcloud") || streamHref.contains("vifix.site") || streamHref.contains("fastcloud")) {
                    if (extractHubCloud(streamHref, quality, subtitleCallback, callback)) extracted = true
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
        } catch (ignored: Exception) {
            false
        }
    }

    private fun emitPixelDrain(
        url: String,
        qualityName: String,
        qualityValue: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fileId = Regex("""pixeldrain\.com/(?:u|api/file)/([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
        return if (!fileId.isNullOrBlank()) {
            val streamUrl = "https://pixeldrain.com/api/file/$fileId"
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
            true
        } else false
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

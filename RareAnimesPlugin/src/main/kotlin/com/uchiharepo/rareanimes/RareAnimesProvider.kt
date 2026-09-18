package com.uchiharepo.rareanimes

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class RareAnimesProvider : MainAPI() {
    override var mainUrl = "https://www.rareanimes.mov"
    override var name = "RareAnimes"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/hindi/page/" to "Latest Releases",
        "$mainUrl/hindi/category/anime-series/page/" to "Anime Series",
        "$mainUrl/hindi/category/cartoon-series/page/" to "Cartoon Series",
        "$mainUrl/hindi/category/movies/page/" to "Movies",
        "$mainUrl/hindi/category/hindi-dub/page/" to "Hindi Dubbed",
        "$mainUrl/hindi/category/hindi-sub/page/" to "Hindi Subbed",
        "$mainUrl/hindi/category/multi-audio/page/" to "Multi Audio",
        "$mainUrl/hindi/category/disney-xd/page/" to "Disney / XD",
        "$mainUrl/hindi/category/marvel-hq/page/" to "Marvel HQ",
        "$mainUrl/hindi/category/cartoon-network/page/" to "Cartoon Network",
        "$mainUrl/hindi/category/hungama-tv/page/" to "Hungama TV"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            val base = request.data.removeSuffix("page/")
            if (base.endsWith("/")) base else "$base/"
        } else {
            "${request.data}$page/"
        }
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val home = document.select("article.post, .herald-lay-b, .herald-lay-a").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun extractImageUrl(element: Element?): String? {
        if (element == null) return null
        val img = if (element.tagName() == "img") element else element.selectFirst("img") ?: return null
        val raw = (
            img.attr("srcset").split(",").lastOrNull()?.trim()?.substringBefore(" ")?.ifEmpty { null }
                ?: img.attr("data-src").ifEmpty {
                    img.attr("data-lazy-src").ifEmpty {
                        img.attr("src")
                    }
                }
        ).trim()
        if (raw.isBlank() || raw.startsWith("data:image")) return null
        val cleaned = if (raw.startsWith("//")) "https:$raw" else raw
        if (cleaned.contains("Rare-Animes", ignoreCase = true) ||
            cleaned.contains("cropped-", ignoreCase = true) ||
            cleaned.contains("favicon", ignoreCase = true)
        ) {
            return null
        }
        return cleaned.replace(Regex("""-\d+x\d+\.(jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE), ".$1")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("h2.entry-title a, .entry-title a, h2 a")
            ?: this.selectFirst("a[title]") ?: return null
        val title = titleEl.text().trim().ifEmpty { titleEl.attr("title").trim() }
        val href = titleEl.attr("href").trim()
        if (href.isBlank() || !href.startsWith("http") || href.contains("/category/") || href.contains("/tag/")) {
            return null
        }
        val posterUrl = extractImageUrl(this)
        val isMovie = href.contains("-movie-", ignoreCase = true) ||
            href.contains("/movies/", ignoreCase = true) ||
            title.contains("Movie", ignoreCase = true)

        val isCartoon = href.contains("/cartoon-series/", ignoreCase = true) ||
            title.contains("Ben 10", ignoreCase = true) ||
            title.contains("Oggy", ignoreCase = true) ||
            title.contains("Doraemon", ignoreCase = true) ||
            title.contains("Shinchan", ignoreCase = true) ||
            title.contains("Roll No 21", ignoreCase = true) ||
            title.contains("Chhota Bheem", ignoreCase = true)

        val tvType = when {
            isMovie -> TvType.AnimeMovie
            isCartoon -> TvType.Cartoon
            else -> TvType.Anime
        }

        return if (isMovie) {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val document = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        return document.select("article.post, .herald-lay-b, .herald-lay-a").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "RareAnimes"
        val poster = extractImageUrl(document.selectFirst(".post-thumbnail img, figure img, .attachment-herald-lay-f1"))
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.select(".entry-content img").mapNotNull { extractImageUrl(it) }.firstOrNull()
        val plot = document.selectFirst(".entry-content p, meta[property='og:description']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()
        val tags = document.select(".herald-tags a, .genres a, .entry-categories a").map { it.text().trim() }.distinct()
        val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(title)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        val contentEl = document.selectFirst(".entry-content, .herald-entry-content, article .entry-content")
        val contentHtml = contentEl?.html() ?: document.html()

        val episodes = parseEpisodes(document, contentHtml, contentEl, url, poster)

        val isMovie = (episodes.isEmpty() && document.select("a[href*='codedew.com'], a[href*='hubcloud'], a[href*='drive.google'], a[href*='pixeldrain']").isNotEmpty()) ||
            url.contains("-movie-", ignoreCase = true) ||
            title.contains("Movie", ignoreCase = true)

        if (isMovie && episodes.isEmpty()) {
            val movieLinks = (contentEl ?: document).select("a[href*='codedew.com'], a[href*='hubcloud'], a[href*='drive.google'], a[href*='pixeldrain'], a[href*='mega.nz'], a[href*='streamwish'], a[href*='filepress'], a[href*='droplink']").mapNotNull { a ->
                val link = a.attr("href").trim()
                val label = a.text().trim().ifEmpty { a.parent()?.text()?.trim() ?: "Stream" }
                if (link.isNotBlank() && !link.contains("/category/") && !link.contains("/tag/")) ServerLink(label, link) else null
            }.distinctBy { it.url }.sortedBy { serverPriority(it.name) }

            val movieData = RareAnimesEpisodeData(url, movieLinks).toJson()
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, movieData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }

        val isCartoon = url.contains("/cartoon-series/", ignoreCase = true) ||
            title.contains("Ben 10", ignoreCase = true) ||
            title.contains("Oggy", ignoreCase = true) ||
            title.contains("Doraemon", ignoreCase = true) ||
            title.contains("Shinchan", ignoreCase = true) ||
            title.contains("Roll No 21", ignoreCase = true) ||
            title.contains("Chhota Bheem", ignoreCase = true)

        val tvType = if (isCartoon) TvType.Cartoon else TvType.Anime

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    private fun serverPriority(name: String): Int {
        val s = name.lowercase()
        return when {
            s.contains("streambeta") || s.contains("stream-beta") -> 0
            s.contains("pixeldrain") || s.contains("pixel") -> 1
            s.contains("fastcdn") || s.contains("fastcloud") || s.contains("hubcloud") -> 2
            s.contains("gdrive") || s.contains("google") -> 3
            s.contains("multiquality") || s.contains("multquality") || s.contains("backup hls") -> 4
            s.contains("mega") -> 5
            s.contains("dlbeta") -> 6
            s.contains("streamwish") || s.contains("vidhide") || s.contains("streamtape") -> 7
            else -> 10
        }
    }

    private suspend fun parseEpisodes(
        document: Document,
        contentHtml: String,
        contentEl: Element?,
        postUrl: String,
        fallbackThumb: String?
    ): List<Episode> {
        val episodes = ArrayList<Episode>()
        val root = contentEl ?: document

        // 1. Check for intermediate archive links
        val archiveLinks = root.select("a[href*='/archives/'], a[href*='store.animetoonhindi.com'], a[href*='animetoonhindi.com']").mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http") && (href.contains("archives") || href.contains("store."))) href else null
        }.distinct()

        if (archiveLinks.isNotEmpty()) {
            for (archUrl in archiveLinks) {
                try {
                    val archDoc = app.get(archUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to postUrl)).document
                    val archLinks = archDoc.select("a[href*='codedew.com'], a[href*='hubcloud'], a[href*='pixeldrain'], a[href*='drive.google'], a[href*='mega.nz'], a[href*='streamwish'], a[href*='filepress'], a[href*='droplink']")
                    for (a in archLinks) {
                        val sUrl = a.attr("href").trim()
                        val text = a.text().trim()
                        val parentText = a.parent()?.text()?.trim() ?: ""
                        val fullText = "$text $parentText"
                        if (sUrl.isBlank()) continue

                        val epMatch = Regex("""(?:Episode|Ep\.?|E)\s*0*(\d+)""", RegexOption.IGNORE_CASE).find(fullText)
                        val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: (episodes.size + 1)
                        val epTitle = text.ifEmpty { "Episode $epNum" }

                        val existing = episodes.find { it.episode == epNum }
                        if (existing != null) {
                            try {
                                val curData = parseJson<RareAnimesEpisodeData>(existing.data)
                                val updatedServers = (curData.servers + ServerLink(epTitle, sUrl)).distinctBy { it.url }
                                existing.data = RareAnimesEpisodeData(archUrl, updatedServers.sortedBy { serverPriority(it.name) }).toJson()
                            } catch (e: Exception) { }
                        } else {
                            val epData = RareAnimesEpisodeData(archUrl, listOf(ServerLink(epTitle, sUrl))).toJson()
                            episodes.add(
                                newEpisode(epData) {
                                    this.name = epTitle
                                    this.episode = epNum
                                    this.posterUrl = fallbackThumb
                                }
                            )
                        }
                    }
                } catch (e: Exception) { }
            }
            if (episodes.isNotEmpty()) {
                return episodes.sortedBy { it.episode ?: 0 }
            }
        }

        // 2. Standard HR sections parsing
        val sections = contentHtml.split(Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE))

        for (section in sections) {
            val epMatch = Regex(
                """(?:Episode|Ep\.?)\s*0*(\d+)(?:\s*[-–:]\s*([^<\n]+))?""",
                RegexOption.IGNORE_CASE
            ).find(section) ?: continue

            val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
            val rawEpTitle = epMatch.groupValues.getOrNull(2)?.trim()?.replace(Regex("""<[^>]+>"""), "")?.trim()

            val linkMatches = Regex(
                """<a\s+[^>]*href=["'](https?://(?:codedew\.com|hubcloud|pixeldrain|drive\.google|mega\.nz|streamwish|filepress)[^"']+)["'][^>]*>([\s\S]*?)</a>""",
                RegexOption.IGNORE_CASE
            ).findAll(section)

            val servers = mutableListOf<ServerLink>()
            for (m in linkMatches) {
                val link = m.groupValues[1].trim()
                val label = m.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
                if (link.isNotBlank()) {
                    servers.add(ServerLink(label.ifEmpty { "Server" }, link))
                }
            }

            if (servers.isNotEmpty()) {
                val epTitle = if (!rawEpTitle.isNullOrBlank()) {
                    "Episode $epNum - $rawEpTitle"
                } else {
                    "Episode $epNum"
                }
                val sortedServers = servers.sortedBy { serverPriority(it.name) }
                val epData = RareAnimesEpisodeData(postUrl, sortedServers).toJson()

                episodes.add(
                    newEpisode(epData) {
                        this.name = epTitle
                        this.episode = epNum
                        this.posterUrl = fallbackThumb
                    }
                )
            }
        }

        // 3. Fallback: Parse direct episode links
        if (episodes.isEmpty()) {
            val allServerLinks = root.select("a[href*='codedew.com'], a[href*='hubcloud'], a[href*='pixeldrain'], a[href*='drive.google'], a[href*='mega.nz'], a[href*='streamwish'], a[href*='filepress']")
            for ((idx, a) in allServerLinks.withIndex()) {
                val link = a.attr("href").trim()
                val text = a.text().trim()
                val parentText = a.parent()?.text()?.trim() ?: ""
                val fullText = "$text $parentText"
                if (link.isBlank() || link.contains("/category/") || link.contains("/tag/") || link == mainUrl) continue

                val epMatch = Regex("""(?:Episode|Ep\.?|E)\s*0*(\d+)""", RegexOption.IGNORE_CASE).find(fullText)
                val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                val label = text.ifEmpty { "Episode $epNum" }

                val existing = episodes.find { it.episode == epNum }
                if (existing != null) {
                    try {
                        val curData = parseJson<RareAnimesEpisodeData>(existing.data)
                        val updatedServers = (curData.servers + ServerLink(label, link)).distinctBy { it.url }
                        existing.data = RareAnimesEpisodeData(postUrl, updatedServers.sortedBy { serverPriority(it.name) }).toJson()
                    } catch (e: Exception) { }
                } else {
                    val epData = RareAnimesEpisodeData(postUrl, listOf(ServerLink(label, link))).toJson()
                    episodes.add(
                        newEpisode(epData) {
                            this.name = label
                            this.episode = epNum
                            this.posterUrl = fallbackThumb
                        }
                    )
                }
            }
        }

        return episodes.distinctBy { it.episode }.sortedBy { it.episode ?: 0 }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            parseJson<RareAnimesEpisodeData>(data)
        } catch (e: Exception) {
            null
        }

        val rawServers = epData?.servers ?: listOf(ServerLink("Direct", data))
        val servers = rawServers.sortedBy { serverPriority(it.name) }
        val postUrl = epData?.postUrl ?: "$mainUrl/"
        var loadedAny = false

        for (server in servers) {
            try {
                val sName = server.name
                val sUrl = server.url
                when {
                    sName.contains("StreamBeta", ignoreCase = true) || sUrl.contains("codedew.com") -> {
                        if (extractCodedewAndStreamBeta(sUrl, postUrl, subtitleCallback, callback)) loadedAny = true
                    }
                    sName.contains("Mega", ignoreCase = true) || sUrl.contains("mega.nz") -> {
                        if (extractMega(sUrl, postUrl, subtitleCallback, callback)) loadedAny = true
                    }
                    sName.contains("DLBeta", ignoreCase = true) -> {
                        if (extractDLBeta(sUrl, postUrl, callback)) loadedAny = true
                    }
                    sName.contains("MultiQuality", ignoreCase = true) || sName.contains("MultQuality", ignoreCase = true) || sName.contains("Backup HLS", ignoreCase = true) -> {
                        if (extractMultiQuality(sUrl, postUrl, callback)) loadedAny = true
                    }
                    sUrl.contains("pixeldrain.com") || sUrl.contains("pixeldra.in") -> {
                        if (extractPixelDrainDirect(sUrl, sName, callback)) loadedAny = true
                    }
                    sUrl.contains("hubcloud") || sUrl.contains("fastcloud") -> {
                        if (extractHubCloud(sUrl, postUrl, subtitleCallback, callback)) loadedAny = true
                    }
                    else -> {
                        if (extractCodedewAndStreamBeta(sUrl, postUrl, subtitleCallback, callback)) {
                            loadedAny = true
                        } else if (extractMultiQuality(sUrl, postUrl, callback)) {
                            loadedAny = true
                        } else if (extractMega(sUrl, postUrl, subtitleCallback, callback)) {
                            loadedAny = true
                        } else if (loadExtractor(sUrl, postUrl, subtitleCallback, callback)) {
                            loadedAny = true
                        }
                    }
                }
            } catch (e: Exception) { }
        }

        return loadedAny
    }

    private suspend fun extractCodedewAndStreamBeta(
        zipperUrl: String,
        postUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var success = false
        val streamBetaPage = app.get(
            zipperUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to postUrl
            )
        )
        var html = streamBetaPage.text

        if (!html.contains("playerSources") && (html.contains("ad_done=1") || html.contains("verification"))) {
            val targetUrl = if (zipperUrl.contains("ad_done=1")) zipperUrl else if (zipperUrl.contains("?")) "$zipperUrl&ad_done=1" else "$zipperUrl?ad_done=1"
            try {
                html = app.get(
                    targetUrl,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://codedew.com/")
                ).text
            } catch (e: Exception) { }
        }

        val iframeMatch = Regex("""https?://argon\.razorshell\.space/embed/[A-Za-z0-9]+""").find(html)
        if (iframeMatch != null) {
            if (extractMultiQuality(iframeMatch.value, "https://codedew.com/", callback)) {
                success = true
            }
        }

        val otherIframes = Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html)
        for (ifm in otherIframes) {
            val src = ifm.groupValues[1].trim()
            if (src.contains("argon.razorshell.space")) continue
            if (src.contains("mega.nz")) {
                if (loadExtractor(src, zipperUrl, subtitleCallback, callback)) success = true
            } else {
                try {
                    if (loadExtractor(src, zipperUrl, subtitleCallback, callback)) success = true
                } catch (e: Exception) { }
            }
        }

        val jsonMatch = Regex("""let\s+playerSources\s*=\s*(\[[^;]+\]);""").find(html)
            ?: Regex("""var\s+playerSources\s*=\s*(\[[^;]+\]);""").find(html)
            ?: Regex("""sources\s*:\s*(\[[^;\]]+\])""").find(html)

        if (jsonMatch != null) {
            val sources = try {
                parseJson<List<StreamBetaSource>>(jsonMatch.groupValues[1])
            } catch (e: Exception) {
                null
            }

            if (sources != null) {
                for (src in sources) {
                    val sourceName = src.name ?: "StreamBeta"
                    val rawStream = src.streamUrl
                    val rawDirect = src.url

                    val payloadStream = decodeWorkerPayload(rawStream)
                    val payloadDirect = decodeWorkerPayload(rawDirect)

                    val resolvedTarget = payloadStream ?: payloadDirect ?: rawDirect ?: rawStream ?: continue

                    if (resolvedTarget.contains("googleusercontent.com", ignoreCase = true) || resolvedTarget.contains("drive.google.com", ignoreCase = true)) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "$name - Google Drive ($sourceName)",
                                url = resolvedTarget,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://drive.google.com/"
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                                this.quality = Qualities.P1080.value
                            }
                        )
                        success = true
                    } else if (resolvedTarget.contains("pixeldra.in", ignoreCase = true) || resolvedTarget.contains("pixeldrain.com", ignoreCase = true)) {
                        val fileId = resolvedTarget.substringAfter("/u/").substringAfter("/file/").substringBefore("?").substringBefore("/")
                        if (fileId.isNotBlank()) {
                            val directDownload = "https://pixeldrain.com/api/file/$fileId?download"
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "$name - PixelDrain ($sourceName)",
                                    url = directDownload,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://pixeldrain.com/"
                                    this.headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to "https://pixeldrain.com/"
                                    )
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            loadExtractor("https://pixeldrain.com/u/$fileId", "https://pixeldrain.com/", subtitleCallback, callback)
                            success = true
                        }
                    } else if (resolvedTarget.contains("r2.dev", ignoreCase = true) || resolvedTarget.contains("cloudflarestorage.com", ignoreCase = true) || resolvedTarget.contains("workers.dev", ignoreCase = true)) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "$name - Fast Cloud ($sourceName)",
                                url = resolvedTarget,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = ""
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                                this.quality = Qualities.P1080.value
                            }
                        )
                        success = true
                    } else if (resolvedTarget.contains(".mp4", ignoreCase = true) || resolvedTarget.contains(".mkv", ignoreCase = true)) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "$name - Direct Video ($sourceName)",
                                url = resolvedTarget,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = ""
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                                this.quality = Qualities.P1080.value
                            }
                        )
                        success = true
                    } else if (resolvedTarget.contains(".m3u8", ignoreCase = true)) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "$name - HLS Stream ($sourceName)",
                                url = resolvedTarget,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = ""
                                this.quality = Qualities.P1080.value
                            }
                        )
                        try {
                            M3u8Helper.generateM3u8(
                                source = this.name,
                                streamUrl = resolvedTarget,
                                referer = "",
                                quality = Qualities.P1080.value,
                                name = "$name - HLS Stream ($sourceName)"
                            ).forEach { link ->
                                callback.invoke(link)
                            }
                        } catch (t: Throwable) { }
                        success = true
                    } else if (loadExtractor(resolvedTarget, postUrl, subtitleCallback, callback)) {
                        success = true
                    }
                }
            }
        }

        val megaMatch = Regex("""https?://mega\.nz/(?:file|embed)/[^\s"'<>]+""").find(html)
        if (megaMatch != null) {
            if (loadExtractor(megaMatch.value, postUrl, subtitleCallback, callback)) {
                success = true
            }
        }

        return success
    }

    private fun extractPixelDrainDirect(url: String, sourceName: String, callback: (ExtractorLink) -> Unit): Boolean {
        val fileId = url.substringAfter("/u/").substringAfter("/file/").substringBefore("?").substringBefore("/")
        if (fileId.isNotBlank()) {
            val directDownload = "https://pixeldrain.com/api/file/$fileId?download"
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "$name - PixelDrain ($sourceName)",
                    url = directDownload,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://pixeldrain.com/"
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://pixeldrain.com/"
                    )
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }
        return false
    }

    private suspend fun extractHubCloud(
        url: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to refererUrl)).document
            var found = false
            for (a in doc.select("a[href]")) {
                val href = a.attr("href").trim()
                if (href.contains("pixeldrain.com")) {
                    if (extractPixelDrainDirect(href, "HubCloud", callback)) found = true
                } else if (href.contains("r2.dev") || href.contains("cloudflare") || href.contains("fastcloud")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$name - FastCloud Direct (1080p)",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                    found = true
                } else {
                    try {
                        if (loadExtractor(href, url, subtitleCallback, callback)) found = true
                    } catch (e: Exception) { }
                }
            }
            found
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractMega(
        zipperUrl: String,
        postUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = if (zipperUrl.contains("ad_done=1")) zipperUrl else if (zipperUrl.contains("?")) "$zipperUrl&ad_done=1" else "$zipperUrl?ad_done=1"
        val response = app.get(
            targetUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to postUrl
            )
        )
        val megaMatch = Regex("""https?://mega\.nz/(?:file|embed)/[^\s"'<>]+""").find(response.text) ?: return false
        val megaUrl = megaMatch.value
        return loadExtractor(megaUrl, postUrl, subtitleCallback, callback)
    }

    private suspend fun extractDLBeta(
        zipperUrl: String,
        postUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = if (zipperUrl.contains("ad_done=1")) zipperUrl else if (zipperUrl.contains("?")) "$zipperUrl&ad_done=1" else "$zipperUrl?ad_done=1"
        val response = app.get(
            targetUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to postUrl
            )
        )
        val html = response.text
        val videoUrl = Regex("""https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)""").find(html)?.value ?: return false
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "$name - DLBeta",
                url = videoUrl,
                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = ""
                this.headers = mapOf("User-Agent" to USER_AGENT)
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }

    private suspend fun extractMultiQuality(
        embedOrZipperUrl: String,
        postUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var embedUrl = embedOrZipperUrl
            if (!embedUrl.contains("argon.razorshell.space/embed/")) {
                val response = app.get(
                    embedOrZipperUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to postUrl
                    )
                )
                val html = response.text
                embedUrl = Regex("""https?://argon\.razorshell\.space/embed/[A-Za-z0-9]+""").find(html)?.value ?: return false
            }

            val embedRes = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://codedew.com/"
                )
            )
            val embedHtml = embedRes.text
            val juicyMatch = Regex("""_juicycodes\((["'\s\S]*?)\);""").find(embedHtml) ?: return false
            val encodedArg = juicyMatch.groupValues[1].replace("\"", "").replace("'", "").replace("+", "")
                .replace("\n", "").replace("\r", "").replace(" ", "")

            val decodedConfigStr = decodeJuicyCodes(encodedArg) ?: return false
            val m3u8Match = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(decodedConfigStr) ?: return false
            val m3u8Url = m3u8Match.value

            val customHeaders = mapOf(
                "Referer" to "https://argon.razorshell.space/",
                "Origin" to "https://argon.razorshell.space",
                "User-Agent" to USER_AGENT
            )

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "$name - MultiQuality [Backup HLS Master]",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://argon.razorshell.space/"
                    this.headers = customHeaders
                    this.quality = Qualities.P1080.value
                }
            )

            try {
                M3u8Helper.generateM3u8(
                    source = this.name,
                    streamUrl = m3u8Url,
                    referer = "https://argon.razorshell.space/",
                    quality = Qualities.P1080.value,
                    headers = customHeaders,
                    name = "$name - MultiQuality HLS"
                ).forEach { link ->
                    callback.invoke(link)
                }
            } catch (t: Throwable) { }

            true
        } catch (e: Exception) {
            false
        }
    }

    private fun decodeWorkerPayload(rawUrl: String?): String? {
        if (rawUrl == null) return null
        return try {
            val match = Regex("""workers\.dev/([A-Za-z0-9+/=_-]+)""").find(rawUrl) ?: return null
            var b64 = match.groupValues[1].replace('-', '+').replace('_', '/')
            val pad = b64.length % 4
            if (pad != 0) b64 += "=".repeat(4 - pad)
            val jsonStr = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            val jsonPayload = parseJson<WorkerPayload>(jsonStr)
            jsonPayload.url
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeJuicyCodes(encoded: String): String? {
        return try {
            if (encoded.length <= 3) return null
            val saltPart = encoded.takeLast(3)
            val dataPart = encoded.dropLast(3).replace('_', '+').replace('-', '/')
            val pad = dataPart.length % 4
            val paddedData = if (pad != 0) dataPart + "=".repeat(4 - pad) else dataPart
            val raw = String(Base64.decode(paddedData, Base64.DEFAULT), Charsets.ISO_8859_1)
            val symbols = charArrayOf('`', '%', '-', '+', '*', '$', '!', '_', '^', '=')
            val symMap = symbols.mapIndexed { idx, c -> c to idx.toString() }.toMap()
            val digits = buildString {
                for (c in raw) {
                    symMap[c]?.let { append(it) }
                }
            }
            val salt = saltPart.map { (it.code - 100).toString() }.joinToString("").toIntOrNull() ?: 0
            val sb = StringBuilder()
            var i = 0
            while (i + 4 <= digits.length) {
                val chunk = digits.substring(i, i + 4)
                val num = chunk.toIntOrNull()
                if (num != null) {
                    val charCode = (num % 1000) - salt
                    sb.append(charCode.toChar())
                }
                i += 4
            }
            sb.toString().replace("\\/", "/")
        } catch (e: Exception) {
            null
        }
    }

    data class RareAnimesEpisodeData(
        @JsonProperty("postUrl") val postUrl: String,
        @JsonProperty("servers") val servers: List<ServerLink>
    )

    data class ServerLink(
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String
    )

    data class StreamBetaSource(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("stream_url") val streamUrl: String? = null
    )

    data class WorkerPayload(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("filename") val filename: String? = null
    )
}

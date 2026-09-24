package com.uchiharepo.animehd

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URLEncoder

class AnimeHDProvider : MainAPI() {

    // Provider Information
    override var name = "AnimeHD"
    override var mainUrl = "https://animahd.com"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val DEST_ORIGIN =
            "https://youranimewatchingdestination.animahd.online"
    }

    // Standard high-compatibility browser headers to bypass Cloudflare / Referer locks
    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "https://animahd.com/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/action/" to "Action Anime",
        "$mainUrl/category/adventure/" to "Adventure Anime",
        "$mainUrl/category/comedy/" to "Comedy Anime",
        "$mainUrl/category/fantasy/" to "Fantasy Anime",
        "$mainUrl/category/isekai/" to "Isekai Series",
        "$mainUrl/category/sci-fi/" to "Sci-Fi Anime",
        "$mainUrl/category/romance/" to "Romance Anime",
        "$mainUrl/category/shounen/" to "Shounen Series",
        "$mainUrl/category/supernatural/" to "Supernatural Anime"
    )

    private fun resolveCleanUrl(url: String): String {
        if (url.contains("p=")) {
            try {
                val b64 = url.substringAfter("p=").substringBefore("&").replace("&#038;", "&")
                val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                if (decoded.startsWith("http")) return decoded
            } catch (e: Exception) {}
        }
        return if (url.startsWith("http")) url else "$mainUrl$url"
    }

    private fun extractPoster(card: org.jsoup.nodes.Element): String? {
        val styleAttr = card.selectFirst(".animahd-poster, [style*='background-image']")?.attr("style") ?: card.attr("style")
        if (styleAttr.isNotBlank()) {
            val bgUrl = Regex("""url\(['"]?([^'"\)]+)['"]?\)""").find(styleAttr)?.groupValues?.get(1)
            if (!bgUrl.isNullOrBlank()) return bgUrl
        }
        return card.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { null }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val items = mutableListOf<SearchResponse>()
        try {
            val html = app.get(url, headers = defaultHeaders).text
            val doc = Jsoup.parse(html)
            val cards = doc.select(".animahd-card, article")

            for (card in cards) {
                val aTag = if (card.tagName() == "a") card else card.selectFirst("a") ?: continue
                val rawLink = aTag.attr("href").ifBlank { card.attr("href") }
                if (rawLink.isBlank()) continue

                val title = aTag.text().ifBlank { card.selectFirst("h2, h3")?.text() ?: "Unknown" }.trim()
                val poster = extractPoster(card)
                val cleanLink = resolveCleanUrl(rawLink)

                items.add(newTvSeriesSearchResponse(title, cleanLink, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        } catch (e: Exception) {}
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/?s=$encodedQuery"
            val html = app.get(url, headers = defaultHeaders).text
            val doc = Jsoup.parse(html)
            val cards = doc.select("article, .animahd-card")

            for (card in cards) {
                val aTag = card.selectFirst(".article__title a, .entry-title a, a.animahd-card, a") ?: continue
                val rawLink = aTag.attr("href").ifBlank { card.attr("href") }
                if (rawLink.isBlank()) continue

                val title = aTag.text().ifBlank { card.selectFirst("h2, h3")?.text() ?: "Unknown" }.trim()
                val poster = extractPoster(card)
                val cleanLink = resolveCleanUrl(rawLink)

                results.add(newTvSeriesSearchResponse(title, cleanLink, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        } catch (e: Exception) {}
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)

        val titleRaw = doc.selectFirst("h1.entry-title, .ff-title, title")?.text() ?: "Anime Title"
        val cleanTitle = titleRaw.replace("Watch Online All Episodes HD", "", ignoreCase = true)
            .replace("- A Universe Of Anime", "", ignoreCase = true)
            .trim()

        val plot = doc.selectFirst(".entry-content p:not(:empty), .ff-synopsis")?.text()?.ifBlank { null }
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("meta[name='description']")?.attr("content")?.ifBlank { null }

        val poster = doc.selectFirst(".animahd-poster, [style*='background-image']")?.attr("style")?.let {
            Regex("""url\(['"]?([^'"\)]+)['"]?\)""").find(it)?.groupValues?.get(1)
        } ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
          ?: doc.selectFirst(".animahd-poster img, .ff-poster-wrap img, .entry-content img[src*='upload']")?.let {
              it.attr("data-src").ifBlank { it.attr("src") }
          }

        val episodesList = mutableListOf<Episode>()
        val epElements = doc.select(".app-ep-row-item, a:has(.gdrive-ep-meta), a[href*='player/']")

        var epCounter = 1
        for (el in epElements) {
            val rawHref = el.attr("href")
            val seasonName = el.attr("data-season").ifBlank { "Season 1" }
            var seasonNum = Regex("""\d+""").find(seasonName)?.value?.toIntOrNull() ?: 1

            var fileId: String? = el.selectFirst(".gdrive-ep-meta")?.attr("data-fileid")?.ifBlank { null }
                ?: el.attr("data-fileid").ifBlank { null }

            if (fileId.isNullOrBlank() && rawHref.contains("p=")) {
                try {
                    val b64 = rawHref.substringAfter("p=").substringBefore("&")
                    val decoded = String(Base64.decode(b64.replace("&#038;", "&"), Base64.DEFAULT), Charsets.UTF_8)
                    fileId = Regex("""file_id=([a-zA-Z0-9_-]+)""").find(decoded)?.groupValues?.get(1)
                } catch (e: Exception) {}
            }

            if (fileId.isNullOrBlank()) {
                fileId = Regex("""file_id=([a-zA-Z0-9_-]+)""").find(rawHref)?.groupValues?.get(1)
            }

            if (fileId.isNullOrBlank()) continue

            val id = fileId
            val rawEpText = el.selectFirst(".gdrive-ep-meta div:first-child, .ff-ep-row-title")?.text()?.trim()
                ?: "Episode $epCounter"

            // Parse Season and Episode numbers if embedded like S01E01
            val sMatch = Regex("""(?i)s(\d+)e(\d+)""").find(rawEpText)
            val epNum = if (sMatch != null) {
                seasonNum = sMatch.groupValues[1].toIntOrNull() ?: seasonNum
                sMatch.groupValues[2].toIntOrNull() ?: epCounter
            } else {
                Regex("""(?i)(?:E|Episode)\s*(\d+)""").find(rawEpText)?.groupValues?.get(1)?.toIntOrNull() ?: epCounter
            }

            // Clean episode display title
            val cleanEpName = rawEpText.replace(Regex("""(?i)^\[ANIMAHD\.COM\]\s*"""), "")
                .replace(Regex("""(?i)\.(mkv|mp4)$"""), "")
                .trim()
                .ifBlank { "Episode $epCounter" }

            val epThumb = "https://drive.google.com/thumbnail?id=$id&sz=w400"

            episodesList.add(newEpisode("$mainUrl/player/?file_id=$id") {
                this.name = cleanEpName
                this.season = seasonNum
                this.episode = epNum
                this.posterUrl = epThumb
            })
            epCounter++
        }

        return if (episodesList.isNotEmpty()) {
            newTvSeriesLoadResponse(cleanTitle, url, TvType.Anime, episodesList) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(cleanTitle, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
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
        val fileId = when {
            data.contains("file_id=") -> data.substringAfter("file_id=").substringBefore("&")
            data.contains("/d/") -> data.substringAfter("/d/").substringBefore("/")
            data.matches(Regex("[a-zA-Z0-9_-]{25,}")) -> data
            else -> {
                val pageHtml = app.get(data, headers = defaultHeaders).text
                Regex("""data-fileid=['"]([^'"]+)['"]|file_id=([a-zA-Z0-9_-]+)""")
                    .find(pageHtml)?.groupValues?.drop(1)?.firstOrNull { !it.isNullOrBlank() } ?: ""
            }
        }

        if (fileId.isBlank()) return false
        var foundLink = false

        val workerHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$DEST_ORIGIN/",
            "Accept" to "*/*"
        )

        // =========================================================================
        // SERVER 1: Primary Official AnimaHD High-Speed Worker Stream
        // Solves 3003 by ensuring .mkv container extension is detected by ExoPlayer
        // Solves 2004 by using live token session and authentic destination headers
        // =========================================================================
        var destHtml = ""
        try {
            val destUrl = "$DEST_ORIGIN/?id=$fileId"
            destHtml = app.get(
                destUrl,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT
                )
            ).text

            val srcMatch = Regex("""<source[^>]+src=['"]([^'"]+)['"]""").find(destHtml)?.groupValues?.get(1)
            if (!srcMatch.isNullOrBlank() && !srcMatch.contains("error")) {
                // Fix Container Unsupported (3003): The upstream streams are MKV (Matroska) containers.
                // Replace erroneous &ext=.mp4 with &ext=.mkv and append anchor tag so ExoPlayer loads MatroskaExtractor.
                val containerFixedUrl = srcMatch.replace("&ext=.mp4", "&ext=.mkv").let { url ->
                    if (!url.contains(".mkv")) "$url#video.mkv" else url
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name · High-Speed HD Stream (1080p MKV)",
                        url = containerFixedUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$DEST_ORIGIN/"
                        this.headers = workerHeaders
                        this.quality = Qualities.P1080.value
                    }
                )
                foundLink = true
            }
        } catch (e: Exception) {}

        // =========================================================================
        // SERVER 2: Google Drive Direct Download (Validated to prevent 2004 / 3003)
        // Checks virus scan confirmation and verifies file is NOT quota-exceeded.
        // =========================================================================
        try {
            val driveUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&authuser=0"
            val initialRes = app.get(
                driveUrl,
                headers = mapOf("User-Agent" to USER_AGENT)
            )
            val cookie = initialRes.headers["set-cookie"]?.split(";")?.firstOrNull() ?: ""
            val body = initialRes.text

            // Only proceed if file is NOT quota-limited or removed
            val isQuotaExceeded = body.contains("Quota exceeded", ignoreCase = true) ||
                    body.contains("Too many users", ignoreCase = true) ||
                    body.contains("Error 404", ignoreCase = true)

            if (!isQuotaExceeded) {
                val uuid = Regex("""name=["']uuid["']\s+value=["']([^"']+)["']""").find(body)?.groupValues?.get(1)
                val confirm = Regex("""name=["']confirm["']\s+value=["']([^"']+)["']""").find(body)?.groupValues?.get(1) ?: "t"

                val confirmedDlUrl = if (!uuid.isNullOrBlank()) {
                    "https://drive.usercontent.google.com/download?id=$fileId&export=download&authuser=0&confirm=$confirm&uuid=$uuid#video.mkv"
                } else {
                    "https://drive.usercontent.google.com/download?id=$fileId&export=download&authuser=0&confirm=$confirm#video.mkv"
                }

                val driveHeaders = mutableMapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://drive.google.com/"
                )
                if (cookie.isNotBlank()) {
                    driveHeaders["Cookie"] = cookie
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name · Google Drive Direct [High-Speed MKV]",
                        url = confirmedDlUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://drive.google.com/"
                        this.headers = driveHeaders
                        this.quality = Qualities.P1080.value
                    }
                )
                foundLink = true
            }
        } catch (e: Exception) {}

        // =========================================================================
        // SERVER 3: Dedicated Proxy Mirror Download Stream
        // =========================================================================
        if (destHtml.isNotBlank()) {
            try {
                val proxyDl = Regex("""proxyDownloadUrl\s*=\s*['"]([^'"]+)['"]""").find(destHtml)?.groupValues?.get(1)
                if (!proxyDl.isNullOrBlank()) {
                    val fixedProxyUrl = proxyDl.replace("&ext=.mp4", "&ext=.mkv").let {
                        if (!it.contains(".mkv")) "$it#video.mkv" else it
                    }
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name · Fast Mirror Download (1080p)",
                            url = fixedProxyUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$DEST_ORIGIN/"
                            this.headers = workerHeaders
                            this.quality = Qualities.P1080.value
                        }
                    )
                    foundLink = true
                }
            } catch (e: Exception) {}

            // =====================================================================
            // SERVER 4: Fallback Alternative Player Gateway
            // =====================================================================
            try {
                val fallbackQuery = Regex("""href=['"]([^'"]*(?:fallback=1|\?eid=[^'"]+))['"]""").find(destHtml)?.groupValues?.get(1)
                if (!fallbackQuery.isNullOrBlank()) {
                    val fullFbUrl = if (fallbackQuery.startsWith("http")) fallbackQuery else "$DEST_ORIGIN/$fallbackQuery"
                    val fbHtml = app.get(fullFbUrl, headers = mapOf("Referer" to "$DEST_ORIGIN/", "User-Agent" to USER_AGENT)).text
                    val fbDl = Regex("""id=['"]dl-btn-fallback['"][^>]*href=['"]([^'"]+)['"]""").find(fbHtml)?.groupValues?.get(1)
                    if (!fbDl.isNullOrBlank()) {
                        val fixedFbUrl = fbDl.replace("&ext=.mp4", "&ext=.mkv").let {
                            if (!it.contains(".mkv")) "$it#video.mkv" else it
                        }
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name · Alternate Player Stream (1080p)",
                                url = fixedFbUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$DEST_ORIGIN/"
                                this.headers = workerHeaders
                                this.quality = Qualities.P1080.value
                            }
                        )
                        foundLink = true
                    }
                }
            } catch (e: Exception) {}
        }

        // =========================================================================
        // SERVER 5: Universal Google Drive Embed Fallback
        // =========================================================================
        try {
            loadExtractor("https://drive.google.com/file/d/$fileId/preview", subtitleCallback, callback)
        } catch (e: Exception) {}

        return foundLink
    }
}

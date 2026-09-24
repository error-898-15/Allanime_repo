package com.uchiharepo.animehd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Base64

class AnimeHDProvider : MainAPI() {
    override var name = "AnimeHD"
    override var mainUrl = "https://animahd.com"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "https://animahd.com/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "" to "Trending & Latest Episodes",
        "category/hindi-dub" to "Hindi Dubbed & Multi-Audio",
        "category/action" to "Action & Adventure",
        "category/fantasy" to "Fantasy & Supernatural",
        "category/drama" to "Drama & Psychological",
        "category/romance" to "Romance & Slice of Life"
    )

    private fun resolveCleanUrl(rawUrl: String): String {
        return try {
            if (rawUrl.contains("p=")) {
                val b64 = rawUrl.substringAfter("p=").substringBefore("&")
                val decoded = String(Base64.getUrlDecoder().decode(b64.replace("&#038;", "&")), Charsets.UTF_8)
                if (decoded.startsWith("http")) decoded else fixUrl(rawUrl)
            } else {
                fixUrl(rawUrl)
            }
        } catch (_: Exception) {
            fixUrl(rawUrl)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetPath = request.data.trim().trim('/')
        val url = when {
            targetPath.isEmpty() && page == 1 -> "$mainUrl/"
            targetPath.isEmpty() -> "$mainUrl/page/$page/"
            page == 1 -> "$mainUrl/$targetPath/"
            else -> "$mainUrl/$targetPath/page/$page/"
        }

        val items = mutableListOf<SearchResponse>()
        try {
            val html = app.get(url, headers = defaultHeaders).text
            val doc = Jsoup.parse(html)
            
            val cards = doc.select(".animahd-card, article.post, .top10-post")
            for (card in cards) {
                val href = card.selectFirst("a")?.attr("href") ?: card.attr("href")
                if (href.isBlank()) continue
                
                val title = card.selectFirst(".animahd-card-title, .article__title a, .entry-title, h2, h3")?.text()?.trim()
                    ?: card.attr("title").ifBlank { "Featured Anime" }
                
                val poster = card.selectFirst("img")?.let { img ->
                    img.attr("src").ifBlank { img.attr("data-src") }
                }

                val cleanUrl = resolveCleanUrl(href)
                items.add(newTvSeriesSearchResponse(title, cleanUrl, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        } catch (_: Exception) {}

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encQuery"
        val results = mutableListOf<SearchResponse>()

        try {
            val html = app.get(searchUrl, headers = defaultHeaders).text
            val doc = Jsoup.parse(html)

            val articles = doc.select("article, .animahd-card")
            for (elem in articles) {
                val aTag = elem.selectFirst(".article__title a, .entry-title a, a.animahd-card, a") ?: continue
                val rawLink = aTag.attr("href").ifBlank { elem.attr("href") }
                if (rawLink.isBlank()) continue

                val title = aTag.text().ifBlank { elem.selectFirst("h2, h3")?.text() ?: "Unknown" }.trim()
                val poster = elem.selectFirst("img")?.let { img ->
                    img.attr("src").ifBlank { img.attr("data-src") }
                }

                val cleanLink = resolveCleanUrl(rawLink)
                results.add(newTvSeriesSearchResponse(title, cleanLink, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        } catch (_: Exception) {}

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)

        val titleRaw = doc.selectFirst("h1.entry-title, .ff-title, title")?.text() ?: "Anime Title"
        val cleanTitle = titleRaw.replace("Watch Online All Episodes HD", "", ignoreCase = true)
            .replace("- A Universe Of Anime", "", ignoreCase = true)
            .trim()

        val plot = doc.selectFirst(".entry-content p, .ff-synopsis, meta[property='og:description']")?.text()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

        val poster = doc.selectFirst(".animahd-poster img, .ff-poster-wrap img, .entry-content img[src*='upload']")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        } ?: doc.selectFirst("meta[property='og:image']")?.attr("content")

        val episodesList = mutableListOf<Episode>()
        val epElements = doc.select(".app-ep-row-item, a:has(.gdrive-ep-meta), a[href*='player/']")

        var epCounter = 1
        for (el in epElements) {
            val rawHref = el.attr("href")
            val seasonName = el.attr("data-season").ifBlank { "Season 1" }
            val seasonNum = Regex("""\d+""").find(seasonName)?.value?.toIntOrNull() ?: 1

            var fileId = el.selectFirst(".gdrive-ep-meta")?.attr("data-fileid")
                ?: el.attr("data-fileid")

            if (fileId.isNullOrBlank() && rawHref.contains("p=")) {
                try {
                    val b64 = rawHref.substringAfter("p=").substringBefore("&")
                    val decoded = String(Base64.getUrlDecoder().decode(b64.replace("&#038;", "&")), Charsets.UTF_8)
                    fileId = Regex("""file_id=([a-zA-Z0-9_-]+)""").find(decoded)?.groupValues?.get(1)
                } catch (_: Exception) {}
            }

            if (fileId.isNullOrBlank()) {
                fileId = Regex("""file_id=([a-zA-Z0-9_-]+)""").find(rawHref)?.groupValues?.get(1)
            }

            if (fileId.isNullOrBlank()) continue

            val epTitle = el.selectFirst(".gdrive-ep-meta div:first-child, .ff-ep-row-title")?.text()?.trim()
                ?: "Episode $epCounter"

            val epNum = Regex("""(?i)(?:E|Episode)\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: epCounter

            val epThumb = "https://drive.google.com/thumbnail?id=$fileId&sz=w400"

            episodesList.add(newEpisode("$mainUrl/player/?file_id=$fileId") {
                this.name = epTitle
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

        // SERVER 1: Direct 1080p Stream (Google UserContent) - Bypasses Worker 401 & "Stream Locked" Image
        try {
            val directStreamUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&authuser=0"
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name · Google Drive Direct [1080p]",
                    url = directStreamUrl,
                    referer = "https://drive.google.com/",
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
            foundLink = true
        } catch (_: Exception) {}

        // SERVER 2: UC Direct Stream (High Compatibility Mirror)
        try {
            val ucStreamUrl = "https://drive.google.com/uc?id=$fileId&export=download"
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name · Fast Stream Mirror",
                    url = ucStreamUrl,
                    referer = "https://drive.google.com/",
                    quality = Qualities.P720.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
            foundLink = true
        } catch (_: Exception) {}

        // SERVER 3: CloudStream Built-In Google Drive Extractor
        try {
            loadExtractor("https://drive.google.com/file/d/$fileId/preview", subtitleCallback, callback)
            foundLink = true
        } catch (_: Exception) {}

        // SERVER 4: AnimaHD Secure Destination Player
        try {
            val destUrl = "https://youranimewatchingdestination.animahd.online/?id=$fileId"
            val destHtml = app.get(
                destUrl,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to defaultHeaders["User-Agent"]!!
                )
            ).text

            val srcMatch = Regex("""<source[^>]+src=['"]([^'"]+)['"]""").find(destHtml)?.groupValues?.get(1)
            if (!srcMatch.isNullOrBlank() && !srcMatch.contains("error")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name · AnimaHD Worker Stream",
                        url = srcMatch,
                        referer = "https://youranimewatchingdestination.animahd.online/",
                        quality = Qualities.P1080.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                foundLink = true
            }
        } catch (_: Exception) {}

        return foundLink
    }
}

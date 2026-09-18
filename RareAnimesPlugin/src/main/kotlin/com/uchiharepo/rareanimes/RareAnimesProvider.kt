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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
        val url = "${request.data}$page/"
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

        // Filter out site logos
        if (cleaned.contains("Rare-Animes", ignoreCase = true) ||
            cleaned.contains("cropped-", ignoreCase = true) ||
            cleaned.contains("favicon", ignoreCase = true)
        ) {
            return null
        }
        return cleaned
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

        val contentHtml = document.selectFirst(".entry-content")?.html() ?: document.html()
        val episodes = parseEpisodes(document, contentHtml, url, poster)

        val isMovie = (episodes.isEmpty() && document.select("a[href*='codedew.com']").isNotEmpty()) ||
            url.contains("-movie-", ignoreCase = true) ||
            title.contains("Movie", ignoreCase = true)

        if (isMovie && episodes.isEmpty()) {
            val movieServers = document.select("a[href*='codedew.com']").mapNotNull { a ->
                val link = a.attr("href").trim()
                val label = a.text().trim().ifEmpty { "Stream" }
                if (link.isNotBlank()) ServerLink(label, link) else null
            }
            val movieData = toJson(RareAnimesEpisodeData(url, movieServers))
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, movieData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    private fun parseEpisodes(
        document: Document,
        contentHtml: String,
        postUrl: String,
        fallbackThumb: String?
    ): List<Episode> {
        val episodes = ArrayList<Episode>()
        val sections = contentHtml.split(Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE))

        for (section in sections) {
            val epMatch = Regex(
                """(?:Episode|Ep\.?)\s*0*(\d+)(?:\s*[-–:]\s*([^<\n]+))?""",
                RegexOption.IGNORE_CASE
            ).find(section) ?: continue

            val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
            val rawEpTitle = epMatch.groupValues.getOrNull(2)?.trim()?.replace(Regex("""<[^>]+>"""), "")?.trim()

            // Find all codedew links in this section
            val linkMatches = Regex(
                """<a\s+[^>]*href=["'](https?://codedew\.com/[^"']+)["'][^>]*>([\s\S]*?)</a>""",
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
                val epData = toJson(RareAnimesEpisodeData(postUrl, servers))
                val epName = if (!rawEpTitle.isNullOrBlank()) {
                    "Episode $epNum - $rawEpTitle"
                } else {
                    "Episode $epNum"
                }

                episodes.add(
                    newEpisode(epData) {
                        this.name = epName
                        this.episode = epNum
                        this.posterUrl = fallbackThumb
                    }
                )
            }
        }
        return episodes.sortedBy { it.episode }
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

        val servers = epData?.servers ?: listOf(ServerLink("Direct", data))
        val postUrl = epData?.postUrl ?: "$mainUrl/"
        var loadedAny = false

        for (server in servers) {
            try {
                val sName = server.name
                val sUrl = server.url

                when {
                    sName.contains("StreamBeta", ignoreCase = true) -> {
                        if (extractStreamBeta(sUrl, postUrl, callback)) loadedAny = true
                    }
                    sName.contains("Mega", ignoreCase = true) -> {
                        if (extractMega(sUrl, postUrl, subtitleCallback, callback)) loadedAny = true
                    }
                    sName.contains("MultiQuality", ignoreCase = true) || sName.contains("MultQuality", ignoreCase = true) -> {
                        if (extractMultiQuality(sUrl, postUrl, callback)) loadedAny = true
                    }
                    sName.contains("DLBeta", ignoreCase = true) -> {
                        if (extractDLBeta(sUrl, postUrl, callback)) loadedAny = true
                    }
                    else -> {
                        if (loadExtractor(sUrl, postUrl, subtitleCallback, callback)) {
                            loadedAny = true
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue to next server
            }
        }

        return loadedAny
    }

    private suspend fun extractStreamBeta(
        zipperUrl: String,
        postUrl: String,
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
        val html = streamBetaPage.text
        val jsonMatch = Regex("""let\s+playerSources\s*=\s*(\[[^;]+\]);""").find(html) ?: return false
        val sources = try {
            parseJson<List<StreamBetaSource>>(jsonMatch.groupValues[1])
        } catch (e: Exception) {
            return false
        }

        for (src in sources) {
            val streamUrl = src.streamUrl
            val directUrl = src.url
            val sourceName = src.name ?: "StreamBeta"

            if (!streamUrl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - StreamBeta ($sourceName)",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://codedew.com/"
                        this.headers = mapOf(
                            "Referer" to "https://codedew.com/",
                            "User-Agent" to USER_AGENT
                        )
                        this.quality = Qualities.P1080.value
                    }
                )
                success = true
            } else if (!directUrl.isNullOrBlank() && directUrl.contains("pixeldra.in")) {
                val fileId = directUrl.substringAfter("/u/").substringBefore("?").substringBefore("/")
                if (fileId.isNotBlank()) {
                    val apiDownload = "https://pixeldra.in/api/file/$fileId?download"
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$name - Pixeldrain ($sourceName)",
                            url = apiDownload,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://pixeldra.in/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    success = true
                }
            }
        }
        return success
    }

    private suspend fun extractMega(
        zipperUrl: String,
        postUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = if (zipperUrl.contains("ad_done=1")) zipperUrl else "$zipperUrl&ad_done=1"
        val response = app.get(
            targetUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to postUrl
            )
        )
        val megaMatch = Regex("""https?://mega\.nz/(?:file|embed)/[^\s"'<>]+""").find(response.text) ?: return false
        val megaUrl = megaMatch.value
        return loadExtractor(megaUrl, subtitleCallback, callback)
    }

    private suspend fun extractDLBeta(
        zipperUrl: String,
        postUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = if (zipperUrl.contains("ad_done=1")) zipperUrl else "$zipperUrl&ad_done=1"
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
                this.referer = "https://codedew.com/"
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }

    private suspend fun extractMultiQuality(
        zipperUrl: String,
        postUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(
            zipperUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to postUrl
            )
        )
        val html = response.text
        val embedUrl = Regex("""https?://argon\.razorshell\.space/embed/[A-Za-z0-9]+""").find(html)?.value ?: return false

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
        val fileMatch = Regex(""""file"\s*:\s*"([^"]+)"""").find(decodedConfigStr) ?: return false
        val m3u8Url = fileMatch.groupValues[1]

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "$name - MultiQuality (HLS Master)",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://argon.razorshell.space/"
                this.headers = mapOf(
                    "Referer" to "https://argon.razorshell.space/",
                    "User-Agent" to USER_AGENT
                )
                this.quality = Qualities.Unknown.value
            }
        )
        try {
            M3u8Helper.generateM3u8(
                source = this.name,
                streamUrl = m3u8Url,
                referer = "https://argon.razorshell.space/",
                quality = Qualities.Unknown.value,
                headers = mapOf(
                    "Referer" to "https://argon.razorshell.space/",
                    "User-Agent" to USER_AGENT
                ),
                name = "$name - MultiQuality"
            ).forEach { link ->
                callback.invoke(link)
            }
        } catch (e: Exception) {
            // Sub-stream generation optional
        }
        return true
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
            sb.toString()
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
}
